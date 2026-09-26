(ns oplenario.sessoes.logic
  "PURO: regras e maquina de estados da sessao plenaria (§22.6 eixo A). Sem I/O. Capabilities desacopladas
  do tipo (disciplina §22.6.3 nº3): o tipo e' nome regimental, o comportamento e' atributo com default
  derivado + override auditado. Os vocabularios espelham os CHECK da migration 0026.

  Requer `kernel/tempo` SO' pela zona civil (§22.6 eixo C: o piso da janela de presenca e' o DIA CIVIL da
  sessao, e dia civil nao existe sem fuso). Continua PURO — `zona-civil-padrao` e' uma constante, nao um
  relogio; nenhum instante e' LIDO aqui."
  (:require [clojure.string :as str]
            [oplenario.kernel.tempo :as tempo])
  (:import (java.time Duration Instant LocalDate ZoneId)
           (java.time.temporal ChronoUnit)))

(set! *warn-on-reflection* true)

(def tipos-sessao #{"ordinaria" "extraordinaria" "solene" "secreta" "especial"})
(def modalidades-sessao #{"presencial" "remota" "hibrida"})
(def estados-sessao #{"agendada" "aberta" "suspensa" "encerrada" "nao_realizada" "arquivada"})

(def transicoes-sessao
  "Mapa de transicoes validas do ciclo de vida da sessao (§22.6 eixo A). agendada -> aberta (a sessao comeca)
  ou nao_realizada (prejudicada: falta de quorum/luto/caso fortuito); aberta <-> suspensa; aberta|suspensa
  -> encerrada; encerrada|nao_realizada -> arquivada (terminal). 'arquivada' nao sai (terminal final)."
  {"agendada"      #{"aberta" "nao_realizada"}
   "aberta"        #{"suspensa" "encerrada"}
   "suspensa"      #{"aberta" "encerrada"}
   "encerrada"     #{"arquivada"}
   "nao_realizada" #{"arquivada"}
   "arquivada"     #{}})

(defn transicao-valida?
  "A transicao de->para e' permitida pela maquina? (puro)"
  [de para]
  (contains? (get transicoes-sessao de #{}) para))

(def capabilities
  "As 5 capabilities da sessao (§22.6 eixo A) — ordem estavel p/ defaults/models."
  [:delibera :transmite-publica :gera-ata-regimental :permite-voto-secreto :permite-modalidade-remota])

(def ^:private capabilities-default-por-tipo
  "Defaults DEFENSAVEIS por tipo (regimental — [GAP] p/ o especialista, como os templates do motor). O
  override individual e' explicito e auditado (disc.3). [delibera transmite gera_ata voto_secreto remota]"
  {;;                       delib  transm  ata   v.secr  remota
   "ordinaria"      {:delibera true  :transmite-publica true  :gera-ata-regimental true  :permite-voto-secreto false :permite-modalidade-remota true}
   "extraordinaria" {:delibera true  :transmite-publica true  :gera-ata-regimental true  :permite-voto-secreto false :permite-modalidade-remota true}
   "solene"         {:delibera false :transmite-publica true  :gera-ata-regimental false :permite-voto-secreto false :permite-modalidade-remota false}
   "secreta"        {:delibera true  :transmite-publica false :gera-ata-regimental true  :permite-voto-secreto true  :permite-modalidade-remota false}
   "especial"       {:delibera false :transmite-publica true  :gera-ata-regimental false :permite-voto-secreto false :permite-modalidade-remota true}})

(defn capabilities-default
  "As capabilities default do tipo. Fail-closed: tipo desconhecido lanca (nao monta sessao sem comportamento)."
  [tipo]
  (or (get capabilities-default-por-tipo tipo)
      (throw (ex-info "tipo de sessao sem capabilities default" {:tipo tipo}))))

(defn resolver-capabilities
  "Capabilities efetivas = default do tipo + override (so as chaves presentes em `override` sobrescrevem).
  `override` e' um mapa parcial das 5 capabilities (valores boolean)."
  [tipo override]
  (merge (capabilities-default tipo) (select-keys override capabilities)))

(defn escopo-numeracao
  "Escopo da numeracao canonica gapless: 'sessao:<sessao_legislativa_id>:<tipo>' — reseta por sessao
  legislativa e por tipo (§22.6 eixo A)."
  [sessao-legislativa-id tipo]
  (str "sessao:" sessao-legislativa-id ":" tipo))

(defn validar-tipo [tipo]
  (when-not (contains? tipos-sessao tipo)
    (throw (ex-info "tipo de sessao invalido" {:tipo tipo :validos tipos-sessao}))))

(defn validar-modalidade [modalidade]
  (when (and (some? modalidade) (not (contains? modalidades-sessao modalidade)))
    (throw (ex-info "modalidade de sessao invalida" {:modalidade modalidade}))))

;; ---------- §22.5 eixo E — politica da camada FINA (policy.check com o recurso carregado) ----------
;; A POLITICA declarativa mora no modulo DONO do recurso (ADR-0001). Aqui = fn pura (ator recurso -> bool)
;; consumida por kernel.autorizacao/check! no controller. Em F2 vira expressao da DSL avaliada pelo MESMO
;; motor (disciplina 5); esta fn e' o seam estavel.

(defn pode-ver-sessao?
  "Camada FINA de autorizacao p/ LER uma sessao, com o recurso ja carregado. V1 = defesa-em-profundidade:
  o tenant do ator tem de bater com o da sessao (a RLS ja escopa a query; isto barra um recurso de outro
  ente que escape por bug de query/repo — fail-closed). Politicas mais ricas (ex.: restricao de sessao
  'secreta') plugam aqui sem mudar a borda. Puro."
  [ator sessao]
  (= (:ente-id ator) (:ente-id sessao)))

(defn pode-ver-quorum-da-sessao?
  "Camada FINA de `GET /sessoes/:id/quorum` (a leitura MAGRA, Etapa 4a): mesma Casa E (transmissao publica OU
  papel 'secretario').

  EXISTE PORQUE A ETAPA 4a COPIOU METADE DA POLITICA DO TELAO. A rota magra justificou o seu nivel de authz
  pelo publico do painel ao vivo, mas o painel roda `tempo-real/logic/pode-assistir-plenario?` = mesma-casa?
  AND `plenario-publico?` — e so' a primeira metade atravessou. `plenario-publico?` existe exatamente para a
  sessao SECRETA (`tipos-sessao` da' `transmite-publica false`), e `tempo_real/canais.clj` grava a regra:
  'se transmite_publica=false, RECUSAR a subscricao (403) — nao filtrar por evento'. Sem esta fn, o estado de
  presenca de uma sessao secreta — que antes da Etapa 4a so' o papel 'secretario' alcancava, via `/chamada` —
  passava a ser alcancavel por qualquer vinculo ativo da Casa, e por polling reconstroi a serie temporal do
  quorum de um rito fechado (quando caiu por obstrucao, quando voltou por acordo).

  A clausula do 'secretario' nao afrouxa nada: ele JA lia esse mesmo estado, e nominalmente, pela rota da
  chamada. O gate e' de PUBLICO (quem so' assiste ao telao), nao de sessao.

  `(true? ...)` e nao `(not (false? ...))`: sessao sem o campo (fixture pobre, projecao futura incompleta)
  NEGA — fail-closed, a mesma disciplina de `plenario-publico?`. Pura; le' o snapshot de papeis do ator
  (§22.5 eixo D) sem importar o kernel — o acesso e' um `contains?` sobre `:papeis`, mesmo predicado de
  `kernel.autorizacao/tem-papel?`."
  [ator sessao]
  (and (pode-ver-sessao? ator sessao)
       (or (true? (:transmite-publica sessao))
           (contains? (:papeis ator) "secretario"))))

;; ---------- §22.6 eixo B — pauta (F4.2a) ----------
;; Os vocabularios espelham os CHECK da migration 0027.

(def fases-pauta
  "Fases da pauta como ATRIBUTO do item (descartado bloco-por-fase como entidade)."
  #{"expediente" "grande_expediente" "ordem_do_dia" "explicacoes_pessoais" "tribuna_livre_cidadao"})

(def tipos-item-pauta
  "Tipos de item com enum fechado + FK declarativa por tipo (descartado polimorfismo): so 'proposicao'
  carrega proposicao_id; os demais carregam texto_descricao."
  #{"proposicao" "leitura" "comunicado" "homenagem"})

(def tipos-alteracao-pauta
  "Tipos de alteracao intra-sessao (eixo B), modeladas como eventos append-only."
  #{"inclusao" "exclusao" "inversao" "retirada_pedido_autor"})

(def tipos-remocao-pauta
  "Subconjunto de alteracao que TIRA um item da pauta (remover-item!)."
  #{"exclusao" "retirada_pedido_autor"})

(defn item-requer-proposicao?
  "So o tipo 'proposicao' aponta a uma materia (proposicao_id); o resto usa texto_descricao."
  [tipo-item]
  (= "proposicao" tipo-item))

(defn validar-fase [fase]
  (when-not (contains? fases-pauta fase)
    (throw (ex-info "fase de pauta invalida" {:fase fase :validas fases-pauta}))))

(defn validar-tipo-item [tipo-item]
  (when-not (contains? tipos-item-pauta tipo-item)
    (throw (ex-info "tipo de item de pauta invalido" {:tipo-item tipo-item :validos tipos-item-pauta}))))

(defn validar-tipo-remocao [tipo]
  (when-not (contains? tipos-remocao-pauta tipo)
    (throw (ex-info "tipo de remocao de item invalido (so exclusao|retirada_pedido_autor)"
                    {:tipo tipo :validos tipos-remocao-pauta}))))

;; ---------- §22.6 eixo B — versionamento canonico da pauta (F4.2b) ----------
;; A camada viva (item/alteracao) muta; a VERSAO congela a pauta num instante (snapshot jsonb append-only)
;; = o que o portal do cidadao cita e a prova institucional. O vocabulario espelha o CHECK da migration 0028.

(def tipos-versao-pauta
  "Tipos de versao canonica: publicacao_inicial (1a publicacao da pauta), republicacao (republicada apos
  alteracao), execucao_final (a pauta efetivamente executada na sessao, p/ a ata)."
  #{"publicacao_inicial" "republicacao" "execucao_final"})

(defn validar-tipo-versao [tipo]
  (when-not (contains? tipos-versao-pauta tipo)
    (throw (ex-info "tipo de versao de pauta invalido" {:tipo tipo :validos tipos-versao-pauta}))))

(defn validar-republicacao
  "Republicacao exige justificativa nao-vazia (trilha de auditoria do porque republicou; o CHECK da mig 0028
  espelha). Demais tipos nao exigem."
  [tipo justificativa]
  (when (and (= "republicacao" tipo)
             (or (nil? justificativa) (str/blank? justificativa)))
    (throw (ex-info "republicacao exige justificativa" {:tipo tipo}))))

;; ---------- §22.6 eixo C — presenca e quorum (F4.3a) ----------
;; A presenca corrente nunca e' materializada: e' DERIVADA do ultimo evento por vereador ate um instante.
;; Os vocabularios espelham os CHECK da migration 0029.

(def tipos-evento-presenca
  "Eventos de presenca append-only (descartado: presenca binaria; intervalos explicitos)."
  #{"entrada" "saida" "retorno" "mudanca_modalidade"})

(def modalidades-presenca
  "Modalidade do vereador no instante do evento. V1 = Nivel 1: remoto e' marcado manualmente (sem integracao
  de videoconferencia), por isso nao ha enum 'videoconferencia' aqui nem em `fontes-presenca`."
  #{"plenario" "remoto"})

(def fontes-presenca
  "Fonte de captura do evento. As inferencias (vereador vota/usa tribuna sem check-in) viram evento concreto.
  'autoatendimento' (Onda C3) = o proprio vereador confirma a propria presenca pelo celular."
  #{"painel_eletronico" "manual_secretaria" "autoatendimento" "inferida_por_voto" "inferida_por_tribuna"})

(def tipos-presenca-positiva
  "Tipos cujo ULTIMO evento mantem o vereador PRESENTE; 'saida' e' o unico que tira."
  #{"entrada" "retorno" "mudanca_modalidade"})

(def precedencia-fonte
  "Precedencia em conflito de MESMO instante (§22.6 eixo C): manual_secretaria > painel_eletronico >
  autoatendimento > inferida_* (Onda C3). Usada como desempate ao escolher o ultimo evento por vereador (a
  consulta replica esta ordem em SQL, fonte_precedencia — migration 20260620000056)."
  {"manual_secretaria" 4 "painel_eletronico" 3 "autoatendimento" 2 "inferida_por_voto" 1 "inferida_por_tribuna" 1})

;; ---------- a ORDEM canonica do "ultimo evento por vereador" (UMA so, para todos os caminhos) ----------
;; Isto e' DADO (mapa/vetor HoneySQL), nao I/O: nenhuma conexao, nenhum efeito — o ns segue puro. Mora aqui
;; porque ha' DOIS consumidores em camadas que nao podem se importar (ADR-0001 §3-bis: `db/` nao e' importado
;; por `relacoes/`), e ambos ja' dependem deste ns:
;;   - `sessoes/relacoes/presenca` — o caminho que o MOTOR de votacao alcanca por nome (quorum da policy);
;;   - `sessoes/db/presenca`       — o caminho do agregado publicado no dashboard da Mesa.
;; Enquanto a ordem era transcrita a mao nos dois lugares, divergir era um `git blame` de distancia: a TELA
;; diria um quorum e a POLICY usaria outro na MESMA sessao. Uma fonte so' fecha a porta.

(def ordem-ultimo-evento
  "Ordem canonica do 'ultimo evento por vereador': mais recente primeiro, desempatando MESMO instante pela
  precedencia da fonte (manual>painel>autoatendimento>inferida, materializada em `fonte_precedencia` —
  generated stored, indexada) e por fim `id`. NAO leva `vereador_id`: quem particiona (DISTINCT ON) ou fixa
  o vereador (WHERE) o antepoe."
  [[:ocorrido_em :desc] [:fonte_precedencia :desc] [:id :desc]])

(def projecao-ultimo-evento-padrao
  "Projecao default da subquery: o que os agregadores de quorum precisam."
  [:vereador_id :tipo :modalidade])

(defn ultimos-eventos-por-vereador-q
  "Subquery (mapa HoneySQL puro): o ULTIMO evento por vereador na sessao ate' `:instante`, um por vereador
  (DISTINCT ON vereador na `ordem-ultimo-evento`).

  `:ente-id` e' OPCIONAL — e' o que permite os dois consumidores conviverem sem mudar comportamento: a camada
  de RELACAO nao carrega `ente` na assinatura (a Casa = a tx, FORCE RLS isola) e o omite; a camada `db/` o
  passa como defense-in-depth (ente_id em toda query, ver a docstring daquele ns). Presente ou ausente, a
  ORDEM e' a mesma — e' esse o ponto.

  `:projecao` default = `projecao-ultimo-evento-padrao`; quem filtra a query externa por outra coluna (ex.:
  `ente_id`) tem de pedi-la aqui, senao ela nao existe na subquery."
  [{:keys [sessao-id instante ente-id projecao]}]
  {:select-distinct-on (into [[:vereador_id]] (or projecao projecao-ultimo-evento-padrao))
   :from [:sessoes.presenca_evento]
   :where (into (if (some? ente-id) [:and [:= :ente_id ente-id]] [:and])
                [[:= :sessao_id sessao-id] [:<= :ocorrido_em instante]])
   :order-by (into [[:vereador_id :asc]] ordem-ultimo-evento)})

(defn ultimos-eventos-por-sessao-e-vereador-q
  "Subquery (mapa HoneySQL puro): a IRMA MULTI-SESSAO de `ultimos-eventos-por-vereador-q` — o ULTIMO evento
  por (sessao, vereador), para um LOTE de sessoes de UMA VEZ, cada sessao com o SEU PROPRIO instante de
  corte (I5 do brief da Etapa 6: cada sessao e' avaliada no seu `instante-de-avaliacao`; um instante GLOBAL
  do periodo fabricaria presenca — um evento gravado depois do fechamento de uma sessao mudaria a apuracao
  de OUTRA sessao que fechou depois).

  A FORMA: `CROSS JOIN LATERAL` **chamando `ultimos-eventos-por-vereador-q`** — a query singular, tal e
  qual, uma vez por linha da tabela VALUES `si(sessao_id, instante)`. Ou seja: o lote nao TRANSCREVE a
  consulta singular com um particionador a mais; ele a INVOCA. E' o I2 na sua forma mais forte — nao existe
  uma segunda redacao do `DISTINCT ON` nem da `ordem-ultimo-evento` para divergir da primeira.

  POR QUE NAO O JOIN PLANO (a forma commitada em f1ef7c4, trocada na revisao). O `DISTINCT ON (sessao_id,
  vereador_id)` exigia um `ORDER BY` global comecando por `sessao_id`; nenhum indice pode servi-lo, porque a
  ordem tem de sair do JOIN com uma tabela VALUES, e o `Values Scan` nao tem pathkey — o planner nao propaga
  a ordem do indice atraves do nested loop. Resultado MEDIDO nesta maquina (`oplenario-postgres-1`, PG 16,
  1.897.248 eventos / 400 sessoes / 21 vereadores / `work_mem` 4MB, papel `oplenario_app` com RLS, dado
  sintetico em tx com ROLLBACK):

    | forma            | plano                          | Sort                       | Execution |
    |------------------|--------------------------------|----------------------------|-----------|
    | JOIN plano (1a)  | Parallel Seq Scan + Hash Join  | external merge Disk 15.544 kB | 470,1 ms |
    | JOIN plano (2a)  | Nested Loop + Index Scan       | external merge Disk 43.728 kB | 1.525,2 ms |
    | LATERAL (1a)     | Nested Loop + Index Scan       | NENHUM (zero temp)         | 490,8 ms  |
    | LATERAL (2a)     | Nested Loop + Index Scan       | NENHUM (zero temp)         | 559,0 ms  |

  O ganho medido NAO e' de tempo — e' de ESTABILIDADE e de memoria: a forma antiga derrama dezenas de MB em
  disco por request e escolhe planos radicalmente diferentes para a MESMA query (470 ms num run, 1.525 ms no
  seguinte, sem nada mudar alem dos uuids sinteticos); a forma LATERAL nunca ordena e nunca derrama. Num
  processo com pool de 10 conexoes e SEM `statement_timeout` (carry de infra conhecido), plano instavel e'
  o que transforma uma leitura de relatorio em conexao presa durante a sessao ao vivo.

  Os CASTs explicitos da tabela VALUES (`[:cast sid :uuid]`, `[:cast inst :timestamptz]`) sao obrigatorios,
  nao cosmeticos: sem OID declarado o Postgres resolve as colunas da VALUES como `text` e a query nem
  PLANEJA (`operator does not exist: uuid = text`). Hoje isso funcionava por carona do type-mapping do
  driver; declarar o tipo aqui e' o que torna a subquery correta por si.

  `:sessoes-e-instantes` = colecao de pares `[sessao-id instante]`.
  `:ente-id` opcional, MESMO contrato do singular (defense-in-depth; a RLS ja isola por tenant) — repassado
  INTACTO a' subquery singular.
  `:projecao` = as colunas da subquery SINGULAR (sem `sessao_id`: ele vem do lado de fora, de `si`); default
  = `projecao-ultimo-evento-padrao`.

  SEM `ORDER BY` externo, de proposito: era ele o Sort. As linhas saem agrupadas por sessao (a ordem do
  `Values Scan`) e, dentro de cada sessao, por `vereador_id` (o `DISTINCT ON` da subquery) — que e'
  exatamente a mesma sequencia que o singular devolve para uma sessao. O consumidor
  (`db/presenca/presencas-correntes-das-sessoes`) faz `group-by :sessao-id` e nao depende de ordem global."
  [{:keys [sessoes-e-instantes ente-id projecao]}]
  {:select (into [:si.sessao_id] (map #(keyword (str "u." (name %)))) (or projecao projecao-ultimo-evento-padrao))
   :from [[{:values (mapv (fn [[sid inst]] [[:cast sid :uuid] [:cast inst :timestamptz]])
                          sessoes-e-instantes)}
           [[:si :sessao_id :instante]]]]
   :join [[[:lateral (ultimos-eventos-por-vereador-q
                      {:sessao-id :si.sessao_id :instante :si.instante
                       :ente-id ente-id :projecao projecao})]
           :u]
          true]})

(defn presente-por-tipo?
  "O vereador esta presente se o tipo do seu ultimo evento e' positivo (entrada/retorno/mudanca_modalidade)?"
  [tipo]
  (contains? tipos-presenca-positiva tipo))

(defn validar-tipo-evento
  "Fail-closed: lanca se `tipo` nao esta em tipos-evento-presenca (espelha o CHECK da mig 0029)."
  [tipo]
  (when-not (contains? tipos-evento-presenca tipo)
    (throw (ex-info "tipo de evento de presenca invalido" {:tipo tipo :validos tipos-evento-presenca}))))

(defn validar-modalidade-presenca
  "Fail-closed: lanca se `modalidade` nao e' plenario|remoto."
  [modalidade]
  (when-not (contains? modalidades-presenca modalidade)
    (throw (ex-info "modalidade de presenca invalida" {:modalidade modalidade :validas modalidades-presenca}))))

(defn validar-fonte
  "Fail-closed: lanca se `fonte` de captura nao e' conhecida (V1 sem videoconferencia)."
  [fonte]
  (when-not (contains? fontes-presenca fonte)
    (throw (ex-info "fonte de presenca invalida" {:fonte fonte :validas fontes-presenca}))))

;; justificativa de ausencia — ato administrativo apartado, state machine pequena.
(def estados-justificativa #{"pendente" "aprovada" "indeferida"})
(def estados-justificativa-terminais #{"aprovada" "indeferida"})

(def transicoes-justificativa
  "pendente -> aprovada|indeferida (ambos terminais). O CHECK da mig 0029 + o terminal-lock trigger espelham."
  {"pendente"   #{"aprovada" "indeferida"}
   "aprovada"   #{}
   "indeferida" #{}})

(defn transicao-justificativa-valida?
  "A transicao de->para da justificativa e' permitida? (`de`/`para` = estados; terminais nao saem). Puro."
  [de para]
  (contains? (get transicoes-justificativa de #{}) para))

(defn validar-estado-justificativa
  "Fail-closed: lanca se `estado` nao e' pendente|aprovada|indeferida (espelha o CHECK da mig 0029)."
  [estado]
  (when-not (contains? estados-justificativa estado)
    (throw (ex-info "estado de justificativa invalido" {:estado estado :validos estados-justificativa}))))

(defn mensagem-de-recusa-de-justificativa
  "PURO. A mensagem ACIONAVEL do 409 da porta da justificativa (Etapa 2 da chamada) — em portugues, dizendo o
  LIMITE violado e o que fazer. Fica aqui (nao no diplomat) pela mesma razao de
  `mensagem-de-recusa-de-presenca`: a razao da recusa e' regra de dominio, a borda so' repassa.

  NAO carrega dado de pessoa — em particular NUNCA o `motivo` da justificativa, que pode ser dado de saude
  (LGPD): a mensagem de erro tambem e' superficie de vazamento, e ela viaja para telas, logs de cliente e
  bug reports. So' estados e ids tecnicos."
  [motivo]
  (case motivo
    :sem-assento
    (str "este vereador nao compoe a Casa na data desta sessao. Confira o mandato em cadastros antes de "
         "lancar a justificativa — uma justificativa sem cadeira nao aparece na chamada.")

    :ja-existe
    (str "ja existe uma justificativa deste vereador nesta sessao. Abra a existente para acompanhar ou "
         "decidir; uma segunda justificativa apagaria a trilha do que foi alegado antes.")

    :lock-stale
    (str "esta justificativa foi decidida por outra pessoa enquanto voce olhava. Recarregue a lista e "
         "confira a decisao ja registrada.")

    :transicao-invalida
    (str "esta justificativa ja foi decidida e a decisao e' definitiva. Reverter e' um ato novo da Mesa, "
         "registrado em ata — nao uma correcao desta.")

    :inexistente
    "justificativa inexistente nesta Casa."

    (str "operacao recusada sobre a justificativa de ausencia (" (name motivo) ").")))

;; ---------- §22.6 eixo C — a CHAMADA: derivacao PURA do estado por vereador ----------
;; A chamada e' a leitura que o servidor projeta no telao e que a policy de quorum consulta. Ela cruza TRES
;; fontes que nunca se materializam juntas: o roster do cadastro (quem e' membro, e se esta licenciado), o
;; ULTIMO evento de presenca (o fato observado — `ultimos-eventos-por-vereador-q` acima e' quem o busca) e a
;; justificativa de ausencia (ato administrativo apartado). Aqui e' tudo puro: quem le' o banco e' outra camada.

(def estados-chamada
  "Os 6 estados possiveis de um vereador na chamada. `:ausente-justificativa-pendente` e' um estado PROPRIO —
  nao um sinonimo de `:ausente`. A Mesa ainda nao decidiu; publicar 'ausente' (que a Casa le' como
  injustificada) e' acusacao falsa contra o vereador, e e' o tipo de erro que so aparece em ata."
  #{:presente-plenario :presente-remoto :ausente :ausente-justificado
    :ausente-justificativa-pendente :licenciado})

;; NAO existe aqui um segundo `estados-*-presentes`. Existiu: `estados-chamada-presentes`, gemeo de
;; `estados-presentes` (abaixo, junto de `contar-quorum`) — mesmo conteudo, duas docstrings reivindicando ser
;; a fonte unica do NUMERADOR. A apuracao de assiduidade nasceu consumindo a copia, e no dia em que uma
;; terceira categoria positiva (presenca por videoconferencia) entrasse em `estados-presentes`, a chamada
;; mostraria o vereador presente nas 40 sessoes e o CSV publicaria `:comparecimentos 0` — sem NENHUM teste
;; vermelho. O numerador agora tem uma fonte so': `conta-no-numerador-do-quorum?`.

(def estados-mandato-cadastro
  "Vocabulario de `cadastros.mandato.estado` — ESPELHO do CHECK da migration 20260620000010-cadastros.
  `sessoes` NAO pode importar `cadastros` (import-lint §22.10: comunicacao so por HTTP/eventos); o roster
  chega por SEAM injetado no host ja' com este vocabulario CRU, e espelhar e' a unica forma disponivel.
  Espelhar nao e' REDIGITAR de memoria: um valor proximo-mas-errado (o classico `\"suplente\"` por
  `\"suplencia\"`) nao quebra nada — deixa a tela publicando zero em silencio por meses. Por isso a validacao
  e' fail-closed: estado desconhecido EXPLODE. Se `cadastros` acrescentar um estado, este set fica
  desatualizado e a chamada para de responder — que e' o comportamento desejado (alguem conserta hoje)."
  #{"vigente" "licenciado" "cassado" "renunciado" "falecido" "concluido"})

(def estado-mandato-licenciado
  "O unico estado de mandato que a chamada trata de forma especial (§22.6 eixo C). Os demais nao alteram a
  derivacao: cassado/renunciado/falecido/concluido descrevem mandato ENCERRADO — quem monta o roster ja' os
  filtra pela janela de vigencia, e se um escapar ele e' derivado como qualquer outro membro (o que a
  contagem revela, em vez de esconder)."
  "licenciado")

(defn validar-estado-mandato
  "Fail-closed: lanca se `estado` de mandato e' nao-nil e forasteiro ao vocabulario de `cadastros`. `nil` e'
  LEGITIMO e passa: o roster vem de LEFT JOIN LATERAL e devolve nil quando nenhum mandato cobre a data."
  [estado]
  (when (and (some? estado) (not (contains? estados-mandato-cadastro estado)))
    (throw (ex-info "estado de mandato desconhecido no roster da chamada"
                    {:estado estado :validos estados-mandato-cadastro}))))

(defn estado-de-presenca
  "PURO. Deriva o estado da chamada de UM vereador a partir das tres fontes. Devolve
  `{:estado <de estados-chamada> :inconsistencia-cadastro <boolean>}`.

  A PRECEDENCIA, escrita e nao inferida:
    1. licenciado (cadastro)            -> `:licenciado`
    2. ultimo evento POSITIVO            -> `:presente-plenario` | `:presente-remoto` (a modalidade decide)
    3. justificativa `aprovada`          -> `:ausente-justificado`
    4. justificativa `pendente`          -> `:ausente-justificativa-pendente`
    5. nada disso                        -> `:ausente`

  CASO ESPECIAL (o degrau 1 cede ao 2): licenciado COM evento positivo => o vereador esta PRESENTE e
  `:inconsistencia-cadastro` vem `true`. O fato observado vence o cadastro — ele entrou no plenario, esta
  la'. E a contradicao nao se esconde: numa Casa recem-migrada (licenca que ninguem encerrou no sistema
  antigo, reassuncao nao registrada) esse sinalizador e' exatamente o que o servidor precisa ver para ir
  consertar o cadastro. Silenciar o conflito escolhendo um dos lados e' o que produz um quorum errado
  defensavel-no-papel.

  Evento NEGATIVO (`saida`) nao contradiz licenca nenhuma — so o fato positivo contradiz.

  `ultimo-evento` = mapa com `:tipo`/`:modalidade` (nil se o vereador nao tem evento na sessao);
  `justificativa` = mapa com `:estado` (nil se nao ha). Todos os vocabularios sao validados fail-closed."
  [roster-linha ultimo-evento justificativa]
  (validar-estado-mandato (:estado-mandato roster-linha))
  (when (some? ultimo-evento)
    (validar-tipo-evento (:tipo ultimo-evento))
    (validar-modalidade-presenca (:modalidade ultimo-evento)))
  (when (some? justificativa)
    (validar-estado-justificativa (:estado justificativa)))
  (let [licenciado? (= estado-mandato-licenciado (:estado-mandato roster-linha))
        presente?   (and (some? ultimo-evento) (presente-por-tipo? (:tipo ultimo-evento)))
        estado      (cond
                      presente?  (if (= "remoto" (:modalidade ultimo-evento))
                                   :presente-remoto
                                   :presente-plenario)
                      licenciado? :licenciado
                      (= "aprovada" (:estado justificativa)) :ausente-justificado
                      (= "pendente" (:estado justificativa)) :ausente-justificativa-pendente
                      :else :ausente)]
    {:estado estado
     :inconsistencia-cadastro (boolean (and licenciado? presente?))}))

(defn derivar-linha-chamada
  "PURO. A linha da chamada de um vereador = a identidade do roster + o estado derivado. Corresponde ao model
  `sessoes.models.presenca/LinhaChamada`. A identidade vem CRUA do roster (nome, nome parlamentar, partido):
  `sessoes` nao inventa dado de `cadastros`, so o repassa. `:sem-assento` e' false aqui por definicao (a
  linha NASCEU do roster); o campo existe em toda linha, uniforme, para o mapa nunca ter forma variavel."
  [roster-linha ultimo-evento justificativa]
  (merge (select-keys roster-linha [:vereador-id :nome :nome-parlamentar :partido])
         {:sem-assento false}
         (estado-de-presenca roster-linha ultimo-evento justificativa)))

(defn derivar-linha-sem-assento
  "PURO. A linha de uma PRESENCA cujo vereador NAO tem assento no roster da data (§22.6 eixo C, revisao da
  Etapa 1). Existe por um motivo estrutural, nao cosmetico: o quorum que o MOTOR de votacao resolve por nome
  (`relacoes/presenca/presentes-plenario|remoto`) conta sobre `presenca_evento` SOZINHO — nenhum join a
  mandato, e a coluna `vereador_id` nao tem FK. Se a chamada cruzasse roster x eventos e DESCARTASSE o que
  sobra, a tela e a policy passariam a contar conjuntos diferentes na mesma votacao: o defeito que a fatia
  1a fechou uma camada abaixo, reaberto aqui.

  Entao a presenca orfa vira linha: derivada pela MESMA regra (o fato observado manda), marcada
  `:sem-assento true` e `:inconsistencia-cadastro true`. Ela CONTA no numerador (para casar com o motor) e
  NAO conta no denominador (`contar-quorum`) — ninguem ganha cadeira por ter um evento. A identidade vem
  nil: `sessoes` nao tem de onde inventar nome de quem `cadastros` nao conhece naquela data.

  Como acontece na pratica: mandato cuja janela nao cobre a data (suplente convocado para amanha, vereador
  cujo mandato encerrou ontem), acervo migrado com licenca que ninguem encerrou, ou simplesmente um id
  digitado errado pela secretaria — `registrar-presenca` nao valida mandato."
  [presenca justificativa]
  (-> (derivar-linha-chamada {:vereador-id (:vereador-id presenca) :nome nil :nome-parlamentar nil
                              :partido nil :estado-mandato nil}
                             presenca justificativa)
      (assoc :sem-assento true :inconsistencia-cadastro true)))

(def estados-presentes
  "Os estados de linha derivada que contam como PRESENTE no NUMERADOR do quorum. FONTE UNICA — nao ha' um
  segundo conjunto com este conteudo em lugar nenhum do ns (ver o comentario onde o gemeo
  `estados-chamada-presentes` foi morto). Quem consulta usa `conta-no-numerador-do-quorum?`, nunca o
  `contains?` cru: e' o predicado que os DOIS consumidores (quorum e assiduidade) compartilham."
  #{:presente-plenario :presente-remoto})

(defn conta-no-numerador-do-quorum?
  "PURO. Uma linha JA DERIVADA conta no NUMERADOR do quorum (`:presentes-total` de `contar-quorum`)?
  Simetrico de `conta-no-denominador-do-quorum?` e pela MESMA razao: a apuracao de assiduidade
  (`somar-linha-na-assiduidade`) precisa da decisao LINHA A LINHA ao longo de um periodo inteiro, e antes
  desta extracao ela tomava a decisao por um SEGUNDO conjunto de estados (`estados-chamada-presentes`), copia
  de `estados-presentes`. Duas copias do numerador nao produzem erro de tipo nem teste vermelho quando
  divergem — produzem um CSV oficial dizendo `:comparecimentos 0` para quem a tela mostra presente.

  A linha SEM ASSENTO conta aqui de proposito (e nao conta no denominador): e' o mesmo tratamento que
  `contar-quorum` documenta — o motor de votacao conta o evento observado, entao a tela tambem conta."
  [linha]
  (contains? estados-presentes (:estado linha)))

(defn conta-no-denominador-do-quorum?
  "PURO. Uma linha JA DERIVADA (`derivar-linha-chamada`/`derivar-linha-sem-assento`) conta no DENOMINADOR do
  quorum (`:membros-da-casa` de `contar-quorum`, abaixo) quando NAO e' `:licenciado` E TEM assento — a MESMA
  regra que `contar-quorum` aplicava inline, extraida (Etapa 6 fatia 2) para ser reusada LINHA A LINHA pela
  apuracao de assiduidade por vereador: ali a decisao precisa ser tomada uma linha de cada vez, ao longo de
  um periodo inteiro de sessoes, nao so' resumida no agregado de uma sessao. Extrair em vez de redigitar e'a
  mesma disciplina de `ordem-ultimo-evento`/`mandato-vigente-lateral` (I1/I3 do brief): uma so' fonte para
  'o que conta na Casa', nunca duas contagens que podem divergir."
  [linha]
  (not (or (= :licenciado (:estado linha)) (:sem-assento linha))))

(defn contar-quorum
  "PURO. Contagem de quorum sobre as linhas JA derivadas (`derivar-linha-chamada` /
  `derivar-linha-sem-assento`) — nunca sobre eventos crus, para que a tela e a policy contem o mesmo
  conjunto.

  `:membros-da-casa` e' o DENOMINADOR e exclui DOIS conjuntos:
    - os licenciados: durante a licenca o vereador nao compoe a Casa para efeito de quorum (quem compoe e' o
      suplente, que entra no roster com mandato proprio). Contar o licenciado inflaria o denominador e faria
      uma sessao legitima parecer sem quorum.
    - as linhas SEM ASSENTO: um evento de presenca nao cria cadeira. Elas contam no numerador (o motor as
      conta) mas nao no denominador — e' por isso que `presentes` PODE passar de `membros-da-casa`. Essa
      desigualdade e' o sintoma visivel de cadastro furado, e e' melhor que ela apareca do que ser
      normalizada em silencio.

  O licenciado-com-evento-positivo (a inconsistencia acima) NAO fica de fora: ele foi derivado como
  presente, entao conta nos dois lados — numerador e denominador. E' o unico tratamento coerente: quem esta
  no plenario esta na Casa.

  `:presencas-fora-do-roster` e' fail-loud: publica QUANTAS linhas sem assento entraram nesta chamada, para
  a Mesa nao precisar somar de cabeca para descobrir que a tela e a policy divergem do cadastro.

  `:presentes-total` e' o NUMERADOR pronto, e existe por uma razao de corretude que a revisao adversarial
  desta branch levantou: sem ele, o cliente somava `presentes-plenario + presentes-remoto` para pintar o
  telao — uma SEGUNDA aritmetica do quorum, no lugar mais distante possivel da regra. No dia em que
  `estados-presentes` ganhar uma terceira categoria positiva, o servidor passa a contar N e um cliente que
  soma dois campos subconta em silencio, sem erro de tipo e sem teste vermelho. A soma mora aqui, uma vez."
  [linhas]
  {:presentes-plenario (count (filter #(= :presente-plenario (:estado %)) linhas))
   :presentes-remoto   (count (filter #(= :presente-remoto (:estado %)) linhas))
   :presentes-total    (count (filter conta-no-numerador-do-quorum? linhas))
   :membros-da-casa    (count (filter conta-no-denominador-do-quorum? linhas))
   :presencas-fora-do-roster (count (filter :sem-assento linhas))})

(defn derivar-linhas-da-chamada
  "PURO. A UNIAO que a chamada publica, derivada de uma vez so': as linhas do ROSTER (cada uma cruzada com o
  seu ultimo evento e a sua justificativa) MAIS as linhas SEM ASSENTO (presencas cujo vereador o roster da
  data nao contem), estas no fim e ordenadas por id.

  Devolve `[{:linha <derivada> :roster-linha <ou nil> :evento <ultimo evento / a propria presenca, ou nil>
  :justificativa <ou nil>} ...]` — a linha derivada e as TRES FONTES que a produziram. O controller precisa
  das fontes para acrescentar o que a derivacao nao carrega (`cargo-mesa`, `desde`, `fonte`,
  `registrado-em`); quem so' quer contar quorum usa `(map :linha ...)`.

  EXISTE POR CORRETUDE, nao por organizacao. Antes desta funcao, a uniao era montada no controller (para a
  LEITURA) e o denominador do ATO de chamada conduzida saia de uma segunda conta, roster-only. As duas
  DIVERGIAM no caso que `estado-de-presenca` documenta como real: o licenciado COM evento positivo e'
  derivado PRESENTE e entra no denominador ('quem esta no plenario esta na Casa'), mas a conta roster-only o
  via `:licenciado` e o tirava. A mesma sessao passava a ter duas aritmeticas da composicao da Casa — uma na
  tela, outra congelada num registro APPEND-ONLY que ninguem pode corrigir. E' o defeito que a Etapa 1
  gastou uma revisao inteira matando, reaberto pela porta do ato."
  [roster presencas justificativas]
  (let [presenca-por-ver      (into {} (map (juxt :vereador-id identity)) presencas)
        justificativa-por-ver (into {} (map (juxt :vereador-id identity)) justificativas)
        com-assento           (into #{} (map :vereador-id) roster)
        do-roster (mapv (fn [rl]
                          (let [ev (get presenca-por-ver (:vereador-id rl))
                                j  (get justificativa-por-ver (:vereador-id rl))]
                            {:linha (derivar-linha-chamada rl ev j)
                             :roster-linha rl :evento ev :justificativa j}))
                        roster)
        sem-assento (->> presencas
                         (remove #(contains? com-assento (:vereador-id %)))
                         (sort-by #(str (:vereador-id %)))
                         (mapv (fn [p]
                                 (let [j (get justificativa-por-ver (:vereador-id p))]
                                   {:linha (derivar-linha-sem-assento p j)
                                    :roster-linha nil :evento p :justificativa j}))))]
    (into do-roster sem-assento)))

(defn membros-da-casa-da-chamada
  "PURO. O DENOMINADOR do quorum (`:membros-da-casa`) sobre a MESMA uniao que a leitura publica — o unico
  numero que pode ser congelado no ato de chamada conduzida sem mentir. Substitui a antiga
  `membros-da-casa-do-roster`, que passava `nil` como evento/justificativa e por isso divergia (ver
  `derivar-linhas-da-chamada`)."
  [roster presencas justificativas]
  (:membros-da-casa (contar-quorum (mapv :linha (derivar-linhas-da-chamada roster presencas justificativas)))))

;; ---------- §22.6 eixo C — o INSTANTE em que a chamada avalia a presenca ----------
;; PURO e aqui (nao no controller) por uma razao de corretude, nao de organizacao: o instante tem de ser
;; derivado da MESMA leitura da sessao que a leitura da presenca usa — quem le' a sessao e' o Repo, dentro
;; da tx. Calcular no controller a partir de uma leitura ANTERIOR deixa a janela em que a Mesa encerra a
;; sessao no meio do request e a chamada avalia 'agora' uma sessao que ja fechou.

;; ---------- Faixa A / A.6: a ATA ----------

(def origens-redacao-ata
  "O discriminador da ata publicada (§22.6): redigida pela Casa, ou partida de um rascunho da IA revisado por uma pessoa."
  #{"redigida_externamente" "gerada_automaticamente"})

(def estados-com-ata
  "A ata e' o registro da sessao que ACONTECEU e ACABOU: encerrada, ou arquivada (acervo historico)."
  #{"encerrada" "arquivada"})

(def teto-texto-ata 200000)

(defn pode-ter-ata?
  "PURO: a sessao gera ata regimental (capability — solene e especial, por exemplo, nao geram) e ja' acabou."
  [sessao]
  (boolean (and (:gera-ata-regimental sessao) (contains? estados-com-ata (:estado sessao)))))

;; ---------- Faixa A / A.6b: o RASCUNHO da ata pela IA ----------

(def minutos-de-rascunho-em-curso
  "Janela em que um pedido ainda sem resposta da IA conta como EM CURSO (outro pedido e' recusado). Passada a janela,
  a secretaria pode pedir de novo — a IA pode ter perdido o pedido; a resposta tardia do primeiro continua valendo."
  30)

(defn sessao-vai-para-ia?
  "Sessao secreta nunca vai para a IA (o sigilo e' da sessao inteira) — o mesmo corte da fronteira (ADR-0008)."
  [sessao]
  (not= "secreta" (:tipo-sessao sessao)))

(defn pode-pedir-rascunho?
  "PURO: sem pedido, pedido ja' respondido (pronto/falhou) ou pedido em aberto ha' mais que a janela."
  [ultimo ^java.time.Instant agora]
  (or (nil? ultimo)
      (not= "solicitado" (:situacao ultimo))
      (.isBefore ^java.time.Instant (:solicitado-em ultimo)
                 (.minusSeconds agora (* 60 minutos-de-rascunho-em-curso)))))

(def estados-sem-gravacao
  "Estados de sessao que NAO recebem gravacao (Faixa A / A.2). So' 'nao_realizada': a sessao que nao aconteceu nao
  tem registro de audio. 'encerrada' e 'arquivada' RECEBEM — a fonte primaria da V1 e' a gravacao local enviada
  DEPOIS da sessao (§22.3.4), e a importacao de audio historico vincula a sessoes arquivadas. O vinculo da
  gravacao nao e' escrita de conducao (o gate `estados-sessao-fechada` continua valendo para estas)."
  #{"nao_realizada"})

(def ^:private ^java.time.Duration folga-sugestao-gravacao
  "Folga em torno da janela da sessao para casar uma gravacao (o OBS costuma comecar antes da abertura e parar
  depois do encerramento)."
  (java.time.Duration/ofHours 2))

(def ^:private ^java.time.Duration duracao-presumida-sessao
  "Janela presumida quando a sessao nao tem encerrada-em (ainda aberta, ou so' agendada)."
  (java.time.Duration/ofHours 6))

(defn sugerir-sessao-da-gravacao
  "PURO (Faixa A / A.2): a sessao mais provavel de uma gravacao recebida sem vinculo, pelo HORARIO. Janela de uma
  sessao = [inicio - 2h, fim + 2h], com inicio = aberta-em (ou agendada-para) e fim = encerrada-em (ou inicio +
  6h). Entre as sessoes cuja janela contem o `iniciou-em` da gravacao, vence a de inicio mais proximo. Sessoes em
  `estados-sem-gravacao` nunca sao sugeridas. So' SUGERE: quem vincula e' a secretaria. nil = nenhuma casa."
  [iniciou-em sessoes]
  (let [inst (fn [x] (cond (instance? java.time.Instant x) x
                           (instance? java.time.OffsetDateTime x) (.toInstant ^java.time.OffsetDateTime x)
                           (instance? java.util.Date x) (.toInstant ^java.util.Date x)
                           (string? x) (java.time.Instant/parse x)
                           :else nil))
        t (inst iniciou-em)
        candidatas
        (for [s sessoes
              :when (not (contains? estados-sem-gravacao (:estado s)))
              :let [ini (inst (or (:aberta-em s) (:agendada-para s)))]
              :when (and t ini)
              :let [fim (or (inst (:encerrada-em s)) (.plus ^java.time.Instant ini duracao-presumida-sessao))
                    de  (.minus ^java.time.Instant ini folga-sugestao-gravacao)
                    ate (.plus ^java.time.Instant fim folga-sugestao-gravacao)]
              :when (and (not (.isBefore ^java.time.Instant t de)) (not (.isAfter ^java.time.Instant t ate)))]
          [(Math/abs (.toMillis (java.time.Duration/between ini t))) (str (:id s)) s])]
    (some-> (sort-by (juxt first second) candidatas) first peek)))

(def estados-sessao-fechada
  "Estados em que a sessao JA fechou — a chamada tem de congelar no instante em que ela fechou (um evento
  inferido/registrado DEPOIS nao pode mudar uma chamada que ja foi para a ata). O CHECK
  `sessao_encerrada_em_obrigatoria` da mig 0026 cobria so' 'encerrada'/'nao_realizada'; a migration 0071
  (revisao da Etapa 1) estendeu-o a 'arquivada' — antes disso o banco aceitava uma linha arquivada SEM
  carimbo de encerramento, e a derivacao abaixo e' o cinto de seguranca que sobrou dela."
  #{"encerrada" "nao_realizada" "arquivada"})

;; ---------- GET /sessoes — a LISTAGEM GERAL (ledger de prontidao #16, MATA) ----------
;; A home do vereador so tinha POST /sessoes (agendar) e GET /sessoes/:id (uma so') — sem listagem, o
;; frontend chamava sempre com `sessoes=[]` e "nao sei" virava "nao ha": a mesma sessao ABERTA (orador na
;; tribuna) e AGENDADA que `/paineis/mesa` mostrava corretamente sumia na home. Esta secao e' a ORDENACAO
;; PURA que a leitura em lote usa — ao contrario da assiduidade, aqui NAO ha' `WHERE` de periodo (a
;; listagem e' "todas as sessoes do ente", nao um recorte), entao o teto de linhas e' o UNICO guard-rail.

(def teto-de-sessoes-da-listagem-geral
  "Teto de linhas de GET /sessoes — SEM filtro de periodo (a listagem geral, ao contrario da apuracao de
  assiduidade, nao recebe `de`/`ate`), entao o crescimento e' o de TODA a vida da Casa, nao de um recorte.
  500 cobre com folga o pior caso realista de MUITAS legislaturas (a Casa da demo tem 3; uma Casa real
  ativa por decadas fica na casa das centenas — ver o brief da fatia). Fail-closed (lanca, nunca pagina
  truncada em silencio — mesma disciplina de `teto-de-sessoes-do-periodo-de-assiduidade`): silenciar aqui
  reabriria, por outro mecanismo, o MESMO defeito que esta rota existe para fechar — a sessao aberta
  'desaparecendo' de uma pagina cortada sem aviso nenhum."
  500)

(def ^:private grupo-listagem-por-estado
  "O GRUPO de prioridade de cada estado na listagem — 0 = a Casa esta reunida AGORA ('aberta' e
  'suspensa': um recesso de plenario NAO fecha a sessao, entao quem abre a home durante um recesso ainda
  tem 'uma sessao agora'), 1 = agendada (o futuro), 2 = os tres estados de `estados-sessao-fechada` (o
  passado). Reusa a particao ja fechada de `estados-sessao-fechada` em vez de inventar uma nova — so'
  'aberta'/'suspensa' precisavam de um grupo proprio, que nenhuma constante existente ja nomeava."
  (merge {"aberta" 0 "suspensa" 0 "agendada" 1} (zipmap estados-sessao-fechada (repeat 2))))

(defn- epoch-ms-ou-fim
  "PURO. `Instant` -> epoch-millis (long); nil -> `Long/MAX_VALUE` (sentinela 'fim do grupo' — nao ha' como
  saber se um marco ausente e' 'recente' ou 'antigo', entao ele sempre perde para qualquer data real)."
  [instante]
  (if instante (.toEpochMilli ^Instant instante) Long/MAX_VALUE))

(defn chave-ordenacao-listagem-geral
  "PURA, FAIL-SAFE (nunca lanca — quem barra estado invalido e' a ESCRITA, nao esta leitura). A chave de
  ordenacao de GET /sessoes: `[grupo valor-no-grupo id]` — `sort-by` com esta chave (via `compare`, que
  compara vetores Clojure elemento a elemento) da' a ordem certa numa PASSADA so, sem comparator
  customizado.

  - Grupo 0 (aberta/suspensa, 'agora'): `aberta-em` CRESCENTE (a mais antiga ainda aberta primeiro —
    normalmente ha' so' uma sessao viva por vez).
  - Grupo 1 (agendada, 'futuro'): `agendada-para` CRESCENTE (a mais proxima primeiro).
  - Grupo 2 (fechada, 'passado'): `COALESCE(encerrada-em, agendada-para, aberta-em)` DECRESCENTE — a mais
    recente primeiro. Decrescente dentro de uma chave que so' sabe crescer vira o valor NEGADO (ASC de -x
    == DESC de x); a mesma ordem de fallback de `marcos-de-data-de-referencia`, com `encerrada-em` na
    FRENTE (o marco mais especifico do estado fechado, que aquela constante deliberadamente evita para nao
    criar uma quarta variante da regra — aqui nao ha' esse risco: esta ordenacao nao alimenta apuracao
    nenhuma, so' a exibicao).
  - Marco ausente (agendada sem data, fechada sem NENHUM marco — ambos [GAP] conhecidos, nao dado
    corrompido): vai para o FIM do proprio grupo via `epoch-ms-ou-fim`, nunca lanca.
  - Estado fora do vocabulario (defensivo — `estados-sessao` e' fechado por CHECK, isto nunca deveria ser
    alcancavel pela API real): grupo 3, sempre por ultimo.

  `id` (string, ordem lexica de UUID) e' o ULTIMO desempate — sem ele, duas sessoes com o MESMO carimbo no
  mesmo grupo dancariam de posicao a cada request."
  [{:keys [estado aberta-em agendada-para encerrada-em id]}]
  (let [grupo (get grupo-listagem-por-estado estado 3)]
    (case (int grupo)
      0 [0 (epoch-ms-ou-fim aberta-em) (str id)]
      1 [1 (epoch-ms-ou-fim agendada-para) (str id)]
      2 (let [marco (or encerrada-em agendada-para aberta-em)]
          [2 (if marco (- (.toEpochMilli ^Instant marco)) Long/MAX_VALUE) (str id)])
      [3 0 (str id)])))

(defn instante-de-avaliacao
  "PURO. O INSTANTE em que a presenca corrente e' avaliada: `agora` (o relogio ja lido, injetado) enquanto a
  sessao esta viva (agendada/aberta/suspensa — a chamada de uma sessao ao vivo e' sempre 'agora');
  `encerrada-em` (congelado) quando ja fechou.

  FAIL-CLOSED: sessao fechada sem `encerrada-em` LANCA. Um nil aqui viraria `ocorrido_em <= NULL` na
  consulta -> zero linhas -> uma chamada FABRICADA (a Casa inteira ausente, quorum zero) servida como
  leitura legitima e publicada em ata. Melhor a rota cair do que a ata mentir."
  [sessao agora]
  (if (contains? estados-sessao-fechada (:estado sessao))
    (or (:encerrada-em sessao)
        (throw (ex-info "sessao fechada sem encerrada-em: sem instante de avaliacao p/ a chamada"
                        {:tipo :servidor/erro :sessao-id (:id sessao) :estado (:estado sessao)})))
    agora))

;; ---------- §22.6 eixo C — a DATA DE REFERENCIA (uma REGRA, tres consumidores) ----------
;; A data civil que resolve QUEM compoe a Casa numa sessao estava REDIGITADA em tres lugares: a regra no
;; controller (privada, e por isso inalcancavel para quem quisesse reusa-la), o `COALESCE` do filtro SQL da
;; apuracao e o rotulo em Clojure da mesma leitura. Redigitar e' o defeito de primeira classe deste repo
;; (I2/I3), e o cenario nao e' teorico: sessao agendada para 10/03, adiada, aberta em 17/03 — inverter o
;; COALESCE faz a sessao ser FILTRADA por 10/03 e ROTULADA 17/03, com a suite inteira VERDE (nenhuma fixture
;; tinha `aberta_em` e `agendada_para` em dias civis diferentes). Agora a ordem mora numa constante so', e o
;; fragmento SQL e' DERIVADO dela — inverter a regra inverte os dois lados juntos, por construcao.

(def marcos-de-data-de-referencia
  "A ORDEM CANONICA dos marcos que resolvem a data de referencia de uma sessao: `aberta-em` primeiro (a Casa
  que efetivamente se reuniu), `agendada-para` como fallback (a Casa PREVISTA, sessao que nunca abriu).
  NUNCA `encerrada-em`: acrescentar um terceiro nivel consertaria o denominador da apuracao para a sessao
  agendada-sem-data que virou nao_realizada, mas criaria uma QUARTA variante da regra, divergente da que a
  rota `/sessoes/:id/chamada` aplica — que e' exatamente o drift que esta constante existe para fechar."
  [:aberta-em :agendada-para])

(def marcos-de-data-de-referencia-sql
  "As MESMAS colunas de `marcos-de-data-de-referencia`, na MESMA ordem, em snake_case — DERIVADAS, nunca
  redigitadas (`db/sessao` monta `(into [:coalesce] ...)` com isto)."
  (mapv #(keyword (str/replace (name %) "-" "_")) marcos-de-data-de-referencia))

(defn data-de-referencia-da-sessao
  "PURO. A DATA CIVIL (LocalDate, fuso `tempo/zona-civil-padrao`) que resolve QUEM compoe a Casa nesta
  sessao — o primeiro marco NAO-NIL de `marcos-de-data-de-referencia`. NUNCA `LocalDate/now`: reabrir a
  chamada de uma sessao do mes passado mostraria a composicao de HOJE, nao a de entao.

  Sessao sem NENHUM dos marcos e' um caminho NORMAL da API, nao dado corrompido: `wire/in/AgendarSessao`
  declara `agendada-para` OPCIONAL e a coluna e' nullable (mig 0026), e `agendada -> nao_realizada` nao
  exige data. Por isso o erro e' de CONFLITO DE ESTADO com mensagem acionavel (`:conflito/sessao-sem-data`
  -> 409 no diplomat), e nao um `:servidor/erro` que a borda traduziria em 500 'erro interno': a secretaria
  que agendou sem marcar a data precisa saber que e' isso que falta. O que continua proibido e' INVENTAR uma
  data — uma composicao adivinhada vai parar em ata."
  ^LocalDate [sessao]
  (if-let [instante (some #(get sessao %) marcos-de-data-de-referencia)]
    (tempo/hoje-de instante tempo/zona-civil-padrao)
    (throw (ex-info "sessao sem data marcada: sem data de referencia p/ a chamada"
                    {:tipo :conflito/sessao-sem-data :sessao-id (:id sessao)}))))

;; ---------- §22.6 eixo C — a SERIE da FOLHA (Etapa 5 fatia 1) ----------
;; A CHAMADA mostra o ULTIMO evento por vereador; a FOLHA mostra a SERIE inteira dentro da janela
;; ([piso-da-janela-de-presenca, instante-de-avaliacao] acima) — "entrou 14h03, saiu 15h10, retornou 15h40".
;; A fonte crua vem de `db/presenca/serie-de-eventos-da-sessao`; agrupar por vereador e' a UNICA
;; transformacao PURA que falta para o gerador da folha consumir.

(defn agrupar-serie-por-vereador
  "PURO. Agrupa a serie CRUA de eventos (ja' ordenada por vereador_id,ocorrido_em asc —
  `db/presenca/serie-de-eventos-da-sessao`) por vereador, preservando a ordem cronologica DENTRO de cada
  grupo (`group-by` preserva a ordem de insercao). Devolve {vereador-id -> [eventos ordenados]}; um
  vereador sem nenhum evento na janela simplesmente NAO aparece como chave — quem itera por cima do roster
  trata a ausencia como lista vazia, o mesmo tratamento que `presenca-por-ver` da' em
  `derivar-linhas-da-chamada`."
  [eventos]
  (update-vals (group-by :vereador-id eventos) vec))

;; ---------- §22.6 eixo C — o GATE de ESCRITA de presenca (Etapa 2 da chamada) ----------
;; A leitura acima congela o instante de uma sessao que fechou. Isso protege a CHAMADA, nao o BANCO: sem o
;; gate abaixo, o sistema aceitava gravar `presenca_evento` numa sessao ja encerrada, e como o quorum e'
;; DERIVADO do ultimo evento por vereador, o quorum de uma votacao JA REALIZADA mudava depois do fato. A ata
;; passava a divergir do banco sem trilha que as reconciliasse.

(def estados-sessao-aceita-presenca
  "ALLOWLIST dos estados em que uma escrita de presenca e' aceita — o complemento exato de
  `estados-sessao-fechada` sobre `estados-sessao` (a particao e' pinada por teste).

  E' ALLOWLIST, e nao `(not (contains? estados-sessao-fechada estado))`, por fail-closed: um estado NOVO no
  vocabulario (ou uma string corrompida/nil vinda de uma linha que nao devia existir) cai na RECUSA sozinho.
  A forma por complemento aceitaria o desconhecido, que e' exatamente o caso em que ninguem pensou.

  `agendada` ENTRA: a chamada que apura quorum PRECEDE a abertura da sessao — recusa-la deixaria a Mesa sem
  como provar que havia quorum para abrir."
  #{"agendada" "aberta" "suspensa"})

(defn aceita-registro-de-presenca?
  "PURO. O `estado` da sessao aceita uma escrita de presenca? (fail-closed: desconhecido/nil -> false)."
  [estado]
  (contains? estados-sessao-aceita-presenca estado))

(def ^Duration tolerancia-de-relogio
  "A folga do LIMITE SUPERIOR da janela (`instante > agora`). Existe porque a desigualdade compara relogios
  de MAQUINAS DIFERENTES: `ocorrido-em` vem do browser da secretaria, `agora` do relogio da JVM da aplicacao
  e `aberta_em`/`encerrada_em` do `now()` do Postgres. Uma estacao de trabalho adiantada em segundos —
  situacao corriqueira em camara pequena sem sincronismo de dominio — fazia TODA linha da chamada ao vivo
  voltar 409 `:instante-no-futuro`, com uma mensagem citando 'o relogio do servidor', que nao e' o relogio
  que o operador ve. Cinco minutos e' folga de skew, nao licenca para declarar fato futuro: o proposito do
  limite (recusar o que ainda nao aconteceu) sobrevive, e o ramo continua alcancavel (teste R3)."
  ^Duration (Duration/ofMinutes 5))

(defn piso-da-janela-de-presenca
  "PURO. O LIMITE INFERIOR da janela: o inicio do DIA CIVIL da sessao (fuso `tempo/zona-civil-padrao`),
  derivado do MESMO marco que resolve a composicao da Casa (`aberta-em` se a sessao ja abriu, senao
  `agendada-para` — a regra de `controllers/data-de-referencia`). `nil` quando a sessao nao tem nenhum dos
  dois marcos: sem data nao ha' dia civil a partir do qual medir, e inventar um piso seria pior.

  POR QUE NAO `aberta_em` (a forma anterior, corrigida na revisao): a chamada de uma camara e' conduzida COM
  a sessao ja aberta, e as horas que ela registra sao as de CHEGADA — anteriores ao martelo, por definicao.
  Com o piso em `aberta_em`, a hora real de quem chegou as 13:48 numa sessao aberta as 14:00 era
  IRREGISTRAVEL, e no `POST /presenca/lote` a primeira linha reprovada recusava a chamada inteira. Como nao
  existe evento de RETIFICACAO, a unica saida do secretario no plenario era redigitar a hora do martelo em
  todos — falsificar o fato observado para caber num gate que existia para impedir falsificacao.

  O dia civil preserva o invariante que importa (o fato tem de ser DAQUELA sessao, nao de outro dia) e vale
  TAMBEM para `agendada`, que antes era o unico estado sem piso nenhum — e e' justamente o estado em que a
  chamada de quorum acontece."
  ^Instant [sessao]
  (when-let [^Instant marco (or (:aberta-em sessao) (:agendada-para sessao))]
    (-> (tempo/hoje-de marco ^ZoneId tempo/zona-civil-padrao)
        (.atStartOfDay ^ZoneId tempo/zona-civil-padrao)
        .toInstant)))

(def motivos-do-gate-de-presenca
  "O VOCABULARIO fechado que `motivo-recusa-de-presenca` produz — a fonte unica para as DUAS tabelas de
  mensagem (`mensagem-de-recusa-de-presenca` e `mensagem-de-recusa-de-chamada`), que sao `case` sobre ele e
  degradariam em silencio num fallback generico se um motivo novo entrasse so' no `cond`. O teste
  `r5-todo-motivo-do-gate-tem-mensagem-propria-nas-duas-bordas` exige que todo membro deste set produza
  mensagem distinta do fallback nas duas; `r5-o-set-e-exatamente-o-que-o-gate-produz` fecha a outra direcao.
  Mesmo padrao de `vocabulario-de-estado-e-particionado`."
  #{:estado-nao-aceita-presenca
    :instante-no-futuro
    :instante-fora-do-dia-da-sessao
    :instante-apos-o-encerramento})

(defn motivo-recusa-de-presenca
  "PURO. `nil` = pode gravar. Senao, a RAZAO da recusa (keyword de `motivos-do-gate-de-presenca`), na ordem
  em que importam:

    :estado-nao-aceita-presenca     a sessao ja fechou (ou esta num estado que ninguem classificou)
    :instante-no-futuro             o fato declarado ainda nao aconteceu (alem da `tolerancia-de-relogio`)
    :instante-fora-do-dia-da-sessao o fato e' de OUTRO dia civil que nao o da sessao
    :instante-apos-o-encerramento   o fato e' posterior a `encerrada_em`

  RECUSA, nunca CLAMP. Grudar a hora declarada no limite da janela produz um registro que PARECE bom e
  mente sobre quando o fato ocorreu — pior que a recusa, porque o operador nao fica sabendo.

  O ramo `:instante-apos-o-encerramento` e' segunda tranca: o CHECK `sessao_encerrada_em_exige_estado` (mig
  0026) impede uma sessao VIVA de ter `encerrada_em`, logo ele e' inalcancavel hoje pelo banco. Fica para o
  dia em que a allowlist crescer e a janela virar o unico limite.

  `instante` e `agora` sao `java.time.Instant` (o data layer devolve timestamptz como Instant,
  kernel/db-tipos) e sao comparados por `compare` — Comparable, sem interop e sem reflexao. Ambos sao
  PRE-CONDICAO nao-nil: quem chama garante (a ausencia e' bug de servidor, nao conflito do usuario)."
  [sessao ^Instant instante ^Instant agora]
  (cond
    (not (aceita-registro-de-presenca? (:estado sessao)))              :estado-nao-aceita-presenca
    (pos? (compare instante (.plus agora tolerancia-de-relogio)))      :instante-no-futuro
    (when-let [piso (piso-da-janela-de-presenca sessao)]
      (neg? (compare instante piso)))                                  :instante-fora-do-dia-da-sessao
    (and (:encerrada-em sessao)
         (pos? (compare instante (:encerrada-em sessao))))             :instante-apos-o-encerramento
    :else nil))

(defn mensagem-de-recusa-de-presenca
  "PURO. A mensagem ACIONAVEL que vai no corpo do 409 — em portugues, dizendo o LIMITE violado (nao so' que
  houve violacao) e o que fazer. Fica aqui, e nao no diplomat, porque a razao da recusa e' regra de dominio:
  a borda so' repassa. Nao carrega dado de pessoa (so' estado e instantes da sessao), entao e' seguro
  devolver ao cliente.

  Cobre `motivos-do-gate-de-presenca` MAIS `:sem-assento`, que nao sai do gate puro (decidir se o vereador
  compoe a Casa exige o roster, que so' o controller alcanca pelo seam) mas cuja mensagem e' regra de
  dominio igual as outras — deixa-la na borda espalharia a redacao do 409 por dois lugares."
  [motivo sessao agora]
  (case motivo
    :estado-nao-aceita-presenca
    (str "a sessao esta '" (:estado sessao) "' e nao aceita mais registro de presenca. "
         "So' se registra presenca com a sessao agendada, aberta ou suspensa — mudar a presenca agora "
         "alteraria o quorum de votacoes ja realizadas. Corrija pela ata.")

    :instante-no-futuro
    (str "o instante informado e' posterior ao relogio do servidor (" agora ") alem da tolerancia de "
         (.toMinutes tolerancia-de-relogio) " min. Registre o fato depois que ele ocorrer — e confira o "
         "relogio desta estacao de trabalho, que pode estar adiantado.")

    :instante-fora-do-dia-da-sessao
    (str "o instante informado nao cai no dia desta sessao (a partir de " (piso-da-janela-de-presenca sessao)
         "). Presenca de outro dia pertence a outra sessao — confira a data digitada.")

    :instante-apos-o-encerramento
    (str "o instante informado e' posterior ao encerramento da sessao (" (:encerrada-em sessao) ").")

    :sem-assento
    (str "este vereador nao compoe a Casa na data desta sessao. Um evento de presenca nao cria cadeira: "
         "a linha entraria no quorum que o motor de votacao conta e no read-model publico sem que a "
         "chamada saiba de quem e'. Confira o id enviado, ou o mandato em cadastros.")

    (str "registro de presenca recusado para esta sessao (" (name motivo) ").")))

;; ---------- §22.6 eixo C — o LOTE de registro de presenca (Etapa 2c da chamada) ----------
;; A chamada de uma camara municipal e' UM ato de dezenas de nomes em minutos, numa rede real que cai no
;; meio — N POSTs sequenciais e nao-atomicos deixam meia chamada gravada, e ninguem sabe que ficou pela
;; metade. `POST /sessoes/:id/presenca/lote` grava o lote inteiro NUMA UNICA transacao (tudo ou nada); esta
;; funcao e' o desempate ANTES de a transacao comecar.

(defn vereador-duplicado-no-lote
  "PURO. O PRIMEIRO `:vereador-id` que aparece mais de uma vez em `registros` (seq de mapas com
  `:vereador-id`), ou nil se todos sao unicos.

  Duas linhas do MESMO lote apontando para o MESMO vereador sao uma AMBIGUIDADE, nao um erro de digitacao
  obvio: pode ser 'saiu e voltou' (dois eventos legitimos, tipos diferentes) ou pode ser a Mesa corrigindo
  um registro que acabou de errar. A ordem de uma lista JSON nao decide qual das duas leituras vale — RECUSA
  o lote inteiro (400) e deixa o operador mandar dois POSTs separados se o caso for 'saiu e voltou'."
  [registros]
  (loop [vistos #{} regs (seq registros)]
    (if-let [[{:keys [vereador-id]} & rs] regs]
      (if (contains? vistos vereador-id)
        vereador-id
        (recur (conj vistos vereador-id) rs))
      nil)))

;; ---------- §22.6 eixo D — gravacao (audio/video) da sessao (F4.4b) ----------
;; `gravacao_segmento` e' unidade TECNICA do arquivo, nao regimental (uma sessao tem 1 segmento tipico mas N
;; possiveis: reinicio do OBS, divisao manual). Alinhamento com fatos da sessao = por INSTANTE. Os vocabularios
;; espelham os CHECK da migration 0031.

(def motivos-inicio-gravacao
  "Por que um segmento de gravacao COMECOU: a sessao iniciou, reinicio apos falha tecnica (OBS caiu), ou
  divisao manual feita pela camara."
  #{"inicio_sessao" "reinicio_pos_falha" "divisao_manual"})

(def motivos-fim-gravacao
  "Por que um segmento TERMINOU: a sessao encerrou, falha tecnica interrompeu, ou divisao manual."
  #{"fim_sessao" "falha_tecnica" "divisao_manual"})

(def fontes-ingestao-gravacao
  "Como o arquivo chegou (§22.6 eixo D / §22.3.4). V1 produz so `gravacao_local_pos_sessao` (upload do
  utilitario CLI/watch folder); os demais existem no enum mas sem fluxo produtor V1 (rtmp/youtube dependem do
  satelite de captura; importacao_legado entra quando bulk historico voltar)."
  #{"gravacao_local_pos_sessao" "rtmp_duplicado_ao_vivo" "youtube_api_fallback" "importacao_legado"})

(defn validar-motivo-inicio
  "Fail-closed: lanca se `motivo` de inicio nao e' conhecido (espelha o CHECK da mig 0031)."
  [motivo]
  (when-not (contains? motivos-inicio-gravacao motivo)
    (throw (ex-info "motivo de inicio de gravacao invalido" {:motivo motivo :validos motivos-inicio-gravacao}))))

(defn validar-motivo-fim
  "Fail-closed: lanca se `motivo` de fim e' nao-nil e desconhecido. nil e' valido (gravacao ainda aberta)."
  [motivo]
  (when (and (some? motivo) (not (contains? motivos-fim-gravacao motivo)))
    (throw (ex-info "motivo de fim de gravacao invalido" {:motivo motivo :validos motivos-fim-gravacao}))))

(defn validar-fonte-ingestao
  "Fail-closed: lanca se `fonte` de ingestao nao e' conhecida."
  [fonte]
  (when-not (contains? fontes-ingestao-gravacao fonte)
    (throw (ex-info "fonte de ingestao de gravacao invalida" {:fonte fonte :validas fontes-ingestao-gravacao}))))

;; ---------- §22.6 eixo F — tribuna: inscricao de oradores (F4.5a) ----------
;; A inscricao e' a camada de INTENCAO (intencao != execucao): pode terminar em `desistencia` SEM gerar fala.
;; Subordinada a FASE da pauta (reusa `fases-pauta`). Os vocabularios espelham os CHECK da migration 0032.

(def origens-inscricao
  "Os 4 caminhos pelos quais um orador se inscreve (§22.6 eixo F): pelo app (vereador), pela secretaria,
  pedido intra-sessao, ou automatica por autoria (o autor da materia entra na fila ao ir a ordem do dia)."
  #{"pre_sessao_app" "pre_sessao_secretaria" "intra_sessao_pedido" "automatica_por_autoria"})

(def estados-inscricao
  "Ciclo da intencao: nasce 'inscrita' e pode terminar em 'desistencia' (terminal, sem fala). Nome `estado`
  (nao `situacao`) p/ alinhar a coluna ao shared.imut_trava_estado_terminal, como justificativa_ausencia."
  #{"inscrita" "desistencia"})

(def estados-inscricao-terminais #{"desistencia"})

(def transicoes-inscricao
  "inscrita -> desistencia (terminal). O CHECK da mig 0032 + o terminal-lock trigger espelham."
  {"inscrita"    #{"desistencia"}
   "desistencia" #{}})

(defn transicao-inscricao-valida?
  "A transicao de->para da inscricao e' permitida? (terminal nao sai). Puro."
  [de para]
  (contains? (get transicoes-inscricao de #{}) para))

(defn validar-origem-inscricao
  "Fail-closed: lanca se `origem` de inscricao nao e' um dos 4 caminhos conhecidos."
  [origem]
  (when-not (contains? origens-inscricao origem)
    (throw (ex-info "origem de inscricao invalida" {:origem origem :validas origens-inscricao}))))

;; ---------- §22.6 eixo F — tribuna: fala executada + cronometro (F4.5b) ----------
;; A FALA e' SEPARADA da inscricao (intencao != execucao). O cronometro NUNCA e' snapshot: e' PROJECAO sobre
;; eventos append-only; `tempo_efetivamente_usado_segundos` e' computado AO ENCERRAR. Os vocabularios espelham
;; os CHECK da migration 0033.

(def tipos-fala
  "Tipos de fala (§22.6 eixo F). Apartes vinculam-se a uma fala principal via fala_pai_id."
  #{"principal" "aparte" "pela_ordem" "questao_de_ordem" "explicacao_pessoal" "comunicado"})

(def tipos-evento-cronometro
  "Eventos do cronometro da fala. iniciada/encerrada sao cravados por iniciar-fala!/encerrar-fala!; os demais
  sao registrados pela Mesa ao vivo."
  #{"iniciada" "encerrada" "pausada" "retomada" "aparte_concedido" "tempo_adicional_concedido"})

(def tipos-evento-cronometro-manual
  "Subconjunto que a Mesa registra explicitamente (registrar-evento-cronometro!); iniciada/encerrada sao
  internos do ciclo da fala."
  #{"pausada" "retomada" "aparte_concedido" "tempo_adicional_concedido"})

(defn aparte? [tipo-fala] (= "aparte" tipo-fala))

(defn escolher-tempo-regimental
  "Das linhas candidatas de `tempo_regimental` para um (fase, tipo) — [{:fase :segundos}], a da fase e/ou a
  generica (fase nil) — devolve os segundos que valem: a linha com fase EXPLICITA vence a generica; sem
  nenhuma, nil (sem limite). Pura (mig 0081)."
  [linhas]
  (or (some #(when (some? (:fase %)) (:segundos %)) linhas)
      (some #(when (nil? (:fase %)) (:segundos %)) linhas)))

(def tempo-regimental-maximo-segundos
  "Teto de um tempo regimental (1 hora). Nao e' regra do regimento — e' defesa contra digitacao errada na tela
  da secretaria (3000 minutos no lugar de 30). Um regimento que de' mais que isso a uma fala e' caso a
  reabrir, nao a aceitar em silencio."
  3600)

(def referencia-normativa-maximo-caracteres 200)

(defn validar-tempos-regimentais!
  "A TABELA INTEIRA de tempos da Casa, como a secretaria a salva (tela \"Tempos da tribuna\"): vocabulario
  fechado (tipo-fala, fase ou nil), segundos inteiros em 1..`tempo-regimental-maximo-segundos`, referencia
  normativa curta, e NO MAXIMO uma linha por (fase, tipo) — duas diriam valores diferentes para a mesma fala.
  Tabela vazia e' valida (a Casa volta a nao ter limite). Falha = ex-info `:validacao/invalido` (400 na borda,
  nunca o CHECK/indice unico do banco -> 500). Pura."
  [itens]
  (let [invalido! (fn [msg info] (throw (ex-info msg (assoc info :tipo :validacao/invalido))))]
    (doseq [{:keys [fase tipo-fala segundos referencia-normativa]} itens]
      (when-not (contains? tipos-fala tipo-fala)
        (invalido! "tipo de fala invalido" {:campo :tipo-fala}))
      (when-not (or (nil? fase) (contains? fases-pauta fase))
        (invalido! "fase invalida" {:campo :fase}))
      (when-not (and (int? segundos) (<= 1 segundos tempo-regimental-maximo-segundos))
        (invalido! (str "segundos deve ser inteiro entre 1 e " tempo-regimental-maximo-segundos)
                   {:campo :segundos}))
      (when (and referencia-normativa (> (count referencia-normativa) referencia-normativa-maximo-caracteres))
        (invalido! (str "referencia-normativa com mais de " referencia-normativa-maximo-caracteres " caracteres")
                   {:campo :referencia-normativa})))
    (when-let [repetido (some (fn [[par n]] (when (> n 1) par))
                              (frequencies (map (juxt :fase :tipo-fala) itens)))]
      (invalido! "o mesmo tipo de fala aparece duas vezes para a mesma fase"
                 {:campo :itens :fase (first repetido) :tipo-fala (second repetido)}))
    nil))

(defn validar-tipo-fala [tipo]
  (when-not (contains? tipos-fala tipo)
    (throw (ex-info "tipo de fala invalido" {:tipo tipo :validos tipos-fala}))))

(defn validar-tipo-evento-cronometro-manual [tipo]
  (when-not (contains? tipos-evento-cronometro-manual tipo)
    (throw (ex-info "tipo de evento de cronometro invalido (manual)" {:tipo tipo :validos tipos-evento-cronometro-manual}))))

(defn validar-evento-cronometro
  "Coerencia tipo<->segundos-adicionais (o CHECK da mig 0033 espelha): 'tempo_adicional_concedido' EXIGE
  segundos-adicionais > 0; os demais tipos proibem o campo."
  [tipo segundos-adicionais]
  (validar-tipo-evento-cronometro-manual tipo)
  (if (= "tempo_adicional_concedido" tipo)
    (when-not (and (int? segundos-adicionais) (pos? segundos-adicionais))
      (throw (ex-info "tempo_adicional_concedido exige segundos_adicionais > 0" {:segundos segundos-adicionais})))
    (when (some? segundos-adicionais)
      (throw (ex-info "so tempo_adicional_concedido carrega segundos_adicionais" {:tipo tipo})))))

(defn- epoch-s ^long [^java.time.Instant t] (.getEpochSecond t))

(defn tempo-efetivo-segundos
  "Tempo EFETIVAMENTE usado (segundos) = (encerrou - iniciou) menos a soma dos intervalos pausados. `eventos` =
  os eventos de cronometro (kebab, com :tipo e :ocorrido-em Instant) — usa os pares pausada->retomada. Aparte e
  tempo adicional NAO entram no tempo USADO (aparte = marcador; tempo adicional estende o LIMITE regimental, nao
  o uso). Pura — a base de computar o cronometro ao encerrar (projecao sobre eventos, sem snapshot)."
  [^java.time.Instant iniciou-em ^java.time.Instant encerrou-em eventos]
  (let [bruto (- (epoch-s encerrou-em) (epoch-s iniciou-em))
        [pausado-pares ini-final]
        (loop [evs (sort-by :ocorrido-em (filter #(#{"pausada" "retomada"} (:tipo %)) eventos))
               ini nil acc 0]
          (if-let [e (first evs)]
            (let [t (:tipo e) o (:ocorrido-em e)]
              (cond
                (and (= t "pausada")  (nil? ini))  (recur (rest evs) o acc)
                (and (= t "retomada") (some? ini)) (recur (rest evs) nil (+ acc (- (epoch-s o) (epoch-s ini))))
                :else (recur (rest evs) ini acc)))
            [acc ini]))
        ;; pausa ABERTA no encerramento (pausada sem retomada): desconta o intervalo [ini-final, encerrou-em] —
        ;; senao o tempo viria inflado (o orador estava pausado quando a fala encerrou).
        pausado (cond-> pausado-pares
                  (some? ini-final) (+ (- (epoch-s encerrou-em) (epoch-s ini-final))))]
    (max 0 (- bruto pausado))))

;; ---------- incidente_processual (§16.13) ----------

(def tipos-incidente
  "Incidentes processuais que NAO tem casa propria. `questao_de_ordem` vive em decisao_mesa (decisao do
  presidente, mig 0034) e `retirada_de_pauta` no soft-remove do pauta_item (mig 0027) — por isso ficam FORA
  deste enum (evita dupla modelagem)."
  #{"pedido_vista" "verificacao_votacao" "urgencia" "votacao_em_bloco"})

(def resultados-incidente
  "Disposicao do incidente, deliberada na hora (V1 atomico)."
  #{"deferido" "indeferido" "prejudicado" "retirado"})

(def tipos-objeto-incidente
  "Materias que um incidente pode atingir (ref polimorfica forward-ref). BOUNDED (o CHECK da mig 0035 espelha):
  evita typo mudo no read-model por materia. Aberto a 1 entrada por tipo novo (config-ish)."
  #{"proposicao" "votacao" "emenda"})

(defn validar-tipo-incidente
  "Fail-closed: lanca se `tipo` nao e' um incidente processual conhecido (espelha o CHECK da mig 0035 -> evita
  500 do banco quando barra antes)."
  [tipo]
  (when-not (contains? tipos-incidente tipo)
    (throw (ex-info "tipo de incidente processual invalido" {:tipo tipo :validos tipos-incidente}))))

(defn validar-resultado-incidente
  "Fail-closed: lanca se `resultado` nao e' uma disposicao conhecida (espelha o CHECK da mig 0035)."
  [resultado]
  (when-not (contains? resultados-incidente resultado)
    (throw (ex-info "resultado de incidente processual invalido" {:resultado resultado :validos resultados-incidente}))))

;; ---------- §22.6 eixo C — o ATO da CHAMADA CONDUZIDA (Etapa 2d) ----------
;; `presenca_evento` sozinho so' grava QUEM APARECEU: "ninguem registrou nada ainda" e "a chamada foi feita e
;; a Casa inteira faltou" produzem as MESMAS zero linhas — indistinguiveis, o que contamina a folha da sessao
;; (Etapa 5) e a apuracao de assiduidade (Etapa 6). O ato abaixo e' o registro de que a chamada ACONTECEU:
;; quando, quem conduziu, e quantos membros a Casa tinha NAQUELE instante (o denominador CONGELADO). Ver o
;; cabecalho da migration 0072 para o porque de uma tabela propria em vez de estender `incidente_processual`.

(def teto-de-atos-de-chamada
  "Quantos atos de chamada conduzida uma sessao aceita. A tabela e' APPEND-ONLY e a mig 0072 concede
  `SELECT, INSERT` (sem DELETE) alem de instalar `trg_chamada_conduzida_append_only` — uma vez inserida, a
  linha NAO SAI por nenhum caminho da aplicacao. Sem teto, a rota (bodyless, sem rate limit) deixava um
  `secretario` inflar `GET /sessoes/:id/chamada` — o endpoint do telao do M4 — ate' o OOM de um monolito
  COMPARTILHADO por todos os tenants, e o dano era IRREVERSIVEL. Reconduzir a chamada legitimamente sao
  unidades por sessao (apos suspensao, ou reverificacao a pedido da Mesa); 50 e' folga de duas ordens de
  grandeza. E' a convencao ja declarada em `rotas.clj` ('todo predicado de cardinalidade aberta tem teto
  explicito') aplicada onde faltava."
  50)

(def ^Duration janela-de-deduplicacao-de-chamada
  "Janela em que um SEGUNDO `POST /sessoes/:id/chamada` do MESMO ator e' lido como REENVIO, nao como
  reconducao: devolve o ato existente (200) em vez de criar outro. A rota nao tem corpo, entao o cliente nao
  tem como sinalizar 'e' o mesmo ato' — e sem isto um duplo clique num plenario com Wi-Fi ruim gravava dois
  atos, fazendo a folha da sessao registrar uma reverificacao de quorum que nao aconteceu, num registro que
  nao pode ser desfeito. 30s separa o duplo clique da reconducao real (que leva minutos: a Mesa reabre a
  chamada, chama os nomes)."
  ^Duration (Duration/ofSeconds 30))

(defn validar-membros-da-casa
  "Fail-closed: lanca se `n` nao e' um inteiro >= 0 (espelha o CHECK `membros_da_casa >= 0` da mig 0072). Nao
  ha' um 'vocabulario' aqui (nao e' um enum — este ato nao introduz nenhum CHECK de texto) mas a MESMA
  disciplina de validar ANTES do banco vale: um denominador negativo e' bug de servidor (nunca deveria sair
  de `membros-da-casa-da-chamada`), nao um dado de cliente a recusar com mensagem acionavel."
  [n]
  (when-not (and (integer? n) (>= n 0))
    (throw (ex-info "membros-da-casa invalido (deve ser inteiro >= 0)" {:membros-da-casa n}))))

(defn mensagem-de-recusa-de-chamada
  "PURO. A mensagem ACIONAVEL do 409 ao CONDUZIR a chamada (Etapa 2d, `POST /sessoes/:id/chamada`) — MESMO
  gate de estado+janela de `motivo-recusa-de-presenca` (conduzir a chamada e' o MESMO tipo de fato que
  registrar presenca: um ato contra o quorum de uma sessao, que nao pode ser escrito depois que ela fechou —
  fatia 2a), so' com a REDACAO do recurso certo (nao fala de 'presenca'). Como `ocorrido-em` desta rota e'
  sempre o relogio do servidor (nunca do cliente, mesmo contrato de `confirmar-minha-presenca`), na pratica
  so' `:estado-nao-aceita-presenca` e' alcancavel; os demais ramos ficam de cinto de seguranca."
  [motivo sessao agora]
  (case motivo
    :estado-nao-aceita-presenca
    (str "a sessao esta '" (:estado sessao) "' e nao aceita mais a conducao da chamada. "
         "So' se conduz a chamada com a sessao agendada, aberta ou suspensa — refaze-la agora alteraria "
         "o quorum de votacoes ja realizadas.")

    :instante-no-futuro
    (str "o instante da chamada e' posterior ao relogio do servidor (" agora ").")

    :instante-fora-do-dia-da-sessao
    (str "o instante da chamada nao cai no dia desta sessao (a partir de "
         (piso-da-janela-de-presenca sessao) ").")

    :instante-apos-o-encerramento
    (str "o instante da chamada e' posterior ao encerramento da sessao (" (:encerrada-em sessao) ").")

    ;; FORA de `motivos-do-gate-de-presenca`: nasce no Repo, sobre o denominador ja computado, nao na janela.
    :casa-sem-membros
    (str "a Casa nao tem nenhum mandato vigente na data desta sessao — o ato registraria um denominador de "
         "quorum ZERO num registro que nao pode ser corrigido. Confira a data da sessao e a composicao em "
         "cadastros antes de conduzir a chamada.")

    :teto-de-atos
    (str "esta sessao ja registrou o maximo de " teto-de-atos-de-chamada " conducoes de chamada. "
         "Reconduzir a chamada e' ato excepcional (apos suspensao, ou reverificacao a pedido da Mesa) — "
         "se o limite foi atingido de verdade, o caso e' de suporte, nao de mais um POST.")

    (str "conducao de chamada recusada para esta sessao (" (name motivo) ").")))

;; ---------- §22.6 eixo C — a APURACAO DE ASSIDUIDADE (Etapa 6 fatia 2) ----------
;; A apuracao NAO e' uma segunda aritmetica de presenca (I1): para CADA sessao do periodo, ela roda a MESMA
;; `derivar-linhas-da-chamada` + `contar-quorum` que a chamada nominal usa. O que muda e' a ESCALA — em vez
;; de uma sessao, um PERIODO inteiro — e o AGRUPAMENTO — por vereador, ao longo das sessoes em que ele
;; comparecia a Casa. `janelas-de-exercicio` (I-5, cadastros) NAO entra aqui: o denominador de CADA sessao
;; ja' e' o roster daquela data (Etapa 6 fatia 1), o MESMO numero que a chamada congelou — recalcular por
;; janelas de exercicio produziria um TERCEIRO numero para a mesma pergunta (ver o brief da Etapa 6).

(def teto-de-dias-do-periodo-de-assiduidade
  "Periodo maximo (dias, inclusivo dos dois lados) que `apurar-assiduidade` aceita apurar de uma vez — o
  MESMO numero de `cadastros.db.vereador/teto-de-datas-lote` (366): sao a MESMA regra de produto vista de
  dois modulos (sessoes PEDE o periodo; cadastros o RECEBE como lista de datas civis distintas), e os dois
  tem de concordar. Um teto de dias maior que o de datas civis produziria uma rejeicao IMPOSSIVEL de
  disparar a partir da borda real — nao ha' mais de 366 datas civis distintas num periodo de 366 dias."
  366)

(def teto-de-sessoes-do-periodo-de-assiduidade
  "Teto de SESSOES no recorte — NAO de datas civis (duas sessoes no mesmo dia sao UMA data para o roster em
  lote, mas DUAS sessoes para este teto; ver §Tetos do brief da Etapa 6, revisao da Fatia 1: 'datas !=
  sessoes'). Pior caso teorico do produto: 400 sessoes x 150 vereadores no roster do periodo (54.900 linhas
  de detalhe); na Casa real e' ~200 sessoes x ~21 vereadores. O teto e' o guard-rail, nao a expectativa."
  400)

(def teto-de-vereadores-do-periodo-de-assiduidade
  "Vereadores no roster do periodo (brief da Etapa 6, §Tetos) — o MESMO 150 que `cadastros.db.vereador/
  teto-de-linhas-lote` usa como multiplicador. Fica aqui nomeado (e nao como literal solto) porque e' o
  fator dos tetos de LINHA das leituras em lote de presenca, abaixo."
  150)

(def teto-de-linhas-de-lote-de-presenca
  "Teto de LINHAS que as leituras em lote de `db/presenca` (`presencas-correntes-das-sessoes`,
  `justificativas-das-sessoes`) aceitam materializar na JVM: 400 sessoes x 150 vereadores = 60.000.

  NAO e' redundante com o teto de SESSOES, e a razao e' concreta: `presenca_evento.vereador_id` **nao tem
  FK** e a escrita nao valida mandato (ver `derivar-linha-sem-assento`), entao o `DISTINCT ON` e' limitado
  pelos `vereador_id` DISTINTOS que aparecem nos EVENTOS — nao pelos 150 do roster. Um acervo migrado sujo
  com 3.000 ids distintos produz 400 x 3.000 = 1,2 MILHAO de linhas materializadas por request. Sem
  `statement_timeout` (carry de infra) e com pool de 10, dois ou tres requests desses derrubam o processo
  para TODOS os tenants — inclusive a sessao ao vivo, que e' o SLA que o produto vende.

  Aplicado como na fatia 1: `:max-rows` = teto+1 (o driver PARA de materializar no primeiro excedente) +
  rejeicao fail-closed `:limite/linhas-excedido` com `:medido-ao-menos` (a medicao e' um PISO), nunca pagina
  truncada (I7)."
  (* teto-de-sessoes-do-periodo-de-assiduidade teto-de-vereadores-do-periodo-de-assiduidade))

(def teto-de-linhas-de-detalhe-de-assiduidade
  "Teto da SAIDA `:detalhe` (400 x 150 = 60.000 linhas). NAO e' o mesmo guard-rail que
  `teto-de-linhas-de-lote-de-presenca`, que limita o que ENTRA da leitura de eventos: este limita o produto
  `sessoes x roster-da-data` que a apuracao PRODUZ, e ate' a revisao da Fatia 3 esse produto era ILIMITADO —
  o teto de 150 vereadores existia como `def` e nao era verificado em lugar nenhum (`grep`: aparecia so' na
  propria `def` e como multiplicador). 400 sessoes na MESMA data com um roster inflado (o acervo migrado
  sujo que a docstring do lote de presenca ja' descreve) produziam milhoes de linhas serializadas e
  validadas por Malli, tres a quatro copias do arquivo no heap, com pool de 10 e sem `statement_timeout`.
  Fail-closed com o numero MEDIDO (`:limite/*` -> 422 pelo interceptor global), nunca pagina truncada (I7)."
  (* teto-de-sessoes-do-periodo-de-assiduidade teto-de-vereadores-do-periodo-de-assiduidade))

(defn validar-periodo-assiduidade!
  "Fail-closed, chamado em DOIS lugares de proposito (mesma REDE de `vereador/normalizar-datas!`): no
  controller, ANTES de abrir a tx (rejeicao nao empresta conexao do pool) — e de novo dentro de
  `db/sessao/listar-fechadas-no-periodo`, como rede contra um chamador direto.

  `de` posterior a `ate` -> `:validacao/invalido` (400 na borda futura da Fatia 3): nao e' um teto, e' uma
  entrada sem sentido — devolver silenciosamente ZERO sessoes para um periodo invertido seria indistinguivel
  de 'realmente nao houve sessao', que e' exatamente o tipo de silencio que I7 proibe.

  Periodo acima de `teto-de-dias-do-periodo-de-assiduidade` dias -> `:limite/periodo-excedido` (namespace
  `limite` -> 422 no interceptor global, JA' mapeado — nenhum handler novo precisa reconhecer este `:tipo`),
  com `:medido`/`:teto` em DIAS no corpo.

  `de`/`ate` que NAO sao `java.time.LocalDate` -> `:validacao/invalido` ANTES de qualquer `.isAfter`. Sem
  este guarda, um `java.sql.Date` (o que o driver devolve quando alguem passa uma data lida do banco) ou um
  `nil` viravam ClassCastException/NPE sob o type-hint — 500 opaco com stack no `log/error`, no lugar de um
  400 que diz qual campo esta errado. E' a MESMA disciplina que `vereador/normalizar-datas!` (fatia 1) ja'
  aplica; ela nao tinha sido aplicada aqui."
  [de ate]
  (doseq [[campo v] [[:de de] [:ate ate]]]
    (when-not (instance? LocalDate v)
      (throw (ex-info "apuracao de assiduidade: data que nao e' java.time.LocalDate"
                      {:tipo :validacao/invalido :campo campo
                       :classe (some-> v class .getName)}))))
  (let [^LocalDate de de
        ^LocalDate ate ate]
    (when (.isAfter de ate)
      (throw (ex-info "apuracao de assiduidade: periodo invalido (de posterior a ate)"
                      {:tipo :validacao/invalido :de de :ate ate})))
    (let [dias (inc (.until de ate ChronoUnit/DAYS))]
      (when (> dias teto-de-dias-do-periodo-de-assiduidade)
        (throw (ex-info "periodo de apuracao de assiduidade acima do teto"
                        {:tipo :limite/periodo-excedido :medido dias
                         :teto teto-de-dias-do-periodo-de-assiduidade}))))))

(defn validar-tipos-de-assiduidade!
  "Fail-closed sobre o filtro `tipos` da apuracao, chamado nos MESMOS DOIS lugares que
  `validar-periodo-assiduidade!` (controller antes da tx + `db/sessao/listar-fechadas-no-periodo` como rede
  contra o chamador direto). Ate' a revisao desta fatia, `tipos` chegava ao SQL sem validacao em camada
  NENHUMA, e isso produzia dois defeitos distintos:

  - FAIL-OPEN: `?tipos=ordinaria` com acento, ou um typo (`ordinaira`), casa ZERO sessoes e devolve 200 com
    a apuracao em branco — indistinguivel de 'nao houve sessao no periodo'. E' o silencio que I7 proibe, e e'
    TEXTUALMENTE a licao que a fatia 1 pagou e escreveu em `vereador/normalizar-datas!`.
  - CONTRATO FROUXO: um elemento que nao e' string atravessa o HoneySQL como identificador — uma keyword
    vira `IN ORDINARIA()` (erro de SQL -> 500) e um vetor `[:raw ...]` vira texto CRU dentro da clausula.
    Nao ha' rota que alcance isto hoje (a Fatia 3 e' que abre a borda), e por isso nao e' explotavel; o ponto
    e' que a unica coisa que separava o SQL de uma entrada arbitraria era NAO EXISTIR chamador.

  `nil`/vazio = todos os tipos (o default do brief), e continua legitimo."
  [tipos]
  (doseq [t tipos]
    (when-not (and (string? t) (contains? tipos-sessao t))
      (throw (ex-info "apuracao de assiduidade: tipo de sessao desconhecido"
                      {:tipo :validacao/invalido :campo :tipos :valor (str t)
                       :classe (some-> t class .getName) :validos tipos-sessao})))))

(def ^:private criterio-de-inclusao-assiduidade
  "So' sessoes FECHADAS entram no periodo apurado: encerrada, nao_realizada ou arquivada
  (logic/estados-sessao-fechada). 'Convocada', nesta apuracao, significa 'convocada e ja ocorrida ou
  frustrada' — sessao agendada (ainda nao ocorreu) ou aberta/suspensa (ao vivo) fica de fora, porque o
  denominador de uma sessao viva ainda esta em movimento. Sessao nao_realizada CONTA como convocada: quem
  compareceu antes de a sessao ser dada como frustrada e' presente; quem nao compareceu e' falta,
  classificada pela justificativa como qualquer outra.")

(def ^:private nota-de-metodologia-assiduidade
  "Esta apuracao e' RECALCULADA a partir do CADASTRO DE HOJE (roster, mandatos, licencas). A folha de
  presenca da sessao (Etapa 5) e' o registro do que valia NO DIA da sessao e fica congelada para sempre; um
  cadastro corrigido DEPOIS daquele dia (um mandato lancado com a data errada, uma licenca registrada com
  atraso) faz os dois numeros legitimamente diferirem — a divergencia nao e' inconsistencia do sistema, e' o
  efeito esperado de corrigir um erro de cadastro apos o fato.")

(defn- agregado-vazio-de-assiduidade []
  {:sessoes-computadas 0 :comparecimentos 0 :ausencias-justificadas 0
   :ausencias-com-justificativa-pendente 0 :ausencias-injustificadas 0 :sessoes-licenciado 0})

(defn- somar-linha-na-assiduidade
  "PURO. Soma UMA linha JA DERIVADA (`derivar-linhas-da-chamada`) no agregado de UM vereador. As 4
  classificações de falta/presença são as de `estados-chamada` — `:ausente-justificativa-pendente` tem
  bucket PRÓPRIO e nunca cai no `else` de `:ausente` (I4): a Mesa ainda não decidiu, e contar como
  'injustificada' seria publicar uma acusação que ninguém fez. `:sessoes-computadas` (o denominador) usa a
  MESMA regra de `conta-no-denominador-do-quorum?` — nunca uma soma paralela que poderia divergir da
  contagem de quorum da própria sessão (I1). `:comparecimentos` (o NUMERADOR) usa
  `conta-no-numerador-do-quorum?` pela MESMA razão: até a revisão desta fatia ele consultava um conjunto
  GÊMEO de `estados-presentes`, e uma terceira categoria positiva no domínio teria feito a chamada e o CSV
  contarem conjuntos diferentes, em silêncio."
  [agregado linha]
  (cond-> agregado
    (conta-no-denominador-do-quorum? linha)
    (update :sessoes-computadas inc)

    (conta-no-numerador-do-quorum? linha)
    (update :comparecimentos inc)

    (= :ausente-justificado (:estado linha))
    (update :ausencias-justificadas inc)

    (= :ausente-justificativa-pendente (:estado linha))
    (update :ausencias-com-justificativa-pendente inc)

    (= :ausente (:estado linha))
    (update :ausencias-injustificadas inc)

    (= :licenciado (:estado linha))
    (update :sessoes-licenciado inc)))

(defn apurar-assiduidade
  "PURA — o coracao da Etapa 6. Para CADA sessao de `sessoes`, deriva as linhas da chamada
  (`derivar-linhas-da-chamada`) e conta o quorum (`contar-quorum`) — a MESMA aritmetica que a chamada
  nominal usa (I1 do brief): nenhum COUNT/FILTER em SQL reproduzindo esta regra em outro lugar. Se um teste
  passa sem que estas duas funcoes sejam chamadas, o teste e' falso.

  `sessoes` = as linhas de `db/sessao/listar-fechadas-no-periodo`, cada uma JA carregando
  `:data-de-referencia` (LocalDate, calculada UMA vez em `db/sessao` — `(or aberta-em agendada-para)`
  convertida a data civil — nunca recomputada aqui: um segundo calculo da MESMA data seria a mesma classe de
  risco de divergencia que I1/I3 fecham para o roster e a ordem de evento).
  `rosters-por-data` = `{LocalDate -> [roster-linha ...]}` (`cadastros/roster-da-casa-em-datas`, Fatia 1).
  `presencas-por-sessao`/`justificativas-por-sessao` = `{sessao-id -> [...]}`, MESMA forma de
  `presenca-corrente`/`listar-justificativas-da-sessao`, agora em lote por sessao (`db/presenca`, Fatia 2).

  IDENTIDADE UMA SO' VEZ (carry de LGPD da revisao da Fatia 1): `nome`/`nome-parlamentar`/`partido` sao
  CONSTANTES por vereador dentro do periodo e vivem SO' em `:vereadores`; `:detalhe` referencia por
  `:vereador-id`, nunca repete o nome civil por linha — no pior caso do teto seriam dezenas de milhares de
  repeticoes do mesmo nome e filiacao partidaria num payload so'. Uma linha SEM ASSENTO (vereador-id sem
  registro no roster de nenhuma data do periodo) entra em `:vereadores` com identidade `nil` — `sessoes` nao
  inventa nome que `cadastros` nao devolveu para aquele vereador em nenhuma das datas pedidas.

  NOMES DO WIRE distintos do card publico de `transparencia` (I-5): `:sessoes-computadas`/`:comparecimentos`,
  NUNCA `:presenca`/`:sessoes-presente` — a Casa nao pode ver dois numeros de assiduidade do MESMO vereador
  com o MESMO rotulo, um vindo do card publico e outro desta apuracao interna.

  `:percentual` de cada vereador e' `nil` quando `:sessoes-computadas` e' ZERO (nunca `0` — que leria como
  'faltou a tudo'; a diferenca entre 'nao podia comparecer a nada' e 'faltou a tudo' e' a diferenca entre um
  suplente e um faltoso). NAO E' CLAMPADO a [0,100] — ao contrario de `db/presenca/resumo-presenca` (que
  clampa para a vitrine PUBLICA de comprador): aqui, um `:comparecimentos` que excede `:sessoes-computadas`
  (um vereador com presenca SEM ASSENTO fora do proprio periodo de mandato) e' o MESMO tipo de sintoma de
  cadastro furado que `contar-quorum` deliberadamente deixa aparecer em vez de normalizar em silencio — a
  leitura e' do secretario/Mesa, nao da vitrine publica.

  E' TRUNCADO PARA BAIXO (floor), nunca arredondado. Com `Math/round`, um periodo de 200 sessoes em que o
  vereador faltou a UMA dava 199/200 = 99,5 -> **100%**: afirmar 'compareceu a 100% das sessoes' de quem
  faltou a uma e' falso, e este numero vai para um CSV que responde oficio e requerimento — e' o tipo de
  afirmacao que o adversario politico contesta com a lista de presenca na mao. Com floor, **100% so' aparece
  quando `comparecimentos >= sessoes-computadas`**. A fracao crua ja' esta publicada nos contadores; o
  percentual e' conveniencia, e conveniencia nao pode contradizer a fracao.

  `:partido` e' HONESTO, nao constante presumida: mandatos sequenciais com partidos diferentes sao
  permitidos e `mandato-vigente-lateral` devolve o partido DAQUELA data, entao um vereador que trocou de
  partido dentro da janela tem mais de um. Publicado quando e' o MESMO em todas as linhas do periodo; quando
  variou, sai `:partido nil` + `:partido-variou true` (a Casa lista por partido no oficio, entao o campo
  fica, mas nao pode fixar um rotulo escolhido pela ordem das sessoes).

  `:detalhe` marca `:sigilosa` LINHA A LINHA, e nao so' no total: `:sessoes-sigilosas N` nos totais diz
  QUANTAS, nao QUAIS — e a fatia 3 serializa `:detalhe` para um CSV que circula por e-mail. A politica de
  sigilo existe no nivel da sessao desde a Etapa 4; e' a projecao nova que passava por baixo dela.

  DOIS TETOS DE SAIDA, fail-closed com o numero MEDIDO (`:limite/*` -> 422 pelo interceptor global), ambos
  ANTES de qualquer serializacao: `teto-de-linhas-de-detalhe-de-assiduidade` (60.000 linhas de detalhe) e
  `teto-de-vereadores-do-periodo-de-assiduidade` (150). Ate' a revisao da Fatia 3 os dois eram `def` que
  ninguem verificava, e o produto `sessoes x roster-da-data` era ilimitado.

  Devolve `{:sessoes [...] :vereadores [...] :por-vereador [...] :detalhe [...] :totais {...}}`.
  `contexto` = `{:sessoes-sem-data-de-referencia N}` (obrigatorio, ver `:totais`) +
  `:com-detalhe?` (opcional, default TRUE): `false` OMITE a chave `:detalhe` — nunca a devolve vazia — para
  a unica apresentacao que nao a consome (CSV `recorte=resumo`)."
  [sessoes rosters-por-data presencas-por-sessao justificativas-por-sessao
   {:keys [sessoes-sem-data-de-referencia] :as contexto}]
  (when-not (nat-int? sessoes-sem-data-de-referencia)
    (throw (ex-info "apurar-assiduidade: contexto sem :sessoes-sem-data-de-referencia"
                    {:tipo :servidor/erro :contexto (keys contexto)})))
  (let [;; `:com-detalhe?` AUSENTE = true (o payload JSON sempre publica `:detalhe`). O unico chamador que
        ;; o passa false e' o CSV `recorte=resumo`, que nao consome a projecao: construi-la ali era gastar
        ;; heap e uma passagem inteira de Malli para jogar fora (achado da revisao adversarial da Fatia 3).
        com-detalhe? (get contexto :com-detalhe? true)
        ;; "chave ausente" e "perguntei e veio vazio" voltam a ser COISAS DIFERENTES. As duas camadas de
        ;; baixo ja' garantiam isso — `roster-da-casa-em-datas` chega a LANCAR `:invariante/data-desconhecida`
        ;; para nao fabricar chave, e as leituras em lote pre-semeiam toda chave pedida — e um `(get m k [])`
        ;; aqui desfazia as duas: uma data de referencia que nao casasse com nenhuma chave (seam trocado, tipo
        ;; divergente, uma futura conversao de fuso por ente) derivaria a sessao com roster VAZIO, e quem
        ;; faltou SUMIRIA da sessao em vez de virar linha ausente — o percentual INFLA, no limite ate' 100%
        ;; para quem faltou, com HTTP 200 e zero log.
        exigir (fn [m k rotulo sessao]
                 (or (get m k)
                     (throw (ex-info (str "apurar-assiduidade: " rotulo " ausente para a sessao")
                                     {:tipo :servidor/erro :chave-ausente rotulo :chave k
                                      :sessao-id (:id sessao)}))))
        por-sessao
        (mapv (fn [sessao]
                (let [data      (:data-de-referencia sessao)
                      roster    (exigir rosters-por-data data :roster-da-data sessao)
                      presencas (exigir presencas-por-sessao (:id sessao) :presencas-da-sessao sessao)
                      justs     (exigir justificativas-por-sessao (:id sessao) :justificativas-da-sessao sessao)
                      linhas    (mapv :linha (derivar-linhas-da-chamada roster presencas justs))]
                  {:sessao sessao :linhas linhas :quorum (contar-quorum linhas)}))
              sessoes)

        sessoes-out
        (mapv (fn [{:keys [sessao quorum]}]
                {:id (:id sessao) :numero (:numero sessao) :tipo (:tipo sessao) :estado (:estado sessao)
                 :data-de-referencia (:data-de-referencia sessao)
                 :sigilosa (not (true? (:transmite-publica sessao)))
                 :quorum quorum})
              por-sessao)

        ;; [sessao-id sigilosa? linha] para cada linha derivada de cada sessao — a fonte comum do resto
        ;; (identidade, detalhe, agregados por vereador). Uma UNICA passada sobre a projecao inteira.
        todas-linhas
        (into [] (mapcat (fn [{:keys [sessao linhas]}]
                           (let [sid (:id sessao)
                                 sigilosa (not (true? (:transmite-publica sessao)))]
                             (map (fn [l] [sid sigilosa l]) linhas))))
              por-sessao)

        ;; O TETO DA SAIDA, fail-closed, ANTES de construir/serializar qualquer projecao (ver
        ;; `teto-de-linhas-de-detalhe-de-assiduidade`). `(count todas-linhas)` E' a contagem de `:detalhe`
        ;; (um para um), medida aqui porque e' o primeiro ponto em que o produto `sessoes x roster` existe.
        _ (let [n (count todas-linhas)]
            (when (> n teto-de-linhas-de-detalhe-de-assiduidade)
              (throw (ex-info "apuracao de assiduidade: linhas de detalhe acima do teto"
                              {:tipo :limite/detalhe-excedido :medido n
                               :teto teto-de-linhas-de-detalhe-de-assiduidade}))))

        identidades
        (reduce (fn [acc [_sid _sig {:keys [vereador-id nome partido] :as linha}]]
                  (if (nil? vereador-id)
                    acc
                    (let [atual (get acc vereador-id)
                          ;; identidade CONHECIDA (nome nao-nil) nunca e' sobrescrita por uma linha
                          ;; sem-assento (identidade nil) de outra sessao do mesmo vereador — o vereador
                          ;; cassado que registra presenca depois do fim do mandato sairia com o nome EM
                          ;; BRANCO num CSV oficial, para alguem que a Casa conhece. `assoc` sobre `atual`
                          ;; (e nao um mapa novo) preserva os `:partidos` ja' acumulados.
                          base (if (some? (:nome atual))
                                 atual
                                 (assoc atual :id vereador-id :nome nome
                                        :nome-parlamentar (:nome-parlamentar linha)))]
                      (assoc acc vereador-id
                             (cond-> base
                               (some? partido)
                               (update :partidos (fnil conj #{}) partido))))))
                {} todas-linhas)

        identidade-out
        (fn [{:keys [partidos] :as ident}]
          (let [variou (> (count partidos) 1)]
            (-> ident
                (dissoc :partidos)
                (assoc :partido (when-not variou (first partidos))
                       :partido-variou variou))))

        vereadores-out
        (->> (vals identidades)
             (map identidade-out)
             (sort-by (juxt (comp #(or % "") :nome) (comp str :id)))
             vec)

        ;; O teto de VEREADORES do brief (§Tetos) — ate' a revisao da Fatia 3 ele existia so' como `def`.
        ;; Vale sobre `vereadores-out` (e nao sobre o roster) porque e' esta a contagem que sai no arquivo:
        ;; ela inclui as presencas SEM ASSENTO, que nao tem FK e que um acervo migrado sujo multiplica.
        _ (when (> (count vereadores-out) teto-de-vereadores-do-periodo-de-assiduidade)
            (throw (ex-info "apuracao de assiduidade: vereadores no periodo acima do teto"
                            {:tipo :limite/vereadores-excedido :medido (count vereadores-out)
                             :teto teto-de-vereadores-do-periodo-de-assiduidade})))

        detalhe-out
        (when com-detalhe?
          ;; `vereador-id` nil e' DESCARTADO aqui pela MESMA razao que `identidades` e `agregados` acima o
          ;; descartam — a assimetria era um defeito: `(str nil)` = "" passa o `:string` do schema e a linha
          ;; saia ORFA no CSV, sem nome e sem entrada correspondente em `:vereadores`.
          (into [] (comp (remove (fn [[_sid _sig linha]] (nil? (:vereador-id linha))))
                         (map (fn [[sid sigilosa linha]]
                                {:sessao-id sid :vereador-id (:vereador-id linha)
                                 :estado (:estado linha) :sigilosa sigilosa})))
                todas-linhas))

        agregados
        (reduce (fn [acc [_sid _sig {:keys [vereador-id] :as linha}]]
                  (if (nil? vereador-id)
                    acc
                    (update acc vereador-id (fnil somar-linha-na-assiduidade (agregado-vazio-de-assiduidade))
                            linha)))
                {} todas-linhas)

        por-vereador-out
        (->> agregados
             (map (fn [[vereador-id agregado]]
                    (assoc agregado
                           :vereador-id vereador-id
                           :percentual (when (pos? (:sessoes-computadas agregado))
                                         (int (Math/floor (* 100.0 (/ (:comparecimentos agregado)
                                                                      (:sessoes-computadas agregado)))))))))
             (sort-by (fn [{:keys [vereador-id]}]
                       [(or (:nome (get identidades vereador-id)) "") (str vereador-id)]))
             vec)]
    (cond-> {:sessoes sessoes-out
             :vereadores vereadores-out
             :por-vereador por-vereador-out
             :totais
             {:sessoes-consideradas (count sessoes-out)
              :vereadores-considerados (count vereadores-out)
              :sessoes-sigilosas (count (filter :sigilosa sessoes-out))
              ;; DECLARADO, nao silencioso. Sessao fechada sem NENHUM marco de data (`agendada_para` e'
              ;; opcional no wire e nullable na coluna, e `agendada -> nao_realizada` nao exige data: a linha
              ;; e' alcancavel pela API NORMAL) fica FORA do periodo — nao ha' data de referencia que a
              ;; posicione em periodo nenhum, e acrescentar `encerrada_em` como terceiro nivel do COALESCE
              ;; criaria uma QUARTA variante da regra, divergente da `/chamada` (ver
              ;; `marcos-de-data-de-referencia`). O que NAO pode e' o denominador de todos os vereadores
              ;; encolher sem explicacao: a mesma linha faz `/sessoes/:id/chamada` devolver um 409 ACIONAVEL,
              ;; e era o comportamento SILENCIOSO que virava documento. Assiduidade sustenta perda de mandato
              ;; por falta (DL 201) — publicar 92% onde o correto e' 91,7% sem sinal e' o silencio que I7
              ;; proibe.
              :sessoes-sem-data-de-referencia sessoes-sem-data-de-referencia
              :criterio-de-inclusao criterio-de-inclusao-assiduidade
              :nota-de-metodologia nota-de-metodologia-assiduidade}}
      ;; A chave `:detalhe` ou vem COMPLETA ou NAO VEM — nunca um vetor vazio, que leria como "nenhuma
      ;; linha no periodo" e nao como "esta apresentacao nao pede detalhe".
      com-detalhe? (assoc :detalhe detalhe-out))))
