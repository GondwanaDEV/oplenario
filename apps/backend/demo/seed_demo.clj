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
            [oplenario.cadastros.db.estrutura :as estrutura]
            [oplenario.cadastros.db.referencia :as referencia]
            [oplenario.cadastros.relacoes.cadastro :as rel-cad]
            [oplenario.config :as config]
            [oplenario.identidade.db.identidade :as id]
            [oplenario.identidade.db.vinculo :as vinc]
            [oplenario.identidade.relacoes.identidade :as rel-id]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.outbox :as outbox]
            [oplenario.kernel.tempo :as tempo]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.legislativo.components.repositorio :as legislativo-repo]
            [oplenario.motor.components.registro-fatos :as rf]
            [oplenario.participacao.components.repositorio :as participacao-repo]
            [oplenario.participacao.controllers :as participacao-controllers]
            [oplenario.sessoes.components.repositorio :as repo]
            [clojure.edn :as edn])
  (:import (java.time Instant)))

(def ids-file "/private/tmp/claude-501/-Users-daoudatraore-oplenario/fb0b8172-5838-4585-9188-536f405b4b01/scratchpad/demo-ids.edn")

(defn- dv [ds] (let [r (mod (reduce + (map * ds (range (inc (count ds)) 1 -1))) 11)] (if (< r 2) 0 (- 11 r))))
(defn- cpf-valido [] (let [b (vec (repeatedly 9 #(rand-int 10))) d1 (dv b)] (apply str (concat b [d1 (dv (conj b d1))]))))

(defn- com-ds [f]
  (let [c (component/start (datasource/datasource (config/carregar)))]
    (try (f (:ds c)) (finally (component/stop c)))))

(defn- repo-sessoes [ds] (assoc (repo/repositorio) :datasource {:ds ds} :bus (outbox/bus)))
(defn- repo-legislativo [ds] (legislativo-repo/->RepoLegislativoPg {:ds ds} (outbox/bus)))
(defn- repo-participacao [ds] (participacao-repo/->RepoParticipacaoPg {:ds ds} (outbox/bus)))

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
           (vinc/criar! tx {:id (random-uuid) :ente-id ente :identidade-id ident :tipo "servidor"})))
       ;; sessão ordinária (transmite-publica=true) -> abrir
       (let [sid (:id (repo/agendar-sessao! r ente {:id (random-uuid) :sessao-legislativa-id (random-uuid)
                                                    :tipo-sessao "ordinaria" :modalidade "presencial"}))]
         (repo/transicionar-sessao! r ente {:id sid :para "aberta" :updated-by ident :lock-version 0})
         (spit ids-file (pr-str {:ente ente :ident ident :sessao sid}))
         (let [token (format "{\"identidade-id\":\"%s\",\"ente-id\":\"%s\"}" ident ente)]
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
         (repo/registrar-presenca! r ente {:id (random-uuid) :sessao-id sessao :vereador-id v
                                           :tipo "entrada" :modalidade "plenario" :fonte "manual_secretaria"
                                           :ocorrido-em (Instant/now) :created-by v})
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
