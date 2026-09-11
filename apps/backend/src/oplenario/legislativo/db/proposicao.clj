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
   :template_id
   :objeto_indicacao :destinatario_id :destinatario_texto :tipo_requerimento :categoria_mocao
   :atributos_especificos :texto_vigente_versao_id :lock_version :atualizado_em])

(defn- linha->proposicao [linha]
  (when linha
    (update (comum/linha->kebab linha) :atributos-especificos comum/jsonb->kw)))

;; ---------------------------------------------------------------------------------------------------
;; O elo MATERIA <-> TEMPLATE (mig 0076) — espelha db/parecer/criar!
;; ---------------------------------------------------------------------------------------------------

(defn- template-meta
  "Le estado_inicial + sujeito de UM template do tenant (mesma forma de db/parecer/template-meta). nil =
  inexistente DAQUI: a RLS ja' esconde o template de outro ente, entao 'do vizinho' e 'nao existe' sao a
  mesma resposta — e a FK same-tenant recusa o mesmo par por baixo, caso alguem escreva sem passar aqui."
  [tx ente-id template-id]
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:select [:estado_inicial :sujeito] :from [:legislativo.template_tramitacao]
                  :where [:and [:= :ente_id ente-id] [:= :id template-id]]}))))

(defn- ritos-ativos-do-tenant
  "Templates ATIVOS de sujeito 'proposicao' do tenant. Teto 2 — a decisao so' distingue nenhum / exatamente
  um / mais de um, nao precisa arrastar a tabela de config inteira. `ativo` entra no filtro porque o
  versionamento de template e' por COPIA INTEGRAL (mig 0016): sem ele, a primeira Casa que versionasse o
  proprio rito passaria a ter 2 linhas e recusaria TODA materia nova por ambiguidade."
  [tx ente-id]
  (comum/linhas->kebab
   (jdbc/execute! tx
     (sql/format {:select [:id :estado_inicial] :from [:legislativo.template_tramitacao]
                  :where [:and [:= :ente_id ente-id] [:= :sujeito [:inline "proposicao"]] [:= :ativo true]]
                  :order-by [[:id :asc]] :limit 2}))))

(defn- resolver-rito!
  "Decide SOB QUAL RITO a materia nasce, e qual e' o estado inicial dela. FALHA FECHADA.

  - `template-id` explicito VENCE sempre (inclusive sobre um tenant que seria ambiguo, e inclusive sobre
    `ativo`: quem nomeia o template esta' declarando a intencao — importacao de acervo entra num rito
    aposentado de proposito). Valida que existe no tenant e que e' de sujeito 'proposicao' (anti-misconfig
    cross-sujeito — o espelho exato de db/parecer/criar!, que recusa template de 'proposicao').
  - ausente + EXATAMENTE UM rito ativo: a materia nasce nele, e o estado vem de `template.estado_inicial`.
    NAO do literal 'protocolada' — cravar a string de estado no codigo e' precisamente o que o Inv.4
    proibe (os valores de `proposicoes.estado` sao chaves de `template_estado`, config do tenant).
  - ausente + NENHUM rito ativo: devolve nil/nil. A materia nasce sem rito e nao tramita — o comportamento
    de hoje, agora explicito. O `estado` fica a cargo do DEFAULT DA COLUNA (schema), nao do codigo.
  - ausente + MAIS DE UM rito ativo: RECUSA. Escolher o rito da Casa por conta (o primeiro, o mais novo,
    o de nome mais parecido) e' exatamente a classe de defeito do T3-A — o chamador escolhendo a regra.

  [CARRY / DECISAO ABERTA — nao resolvida aqui de proposito] Regimentalmente, especies diferentes tem
  ritos diferentes: requerimento nao passa por comissoes como projeto de lei passa, e mocao raramente tem
  parecer. A prazo isto deixa de ser 'um rito por Casa' e vira um mapa `tipo -> template` (provavelmente
  uma coluna `sujeito_tipo` no template, ou uma tabela de vinculo tipo<->rito). Hoje a resolucao automatica
  IGNORA `tipo` — e a regra de 'mais de um recusa' e' justamente o que impede uma Casa de simular esse mapa
  por acidente, cadastrando varios ritos e torcendo para o certo ser escolhido. Quem fecha isto e' o
  Daouda com o regimento na mao, nao a engenharia."
  [tx ente-id template-id]
  (if (some? template-id)
    (let [{:keys [estado-inicial sujeito]} (template-meta tx ente-id template-id)]
      (when (nil? estado-inicial)
        (throw (ex-info "protocolar: template inexistente" {:template-id template-id})))
      (when (not= "proposicao" sujeito)
        (throw (ex-info "protocolar: template nao e' de sujeito 'proposicao' (anti-misconfig cross-sujeito)"
                        {:template-id template-id :sujeito sujeito})))
      {:template-id template-id :estado estado-inicial})
    (let [ritos (ritos-ativos-do-tenant tx ente-id)]
      (case (count ritos)
        0 {:template-id nil :estado nil}
        1 {:template-id (:id (first ritos)) :estado (:estado-inicial (first ritos))}
        (throw (ex-info (str "protocolar: mais de um template de proposicao ativo no tenant — "
                             "informe `template-id` explicitamente (o rito nao se escolhe por conta)")
                        {:ente-id ente-id}))))))

(defn protocolar!
  "Protocola: resolve o RITO (mig 0076), gera o sequencial gapless (escopo 'tipo:ano' do ente da SESSAO),
  computa a URN/LexML (eixo H) e insere — atomico na tx. uf/municipio-nome = FATO do ente resolvido
  UPSTREAM (nao JOIN cross-schema, §22.10). Devolve {:id :sequencial :urn-lex :template-id :estado} (o
  numero so existe pos-commit).

  ORDEM IMPORTA: o rito e' resolvido ANTES de `sequencial/proximo!`. A numeracao oficial e' GAPLESS (eixo
  H) — se a recusa por config ambigua acontecesse depois, cada tentativa recusada abriria um buraco
  permanente na numeracao da Casa.

  `:estado` vem do `:returning` do proprio INSERT, nao de um chute do codigo: quando ha' rito, e' o
  `estado_inicial` do template; quando nao ha', e' o DEFAULT DA COLUNA. Nos dois casos o literal de estado
  mora no dado (template ou schema), nunca aqui — Inv.4. O caller (Repo) usa este retorno para montar o
  payload publico de `proposicao.protocolada`; sem isso o evento afirmaria 'protocolada' enquanto a linha
  diria outra coisa."
  [tx {:keys [id ente-id tipo ano uf municipio-nome ementa autor-tipo autor-id autor-texto
              objeto-indicacao destinatario-id destinatario-texto tipo-requerimento categoria-mocao
              atributos-especificos template-id created-by]}]
  (let [{rito :template-id estado-inicial :estado} (resolver-rito! tx ente-id template-id)
        seq-val (sequencial/proximo! tx (str tipo ":" ano))
        urn     (logic/urn-lex {:uf uf :municipio-nome municipio-nome :tipo tipo :ano ano :sequencial seq-val})
        linha   (comum/linha->kebab
                 (jdbc/execute-one! tx
                   (sql/format {:insert-into :legislativo.proposicoes
                                :values [(cond-> {:id id :ente_id ente-id :tipo tipo :ano ano :sequencial seq-val
                                                  :urn_lex urn :ementa ementa :autor_tipo autor-tipo
                                                  :autor_id autor-id :autor_texto autor-texto
                                                  :objeto_indicacao objeto-indicacao :destinatario_id destinatario-id
                                                  :destinatario_texto destinatario-texto
                                                  :tipo_requerimento tipo-requerimento
                                                  :categoria_mocao categoria-mocao :template_id rito
                                                  :atributos_especificos (some-> atributos-especificos comum/->jsonb)
                                                  :created_by created-by :efetivado_em [:now]}
                                           ;; sem rito, o estado NAO e' setado: cai no DEFAULT da coluna
                                           (some? estado-inicial) (assoc :estado estado-inicial))]
                                :returning [:estado]})))]
    {:id id :sequencial seq-val :urn-lex urn :template-id rito :estado (:estado linha)}))

;; NOTA: proposicoes e' hash-particionada por ente_id -> toda query inclui ente_id no WHERE (partition
;; pruning + uso do indice composto; a RLS e' funcao volatil, o planner NAO a usa p/ podar particao).
(defn buscar [tx ente-id id]
  (linha->proposicao
   (jdbc/execute-one! tx
     (sql/format {:select colunas :from [:legislativo.proposicoes]
                  :where [:and [:= :ente_id ente-id] [:= :id id]]}))))

(defn autor-vereador-da-proposicao
  "Onda E fatia 1: o `autor_id` da proposicao QUANDO o autor e' vereador — a resolucao 'dono nominal' da
  notificacao interna. SAME-SCHEMA (nunca cruza modulo, §22.10); leitura ESTREITA de proposito (so' o id;
  nada de ementa/jsonb — quem renderiza e' a norma). Autor de outro tipo (executivo/comissao/mesa) ou
  proposicao inexistente -> nil, e o consumer simplesmente nao notifica (silencio honesto)."
  [tx ente-id proposicao-id]
  (some-> (jdbc/execute-one! tx
            (sql/format {:select [:autor_id] :from [:legislativo.proposicoes]
                         :where [:and [:= :ente_id ente-id] [:= :id proposicao-id]
                                 [:= :autor_tipo [:inline "vereador"]]
                                 [:is-not :autor_id nil]]}))
          :proposicoes/autor_id))

(defn listar-por-estado [tx ente-id estado]
  (mapv linha->proposicao
        (jdbc/execute! tx
          (sql/format {:select colunas :from [:legislativo.proposicoes]
                       :where [:and [:= :ente_id ente-id] [:= :estado estado]]
                       :order-by [[:ano :desc] [:sequencial :desc]]}))))

;; ---------- Onda B Slice 1: lista filtravel/ordenavel/paginada do servidor ----------

(def ^:private colunas-resumo
  "Onda B Slice 1 (review ecc clojure+database) — subconjunto ESTREITO de `colunas` p/ a LISTAGEM: so' o que
  ProposicaoResumoOut/`resumo->wire` de fato le' (id/tipo/ano/sequencial/urn_lex/ementa/autor_tipo/
  autor_texto/estado/atualizado_em). NUNCA `atributos_especificos` (jsonb, write-oriented) nem
  texto_vigente_versao_id/objeto_indicacao/destinatario_*/tipo_requerimento/categoria_mocao — evita o
  over-fetch e a inconsistencia de decode do jsonb bruto (PGobject) que `linhas->kebab` sozinho nao resolve.
  `buscar`/`listar-por-estado` continuam com `colunas` (o conjunto cheio) p/ os seus proprios callers."
  [:id :tipo :ano :sequencial :urn_lex :ementa :autor_tipo :autor_texto :estado :atualizado_em])

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
       (sql/format {:select colunas-resumo :from [:legislativo.proposicoes]
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
      ;; `:tipo :conflito/transicao` (Fatia 2 — a borda HTTP da tramitacao): TAG, nao mudanca de
      ;; comportamento. Sem ela a colisao de CAS caia no `:else` do interceptor global e virava 500 opaco na
      ;; borda nova — indistinguivel de um guard que explodiu, que e' incidente de CONFIG e exige outra
      ;; acao do operador. Mesma tag que `sessoes/db/sessao.clj` ja' usa p/ o mesmo fato (espelho
      ;; cross-modulo deliberado). Os demais callers nao mudam: `:conflito/*` nao e' tratado pelo
      ;; interceptor global, entao quem nao traduz explicitamente continua vendo 500, como antes.
      (throw (ex-info "conflito de escrita (lock_version desatualizado) ou proposicao inexistente"
                      {:tipo :conflito/transicao :id id :lock-version lock-version})))
    r))

(defn- estado+lock [tx ente-id id]
  (-> (jdbc/execute-one! tx
        (sql/format {:select [:estado :lock_version] :from [:legislativo.proposicoes]
                     :where [:and [:= :ente_id ente-id] [:= :id id]] :for :update}))
      comum/linha->kebab))

(defn editar!
  "Reescreve metadados (PATCH parcial: so' os campos presentes mudam) de uma proposicao NAO-TERMINAL (CAS
  por lock-version; SELECT...FOR UPDATE evita corrida entre o guard de estado e o UPDATE). O trigger
  tambem barra estado terminal (defesa em profundidade); a excecao aqui carrega a causa real. Mesmo padrao
  de db/documento.clj/editar-rascunho!, mas o guard e' 'nao terminal' (a proposicao nao tem fase rascunho —
  mutacao livre ate estado terminal, §22.4.3 disc.4), nao 'so rascunho'.

  Task 1-N1 Peca A: quando `autor-tipo` VEM PRESENTE nesta escrita e nao e' \"vereador\", zera `autor_id`
  na MESMA linha — sem isto o par (autor_tipo, autor_id) podia ficar incoerente (ex.: PATCH so' de
  autor-tipo p/ \"executivo\" deixava o autor_id antigo de vereador na linha), pois o `some?`-gate nao tem
  como EXPRESSAR 'zerar este campo'. `validar-autor!` (controller) ja garante o sentido inverso (autor-id
  presente => autor-tipo = vereador nesta escrita); os dois juntos fecham a coerencia nos dois sentidos —
  autoria e' PUBLICA (transparencia.materia.autor-id), nao pode sobrar elo morto pro portal.

  Task 1-N1 Peca B: `:returning` devolve o estado POS-UPDATE dos campos publicos (ementa/autor_tipo/
  autor_id/autor_texto) — o Repo-Component usa este RETORNO (nao o PATCH parcial recebido) pra montar o
  payload de `proposicao.editada`: um PATCH so' de ementa nao sabe quem e' o autor atual, so' a linha sabe."
  [tx {:keys [id ente-id ementa autor-tipo autor-id autor-texto objeto-indicacao destinatario-id
              destinatario-texto tipo-requerimento categoria-mocao updated-by lock-version]}]
  (let [{:keys [estado]} (estado+lock tx ente-id id)]
    (when (nil? estado)
      (throw (ex-info "editar!: proposicao inexistente" {:id id :ente-id ente-id})))
    (when (contains? logic/estados-proposicao-terminais estado)
      (throw (ex-info "editar!: proposicao em estado terminal nao edita"
                      {:tipo :validacao/invalido :id id :estado estado}))))
  (let [r (jdbc/execute-one! tx
            (sql/format {:update :legislativo.proposicoes
                         :set (cond-> {:updated_by updated-by :atualizado_em [:now] :lock_version [:+ :lock_version 1]}
                                (some? ementa)             (assoc :ementa ementa)
                                (some? autor-tipo)         (assoc :autor_tipo autor-tipo)
                                (some? autor-id)           (assoc :autor_id autor-id)
                                (and (some? autor-tipo)
                                     (not= "vereador" autor-tipo)) (assoc :autor_id nil)
                                (some? autor-texto)        (assoc :autor_texto autor-texto)
                                (some? objeto-indicacao)   (assoc :objeto_indicacao objeto-indicacao)
                                (some? destinatario-id)    (assoc :destinatario_id destinatario-id)
                                (some? destinatario-texto) (assoc :destinatario_texto destinatario-texto)
                                (some? tipo-requerimento)  (assoc :tipo_requerimento tipo-requerimento)
                                (some? categoria-mocao)    (assoc :categoria_mocao categoria-mocao))
                         :where [:and [:= :ente_id ente-id] [:= :id id] [:= :lock_version lock-version]]
                         :returning [:id :ementa :autor_tipo :autor_id :autor_texto]}))]
    ;; :returning faz o UPDATE devolver a LINHA (nao {:next.jdbc/update-count N}) — nil e' o sinal de
    ;; 0-linhas-afetadas (mesmo padrao de paineis/db/notificacao_caixa/marcar-lida!).
    (when (nil? r)
      (throw (ex-info "editar!: conflito de lock_version ou proposicao inexistente"
                      {:tipo :validacao/invalido :id id :lock-version lock-version})))
    (comum/linha->kebab r)))
