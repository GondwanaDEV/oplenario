(ns oplenario.legislativo.logic.turnos
  "PURO: a materia votada em mais de um turno — hoje so' a emenda a Lei Organica (CF art. 29: dois turnos, intersticio
  minimo de dez dias). Os NUMEROS sao da regra (`legislativo.regra_votacao_materia.turnos`/`intersticio_dias`, Inv.4);
  aqui mora so' a conta sobre as votacoes da materia: qual turno cada uma foi, se a materia ja' esta' aprovada e se a
  votacao seguinte pode abrir hoje. Materia sem regra, ou com um turno, nao passa por aqui (ver
  `db/votacao/aprovacao-vigente`).

  As votacoes chegam como `{:id :objeto-tipo :resultado :texto-versao-id :aberta-em :encerrada-em}` (Instant):
  ENCERRADAS, nao corrigidas por outra, do encerramento mais antigo ao mais novo (`db/votacao/votacoes-da-materia`).
  So' a de `objeto-tipo` 'proposicao' e' turno; a redacao final e' outra fase da materia e nao conta turno.

  O intersticio conta DIA CIVIL da Casa (`zona-civil`): 'dez dias depois' e' calendario, nao 240 horas. Do
  encerramento da aprovacao anterior (dia D) a' abertura da votacao seguinte: abre a partir de D + intersticio."
  (:require [oplenario.kernel.tempo :as tempo])
  (:import (java.time LocalDate ZoneId)))

(set! *warn-on-reflection* true)

;; o mesmo fuso literal das bordas do legislativo (diplomat/http/in, contas, catalogo): o dia civil da Casa
(def zona-civil (ZoneId/of "America/Fortaleza"))

(defn dia
  "O dia civil da Casa de um instante (nil -> nil)."
  [instante]
  (some-> instante (tempo/hoje-de zona-civil)))

(defn- turno? [v] (= "proposicao" (:objeto-tipo v)))
(defn- aprovada? [v] (= "aprovada" (:resultado v)))
(defn- rejeitada? [v] (= "rejeitada" (:resultado v)))

(defn com-turno
  "As votacoes com `:turno` nas que sao turno: 1 + as aprovacoes de turno encerradas antes dela, ate' o numero de
  `turnos` da regra (uma rejeicao e' do turno que se votava). A redacao final passa sem `:turno`."
  [turnos votacoes]
  (first
   (reduce (fn [[acc aprovadas] v]
             (if (turno? v)
               [(conj acc (assoc v :turno (min turnos (inc aprovadas)))) (cond-> aprovadas (aprovada? v) inc)]
               [(conj acc v) aprovadas]))
           [[] 0]
           votacoes)))

(defn- a-partir-de
  "O primeiro dia em que a votacao do turno seguinte a' aprovacao `anterior` pode abrir."
  ^LocalDate [intersticio-dias anterior]
  (cond-> ^LocalDate (dia (:encerrada-em anterior))
    intersticio-dias (.plusDays (long intersticio-dias))))

(defn- respeitou-o-intersticio?
  "A votacao `seguinte` abriu no dia permitido depois de `anterior`? Sem instante de abertura: nao (falha fechada)."
  [intersticio-dias [anterior seguinte]]
  (when-let [aberta (dia (:aberta-em seguinte))]
    (not (.isBefore ^LocalDate aberta (a-partir-de intersticio-dias anterior)))))

(defn aprovacao
  "A votacao que COMPLETOU a aprovacao da materia, ou nil. A materia esta' aprovada quando tem `turnos` aprovacoes de
  turno, cada uma aberta depois do intersticio contado do encerramento da anterior, e nenhuma rejeicao de turno. O
  texto aprovado e' o da redacao final aprovada depois do ultimo turno, se houver (mesma precedencia do rito de
  Fortaleza em `db/votacao/aprovacao-vigente`); senao, o do ultimo turno."
  [{:keys [turnos intersticio-dias]} votacoes]
  (let [ts (filterv turno? votacoes)
        aprovadas (filterv aprovada? ts)]
    (when (and (not-any? rejeitada? ts)
               (>= (count aprovadas) turnos)
               (every? (partial respeitou-o-intersticio? intersticio-dias) (partition 2 1 (take turnos aprovadas))))
      (let [ultimo (nth aprovadas (dec turnos))]
        (or (last (filter #(and (= "redacao_final" (:objeto-tipo %)) (aprovada? %)
                                (not (.isBefore ^java.time.Instant (:encerrada-em %)
                                                ^java.time.Instant (:encerrada-em ultimo))))
                          votacoes))
            ultimo)))))

(defn para-abrir
  "O que a votacao de turno que a Mesa quer abrir `hoje` (LocalDate da Casa) seria:
  - `{:turno n}` — pode abrir, e' o n-esimo turno;
  - `{:recusa :intersticio :turno n :anterior n-1 :a-partir-de LocalDate}` — o intersticio ainda corre;
  - `{:recusa :concluida}` — a materia ja' tem todos os turnos aprovados;
  - `{:recusa :rejeitada :turno n}` — a materia foi rejeitada no turno n."
  [{:keys [turnos intersticio-dias]} votacoes ^LocalDate hoje]
  (let [ts (com-turno turnos (filterv turno? votacoes))
        aprovadas (filterv aprovada? ts)
        rejeicao (first (filter rejeitada? ts))
        n (count aprovadas)]
    (cond
      rejeicao {:recusa :rejeitada :turno (:turno rejeicao)}
      (>= n turnos) {:recusa :concluida}
      (zero? n) {:turno 1}
      :else (let [desde (a-partir-de intersticio-dias (peek aprovadas))]
              (if (.isBefore hoje desde)
                {:recusa :intersticio :turno (inc n) :anterior n :a-partir-de desde}
                {:turno (inc n)})))))
