(ns oplenario.transparencia.portal-test
  "INTEGRACAO (PG real) — F6c Slice 1 + Onda E fatia 2 (perfil publico do vereador): o portal PUBLICO (§16.5).
  Prova a cadeia ponta-a-ponta: o Repo de `legislativo` EMITE
  `proposicao.protocolada`/`proposicao.transicionou`/`norma.publicada`/`voto.registrado` no shared.outbox (na
  tx do ato); o relay DRENA e despacha ao consumer do portal (`transparencia.diplomat.consumers`), que PROJETA
  nas tabelas de read-model (mig 0044/0064) — sem import/JOIN cross-modulo (§22.10; o teste, nao sendo modulo,
  compoe os Repos diretamente, mesmo racional de marco_m2_test). Tambem prova RLS (isolamento cross-tenant) e
  a leitura publica via o Repo de transparencia. As projecoes de voto/presenca (`db.parlamentar`) ainda nao
  tem metodo no protocolo RepoTransparencia (so' escrita, via `projetar-evento!`) — os testes leem direto de
  `db.parlamentar` dentro de uma tx do tenant, mesmo padrao dos testes de tolerancia abaixo."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [honey.sql :as sql]
            [jsonista.core :as json]
            [next.jdbc :as jdbc]
            [oplenario.cadastros.relacoes.cadastro :as rel-cad]
            [oplenario.config :as config]
            [oplenario.identidade.relacoes.identidade :as rel-id]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.eventos :as eventos]
            [oplenario.kernel.outbox :as outbox]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.legislativo.components.repositorio :as legislativo-repo]
            [oplenario.legislativo.db.autografo :as autografo]
            [oplenario.legislativo.db.tramitacao :as tram]
            [oplenario.legislativo.db.tramitacao-executiva :as exec]
            [oplenario.migracao :as migracao]
            [oplenario.motor.components.registro-fatos :as rf]
            [oplenario.sessoes.events.presenca :as ev-presenca]
            [oplenario.transparencia.components.repositorio :as transparencia-repo]
            [oplenario.transparencia.db.parlamentar :as db-parlamentar]
            [oplenario.transparencia.diplomat.consumers :as consumers]
            [oplenario.transparencia.suporte-presenca :as sp])
  (:import (java.time LocalDate)))

(def ^:dynamic *ds* nil)
(def ^:dynamic *repo-legislativo* nil)
(def ^:dynamic *repo-transparencia* nil)
(def ^:dynamic *registro-fatos* nil)

(use-fixtures :once
  (fn [t]
    (let [c   (component/start (datasource/datasource (config/carregar)))
          ;; a guarda-dsl exige um RegistroFatos startado mesmo p/ transicoes de guard nil (assert de
          ;; costura do boot, F2) — mesmo racional de tramitacao_db_test.
          reg (component/start (rf/registro-fatos (merge rel-cad/relacoes rel-id/relacoes)))
          bus (outbox/bus)]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c)
                *repo-legislativo* (legislativo-repo/->RepoLegislativoPg c bus)
                *repo-transparencia* (transparencia-repo/->RepoTransparenciaPg c)
                *registro-fatos* reg]
        (try (t) (finally (component/stop reg) (component/stop c)))))))

(def ^:private janela-larga
  "Janela de exercicio que cobre TUDO — o 4o argumento que `resumo-presenca` passou a exigir na fatia 6 do
  carry I-5. Este ns prova a FIACAO da projecao de presenca (evento -> read-model); o recorte por mandato e'
  provado em `perfil_test`, que constroi a janela pela defn pura do host."
  [{:inicio (LocalDate/of 2000 1 1) :fim nil}])

(defn- drenar!
  "Drena o outbox com o registro do projetor do portal (registro fresco por chamada — stateless, so' o
  mapa {tipo [...]})."
  []
  (outbox/drenar! *ds* (consumers/registrar {})))

(defn- contar-votos-do-ente
  "Total de linhas em voto_parlamentar para o ENTE inteiro — nao para um vereador especifico (achado M-2,
  revisao Task 2: consultar um vereador aleatorio que nunca participou de nada e' vazio de qualquer jeito,
  dispatch presente ou nao; contar por ENTE e' o que de fato distingue 'nada vazou')."
  [ente-id]
  (:count (tenancy/com-tenant* *ds* ente-id
            (fn [tx] (jdbc/execute-one! tx
                       (sql/format {:select [[[:count :*] :count]]
                                    :from [:transparencia.voto_parlamentar]
                                    :where [:= :ente_id ente-id]}))))))

(defn- montar-template!
  "Template MINIMO (2 estados, 1 transicao sem guard) — so' p/ exercitar `proposicao.transicionou`; nao e'
  regulacao real (mesmo disclaimer de tramitacao_db_test)."
  [tx ente]
  (let [tid (random-uuid)]
    (tram/criar-template! tx {:id tid :ente-id ente :chave "rito_fixture" :versao 1
                              :nome "Rito [FIXTURE]" :estado-inicial "protocolada"})
    (doseq [ch ["protocolada" "em_comissoes"]]
      (tram/criar-estado! tx {:id (random-uuid) :ente-id ente :template-id tid :chave ch :nome ch :terminal false}))
    (tram/criar-transicao! tx {:id (random-uuid) :ente-id ente :template-id tid :de-estado "protocolada"
                               :para-estado "em_comissoes" :gatilho "despachar" :guarda nil :ordem 1})
    tid))

(defn- ate-promulgavel!
  "Leva uma proposicao ja protocolada (via Repo, evento ja emitido) ate' o desfecho PROMULGAVEL. autografo/
  exec sao db-level (nao emitem evento — fora do escopo desta fatia)."
  [tx ente pid]
  (let [{aid :id} (autografo/gerar! tx {:id (random-uuid) :ente-id ente :proposicao-id pid :ano 2026
                                        :texto-versao-id (random-uuid)
                                        :destinatario-texto "Prefeito Municipal de Fortaleza"})
        {tid :id} (exec/iniciar! tx {:id (random-uuid) :ente-id ente :autografo-id aid})]
    (exec/registrar-resposta! tx {:id tid :ente-id ente :resultado "sancionado" :updated-by nil :lock-version 0})
    aid))

;; ---------- protocolar! (Repo) -> proposicao.protocolada -> transparencia.materia ----------

(deftest protocolar-projeta-a-materia-no-portal
  (let [ente (random-uuid)
        {:keys [id sequencial urn-lex]}
        (legislativo-repo/protocolar! *repo-legislativo* ente
          {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026 :uf "CE" :municipio-nome "Fortaleza"
           :ementa "Dispoe sobre X" :autor-tipo "vereador" :autor-texto "Fulano de Tal"})]
    (drenar!)
    (let [m (transparencia-repo/buscar-materia *repo-transparencia* ente id)]
      (is (some? m) "a materia foi projetada no read-model do portal")
      (is (= "protocolada" (:estado m)) "nasce no mesmo estado do protocolo")
      (is (= sequencial (:sequencial m)))
      (is (= urn-lex (:urn-lex m)))
      (is (= "Fulano de Tal" (:autor-texto m)) "snapshot publico do autor (sem autor-id interno)"))
    (is (empty? (transparencia-repo/listar-materias *repo-transparencia* (random-uuid) #{}))
        "RLS: outro ente nao ve a materia projetada")))

;; ---------- protocolar! com :autor-id -> proposicao.protocolada -> materia.autor_id (Onda E fatia 2) ----------

(deftest protocolar-com-autor-id-projeta-o-elo-autoria-no-portal
  (let [ente     (random-uuid)
        vereador (random-uuid)
        {pid :id}
        (legislativo-repo/protocolar! *repo-legislativo* ente
          {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026 :uf "CE" :municipio-nome "Fortaleza"
           :ementa "Hortas comunitarias" :autor-tipo "vereador" :autor-id vereador :autor-texto "Helena Past"})]
    (drenar!)
    (is (= vereador (:autor-id (transparencia-repo/buscar-materia *repo-transparencia* ente pid)))
        "o elo autoria->vereador (perfil publico) foi projetado no read-model")))

(deftest protocolar-sem-autor-id-legado-projeta-autor-id-nulo
  (let [ente (random-uuid)
        {pid :id}
        (legislativo-repo/protocolar! *repo-legislativo* ente
          {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026 :uf "CE" :municipio-nome "Fortaleza"
           :ementa "Legado"})]
    (drenar!)
    ;; review task-1 achado I-2: `(:autor-id nil)` tambem e' nil — sem a materia EXISTIR, a asserção de
    ;; baixo passaria vazia (falso-positivo se a projecao nao rodasse, ex.: ON CONFLICT DO NOTHING futuro).
    ;; Mesma disciplina do teste-irmao `protocolar-projeta-a-materia-no-portal`.
    (let [m (transparencia-repo/buscar-materia *repo-transparencia* ente pid)]
      (is (some? m) "a materia foi projetada no read-model do portal")
      (is (nil? (:autor-id m))
          "evento SEM :autor-id (acervo anterior) projeta com autor_id nulo, sem lancar"))))

;; ---------- transicionar! (Repo) -> proposicao.transicionou -> materia.estado ----------

(deftest transicao-atualiza-o-estado-projetado
  (let [ente (random-uuid)
        {pid :id} (legislativo-repo/protocolar! *repo-legislativo* ente
                    {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026 :uf "CE"
                     :municipio-nome "Fortaleza" :ementa "Dispoe sobre Y"})
        tid (tenancy/com-tenant* *ds* ente (fn [tx] (montar-template! tx ente)))]
    (drenar!) ; projeta o protocolo antes da transicao (protocolada precede transicionou, mesma ordem real)
    (let [r (legislativo-repo/transicionar! *repo-legislativo* ente *registro-fatos*
              {:proposicao-id pid :template-id tid :gatilho "despachar" :agora (LocalDate/of 2026 3 1)})]
      (is (true? (:transicionou? r))))
    (drenar!)
    (is (= "em_comissoes" (:estado (transparencia-repo/buscar-materia *repo-transparencia* ente pid)))
        "a transicao real projeta o novo estado")))

(deftest transicao-sem-materia-projetada-e-tolerante
  ;; review architect HIGH-1 (corrige decisao original de "falha fechada"): a projecao de uma transicao sem
  ;; a materia previamente projetada NAO lanca — devolve nil e o caller loga. Um `throw` aqui envenenaria o
  ;; relay COMPARTILHADO (bloqueio head-of-line de TODOS os modulos, nao so' transparencia) a cada gap de
  ;; ordem — cenario real em deploy do tipo de evento novo sobre proposicoes ja existentes.
  (let [ente (random-uuid) orfao-id (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (is (nil? (transparencia-repo/projetar-evento! tx
                    {:tipo "proposicao.transicionou" :ente-id ente
                     :payload {:proposicao-id (str orfao-id) :para "em_comissoes"}}))
            "sem materia previa -> nil (tolerante), nunca excecao")))
    (is (nil? (transparencia-repo/buscar-materia *repo-transparencia* ente orfao-id))
        "nenhuma materia incompleta e' criada em silencio")))

(deftest gap-de-ordem-nao-trava-o-relay-compartilhado
  ;; review architect HIGH-1: prova o comportamento do RELAY REAL (nao so' a fn isolada) — um evento
  ;; "orfao" (transicionou sem protocolada, inserido direto no outbox p/ simular o cenario de deploy sobre
  ;; dado pre-existente) drenado JUNTO com um protocolo LEGITIMO de outra materia. O gap loga e segue; o
  ;; evento legitimo projeta normalmente — nenhum head-of-line-block do bus compartilhado.
  (let [ente (random-uuid) orfao-id (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (eventos/emitir! (outbox/bus) tx
          (eventos/evento "proposicao.transicionou" ente
                          {:proposicao-id (str orfao-id) :para "em_comissoes"}))))
    (let [{pid :id} (legislativo-repo/protocolar! *repo-legislativo* ente
                      {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026 :uf "CE"
                       :municipio-nome "Fortaleza" :ementa "Materia legitima apos o orfao"})]
      (drenar!) ; drena os DOIS eventos pendentes na ORDEM de insercao: o orfao primeiro, depois o legitimo
      (is (nil? (transparencia-repo/buscar-materia *repo-transparencia* ente orfao-id))
          "o orfao nao cria materia incompleta")
      (is (some? (transparencia-repo/buscar-materia *repo-transparencia* ente pid))
          "o evento legitimo POSTERIOR ao orfao projeta normalmente — prova que o gap nao bloqueia o drenar"))))

;; ---------- editar-proposicao! (Repo) -> proposicao.editada -> transparencia.materia (Task 1-N1) ----------

(deftest editar-troca-autor-para-executivo-zera-autor-id-no-portal
  (let [ente (random-uuid)
        vereador (random-uuid)
        {pid :id} (legislativo-repo/protocolar! *repo-legislativo* ente
                    {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026 :uf "CE"
                     :municipio-nome "Fortaleza" :ementa "Dispoe sobre autoria"
                     :autor-tipo "vereador" :autor-id vereador :autor-texto "Helena Past"})]
    (drenar!)
    (let [lock-atual (:lock-version (legislativo-repo/buscar-proposicao *repo-legislativo* ente pid))]
      (legislativo-repo/editar-proposicao! *repo-legislativo* ente
        {:id pid :lock-version lock-atual :autor-tipo "executivo" :updated-by (random-uuid)}))
    (drenar!)
    (let [m (transparencia-repo/buscar-materia *repo-transparencia* ente pid)]
      (is (= "executivo" (:autor-tipo m)))
      (is (nil? (:autor-id m)) "a edicao de autoria chega ao portal — o elo antigo e' removido"))))

(deftest editar-troca-vereador-v-para-vereador-w-no-portal
  ;; achado 5 (Task 1-N1 fix2, Menor): o caso de edicao de autoria MAIS COMUM na vida real — corrigir QUAL
  ;; vereador e' o autor (V -> W), nao so' trocar pra uma especie nao-parlamentar. E' o contrato de fato do
  ;; perfil publico do vereador (Onda E fatia 2): a materia tem que aparecer na lista de W e sumir da de V.
  (let [ente (random-uuid)
        vereador-v (random-uuid)
        vereador-w (random-uuid)
        {pid :id} (legislativo-repo/protocolar! *repo-legislativo* ente
                    {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026 :uf "CE"
                     :municipio-nome "Fortaleza" :ementa "Dispoe sobre autoria trocada"
                     :autor-tipo "vereador" :autor-id vereador-v :autor-texto "Fulano V"})]
    (drenar!)
    (let [lock-atual (:lock-version (legislativo-repo/buscar-proposicao *repo-legislativo* ente pid))]
      (legislativo-repo/editar-proposicao! *repo-legislativo* ente
        {:id pid :lock-version lock-atual :autor-tipo "vereador" :autor-id vereador-w
         :autor-texto "Ciclana W" :updated-by (random-uuid)}))
    (drenar!)
    (let [m (transparencia-repo/buscar-materia *repo-transparencia* ente pid)]
      (is (= "vereador" (:autor-tipo m)))
      (is (= vereador-w (:autor-id m)) "o perfil publico do vereador W passa a listar esta materia")
      (is (= "Ciclana W" (:autor-texto m)) "o nome de exibicao acompanha a troca"))))

(deftest editar-so-ementa-preserva-autor-id-no-portal
  (let [ente (random-uuid)
        vereador (random-uuid)
        {pid :id} (legislativo-repo/protocolar! *repo-legislativo* ente
                    {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026 :uf "CE"
                     :municipio-nome "Fortaleza" :ementa "Ementa original"
                     :autor-tipo "vereador" :autor-id vereador :autor-texto "Helena Past"})]
    (drenar!)
    (let [lock-atual (:lock-version (legislativo-repo/buscar-proposicao *repo-legislativo* ente pid))]
      (legislativo-repo/editar-proposicao! *repo-legislativo* ente
        {:id pid :lock-version lock-atual :ementa "Ementa corrigida" :updated-by (random-uuid)}))
    (drenar!)
    (let [m (transparencia-repo/buscar-materia *repo-transparencia* ente pid)]
      (is (= "Ementa corrigida" (:ementa m)) "a ementa publica muda")
      (is (= vereador (:autor-id m))
          "o RETURNING carrega o estado REAL da linha (nao o PATCH parcial) — autor_id sobrevive"))))

(deftest editar-sem-materia-projetada-e-tolerante
  ;; mesma disciplina de transicao-sem-materia-projetada-e-tolerante: um evento de proposicao anterior
  ;; ao read-model (ou fora de ordem num redrive) nao pode envenenar o relay COMPARTILHADO.
  (let [ente (random-uuid) orfao-id (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (is (nil? (transparencia-repo/projetar-evento! tx
                    {:tipo "proposicao.editada" :ente-id ente
                     :payload {:proposicao-id (str orfao-id) :ementa "X"}}))
            "sem materia previa -> nil (tolerante), nunca excecao")))
    (is (nil? (transparencia-repo/buscar-materia *repo-transparencia* ente orfao-id))
        "nenhuma materia incompleta e' criada em silencio")))

;; ---------- promulgar!+publicar! (Repo) -> norma.publicada -> transparencia.norma ----------

(deftest publicar-norma-projeta-a-norma-e-liga-na-ficha
  (let [ente (random-uuid)
        {pid :id} (legislativo-repo/protocolar! *repo-legislativo* ente
                    {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026 :uf "CE"
                     :municipio-nome "Fortaleza" :ementa "Dispoe sobre Z"})
        aid (tenancy/com-tenant* *ds* ente (fn [tx] (ate-promulgavel! tx ente pid)))
        {nid :id} (legislativo-repo/promulgar-norma! *repo-legislativo* ente
                    {:id (random-uuid) :proposicao-id pid :autografo-id aid :tipo-norma "lei" :ano 2026
                     :uf "CE" :municipio-nome "Fortaleza" :data-promulgacao (LocalDate/of 2026 6 28)
                     :ementa "Dispoe sobre Z" :texto-versao-id (random-uuid)})]
    (drenar!) ; drena o protocolo (promulgar-norma! nao emite evento — so' a publicacao marca eficacia)
    (legislativo-repo/publicar-norma! *repo-legislativo* ente
      {:id nid :veiculo-publicacao "Diario Oficial do Municipio" :updated-by nil :lock-version 0})
    (drenar!)
    (let [n (transparencia-repo/buscar-norma *repo-transparencia* ente nid)]
      (is (some? n) "a norma publicada foi projetada")
      (is (= "lei" (:tipo-norma n)))
      (is (= "Diario Oficial do Municipio" (:veiculo-publicacao n)))
      (is (some? (:publicado-em n)) "publicado-em parseado (Instant) da string ISO do evento"))
    (let [ficha (transparencia-repo/buscar-materia *repo-transparencia* ente pid)
          norma (transparencia-repo/norma-da-materia *repo-transparencia* ente pid)]
      (is (some? ficha) "a materia continua no portal")
      (is (= nid (:norma-id norma)) "a ficha liga 'proposicao -> lei' (norma-da-materia)"))))

;; ---------- registrar-voto!/registrar-voto-secreto! (Repo legislativo) -> voto.registrado ->
;;            transparencia.voto_parlamentar (Onda E fatia 2 — perfil publico do vereador) ----------

(deftest voto-nominal-projeta-o-voto-publico-do-vereador
  (let [ente     (random-uuid)
        vereador (random-uuid)
        sessao   (random-uuid)
        {pid :id} (legislativo-repo/protocolar! *repo-legislativo* ente
                    {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026 :uf "CE"
                     :municipio-nome "Fortaleza" :ementa "Dispoe sobre voto nominal"})
        {vid :id} (legislativo-repo/abrir-votacao! *repo-legislativo* ente
                    {:id (random-uuid) :objeto-tipo "proposicao" :objeto-id pid :sessao-id sessao
                     :modalidade "nominal" :quorum-tipo "maioria_simples"})]
    (legislativo-repo/registrar-voto! *repo-legislativo* ente
      {:id (random-uuid) :votacao-id vid :vereador-id vereador :voto "sim"})
    (drenar!)
    (let [votos (tenancy/com-tenant* *ds* ente
                  (fn [tx] (db-parlamentar/votos-do-vereador tx ente vereador 10)))]
      (is (= 1 (count votos)) "1 voto publico projetado")
      (is (= "sim" (:voto (first votos))) "o valor do voto foi projetado")
      (is (= vid (:votacao-id (first votos))) "a votacao-id foi projetada")
      ;; achado I-2 (re-revisao): sem este `is` o fix do producer (`:proposicao-id` derivado de
      ;; `votacoes.objeto_tipo`/`objeto_id` nos dois call-sites) e' TAUTOLOGICAMENTE nao-coberto — reverte-lo
      ;; deixava a suite verde, porque a chave e' OPCIONAL no contrato Malli. E' este campo que faz o LEFT JOIN
      ;; com `transparencia.materia` casar (a secao 'como votou' do perfil publico mostra a ementa).
      (is (= pid (:proposicao-id (first votos)))
          "o elo voto->materia foi projetado (objeto_tipo 'proposicao' -> :proposicao-id no evento)")
      (is (= "Dispoe sobre voto nominal" (:materia-ementa (first votos)))
          "com :proposicao-id preenchido, o LEFT JOIN com transparencia.materia casa e traz a ementa")
      (is (some? (:ocorrido-em (first votos)))
          "ocorrido-em (tempo de DOMINIO, RETURNING de legislativo.votos.registrado_em) foi projetado"))))

(deftest voto-legado-sem-ocorrido-em-nao-trava-o-relay-compartilhado
  ;; C-1 (revisao Task 2) — o unico teste que prova o guard `instant-tolerante`. `:ocorrido-em` e' chave NOVA
  ;; do contrato de `voto.registrado` nominal; eventos JA' gravados no shared.outbox antes da mudanca (deploy
  ;; rolling, redrive de historico) nao a tem. `outbox/drenar-um!` chama o handler SEM try e o relay
  ;; COMPARTILHADO faz catch+retry eterno: um `Instant/parse` de nil lancaria e travaria a CABECA DA FILA para
  ;; sempre, bloqueando todo evento de id maior de TODOS os modulos.
  ;;
  ;; Por isso o evento legado entra por INSERT CRU em `shared.outbox` — furar `eventos/evento-validado` e' o
  ;; ponto: e' exatamente o que um evento gravado sob o contrato ANTIGO e'. (Mesma classe do irmao
  ;; `gap-de-ordem-nao-trava-o-relay-compartilhado`, que usa o construtor sem validacao.)
  ;;
  ;; O assert que MAIS importa e' o (3): um voto BEM-FORMADO com id de outbox MAIOR e' projetado normalmente.
  ;; Sem ele o teste nao provaria head-of-line — so' 'nao explodiu'.
  (let [ente            (random-uuid)
        vereador-legado (random-uuid)
        vereador-ok     (random-uuid)
        sessao          (random-uuid)
        {pid :id} (legislativo-repo/protocolar! *repo-legislativo* ente
                    {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026 :uf "CE"
                     :municipio-nome "Fortaleza" :ementa "Dispoe sobre evento legado"})
        {vid-legado :id} (legislativo-repo/abrir-votacao! *repo-legislativo* ente
                           {:id (random-uuid) :objeto-tipo "proposicao" :objeto-id pid :sessao-id sessao
                            :modalidade "nominal" :quorum-tipo "maioria_simples"})
        {vid-ok :id} (legislativo-repo/abrir-votacao! *repo-legislativo* ente
                       {:id (random-uuid) :objeto-tipo "proposicao" :objeto-id pid :sessao-id sessao
                        :modalidade "nominal" :quorum-tipo "maioria_simples"})]
    ;; (a) o evento LEGADO — payload do contrato ANTIGO (sem :ocorrido-em), gravado direto no outbox.
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (jdbc/execute-one! tx
          ["INSERT INTO shared.outbox (ente_id, tipo, payload, idempotency_key) VALUES (?, ?, ?::jsonb, ?)"
           ente "voto.registrado"
           (json/write-value-as-string {:votacao-id (str vid-legado) :sessao-id (str sessao)
                                        :modalidade "nominal" :vereador-id (str vereador-legado)
                                        :voto "sim"})
           (str (random-uuid))])))
    ;; (b) o evento POSTERIOR, bem-formado, pelo caminho REAL de escrita — id de outbox MAIOR que o legado.
    (legislativo-repo/registrar-voto! *repo-legislativo* ente
      {:id (random-uuid) :votacao-id vid-ok :vereador-id vereador-ok :voto "sim"})
    ;; (1) nenhuma excecao propaga do relay
    (is (number? (drenar!))
        "drenar! atravessa o evento legado sem propagar excecao (o relay nao e' envenenado)")
    (let [legado (tenancy/com-tenant* *ds* ente
                   (fn [tx] (db-parlamentar/votos-do-vereador tx ente vereador-legado 10)))
          ok     (tenancy/com-tenant* *ds* ente
                   (fn [tx] (db-parlamentar/votos-do-vereador tx ente vereador-ok 10)))]
      ;; (2) o legado nao projeta linha nenhuma (ocorrido_em e' NOT NULL — nao ha estado parcial honesto)
      (is (empty? legado) "o evento legado nao projetou voto (tolerado + logado, nunca gravado pela metade)")
      ;; (3) O PONTO: a cabeca da fila nao travou — o evento POSTERIOR foi projetado
      (is (= 1 (count ok))
          "o voto bem-formado POSTERIOR ao legado projeta normalmente — nenhum head-of-line block")
      (is (= vid-ok (:votacao-id (first ok))))
      (is (= 1 (contar-votos-do-ente ente))
          "exatamente 1 linha no ente inteiro: so' a do evento posterior"))))

(deftest voto-secreto-nao-projeta-nada
  ;; SIGILO §22.6: o ramo 'secreta' de VotoRegistradoPayload e' :closed e NEM ADMITE :vereador-id/:voto — o
  ;; consumer nao tem como projetar identidade mesmo que tentasse (garantia de SCHEMA DE EVENTO, nao de um
  ;; `if` no consumer). Prova ponta-a-ponta via o caminho REAL de escrita (registrar-voto-secreto!), nao um
  ;; payload construido a mao.
  ;;
  ;; M-2 (revisao Task 2): a versao anterior so' checava "vazio para um vereador aleatorio" — passaria com o
  ;; dispatch "voto.registrado" DELETADO por inteiro (o vereador aleatorio nunca votou em nada de qualquer
  ;; jeito). Este teste registra um voto NOMINAL de verdade NO MESMO ENTE e conta as linhas do ENTE: se o
  ;; dispatch sumisse, a contagem cairia p/ 0 (deveria ser 1); se o segredo vazasse, subiria p/ 2 (deveria
  ;; continuar 1) — falha nos dois sentidos, comportamental de verdade.
  (let [ente          (random-uuid)
        vereador-nom  (random-uuid)
        sessao        (random-uuid)
        {pid :id} (legislativo-repo/protocolar! *repo-legislativo* ente
                    {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026 :uf "CE"
                     :municipio-nome "Fortaleza" :ementa "Dispoe sobre voto secreto"})
        {vid-nom :id} (legislativo-repo/abrir-votacao! *repo-legislativo* ente
                        {:id (random-uuid) :objeto-tipo "proposicao" :objeto-id pid :sessao-id sessao
                         :modalidade "nominal" :quorum-tipo "maioria_simples"})
        {vid-sec :id} (legislativo-repo/abrir-votacao! *repo-legislativo* ente
                        {:id (random-uuid) :objeto-tipo "proposicao" :objeto-id pid :sessao-id sessao
                         :modalidade "secreta" :quorum-tipo "maioria_simples"})]
    (legislativo-repo/registrar-voto! *repo-legislativo* ente
      {:id (random-uuid) :votacao-id vid-nom :vereador-id vereador-nom :voto "sim"})
    (legislativo-repo/registrar-voto-secreto! *repo-legislativo* ente
      {:id (random-uuid) :votacao-id vid-sec :voto "sim"})
    (drenar!)
    (is (= 1 (contar-votos-do-ente ente))
        "so' o voto NOMINAL materializou em voto_parlamentar — o secreto nao vazou nenhuma linha")))

;; ---------- presenca.registrada -> transparencia.presenca_parlamentar (Onda E fatia 2) ----------
;; `sessoes` nao esta' wireado neste teste (o portal so' consome o evento PUBLICO) — o payload casa
;; events.presenca/RegistradaPayload (sessoes/events/presenca.clj), exercitado via `projetar-evento!`
;; diretamente, mesmo padrao dos testes de tolerancia acima (transicao-sem-materia-projetada-e-tolerante).

(deftest presenca-registrada-projeta-a-presenca-do-vereador
  ;; re-revisao: com UMA UNICA linha no ente (a do proprio vereador) o predicado do numerador podia ser
  ;; deletado sem falhar — `COUNT(DISTINCT sessao_id)` cru daria o mesmo 1. Aqui ha TRES sessoes com chamada:
  ;; uma com ESTE vereador e DUAS so' com o outro, que matam o predicado `vereador_id`. Numerador 1,
  ;; denominador 3.
  ;; Onda E / carry I-5 fatia 1: o vocabulario passa ao REAL de `sessoes` (entrada|saida|retorno|
  ;; mudanca_modalidade, plenario|remoto, manual_secretaria) — 'presente'/'ausente'/'presencial'/'mesa'
  ;; NUNCA existiram no produtor, e o distrator de terceiro caso deixou de ser "ausente" (que o produtor nao
  ;; grava: ausencia e' a AUSENCIA de linha) e virou uma segunda sessao do OUTRO vereador.
  (let [ente (random-uuid) vereador (random-uuid) outro (random-uuid)
        sessao-a (random-uuid) sessao-b (random-uuid) sessao-c (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (doseq [[sessao quem tipo] [[sessao-a vereador "entrada"]
                                    [sessao-b outro    "entrada"]
                                    [sessao-c outro    "saida"]]]
          (transparencia-repo/projetar-evento! tx
            {:tipo "presenca.registrada" :ente-id ente
             :payload (sp/validar-vocabulario!
                       {:sessao-id (str sessao) :vereador-id (str quem) :tipo tipo
                        :modalidade "plenario" :fonte "manual_secretaria"
                        :ocorrido-em "2026-05-18T14:00:00Z"})}))
        ;; I-5 fatia 6: `resumo-presenca` passou a exigir a JANELA DE EXERCICIO (4o arg). Aqui ela cobre
        ;; tudo — este ns prova a FIACAO da projecao (evento -> read-model), nao o recorte por mandato
        ;; (esse e' `perfil_test`). Com `[]` a fn nem tocaria o banco e o teste deixaria de medir a fiacao.
        (let [resumo (db-parlamentar/resumo-presenca tx ente vereador janela-larga)]
          (is (= 1 (:sessoes-presente resumo))
              "numerador: so' a sessao em que ESTE vereador tem linha (ter linha == compareceu)")
          (is (= 3 (:sessoes-com-chamada resumo))
              "denominador: todas as sessoes da JANELA que tiveram chamada, independente de quem/como")
          (is (true? (:janela-de-exercicio-conhecida resumo))))))))

(deftest presenca-registrada-via-relay-real-projeta-a-presenca
  ;; M-1 (revisao Task 2): o teste-irmao acima chama projetar-evento! DIRETO — apagar "presenca.registrada"
  ;; de tipos-consumidos deixaria a suite inteira verde mesmo assim. Este exercita a fiacao REAL: emite o
  ;; envelope VALIDADO (sessoes/events/presenca, o mesmo construtor que producao usa) no shared.outbox e
  ;; drena via consumers/registrar (que so' despacha os tipos listados em tipos-consumidos).
  (let [ente (random-uuid) vereador (random-uuid) sessao (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (eventos/emitir! (outbox/bus) tx
          (ev-presenca/registrada ente
            (sp/validar-vocabulario!
             {:sessao-id sessao :vereador-id vereador :tipo "entrada" :modalidade "plenario"
              :fonte "manual_secretaria" :ocorrido-em "2026-05-18T14:00:00Z"})))))
    (drenar!)
    (let [resumo (tenancy/com-tenant* *ds* ente
                   (fn [tx] (db-parlamentar/resumo-presenca tx ente vereador janela-larga)))]
      (is (= 1 (:sessoes-presente resumo))
          "a presenca chegou via o relay REAL (tipos-consumidos + dispatch), nao so' via a fn isolada")
      (is (= 1 (:sessoes-com-chamada resumo))
          "o denominador tambem conta a sessao chegada pelo relay"))))
