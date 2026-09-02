(ns seed-demo
  "Semente de DEMO (não é produto; só p/ ver o painel ao vivo end-to-end). Usa o PRÓPRIO código do projeto:
  cria uma Casa + um ator (identidade+vínculo) + uma sessão ABERTA e pública, e injeta eventos de presença/
  tribuna via os Repo — que emitem no shared.outbox; o relay do app servido (docker) os drena → projetor →
  CanalStore → SSE → browser. Rodar do host apontando p/ o Postgres em :5544 (DATABASE_URL).

  uso:
    DATABASE_URL=jdbc:postgresql://localhost:5544/oplenario MINIO_ENDPOINT=http://localhost:9100 \\
      clojure -X:dev seed-demo/base
    ... (abrir o browser na URL impressa) ...
    DATABASE_URL=... clojure -X:dev seed-demo/eventos
    DATABASE_URL=... clojure -X:dev seed-demo/materias      ; Portal do Cidadão (Task 1.4, Fatia A2.1 FE)
    DATABASE_URL=... clojure -X:dev seed-demo/esic          ; balcão e-SIC (Task 2.1, Fatia A2.2 FE)
    DATABASE_URL=... clojure -X:dev seed-demo/encarregado   ; balcão LGPD (Task 2.2, Fatia A2.2 FE)

  (rodar via `clojure -Sdeps '{:aliases {:seed {:extra-paths [\"demo\"]}}}' -X:seed seed-demo/<fn>` — fora
  do alias `:dev` porque `dev/user.clj` exige `component.repl` ausente, mesma nota de oplenario-fe-execucao)"
  (:require [com.stuartsierra.component :as component]
            [jsonista.core :as json]
            [oplenario.cadastros.db.comissao :as comissao-db]
            [oplenario.cadastros.db.estrutura :as estrutura]
            [oplenario.cadastros.db.referencia :as referencia]
            [oplenario.cadastros.db.vereador :as vereador-db]
            [oplenario.cadastros.relacoes.cadastro :as rel-cad]
            [oplenario.config :as config]
            [oplenario.identidade.db.identidade :as id]
            [oplenario.identidade.db.vinculo :as vinc]
            [oplenario.identidade.relacoes.identidade :as rel-id]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.components.idp :as idp]
            [oplenario.kernel.components.keycloak-idp :as keycloak-idp]
            [oplenario.kernel.outbox :as outbox]
            [oplenario.kernel.tempo :as tempo]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.legislativo.components.repositorio :as legislativo-repo]
            [oplenario.motor.components.registro-fatos :as rf]
            [oplenario.participacao.components.repositorio :as participacao-repo]
            [oplenario.participacao.controllers :as participacao-controllers]
            [oplenario.sessoes.components.repositorio :as repo]
            [oplenario.transparencia.components.repositorio :as transparencia-repo]
            [clojure.edn :as edn])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse HttpResponse$BodyHandlers)
           (java.time Instant LocalDate)))

(def ids-file "/demo-scratch/demo-ids.edn")

(defn- dv [ds] (let [r (mod (reduce + (map * ds (range (inc (count ds)) 1 -1))) 11)] (if (< r 2) 0 (- 11 r))))
(defn- cpf-valido [] (let [b (vec (repeatedly 9 #(rand-int 10))) d1 (dv b)] (apply str (concat b [d1 (dv (conj b d1))]))))

(defn- com-ds [f]
  (let [c (component/start (datasource/datasource (config/carregar)))]
    (try (f (:ds c)) (finally (component/stop c)))))

(defn- repo-sessoes [ds] (assoc (repo/repositorio) :datasource {:ds ds} :bus (outbox/bus)))
(defn- repo-legislativo [ds] (legislativo-repo/->RepoLegislativoPg {:ds ds} (outbox/bus)))
(defn- repo-participacao [ds] (participacao-repo/->RepoParticipacaoPg {:ds ds} (outbox/bus)))
(defn- repo-transparencia [ds] (transparencia-repo/->RepoTransparenciaPg {:ds ds}))

(defn base [_]
  (com-ds
   (fn [ds]
     (let [ente (random-uuid) ident (random-uuid)
           r    (repo-sessoes ds)]
       ;; município (FK do ente) — idempotente p/ re-runs
       (try (referencia/inserir-municipio! ds {:codigo-ibge "2304400" :nome "Fortaleza" :uf "CE" :capital true :populacao 2703391})
            (catch Exception _ nil))
       ;; identidade supratenant + ente + vínculo (o ator)
       (id/inserir! ds {:id ident :cpf (cpf-valido) :nome "Secretária da Mesa"})
       (tenancy/com-tenant* ds ente
         (fn [tx]
           (estrutura/inserir-ente! tx {:ente-id ente :municipio-ibge "2304400" :nome-oficial "Câmara Municipal de Fortaleza"})
           (vinc/criar! tx {:id (random-uuid) :ente-id ente :identidade-id ident :tipo "servidor"})
           ;; O vinculo sozinho NAO abre as telas internas — a autorizacao e' por PAPEL, e o dev-token
           ;; so' carrega o que esta escrito nele. Sem isto, a URL que este proprio seed imprime abaixo
           ;; cai em "Acesso restrito" em /pauta-convocacao, /paineis/mesa e afins: a semente existe
           ;; para demonstrar as telas e imprimia um endereco que nao as abre.
           (vinc/adicionar-papel! tx {:id (random-uuid) :ente-id ente :identidade-id ident :papel "secretario"})))
       ;; sessão ordinária (transmite-publica=true) -> abrir
       (let [sid (:id (repo/agendar-sessao! r ente {:id (random-uuid) :sessao-legislativa-id (random-uuid)
                                                    :tipo-sessao "ordinaria" :modalidade "presencial"}))]
         (repo/transicionar-sessao! r ente {:id sid :para "aberta" :updated-by ident :lock-version 0})
         (spit ids-file (pr-str {:ente ente :ident ident :sessao sid}))
         ;; `papeis` VAI no token: o IdP de dev confia nos claims do proprio token (nao consulta o banco),
         ;; entao um token sem papeis navega como se a identidade nao tivesse nenhum — mesmo com o papel
         ;; gravado acima. E' o mesmo formato que `seed-demo/vereador` ja imprime.
         (let [token (format "{\"identidade-id\":\"%s\",\"ente-id\":\"%s\",\"papeis\":[\"secretario\"]}" ident ente)]
           (println "\n=== DEMO PRONTA ===")
           (println "sessao-id:" sid)
           (println "token    :" token)
           (println "URL      : http://localhost:3000/sessoes/" (str sid) "/plenario?token=" (java.net.URLEncoder/encode token "UTF-8"))
           (println "===================\n")))))))

(defn eventos [_]
  (com-ds
   (fn [ds]
     (let [{:keys [ente sessao]} (edn/read-string (slurp ids-file))
           r        (repo-sessoes ds)
           vers     (repeatedly 7 random-uuid)]
       (println "injetando presenças (entrada) ...")
       (doseq [v vers]
         ;; `ocorrido-em` e `agora` sao o MESMO instante lido uma vez: o gate de janela recusa hora futura, e
         ;; duas leituras separadas do relogio poderiam inverter a ordem entre elas.
         (let [t (Instant/now)]
           (repo/registrar-presenca! r ente {:id (random-uuid) :sessao-id sessao :vereador-id v
                                             :tipo "entrada" :modalidade "plenario" :fonte "manual_secretaria"
                                             :ocorrido-em t :agora t :created-by v}))
         (Thread/sleep 600))
       (println "inscrição + início de fala (tribuna) ...")
       (let [orad (first vers)]
         (repo/inscrever! r ente {:id (random-uuid) :sessao-id sessao :vereador-id (second vers)
                                  :origem-inscricao "pre_sessao_app" :fase "ordem_do_dia" :created-by orad})
         (repo/iniciar-fala! r ente {:id (random-uuid) :sessao-id sessao :orador-id orad :tipo-fala "principal"
                                     :fase "ordem_do_dia" :iniciou-em (Instant/now) :created-by orad}))
       (println "feito — o painel deve mostrar quórum 7 + tribuna com cronômetro correndo.")))))

;; ---------- Task 1.4 (Fatia A2.1, Portal do Cidadão FE) — matérias variadas p/ o portal público ----------

(def ^:private rito-fixture-transicoes
  "Rito de 5 passos [FIXTURE] — mesmo disclaimer de tramitacao_db_test/portal_test.clj: NÃO é regulamento
  real de nenhuma câmara (o regimento real é [GAP], §22.7.5). Espelha o vocabulário ilustrativo que
  src/lib/tramitacao-vista.ts (FE) já documenta como fixture de demo, só p/ a AzulejoFaixa ter estágios
  reais p/ exercitar no navegador."
  [["protocolada" "em_comissoes"] ["em_comissoes" "em_pauta"]
   ["em_pauta" "segundo_turno"] ["segundo_turno" "em_sancao"] ["em_sancao" "aprovada"]])

(def ^:private materias-seed
  "6 proposições variadas (tipo + quantos passos avança no rito, ou arquivamento direto) — o suficiente
  p/ a home do portal (destaque + mais-em-tramitação, Task 1.3) mostrar estágios DIFERENTES da faixa."
  [{:tipo "projeto_lei" :ementa "Cria o Programa Municipal de Hortas Comunitárias." :autor-texto "Ver.ª Helena Matos" :avancos 3}
   {:tipo "projeto_lei" :ementa "Arborização viária do entorno da Av. Bezerra de Menezes" :autor-texto "Ver. João Pontes" :avancos 2}
   {:tipo "projeto_lei_complementar" :ementa "Altera o Código de Posturas do Município" :autor-texto "Ver.ª Cida Ramos" :avancos 1}
   {:tipo "projeto_lei" :ementa "Denominação de via pública no Bairro Messejana" :autor-texto "Ver. Marcos Frota" :avancos 5}
   {:tipo "indicacao" :ementa "Solicita reparo de iluminação pública na Praça da Gentilândia" :autor-texto "Ver.ª Helena Matos" :avancos 0
    :objeto-indicacao "Reparo de iluminação pública"}
   {:tipo "requerimento" :ementa "Requer informações sobre o Programa de Compostagem" :autor-texto "Ver. Marcos Frota" :arquivar true
    :tipo-requerimento "informacao"}])

(defn materias
  "Semente do PORTAL DO CIDADÃO (Task 1.4, Fatia A2.1 FE): protocola as `materias-seed` sob o MESMO ente
  da demo (ids-file de `base` — rodar `base` primeiro) e avança cada uma um número diferente de passos
  no rito [FIXTURE] `rito-fixture-transicoes`. Usa o Repo de `legislativo` de VERDADE — `protocolar!`
  emite `proposicao.protocolada`, `transicionar!` emite `proposicao.transicionou` — o MESMO caminho que
  a rota pública GET /portal/casa/:ente/materias lê (via a projeção real de `transparencia`, drenada pelo
  relay do app servido em docker, igual à nota de topo deste ns). NÃO é idempotente (protocola matérias
  NOVAS a cada chamada, como `eventos` já é p/ presença/tribuna) — rodar uma vez por demo fresca."
  [_]
  (com-ds
   (fn [ds]
     (let [{:keys [ente ident]} (edn/read-string (slurp ids-file))
           repo (repo-legislativo ds)
           reg  (component/start (rf/registro-fatos (merge rel-cad/relacoes rel-id/relacoes)))
           tid  (random-uuid)]
       (legislativo-repo/criar-template! repo ente
         {:id tid :chave "rito_fixture_portal" :versao 1
          :nome "Rito [FIXTURE] — demo do portal" :estado-inicial "protocolada"})
       (doseq [ch ["protocolada" "em_comissoes" "em_pauta" "segundo_turno" "em_sancao" "aprovada" "arquivada"]]
         (legislativo-repo/criar-estado! repo ente
           {:id (random-uuid) :template-id tid :chave ch :nome ch
            :terminal (boolean (#{"aprovada" "arquivada"} ch))}))
       (doseq [[de para] rito-fixture-transicoes]
         (legislativo-repo/criar-transicao! repo ente
           {:id (random-uuid) :template-id tid :de-estado de :para-estado para :gatilho "avancar" :guarda nil}))
       (legislativo-repo/criar-transicao! repo ente
         {:id (random-uuid) :template-id tid :de-estado "protocolada" :para-estado "arquivada"
          :gatilho "arquivar" :guarda nil})

       (doseq [{:keys [tipo ementa autor-texto avancos arquivar objeto-indicacao tipo-requerimento]} materias-seed]
         (let [{pid :id} (legislativo-repo/protocolar! repo ente
                           {:id (random-uuid) :ente-id ente :tipo tipo :ano 2026 :uf "CE"
                            :municipio-nome "Fortaleza" :ementa ementa :autor-tipo "vereador"
                            :autor-texto autor-texto :objeto-indicacao objeto-indicacao
                            :tipo-requerimento tipo-requerimento})]
           (if arquivar
             (legislativo-repo/transicionar! repo ente reg
               {:proposicao-id pid :template-id tid :gatilho "arquivar" :updated-by ident})
             (dotimes [_ (or avancos 0)]
               (legislativo-repo/transicionar! repo ente reg
                 {:proposicao-id pid :template-id tid :gatilho "avancar" :updated-by ident})))))

       (println "\n=== MATÉRIAS DA DEMO PRONTAS ===")
       (println "URL: http://localhost:3000/portal/casa/" (str ente))
       (println "=================================\n")))))

;; ---------- Task 2.1/2.2 (Fatia A2.2, Portal do Cidadão FE) — e-SIC + Encarregado/DPO ----------

(defn esic
  "Semente do BALCÃO E-SIC (Task 2.1, Fatia A2.2 FE): protocola 1 pedido de acesso à informação sob o
  MESMO ente da demo (ids-file de `base` — rodar `base` primeiro), via o controller REAL
  (`participacao.controllers/protocolar-pedido`) — o MESMO caminho que POST /portal/esic/pedidos usa. O
  protocolo devolvido (formato ESIC-<ano>-<seq>, `participacao/logic.clj`) é o que
  GET /portal/casa/:ente/esic/acompanhar/:protocolo lê de volta — exercita o balcão e-SIC do FE com dado
  real. NÃO idempotente (protocola um pedido NOVO a cada chamada, como `eventos`/`materias` já são)."
  [_]
  (com-ds
   (fn [ds]
     (let [{:keys [ente]} (edn/read-string (slurp ids-file))
           repo       (repo-participacao ds)
           cidadao-id (random-uuid)]
       (id/inserir! ds {:id cidadao-id :cpf (cpf-valido) :nome "Cidadão Demo"})
       (let [{:keys [protocolo]}
             (participacao-controllers/protocolar-pedido repo (tempo/relogio-sistema)
               {:ente-id ente :identidade-id cidadao-id}
               {:assunto "Contratos de tecnologia vigentes"
                :descricao "Cópia dos contratos de tecnologia vigentes e seus valores"})]
         (println "\n=== PEDIDO E-SIC DA DEMO PRONTO ===")
         (println "protocolo:" protocolo)
         (println "URL      : http://localhost:3000/portal/casa/" (str ente) " (busque por" protocolo "no balcão e-SIC)")
         (println "====================================\n"))))))

;; ---------- Task 3.2 (Fatia A2.3, Portal do Cidadão FE) — 1 comentário aprovado p/ a ficha ----------

(defn comentario
  "Semente da FICHA PÚBLICA (Task 3.2, Fatia A2.3 FE): protocola 1 comentário via o controller REAL
  (`comentar!`) sobre a matéria 'Hortas comunitárias' (a mesma que `materias` protocola primeiro, sempre o
  1º item da lista — o destaque da home) e já o MODERA como aprovado (`moderar-comentario!`) — o MESMO
  caminho que POST /portal/materias/:id/comentarios + POST /comentarios/:id/moderar usam. Exercita
  GET /portal/casa/:ente/materias/:id/comentarios (lista PÚBLICA, só aprovados) com dado real. Rodar
  `base` + `materias` primeiro. NÃO idempotente (comenta de novo a cada chamada, como as demais sementes
  não-upsert deste ns)."
  [_]
  (com-ds
   (fn [ds]
     (let [{:keys [ente]} (edn/read-string (slurp ids-file))
           repo-part      (repo-participacao ds)
           repo-transp    (repo-transparencia ds)
           cidadao-id     (random-uuid)
           moderador-id   (random-uuid)
           ;; a mesma que `materias` protocola primeiro (ementa "Hortas comunitárias") — é o destaque da
           ;; home (escolherDestaque, FE); comentar nela exercita o click-through inteiro home->ficha.
           alvo           (->> (transparencia-repo/listar-materias repo-transp ente #{})
                                (filter #(re-find #"[Hh]ortas" (:ementa %)))
                                first)
           pid            (:proposicao-id alvo)]
       (when-not pid
         (throw (ex-info "materia 'Hortas comunitarias' nao encontrada — rode seed-demo/materias primeiro" {})))
       (id/inserir! ds {:id cidadao-id :cpf (cpf-valido) :nome "Cidadão Demo (comentário)"})
       (id/inserir! ds {:id moderador-id :cpf (cpf-valido) :nome "Servidora moderadora"})
       (let [{comentario-id :id}
             (participacao-controllers/comentar! repo-part {:ente-id ente :identidade-id cidadao-id} pid
               {:corpo "Apoio demais. No meu bairro tem terrenos abandonados que virariam ótimas hortas."})]
         (participacao-controllers/moderar-comentario! repo-part (tempo/relogio-sistema)
           {:ente-id ente :identidade-id moderador-id} comentario-id {:acao "aprovado"})
         (println "\n=== COMENTÁRIO DA DEMO PRONTO (aprovado) ===")
         (println "proposicao-id:" pid)
         (println "URL          : http://localhost:3000/portal/casa/" (str ente) "/materias/" (str pid))
         (println "=============================================\n"))))))

(defn encarregado
  "Semente do BALCÃO LGPD (Task 2.2, Fatia A2.2 FE): define o Encarregado/DPO do MESMO ente da demo via o
  controller REAL (`participacao.controllers/definir-encarregado!`) — o MESMO caminho que
  PUT /lgpd/encarregado usa. IDEMPOTENTE (upsert: 1 linha por ente, pode rodar de novo p/ trocar o
  contato) — ao contrário de `esic`/`materias`."
  [_]
  (com-ds
   (fn [ds]
     (let [{:keys [ente ident]} (edn/read-string (slurp ids-file))
           repo (repo-participacao ds)]
       (participacao-controllers/definir-encarregado! repo {:ente-id ente :identidade-id ident}
         {:nome "Mariana Couto" :rotulo "Encarregada de Dados (DPO)"
          :email "encarregado.dados@cmfor.ce.gov.br"})
       (println "\n=== ENCARREGADO/DPO DA DEMO PRONTO ===")
       (println "URL: http://localhost:3000/portal/casa/" (str ente))
       (println "=======================================\n")))))

;; ---------- fe-19-cadastro-vereadores — 5 vereadores + mandatos + comissões (lista/ficha) ----------

(defn vereadores
  "Semente do CADASTRO DE VEREADORES (branch fe-19-cadastro-vereadores): popula GET /cadastros/vereadores
  (lista) e GET /cadastros/vereadores/:id (ficha) com dado REAL sob o MESMO ente da demo (ids-file de
  `base` — rodar `base` primeiro), via os insert fns REAIS de `cadastros.db.{estrutura,vereador,comissao}`
  (o mesmo caminho que os controllers leem). Cria: 1 legislatura vigente (reusa se o ente já tiver uma,
  como `vereador` já faz) cobrindo hoje · 5 vereadores (\"Ana Ribeiro\", \"Bruno Sales\", \"Carla Nunes\",
  \"Diego Alves\", \"Elena Costa\") · 4 mandatos (3 'vigente' com partidos diferentes PT/PSDB/PL + 1
  'licenciado' PDT; a 5ª — Elena Costa — fica SEM mandato de propósito, p/ exercitar o caso de
  estado-mandato nil na lista) · 1 comissão 'mesa' (Mesa Diretora) com Ana Ribeiro 'presidente' · 1
  comissão 'permanente' (Comissão de Constituição e Justiça) com Bruno/Carla/Diego membros e Bruno
  'presidente' (p/ a ficha mostrar o destaque de presidente + uma contagem de comissões real).

  Também concede à identidade `ident` da demo (\"Secretária da Mesa\", criada por `base` só com vínculo
  tipo 'servidor', sem papel RBAC nenhum) o papel estático 'secretario' via
  `identidade.db.vinculo/adicionar-papel!` — as rotas de `cadastros/diplomat/http/in` exigem
  `it/exige-papel \"secretario\"` (autorização GROSSA, `kernel/autorizacao`) e o papel vem do SNAPSHOT em
  `identidade.usuario_papel` (resolvido em `resolver-sessao`), NÃO do texto do token nem do `tipo` do
  vínculo — sem este `adicionar-papel!`, GET /cadastros/vereadores devolveria 403 mesmo autenticado como o
  ator de `base`. `adicionar-papel!` é idempotente (ON CONFLICT DO NOTHING), então esta parte é segura de
  rodar de novo; o resto (vereadores/mandatos/comissões) NÃO é — protocola linhas NOVAS a cada chamada,
  como `materias`/`esic`/`vereador` já são. Rodar uma vez por demo fresca."
  [_]
  (com-ds
   (fn [ds]
     (let [{:keys [ente ident]} (edn/read-string (slurp ids-file))
           hoje   (LocalDate/of 2025 1 1)
           nomes  ["Ana Ribeiro" "Bruno Sales" "Carla Nunes" "Diego Alves" "Elena Costa"]
           ver-ids (vec (repeatedly 5 random-uuid))
           [id-ana id-bruno id-carla id-diego _id-elena] ver-ids]
       (tenancy/com-tenant* ds ente
         (fn [tx]
           ;; papel RBAC 'secretario' p/ o ator de `base` passar o gate das rotas de cadastros/vereadores
           (vinc/adicionar-papel! tx {:id (random-uuid) :ente-id ente :identidade-id ident :papel "secretario"})

           (let [leg-existente (estrutura/legislatura-vigente tx ente)
                 leg-id (or (:id leg-existente)
                            (let [novo-id (random-uuid)]
                              (estrutura/inserir-legislatura! tx
                                {:id novo-id :ente-id ente :numero 19 :ano-inicio 2025 :ano-fim 2028 :vigente true})
                              novo-id))]

             (doseq [[nome vid] (map vector nomes ver-ids)]
               (vereador-db/inserir! tx {:id vid :ente-id ente :nome nome :nome-parlamentar nome}))

             ;; mandatos: 3 vigentes (partidos diferentes) + 1 licenciado; Elena Costa fica sem mandato
             (doseq [[vid partido] [[id-ana "PT"] [id-bruno "PSDB"] [id-carla "PL"]]]
               (vereador-db/inserir-mandato! tx
                 {:id (random-uuid) :ente-id ente :vereador-id vid :legislatura-id leg-id
                  :partido partido :estado "vigente" :natureza "titular" :vigencia-inicio hoje}))
             (vereador-db/inserir-mandato! tx
               {:id (random-uuid) :ente-id ente :vereador-id id-diego :legislatura-id leg-id
                :partido "PDT" :estado "licenciado" :natureza "titular" :vigencia-inicio hoje})

             ;; Mesa Diretora — Ana Ribeiro presidente
             (let [mesa-id (random-uuid)]
               (comissao-db/inserir! tx {:id mesa-id :ente-id ente :nome "Mesa Diretora" :tipo "mesa"
                                         :legislatura-id leg-id :vigencia-inicio hoje})
               (comissao-db/inserir-cargo! tx {:id (random-uuid) :ente-id ente :comissao-id mesa-id
                                               :vereador-id id-ana :cargo "presidente" :vigencia-inicio hoje}))

             ;; Comissão permanente — CCJ: Bruno (presidente) + Carla + Diego membros
             (let [ccj-id (random-uuid)]
               (comissao-db/inserir! tx {:id ccj-id :ente-id ente :nome "Comissão de Constituição e Justiça"
                                         :tipo "permanente" :legislatura-id leg-id :vigencia-inicio hoje})
               (doseq [vid [id-bruno id-carla id-diego]]
                 (comissao-db/inserir-membro! tx {:id (random-uuid) :ente-id ente :comissao-id ccj-id
                                                  :vereador-id vid :vigencia-inicio hoje}))
               (comissao-db/inserir-cargo! tx {:id (random-uuid) :ente-id ente :comissao-id ccj-id
                                               :vereador-id id-bruno :cargo "presidente" :vigencia-inicio hoje})))))
       ;; `papeis` no token pelo MESMO motivo de `base`: esta funcao GRAVA o papel 'secretario' acima
       ;; (sem ele GET /cadastros/vereadores e' 403), mas o dev-token nao consulta o banco — le' os claims
       ;; do proprio token. Sem `papeis` aqui, a URL impressa logo abaixo caia em "Acesso restrito"
       ;; justamente na tela que esta semente existe para abrir.
       (let [token (format "{\"identidade-id\":\"%s\",\"ente-id\":\"%s\",\"papeis\":[\"secretario\"]}" ident ente)]
         (println "\n=== VEREADORES DA DEMO PRONTOS (fe-19-cadastro-vereadores) ===")
         (println "ente-id  :" (str ente))
         (println "token    :" token)
         (println "Ana Ribeiro (PT, vigente, presidente da Mesa) / Bruno Sales (PSDB, vigente, presidente da CCJ) /")
         (println "Carla Nunes (PL, vigente, membro CCJ) / Diego Alves (PDT, licenciado, membro CCJ) /")
         (println "Elena Costa (sem mandato)")
         (println "URL      : http://localhost:3000/cadastros/vereadores?token=" (java.net.URLEncoder/encode token "UTF-8"))
         (println "================================================================\n"))))))

;; ---------- Task 7 (Onda C1, home do vereador) — vereadora com mandato + proposição vinculados ----------

(defn vereador
  "Semente da HOME DO VEREADOR (Onda C1, Task 7): cria uma identidade+cadastro de vereador de VERDADE (não
  só `autor-texto`, como as `materias-seed` fazem) sob o MESMO ente da demo (ids-file de `base` — rodar
  `base` primeiro): identidade -> cadastros.vereador -> legislatura vigente (reusa se o ente já tiver uma,
  senão cria uma nova 2025-2028) -> mandato vigente -> vínculo (`tipo` 'vereador') + papel RBAC estático
  'vereador' -> protocola 1 proposição com `:autor-tipo 'vereador'`/`:autor-id` = o vereador cadastrado — a
  PRIMEIRA semente deste ns a linkar de verdade (`materias-seed` só seta `autor-texto`, sem `autor-id`).
  SEM parecer/ciência nesta rodada (opcional do plano C1, deferido por tempo — `emitir-parecer!` exige
  template+comissão+registro de fatos, fora do escopo enxuto desta semente); a home mostra ':ciencias'
  vazio, o que é honesto (nenhuma ciência pendente ainda), não um [GAP] fingido. NÃO idempotente (protocola
  proposição NOVA e cria vereador NOVO a cada chamada, como `materias`/`esic` já são)."
  [_]
  (com-ds
   (fn [ds]
     (let [{:keys [ente]} (edn/read-string (slurp ids-file))
           ident  (random-uuid)
           ver-id (random-uuid)]
       (id/inserir! ds {:id ident :cpf (cpf-valido) :nome "Vereadora Demo"})
       (tenancy/com-tenant* ds ente
         (fn [tx]
           (vereador-db/inserir! tx {:id ver-id :ente-id ente :identidade-id ident
                                     :nome "Vereadora Demo" :nome-parlamentar "Vereadora Demo"})
           (let [leg-id (or (:id (estrutura/legislatura-vigente tx ente))
                             (let [novo-id (random-uuid)]
                               (estrutura/inserir-legislatura! tx
                                 {:id novo-id :ente-id ente :numero 19 :ano-inicio 2025 :ano-fim 2028 :vigente true})
                               novo-id))]
             (vereador-db/inserir-mandato! tx
               {:id (random-uuid) :ente-id ente :vereador-id ver-id :legislatura-id leg-id
                :partido "PDT" :estado "vigente" :natureza "titular"
                :vigencia-inicio (LocalDate/of 2025 1 1)}))
           (vinc/criar! tx {:id (random-uuid) :ente-id ente :identidade-id ident :tipo "vereador"})
           (vinc/adicionar-papel! tx {:id (random-uuid) :ente-id ente :identidade-id ident :papel "vereador"})))
       (let [repo   (repo-legislativo ds)
             {prop-id :id}
             (legislativo-repo/protocolar! repo ente
               {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026 :uf "CE"
                :municipio-nome "Fortaleza" :ementa "Institui a Semana Municipal do Voluntariado"
                :autor-tipo "vereador" :autor-id ver-id})
             token (format "{\"identidade-id\":\"%s\",\"ente-id\":\"%s\",\"papeis\":[\"vereador\"]}" ident ente)]
         (println "\n=== VEREADORA DA DEMO PRONTA ===")
         (println "vereador-id  :" ver-id)
         (println "proposicao-id:" prop-id)
         (println "token        :" token)
         (println "URL          : http://localhost:3000/vereador?token=" (java.net.URLEncoder/encode token "UTF-8"))
         (println "obs.: sem parecer/ciência nesta semente — ':ciencias' vazio (honesto, não [GAP] fingido)")
         (println "=================================\n"))))))

;; ---------- Onda C2 (pauta-convocacao) — secretário com sessão agendada + pauta ----------

(defn secretario
  "Semente ad-hoc p/ ver /pauta-convocacao end-to-end: identidade+vínculo com papel RBAC 'secretario' (a
  PRIMEIRA vez que este papel é semeado neste ns — nenhuma outra fn cria) + uma sessão em estado 'agendada'
  (agendar-sessao! sem transicionar) com 2 itens de pauta (1 expediente 'leitura', 1 ordem_do_dia
  'proposicao', referenciando uma proposição real recém-protocolada). Ente NOVO, self-contained (não reusa
  ids-file). NÃO idempotente."
  [_]
  (com-ds
   (fn [ds]
     (let [ente (random-uuid) ident (random-uuid)
           r    (repo-sessoes ds)
           lr   (repo-legislativo ds)]
       (try (referencia/inserir-municipio! ds {:codigo-ibge "2304400" :nome "Fortaleza" :uf "CE" :capital true :populacao 2703391})
            (catch Exception _ nil))
       (id/inserir! ds {:id ident :cpf (cpf-valido) :nome "Secretária Legislativa Demo"})
       (tenancy/com-tenant* ds ente
         (fn [tx]
           (estrutura/inserir-ente! tx {:ente-id ente :municipio-ibge "2304400" :nome-oficial "Câmara Municipal de Fortaleza"})
           (vinc/criar! tx {:id (random-uuid) :ente-id ente :identidade-id ident :tipo "servidor"})
           (vinc/adicionar-papel! tx {:id (random-uuid) :ente-id ente :identidade-id ident :papel "secretario"})))
       (let [{prop-id :id}
             (legislativo-repo/protocolar! lr ente
               {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026 :uf "CE"
                :municipio-nome "Fortaleza" :ementa "Cria o programa de arborização de vias e praças"
                :autor-tipo "vereador" :autor-texto "Ver.ª Helena Matos"})
             sid (:id (repo/agendar-sessao! r ente {:id (random-uuid) :sessao-legislativa-id (random-uuid)
                                                    :tipo-sessao "ordinaria" :modalidade "presencial"
                                                    :agendada-para (.plusSeconds (Instant/now) (* 5 86400))}))]
         (repo/adicionar-item-na-sessao! r ente {:id (random-uuid) :sessao-id sid :fase "expediente"
                                                 :tipo-item "leitura"
                                                 :texto-descricao "Leitura e aprovação da ata da sessão anterior"
                                                 :created-by ident})
         (repo/adicionar-item-na-sessao! r ente {:id (random-uuid) :sessao-id sid :fase "ordem_do_dia"
                                                 :tipo-item "proposicao" :proposicao-id prop-id
                                                 :created-by ident})
         (let [token (format "{\"identidade-id\":\"%s\",\"ente-id\":\"%s\",\"papeis\":[\"secretario\"]}" ident ente)]
           (println "\n=== SECRETÁRIA DA DEMO PRONTA (pauta-convocacao) ===")
           (println "sessao-id (agendada):" sid)
           (println "token        :" token)
           (println "URL          : http://localhost:3000/pauta-convocacao?token=" (java.net.URLEncoder/encode token "UTF-8"))
           (println "======================================================\n")))))))

(defn login-kc
  "T17 (Onda D Slice 2): provisiona uma Casa completa + o realm Keycloak + um usuario com o atributo
  identidade-id, p/ PROVAR o login PKCE ao vivo no browser. Rodar com KEYCLOAK_BASE_URL=http://keycloak:8080
  (o container efemero do seed alcanca o KC pelo DNS do compose, nao por localhost). Cria: ente + identidade
  + vinculo servidor + papeis (vereador+secretario) + sessao ABERTA/publica; provisiona o realm (declara o
  atributo identidade-id + cria o client publico oplenario-web PKCE) + cria o usuario KC (username =
  identidade-id). A SENHA e' setada num passo separado via admin-API (o kc-user-id impresso abaixo)."
  [_]
  (com-ds
   (fn [ds]
     (let [ente (random-uuid) ident (random-uuid)
           r    (repo-sessoes ds)
           idp  (component/start (keycloak-idp/keycloak-idp (:keycloak (config/carregar))))]
       (try
         (try (referencia/inserir-municipio! ds {:codigo-ibge "2304400" :nome "Fortaleza" :uf "CE" :capital true :populacao 2703391})
              (catch Exception _ nil))
         (id/inserir! ds {:id ident :cpf (cpf-valido) :nome "Helena Matos"})
         (tenancy/com-tenant* ds ente
           (fn [tx]
             (estrutura/inserir-ente! tx {:ente-id ente :municipio-ibge "2304400" :nome-oficial "Câmara Municipal de Fortaleza"})
             (vinc/criar! tx {:id (random-uuid) :ente-id ente :identidade-id ident :tipo "servidor"})
             (vinc/adicionar-papel! tx {:id (random-uuid) :ente-id ente :identidade-id ident :papel "vereador"})
             (vinc/adicionar-papel! tx {:id (random-uuid) :ente-id ente :identidade-id ident :papel "secretario"})))
         (let [sid (:id (repo/agendar-sessao! r ente {:id (random-uuid) :sessao-legislativa-id (random-uuid)
                                                      :tipo-sessao "ordinaria" :modalidade "presencial"}))]
           (repo/transicionar-sessao! r ente {:id sid :para "aberta" :updated-by ident :lock-version 0})
           (idp/provisionar-realm! idp ente)
           (let [{:keys [keycloak-user-id]} (idp/criar-usuario! idp ente {:identidade-id ident
                                                                          :nome "Helena Matos"
                                                                          :email "helena@example.org"})
                 realm (str (:realm-prefixo (:keycloak (config/carregar))) ente)]
             (println "\n=== LOGIN KC PRONTO (T17) ===")
             (println "ente-id     :" (str ente))
             (println "sessao-id   :" (str sid))
             (println "realm       :" realm)
             (println "kc-user-id  :" keycloak-user-id)
             (println "username    :" (str ident) " (= identidade-id)")
             (println "ENTRAR      : http://localhost:3000/entrar/" (str ente))
             (println "PLENARIO    : http://localhost:3000/sessoes/" (str sid) "/plenario")
             (println "=============================\n")))
         (finally (component/stop idp)))))))

;; ---------- Onda D Slice 5 (identidade do vereador, Tier 2) — admin_ente concede acesso a um vereador ----------

;; --- helpers de admin-API do Keycloak (mesmo racional de `setar-senha-teste!`/`limpar-required-actions-teste!`
;; em test/keycloak/oplenario/keycloak/ponta_a_ponta_test.clj:85-108 — `demo/` nao pode requerer `test/`,
;; entao replicados aqui verbatim na forma, so' parametrizados por base-url/credenciais em vez de hardcode).
(defn- admin-token-kc! [^HttpClient http base-url admin-usuario admin-senha]
  (let [corpo (str "grant_type=password&client_id=admin-cli&username=" admin-usuario "&password=" admin-senha)
        req (-> (HttpRequest/newBuilder) (.uri (URI/create (str base-url "/realms/master/protocol/openid-connect/token")))
                (.header "Content-Type" "application/x-www-form-urlencoded")
                (.POST (HttpRequest$BodyPublishers/ofString corpo)) (.build))
        resp (.send http req (HttpResponse$BodyHandlers/ofString))]
    (get (json/read-value (.body resp) json/keyword-keys-object-mapper) :access_token)))

(defn- setar-senha-kc! [^HttpClient http base-url token realm kc-user-id senha]
  (.send http (-> (HttpRequest/newBuilder)
                  (.uri (URI/create (str base-url "/admin/realms/" realm "/users/" kc-user-id "/reset-password")))
                  (.header "Authorization" (str "Bearer " token)) (.header "Content-Type" "application/json")
                  (.PUT (HttpRequest$BodyPublishers/ofString
                         (str "{\"type\":\"password\",\"value\":\"" senha "\",\"temporary\":false}")))
                  (.build))
            (HttpResponse$BodyHandlers/ofString)))

(defn- limpar-required-actions-kc! [^HttpClient http base-url token realm kc-user-id]
  (let [resp (.send http (-> (HttpRequest/newBuilder)
                             (.uri (URI/create (str base-url "/admin/realms/" realm "/users/" kc-user-id)))
                             (.header "Authorization" (str "Bearer " token)) (.header "Content-Type" "application/json")
                             (.PUT (HttpRequest$BodyPublishers/ofString "{\"requiredActions\":[]}"))
                             (.build))
                       (HttpResponse$BodyHandlers/ofString))]
    (when-not (= 204 (.statusCode resp))
      (throw (ex-info "seed-demo/slice5: falha ao limpar required-actions do admin (infra)"
                       {:status (.statusCode resp) :corpo (.body resp)})))))

(defn slice5
  "Semente da Onda D Slice 5 (identidade do vereador, Tier 2): planta o TERRENO p/ provar AO VIVO no browser
  o fluxo em que um admin_ente concede acesso a um vereador JA' CADASTRADO (POST /identidade/acessos,
  `identidade.diplomat.http.in/conceder-acesso-handler`) — o vereador recebe convite por e-mail (Mailpit em
  dev) e registra passkey pelo fluxo REAL do Keycloak (§22.5.2 eixo F). A PROPRIA concessao e' o gesto que
  se faz AO VIVO na UI (escolher um vereador na lista + confirmar) — este seed nao a antecipa. Cria:
    - ente 'Câmara Municipal de Fortaleza' (município Fortaleza, idempotente igual `login-kc`/`vereadores`);
    - identidade ADMIN 'Helena Matos', vínculo tipo 'servidor', com DOIS papéis RBAC: 'admin_ente' (abre
      POST /identidade/acessos, a rota que concede) + 'secretario' (abre GET /cadastros/vereadores, a lista
      onde o admin escolhe o alvo — sem este papel a lista devolveria 403, mesmo racional de `vereadores`);
    - 1 legislatura vigente + 3 vereadores em cadastros.vereador SEM identidade vinculada
      (identidade-id NULL) e com mandato vigente cobrindo hoje — os ALVOS da concessão;
    - provisiona o realm do ente + cria o usuário Keycloak do ADMIN (mesmo passo de `login-kc`).

  FURA O BOOTSTRAP DE PROPÓSITO, SÓ PARA O ADMIN: `criar-usuario!` faz nascer com a required action
  'webauthn-register-passwordless' — sem passkey ninguem loga so' com senha. Como o admin NAO e' o sujeito
  da prova (quem prova convite+passkey e' o VEREADOR, via a concessão feita na UI), este seed seta a senha
  dele direto pela admin-API do Keycloak e descarrega essa required action (mesmo gesto de
  `setar-senha-teste!`/`limpar-required-actions-teste!` do `ponta_a_ponta_test.clj`, pelo mesmo racional:
  furar SÓ o bootstrap de quem não é o sujeito do teste/demo). O VEREADOR fica intocado — nasce SEM
  identidade nenhuma; só a ganha quando o admin concede acesso na UI, aí sim pelo fluxo real de convite +
  passkey.

  NÃO idempotente (ente/identidade/vereadores NOVOS a cada chamada, como as demais sementes não-upsert)."
  [_]
  (com-ds
   (fn [ds]
     (let [ente    (random-uuid) ident (random-uuid)
           cfg     (config/carregar)
           kc-cfg  (:keycloak cfg)
           {:keys [base-url admin-usuario admin-senha realm-prefixo]} kc-cfg
           idp     (component/start (keycloak-idp/keycloak-idp kc-cfg))
           http    (HttpClient/newHttpClient)
           hoje    (LocalDate/of 2025 1 1)
           nomes   ["Ana Ribeiro" "Bruno Sales" "Carla Nunes"]
           ver-ids (vec (repeatedly 3 random-uuid))]
       (try
         (try (referencia/inserir-municipio! ds {:codigo-ibge "2304400" :nome "Fortaleza" :uf "CE" :capital true :populacao 2703391})
              (catch Exception _ nil))
         (id/inserir! ds {:id ident :cpf (cpf-valido) :nome "Helena Matos"})
         (tenancy/com-tenant* ds ente
           (fn [tx]
             (estrutura/inserir-ente! tx {:ente-id ente :municipio-ibge "2304400" :nome-oficial "Câmara Municipal de Fortaleza"})
             (vinc/criar! tx {:id (random-uuid) :ente-id ente :identidade-id ident :tipo "servidor"})
             (vinc/adicionar-papel! tx {:id (random-uuid) :ente-id ente :identidade-id ident :papel "admin_ente"})
             (vinc/adicionar-papel! tx {:id (random-uuid) :ente-id ente :identidade-id ident :papel "secretario"})

             (let [leg-id (random-uuid)]
               (estrutura/inserir-legislatura! tx
                 {:id leg-id :ente-id ente :numero 19 :ano-inicio 2025 :ano-fim 2028 :vigente true})
               (doseq [[nome vid] (map vector nomes ver-ids)]
                 (vereador-db/inserir! tx {:id vid :ente-id ente :nome nome :nome-parlamentar nome})
                 (vereador-db/inserir-mandato! tx
                   {:id (random-uuid) :ente-id ente :vereador-id vid :legislatura-id leg-id
                    :partido "PDT" :estado "vigente" :natureza "titular" :vigencia-inicio hoje})))))

         (idp/provisionar-realm! idp ente)
         (let [{:keys [keycloak-user-id]} (idp/criar-usuario! idp ente {:identidade-id ident
                                                                        :nome "Helena Matos"
                                                                        :email "helena@example.org"})
               realm (str realm-prefixo ente)
               admin-tok (admin-token-kc! http base-url admin-usuario admin-senha)]
           ;; SO' o admin — ver docstring acima (o vereador fica intocado).
           (limpar-required-actions-kc! http base-url admin-tok realm keycloak-user-id)
           (setar-senha-kc! http base-url admin-tok realm keycloak-user-id "senha-teste-123")

           (println "\n=== SLICE 5 PRONTO ===")
           (println "ente-id     :" (str ente))
           (println "realm       :" realm)
           (println "kc-user-id  :" keycloak-user-id)
           (println "username    :" (str ident) " (= identidade-id do admin)")
           (println "senha       : senha-teste-123")
           (println "vereadores (alvos da concessao — SEM identidade vinculada):")
           (doseq [[nome vid] (map vector nomes ver-ids)]
             (println "  -" nome ":" (str vid)))
           (println "ENTRAR      : http://localhost:3000/entrar/" (str ente))
           (println "VEREADORES  : http://localhost:3000/cadastros/vereadores")
           (println "=======================\n"))
         (finally (component/stop idp)))))))

;; ---------- Onda E fatia 1 — INBOX interna: "a sua proposicao virou lei" ----------

(defn notificacoes
  "Semente da INBOX do vereador (Onda E fatia 1). Sob o MESMO ente da demo (ids-file de `base` — rodar
  `base` primeiro): cria um VEREADOR com identidade propria + vinculo + papel 'vereador', protocola uma
  proposicao DE AUTORIA DELE e a leva ate' NORMA PUBLICADA usando os Repo de VERDADE. `publicar-norma!`
  emite `norma.publicada`; o relay do app servido drena, `legislativo` resolve autor->identidade e emite
  `notificacao.requisitada` (in_app); `paineis` projeta na inbox. Imprime a URL + o token do vereador.
  NAO e' idempotente (cria uma norma NOVA a cada chamada) — rodar uma vez por demo fresca."
  [_]
  (com-ds
   (fn [ds]
     (let [{:keys [ente]} (edn/read-string (slurp ids-file))
           repo (repo-legislativo ds)
           ident-vereador (random-uuid)
           vereador-id (random-uuid)]
       ;; identidade supratenant + vinculo + papel (o ator que vai LER a inbox)
       (id/inserir! ds {:id ident-vereador :cpf (cpf-valido) :nome "Vereadora Ana Ribeiro"})
       (tenancy/com-tenant* ds ente
         (fn [tx]
           (vinc/criar! tx {:id (random-uuid) :ente-id ente :identidade-id ident-vereador :tipo "vereador"})
           (vinc/adicionar-papel! tx {:id (random-uuid) :ente-id ente :identidade-id ident-vereador :papel "vereador"})
           ;; cadastro institucional do vereador, JA' ligado a' identidade — e' o que o resolvedor
           ;; injetado (cadastros/identidade-do-vereador-em-tx) vai encontrar.
           (vereador-db/inserir! tx {:id vereador-id :ente-id ente :identidade-id ident-vereador
                                     :nome "Ana Ribeiro" :nome-parlamentar "Ana Ribeiro"})))
       ;; a materia DELA, ate' virar lei
       (let [{pid :id} (legislativo-repo/protocolar! repo ente
                         {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026 :uf "CE"
                          :municipio-nome "Fortaleza"
                          :ementa "Dispoe sobre as hortas comunitarias urbanas."
                          :autor-tipo "vereador" :autor-id vereador-id :autor-texto "Ver. Ana Ribeiro"})
             {aid :id} (legislativo-repo/gerar-autografo! repo ente
                         {:id (random-uuid) :proposicao-id pid :ano 2026
                          :texto-versao-id (random-uuid)
                          :destinatario-texto "Prefeito Municipal de Fortaleza"})
             {tid :id} (legislativo-repo/iniciar-tramitacao-executiva! repo ente
                         {:id (random-uuid) :autografo-id aid})]
         (legislativo-repo/registrar-resposta-executivo! repo ente
           {:id tid :resultado "sancionado" :updated-by nil :lock-version 0})
         (let [{nid :id} (legislativo-repo/promulgar-norma! repo ente
                           {:id (random-uuid) :proposicao-id pid :autografo-id aid :tipo-norma "lei"
                            :ano 2026 :uf "CE" :municipio-nome "Fortaleza"
                            :data-promulgacao (LocalDate/of 2026 6 28)
                            :ementa "Dispoe sobre as hortas comunitarias urbanas."
                            :texto-versao-id (random-uuid)})]
           (legislativo-repo/publicar-norma! repo ente
             {:id nid :veiculo-publicacao "Diario Oficial do Municipio" :updated-by nil :lock-version 0})))
       (let [token (format "{\"identidade-id\":\"%s\",\"ente-id\":\"%s\",\"papeis\":[\"vereador\"]}" ident-vereador ente)]
         (println "\n=== INBOX DA DEMO PRONTA ===")
         (println "identidade-vereador:" (str ident-vereador))
         (println "token               :" token)
         (println "Aguarde o relay drenar (~1s) e abra:")
         (println (str "http://localhost:3000/notificacoes?token=" (java.net.URLEncoder/encode token "UTF-8")))
         (println "============================\n"))))))
