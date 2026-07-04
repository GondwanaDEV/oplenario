(ns oplenario.paineis.db.sli-sessao
  "Persistencia de 'paineis.sli_sessao' (F7 E3, Inv.9 — SLI de janela de sessao) — funcoes sobre a `tx`
  corrente (FORCE RLS isola, mig 0051). HoneySQL schema-qualified; ente_id em TODA query. Read-model PROJETADO
  do evento `sessao.transicionou` (F4): UMA linha por sessao com a JANELA (aberta_em -> encerrada_em) + estado
  atual. `projetar-transicao!` e' um UPSERT (a 1a transicao de uma sessao INSERE; as seguintes atualizam) —
  nao ha evento de 'criacao' separado (a sessao nasce 'agendada' SEM emitir evento; o SLI a enxerga a partir
  da 1a transicao — `agendada->aberta`|`agendada->nao_realizada`). Chamado pelo CONSUMER (§22.10
  diplomat/consumers), dentro da tx do relay — NAO ha Repo-Component na escrita; o Repo-Component so' serve a
  LEITURA interna do servidor.

  Dono da verdade continua `sessoes` (a maquina de estado, CAS por lock_version); aqui so' a VISTA
  best-effort de observabilidade."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]))

(set! *warn-on-reflection* true)

(def ^:private cols
  [:sessao_id :estado_atual :aberta_em :encerrada_em :transicionou_em])

(def ^:private teto-sli-absoluto
  "Ceiling absoluto do read (defesa-em-profundidade — `listar-sli-sessoes` recebe `limite` do Repo, mas nunca
  confia nele cegamente; mesmo racional de db/tramitacao/teto-por-estado-absoluto). Sessoes por tenant sao
  limitadas por legislatura; algumas centenas cobre folgado o horizonte de um painel."
  500)

(def ^:private estados-encerramento
  "Transicoes que carimbam encerrada_em (fim da janela): encerramento normal E 'nao_realizada' (a sessao
  agendada que nao aconteceu — tambem e' um desfecho terminal da janela, e um sinal de SLI relevante)."
  #{"encerrada" "nao_realizada"})

(defn projetar-transicao!
  "UPSERT de uma transicao de sessao (`sessao.transicionou`) na vista de SLI. `ocorrido-em` = Instant REAL da
  transicao (o consumer ja' parseou a string ISO do evento). Semantica:
    - estado_atual/transicionou_em: sempre adotam o valor da transicao mais recente aplicada.
    - aberta_em: carimbado NA 1a abertura (`para`='aberta') e PRESERVADO depois (COALESCE existente-primeiro) —
      uma suspensao/reabertura nao reescreve o inicio da janela; espelha `db/sessao/transicionar!` (so na 1a).
    - encerrada_em: carimbado no fechamento ('encerrada'|'nao_realizada') e preservado (COALESCE excluded-
      primeiro: so' escreve quando a transicao ATUAL e' de encerramento; uma transicao posterior nao-terminal
      — ex.: 'arquivada' — nao apaga o fim ja' gravado).

  GATE DE MONOTONICIDADE (`WHERE sli_sessao.transicionou_em <= EXCLUDED.transicionou_em` no DO UPDATE — mesmo
  racional de db/tramitacao/atualizar-estado!): uma transicao MAIS ANTIGA reentregue DEPOIS de uma mais nova
  ja' projetada (redrive/backfill fora de ordem, R-DR) e' no-op — nunca retrocede o estado nem a janela.
  Idempotente sob redrive do MESMO evento (transicionou_em igual: `<=` re-aplica os MESMOS valores).

  NO-OP SILENCIOSO (DESIGN CONSCIENTE, review clojure MAJOR): quando o gate bloqueia (transicao fora de
  ordem), o DO UPDATE afeta 0 linhas e NADA e' logado — assimetrico vs. `fechar!`/`transicionar-tramitacao!`
  (que logam todo no-op por 'sem linha projetada'). E' deliberado, nao descuido: num bus AT-LEAST-ONCE,
  redrive/reordenacao e' NORMAL e BENIGNO (o gate existe justamente p/ absorve-lo); um `log/warn` por
  ocorrencia seria ruido de falso-alarme. A verdade canonica vive em `sessoes.sessao` (CAS de lock_version) —
  esta vista best-effort atrasar sob reordenacao nao e' incidente. Se um dia a observabilidade de reprojecao
  exigir, o sinal certo e' uma metrica de contagem (pilar 2 do Inv.7, infra-gated [GAP]), nao um log por evento.

  ARTEFATO 'ENCERRAMENTO-PRIMEIRO' (review architect MINOR-3): se a 1a transicao JAMAIS vista de uma sessao
  for de encerramento (a `agendada->aberta` foi perdida/atrasada e chega DEPOIS, virando no-op pelo gate),
  `aberta_em` fica NULL para sempre e a duracao da janela e' incomputavel. Raro na pratica (o outbox entrega
  em ordem de id e o CAS de lock_version serializa as transicoes da MESMA sessao), artefato aceitavel de vista
  best-effort — a duracao ausente e' fail-soft (nil), nunca valor errado."
  [tx {:keys [ente-id sessao-id para ocorrido-em]}]
  {:pre [(some? ente-id) (some? sessao-id) (some? para) (some? ocorrido-em)]}
  (let [aberta    (when (= para "aberta") ocorrido-em)
        encerrada (when (contains? estados-encerramento para) ocorrido-em)]
    (jdbc/execute-one! tx
      (sql/format {:insert-into :paineis.sli_sessao
                   :values [{:ente_id ente-id :sessao_id sessao-id :estado_atual para
                             :transicionou_em ocorrido-em :aberta_em aberta :encerrada_em encerrada}]
                   :on-conflict [:ente_id :sessao_id]
                   :do-update-set {:fields {:estado_atual     :excluded.estado_atual
                                            :transicionou_em  :excluded.transicionou_em
                                            :aberta_em    [:coalesce :sli_sessao.aberta_em :excluded.aberta_em]
                                            :encerrada_em [:coalesce :excluded.encerrada_em :sli_sessao.encerrada_em]}
                                   :where [:<= :sli_sessao.transicionou_em :excluded.transicionou_em]}}))))

(defn listar-sli-sessoes
  "O SLI de janela de sessao (Inv.9) do tenant: TODAS as sessoes vistas, ABERTAS primeiro (encerrada_em IS
  NULL = as que ainda estao em curso ou possivelmente TRAVADAS — o sinal operacional que o painel existe p/
  pegar). DENTRO do grupo aberto, a MAIS ANTIGA primeiro (`transicionou_em` ASC via CASE) — a sessao aberta
  ha' mais tempo e' a mais provavelmente travada, e tem de estar no TOPO (review database MEDIUM: com
  desempate DESC, sob o teto, cairiam fora justamente as abertas mais antigas — o sinal mais critico; mesmo
  racional 'mais estagnada primeiro' de db/tramitacao/listar-board). O grupo CONCLUIDO vem depois, mais
  recente primeiro (`transicionou_em` DESC); `sessao_id` desempate deterministico. `limite` teto server-side
  (clampado por teto-sli-absoluto)."
  [tx ente-id limite]
  {:pre [(some? ente-id) (pos-int? limite)]}
  (comum/linhas->kebab
   (jdbc/execute! tx
     (sql/format {:select cols :from [:paineis.sli_sessao]
                  :where [:= :ente_id ente-id]
                  ;; 1) abertas primeiro; 2) NO grupo aberto, mais antiga primeiro (CASE: transicionou_em p/
                  ;; abertas, NULL->NULLS LAST p/ concluidas neste criterio); 3) concluidas por recencia; 4) desempate.
                  :order-by [[[:is :encerrada_em nil] :desc]
                             [[:case [:is :encerrada_em nil] :transicionou_em] :asc]
                             [:transicionou_em :desc]
                             [:sessao_id :asc]]
                  :limit (min limite teto-sli-absoluto)}))))
