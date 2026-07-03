(ns oplenario.participacao.logic
  "PURO (§22.10 logic): o vocabulario do e-SIC (ciclo VISIVEL ao cidadao do pedido + ciclo do prazo,
  forma disc.6) + a matematica do prazo LAI + read-derivations. Sem banco, sem motor — funcoes puras +
  vocabularios (fonte unica dos enums; os CHECK da mig 0039 espelham). Espelha a disciplina de
  compliance/logic (enum FIXO em codigo, nao template).

  Prazo do e-SIC = 20 dias (LAI 12.527/2011, art. 11 §1º). [GAP] DE CONTEUDO: a LAI nao cravou
  'corridos vs uteis' -> V1 conta DIA-CORRIDO (LocalDate.plusDays), NAO afirma dias uteis nem adiciona
  feriados. Quando o conteudo resolver p/ dias uteis, a matematica migra p/ um `kernel/calendario.clj`
  compartilhado (disc.5; toca o motor — fora do caminho critico do e-SIC)."
  (:import (java.time LocalDate)))

(set! *warn-on-reflection* true)

;; ---- ciclo VISIVEL ao cidadao (pedido_esic.estado) — enum FIXO em codigo ----
(def estados-pedido
  "Ciclo do pedido e-SIC visivel ao cidadao. `respondido`/`indeferido` sao terminais (trava a linha)."
  #{"protocolado" "em_analise" "respondido" "indeferido"})

(def ^:private estados-terminais-pedido
  "Desfechos do pedido: o orgao ja respondeu (a linha congela — trg imut_trava_estado_terminal)."
  #{"respondido" "indeferido"})

(def ^:private transicoes-pedido
  "Grafo de transicoes LEGAIS (de -> conjunto de proximos). Terminais nao tem saida."
  {"protocolado" #{"em_analise" "respondido" "indeferido"}
   "em_analise"  #{"respondido" "indeferido"}
   "respondido"  #{}
   "indeferido"  #{}})

;; ---- ciclo do PRAZO (prazo_ativo.estado) — forma disc.6 (polimorfico, o ciclo E a maquina) ----
(def estados-prazo
  "Ciclo do prazo materializado (prazo_ativo.estado), forma disc.6 (espelha compliance/prazo_dominio_ativo).
  `cumprida` disparada quando o agregado chega ao desfecho respondido; `vencida` pelo sweep (F6.3)."
  #{"pendente" "cumprida" "vencida" "dispensada" "cancelada"})

(def objeto-tipos-prazo
  "Objeto polimorfico que um prazo pode monitorar (prazo_ativo.objeto_tipo). Recurso e solicitacao de
  titular ganham relogio proprio nas fatias seguintes; o pedido e-SIC e' o desta fatia."
  #{"pedido_esic" "recurso_esic" "solicitacao_titular"})

;; ---- matematica do prazo LAI ----
(def dias-lai-esic
  "Prazo do e-SIC em dias (LAI art. 11 §1º = 20). [GAP]: corridos-vs-uteis nao cravado -> V1 = corridos."
  20)

(defn vence-em
  "Data de vencimento do prazo LAI a partir do LocalDate do recibo (marco de inicio do relogio). DIA-CORRIDO:
  `.plusDays 20` sobre a data civil do recibo. [GAP] de conteudo: NAO afirma dias uteis nem adiciona feriados."
  ^LocalDate [^LocalDate recibo-data]
  (.plusDays recibo-data (long dias-lai-esic)))

(defn dias-restantes
  "Read-derivation PURA (§ Arch B: monitoramento = derivacao sobre vence_em, sem cross-schema): diferenca
  em dias civis entre `vence-em` e `hoje`. Positivo = ainda ha prazo; 0 = ultimo dia; negativo = vencido
  (quem transiciona p/ 'vencida' e' o sweep de F6.3 — aqui e' leitura pura, como motor/runtime monitorar)."
  [^LocalDate venc ^LocalDate hoje]
  (- (.toEpochDay venc) (.toEpochDay hoje)))

(defn protocolo-esic
  "Numero de PROTOCOLO humano do pedido a partir do (ano, sequencial gapless). Formato estavel
  'ESIC-<ano>-<seq 6 digitos>' (ex.: ESIC-2026-000001). PURO — o sequencial gapless vem do kernel."
  [ano sequencial]
  (format "ESIC-%d-%06d" (long ano) (long sequencial)))

;; ---- validadores (guardas de profundidade; espelham os demais validar-* do projeto) ----

(defn- validar! [conjunto rotulo v]
  (when-not (contains? conjunto v)
    (throw (ex-info (str rotulo " invalido: " (pr-str v)) {:valor v :validos conjunto})))
  nil)

(defn validar-estado-pedido
  "Lanca se `v` nao e' estado do pedido (protocolado|em_analise|respondido|indeferido)."
  [v] (validar! estados-pedido "estado de pedido e-SIC" v))

(defn validar-estado-prazo
  "Lanca se `v` nao e' estado do prazo (pendente|cumprida|vencida|dispensada|cancelada)."
  [v] (validar! estados-prazo "estado de prazo" v))

(defn validar-objeto-tipo-prazo
  "Lanca se `v` nao e' objeto_tipo de prazo (pedido_esic|recurso_esic|solicitacao_titular)."
  [v] (validar! objeto-tipos-prazo "objeto_tipo de prazo" v))

(defn terminal-pedido?
  "O estado do pedido e' terminal (o orgao ja respondeu/indeferiu)?"
  [estado] (contains? estados-terminais-pedido estado))

(defn transicao-pedido-valida?
  "A transicao `de`->`para` do ciclo do pedido e' legal? (pura — so o grafo fixo). Terminais nao transicionam."
  [de para]
  (contains? (get transicoes-pedido de) para))
