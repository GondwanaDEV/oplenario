(ns oplenario.sessoes.anuncio-repo-test
  "INTEGRACAO (PG real): o ANUNCIO DE ITEM DA PAUTA (docs/23 Fatia 4b) pelo Repo-Component de SESSOES — o ato
  append-only + o evento `pauta.item-anunciado` no shared.outbox na MESMA tx (§22.9 E2), os gates de estado
  (so' sessao `aberta`) e de item ativo DENTRO da tx, e a deduplicacao do reenvio (duplo clique no mesmo item
  nao vira dois atos nem dois eventos). Prova o caminho de producao (via o Component, nao o db/ direto)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [next.jdbc :as jdbc]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.outbox :as outbox]
            [oplenario.migracao :as migracao]
            [oplenario.sessoes.components.repositorio :as repo]))

(def ^:dynamic *ds* nil)
(def ^:dynamic *repo* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds*   (:ds c)
                *repo* (repo/->RepoSessoesPg c (outbox/bus))]
        (try (t) (finally (component/stop c)))))))

(defn- eventos-anunciados [ente]
  (jdbc/execute! *ds*
    ["SELECT payload::text AS payload FROM shared.outbox
      WHERE ente_id = ? AND tipo = 'pauta.item-anunciado' ORDER BY id" ente]))

(def ^:private t0 (java.time.Instant/parse "2026-09-24T12:05:00Z"))
(defn- mais [^java.time.Instant t s] (.plusSeconds t s))

(defn- sessao-com-pauta!
  "Agenda uma sessao, abre (se `abrir?`) e monta a pauta: um item de materia e uma leitura. Devolve
  {:sid :materia :leitura :prop}."
  [ente abrir?]
  (let [autor (random-uuid)
        sid   (:id (repo/agendar-sessao! *repo* ente {:id (random-uuid) :sessao-legislativa-id (random-uuid)
                                                      :tipo-sessao "ordinaria" :modalidade "presencial"}))
        prop  (random-uuid)
        mat   (:id (repo/adicionar-item-na-sessao! *repo* ente {:id (random-uuid) :sessao-id sid :fase "ordem_do_dia"
                                                                :tipo-item "proposicao" :proposicao-id prop
                                                                :created-by autor}))
        leit  (:id (repo/adicionar-item-na-sessao! *repo* ente {:id (random-uuid) :sessao-id sid :fase "expediente"
                                                                :tipo-item "leitura" :texto-descricao "Leitura da ata"
                                                                :created-by autor}))]
    (when abrir?
      (repo/transicionar-sessao! *repo* ente {:id sid :para "aberta" :updated-by autor :lock-version 0}))
    {:sid sid :materia mat :leitura leit :prop prop}))

(defn- anunciar! [ente sid item quando]
  (repo/anunciar-item! *repo* ente {:id (random-uuid) :sessao-id sid :pauta-item-id item
                                    :anunciado-em quando :created-by (random-uuid)}))

(deftest anunciar-grava-o-ato-e-emite-o-evento-na-mesma-tx
  (let [ente (random-uuid)
        {:keys [sid materia prop]} (sessao-com-pauta! ente true)
        r (anunciar! ente sid materia t0)]
    (is (= materia (:pauta-item-id r)))
    (is (= t0 (:anunciado-em r)) "o instante de dominio e' o que a borda passou (relogio do servidor)")
    (is (not (:ja-anunciado r)))
    (let [evs (eventos-anunciados ente)]
      (is (= 1 (count evs)) "exatamente 1 pauta.item-anunciado")
      (let [pl (:payload (first evs))]
        (is (re-find (re-pattern (str sid)) pl) "carrega a sessao-id (rota do canal plenario)")
        (is (re-find (re-pattern (str materia)) pl) "carrega o item anunciado")
        (is (re-find (re-pattern (str prop)) pl) "item de materia carrega o proposicao-id (casa com o placar)")
        (is (re-find #"2026-09-24T12:05:00Z" pl) "anunciado-em como ISO-8601 string")))
    (is (= materia (:pauta-item-id (repo/item-em-apreciacao *repo* ente sid))))))

(deftest reanunciar-o-mesmo-item-nao-duplica-o-ato-nem-o-evento
  (let [ente (random-uuid)
        {:keys [sid materia]} (sessao-com-pauta! ente true)
        r1 (anunciar! ente sid materia t0)
        r2 (anunciar! ente sid materia (mais t0 5))]
    (is (:ja-anunciado r2) "o reenvio e' reconhecido")
    (is (= (:id r1) (:id r2)) "devolve o anuncio EXISTENTE, nao um novo")
    (is (= 1 (count (eventos-anunciados ente))) "nenhum evento a mais")))

(deftest anunciar-outro-item-troca-o-item-em-apreciacao
  (let [ente (random-uuid)
        {:keys [sid materia leitura]} (sessao-com-pauta! ente true)]
    (anunciar! ente sid leitura t0)
    (anunciar! ente sid materia (mais t0 60))
    (is (= materia (:pauta-item-id (repo/item-em-apreciacao *repo* ente sid))) "o ULTIMO anuncio e' o item em apreciacao")
    (is (= 2 (count (eventos-anunciados ente))))
    ;; voltar a um item ja' anunciado antes (materia adiada que volta) e' um ato NOVO, nao reenvio
    (let [r (anunciar! ente sid leitura (mais t0 120))]
      (is (not (:ja-anunciado r)))
      (is (= leitura (:pauta-item-id (repo/item-em-apreciacao *repo* ente sid)))))))

(deftest so-se-anuncia-com-a-sessao-aberta
  (let [ente (random-uuid)
        {:keys [sid materia]} (sessao-com-pauta! ente false)
        e (try (anunciar! ente sid materia t0) nil (catch clojure.lang.ExceptionInfo e e))]
    (is (= :conflito/anuncio (:tipo (ex-data e))))
    (is (= :sessao-nao-aberta (:motivo (ex-data e))) "sessao agendada recusa")
    (is (empty? (eventos-anunciados ente)) "recusa nao emite evento")
    (is (nil? (repo/item-em-apreciacao *repo* ente sid)) "nem grava o ato")))

(deftest item-retirado-da-pauta-nao-se-anuncia
  (let [ente (random-uuid)
        {:keys [sid materia]} (sessao-com-pauta! ente true)]
    (repo/remover-item! *repo* ente {:id materia :tipo "exclusao" :lock-version 0 :updated-by (random-uuid)})
    (let [e (try (anunciar! ente sid materia t0) nil (catch clojure.lang.ExceptionInfo e e))]
      (is (= :conflito/anuncio (:tipo (ex-data e))))
      (is (= :item-retirado (:motivo (ex-data e))))
      (is (empty? (eventos-anunciados ente))))))

(deftest item-em-apreciacao-de-sessao-sem-anuncio-e-nil
  (let [ente (random-uuid)
        {:keys [sid]} (sessao-com-pauta! ente true)]
    (is (nil? (repo/item-em-apreciacao *repo* ente sid)))))
