(ns oplenario.legislativo.notificacao-autor-test
  "INTEGRACAO (PG real) — Onda E fatia 1: o PRODUTOR interno. `legislativo` consome o PROPRIO
  `norma.publicada` (2o consumidor, dedup independente), resolve proposicao->autor (same-schema) e
  vereador->identidade pelo RESOLVEDOR INJETADO PELO HOST (legislativo NUNCA importa cadastros, §22.10),
  e emite `notificacao.requisitada` de canal in_app. `paineis` projeta na inbox. Prova o vertical inteiro
  com o relay REAL, alem dos criterios de aceitacao 1 (uma notificacao, idempotente) e 2 (autor sem
  identidade -> nada, e sem erro)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [clojure.tools.logging.test :refer [logged? with-log]]
            [com.stuartsierra.component :as component]
            [next.jdbc :as jdbc]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.eventos :as eventos]
            [oplenario.kernel.outbox :as outbox]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.legislativo.components.repositorio :as legislativo-repo]
            [oplenario.legislativo.db.proposicao :as db-proposicao]
            [oplenario.legislativo.diplomat.consumers :as legislativo-consumers]
            [oplenario.migracao :as migracao]
            [oplenario.paineis.diplomat.consumers :as paineis-consumers])
  (:import (java.time LocalDate)))

(def ^:dynamic *ds* nil)
(def ^:dynamic *leg* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c)
                *leg* (legislativo-repo/->RepoLegislativoPg c (outbox/bus))]
        (try (t) (finally (component/stop c)))))))

;; resolvedor FAKE injetado (o host injeta o real, que le' cadastros.vereador): vereador-id -> identidade-id
(defn- resolver-fixo [mapa] (fn [_tx _ente-id vereador-id] (get mapa vereador-id)))

;; resolvedor CORINGA (achado 2 da revisao): resolve QUALQUER vereador-id p/ uma identidade fixa — usado SO'
;; em `autor-nao-vereador-nao-notifica`, pra que o UNICO motivo de nao notificar seja `autor_tipo != 'vereador'`,
;; nunca a ausencia de `:autor-id` nem a falta de identidade resolvivel (os dois outros gates da fn sob teste).
(def ^:private identidade-coringa (random-uuid))
(defn- resolver-coringa [_tx _ente-id _vereador-id] identidade-coringa)

(defn- drenar! [resolver]
  (outbox/drenar! *ds* (-> {}
                           (legislativo-consumers/registrar resolver)
                           (paineis-consumers/registrar))))

(defn- outbox-do-ente
  "Quantos eventos DESTE ente estao no outbox, por estado. `drenar!` devolve uma contagem GLOBAL — ela
  nao serve de assercao numa suite que compartilha o banco."
  [ente]
  (let [linha (jdbc/execute-one! *ds*
                                 ["SELECT count(*) FILTER (WHERE processed_at IS NOT NULL) AS processados,
                                          count(*) FILTER (WHERE processed_at IS NULL)     AS pendentes
                                     FROM shared.outbox WHERE ente_id = ?" ente])]
    {:processados (:processados linha) :pendentes (:pendentes linha)}))

(defn- caixa [ente]
  (tenancy/com-tenant* *ds* ente
    (fn [tx] (jdbc/execute! tx ["SELECT destinatario_identidade_id, categoria, assunto, objeto_id
                                 FROM paineis.notificacao_caixa"]))))

(defn- publicar-norma-de-autor!
  "Protocola uma proposicao com autor vereador, leva ao desfecho promulgavel, promulga e publica.
  Devolve {:proposicao-id :norma-id}."
  [ente vereador-id]
  (let [{pid :id} (legislativo-repo/protocolar! *leg* ente
                    {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026 :uf "CE"
                     :municipio-nome "Fortaleza" :ementa "Dispoe sobre as hortas comunitarias."
                     :autor-tipo "vereador" :autor-id vereador-id :autor-texto "Ver. Fulana"})
        {aid :id} (legislativo-repo/gerar-autografo! *leg* ente
                    {:id (random-uuid) :proposicao-id pid :ano 2026
                     :texto-versao-id (random-uuid)
                     :destinatario-texto "Prefeito Municipal de Fortaleza"})
        {tid :id} (legislativo-repo/iniciar-tramitacao-executiva! *leg* ente
                    {:id (random-uuid) :autografo-id aid})]
    (legislativo-repo/registrar-resposta-executivo! *leg* ente
      {:id tid :resultado "sancionado" :updated-by nil :lock-version 0})
    (let [{nid :id} (legislativo-repo/promulgar-norma! *leg* ente
                      {:id (random-uuid) :proposicao-id pid :autografo-id aid :tipo-norma "lei"
                       :ano 2026 :uf "CE" :municipio-nome "Fortaleza"
                       :data-promulgacao (LocalDate/of 2026 6 28)
                       :ementa "Dispoe sobre as hortas comunitarias." :texto-versao-id (random-uuid)})]
      (legislativo-repo/publicar-norma! *leg* ente
        {:id nid :veiculo-publicacao "Diario Oficial do Municipio" :updated-by nil :lock-version 0})
      {:proposicao-id pid :norma-id nid})))

(deftest norma-publicada-notifica-o-autor-vereador
  (let [ente (random-uuid) vereador (random-uuid) identidade (random-uuid)
        {:keys [proposicao-id]} (publicar-norma-de-autor! ente vereador)]
    (drenar! (resolver-fixo {vereador identidade}))
    (let [linhas (caixa ente)]
      (is (= 1 (count linhas)) "criterio 1: UMA notificacao na inbox do autor")
      (let [l (first linhas)]
        (is (= identidade (:notificacao_caixa/destinatario_identidade_id l)) "endereçada a identidade dele")
        (is (= "norma_publicada" (:notificacao_caixa/categoria l)))
        (is (= proposicao-id (:notificacao_caixa/objeto_id l)) "o clique leva a' materia")))))

(deftest reprocessar-nao-duplica
  (let [ente (random-uuid) vereador (random-uuid) identidade (random-uuid)
        resolver (resolver-fixo {vereador identidade})]
    (publicar-norma-de-autor! ente vereador)
    (drenar! resolver)
    ;; REDRIVE DE VERDADE (achado 1 da revisao): so' reexecutar `drenar!` sobre o outbox ja' esvaziado e' um
    ;; no-op — `drenar-um!` seleciona `WHERE processed_at IS NULL`, e apos a 1a `drenar!` tudo ja' esta'
    ;; marcado. Sem isto, o teste passaria mesmo com o `ON CONFLICT ... DO NOTHING` do projetor REMOVIDO e a
    ;; chave de idempotencia trocada por uma string aleatoria — nao provaria nada. Reabre os eventos deste
    ;; ente (`processed_at = NULL`) + apaga o ledger de dedup do relay (`shared.evento_consumido`) — simula
    ;; um redrive/backfill honesto, exatamente o cenario que o ON CONFLICT existe pra proteger.
    (jdbc/execute! *ds* ["UPDATE shared.outbox SET processed_at = NULL WHERE ente_id = ?" ente])
    (jdbc/execute! *ds* ["DELETE FROM shared.evento_consumido WHERE ente_id = ?" ente])
    (drenar! resolver)   ; 2a passada REAL: o handler roda de novo; o ON CONFLICT (ente_id,idempotency_key) segura
    (is (= 1 (count (caixa ente))) "criterio 1: reexecutar nao cria uma segunda notificacao")))

(deftest autor-sem-identidade-vinculada-nao-notifica-e-nao-quebra
  (let [ente (random-uuid) vereador (random-uuid)]
    (publicar-norma-de-autor! ente vereador)
    ;; achado 6: `(some? (drenar! ...))` e' vacuo — `drenar!` devolve um INTEIRO, e `(some? 0)` tambem e'
    ;; `true`; o teste passaria igual se `norma.publicada` nem existisse. O fluxo emite exatamente 2 eventos
    ;; (`proposicao.protocolada` em `protocolar!` + `norma.publicada` em `publicar-norma!` — nenhum outro
    ;; passo do fixture emite) e, sem identidade resolvivel, NENHUM `notificacao.requisitada` e' gerado —
    ;; a contagem real drenada prova que os dois eventos do fluxo de fato passaram pelo relay.
    ;; FLAKE ESTRUTURAL (T1.3): `outbox/drenar!` nao e' escopado por ente — ele drena o outbox INTEIRO
    ;; do banco e devolve essa contagem global. Com outros testes na mesma suite (ou a stack de pe',
    ;; com o relay da aplicacao emitindo), `(= 2 ...)` reprovava em run cheio e passava isolado. A
    ;; pergunta que o criterio 2 realmente faz e' escopada: os 2 eventos DESTE fluxo passaram pelo relay?
    (drenar! (resolver-fixo {}))
    (let [{:keys [processados pendentes]} (outbox-do-ente ente)]
      (is (= 2 processados) "criterio 2: os 2 eventos deste fluxo foram processados pelo relay")
      (is (zero? pendentes) "nenhum evento deste fluxo ficou pendente — o relay nao lancou no meio"))
    (is (empty? (caixa ente)) "sem identidade resolvivel -> silencio honesto, nenhuma notificacao")))

(deftest autor-nao-vereador-nao-notifica
  (let [ente (random-uuid) autor-executivo (random-uuid)
        ;; achado 2: `:autor-id` PRESENTE (o Executivo tambem tem um id de autor no mundo real) + o
        ;; `resolver-coringa` (resolve QUALQUER vereador-id) — assim os outros dois gates da fn sob teste
        ;; (`[:is-not :autor_id nil]` e a resolucao de identidade) ficam SEMPRE satisfeitos, e o UNICO motivo
        ;; que pode barrar a notificacao e' `autor_tipo != 'vereador'`, que e' o que este teste diz provar.
        {pid :id} (legislativo-repo/protocolar! *leg* ente
                    {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026 :uf "CE"
                     :municipio-nome "Fortaleza" :ementa "De autoria do Executivo."
                     :autor-tipo "executivo" :autor-id autor-executivo :autor-texto "Prefeitura"})]
    (let [{aid :id} (legislativo-repo/gerar-autografo! *leg* ente
                      {:id (random-uuid) :proposicao-id pid :ano 2026 :texto-versao-id (random-uuid)
                       :destinatario-texto "Prefeito"})
          {tid :id} (legislativo-repo/iniciar-tramitacao-executiva! *leg* ente
                      {:id (random-uuid) :autografo-id aid})]
      (legislativo-repo/registrar-resposta-executivo! *leg* ente
        {:id tid :resultado "sancionado" :updated-by nil :lock-version 0})
      (let [{nid :id} (legislativo-repo/promulgar-norma! *leg* ente
                        {:id (random-uuid) :proposicao-id pid :autografo-id aid :tipo-norma "lei"
                         :ano 2026 :uf "CE" :municipio-nome "Fortaleza"
                         :data-promulgacao (LocalDate/of 2026 6 28) :ementa "De autoria do Executivo."
                         :texto-versao-id (random-uuid)})]
        (legislativo-repo/publicar-norma! *leg* ente
          {:id nid :veiculo-publicacao "DOM" :updated-by nil :lock-version 0})))
    (drenar! resolver-coringa)
    (is (empty? (caixa ente))
        "autor_tipo != 'vereador' -> nao ha' dono nominal, nao notifica (mesmo com :autor-id presente e um
         resolvedor que resolve QUALQUER id — o UNICO gate e' autor_tipo)")))

;; ---------- frente 'relay-observavel': as duas classes de log ----------

(defn- outbox-id-do-tipo [ente tipo]
  (:outbox/id (jdbc/execute-one! *ds*
                ["SELECT id FROM shared.outbox WHERE ente_id = ? AND tipo = ?" ente tipo])))

(deftest evento-malformado-loga-warn-com-o-id-da-linha
  ;; Envelope CRU de `eventos/evento` (sem passar pela validacao Malli do construtor real,
  ;; `events.notificacao/publicada` — o mesmo truque de `transparencia.relay-tolerante-test/gravar!`):
  ;; e' o unico jeito de por um `:proposicao-id` nao-UUID no outbox, o formato que so' um teste ou um
  ;; redrive de payload legado escreveria.
  (let [ente (random-uuid)
        ev (eventos/evento "norma.publicada" ente
             {:proposicao-id "nao-e-uuid" :norma-id (str (random-uuid))})]
    (jdbc/with-transaction [tx *ds*] (eventos/emitir! (outbox/bus) tx ev))
    (let [oid (outbox-id-do-tipo ente "norma.publicada")]
      (with-log
        (drenar! (resolver-fixo {}))
        ;; `logged?` de 3-aridade compara o throwable contra `nil` por IGUALDADE — sempre falso quando o
        ;; log carrega uma excecao de verdade. A 4-aridade com a CLASSE medida
        ;; (IllegalArgumentException — UUID/fromString com string mal-formada) e' o matcher certo, mesmo
        ;; idioma do precedente `transparencia.relay-tolerante-test`.
        (is (logged? 'oplenario.legislativo.components.repositorio :warn
                     IllegalArgumentException #"payload malformado")
            "UUID/fromString invalido (:proposicao-id) e' classificado como FORMA DO PAYLOAD -> :warn")
        (is (logged? 'oplenario.legislativo.components.repositorio :warn
                     IllegalArgumentException (re-pattern (str oid)))
            "o :id da linha do outbox aparece no log — achavel sem SELECT de adivinhacao")
        (is (not (logged? 'oplenario.legislativo.components.repositorio :error Throwable #"NAO projetado"))
            "payload malformado NUNCA loga como perda de infra")))))

(deftest falha-de-infra-loga-error-nomeando-a-perda-com-o-id-da-linha
  ;; simula falha de INFRA (nao forma do dado) na LEITURA same-schema (`autor-vereador-da-proposicao`,
  ;; fn PLANA de nivel superior — nao metodo de protocolo num defrecord com impl INLINE; with-redefs
  ;; funciona aqui sem cair na armadilha do fast-path de despacho de protocolo, medida na frente
  ;; anterior). Mesma classe (`java.sql.SQLException`) do contraste de
  ;; `transparencia.relay-tolerante-test/erro-de-infra-propaga-em-vez-de-ser-descartado` — so' que AQUI
  ;; o guard TOLERA (legislativo nunca lanca, por desenho); so' o NIVEL do log muda.
  (let [ente (random-uuid) vereador (random-uuid) identidade (random-uuid)]
    (publicar-norma-de-autor! ente vereador)
    (let [oid (outbox-id-do-tipo ente "norma.publicada")]
      (with-redefs [db-proposicao/autor-vereador-da-proposicao
                    (fn [& _] (throw (java.sql.SQLException. "conexao caiu (simulado)")))]
        (with-log
          (is (nil? (try (drenar! (resolver-fixo {vereador identidade})) nil (catch Throwable e e)))
              "a falha de infra e' TOLERADA igual — o catch continua Throwable inteiro (NAO estreitar)")
          (is (logged? 'oplenario.legislativo.components.repositorio :error
                       java.sql.SQLException #"NAO projetado e NAO sera' reprocessado")
              "SQLException nao reconhecida -> :error nomeando a PERDA, nao um :warn de rotina")
          (is (logged? 'oplenario.legislativo.components.repositorio :error
                       java.sql.SQLException (re-pattern (str oid)))
              "o :id da linha do outbox aparece no log — achavel sem SELECT de adivinhacao")
          (is (not (logged? 'oplenario.legislativo.components.repositorio :warn Throwable #"payload malformado"))
              "falha de infra NUNCA loga como rotina de dado malformado"))))
    (is (empty? (caixa ente)) "sem a leitura same-schema, nao ha' dono a notificar — inbox vazia")))
