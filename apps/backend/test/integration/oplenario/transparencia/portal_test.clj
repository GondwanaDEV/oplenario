(ns oplenario.transparencia.portal-test
  "INTEGRACAO (PG real) — F6c Slice 1: o portal PUBLICO (§16.5). Prova a cadeia ponta-a-ponta: o Repo de
  `legislativo` EMITE `proposicao.protocolada`/`proposicao.transicionou`/`norma.publicada` no shared.outbox
  (na tx do ato); o relay DRENA e despacha ao consumer do portal (`transparencia.diplomat.consumers`), que
  PROJETA nas tabelas de read-model (mig 0044) — sem import/JOIN cross-modulo (§22.10; o teste, nao sendo
  modulo, compoe os Repos diretamente, mesmo racional de marco_m2_test). Tambem prova RLS (isolamento
  cross-tenant) e a leitura publica via o Repo de transparencia."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
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
            [oplenario.transparencia.components.repositorio :as transparencia-repo]
            [oplenario.transparencia.diplomat.consumers :as consumers])
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

(defn- drenar!
  "Drena o outbox com o registro do projetor do portal (registro fresco por chamada — stateless, so' o
  mapa {tipo [...]})."
  []
  (outbox/drenar! *ds* (consumers/registrar {})))

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
