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
            [oplenario.kernel.db-util :as comum]))

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
  isto, o redrive lancaria PK-violation e envenenaria o RELAY COMPARTILHADO."
  [tx {:keys [ente-id proposicao-id tipo ano sequencial urn-lex ementa autor-tipo autor-texto estado]}]
  {:pre [(some? ente-id) (some? proposicao-id) (some? tipo) (some? ano) (some? sequencial)
         (some? urn-lex) (some? ementa) (some? estado)]}
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:insert-into :paineis.tramitacao
                  :values [{:ente_id ente-id :proposicao_id proposicao-id :tipo tipo :ano ano
                            :sequencial sequencial :urn_lex urn-lex :ementa ementa
                            :autor_tipo autor-tipo :autor_texto autor-texto :estado estado}]
                  :on-conflict [:ente_id :proposicao_id]
                  :do-nothing []
                  :returning [:*]}))))

(defn atualizar-estado!
  "Projeta a transicao (`proposicao.transicionou`): `estado` + `transicionou_em` mudam (o snapshot de
  conteudo e' imutavel pos-protocolo). TOLERANTE (mesmo racional de transparencia/db/materia/
  atualizar-estado!, review architect HIGH-1 da fatia anterior): 0 linhas afetadas (materia ainda nao
  projetada) devolve nil em vez de lancar — um `throw` aqui rodaria DENTRO da tx do RELAY COMPARTILHADO por
  todos os modulos, fazendo o mesmo evento ser reprocessado para sempre (poison, head-of-line block).

  CARRY (review architect MEDIUM + review database MEDIUM, F7 Slice 2 — severidade CONFIRMADA pelo database:
  nao depende de reprojecao futura, um backlog COMUM do relay ja basta): `transicionou_em` e' carimbado com
  `now()` NO MOMENTO DA PROJECAO, nao com o instante real da transicao no dominio. `legislativo.
  proposicao_transicao_historico.ocorrido_em` JA existe como o timestamp AUTORITATIVO (mig 0016), mas
  `TransicionouPayload` (legislativo/events/proposicao.clj) NAO o carrega (so' :de/:para/:gatilho/
  :transicao-id/:ator-id), e o envelope do outbox (kernel/outbox.clj row->evento) tambem nao expoe
  `criado_em` da linha ao handler. Sob relay saudavel a imprecisao e' de ms — mas QUALQUER atraso de
  catch-up (deploy, blip de conexao no auto-heal do OutboxRelay, backpressure) faz TODO evento drenado no
  catch-up carimbar 'agora', fazendo uma materia REALMENTE estagnada ha horas parecer 'acabou de
  transicionar' bem no momento em que o sinal de estagnacao mais importa (pos-incidente) — sem precisar de
  nenhuma ferramenta de reprojecao (R-DR) para acontecer. Fix correto exige estender o CONTRATO do evento em
  `legislativo` (:ocorrido-em em TransicionouPayload, alimentado por `ocorrido_em` que a maquina de
  tramitacao ja persiste) + gate de monotonicidade aqui (`WHERE transicionou_em <= ?ocorrido-em`, tornando o
  UPDATE seguro contra redrive fora de ordem tambem) — fora do escopo desta fatia (§22.10: paineis nao
  deveria precisar mudar um contrato de outro modulo sem decisao propria; `transparencia.materia.
  atualizado_em` tem a MESMA fragilidade, ali mais tolerada por nao ser o sinal central de nenhuma feature).
  Registrar como fatia propria em `legislativo` (estender TransicionouPayload) antes de confiar neste board
  p/ decisao operacional pos-incidente."
  [tx {:keys [ente-id proposicao-id estado]}]
  {:pre [(some? ente-id) (some? proposicao-id) (some? estado)]}
  (let [r (jdbc/execute-one! tx
            (sql/format {:update :paineis.tramitacao
                         :set {:estado estado :transicionou_em [:now]}
                         :where [:and [:= :ente_id ente-id] [:= :proposicao_id proposicao-id]]}))]
    (when-not (zero? (:next.jdbc/update-count r 0))
      {:proposicao-id proposicao-id :estado estado})))

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
