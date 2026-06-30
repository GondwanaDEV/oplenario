(ns oplenario.sessoes.logic
  "PURO: regras e maquina de estados da sessao plenaria (§22.6 eixo A). Sem I/O. Capabilities desacopladas
  do tipo (disciplina §22.6.3 nº3): o tipo e' nome regimental, o comportamento e' atributo com default
  derivado + override auditado. Os vocabularios espelham os CHECK da migration 0026."
  (:require [clojure.string :as str]))

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
  "Fonte de captura do evento. As inferencias (vereador vota/usa tribuna sem check-in) viram evento concreto."
  #{"painel_eletronico" "manual_secretaria" "inferida_por_voto" "inferida_por_tribuna"})

(def tipos-presenca-positiva
  "Tipos cujo ULTIMO evento mantem o vereador PRESENTE; 'saida' e' o unico que tira."
  #{"entrada" "retorno" "mudanca_modalidade"})

(def precedencia-fonte
  "Precedencia em conflito de MESMO instante (§22.6 eixo C): manual_secretaria > painel_eletronico > inferida_*.
  Usada como desempate ao escolher o ultimo evento por vereador (a consulta replica esta ordem em SQL)."
  {"manual_secretaria" 3 "painel_eletronico" 2 "inferida_por_voto" 1 "inferida_por_tribuna" 1})

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
