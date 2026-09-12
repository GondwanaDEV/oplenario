(ns oplenario.legislativo.db.votacao
  "Persistencia da votacao (eixo G) — funcoes sobre a `tx` do tenant (RLS isola). HoneySQL schema-qualified;
  ente_id em toda query. `abrir!` cria a votacao 'aberta'; `registrar-voto!`/`registrar-voto-secreto!` sao
  APPEND-ONLY (o trigger congela; correcao = nova votacao); `encerrar!` apura (votos OU votos_secretos
  conforme a modalidade), computa o resultado pela aritmetica EXATA do quorum (logic) e grava o snapshot
  (CAS por lock_version). `anular!` leva a 'anulada' (terminal) — usado na correcao (nova votacao aponta a
  corrigida via votacao_corrige_id)."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]
            [oplenario.legislativo.db.texto-versao :as texto]
            [oplenario.legislativo.logic :as logic]))

(set! *warn-on-reflection* true)

(def ^:private colunas
  [:id :ente_id :objeto_tipo :objeto_id :modalidade :quorum_tipo :estado :resultado
   :total_sim :total_nao :total_abstencao :base_membros :votacao_corrige_id
   :sessao_id :pauta_item_id :texto_versao_id :lock_version])

(def ^:private objetos-que-carregam-a-materia
  "Os `objeto_tipo` de votacao cujo `objeto_id` E' a PROPRIA proposicao — nao um objeto satelite dela.

  `proposicao` e' obvio. `redacao_final` entra porque a redacao final NAO E' entidade: e' uma FASE da
  materia (no schema ela existe como `origem_versao='redacao_final'` de `proposicao_texto_versao`,
  migration 0015). Nao ha' tabela `redacao_final` p/ o `objeto_id` apontar — entao ele so' pode ser a
  proposicao, e essa e' a semantica que este ns FIXA: ate' aqui nenhum produtor existia (nem FE, nem
  semente, nem e2e abriam votacao deste tipo) e o campo estava sem significado definido.

  As irmas polimorficas — `emenda`, `parecer`, `requerimento` — SAO entidades proprias e ficam de fora: o
  `objeto_id` delas aponta OUTRA tabela. Note que isso e' CONVENCAO defendida por este filtro, nao
  invariante do banco: `votacoes.objeto_id` nao tem FK (mig 0021, disc.2), entao a colisao de uuid entre
  espacos e' possivel por construcao — e' exatamente por isso que o filtro por tipo tem de existir.

  UMA fonte p/ os dois lugares que dependem disto: `abrir!` (que congela o texto deliberado) e
  `aprovacao-vigente` (que o le de volta). Divergirem e' o pior dos mundos — o predicado passa e o
  autografo recusa por falta de texto."
  #{"proposicao" "redacao_final"})

(def ^:private objetos-que-carregam-a-materia-sql
  "O mesmo conjunto, ordenado e em vetor, p/ o `IN` do HoneySQL — pre-computado (a query roda por request)."
  (vec (sort objetos-que-carregam-a-materia)))

(defn abrir!
  "Abre uma votacao (estado 'aberta') sobre o objeto polimorfico (objeto-tipo,objeto-id). `sessao-id` +
  `pauta-item-id` (ambos forward-ref a sessoes, §22.10) sao CONTEXTO TEMPORAL — a votacao e' sobre a
  MATERIA (objeto), nao sobre o item (§22.6 eixo B). RETURNING `lock_version` (ledger de prontidao Fase
  8 achado #2): esta e' a UNICA leitura que existe da votacao recem-aberta — nao ha' rota GET de
  detalhe — entao o recibo de abertura tem de carregar o token de CAS que `POST .../encerramento`
  exige, ou o encerramento fica impossivel de montar so' pela API. Devolve {:id :lock-version}."
  [tx {:keys [id ente-id objeto-tipo objeto-id modalidade quorum-tipo votacao-corrige-id
              sessao-id pauta-item-id created-by]}]
  (let [;; T3-A2 (mig 0075) — CONGELA a versao de texto posta em deliberacao. Resolvida AQUI, server-side,
        ;; dentro da mesma tx do INSERT: nunca vem do corpo do request (mesma disciplina de
        ;; `destinatario-texto` do autografo) e nao ha' janela entre resolver e gravar. O instante certo e' a
        ;; ABERTURA, nao o encerramento: o texto sobre o qual o plenario delibera e' o que esta' na mesa
        ;; quando a votacao abre. So' faz sentido quando o `objeto_id` E' a materia
        ;; (`objetos-que-carregam-a-materia`) — o objeto e' POLIMORFICO, e emenda/parecer/requerimento nao
        ;; tem versao de texto de proposicao; nesses casos fica nil, e quem consome falha fechada (ver
        ;; aprovacao-vigente). `redacao_final` entrou aqui JUNTO com o predicado, nunca depois: aceita-la so'
        ;; em `aprovada-em-votacao?` faria o read-model dizer "aprovada" enquanto `gerar-autografo` seguiria
        ;; recusando por :conflito/aprovacao-sem-texto — um destrave que nao destrava nada.
        texto-versao-id (when (contains? objetos-que-carregam-a-materia objeto-tipo)
                          (:id (texto/vigente tx ente-id objeto-id)))
        r (comum/linha->kebab
           (jdbc/execute-one! tx
             (sql/format {:insert-into :legislativo.votacoes
                          :values [{:id id :ente_id ente-id :objeto_tipo objeto-tipo :objeto_id objeto-id
                                    :modalidade modalidade :quorum_tipo quorum-tipo :estado "aberta"
                                    :votacao_corrige_id votacao-corrige-id :sessao_id sessao-id
                                    :pauta_item_id pauta-item-id :texto_versao_id texto-versao-id
                                    :created_by created-by :efetivado_em [:now]}]
                          :returning [:lock_version]})))]
    {:id id :lock-version (:lock-version r)}))

(defn registrar-voto!
  "Registra um voto NOMINAL (atribuido). Append-only; a UNIQUE (ente_id,votacao_id,vereador_id) barra voto
  duplo do mesmo vereador. RETURNING `registrado_em` (Onda E fatia 2 carry, mesmo racional de
  tramitacao/registrar-transicao!): o carimbo REAL do voto, devolvido p/ o Repo incluir no evento de dominio
  (voto.registrado :ocorrido-em) — a jusante, o perfil publico do vereador em `transparencia` usa isto em vez
  do momento em que o consumer PROJETA. Devolve {:id :ocorrido-em}."
  [tx {:keys [id ente-id votacao-id vereador-id voto created-by]}]
  (let [r (comum/linha->kebab
           (jdbc/execute-one! tx
             (sql/format {:insert-into :legislativo.votos
                          :values [{:id id :ente_id ente-id :votacao_id votacao-id :vereador_id vereador-id
                                    :voto voto :created_by created-by :efetivado_em [:now]}]
                          :returning [:registrado_em]})))]
    {:id id :ocorrido-em (:registrado-em r)}))

(defn registrar-voto-secreto!
  "Registra um voto SECRETO (anonimo — a tabela nao tem vereador_id/created_by). Append-only. Devolve {:id}."
  [tx {:keys [id ente-id votacao-id voto]}]
  (jdbc/execute-one! tx
    (sql/format {:insert-into :legislativo.votos_secretos
                 :values [{:id id :ente_id ente-id :votacao_id votacao-id :voto voto :efetivado_em [:now]}]}))
  {:id id})

(defn buscar [tx ente-id id]
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:select colunas :from [:legislativo.votacoes]
                  :where [:and [:= :ente_id ente-id] [:= :id id]]}))))

(defn aberta-da-sessao
  "A votacao 'aberta' MAIS RECENTE desta sessao, ou nil (fatia 'demo-tres-consertos' #2b — recuperacao de
  estado). Nao ha' UNIQUE que impeca duas 'aberta' na mesma sessao (migration 0021: so' o CHECK de
  `estado`, sem indice parcial por sessao) — `ORDER BY criado_em DESC LIMIT 1` escolhe a MAIS NOVA, mesma
  semantica que o reducer do FE ja assume ('uma votacao por vez no plenario: a abertura SUBSTITUI o
  placar anterior', plenario-reducer.ts). `idx_votacoes_estado (ente_id, estado)` cobre o filtro; o
  volume por sessao e' pequeno o bastante pra nao precisar de indice dedicado por sessao_id."
  [tx ente-id sessao-id]
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:select colunas :from [:legislativo.votacoes]
                  :where [:and [:= :ente_id ente-id] [:= :sessao_id sessao-id] [:= :estado [:inline "aberta"]]]
                  :order-by [[:criado_em :desc]]
                  :limit 1}))))

(defn contar-votos-secretos
  "Quantos votos SECRETOS ja' foram registrados nesta votacao — o MESMO tick anonimo que `voto.registrado`
  secreto ja' expoe ao vivo (§22.6), nunca uma apuracao por valor (sim/nao/abstencao): isso vazaria MAIS
  do que o proprio stream vivo vaza antes do encerramento. Usada tambem pra 'simbolica' (sempre 0 — essa
  modalidade nao registra voto individual, `controllers/registrar-voto`)."
  [tx ente-id votacao-id]
  (:contagem
   (comum/linha->kebab
    (jdbc/execute-one! tx
      (sql/format {:select [[[:count :*] :contagem]] :from [:legislativo.votos_secretos]
                   :where [:and [:= :ente_id ente-id] [:= :votacao_id votacao-id]]})))))

(defn aprovacao-vigente
  "T3-A2 — a votacao que APROVOU `proposicao-id`, ou nil. Devolve {:votacao-id :texto-versao-id}; o
  `:texto-versao-id` e' a versao que estava na mesa quando a votacao ABRIU (congelada por `abrir!`, mig
  0075) e pode ser nil (votacao anterior a' migration, ou materia que foi a plenario sem texto vigente).

  E' desta fn que sai `aprovada-em-votacao?` — mesma consulta, mesmas exclusoes, uma so' fonte de verdade.
  A separacao existe porque as duas perguntas do sistema sao distintas e nao devem colapsar:
  'a Casa aprovou?' (o read-model, que gateia botao) e 'entao QUAL texto ela aprovou?' (o autografo, que
  precisa do conteudo). Quem precisa do conteudo FALHA FECHADA quando `:texto-versao-id` e' nil — nao se
  emite ato juridico sem saber o que foi deliberado —, enquanto o read-model segue dizendo a verdade: a
  materia foi mesmo aprovada.

  T3-A4 — a aprovacao pode vir de DOIS objeto_tipo (`objetos-que-carregam-a-materia`), porque o gatilho do
  autografo varia por casa: em Mossoro/RN e' a aprovacao do PROJETO, em Fortaleza (Res. 1.670/2020, Art.
  180 §1º — o beachhead) e' a aprovacao da REDACAO FINAL em Plenario. Aceitar so' um dos dois acerta numa
  casa e erra na outra; a uniao acerta nas duas e continua muito mais restritiva que o estado anterior a'
  guarda (nenhuma pre-condicao). Ver `docs/17-rito-do-autografo-fortaleza-e-ceara.md` §5.1.

  DESEMPATE em TRES niveis, todos load-bearing — `:limit 1` sem ordem deixaria o TEXTO do autografo ao
  acaso do plano do Postgres (na primeira corrida do teste ele devolveu o texto do PROJETO):

  1. `CASE objeto_tipo` — no rito de Fortaleza as duas aprovacoes COEXISTEM (vota-se o projeto e depois a
     redacao final). A redacao final vence sempre porque so' existe DEPOIS do projeto aprovado. Isto e'
     ordem do RITO, e nenhum carimbo de tempo a substitui.
  2. `atualizado_em DESC` — desempata DUAS aprovacoes do MESMO tipo, e esse caso e' alcancavel: nao ha'
     rota de anulacao nem de votacao corretiva (`AbrirVotacao` nao expoe `votacao-corrige-id`), entao
     refazer uma votacao errada hoje so' e' possivel abrindo OUTRA — e as duas ficam encerrada+aprovada+
     nao-corrigidas. Sem este nivel a escolha cairia em `id DESC` sobre uuid v4, que nao tem relacao com
     o tempo: moeda decidindo qual texto vai ao Prefeito (achado C-1 da revisao adversarial). Em producao
     cada encerramento e' sua propria tx, entao `now()` DISCRIMINA; e o trigger `trg_votacoes_imut_estado`
     (mig 0012 (b)) bloqueia UPDATE em row ja' terminal, logo o carimbo de uma 'encerrada' nao se move
     mais — e' o instante do encerramento, congelado.
  3. `id DESC` — so' o caso patologico que sobra (mesmo tipo, mesmo carimbo: duas encerradas na MESMA tx,
     que hoje so' acontece em teste). Arbitrario, mas ESTAVEL entre leituras."
  [tx ente-id proposicao-id]
  (some-> (jdbc/execute-one! tx
           (sql/format {:select [[:v.id :votacao_id] :v.texto_versao_id] :from [[:legislativo.votacoes :v]]
                        :where [:and [:= :v.ente_id ente-id]
                                     [:in :v.objeto_tipo objetos-que-carregam-a-materia-sql]
                                     [:= :v.objeto_id proposicao-id]
                                     [:= :v.estado "encerrada"]
                                     [:= :v.resultado "aprovada"]
                                     [:not [:exists {:select [[[:inline 1]]]
                                                     :from [[:legislativo.votacoes :c]]
                                                     :where [:and [:= :c.ente_id ente-id]
                                                                  [:= :c.votacao_corrige_id :v.id]]}]]]
                        :order-by [[[:case [:= :v.objeto_tipo [:inline "redacao_final"]] [:inline 0]
                                     :else [:inline 1]] :asc]
                                   [:v.atualizado_em :desc]
                                   [:v.id :desc]]
                        :limit 1}))
          comum/linha->kebab))

(defn aprovada-em-votacao?
  "T3-A (guarda-autografo-votacao) — a proposicao `proposicao-id` foi APROVADA pela Casa? Devolve booleano.

  A pergunta que o autografo (artefato legal, numeracao gapless) tem de fazer NAO e' sobre o rotulo
  `proposicoes.estado`: aquilo e' texto livre (sem CHECK, default 'protocolada'), e' chave de estado de
  TEMPLATE — config do tenant, nao vocabulario de sistema (Inv.4) — e nenhuma rota HTTP o move (o unico
  chamador de `db/tramitacao.clj/transicionar!` e' a semente da demo). A pergunta e' sobre o ATO: existe
  votacao ENCERRADA sobre esta proposicao cujo resultado foi 'aprovada'.

  Tres exclusoes, todas deliberadas:
  - `estado = 'encerrada'` deixa de fora a votacao 'aberta' (ainda apurando) e a 'anulada' (terminal por
    correcao — migration 0021 L8: correcao de voto nunca e' UPDATE, anula-se e abre-se outra).
  - `NOT EXISTS (... votacao_corrige_id = v.id)` deixa de fora a votacao que uma OUTRA veio corrigir. A
    anulacao e a abertura da corretiva sao dois atos distintos; entre um e outro a corrigida ainda esta'
    'encerrada' e sozinha ela mentiria.
  - `objeto_tipo IN ('proposicao','redacao_final')` amarra ao objeto certo: `objeto_id` e' polimorfico e
    sem isso uma EMENDA (ou parecer/requerimento) aprovada de id colidente responderia pela materia-mae.
    Os dois aceitos sao os que carregam a PROPRIA materia — ver `objetos-que-carregam-a-materia`.

  LIMITES CONHECIDOS, todos declarados (a lista cresceu com a revisao adversarial ecc — o que este
  predicado NAO responde e' tao importante quanto o que responde):
  - 'houve UMA aprovacao', nao 'o rito se completou': dois turnos e redacao final passam com um turno so'.
  - votacao 'simbolica' (aclamacao) tem o `resultado` vindo do CORPO do request (ver `encerrar!` abaixo) —
    entao uma aprovacao com ZERO votos registrados satisfaz este predicado. Idem 'nominal' com um voto so'
    em maioria_simples, e com `base-membros` do corpo (carry sec MEDIUM-1).
  - a votacao pode ter sido aberta e encerrada numa sessao 'agendada' que nunca se realizou:
    `estados-sessao-fechada` e' so' #{encerrada nao_realizada arquivada}. Este limite DOBROU de superficie
    em T3-A4 — agora vale p/ os dois `objeto_tipo`, nao so' p/ 'proposicao'.
  - (RESOLVIDO em T3-A4) a exclusao de `objeto_tipo='redacao_final'` era ERRADA para o beachhead e a
    docstring anterior a declarava 'conservadora de proposito' — a pesquisa de rito (docs/17) desmentiu.
    Hoje os dois contam; o predicado passa a ser a UNIAO dos modelos de Fortaleza e Mossoro. O que ele
    ainda NAO faz e' escolher POR CASA qual dos dois e' o gatilho legitimo: numa camara cujo regimento
    exige a redacao final, a aprovacao so' do projeto ainda destrava. Isso e' regra de tenant (Inv.4:
    compliance e' DADO), pertence a' DSL do motor declarativo (disciplina 5), e nao a um `if` aqui — a
    forma atual aceita esse refino sem refactor: o predicado ganha criterio, os chamadores nao mudam.
  - (RESOLVIDO em T3-A2, mig 0075) qual TEXTO foi aprovado deixou de ser incognita: `abrir!` congela a
    versao posta em deliberacao, e quem precisa do conteudo usa `aprovacao-vigente` logo acima.
  - (NOVO em T3-A4, achado I-1 da revisao adversarial) uma REDACAO FINAL aprovada SOZINHA destrava, mesmo
    com o projeto REJEITADO. Em nenhum rito pesquisado (docs/17 §4) a Redacao Final existe sem aprovacao
    previa da materia — ela a PRESSUPOE. A borda nao valida precedencia (nem existencia da proposicao, nem
    estado, nem vinculo com o item de pauta), entao `POST .../votacoes` com objeto-tipo='redacao_final'
    sobre uma materia rejeitada + encerramento 'simbolica' (resultado vem do CORPO) satisfaz este
    predicado. Nao e' escalada de privilegio — o MESMO ator ja' podia fabricar por 'proposicao' (limite
    acima) —, mas e' porta nova com aparencia legitima. Exigir a CONJUNCAO (redacao final conta SE houver
    aprovacao vigente de 'proposicao') e' decisao de dominio, nao de engenharia: em Fortaleza-2008 a
    redacao final era votada pela CCJ, nao pelo Plenario, e a conjuncao a barraria.
  - (M-4) o `NOT EXISTS` da correcao e' tipo-AGNOSTICO: nada amarra o `objeto_tipo` da corretiva ao da
    corrigida. Inofensivo hoje (os dois tipos aceitos carregam a mesma materia) e inalcancavel por HTTP
    (nao ha' rota que passe `votacao-corrige-id`), mas e' acidente, nao decisao — declarado p/ nao virar
    'conservadorismo de proposito' como a exclusao que este commit desfez.

  E' [GAP] regimental (mesmo bolso de admissibilidade-de-emenda-de-plenario)."
  [tx ente-id proposicao-id]
  (some? (aprovacao-vigente tx ente-id proposicao-id)))

(defn buscar-com-lock
  "Como `buscar`, mas sob `SELECT ... FOR UPDATE` — serializa contra `encerrar!` (que tambem toma o lock via
  `votacao+lock`). Onda C3 (`registrar-meu-voto!`): fecha a janela de corrida entre AUTORIZAR um voto
  self-service e a Mesa encerrar a MESMA votacao no meio do caminho — so' usar dentro de uma tx que em
  seguida ESCREVE com base neste snapshot (nunca uma leitura solta)."
  [tx ente-id id]
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:select colunas :from [:legislativo.votacoes]
                  :where [:and [:= :ente_id ente-id] [:= :id id]] :for :update}))))

(defn votos-da-votacao
  "Os votos NOMINAIS (atribuidos) da votacao. NOMINAL apenas — voto secreto NAO e' atribuivel por design
  (sigilo no schema; votos_secretos nao tem vereador_id). P/ votacao secreta devolve [] (sem votos nominais)."
  [tx ente-id votacao-id]
  (comum/linhas->kebab
   (jdbc/execute! tx
     (sql/format {:select [:id :votacao_id :vereador_id :voto :registrado_em]
                  :from [:legislativo.votos]
                  :where [:and [:= :ente_id ente-id] [:= :votacao_id votacao-id]]
                  :order-by [[:registrado_em :asc]]}))))

(defn- apurar
  "Apura {:sim n :nao n :abstencao n} contando os votos da `tabela` (votos | votos_secretos) por valor.
  Usa linhas->kebab (padrao do modulo): a chave do `voto` fica `:voto` independente da tabela de origem —
  robusto vs. o namespace por-tabela do next.jdbc (review F3.7 clojure-MAJOR)."
  [tx ente-id votacao-id tabela]
  (let [por-voto (->> (jdbc/execute! tx
                        (sql/format {:select [:voto [[:count :*] :n]] :from [tabela]
                                     :where [:and [:= :ente_id ente-id] [:= :votacao_id votacao-id]]
                                     :group-by [:voto]}))
                      comum/linhas->kebab
                      (into {} (map (juxt :voto :n))))]
    {:sim (get por-voto "sim" 0) :nao (get por-voto "nao" 0) :abstencao (get por-voto "abstencao" 0)}))

(defn- votacao+lock
  [tx ente-id id]
  (-> (jdbc/execute-one! tx
        (sql/format {:select [:estado :lock_version :modalidade :quorum_tipo] :from [:legislativo.votacoes]
                     :where [:and [:= :ente_id ente-id] [:= :id id]] :for :update}))
      comum/linha->kebab))

(defn encerrar!
  "Encerra a votacao (estado 'encerrada'): apura os votos (nominal->votos, secreta->votos_secretos), computa
  o resultado pela aritmetica EXATA do quorum (logic/resultado-votacao com `base-membros` = composicao da
  Casa, resolvida UPSTREAM e passada explicita — nao JOIN), e grava o snapshot (totais+base+resultado) com
  CAS por lock_version. Modalidade 'simbolica' (aclamacao) nao apura individual: passe `:resultado` explicito.
  Lanca em conflito de lock OU votacao inexistente. Fail-closed: parecer terminal nao reabre (trigger)."
  [tx {:keys [id ente-id base-membros resultado updated-by lock-version]}]
  (let [{:keys [estado modalidade quorum-tipo]} (votacao+lock tx ente-id id)]
    (when (nil? estado)
      (throw (ex-info "encerrar!: votacao inexistente" {:id id :ente-id ente-id})))
    ;; fail-closed (review F3.7 clojure-MENOR): votacao terminal nao reabre — erro inspecionavel em vez de
    ;; um "conflito de lock" enganoso (o trigger tambem barra, mas a mensagem aqui e' a causa real).
    (when (contains? logic/estados-votacao-terminais estado)
      (throw (ex-info "encerrar!: votacao ja em estado terminal" {:id id :estado estado})))
    (let [tally (case modalidade
                  "nominal" (apurar tx ente-id id :legislativo.votos)
                  "secreta" (apurar tx ente-id id :legislativo.votos_secretos)
                  "simbolica" nil)
          res (if (= "simbolica" modalidade)
                (or resultado (throw (ex-info "encerrar!: votacao simbolica exige :resultado explicito" {:id id})))
                (logic/resultado-votacao quorum-tipo tally base-membros))
          r (jdbc/execute-one! tx
              (sql/format {:update :legislativo.votacoes
                           :set {:estado "encerrada" :resultado res
                                 :total_sim (:sim tally) :total_nao (:nao tally) :total_abstencao (:abstencao tally)
                                 :base_membros base-membros :updated_by updated-by :atualizado_em [:now]
                                 :lock_version [:+ :lock_version 1]}
                           :where [:and [:= :ente_id ente-id] [:= :id id] [:= :lock_version lock-version]]}))]
      (when (zero? (:next.jdbc/update-count r 0))
        (throw (ex-info "encerrar!: conflito de lock_version ou votacao inexistente"
                        {:id id :lock-version lock-version})))
      (merge {:id id :estado "encerrada" :resultado res :base-membros base-membros} tally))))

(defn anular!
  "Anula a votacao (estado 'anulada', terminal) com CAS. Usada na correcao: anula a votacao errada e abre-se
  uma NOVA apontando a corrigida (votacao_corrige_id). O trigger trava anular uma JA terminal."
  [tx {:keys [id ente-id updated-by lock-version]}]
  (let [r (jdbc/execute-one! tx
            (sql/format {:update :legislativo.votacoes
                         :set {:estado "anulada" :updated_by updated-by :atualizado_em [:now]
                               :lock_version [:+ :lock_version 1]}
                         :where [:and [:= :ente_id ente-id] [:= :id id] [:= :lock_version lock-version]]}))]
    (when (zero? (:next.jdbc/update-count r 0))
      (throw (ex-info "anular!: conflito de lock_version ou votacao inexistente"
                      {:id id :lock-version lock-version})))
    {:id id :estado "anulada"}))
