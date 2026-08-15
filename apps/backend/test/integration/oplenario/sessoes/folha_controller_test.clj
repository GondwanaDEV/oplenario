(ns oplenario.sessoes.folha-controller-test
  "INTEGRACAO (PG real) — `controllers/folha-da-sessao` (§22.6 eixo C, Etapa 5 fatia 1). O que este ns prova:

    - D6, FAIL-CLOSED e ALLOWLIST: so' sessao em `logic/estados-sessao-fechada` tem folha — TODO estado FORA
      dessa allowlist lanca (nao so' 'aberta', o caso feliz de teste; a allowlist inteira, mesmo padrao de
      `presenca_gate_test/guarda-contra-o-drift-silencioso`). 'nao_realizada' com motivo produz documento com
      o motivo visivel.
    - O TESTE CRUZADO (D1, obrigatorio pelo brief): o quorum do DOCUMENTO e' identicamente o quorum que
      `logic/contar-quorum` produz sobre as MESMAS linhas computadas de forma INDEPENDENTE (a mesma disciplina
      de `presenca_chamada_cruzado_test`) — nunca uma segunda aritmetica.
    - A authz herdada e' a da chamada NOMINAL (`pode-ver-sessao?`: mesma Casa), nao a magra do quorum.
    - A SERIE, as JUSTIFICATIVAS (com motivo) e o CABECALHO DA CASA chegam no documento, montados com os
      repositorios de verdade dos dois modulos (nenhum INSERT redigitado no teste).

  NOTA DE TEMPO (carry de rodada anterior — datas relativas ao agora): `aberta_em`/`encerrada_em` sao
  carimbados por `[:now]` DENTRO de `sessao/transicionar!` — nao ha' como injeta-los. Os eventos de
  presenca (piso = inicio do DIA CIVIL de `aberta_em`) tem de ser carimbados com `(Instant/now)` capturado
  NA HORA de cada chamada, nunca uma constante fixa de outro dia (senao a serie cai fora da janela e o
  teste reprova sozinho, sem regressao nenhuma no codigo)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [malli.core :as m]
            [oplenario.cadastros.components.repositorio :as repo-cadastros]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.tempo :as tempo]
            [oplenario.migracao :as migracao]
            [oplenario.sessoes.components.repositorio :as repo-sessoes]
            [oplenario.sessoes.controllers :as controllers]
            [oplenario.sessoes.db.chamada :as db-chamada]
            [oplenario.sessoes.db.presenca :as presenca]
            [oplenario.sessoes.db.sessao :as sessao]
            [oplenario.sessoes.logic :as logic]
            [oplenario.sessoes.models.folha :as mod-folha])
  (:import (java.time Instant LocalDate)))

(def ^:dynamic *repo-s* nil)
(def ^:dynamic *repo-c* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*repo-s* (repo-sessoes/->RepoSessoesPg c nil)
                *repo-c* (repo-cadastros/->RepoCadastrosPg c)]
        (try (t) (finally (component/stop c)))))))

;; ---------- seeds (produtores REAIS dos dois modulos) ----------

(defn- casa! [ente]
  (repo-cadastros/criar-ente! *repo-c* ente
    {:ente-id ente :municipio-ibge "2304400" :nome-oficial "Camara Municipal de Fortaleza" :nome-curto "CMF"})
  (let [leg (random-uuid)]
    (repo-cadastros/criar-legislatura! *repo-c* ente
      {:id leg :ente-id ente :numero 7 :ano-inicio 2025 :ano-fim 2028 :vigente true})
    leg))

(defn- vereador-com-mandato! [ente leg nome]
  (let [id (random-uuid)]
    (repo-cadastros/criar-vereador! *repo-c* ente {:id id :ente-id ente :nome nome
                                                   :nome-parlamentar nil :identidade-id nil})
    (repo-cadastros/criar-mandato! *repo-c* ente
      {:id (random-uuid) :ente-id ente :vereador-id id :legislatura-id leg :estado "vigente"
       :partido "PX" :vigencia-inicio (LocalDate/of 2025 1 1) :vigencia-fim nil})
    id))

(defn- ev! [tx ente sessao-id vereador-id tipo modalidade]
  ;; `ocorrido-em` = (Instant/now) capturado NESTA CHAMADA — ver nota de tempo no topo do ns.
  (presenca/registrar-evento! tx {:id (random-uuid) :ente-id ente :sessao-id sessao-id
                                  :vereador-id vereador-id :tipo tipo :modalidade modalidade
                                  :fonte "manual_secretaria" :ocorrido-em (Instant/now)}))

(defn- roster-seam [] (fn [ente-id data] (repo-cadastros/roster-da-casa *repo-c* ente-id data)))
(defn- dados-da-casa-seam []
  (fn [ente-id _data]
    (let [ente (repo-cadastros/buscar-ente *repo-c* ente-id)
          leg (repo-cadastros/legislatura-vigente *repo-c* ente-id)]
      {:nome-oficial (:nome-oficial ente) :nome-curto (:nome-curto ente)
       :legislatura-numero (:numero leg) :legislatura-ano-inicio (:ano-inicio leg)
       :legislatura-ano-fim (:ano-fim leg)})))

(defn- ator [ente] {:ente-id ente :identidade-id (random-uuid) :papeis #{"secretario"}})

(defn- abrir-e-encerrar!
  "Agenda -> abre -> roda `durante-aberta` (registra eventos) -> encerra, tudo na MESMA sessao. Devolve o
  sessao-id. `aberta_em`/`encerrada_em` saem de `[:now]` dentro de `sessao/transicionar!` — nao sao
  controlaveis daqui, entao os eventos que `durante-aberta` registra tem de usar `(Instant/now)` deles
  mesmos (via `ev!`), nunca uma constante."
  [ente durante-aberta]
  (let [sid (repo-sessoes/transacao *repo-s* ente
              (fn [tx]
                (let [{sid :id} (sessao/agendar! tx {:id (random-uuid) :ente-id ente
                                                     :sessao-legislativa-id (random-uuid)
                                                     :tipo-sessao "ordinaria" :agendada-para (Instant/now)})]
                  (sessao/transicionar! tx {:id sid :ente-id ente :para "aberta" :lock-version 0})
                  (durante-aberta tx sid)
                  sid)))]
    (repo-sessoes/transacao *repo-s* ente
      (fn [tx] (sessao/transicionar! tx {:id sid :ente-id ente :para "encerrada" :lock-version 1})))
    sid))

;; ---------- D6: allowlist inteira, nao so' o caso feliz ----------

(deftest so-sessao-fechada-tem-folha-cada-estado-nao-fechado-lanca
  (let [ente (random-uuid)
        leg (casa! ente)
        _v1 (vereador-com-mandato! ente leg "Ana")]
    (doseq [estado ["agendada" "aberta" "suspensa"]]
      (let [sid (repo-sessoes/transacao *repo-s* ente
                  (fn [tx]
                    (let [{sid :id} (sessao/agendar! tx {:id (random-uuid) :ente-id ente
                                                         :sessao-legislativa-id (random-uuid)
                                                         :tipo-sessao "ordinaria" :agendada-para (Instant/now)})]
                      (when (not= "agendada" estado)
                        (sessao/transicionar! tx {:id sid :ente-id ente :para "aberta" :lock-version 0}))
                      (when (= "suspensa" estado)
                        (sessao/transicionar! tx {:id sid :ente-id ente :para "suspensa" :lock-version 1}))
                      sid)))]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"so' sessao FECHADA tem folha"
              (controllers/folha-da-sessao *repo-s* (roster-seam) (dados-da-casa-seam) (ator ente) sid
                                           (tempo/relogio-fixo (Instant/now))))
            (str "estado '" estado "' nao esta em estados-sessao-fechada -> folha tem de recusar"))))))

(deftest allowlist-de-fechada-e-exatamente-o-conjunto-testado
  ;; guarda contra o drift silencioso (mesmo padrao de presenca_gate_test): se um estado NOVO entrar em
  ;; `estados-sessao` sem ninguem classifica-lo, este teste acusa a divergencia primeiro.
  (is (= #{"agendada" "aberta" "suspensa" "encerrada" "nao_realizada" "arquivada"} logic/estados-sessao))
  (is (= #{"encerrada" "nao_realizada" "arquivada"} logic/estados-sessao-fechada)))

;; ---------- o CASO FELIZ: sessao encerrada produz documento ----------

(deftest sessao-encerrada-produz-documento-com-serie-quorum-cabecalho-e-justificativas
  (let [ente (random-uuid)
        leg (casa! ente)
        v1 (vereador-com-mandato! ente leg "Ana")
        v2 (vereador-com-mandato! ente leg "Bruno")
        conduzida-por (:identidade-id (ator ente))
        sid (abrir-e-encerrar! ente
              (fn [tx sid]
                (ev! tx ente sid v1 "entrada" "plenario")
                (ev! tx ente sid v1 "saida" "plenario")
                (ev! tx ente sid v1 "retorno" "plenario")
                ;; o ATO de chamada conduzida (D1 file-list): registrado NA MESMA tx que abre a sessao, com
                ;; o roster resolvido aqui como o controller faria.
                (let [roster (repo-cadastros/roster-da-casa *repo-c* ente (LocalDate/now))]
                  (db-chamada/registrar! tx
                    {:id (random-uuid) :ente-id ente :sessao-id sid :conduzida-por conduzida-por
                     :membros-da-casa (count roster) :ocorrido-em (Instant/now) :created-by conduzida-por}))))
        _ (repo-sessoes/criar-justificativa! *repo-s* ente
            {:id (random-uuid) :ente-id ente :sessao-id sid :vereador-id v2
             :motivo "atestado medico" :created-by conduzida-por})
        sessao-real (repo-sessoes/buscar-sessao *repo-s* ente sid)
        doc (controllers/folha-da-sessao *repo-s* (roster-seam) (dados-da-casa-seam) (ator ente) sid
                                         (tempo/relogio-fixo (Instant/now)))]
    (is (= "folha-sessao-v1" (:spec-versao doc)))
    (is (= "encerrada" (:estado (:sessao doc))))
    (is (= (:encerrada-em sessao-real) (:instante doc)) "o teto congelado e' o encerrada-em REAL, nunca 'agora'")
    (is (= "Camara Municipal de Fortaleza" (:nome-oficial (:cabecalho-da-casa doc))))
    (is (= 7 (:legislatura-numero (:cabecalho-da-casa doc))))
    (is (= 2 (count (:linhas doc))) "as 2 cadeiras do roster")
    (is (= ["entrada" "saida" "retorno"] (mapv :tipo (get (:serie doc) v1)))
        "a serie de v1 esta completa e cronologica dentro do documento")
    (is (nil? (get (:serie doc) v2)) "v2 nunca teve evento -> nao aparece na serie")
    (is (= 1 (count (:justificativas doc))))
    (is (= "atestado medico" (:motivo (first (:justificativas doc))))
        "o MOTIVO (dado sensivel) chega no documento — e' a folha nominal, nao o quorum magro")
    (is (= 1 (count (:atos-de-chamada-conduzida doc))))
    (is (m/validate mod-folha/FolhaDocumento doc)
        (str "o documento inteiro, com dado REAL do banco, bate FolhaDocumento: "
             (m/explain mod-folha/FolhaDocumento doc)))))

;; ---------- CRUZADO (D1 obrigatorio): o quorum do documento == contar-quorum computado de forma independente ----------

(deftest quorum-do-documento-e-identico-ao-contar-quorum-computado-de-forma-independente
  (let [ente (random-uuid)
        leg (casa! ente)
        v1 (vereador-com-mandato! ente leg "Ana")
        _v2 (vereador-com-mandato! ente leg "Bruno") ; sem evento -> ausente
        sid (abrir-e-encerrar! ente (fn [tx sid] (ev! tx ente sid v1 "entrada" "plenario")))
        sessao-real (repo-sessoes/buscar-sessao *repo-s* ente sid)
        doc (controllers/folha-da-sessao *repo-s* (roster-seam) (dados-da-casa-seam) (ator ente) sid
                                         (tempo/relogio-fixo (Instant/now)))
        ;; a conta INDEPENDENTE: roster + presenca corrente + justificativas, resolvidos de novo aqui, e
        ;; passados PELA PRIMEIRA VEZ (deste teste) por derivar-linhas-da-chamada/contar-quorum.
        data-de-hoje (tempo/hoje-de (:encerrada-em sessao-real) tempo/zona-civil-padrao)
        roster (repo-cadastros/roster-da-casa *repo-c* ente data-de-hoje)
        presencas (repo-sessoes/presenca-corrente *repo-s* ente sid (:encerrada-em sessao-real))
        justs (repo-sessoes/listar-justificativas *repo-s* ente sid)
        linhas-independentes (mapv :linha (logic/derivar-linhas-da-chamada roster presencas justs))
        quorum-independente (logic/contar-quorum linhas-independentes)]
    (is (= quorum-independente (:quorum doc))
        "o quorum do documento nao pode divergir da conta feita por fora — senao a folha tem uma TERCEIRA aritmetica")
    (is (= 1 (:presentes-plenario quorum-independente)) "e o numero e' o esperado, nao um zero mudo dos dois lados")))

;; ---------- nao_realizada: o documento carrega o MOTIVO ----------

(deftest sessao-nao-realizada-traz-o-motivo-no-documento
  (let [ente (random-uuid)
        leg (casa! ente)
        _v1 (vereador-com-mandato! ente leg "Ana")
        sid (repo-sessoes/transacao *repo-s* ente
              (fn [tx]
                (:id (sessao/agendar! tx {:id (random-uuid) :ente-id ente
                                          :sessao-legislativa-id (random-uuid)
                                          :tipo-sessao "ordinaria" :agendada-para (Instant/now)}))))
        _ (repo-sessoes/transacao *repo-s* ente
            (fn [tx] (sessao/transicionar! tx {:id sid :ente-id ente :para "nao_realizada"
                                               :motivo "falta de quorum" :lock-version 0})))
        doc (controllers/folha-da-sessao *repo-s* (roster-seam) (dados-da-casa-seam) (ator ente) sid
                                         (tempo/relogio-fixo (Instant/now)))]
    (is (= "nao_realizada" (:estado (:sessao doc))))
    (is (= "falta de quorum" (:motivo-nao-realizada (:sessao doc))))))

;; ---------- authz: a mesma politica da chamada NOMINAL (mesma Casa), nao a magra do quorum ----------

(deftest ator-de-outra-casa-nao-ve-nada-rls-isola
  (let [ente-a (random-uuid)
        ente-b (random-uuid)
        leg (casa! ente-a)
        _v1 (vereador-com-mandato! ente-a leg "Ana")
        sid (abrir-e-encerrar! ente-a (fn [_tx _sid] nil))]
    (is (nil? (controllers/folha-da-sessao *repo-s* (roster-seam) (dados-da-casa-seam) (ator ente-b) sid
                                           (tempo/relogio-fixo (Instant/now))))
        (str "sessao de outra Casa: a RLS ja' a esconde (com-tenant* escopa ao ente-b) -> nil de "
             "repo/chamada-da-sessao -> nil aqui, nunca vaza"))))
