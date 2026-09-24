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

(defn estado-no-template
  "O estado `chave` COMO O RITO O DECLARA (`legislativo.template_estado`), ou nil se o rito nao o declara.

  MORA AQUI, e nao em `db/tramitacao.clj` onde nasceu, por uma razao de DIRECAO: `db/tramitacao` ja'
  requer este ns (db->db do mesmo modulo), entao o caminho de volta seria um ciclo. Com tres leitores — a
  engine (`tramitacao/transicionar!`), o guard de edicao (`editar!` logo abaixo) e a leitura composta do
  Repo — a consulta tem de existir UMA vez, no ns que os tres ja' alcancam. O ns tambem nao e' estranho ao
  assunto: e' ele que RESOLVE e guarda o rito da materia (`resolver-rito!`, coluna `template_id`).

  Desambigua a lista de gatilhos VAZIA, que tem causas que pedem acoes OPOSTAS do operador:
    - `{:terminal true}`  -> o rito acabou aqui. Nada a fazer, e esta' certo.
    - `{:terminal false}` -> beco: o rito conhece o estado mas nao declara saida dele. Alguem tem de mexer
                             na CONFIG; a materia esta' presa.
    - `nil`               -> o rito nem conhece este estado. Materia anterior ao rito, ou rito trocado sob
                             os pes dela (o versionamento de template e' por copia integral, mig 0016).
  Sem este dado a borda so' poderia dizer 'nenhum ato disponivel' — verdadeiro e inutil."
  [tx ente-id template-id chave]
  (comum/linha->kebab
    (jdbc/execute-one! tx
      (sql/format {:select [:chave :nome :terminal :ordem]
                   :from [:legislativo.template_estado]
                   :where [:and [:= :ente_id ente-id] [:= :template_id template-id] [:= :chave chave]]}))))

(defn- ritos-ativos
  "Templates ATIVOS de sujeito 'proposicao' do tenant que governam `tipo`. `tipo` nil NAO significa 'todos':
  significa os GENERICOS — as linhas cujo `tipo` e' NULL, o rito da Casa para as especies que ela nao quis
  particularizar. Os dois recortes sao mutuamente exclusivos por construcao, e e' isso que permite a
  resolucao em duas camadas de `resolver-rito!`.

  Teto 2 — a decisao so' distingue nenhum / exatamente um / mais de um, nao precisa arrastar a tabela de
  config inteira. `ativo` entra no filtro porque o versionamento de template e' por COPIA INTEGRAL
  (mig 0016): sem ele, a primeira Casa que versionasse o proprio rito passaria a ter 2 linhas e recusaria
  TODA materia daquela especie por ambiguidade. A valvula que baixa `ativo` e'
  `db/tramitacao/aposentar-template!` — ela existe no produto, nao so' em fixture."
  [tx ente-id tipo]
  (comum/linhas->kebab
   (jdbc/execute! tx
     (sql/format {:select [:id :estado_inicial] :from [:legislativo.template_tramitacao]
                  :where [:and [:= :ente_id ente-id] [:= :sujeito [:inline "proposicao"]] [:= :ativo true]
                          (if (some? tipo) [:= :tipo tipo] [:is :tipo nil])]
                  :order-by [[:id :asc]] :limit 2}))))

(defn- rito-unico
  "Zero -> nil. Exatamente um -> ele. Mais de um -> RECUSA, porque escolher o rito da Casa por conta (o
  primeiro, o mais novo, o de nome mais parecido) e' exatamente a classe de defeito do T3-A: o codigo
  decidindo a regra que e' do tenant.

  A `ex-data` carrega `:tipo` no namespace `config` de proposito — e' o que `interceptors/erro` le' para
  mapear a recusa (ver la'). Sem essa chave a excecao caia no `:else` do interceptor e virava 500 opaco no
  `POST /legislativo/proposicoes`, indistinguivel de um bug do servidor: o operador da Casa que cadastrou
  dois ritos para a mesma especie nao teria como saber que o conserto e' dele, e nem qual especie olhar.
  Dois tags distintos (e nao um so' com `:especie` opcional) porque as duas causas pedem acoes
  DIFERENTES: uma se conserta aposentando um dos ritos DAQUELA especie; a outra, um dos GENERICOS."
  [ritos ente-id tipo]
  (case (count ritos)
    0 nil
    1 (first ritos)
    (throw (if (some? tipo)
             (ex-info (str "mais de um rito ativo para a especie '" tipo "' neste tenant — "
                           "aposente um deles, ou informe `template-id` explicitamente")
                      {:tipo :config/rito-ambiguo-na-especie :especie tipo :ente-id ente-id})
             (ex-info (str "mais de um rito GENERICO ativo neste tenant (nenhum declara especie) — "
                           "aposente um deles, ou declare a especie de cada um")
                      {:tipo :config/rito-generico-ambiguo :ente-id ente-id})))))

(defn- resolver-rito!
  "Decide SOB QUAL RITO a materia nasce, e qual e' o estado inicial dela. FALHA FECHADA.

  A resolucao automatica e' POR ESPECIE (`tipo` da materia, mig 0077), e nao mais 'um rito por Casa'.
  A regra antiga tratava 3+ ritos como config ambigua; o dominio diz o contrario — 3+ ritos e' o caso
  NORMAL de qualquer regimento (projeto de lei passa por comissao e dois turnos; requerimento vai direto
  a plenario; mocao nao tem parecer). Com a regra antiga, a Casa que cadastrasse o proprio regimento nao
  conseguia protocolar NADA.

  As camadas, em ordem:

  1. `template-id` EXPLICITO vence sempre (inclusive sobre `ativo`: quem nomeia o template esta'
     declarando a intencao — importacao de acervo entra num rito aposentado de proposito). Valida que
     existe no tenant e que e' de sujeito 'proposicao' (anti-misconfig cross-sujeito — o espelho exato de
     db/parecer/criar!, que recusa template de 'proposicao').

  2. senao, o rito ATIVO cuja ESPECIE casa o `tipo` da materia.

  3. senao, o rito GENERICO ativo (`template.tipo` NULL) — o rito da Casa para as especies que ela nao
     quis particularizar. Esta camada NAO e' conveniencia: e' o que todo acervo de hoje tem (nenhum rito
     jamais declarou especie) e sem ela o efeito de introduzir a coluna seria toda Casa existente parar
     de tramitar EM SILENCIO — materia sem rito e' desfecho legitimo, logo nada apitaria. A precedencia
     especifico-sobre-generico e' regra DECLARADA e deterministica, nao desempate: o codigo nunca escolhe
     entre dois ritos de mesma especificidade (ver `rito-unico`).

  4. nenhum dos dois -> `{:template-id nil :estado nil}`. A materia nasce SEM rito e nao tramita — o
     comportamento de hoje, agora explicito, e NAO um erro. O `estado` fica a cargo do DEFAULT DA COLUNA
     (schema), nao do codigo: cravar a string de estado aqui e' precisamente o que o Inv.4 proibe (os
     valores de `proposicoes.estado` sao chaves de `template_estado`, config do tenant).

  Ambiguidade DENTRO de uma camada (dois ritos ativos da mesma especie, ou dois genericos) recusa — ver
  `rito-unico`."
  [tx ente-id tipo template-id]
  (if (some? template-id)
    (let [{:keys [estado-inicial sujeito]} (template-meta tx ente-id template-id)]
      (when (nil? estado-inicial)
        (throw (ex-info "protocolar: template inexistente" {:template-id template-id})))
      (when (not= "proposicao" sujeito)
        (throw (ex-info "protocolar: template nao e' de sujeito 'proposicao' (anti-misconfig cross-sujeito)"
                        {:template-id template-id :sujeito sujeito})))
      {:template-id template-id :estado estado-inicial})
    (if-let [rito (or (rito-unico (ritos-ativos tx ente-id tipo) ente-id tipo)
                      (rito-unico (ritos-ativos tx ente-id nil) ente-id nil))]
      {:template-id (:id rito) :estado (:estado-inicial rito)}
      {:template-id nil :estado nil})))

(defn protocolar!
  "Protocola: resolve o RITO POR ESPECIE (mig 0076 + 0077), gera o sequencial gapless (escopo 'tipo:ano' do ente da SESSAO),
  computa a URN/LexML (eixo H) e insere — atomico na tx. uf/municipio-nome = FATO do ente resolvido
  UPSTREAM (nao JOIN cross-schema, §22.10). Devolve {:id :sequencial :urn-lex :template-id :estado} (o
  numero so existe pos-commit).

  ORDEM IMPORTA (inalterada): o rito e' resolvido ANTES de `sequencial/proximo!`. A numeracao oficial e' GAPLESS (eixo
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
  (let [{rito :template-id estado-inicial :estado} (resolver-rito! tx ente-id tipo template-id)
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

(defn resumos-por-ids
  "Modo TV (docs/22): o resumo MINIMO (tipo/ano/sequencial/ementa) de um LOTE de proposicoes numa query so' —
  a pauta de uma sessao tem ~5-20 itens, N `buscar` seriam N round-trips. Mesmo molde de
  ProposicaoResumoObjetoVotacaoOut. Id que nao existe no tenant simplesmente nao volta (quem chama nao inventa).
  `autor_texto` (docs/23 Fatia 4a): a TV do plenario mostra de quem e' a materia ('o requerimento do vereador
  X') — e' o mesmo texto de autoria que a ficha publica da materia ja' publica."
  [tx ente-id ids]
  (if (empty? ids)
    []
    (comum/linhas->kebab
     (jdbc/execute! tx
       (sql/format {:select [:id :tipo :ano :sequencial :ementa :autor_texto] :from [:legislativo.proposicoes]
                    :where [:and [:= :ente_id ente-id] [:in :id (vec ids)]]})))))

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
  mesmo estado nao-terminal nao se sobrescrevem em silencio). Aqui so o set; o guard de regra/autorizacao
  e' do controller/motor. Lanca em conflito de versao OU row inexistente (0 linhas afetadas).

  NAO HA' MAIS REDE NO BANCO (mig 0078). Ate' ela, o trigger `trg_proposicoes_imut_estado` barrava UPDATE
  a partir de 'publicada'/'arquivada' — duas palavras de camara cravadas em SQL (Inv.4), que tornavam o
  desarquivamento impossivel ate' para a Casa cujo rito o declara. A trava virou dado do rito
  (`template_estado.terminal`), lida por `db/tramitacao/transicionar!`, que e' o UNICO chamador desta fn em
  `src/`. Chamar `mudar-estado!` de outro lugar PULA a declaracao da Casa em silencio: quem precisar mover
  o estado de uma materia usa a engine (gatilho -> rito), nao esta fn."
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

(defn estado+lock+rito
  "Le estado + lock_version + template_id da proposicao SOB FOR UPDATE. nil = a linha nao existe NESTE
  tenant (a coluna `estado` e' NOT NULL desde a mig 0013, entao `estado` nil nunca significa outra coisa).

  Serializa escritas concorrentes na MESMA proposicao (dois clerks no mesmo ato): sem o lock, ambas leem
  o mesmo `lock_version`, a 2a perde o CAS e sai por uma excecao de conflito — saida nao-documentada do
  contrato. Com ele, a 2a espera, le o estado fresco e reavalia o guard deterministicamente.

  `template_id` entra na MESMA leitura de proposito, e agora por DOIS motivos. Na engine
  (`tramitacao/transicionar!`), e' o rito DA MATERIA, confrontado com o `template-id` que o caller passou.
  No guard de `editar!`, e' o rito a quem se pergunta o que e' terminal. Nos dois casos, le-lo numa
  segunda query abriria a janela exata que o `FOR UPDATE` existe p/ fechar — o rito podia ser reapontado
  entre uma leitura e outra, e a decisao passaria a misturar dois mundos."
  [tx ente-id id]
  (-> (jdbc/execute-one! tx
        (sql/format {:select [:estado :lock_version :template_id] :from [:legislativo.proposicoes]
                     :where [:and [:= :ente_id ente-id] [:= :id id]] :for :update}))
      comum/linha->kebab))

(defn editar!
  "Reescreve metadados (PATCH parcial: so' os campos presentes mudam) de uma proposicao NAO-TERMINAL (CAS
  por lock-version; SELECT...FOR UPDATE evita corrida entre o guard de estado e o UPDATE). O guard aqui e'
  a UNICA camada desde a mig 0078 — o trigger que o duplicava saiu. Mesmo padrao de
  db/documento.clj/editar-rascunho!, mas o guard e' 'nao terminal' (a proposicao nao tem fase rascunho —
  mutacao livre ate estado terminal, §22.4.3 disc.4), nao 'so rascunho'.

  QUEM DIZ O QUE E' TERMINAL E' O RITO. Ate' esta fatia quem dizia era `logic/estados-proposicao-terminais`
  = #{\"publicada\" \"arquivada\"}: duas palavras de camara cravadas em codigo, Inv.4 violado, e o set nao
  sobreviveu a esta mudanca (foi removido). Enquanto `proposicoes.estado` nao se movia por rito nenhum o
  literal era inofensivo; a borda da tramitacao acende a maquina do eixo C e ele passa a errar NOS DOIS
  SENTIDOS AO MESMO TEMPO:
    · FAIL-OPEN — Fortaleza cadastra o rito real (projeto_lei -> comissoes -> pauta -> turnos -> autografo
      -> sancao). Nenhum estado dela se chama 'publicada' nem 'arquivada', entao um projeto de lei JA'
      SANCIONADO seguia com a ementa e a autoria editaveis para sempre.
    · FAIL-CLOSED ERRADO — a Casa cujo rito permite DESARQUIVAMENTO declara 'arquivada' NAO-terminal (e'
      exatamente o ato que a mig 0078 veio destravar), e tinha a edicao barrada num estado que ela mesma
      chamou de vivo.
  A pergunta agora e' a MESMA que `tramitacao/transicionar!` faz — `template_estado.terminal` do estado
  ATUAL, pela MESMA fn (`estado-no-template`), lida sob o MESMO `FOR UPDATE`. Nao ha' `case`/`when` sobre
  nome de estado aqui, e nao pode haver: a unica pergunta e' o que o DADO diz.

  [DECISAO — MATERIA SEM RITO (`template_id` nil) EDITA] Desde a mig 0076/0077 nascer sem rito e'
  comportamento NORMAL, nao erro: a Casa em onboarding ainda nao cadastrou o regimento, e o acervo
  importado (`importacao_legado`) nunca teve um. Sem rito nao ha' quem declare terminal nenhum, e a
  escolha e' entre dois erros:
    · abrir — a materia sem rito segue editavel. O que se perde e' a trava sobre METADADO (ementa, autor,
      destinatario) de uma materia que ninguem declarou encerrada; a identidade legal (tipo/ano/
      sequencial/urn_lex) continua travada pelo `trg_proposicoes_imut_identidade`, o texto tem ciclo
      proprio em `texto_versao`, e toda edicao e' auditada (`updated_by`, `lock_version`, evento
      `proposicao.editada`, versao de origem 'edicao').
    · fechar — a materia sem rito nunca mais edita. E' o pior dos dois PARA EXPLICAR A UM CLIENTE: a Casa
      que ainda esta' cadastrando o regimento protocola uma materia com erro de digitacao na ementa e NAO
      TEM como corrigi-la, nem tem config que destrave — nao existe rito a consertar. O produto estaria
      afirmando que o processo acabou sobre uma materia que nunca comecou.
  Abrimos. Pela mesma leitura, estado que o rito NAO declara (`estado-no-template` -> nil) tambem edita:
  `(:terminal nil)` e' nil, e e' identico ao que `transicionar!` faz com `:estado-fora-do-rito` —
  divergir aqui poria a engine e o guard de edicao discordando sobre a mesma materia.

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
  (let [{:keys [estado template-id]} (estado+lock+rito tx ente-id id)]
    (when (nil? estado)
      (throw (ex-info "editar!: proposicao inexistente" {:id id :ente-id ente-id})))
    (when (and (some? template-id)
               (:terminal (estado-no-template tx ente-id template-id estado)))
      (throw (ex-info "editar!: o rito desta materia declara TERMINAL o estado atual — nao edita"
                      {:tipo :validacao/invalido :id id :estado estado :template-id template-id}))))
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
