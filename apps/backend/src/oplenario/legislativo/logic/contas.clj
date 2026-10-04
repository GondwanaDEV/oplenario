(ns oplenario.legislativo.logic.contas
  "PURO: o julgamento das contas (ADR-0021 Parte B). O ESTADO da prestacao nao e' coluna — e' derivado aqui, das datas
  gravadas e do resultado; daqui saem tambem o motivo em palavras de 'por que a pauta nao aceita o PDL', o numero de
  votos que rejeita o parecer (a MESMA aritmetica do quorum da votacao, `logic/votos-necessarios`) e a frase do
  resultado que a ficha e o portal mostram. Sem I/O: `hoje` (o dia civil da Casa) entra como argumento."
  (:require [oplenario.legislativo.logic :as logic])
  (:import (java.time LocalDate)
           (java.time.format DateTimeFormatter)))

(set! *warn-on-reflection* true)

;; --- vocabularios (espelham os CHECK da migration 20261003000183) ---
(def tipos #{"governo_prefeito" "gestao_camara"})
(def pareceres #{"favoravel" "favoravel_com_ressalvas" "desfavoravel"})
(def resultados #{"parecer_mantido" "parecer_rejeitado"})
(def tipos-documento #{"parecer_previo" "relatorio_tce" "notificacao" "defesa" "decisao_tce" "outro"})

(def documentos-publicos
  "Os documentos que o portal serve: os do TCE. Notificacao, defesa e 'outro' sao do processo interno da Casa."
  #{"parecer_previo" "relatorio_tce" "decisao_tce"})

(def estados
  "Governo: aguardando_notificacao -> prazo_de_defesa -> pronta_para_pauta -> julgada. Mesa: acompanhamento."
  #{"aguardando_notificacao" "prazo_de_defesa" "pronta_para_pauta" "julgada" "acompanhamento"})

;; --- os padroes quando a Casa nao tem linha em `parametro_contas` ([GAP] por LOM: a conferir com o regimento) ---
(def prazo-defesa-dias-padrao 15)
(def prazo-julgamento-dias-padrao 60)

(def chave-da-regra
  "A classe de materia da regra de votacao (`legislativo.regra_votacao_materia`, CF art. 31 §2)."
  "contas_prefeito")

(def quorum-da-regra
  "O quorum da regra (2/3 dos MEMBROS): a base do 'eram precisos N' da ficha antes de haver votacao."
  "maioria_qualificada_2_3")

(def max-bytes-documento (* 10 1024 1024))

(defn governo? [p] (= "governo_prefeito" (:tipo p)))

;; ---------------------------------------------------------------------------------------------------
;; Prazos — congelados no ato que os abre
;; ---------------------------------------------------------------------------------------------------

(defn prazo-julgamento-ate
  "Dia final para julgar: recebimento + N dias corridos (fixado no REGISTRO)."
  ^LocalDate [^LocalDate recebida-em dias]
  (.plusDays recebida-em (long dias)))

(defn prazo-defesa-ate
  "Dia final da defesa: notificacao + N dias corridos (fixado na NOTIFICACAO). Corridos, nao uteis: `[GAP]` por LOM."
  ^LocalDate [^LocalDate notificado-em dias]
  (.plusDays notificado-em (long dias)))

(defn parametros-efetivos
  "A linha da Casa (ou nil) com os padroes no que faltar; `:padrao` diz se a Casa nunca gravou os seus."
  [linha]
  {:prazo-defesa-dias (or (:prazo-defesa-dias linha) prazo-defesa-dias-padrao)
   :prazo-julgamento-dias (or (:prazo-julgamento-dias linha) prazo-julgamento-dias-padrao)
   :padrao (nil? linha)})

;; ---------------------------------------------------------------------------------------------------
;; Estado derivado
;; ---------------------------------------------------------------------------------------------------

(defn estado
  "O estado da prestacao `p` no dia `hoje`. A defesa juntada OU o prazo de defesa vencido (o dia final ainda e' do
  responsavel: vence no dia SEGUINTE) liberam a pauta."
  [p ^LocalDate hoje]
  (cond
    (not (governo? p)) "acompanhamento"
    (some? (:resultado p)) "julgada"
    (nil? (:notificado-em p)) "aguardando_notificacao"
    (or (some? (:defesa-juntada-em p))
        (.isAfter hoje ^LocalDate (:prazo-defesa-ate p))) "pronta_para_pauta"
    :else "prazo_de_defesa"))

(def ^:private ^DateTimeFormatter dd-mm-aaaa (DateTimeFormatter/ofPattern "dd/MM/yyyy"))

(defn data-br [^LocalDate d] (.format d dd-mm-aaaa))

(defn motivo-nao-pautavel
  "Por que a pauta recusa o PDL das contas hoje, em palavras de quem usa a tela; nil = pautavel (B3: a pauta so' aceita
  depois que o prazo de defesa vence ou a defesa e' juntada — bloqueio, nao aviso)."
  [p hoje]
  (case (estado p hoje)
    "pronta_para_pauta" nil
    "aguardando_notificacao" "O responsável ainda não foi notificado."
    "prazo_de_defesa" (str "O prazo de defesa do responsável vai até " (data-br (:prazo-defesa-ate p)) ".")
    "julgada" "As contas deste exercício já foram julgadas."
    "acompanhamento" "As contas da Mesa são só acompanhamento: não vão a votação."))

;; ---------------------------------------------------------------------------------------------------
;; O quorum e o resultado em palavras
;; ---------------------------------------------------------------------------------------------------

(defn necessarios-para-rejeitar
  "ceil(2N/3): quantos votos SIM ('rejeitar o parecer') derrubam o parecer previo numa Casa de `base-membros` membros."
  [base-membros]
  (logic/votos-necessarios quorum-da-regra base-membros))

(defn resultado-da-votacao
  "A votacao pergunta 'Rejeitar o parecer previo?' (sim = rejeitar): aprovada = parecer rejeitado; rejeitada = o parecer
  prevalece (inclusive quando a maioria simples votou sim sem chegar a 2/3)."
  [resultado-votacao]
  (case resultado-votacao
    "aprovada" "parecer_rejeitado"
    "rejeitada" "parecer_mantido"))

(defn- votos [n] (str n (if (= 1 n) " voto" " votos")))

(defn frase-resultado
  "A frase da ficha e do portal: 'O parecer prevalece: 12 votos pela rejeição, eram precisos 14.' Sem votacao registrada
  (julgamento anterior ao sistema), so' a conclusao. nil enquanto nao ha' resultado."
  [resultado sim necessarios]
  (when resultado
    (let [abertura (if (= "parecer_rejeitado" resultado) "O parecer foi rejeitado" "O parecer prevalece")]
      (if (and (some? sim) (some? necessarios))
        (str abertura ": " (votos sim) " pela rejeição, eram precisos " necessarios ".")
        (str abertura ".")))))

;; ---------------------------------------------------------------------------------------------------
;; O PDL e os documentos
;; ---------------------------------------------------------------------------------------------------

(defn ementa-do-pdl
  "A ementa do Projeto de Decreto Legislativo que a prestacao do Prefeito protocola."
  [exercicio]
  (str "Dispõe sobre o julgamento das contas do Prefeito Municipal relativas ao exercício de " exercicio "."))

(defn chave-do-documento
  "A chave no object storage: a convencao `<pasta>/<ente>/...` que a exportacao e o apagamento da Casa descobrem."
  [ente-id prestacao-id documento-id]
  (str "contas/" ente-id "/" prestacao-id "/" documento-id))
