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

;; ---------- fatia 2c: o requerimento COLETIVO ----------

(def CriarPropostaRequerimento
  "Corpo de POST /meu/requerimentos/propostas: o mesmo do protocolo + os COAUTORES convidados (vereador-ids de
  colegas da Casa; ao menos um — sem coautor, o requerimento e' individual e vai direto ao protocolo). O teto
  cobre a Casa inteira; quem e' colega de verdade o controller confere no roster."
  [:map {:closed true}
   [:modelo-id [:string {:min 1 :max 36}]]
   [:campos {:optional true} [:maybe Campos]]
   [:ementa [:string {:min 1 :max 2000}]]
   [:coautores [:vector {:min 1 :max 60} [:string {:min 36 :max 36}]]]])

(def ResponderSubscricao
  "Corpo de POST /meu/requerimentos/propostas/:id/resposta: confirmar (assina) ou recusar."
  [:map {:closed true}
   [:acao [:enum "confirmar" "recusar"]]])
