(ns oplenario.paineis.db.tramitacao
  "Persistencia de 'paineis.tramitacao' (F7 Slice 2, §16.11) — funcoes sobre a `tx` corrente (FORCE RLS
  isola, mig 0049). HoneySQL schema-qualified; ente_id em TODA query. Read-model PROJETADO — `inserir!`
  materializa o snapshot no protocolo (`proposicao.protocolada`); `atualizar-estado!` aplica a transicao
  (`proposicao.transicionou`). Chamado pelo CONSUMER (§22.10 diplomat/consumers), dentro da tx do relay —
  NAO ha Repo-Component na escrita; o Repo-Component (components/repositorio) so' serve a LEITURA interna
  do servidor. Mesmo par de eventos que `transparencia.db.materia` ja projeta — aqui SEM o filtro de
  estados-excluidos do portal publico (o board interno mostra TUDO) + `transicionou_em` (staleness)."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum])
  (:import (java.time Instant)))

(set! *warn-on-reflection* true)

(def ^:private cols
  [:ente_id :proposicao_id :tipo :ano :sequencial :urn_lex :ementa :autor_tipo :autor_texto :estado
   :projetado_em :transicionou_em])

(def ^:private teto-por-estado-absoluto
  "Ceiling absoluto do teto POR GRUPO de estado (defesa-em-profundidade — `listar-board` recebe `limite` do
  Repo, mas nunca confia nele cegamente, mesmo racional de transparencia/db/materia/teto-listagem)."
  200)

(defn inserir!
  "Projeta o snapshot do protocolo (`proposicao.protocolada`). `ON CONFLICT (ente_id,proposicao_id) DO
  NOTHING` (mesmo racional de transparencia/db/materia/inserir! e paineis/db/pendencia/inserir!): cinto-de-
  seguranca contra um futuro redrive/backfill que reemita o MESMO evento com idempotency-key NOVA — sem
  isto, o redrive lancaria PK-violation e envenenaria o RELAY COMPARTILHADO.

  `transicionou_em` semeado com `Instant/EPOCH` (review clojure HIGH: NAO usar o DEFAULT now() da coluna,
  mig 0049) — o protocolo (`ProtocoladaPayload`) nao carrega nenhum instante de dominio p/ semear este campo
  de forma significativa, entao o valor tem que ser um PLACEHOLDER; usar `now()` (tempo de PROCESSAMENTO)
  misturava base de relogio com o `:ocorrido-em` (tempo de DOMINIO) que `atualizar-estado!` passou a exigir
  no gate de monotonicidade — sob backlog do relay (protocolada+transicionou drenados juntos no catch-up), a
  1a transicao real (`:ocorrido-em` no PASSADO, quando realmente ocorreu) ficava MENOR que o `now()` do
  catch-up, e o gate `WHERE transicionou_em <= ?` rejeitava a atualizacao legitima — CONGELANDO o estado em
  silencio, pior que o bug original (sinal impreciso vira ESTADO ERRADO). EPOCH e' SEMPRE <= qualquer
  `:ocorrido-em` real (nenhuma proposicao tramita antes de 1970), entao a 1a transicao nunca e' rejeitada."
  [tx {:keys [ente-id proposicao-id tipo ano sequencial urn-lex ementa autor-tipo autor-texto estado]}]
  {:pre [(some? ente-id) (some? proposicao-id) (some? tipo) (some? ano) (some? sequencial)
         (some? urn-lex) (some? ementa) (some? estado)]}
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:insert-into :paineis.tramitacao
                  :values [{:ente_id ente-id :proposicao_id proposicao-id :tipo tipo :ano ano
                            :sequencial sequencial :urn_lex urn-lex :ementa ementa
                            :autor_tipo autor-tipo :autor_texto autor-texto :estado estado
                            :transicionou_em Instant/EPOCH}]
                  :on-conflict [:ente_id :proposicao_id]
                  :do-nothing []
                  :returning [:*]}))))

(defn atualizar-estado!
  "Projeta a transicao (`proposicao.transicionou`): `estado` + `transicionou_em` mudam (o snapshot de
  conteudo e' imutavel pos-protocolo). TOLERANTE (mesmo racional de transparencia/db/materia/
  atualizar-estado!, review architect HIGH-1 da fatia anterior): 0 linhas afetadas (materia ainda nao
  projetada) devolve nil em vez de lancar — um `throw` aqui rodaria DENTRO da tx do RELAY COMPARTILHADO por
  todos os modulos, fazendo o mesmo evento ser reprocessado para sempre (poison, head-of-line block).

  `transicionou-em` (F7 carry FECHADO — era `now()` no momento da PROJECAO; `legislativo.
  TransicionouPayload` agora carrega `:ocorrido-em`, o instante REAL da transicao no dominio, RETURNING de
  `proposicao_transicao_historico.ocorrido_em`) — o board deixa de resetar o sinal de estagnacao sob atraso
  comum do relay. GATE DE MONOTONICIDADE (`WHERE transicionou_em <= ?transicionou-em`): torna o UPDATE seguro
  contra um futuro redrive/backfill fora de ordem (R-DR, ainda sem ferramenta) — uma transicao MAIS ANTIGA
  reentregue apos uma MAIS NOVA ja projetada e' no-op (nunca retrocede o carimbo).

  CARRY (review database MEDIUM-HIGH, nao corrigido aqui — decisao de `legislativo`, nao de `paineis`):
  `ocorrido_em` usa o DEFAULT `now()` da coluna (mig 0016) — em Postgres, `now()` congela no INICIO da
  transacao, nao no instante do `SELECT ... FOR UPDATE`/INSERT. Sob CONCORRENCIA REAL na MESMA proposicao
  (2 transacoes disputando o lock), a transacao que VENCE o lock por ultimo (causal/serialmente DEPOIS) pode
  ter um `now()` MENOR que a que venceu primeiro (comecou depois, mas foi bloqueada menos tempo) — o gate
  `<=` rejeitaria a atualizacao CAUSALMENTE mais nova por ter timestamp NUMERICAMENTE mais antigo, deixando
  `estado` congelado na transicao anterior ate' a PROXIMA transicao (auto-cura na proxima, nunca permanente).
  A verdade canonica (`legislativo.proposicao.estado`, via CAS de lock_version) NUNCA e' afetada — so' esta
  VISTA interna, best-effort, pode atrasar sob esta janela estreita. Fix correto = `legislativo` trocar
  `now()` por `clock_timestamp()` no DEFAULT de `ocorrido_em` (E em `efetivado_em`) — decisao que tambem
  afeta `historico-da-proposicao` (a prova de auditoria Inv.10), fora do escopo de uma fatia de `paineis`."
  [tx {:keys [ente-id proposicao-id estado transicionou-em]}]
  {:pre [(some? ente-id) (some? proposicao-id) (some? estado) (some? transicionou-em)]}
  (let [r (jdbc/execute-one! tx
            (sql/format {:update :paineis.tramitacao
                         :set {:estado estado :transicionou_em transicionou-em}
                         :where [:and [:= :ente_id ente-id] [:= :proposicao_id proposicao-id]
                                 [:<= :transicionou_em transicionou-em]]}))]
    (when-not (zero? (:next.jdbc/update-count r 0))
      {:proposicao-id proposicao-id :estado estado})))

(defn resumo
  "Rollup 'proposicoes por status' (F7 dashboard da Mesa, §16.11): contagem por estado do tenant. GROUP BY
  estado -> [{:estado :n}], mais numeroso primeiro (`estado` ASC como desempate deterministico). Sem teto (a
  cardinalidade e' o numero de estados da maquina de tramitacao, dezenas no maximo). O adapters/out soma o
  total e projeta — nao ha PII nem ponteiro interno numa contagem agregada."
  [tx ente-id]
  {:pre [(some? ente-id)]}
  (comum/linhas->kebab
   (jdbc/execute! tx
     (sql/format {:select [:estado [[:count :*] :n]]
                  :from [:paineis.tramitacao]
                  :where [:= :ente_id ente-id]
                  :group-by [:estado]
                  :order-by [[:n :desc] [:estado :asc]]}))))

(defn listar-board
  "O board (§16.11): TODAS as proposicoes do tenant (sem filtro de estado — diferenca-chave vs. o portal
  publico), agrupadas por `estado` e ordenadas por `transicionou_em` ASC (`proposicao_id` como desempate
  deterministico, mesmo racional de compliance/db/obrigacao/listar-em-aberto e paineis/db/pendencia/
  listar-abertas) dentro do grupo — a mais estagnada primeiro, o sinal de 'precisa de atencao'. `limite' e'
  o teto POR GRUPO de estado (review database HIGH: um LIMIT global sobre `ORDER BY estado, transicionou_em`
  deixa um UNICO estado com muitas linhas [ex.: um estado terminal como 'arquivada', que so' cresce ao
  longo dos anos] engolir a pagina inteira — os estados ATIVOS [ex.: 'em_comissao'], que sao o motivo do
  board existir, ficam de fora em SILENCIO). ROW_NUMBER() OVER (PARTITION BY estado ORDER BY transicionou_em,
  proposicao_id) numa subquery, filtrado a `<= limite` — garante que TODO estado aparece no board, nao so'
  o que tem mais linhas."
  [tx ente-id limite]
  {:pre [(some? ente-id) (pos-int? limite)]}
  (let [teto (min limite teto-por-estado-absoluto)]
    (comum/linhas->kebab
     (jdbc/execute! tx
       (sql/format {:select cols
                    :from [[{:select (conj (vec cols)
                                           [[:over [[:row_number]
                                                    {:partition-by [:estado]
                                                     :order-by [[:transicionou_em :asc] [:proposicao_id :asc]]}]]
                                            :rn])
                             :from [:paineis.tramitacao]
                             :where [:= :ente_id ente-id]}
                            :pagina]]
                    :where [:<= :rn teto]
                    :order-by [[:estado :asc] [:transicionou_em :asc] [:proposicao_id :asc]]})))))
