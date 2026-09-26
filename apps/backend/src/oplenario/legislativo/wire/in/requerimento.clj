(ns oplenario.legislativo.wire.in.requerimento
  "Representacao EXTERNA de ENTRADA do requerimento do VEREADOR (§22.10 wire/in, ADR-0001, fatia 2a). O
  vereador escolhe um MODELO da Casa e preenche os CAMPOS dele; autor, data, tipo e o texto final NAO vem do
  corpo (o controller resolve do login, do relogio e do modelo — anti-forja). `:closed true` recusa campo
  extra. Mesmos tetos defensivos de `wire/in/documento` para o mapa de campos (alimenta o renderizador).")

(def ^:private campos-max-chaves 50)
(def ^:private campo-valor-max 2000)

(def ^:private Campos
  [:map-of {:max campos-max-chaves} [:string {:min 1 :max 100}] [:string {:max campo-valor-max}]])

(def PreviaRequerimento
  "Corpo de POST /meu/requerimentos/previa: o modelo e os campos preenchidos (ausente = {})."
  [:map {:closed true}
   [:modelo-id [:string {:min 1 :max 36}]]
   [:campos {:optional true} [:maybe Campos]]])

(def ProtocolarRequerimento
  "Corpo de POST /meu/requerimentos: o mesmo da previa + a `ementa` (o resumo de uma linha que identifica a
  proposicao nas listas e no portal — mesmo teto de wire/in/proposicao)."
  [:map {:closed true}
   [:modelo-id [:string {:min 1 :max 36}]]
   [:campos {:optional true} [:maybe Campos]]
   [:ementa [:string {:min 1 :max 2000}]]])
