(ns oplenario.motor.demo
  "Demonstração do loop de runtime (espelha motor-dsl/demo.py). Uso: clojure -M:demo"
  (:require [oplenario.motor.nucleo :as nuc]
            [oplenario.motor.verificador :as v]
            [oplenario.motor.catalogo :as cat]
            [oplenario.motor.templates :as tpl]
            [oplenario.motor.runtime :as rt]))

(defn -main [& _]
  (println "== save-time type-check (4 templates do Eixo C) ==")
  (doseq [k ["T1" "T2" "T3" "T4"]]
    (let [r (v/verificar-template (nuc/carregar-envelope (get tpl/CANONICOS k)))]
      (println (format "  %s -> %-9s expressao-ok=%-5s %s"
                       k (:status r) (str (:expressao-ok r))
                       (if (= "VALIDA" (:status r)) (:registry-versao-ref r) (pr-str (:erros r)))))))

  (println "\n== loop de runtime — T1, CMF, competência 2026-05 ==")
  (let [eng (rt/motor (-> (rt/estado)
                          (update :prazos conj (rt/prazo-vigente* "TCE-CE" "SIM_mensal" "2026-05"
                                                                  (rt/ldate 2026 6 30) "IN 04/2019" true)))
                      (rt/ldate 2026 6 19))
        amb {"ente" (rt/ente "cmf" 2700000 43) "competencia" (rt/competencia 2026 5)}
        regra (nuc/carregar-envelope tpl/T1)]
    (rt/avaliar-regra! eng regra cat/CATALOGO-VERSAO amb "competencia" "2026-05")
    (println "  materializou. monitor:" (rt/monitorar eng))
    (swap! eng update-in [:estado :remessas] conj ["cmf" "SIM" "2026-05"])
    (rt/avaliar-regra! eng regra cat/CATALOGO-VERSAO amb "competencia" "2026-05")
    (println "  após envio.   monitor:" (rt/monitorar eng))

    (println "\n== Ofício Circular desliza o prazo (S3) ==")
    (let [eng2 (rt/motor (-> (rt/estado)
                             (update :prazos conj (rt/prazo-vigente* "TCE-CE" "SIM_mensal" "2026-05"
                                                                     (rt/ldate 2026 6 30) "IN 04/2019" true)))
                         (rt/ldate 2026 6 19))]
      (rt/avaliar-regra! eng2 regra cat/CATALOGO-VERSAO amb "competencia" "2026-05")
      (rt/aplicar-circular! eng2 "TCE-CE" "SIM_mensal" "2026-05" (rt/ldate 2026 7 15) "OC 16/2026")
      (println "  re-stamp. monitor:" (rt/monitorar eng2)))

    (println "\n== auditoria append-only (a prova de compliance, Invariante 10) ==")
    (doseq [ev (:eventos @eng)] (println "   -" ev))))
