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

(defn- where-listar
  "O predicado de `listar`/`contar` (portal PUBLICO) — FONTE UNICA (regra 3 da frente
  'truncamento-familia'): um WHERE repetido nos dois lugares diverge em silencio no dia em que um filtro
  novo entrar num e nao no outro."
  [ente-id {:keys [tipo ano numero]}]
  (cond-> [[:= :ente_id ente-id]]
    tipo   (conj [:= :tipo_norma tipo])
    ano    (conj [:= :ano ano])
    numero (conj [:= :numero numero])))

(defn listar
  "Portal PUBLICO — acervo de legislacao as-enacted (feature 16.5, F6c Slice 3). Filtro OPCIONAL por `:tipo`
  (especie/tipo_norma), `:ano` e `:numero` — todos EXATOS e combinaveis; chave ausente/nil nao filtra
  (especie desconhecida -> lista vazia, tolerante). SEM filtro: mais recentes por publicado_em (contrato do
  Slice 1, preservado). COM qualquer filtro: por (ano DESC, numero DESC) — a ordem natural de 'Lei N/ANO',
  servida por idx_norma_tipo_numero (ente_id, tipo_norma, ano DESC, numero DESC). Teto em ambos os caminhos.
  ORDER BY sempre termina em norma_id DESC — desempate ESTAVEL (a PK e' (ente_id, norma_id)): sem ele,
  empates em (ano,numero) [numeracao reusada entre especies quando se filtra so' por :ano/:numero] ou em
  publicado_em [lote/mesma data] deixariam a ordem — e QUEM cai na borda do LIMIT — a cargo do plano, e uma
  norma podia 'sumir/trocar' entre cargas (inaceitavel em dado legal). NOTA de indice: filtro por :ano ou
  :numero SEM :tipo nao casa o prefixo do indice (tipo_norma e' o 2o nivel) — :numero-so' e' o pior caso
  (faceta menos seletiva) — e cai em scan intra-tenant; aceitavel: o acervo de UMA camara tem cardinalidade
  modesta (RLS por ente_id) e o teto limita o custo. O uso comum inclui :tipo (a especie e' a faceta primaria).

  ARIDADE de 4: `limite` INJETAVEL (achado IMPORTANTE da revisao adversarial — mesmo racional de
  listar-em-tramitacao/pendencia) — SO' para o teste provar 'o total nao capa' sem pagar 201 linhas; a
  rota publica (portal, aridade de 3) cai no default `teto-listagem`. `(min limite teto-listagem)` — nunca
  pede-se mais que o teto server-side, so' menos."
  ([tx ente-id filtro] (listar tx ente-id filtro teto-listagem))
  ([tx ente-id {:keys [tipo ano numero] :as filtro} limite]
   {:pre [(some? ente-id) (pos-int? limite)]}
   (let [filtros? (or tipo ano numero)]
     (comum/linhas->kebab
      (jdbc/execute! tx
        (sql/format {:select cols :from [:transparencia.norma]
                     :where (into [:and] (where-listar ente-id filtro))
                     :order-by (if filtros?
                                 [[:ano :desc] [:numero :desc] [:norma_id :desc]]
                                 [[:publicado_em :desc] [:norma_id :desc]])
                     :limit (min limite teto-listagem)}))))))

(defn contar
  "Quantas normas do acervo (as-enacted, mesmo filtro de `listar`) existem — SEM teto (frente
  'truncamento-familia', sitio (c)): `listar` corta em `teto-listagem` (200) e a rota publica de
  legislacao nao tinha NENHUM sinal de que o acervo tem mais que os 200 primeiros. MESMO predicado de
  `listar` (`where-listar`), senao o proprio total mentiria sobre o que a lista contem."
  [tx ente-id filtro]
  {:pre [(some? ente-id)]}
  (:contagem
   (comum/linha->kebab
    (jdbc/execute-one! tx
      (sql/format {:select [[[:count :*] :contagem]] :from [:transparencia.norma]
                   :where (into [:and] (where-listar ente-id filtro))})))))
