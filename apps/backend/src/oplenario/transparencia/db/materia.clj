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

(defn listar-em-tramitacao
  "Portal PUBLICO: materias EXCLUINDO os estados terminais informados (ex.: arquivadas), mais recentes
  primeiro. `estados-excluidos` e' um set de string — vazio lista tudo. Com teto (sem paginacao nesta fatia)."
  [tx ente-id estados-excluidos]
  {:pre [(some? ente-id) (set? estados-excluidos)]}
  (comum/linhas->kebab
   (jdbc/execute! tx
     (sql/format {:select cols :from [:transparencia.materia]
                  :where (if (seq estados-excluidos)
                           [:and [:= :ente_id ente-id] [:not-in :estado (vec estados-excluidos)]]
                           [:= :ente_id ente-id])
                  :order-by [[:ano :desc] [:sequencial :desc]]
                  :limit teto-listagem}))))
