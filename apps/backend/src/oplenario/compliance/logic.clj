(ns oplenario.compliance.logic
  "PURO (§22.10 logic): o CICLO da obrigacao de compliance (enum FIXO em codigo, §22.7.7) + a costura
  remessa->obrigacao (§22.7.8: 'aceita' CUMPRE; 'rejeitada' NAO). A avaliacao da DSL e' do
  `oplenario.motor` (disciplina 5); aqui mora so o ciclo PERSISTIDO que o compliance reconcilia contra
  o veredito que o motor da. Sem banco, sem motor — funcoes puras + vocabularios (fonte unica dos enums;
  os CHECK da mig 0005 / a logica de runtime do motor espelham)."
  (:import (java.time LocalDate)))

(set! *warn-on-reflection* true)

;; ---- vocabularios (enum FIXO em codigo — §22.7.7; nao e' template) ----
(def fases-obrigacao
  "Ciclo da obrigacao materializada (prazo_dominio_ativo.estado)."
  #{"pendente" "cumprida" "vencida" "dispensada" "cancelada"})

(def ^:private fases-terminais-auto
  "Fases que a AVALIACAO AUTOMATICA nao move: cumprida (nao reverte) + dispensada/cancelada (atos
  administrativos). `vencida` NAO e' terminal — uma obrigacao vencida ainda CUMPRE se virar conforme
  (cumprimento tardio), espelhando o runtime do motor."
  #{"cumprida" "dispensada" "cancelada"})

(def vereditos #{"conforme" "nao_conforme" "inaplicavel"})
(def origens-avaliacao #{"evento" "sweep" "sob_demanda"})
(def severidades #{"bloqueante" "aviso"})

;; ---- ciclo de vida da remessa (enum FIXO em codigo — §22.7.8; nao e' template, como o ciclo da
;;      obrigacao e as emendas §22.4 eixo D). Universal entre camaras/regimes -> mora aqui, nao em dado. ----
(def estados-remessa
  "Ciclo do artefato de remessa (remessa_gerada.estado): rascunho -> validada -> submetida ->
  {aceita | rejeitada}. Re-emissao apos rejeicao = NOVA versao (nao reabre o ciclo da anterior)."
  #{"rascunho" "validada" "submetida" "aceita" "rejeitada"})

(def ^:private transicoes-remessa
  "Grafo de transicoes LEGAIS do ciclo (de -> conjunto de proximos). Aceita/rejeitada nao tem saida
  (terminais; reenvio = nova versao). Sem retorno no ciclo (a auditoria do ciclo nao se reescreve)."
  {"rascunho"  #{"validada"}
   "validada"  #{"submetida"}
   "submetida" #{"aceita" "rejeitada"}
   "aceita"    #{}
   "rejeitada" #{}})

(def ^:private estados-terminais-remessa
  "Estados terminais do ciclo: o TCE ja' respondeu. Reenvio apos rejeicao = nova VERSAO, nunca muta esta."
  #{"aceita" "rejeitada"})

;; ---- estado-de-submissao da remessa que CUMPRE a obrigacao (§22.7.8) ----
(def ^:private estado-remessa-cumpre "aceita")

(defn- validar! [conjunto rotulo v]
  (when-not (contains? conjunto v)
    (throw (ex-info (str rotulo " invalido: " (pr-str v)) {:valor v :validos conjunto})))
  nil)

(defn validar-fase
  "Guarda de profundidade: lanca se `v` nao e' uma fase do ciclo (pendente|cumprida|vencida|dispensada|cancelada)."
  [v] (validar! fases-obrigacao "fase de obrigacao" v))
(defn validar-veredito
  "Guarda de profundidade: lanca se `v` nao e' um veredito (conforme|nao_conforme|inaplicavel)."
  [v] (validar! vereditos "veredito" v))
(defn validar-origem
  "Guarda de profundidade: lanca se `v` nao e' uma origem de avaliacao (evento|sweep|sob_demanda)."
  [v] (validar! origens-avaliacao "origem de avaliacao" v))
(defn validar-severidade
  "Guarda de profundidade: lanca se `v` nao e' uma severidade (bloqueante|aviso)."
  [v] (validar! severidades "severidade" v))

(defn normalizar-resumo
  "Read-model do painel (§16.11): pares crus do db `[{:estado :total}...]` -> mapa keyword 0-FILADO p/ as 5
  fases (pendente|cumprida|vencida|dispensada|cancelada). PURO — garante que o placar sempre tem as cinco
  chaves (mesmo zeradas; o SQL so devolve estados COM linha). Estado fora do enum LANCA via validar-fase
  (guarda de profundidade — como os demais validar-* deste ns; linha corrompida nao envenena o painel)."
  [pares]
  (reduce (fn [acc {:keys [estado total]}]
            (validar-fase estado)
            (assoc acc (keyword estado) (long total)))
          (zipmap (map keyword fases-obrigacao) (repeat 0))
          pares))

(defn vencido?
  "A obrigacao esta vencida em `agora`? Compliance opera em DATAS (LocalDate), nao instantes — o
  vencimento e' por dia civil. Estritamente APOS o vence_em (o proprio dia do vencimento nao vence).
  `vence-em` nil = sem prazo conhecido -> NAO vencida (guarda anti-NPE; o caller nao deve abortar a
  tx e perder a prova de compliance so porque um prazo veio ausente — review sec MEDIO-1)."
  [^LocalDate vence-em ^LocalDate agora]
  (boolean (and vence-em (.isAfter agora vence-em))))

(defn proxima-fase
  "A transicao do ciclo (pura), dado o estado atual + o veredito do motor (conforme?) + se passou do
  prazo (vencido?). Reconcilia o veredito FRESCO do motor contra o estado PERSISTIDO:
    - terminal-auto (cumprida/dispensada/cancelada): nao muda;
    - conforme: cumprida (mesmo se ja vencida = cumprimento tardio);
    - nao-conforme + vencido: vencida;
    - nao-conforme dentro do prazo: pendente."
  [fase-atual conforme? apos-prazo?]
  (cond
    (contains? fases-terminais-auto fase-atual) fase-atual
    conforme?   "cumprida"
    apos-prazo? "vencida"
    :else       "pendente"))

(defn cumpre-obrigacao?
  "Costura remessa->obrigacao (§22.7.8): so a remessa em 'aceita' CUMPRE a obrigacao. Rejeicao /
  estados intermediarios NAO cumprem."
  [estado-remessa]
  (= estado-remessa estado-remessa-cumpre))

(defn validar-estado-remessa
  "Guarda de profundidade: lanca se `v` nao e' um estado do ciclo de remessa
  (rascunho|validada|submetida|aceita|rejeitada)."
  [v] (validar! estados-remessa "estado de remessa" v))

(defn transicao-remessa-valida?
  "A transicao `de`->`para` do ciclo da remessa e' legal? (rascunho->validada->submetida->aceita|rejeitada).
  Terminais (aceita/rejeitada) nao transicionam; sem retorno no ciclo. Pura (so o grafo fixo)."
  [de para]
  (contains? (get transicoes-remessa de) para))

(defn remessa-terminal?
  "O estado da remessa e' terminal (o TCE respondeu)? Reenvio apos rejeicao = nova VERSAO."
  [estado-remessa]
  (contains? estados-terminais-remessa estado-remessa))
