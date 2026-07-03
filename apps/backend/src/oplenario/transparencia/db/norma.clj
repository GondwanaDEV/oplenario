(ns oplenario.transparencia.db.norma
  "Persistencia de 'transparencia.norma' (F6c Slice 1, feature 16.5 — legislacao PUBLICADA as-enacted, i.e.
  o texto tal qual promulgado/publicado) — funcoes sobre a `tx` corrente (FORCE RLS isola, mig 0044).
  HoneySQL schema-qualified; ente_id em TODA query. Read-model PROJETADO, INSERT-only (imutavel apos
  publicada) do evento `norma.publicada`. Chamado pelo CONSUMER, dentro da tx do relay (mesmo racional de
  db/materia). NOTA DE VOCABULARIO (review architect MEDIUM-3): isto NAO e' 'legislacao consolidada' no
  sentido juridico (texto compilado com alteracoes POSTERIORES, as-amended) — e' o snapshot as-enacted no
  momento da publicacao. A consolidacao viva (Slice 3, editor estruturado) exigira mais que esta tabela
  (relacoes de norma-alteradora->norma-alterada e/ou texto mutavel versionado) — nao e' so' adicionar linhas."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]))

(set! *warn-on-reflection* true)

(def ^:private cols
  [:ente_id :norma_id :proposicao_id :tipo_norma :numero :ano :urn :ementa :publicado_em :veiculo_publicacao
   :projetado_em])

(def ^:private teto-listagem 200)

(defn inserir!
  "Projeta a norma PUBLICADA (`norma.publicada`). `publicado-em` chega como java.time.Instant (parseado do
  ISO-8601 do payload pelo consumer). `ON CONFLICT (ente_id,norma_id) DO NOTHING` (review db MEDIUM) —
  mesmo cinto-de-seguranca de db/materia contra redrive futuro com idempotency-key nova; sem isto, um
  redrive lancaria PK-violation e envenenaria o relay COMPARTILHADO (ver db/materia/atualizar-estado!)."
  [tx {:keys [ente-id norma-id proposicao-id tipo-norma numero ano urn ementa publicado-em veiculo-publicacao]}]
  {:pre [(some? ente-id) (some? norma-id) (some? proposicao-id) (some? tipo-norma) (some? numero)
         (some? ano) (some? urn) (some? ementa) (some? publicado-em) (some? veiculo-publicacao)]}
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:insert-into :transparencia.norma
                  :values [{:ente_id ente-id :norma_id norma-id :proposicao_id proposicao-id
                            :tipo_norma tipo-norma :numero numero :ano ano :urn urn :ementa ementa
                            :publicado_em publicado-em :veiculo_publicacao veiculo-publicacao}]
                  :on-conflict [:ente_id :norma_id]
                  :do-nothing []
                  :returning [:*]}))))

(defn buscar
  "Uma norma publicada por id (RLS via ente-id). Devolve o mapa kebab-case ou nil."
  [tx ente-id norma-id]
  {:pre [(some? ente-id) (some? norma-id)]}
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:select cols :from [:transparencia.norma]
                  :where [:and [:= :ente_id ente-id] [:= :norma_id norma-id]]}))))

(defn buscar-por-proposicao
  "A norma publicada de uma materia, se houver (a ficha da materia liga p/ ela). Devolve nil se a materia
  nunca foi promulgada/publicada."
  [tx ente-id proposicao-id]
  {:pre [(some? ente-id) (some? proposicao-id)]}
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:select cols :from [:transparencia.norma]
                  :where [:and [:= :ente_id ente-id] [:= :proposicao_id proposicao-id]]}))))

(defn listar-publicadas
  "Portal PUBLICO — legislacao PUBLICADA as-enacted (feature 16.5): mais recentes primeiro, com teto."
  [tx ente-id]
  {:pre [(some? ente-id)]}
  (comum/linhas->kebab
   (jdbc/execute! tx
     (sql/format {:select cols :from [:transparencia.norma]
                  :where [:= :ente_id ente-id]
                  :order-by [[:publicado_em :desc]]
                  :limit teto-listagem}))))
