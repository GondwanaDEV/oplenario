(ns oplenario.paineis.logic.ia
  "PURO: o mes do painel da IA da Casa (B.9) — 'AAAA-MM' (ou o mes corrente) -> o intervalo [inicio, inicio do seguinte)
  do mes civil da Casa. O mesmo fuso do satelite (America/Fortaleza): o gasto e os desfechos contam o mesmo mes."
  (:import (java.time Instant LocalDate YearMonth ZoneId)
           (java.time.format DateTimeParseException)))

(set! *warn-on-reflection* true)

(def ^:private fuso (ZoneId/of "America/Fortaleza"))

(defn mes
  "'AAAA-MM' (nil = o mes corrente em `agora`) -> {:mes \"AAAA-MM\" :desde Instant :ate Instant}, ou
  `:validacao/invalido`."
  [texto ^Instant agora]
  (let [^YearMonth ym (if (nil? texto)
                        (YearMonth/from (.atZone agora fuso))
                        (try (YearMonth/parse texto)
                             (catch DateTimeParseException _
                               (throw (ex-info "mes no formato AAAA-MM" {:tipo :validacao/invalido :campo :mes})))))
        inicio (fn [^YearMonth y] (.toInstant (.atStartOfDay ^LocalDate (.atDay y 1) fuso)))]
    {:mes (str ym) :desde (inicio ym) :ate (inicio (.plusMonths ym 1))}))
