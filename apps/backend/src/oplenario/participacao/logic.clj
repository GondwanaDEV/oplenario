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

;; ---- ciclo VISIVEL do RECURSO (recurso_esic.estado) — enum FIXO em codigo (Slice 2) ----
(def estados-recurso
  "Ciclo do recurso e-SIC visivel ao cidadao. `decidido` e' terminal (trava a linha — trg_recurso_esic_trava_terminal)."
  #{"protocolado" "decidido"})

(def ^:private estados-terminais-recurso
  "Desfecho do recurso: a autoridade ja decidiu (a linha congela)."
  #{"decidido"})

(def ^:private transicoes-recurso
  "Grafo de transicoes LEGAIS do recurso (de -> conjunto de proximos). Terminal nao tem saida."
  {"protocolado" #{"decidido"}
   "decidido"    #{}})

(def ^:private estados-pedido-recorriveis
  "Estados do PEDIDO a partir dos quais o cidadao pode INTERPOR recurso: so os DESFECHOS (respondido|indeferido).
  Recorrer de um pedido ainda em curso (protocolado|em_analise) nao faz sentido — nao ha o que recorrer ainda."
  #{"respondido" "indeferido"})

;; ---- matematica do prazo LAI ----
(def dias-lai-esic
  "Prazo do e-SIC em dias (LAI art. 11 §1º = 20). [GAP]: corridos-vs-uteis nao cravado -> V1 = corridos."
  20)

(def dias-recurso-esic
  "Prazo (em dias) do RECURSO e-SIC — o relogio PROPRIO da instancia recursal.

  [GAP] DE CONTEUDO: a LAI da a autoridade superior/CGU um prazo PROPRIO no julgamento do recurso, MAS o
  numero exato NAO esta cravado nesta fatia (varia por instancia/autoridade e por corridos-vs-uteis, o mesmo
  [GAP] do pedido). O ponto da fatia e' o MECANISMO de RELOGIO INDEPENDENTE (o recurso materializa a 2a linha
  de prazo_ativo, com vence_em proprio), NAO o valor. V1 = DEFAULT DOCUMENTADO HARDCODED [GAP] — constante de
  compile-time, AINDA SEM seam de config por ente/env (ajustar exige deploy; fiar a config = carry). Escolhido
  deliberadamente != 20 do pedido, p/ nao mascarar a independencia dos relogios; confirmar com juridico antes
  de prod. NAO afirmar este numero como lei."
  10)

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

(defn vencido?
  "Predicado PURO de vencimento: o prazo esta vencido em `hoje`? Vencido ⟺ `hoje` ESTRITAMENTE apos `venc`
  (equivalente a `dias-restantes < 0`) — o proprio dia do vencimento NAO vence ('0 = ultimo dia', ainda
  valido). Coerente com `dias-restantes` e com o sweep do compliance (vence_em < data, estrito). `venc` nil =
  NAO vencido (guarda anti-NPE, espelha o guard sec do compliance/logic vencido?, review sec MEDIO-1). O SQL do
  sweep (`vencer-se-pendente!` CAS) e' a FONTE DE VERDADE da transicao; este predicado e' so p/ derivacao/teste."
  [^LocalDate venc ^LocalDate hoje]
  (boolean (and venc (.isAfter hoje venc))))

(defn vence-em-recurso
  "Data de vencimento do prazo do RECURSO a partir do LocalDate do recibo do recurso (marco de inicio do
  RELOGIO PROPRIO da instancia recursal). DIA-CORRIDO `.plusDays dias-recurso-esic`. [GAP] de conteudo
  (ver dias-recurso-esic): o numero e' default documentado, nao lei; o mecanismo (relogio independente do
  pedido) e' o que a fatia crava."
  ^LocalDate [^LocalDate recibo-data]
  (.plusDays recibo-data (long dias-recurso-esic)))

(defn protocolo-esic
  "Numero de PROTOCOLO humano do pedido a partir do (ano, sequencial gapless). Formato estavel
  'ESIC-<ano>-<seq 6 digitos>' (ex.: ESIC-2026-000001). PURO — o sequencial gapless vem do kernel."
  [ano sequencial]
  (format "ESIC-%d-%06d" (long ano) (long sequencial)))

(defn protocolo-recurso
  "Numero de PROTOCOLO humano do recurso a partir do (ano, sequencial gapless). Formato 'REC-<ano>-<seq 6
  digitos>' (ex.: REC-2026-000001) — namespace distinto do pedido (ESIC-) p/ nao colidir na leitura humana."
  [ano sequencial]
  (format "REC-%d-%06d" (long ano) (long sequencial)))

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

(defn validar-estado-recurso
  "Lanca se `v` nao e' estado do recurso (protocolado|decidido)."
  [v] (validar! estados-recurso "estado de recurso e-SIC" v))

(defn terminal-pedido?
  "O estado do pedido e' terminal (o orgao ja respondeu/indeferiu)?"
  [estado] (contains? estados-terminais-pedido estado))

(defn terminal-recurso?
  "O estado do recurso e' terminal (a autoridade ja decidiu)?"
  [estado] (contains? estados-terminais-recurso estado))

(defn transicao-pedido-valida?
  "A transicao `de`->`para` do ciclo do pedido e' legal? (pura — so o grafo fixo). Terminais nao transicionam."
  [de para]
  (contains? (get transicoes-pedido de) para))

(defn transicao-recurso-valida?
  "A transicao `de`->`para` do ciclo do recurso e' legal? (pura — so o grafo fixo). Terminal nao transiciona."
  [de para]
  (contains? (get transicoes-recurso de) para))

(defn pedido-admite-recurso?
  "O pedido no `estado` dado admite a interposicao de recurso? So os DESFECHOS (respondido|indeferido) —
  recorrer de um pedido ainda em curso e' conflito (a borda mapeia p/ 409)."
  [estado]
  (contains? estados-pedido-recorriveis estado))
