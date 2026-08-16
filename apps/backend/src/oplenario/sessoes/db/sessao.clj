(ns oplenario.sessoes.db.sessao
  "Persistencia da SESSAO plenaria (§22.6 eixo A) — funcoes sobre a `tx` do tenant (RLS isola). `agendar!` e'
  atomico: numera gapless (kernel/sequencial, escopo 'sessao:<leg>:<tipo>' do ente da SESSAO, reset por sessao
  legislativa) + resolve capabilities (default do tipo + override) + insere 'agendada'. `transicionar!` move
  o estado pela maquina (logic/transicao-valida?, fail-closed) com CAS por lock_version, carimbando o marco
  temporal do alvo. HoneySQL schema-qualified; ente_id em toda query."
  (:require [clojure.string :as str]
            [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]
            [oplenario.kernel.sequencial :as sequencial]
            [oplenario.kernel.tempo :as tempo]
            [oplenario.sessoes.logic :as logic])
  (:import (java.time LocalDate ZoneId)))

(set! *warn-on-reflection* true)

(def ^:private colunas
  [:id :ente_id :sessao_legislativa_id :tipo_sessao :numero_sequencial :estado :modalidade
   :delibera :transmite_publica :gera_ata_regimental :permite_voto_secreto :permite_modalidade_remota
   :agendada_para :aberta_em :encerrada_em :motivo_nao_realizada :lock_version])

(defn agendar!
  "Agenda uma sessao: numera gapless (escopo por sessao legislativa+tipo), resolve capabilities (default do
  tipo + `override` parcial) e insere 'agendada'. `sessao-legislativa-id` = forward-ref a cadastros (sem FK).
  Devolve {:id :numero :ocorrido-em} — `ocorrido-em` (F7 E3, RETURNING de `efetivado_em`, o instante do ato de
  agendar) e' o que o evento sessao.agendada carrega p/ semear `transicionou_em` do SLI (sempre <= qualquer
  transicao futura -> o gate de monotonicidade absorve a ordem). Valida tipo/modalidade (fail-closed)."
  [tx {:keys [id ente-id sessao-legislativa-id tipo-sessao modalidade agendada-para
              capabilities-override created-by]}]
  (logic/validar-tipo tipo-sessao)
  (logic/validar-modalidade modalidade)
  (let [num  (sequencial/proximo! tx (logic/escopo-numeracao sessao-legislativa-id tipo-sessao))
        caps (logic/resolver-capabilities tipo-sessao (or capabilities-override {}))
        row  (comum/linha->kebab
              (jdbc/execute-one! tx
                (sql/format {:insert-into :sessoes.sessao
                             :values [{:id id :ente_id ente-id :sessao_legislativa_id sessao-legislativa-id
                                       :tipo_sessao tipo-sessao :numero_sequencial num :estado "agendada"
                                       :modalidade (or modalidade "presencial")
                                       :delibera (:delibera caps) :transmite_publica (:transmite-publica caps)
                                       :gera_ata_regimental (:gera-ata-regimental caps)
                                       :permite_voto_secreto (:permite-voto-secreto caps)
                                       :permite_modalidade_remota (:permite-modalidade-remota caps)
                                       :agendada_para agendada-para :created_by created-by :efetivado_em [:now]}]
                             :returning [:efetivado_em]})))]
    {:id id :numero num :ocorrido-em (:efetivado-em row)}))

(defn buscar [tx ente-id id]
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:select colunas :from [:sessoes.sessao]
                  :where [:and [:= :ente_id ente-id] [:= :id id]]}))))

(defn janela-para-registro
  "Estado + janela (`aberta_em`/`encerrada_em`) da sessao, com LOCK COMPARTILHADO da linha (`FOR SHARE`).
  Insumo do GATE de escrita de presenca (`logic/motivo-recusa-de-presenca`), sempre lido DENTRO da tx que
  escreve. Devolve o mapa kebab ou nil (sessao inexistente neste ente).

  O `FOR SHARE` nao e' zelo: sem ele o gate seria um TOCTOU dentro da propria tx. Sob READ COMMITTED, entre
  este SELECT e o INSERT em `presenca_evento` uma tx concorrente pode COMMITAR o encerramento da sessao, e o
  evento entra numa sessao ja fechada — o defeito exato que esta fatia fecha, so' que numa janela mais
  estreita. `FOR SHARE` bloqueia o `SELECT ... FOR UPDATE` de `transicionar!` ate' o commit desta tx; na
  ordem inversa, este SELECT espera e (por EvalPlanQual) re-le a linha JA encerrada. Sem inversao de ordem
  de lock entre os dois caminhos (ambos travam a sessao primeiro), logo sem deadlock."
  [tx ente-id id]
  (comum/linha->kebab
   (jdbc/execute-one! tx
     ;; `agendada_para` entra na projecao (revisao da Etapa 2): o PISO da janela deixou de ser `aberta_em` e
     ;; passou a ser o DIA CIVIL da sessao (`logic/piso-da-janela-de-presenca`), que sai de `aberta_em` OU de
     ;; `agendada_para` — sem esta coluna, uma sessao ainda `agendada` ficaria sem piso nenhum, que era
     ;; exatamente o buraco apontado (o estado em que a chamada de quorum acontece).
     (sql/format {:select [:id :estado :aberta_em :encerrada_em :agendada_para] :from [:sessoes.sessao]
                  :where [:and [:= :ente_id ente-id] [:= :id id]]
                  :for :share}))))

(defn listar-por-sessao-legislativa [tx ente-id sessao-legislativa-id]
  (comum/linhas->kebab
   (jdbc/execute! tx
     (sql/format {:select colunas :from [:sessoes.sessao]
                  :where [:and [:= :ente_id ente-id] [:= :sessao_legislativa_id sessao-legislativa-id]]
                  :order-by [[:tipo_sessao :asc] [:numero_sequencial :asc]]}))))

(defn- estado+lock [tx ente-id id]
  (-> (jdbc/execute-one! tx
        (sql/format {:select [:estado :lock_version :aberta_em] :from [:sessoes.sessao]
                     :where [:and [:= :ente_id ente-id] [:= :id id]] :for :update}))
      comum/linha->kebab))

(defn transicionar!
  "Move o estado da sessao para `para` (maquina logic/transicao-valida?, fail-closed) com CAS por lock_version.
  Carimba o marco temporal do alvo: aberta -> aberta_em (so na 1a abertura); encerrada/nao_realizada ->
  encerrada_em; nao_realizada exige `motivo`. Lanca em transicao invalida, conflito de lock ou inexistente.
  Devolve {:de :para :ocorrido-em} — `ocorrido-em` (F7 E3, RETURNING de `atualizado_em`) e' o instante REAL
  da transicao no dominio, que o evento sessao.transicionou carrega p/ o SLI de janela de sessao (paineis)
  carimbar a janela DAQUI, nao do momento em que projeta. CARRY (mesmo do legislativo): `atualizado_em` usa
  o DEFAULT `[:now]`, que em Postgres congela no INICIO da tx — sob concorrencia real na MESMA sessao a
  ordem numerica pode divergir da causal; a verdade canonica (`sessoes.sessao.estado`, via CAS de
  lock_version) NUNCA e' afetada, so' a VISTA best-effort do SLI. Fix definitivo (`clock_timestamp()`) e'
  decisao maior, fora do escopo desta fatia."
  [tx {:keys [id ente-id para motivo updated-by lock-version]}]
  (let [{:keys [estado aberta-em] db-lock :lock-version} (estado+lock tx ente-id id)]
    (when (nil? estado)
      ;; :tipo p/ consistencia com os irmaos (review clojure LOW): se a sessao sumir entre o buscar do controller
      ;; e o FOR UPDATE aqui (TOCTOU; sem DELETE no dominio, na pratica inalcancavel), mapeia 409, nunca 500.
      (throw (ex-info "transicionar!: sessao inexistente" {:tipo :conflito/transicao :id id :ente-id ente-id})))
    ;; conflito de concorrencia ANTES da validacao de maquina: um caller com lock stale (estado ja avancou)
    ;; recebe "conflito de lock" — diagnostico correto p/ retry — em vez de "transicao invalida" (review F4.1).
    (when (not= db-lock lock-version)
      (throw (ex-info "transicionar!: conflito de lock_version"
                      {:tipo :conflito/transicao :id id :esperado lock-version :atual db-lock})))
    (when-not (logic/transicao-valida? estado para)
      (throw (ex-info "transicionar!: transicao de estado invalida"
                      {:tipo :conflito/transicao :id id :de estado :para para})))
    (when (and (= "nao_realizada" para) (str/blank? motivo))
      (throw (ex-info "transicionar!: 'nao_realizada' exige motivo" {:tipo :validacao/invalido :id id})))
    (let [sets (cond-> {:estado para :updated_by updated-by :atualizado_em [:now]
                        :lock_version [:+ :lock_version 1]}
                 (and (= "aberta" para) (nil? aberta-em)) (assoc :aberta_em [:now])
                 (contains? #{"encerrada" "nao_realizada"} para) (assoc :encerrada_em [:now])
                 (= "nao_realizada" para) (assoc :motivo_nao_realizada motivo))
          r (comum/linha->kebab
              (jdbc/execute-one! tx
                (sql/format {:update :sessoes.sessao :set sets
                             :where [:and [:= :ente_id ente-id] [:= :id id] [:= :lock_version lock-version]]
                             :returning [:atualizado_em]})))]
      ;; com RETURNING, execute-one! devolve a LINHA (ou nil se 0 linhas casaram o WHERE) — nil = conflito.
      (when (nil? r)
        (throw (ex-info "transicionar!: conflito de lock_version ou sessao inexistente"
                        {:tipo :conflito/transicao :id id :lock-version lock-version})))
      {:de estado :para para :ocorrido-em (:atualizado-em r)})))

;; ---------- Etapa 6 fatia 2 — sessoes FECHADAS de um periodo (insumo da apuracao de assiduidade) ----------

(def ^:private data-de-referencia-sql
  "O fragmento SQL da DATA DE REFERENCIA — `COALESCE` sobre `logic/marcos-de-data-de-referencia-sql`,
  DERIVADO da mesma constante que `logic/data-de-referencia-da-sessao` consome. Antes desta extracao, a
  ordem `(aberta_em, agendada_para)` estava redigitada aqui (no filtro), de novo logo abaixo (no rotulo em
  Clojure) e uma terceira vez no controller: inverter uma das copias fazia a sessao ser FILTRADA por uma data
  e ROTULADA com outra, com a suite inteira verde (nenhuma fixture tinha os dois marcos em dias civis
  diferentes)."
  (into [:coalesce] logic/marcos-de-data-de-referencia-sql))

(defn- where-fechadas-no-periodo
  "O predicado COMPARTILHADO das duas leituras do periodo (`listar-fechadas-no-periodo` e
  `contar-fechadas-sem-data-de-referencia`): mesmo tenant, mesmos estados fechados, mesmo filtro de tipo. O
  que difere entre elas e' SO' a clausula de data, passada em `extra`."
  [ente-id tipos extra]
  (cond-> (into [:and [:= :ente_id ente-id]
                 [:in :estado (vec logic/estados-sessao-fechada)]]
                extra)
    (seq tipos) (conj [:in :tipo_sessao (vec tipos)])))

(defn contar-fechadas-sem-data-de-referencia
  "QUANTAS sessoes FECHADAS do recorte (mesmo tenant, mesmos estados, mesmo filtro de tipo) NAO tem data de
  referencia nenhuma — `COALESCE(aberta_em, agendada_para) IS NULL`.

  POR QUE ESTE CONTADOR EXISTE. A linha e' alcancavel pela API NORMAL: `agendada_para` e' OPCIONAL no wire
  e nullable na coluna, e a transicao `agendada -> nao_realizada` nao exige data — nenhuma CHECK a barra.
  Sem data de referencia ela nao cabe em periodo nenhum, entao `listar-fechadas-no-periodo` a exclui; ate' a
  revisao desta fatia, exclu-ia em SILENCIO, e o denominador de TODOS os vereadores encolhia sem explicacao.
  A mesma linha faz `/sessoes/:id/chamada` devolver um 409 ACIONAVEL (`:conflito/sessao-sem-data`) — dois
  comportamentos para o mesmo buraco, e o que virava documento era o silencioso. Assiduidade sustenta perda
  de mandato por falta (DL 201): publicar 92% onde o correto e' 91,7% sem nenhum sinal e' o silencio que I7
  proibe.

  A DECISAO E' CONTAR E PUBLICAR, nao consertar o COALESCE. Acrescentar `encerrada_em` como terceiro nivel
  arrumaria o denominador e criaria uma QUARTA variante da regra de data de referencia, divergente da que a
  `/chamada` aplica — que e' exatamente o drift que a Etapa 6 existe para policiar (ver
  `logic/marcos-de-data-de-referencia`). A apuracao continua excluindo, e DECLARA: `:sessoes-sem-data-de-
  referencia N` em `:totais`, ao lado de `:sessoes-sigilosas`."
  [tx ente-id {:keys [de ate tipos]}]
  (logic/validar-periodo-assiduidade! de ate)
  (logic/validar-tipos-de-assiduidade! tipos)
  (-> (jdbc/execute-one! tx
        (sql/format {:select [[[:count :*] :n]]
                     :from [:sessoes.sessao]
                     :where (where-fechadas-no-periodo ente-id tipos [[:= data-de-referencia-sql nil]])}))
      comum/linha->kebab
      :n
      long))

(defn listar-fechadas-no-periodo
  "As sessoes FECHADAS (`logic/estados-sessao-fechada`: encerrada/nao_realizada/arquivada) cuja DATA DE
  REFERENCIA cai em `[de, ate]` (LocalDate, inclusivo dos dois lados) — a mesma data que a CHAMADA usa:
  `logic/data-de-referencia-da-sessao`, cuja ordem de marcos (`logic/marcos-de-data-de-referencia`) e' a
  MESMA fonte do `COALESCE` do filtro e do rotulo devolvido em cada linha.

  O FILTRO DE DATA E' EXATO, nao uma janela aproximada: `de`/`ate` (datas CIVIS no fuso
  `tempo/zona-civil-padrao`) viram um intervalo de INSTANTES `[lo, hi)` — `lo` = inicio do dia civil de `de`,
  `hi` = inicio do dia civil do dia SEGUINTE a `ate` — e o range de instantes cujo dia civil (na MESMA zona)
  cai em `[de, ate]` E' EXATAMENTE esse intervalo. Por isso o WHERE compara `data-de-referencia-sql` contra
  `lo`/`hi` (calculados AQUI, em Clojure, com o fuso — o SQL nunca ve o fuso, mesmo racional documentado em
  `transparencia/db/parlamentar.clj`: 'a data e derivada em Clojure e chega pronta').
  Sessao SEM NENHUM dos dois marcos (agendada sem data, jamais aberta, dada como nao_realizada) tem
  `COALESCE` NULL — a comparacao falha e ela fica FORA do periodo. Isso NAO e' silencioso: quantas ficaram de
  fora e' contado por `contar-fechadas-sem-data-de-referencia` (acima) e publicado em `:totais`.

  Cada linha devolvida carrega `:data-de-referencia` (LocalDate) JA CALCULADA — uma unica vez, aqui, por
  `logic/data-de-referencia-da-sessao` (a MESMA funcao do controller da chamada) — para
  `logic/apurar-assiduidade` NUNCA recalcula-la (I3-like: uma so' fonte para 'qual e' a data desta sessao').
  O `throw` daquela funcao para sessao sem marco nenhum e' aqui uma REDE: o WHERE ja' excluiu essas linhas,
  entao dispara-lo significaria que filtro e rotulo divergiram — que e' precisamente o que a constante
  compartilhada existe para impedir.

  TRES rejeicoes, nenhuma silenciosa (I7 do brief):
  - `logic/validar-periodo-assiduidade!` (tipo das datas + ordem + dias do periodo) — pura, sem tocar o banco;
  - `logic/validar-tipos-de-assiduidade!` (cada elemento de `tipos` string E ∈ `logic/tipos-sessao`) — a REDE
    contra o chamador direto, espelhando o que o controller ja' faz. Sem ela, `tipos` era a UNICA entrada da
    apuracao que chegava ao SQL sem validacao em camada nenhuma;
  - `logic/teto-de-sessoes-do-periodo-de-assiduidade` (400) via `:max-rows` = teto+1: o driver PARA de
    materializar no primeiro excedente (mesmo mecanismo de `vereador/roster-da-casa-em-datas`), entao a
    memoria nunca e' gasta com o excesso. O SERVIDOR, porem, ainda ORDENA o resultado inteiro antes do corte
    — `Sort` e' no bloqueante, e `:max-rows` age no cliente/protocolo, nao no plano. O custo medido disso e'
    irrelevante nesta leitura (~0,6 ms), mas a frase honesta e' 'o driver para de materializar', nao 'o banco
    para de trabalhar'. Ler teto+1 e' a PROVA do estouro, e a funcao lanca `:limite/sessoes-excedido`
    (`:medido-ao-menos`, nao `:medido` — a medicao e' um PISO) em vez de devolver a pagina truncada.

  `tipos` filtra `tipo_sessao` (vocabulario `logic/tipos-sessao`); nil/vazio = todos os tipos."
  [tx ente-id {:keys [de ate tipos]}]
  (logic/validar-periodo-assiduidade! de ate)
  (logic/validar-tipos-de-assiduidade! tipos)
  (let [lo (-> ^LocalDate de (.atStartOfDay ^ZoneId tempo/zona-civil-padrao) .toInstant)
        hi (-> ^LocalDate (.plusDays ^LocalDate ate 1) (.atStartOfDay ^ZoneId tempo/zona-civil-padrao) .toInstant)
        teto logic/teto-de-sessoes-do-periodo-de-assiduidade
        linhas (comum/linhas->kebab
                (jdbc/execute! tx
                  (sql/format {:select [:id [:numero_sequencial :numero] [:tipo_sessao :tipo] :estado
                                        :agendada_para :aberta_em :encerrada_em :transmite_publica]
                               :from [:sessoes.sessao]
                               :where (where-fechadas-no-periodo
                                       ente-id tipos [[:>= data-de-referencia-sql lo]
                                                      [:< data-de-referencia-sql hi]])
                               :order-by [[data-de-referencia-sql :asc] [:id :asc]]})
                  {:max-rows (inc teto)}))]
    (when (> (count linhas) teto)
      (throw (ex-info "sessoes no periodo de apuracao de assiduidade acima do teto"
                      {:tipo :limite/sessoes-excedido :medido-ao-menos (count linhas) :teto teto})))
    (mapv (fn [linha] (assoc linha :data-de-referencia (logic/data-de-referencia-da-sessao linha))) linhas)))
