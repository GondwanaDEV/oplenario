(ns oplenario.sessoes.logic
  "PURO: regras e maquina de estados da sessao plenaria (§22.6 eixo A). Sem I/O. Capabilities desacopladas
  do tipo (disciplina §22.6.3 nº3): o tipo e' nome regimental, o comportamento e' atributo com default
  derivado + override auditado. Os vocabularios espelham os CHECK da migration 0026.")

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
