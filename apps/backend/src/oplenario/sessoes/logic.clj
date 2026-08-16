(ns oplenario.sessoes.logic
  "PURO: regras e maquina de estados da sessao plenaria (§22.6 eixo A). Sem I/O. Capabilities desacopladas
  do tipo (disciplina §22.6.3 nº3): o tipo e' nome regimental, o comportamento e' atributo com default
  derivado + override auditado. Os vocabularios espelham os CHECK da migration 0026.

  Requer `kernel/tempo` SO' pela zona civil (§22.6 eixo C: o piso da janela de presenca e' o DIA CIVIL da
  sessao, e dia civil nao existe sem fuso). Continua PURO — `zona-civil-padrao` e' uma constante, nao um
  relogio; nenhum instante e' LIDO aqui."
  (:require [clojure.string :as str]
            [oplenario.kernel.tempo :as tempo])
  (:import (java.time Duration Instant ZoneId)))

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

(def estados-chamada-presentes
  "Os estados que contam como PRESENTE (numerador do quorum). O restante nao conta — inclusive `:licenciado`,
  que tambem sai do denominador."
  #{:presente-plenario :presente-remoto})

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
  "Os estados de linha derivada que contam como PRESENTE no NUMERADOR do quorum. FONTE UNICA de proposito:
  `contar-quorum` deriva `:presentes-total` daqui, para que uma terceira categoria positiva (o dominio ja'
  distingue varios estados em `derivar-linha-chamada`) nao precise ser lembrada em dois lugares."
  #{:presente-plenario :presente-remoto})

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
   :presentes-total    (count (filter #(estados-presentes (:estado %)) linhas))
   :membros-da-casa    (count (remove #(or (= :licenciado (:estado %)) (:sem-assento %)) linhas))
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

(def estados-sessao-fechada
  "Estados em que a sessao JA fechou — a chamada tem de congelar no instante em que ela fechou (um evento
  inferido/registrado DEPOIS nao pode mudar uma chamada que ja foi para a ata). O CHECK
  `sessao_encerrada_em_obrigatoria` da mig 0026 cobria so' 'encerrada'/'nao_realizada'; a migration 0071
  (revisao da Etapa 1) estendeu-o a 'arquivada' — antes disso o banco aceitava uma linha arquivada SEM
  carimbo de encerramento, e a derivacao abaixo e' o cinto de seguranca que sobrou dela."
  #{"encerrada" "nao_realizada" "arquivada"})

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
