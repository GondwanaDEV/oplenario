(ns oplenario.transparencia.db.materia
  "Persistencia de 'transparencia.materia' (F6c Slice 1, feature 16.5) — funcoes sobre a `tx` corrente
  (FORCE RLS isola, mig 0044). HoneySQL schema-qualified; ente_id em TODA query. Read-model PROJETADO —
  `inserir!` materializa o snapshot no protocolo (`proposicao.protocolada`); `atualizar-estado!` aplica a
  mudanca de tramitacao (`proposicao.transicionou`). Chamado pelo CONSUMER (§22.10 diplomat/consumers), dentro
  da tx do relay — NAO ha Repo-Component na escrita (a projecao roda inteira na tx do bus, atomica com o
  dedup, §22.9 E2); o Repo-Component (components/repositorio) so' serve a LEITURA publica."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]))

(set! *warn-on-reflection* true)

(def ^:private cols
  [:ente_id :proposicao_id :tipo :ano :sequencial :urn_lex :ementa :autor_tipo :autor_texto :autor_id :estado
   :projetado_em :atualizado_em])

(def ^:private teto-listagem
  "Teto server-side (anti unbounded-read, mesmo racional de teto-listagem/comentario) — sem paginacao nesta
  fatia (decisao registrada no plano; paginacao e' refino de UX, nao de correcao)."
  200)

(defn inserir!
  "Projeta o snapshot PUBLICO do protocolo (`proposicao.protocolada`). `ON CONFLICT (ente_id,proposicao_id)
  DO NOTHING` (review db MEDIUM): a dedup do bus (evento_consumido, §22.9 E2) ja' garante no-maximo-uma-vez
  por evento COMMITADO sob operacao normal, mas o `ON CONFLICT` e' cinto-de-seguranca BARATO contra uma
  futura ferramenta de redrive/backfill (§22.10 R-DR — reprojetar do event log) que reemita o MESMO evento
  de dominio com uma idempotency-key NOVA (kernel.eventos/evento gera uma por chamada, nao derivada da
  chave de negocio) — sem isto, o redrive lancaria PK-violation e envenenaria o RELAY COMPARTILHADO (ver
  atualizar-estado!)."
  [tx {:keys [ente-id proposicao-id tipo ano sequencial urn-lex ementa autor-tipo autor-texto autor-id estado]}]
  {:pre [(some? ente-id) (some? proposicao-id) (some? tipo) (some? ano) (some? sequencial)
         (some? urn-lex) (some? ementa) (some? estado)]}
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:insert-into :transparencia.materia
                  :values [{:ente_id ente-id :proposicao_id proposicao-id :tipo tipo :ano ano
                            :sequencial sequencial :urn_lex urn-lex :ementa ementa
                            :autor_tipo autor-tipo :autor_texto autor-texto :autor_id autor-id :estado estado}]
                  :on-conflict [:ente_id :proposicao_id]
                  :do-nothing []
                  :returning [:*]}))))

(defn atualizar-estado!
  "Projeta a transicao (`proposicao.transicionou`): so' o `estado` muda (o snapshot de conteudo e' imutavel
  pos-protocolo). TOLERANTE (review architect HIGH-1, corrige decisao original) se a materia nao existe
  (UPDATE de 0 linhas) — devolve nil em vez de lancar. Motivo: o handler roda DENTRO da tx do RELAY, que e'
  UM SO, compartilhado por TODOS os modulos consumidores (§22.9); um `throw` aqui faz o relay reprocessar o
  MESMO evento a cada tick para sempre (poison), bloqueando HEAD-OF-LINE todo evento de id maior — de
  qualquer modulo, nao so' transparencia. A garantia de que `proposicao.protocolada` PRECEDE
  `proposicao.transicionou` do mesmo agregado vem da CAUSALIDADE do fluxo de negocio (so se transiciona uma
  proposicao ja protocolada), NAO de uma garantia FIFO do outbox entre agregados diferentes (o outbox e'
  transporte, nao log replayavel entre consumers — um consumer registrado DEPOIS do deploy de um tipo de
  evento novo, ou sobre um banco com dado pre-existente, pode legitimamente ver a excecao). O caller
  (components/repositorio) loga a anomalia; a materia so' fica temporariamente desatualizada (read-model
  derivado, sem verdade propria) em vez de travar o barramento inteiro."
  [tx {:keys [ente-id proposicao-id estado]}]
  {:pre [(some? ente-id) (some? proposicao-id) (some? estado)]}
  (let [r (jdbc/execute-one! tx
            (sql/format {:update :transparencia.materia
                         :set {:estado estado :atualizado_em [:now]}
                         :where [:and [:= :ente_id ente-id] [:= :proposicao_id proposicao-id]]}))]
    (when-not (zero? (:next.jdbc/update-count r 0))
      {:proposicao-id proposicao-id :estado estado})))

(defn atualizar-metadados!
  "Projeta a edicao (`proposicao.editada`, Task 1-N1): ementa/autor_tipo/autor_texto/autor_id + atualizado_em
  — o snapshot PUBLICO pos-PATCH, sempre a linha inteira (nunca o PATCH parcial que o cliente mandou no
  legislativo, ver docstring de db/proposicao/editar!). SEM `some?`-gate de proposito: `autor_id` PRECISA
  poder virar NULL (autoria deixou de ser parlamentar, Peca A) — um gate aqui reintroduziria exatamente o
  bug que este evento existe pra corrigir. TOLERANTE (mesmo padrao de atualizar-estado!) se a materia nao
  existe (UPDATE de 0 linhas) — devolve nil em vez de lancar; o relay e' COMPARTILHADO por todos os modulos,
  um `throw` aqui travaria HEAD-OF-LINE todo evento de id maior."
  [tx {:keys [ente-id proposicao-id ementa autor-tipo autor-texto autor-id]}]
  {:pre [(some? ente-id) (some? proposicao-id) (some? ementa)]}
  (let [r (jdbc/execute-one! tx
            (sql/format {:update :transparencia.materia
                         :set {:ementa ementa :autor_tipo autor-tipo :autor_texto autor-texto
                               :autor_id autor-id :atualizado_em [:now]}
                         :where [:and [:= :ente_id ente-id] [:= :proposicao_id proposicao-id]]}))]
    (when-not (zero? (:next.jdbc/update-count r 0))
      {:proposicao-id proposicao-id :ementa ementa})))

(defn buscar
  "Ficha PUBLICA de uma materia (RLS via ente-id). Devolve o mapa kebab-case ou nil."
  [tx ente-id proposicao-id]
  {:pre [(some? ente-id) (some? proposicao-id)]}
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:select cols :from [:transparencia.materia]
                  :where [:and [:= :ente_id ente-id] [:= :proposicao_id proposicao-id]]}))))

(defn listar-por-autor
  "Materias de AUTORIA de um vereador (Onda E fatia 2, perfil publico), por NUMERACAO DECRESCENTE.

  ORDEM (revisao Task 3, F1/F3c) — dizer 'mais recentes primeiro' seria falso: ordena-se por
  (ano DESC, sequencial DESC), e `sequencial` e' um contador POR ESPECIE (escopo 'tipo:ano',
  legislativo/db/proposicao), entao a ordem NAO e' cronologica ENTRE especies — um requerimento
  nº 240/2026 de fevereiro vem antes de um projeto de lei nº 3/2026 de novembro. Ordem cronologica REAL
  nao e' possivel hoje: `transparencia.materia` so' tem `projetado_em` (tempo de PROJECAO, nao de
  protocolo — a mesma armadilha ja' registrada como carry em `transicionou_em`), e obtê-la exigiria
  migration + `:ocorrido-em` no payload de `proposicao.protocolada`. CARRY, nao esta fatia.

  DESEMPATE (achado F1): a terceira chave `proposicao_id DESC` NAO e' decorativa. `sequencial` e' gapless
  por escopo 'tipo:ano', logo NAO e' unico por (ente, ano): 'requerimento 12/2026' e 'projeto_lei 12/2026'
  empatam INTEGRALMENTE nas duas primeiras chaves, e sem uma terceira a ordem passa a depender do plano de
  execucao (Index Scan vs Seq Scan+Sort) — a lista publica troca de ordem entre dois carregamentos sem nada
  ter mudado. Mesmo precedente de `db/parlamentar/votos-do-vereador` (achado M-6 da Task 2). Custo medido:
  o planner mantem o Index Scan em idx_materia_autor com Incremental Sort (Presorted Key: ano, sequencial),
  ~0,26ms em 5.000 linhas — nao justifica alargar o indice nem migration.

  TETO: `teto-listagem` (200) trunca. Quem exibe precisa do `contar-por-autor` ao lado para saber que
  truncou (ver docstring de la').

  DOIS filtros, nao um:
  - `autor_id = ?` — so' materia COM o elo. O acervo protocolado ANTES da mig 0063 tem `autor_id` NULL e
    nao aparece aqui (a projecao nao tem replay — carry da 0044); a UI DIZ isso em vez de fingir acervo
    completo.
  - `autor_tipo = 'vereador'` — achado N-1 da revisao da Task 1. `autor_id` e' um elo que pode SOBREVIVER a
    uma mudanca de especie de autoria (um produtor que emita `proposicao.editada` trocando so' o
    `autor_tipo`, um redrive de evento legado, ou um backfill). Sem este filtro, materia cuja autoria virou
    'executivo'/'comissao' continuaria listada como autoria PARLAMENTAR no perfil publico — atribuicao
    falsa de autoria de ato legislativo, o pior erro possivel nesta tela.

  INDICE (achado M-2): `idx_materia_autor (ente_id, autor_id, ano DESC, sequencial DESC) WHERE autor_id IS
  NOT NULL` continua servindo — o prefixo de igualdade (ente_id, autor_id) casa e as DUAS primeiras chaves
  de ordenacao saem do proprio indice; a terceira (`proposicao_id`) custa um Incremental Sort sobre os
  grupos ja' presorted, medido acima. (A redacao anterior dizia 'sem sort' — era verdade ANTES do desempate
  de F1 e deixou de ser por causa dele.) `autor_tipo` NAO esta' no indice nem no predicado parcial, entao
  vira um Filter na heap sobre as linhas ja' restritas ao par (ente, autor). Para a LISTA isso e' irrelevante
  (o LIMIT 200 capa o trabalho: ~102 buffers, 0,3ms medidos em acervo de 42k linhas); para `contar-por-autor`,
  que nao tem teto, NAO e' — ver o carry de indice na docstring de la'."
  [tx ente-id autor-id]
  {:pre [(some? ente-id) (some? autor-id)]}
  (comum/linhas->kebab
   (jdbc/execute! tx
     (sql/format {:select cols :from [:transparencia.materia]
                  :where [:and [:= :ente_id ente-id] [:= :autor_id autor-id]
                          [:= :autor_tipo "vereador"]]
                  :order-by [[:ano :desc] [:sequencial :desc] [:proposicao_id :desc]]
                  :limit teto-listagem}))))

(defn contar-por-autor
  "Quantas materias de autoria parlamentar DESTE vereador existem — SEM teto (revisao Task 3, F2). Existe
  porque `listar-por-autor` trunca em `teto-listagem`: sem este numero, a resposta do perfil nao carrega
  NENHUM sinal de truncamento e a borda nao tem como dizer 'mostrando 200 de 260'. MESMO par de filtros
  de `listar-por-autor` (autor_id + autor_tipo='vereador'), senao o proprio total mentiria sobre o que a
  lista contem. Paginacao por cursor (que dispensaria o par lista+total) e' CARRY, nao esta fatia.

  CUSTO — esta e' a query DOMINANTE do perfil, nao a lista (medido com EXPLAIN ANALYZE em acervo de 42k
  linhas / 2.000 materias do autor): Bitmap Heap Scan, 978 buffers, ~9,8ms — contra 102 buffers e ~0,3ms
  da lista, que o LIMIT capa. A causa e' `autor_tipo` estar fora de `idx_materia_autor` (nem coluna, nem
  predicado parcial), o que impede contagem index-only e forca acesso a heap por linha casada; e sem teto,
  o custo cresce LINEARMENTE com o acervo do autor. Tolerado nesta fatia (dezenas a centenas de materias
  por vereador e' o caso real), mas a rota publica da Task 4 e' sem auth e sem cache — CARRY com remedio ja'
  identificado: migration nova incluindo `autor_tipo` no indice, ou restringindo o predicado parcial a
  `autor_tipo = 'vereador'`, tornando a contagem index-only. Reavaliar quando a rota existir e der para
  medir ponta a ponta."
  [tx ente-id autor-id]
  {:pre [(some? ente-id) (some? autor-id)]}
  (:contagem
   (comum/linha->kebab
    (jdbc/execute-one! tx
      (sql/format {:select [[[:count :*] :contagem]]
                   :from [:transparencia.materia]
                   :where [:and [:= :ente_id ente-id] [:= :autor_id autor-id]
                           [:= :autor_tipo "vereador"]]})))))

(defn contar-normas-por-autor
  "Numero-card 'viraram lei' do perfil publico: quantas materias DESTE autor ja' tem norma publicada. JOIN
  same-schema (transparencia.materia x transparencia.norma — nao e' cross-schema, §22.10 preservado).

  Mesmo par de filtros de `listar-por-autor`, pelo MESMO motivo (achado N-1): um card 'viraram lei' que
  contasse materia de autoria 'executivo' (ou 'comissao') so' porque o `autor_id` sobreviveu creditaria ao
  vereador uma lei que nao e' dele.

  Card e lista usam o MESMO PREDICADO de autoria, mas NAO o mesmo universo (correcao F3b da revisao Task 3
  — a afirmacao anterior, 'contam o MESMO universo', era falsa): esta contagem NAO tem teto e
  `listar-por-autor` tem (200). Um vereador com 260 materias, 15 delas ja' lei e fora das 200 primeiras,
  ve um card '15 viraram lei' sobre uma lista onde nenhuma das 15 aparece. E' exatamente por isso que
  `contar-por-autor` (-> `:materias-total`) existe: e' o sinal que permite a borda dizer que truncou."
  [tx ente-id autor-id]
  {:pre [(some? ente-id) (some? autor-id)]}
  (:contagem
   (comum/linha->kebab
    (jdbc/execute-one! tx
      (sql/format {:select [[[:count :*] :contagem]]
                   :from [[:transparencia.materia :m]]
                   :join [[:transparencia.norma :n]
                          [:and [:= :n.ente_id :m.ente_id] [:= :n.proposicao_id :m.proposicao_id]]]
                   :where [:and [:= :m.ente_id ente-id] [:= :m.autor_id autor-id]
                           [:= :m.autor_tipo "vereador"]]})))))

(defn- where-em-tramitacao
  "O predicado de 'materias em tramitacao' (portal PUBLICO) — FONTE UNICA para `listar-em-tramitacao` e
  `contar-em-tramitacao` (regra 3 da frente 'truncamento-familia'): um WHERE repetido nos dois lugares
  diverge em silencio no dia em que um estado novo entrar num e nao no outro. `estados-excluidos` e' um set
  de string — vazio nao filtra (lista/conta tudo do ente)."
  [ente-id estados-excluidos]
  (if (seq estados-excluidos)
    [:and [:= :ente_id ente-id] [:not-in :estado (vec estados-excluidos)]]
    [:= :ente_id ente-id]))

(defn listar-em-tramitacao
  "Portal PUBLICO: materias EXCLUINDO os estados terminais informados (ex.: arquivadas), mais recentes
  primeiro. `estados-excluidos` e' um set de string — vazio lista tudo. TETO (`teto-listagem`, 200): quem
  exibe precisa de `contar-em-tramitacao` ao lado para saber que truncou (par lista+total, mesmo racional de
  `listar-por-autor`/`contar-por-autor` acima).

  ARIDADE de 4: `limite` INJETAVEL (achado IMPORTANTE da revisao adversarial — mesmo racional de
  paineis/db/pendencia/listar-abertas) — SO' para o teste provar o invariante 'o total nao capa' sem pagar
  201 linhas; o caminho de PRODUCAO (repositorio.clj) usa a aridade de 3 e cai no default `teto-listagem`.
  `(min limite teto-listagem)` — o chamador nunca CONSEGUE pedir mais que o teto server-side, so' menos."
  ([tx ente-id estados-excluidos] (listar-em-tramitacao tx ente-id estados-excluidos teto-listagem))
  ([tx ente-id estados-excluidos limite]
   {:pre [(some? ente-id) (set? estados-excluidos) (pos-int? limite)]}
   (comum/linhas->kebab
    (jdbc/execute! tx
      (sql/format {:select cols :from [:transparencia.materia]
                   :where (where-em-tramitacao ente-id estados-excluidos)
                   :order-by [[:ano :desc] [:sequencial :desc]]
                   :limit (min limite teto-listagem)})))))

(defn contar-em-tramitacao
  "Quantas materias em tramitacao existem — SEM teto (a familia 'truncamento-familia', sitio (b)):
  `listar-em-tramitacao` corta em `teto-listagem` (200) e o portal publico (a UNICA listagem publica de
  proposicoes, sem outra rota — ver materia-vista.ts/escolherDestaque no FE) publicava so' os 200 primeiros
  sem nenhum sinal de que a Casa tem mais. MESMO predicado de `listar-em-tramitacao` (`where-em-tramitacao`),
  senao o proprio total mentiria sobre o que a lista contem."
  [tx ente-id estados-excluidos]
  {:pre [(some? ente-id) (set? estados-excluidos)]}
  (:contagem
   (comum/linha->kebab
    (jdbc/execute-one! tx
      (sql/format {:select [[[:count :*] :contagem]] :from [:transparencia.materia]
                   :where (where-em-tramitacao ente-id estados-excluidos)})))))
