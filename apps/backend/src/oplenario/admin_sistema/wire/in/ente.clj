(ns oplenario.admin-sistema.wire.in.ente
  "Representacao externa (wire) do PROVISIONAR CASA (ADR-0016) — o corpo que o console manda. Malli, chaves string
  como chegam do JSON.")

(def ^:private email #"^[^@\s]+@[^@\s]+\.[^@\s]+$")

(def ProvisionarCasaIn
  [:map {:closed true}
   ["nome-oficial" [:string {:min 5 :max 200}]]
   ["nome-curto" {:optional true} [:maybe [:string {:max 80}]]]
   ["uf" [:re #"^[A-Z]{2}$"]]
   ["municipio-ibge" [:re #"^\d{7}$"]]
   ["municipio-nome" [:string {:min 2 :max 120}]]
   ["admin" [:map {:closed true}
             ["nome" [:string {:min 3 :max 200}]]
             ["cpf" [:re #"^\d{11}$"]]
             ["email" [:and [:string {:max 254}] [:re email]]]]]])
