(ns oplenario.legislativo.db.proposicao
  "Persistencia da proposicao — funcoes sobre a `tx` do tenant (RLS isola). HoneySQL no schema
  'legislativo' (NAO e' port). `protocolar!` e' o ato atomico do gate eixo H: sequencial gapless
  (kernel/sequencial) + URN/LexML (logic) + insert, tudo na MESMA tx (rollback nao deixa buraco)."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]
            [oplenario.kernel.sequencial :as sequencial]
            [oplenario.legislativo.logic :as logic]))

(set! *warn-on-reflection* true)

(def ^:private colunas
  [:id :ente_id :tipo :ano :sequencial :urn_lex :ementa :autor_tipo :autor_id :autor_texto :estado
   :objeto_indicacao :destinatario_id :destinatario_texto :tipo_requerimento :categoria_mocao
   :atributos_especificos :texto_vigente_versao_id])

(defn- linha->proposicao [linha]
  (when linha
    (update (comum/linha->kebab linha) :atributos-especificos comum/jsonb->kw)))

(defn protocolar!
  "Protocola: gera o sequencial gapless (escopo 'tipo:ano' do ente da SESSAO), computa a URN/LexML
  (eixo H) e insere — atomico na tx. uf/municipio-nome = FATO do ente resolvido UPSTREAM (nao JOIN
  cross-schema, §22.10). Devolve {:id :sequencial :urn-lex} (o numero so existe pos-commit)."
  [tx {:keys [id ente-id tipo ano uf municipio-nome ementa autor-tipo autor-id autor-texto
              objeto-indicacao destinatario-id destinatario-texto tipo-requerimento categoria-mocao
              atributos-especificos created-by]}]
  (let [seq-val (sequencial/proximo! tx (str tipo ":" ano))
        urn     (logic/urn-lex {:uf uf :municipio-nome municipio-nome :tipo tipo :ano ano :sequencial seq-val})]
    (jdbc/execute-one! tx
      (sql/format {:insert-into :legislativo.proposicoes
                   :values [{:id id :ente_id ente-id :tipo tipo :ano ano :sequencial seq-val :urn_lex urn
                             :ementa ementa :autor_tipo autor-tipo :autor_id autor-id :autor_texto autor-texto
                             :objeto_indicacao objeto-indicacao :destinatario_id destinatario-id
                             :destinatario_texto destinatario-texto :tipo_requerimento tipo-requerimento
                             :categoria_mocao categoria-mocao :estado "protocolada"
                             :atributos_especificos (some-> atributos-especificos comum/->jsonb)
                             :created_by created-by :efetivado_em [:now]}]}))
    {:id id :sequencial seq-val :urn-lex urn}))

;; NOTA: proposicoes e' hash-particionada por ente_id -> toda query inclui ente_id no WHERE (partition
;; pruning + uso do indice composto; a RLS e' funcao volatil, o planner NAO a usa p/ podar particao).
(defn buscar [tx ente-id id]
  (linha->proposicao
   (jdbc/execute-one! tx
     (sql/format {:select colunas :from [:legislativo.proposicoes]
                  :where [:and [:= :ente_id ente-id] [:= :id id]]}))))

(defn listar-por-estado [tx ente-id estado]
  (mapv linha->proposicao
        (jdbc/execute! tx
          (sql/format {:select colunas :from [:legislativo.proposicoes]
                       :where [:and [:= :ente_id ente-id] [:= :estado estado]]
                       :order-by [[:ano :desc] [:sequencial :desc]]}))))

;; ---------- Onda B Slice 1: lista filtravel/ordenavel/paginada do servidor ----------

(def ^:private colunas-ordenacao
  "Allowlist string(querystring) -> coluna HoneySQL (defesa-em-profundidade: adapters/in ja' rejeitou
  qualquer string fora deste vocabulario -> 400; aqui NUNCA se interpola a string do usuario direto no SQL,
  so' se faz o lookup seguro — default 'atualizado_em' se a chave nao bater por algum motivo)."
  {"atualizado_em" :atualizado_em "sequencial" :sequencial "ano" :ano})

(defn- where-listagem
  [ente-id {:keys [busca tipo estado autor-id ano]}]
  (cond-> [[:= :ente_id ente-id]]
    tipo     (conj [:= :tipo tipo])
    estado   (conj [:= :estado estado])
    autor-id (conj [:= :autor_id autor-id])
    ano      (conj [:= :ano ano])
    busca    (conj [:or [:ilike :ementa (str "%" busca "%")] [:ilike :urn_lex (str "%" busca "%")]])))

(defn listar
  "Onda B Slice 1 — lista filtravel/ordenavel/paginada do servidor (fonte da verdade, NAO read-model
  assincrono). Filtro OPCIONAL e combinavel (chave ausente/nil nao filtra); `busca` e' ILIKE substring
  case-insensitive em ementa+urn_lex (sem indice novo — volume por-tenant limitado, hash-particionado; vira
  carry de indice trigram se aparecer lentidao real). Desempate ESTAVEL sempre por :id (mesma disciplina de
  transparencia/db/norma/listar — paginacao sem desempate fixo pode duplicar/pular linha entre paginas em
  empate de `ordenar-por`)."
  [tx ente-id {:keys [pagina tamanho ordenar-por ordenar-dir] :as filtro}]
  {:pre [(some? ente-id) (pos-int? pagina) (pos-int? tamanho)]}
  (let [col (get colunas-ordenacao ordenar-por :atualizado_em)
        dir (if (= "asc" ordenar-dir) :asc :desc)]
    (comum/linhas->kebab
     (jdbc/execute! tx
       (sql/format {:select colunas :from [:legislativo.proposicoes]
                    :where (into [:and] (where-listagem ente-id filtro))
                    :order-by [[col dir] [:id :asc]]
                    :limit tamanho
                    :offset (* (dec pagina) tamanho)})))))

(defn contar
  "Total de linhas do MESMO filtro de conteudo de `listar` (ignora pagina/tamanho/ordenacao — so' a
  paginacao do wire/out precisa do total)."
  [tx ente-id filtro]
  {:pre [(some? ente-id)]}
  (:total
   (comum/linha->kebab
    (jdbc/execute-one! tx
      (sql/format {:select [[[:count :*] :total]] :from [:legislativo.proposicoes]
                   :where (into [:and] (where-listagem ente-id filtro))})))))

(defn mudar-estado!
  "Transicao COARSE do estado (a maquina fina e' a tramitacao F3.3). CAS por `lock-version` (compare-and-swap
  honesto: o WHERE casa a versao esperada e o bump so vale se ninguem escreveu no meio — dois escritores do
  mesmo estado nao-terminal nao se sobrescrevem em silencio). O trigger trava transicoes a partir de estado
  terminal (exceto correcao auditada). Aqui so o set; o guard de regra/autorizacao e' do controller/motor.
  Lanca em conflito de versao OU row inexistente (0 linhas afetadas)."
  [tx {:keys [id ente-id estado updated-by lock-version]}]
  (let [r (jdbc/execute-one! tx
            (sql/format {:update :legislativo.proposicoes
                         :set {:estado estado :updated_by updated-by :atualizado_em [:now]
                               :lock_version [:+ :lock_version 1]}
                         :where [:and [:= :ente_id ente-id] [:= :id id] [:= :lock_version lock-version]]}))]
    (when (zero? (:next.jdbc/update-count r 0))
      (throw (ex-info "conflito de escrita (lock_version desatualizado) ou proposicao inexistente"
                      {:id id :lock-version lock-version})))
    r))
