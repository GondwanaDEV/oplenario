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

;; ---- ADR-0018 (fatia 1): suspender, reativar, iniciar o encerramento ----

(def ^:private Justificativa [:string {:min 10 :max 2000}])

(def PedirSuspensaoIn
  "Eixo 1a: o motivo e' da lista fechada; a justificativa e' obrigatoria."
  [:map {:closed true}
   ["motivo" [:enum "inadimplencia" "pedido_da_casa" "ordem_judicial" "incidente_de_seguranca"]]
   ["justificativa" Justificativa]])

(def IniciarEncerramentoIn
  "Eixo 4.1: o encerramento e' pedido da Casa (oficio) ou nosso (fim de contrato)."
  [:map {:closed true}
   ["origem" [:enum "pedido_da_casa" "fim_de_contrato"]]
   ["justificativa" Justificativa]])

(def DecisaoIn
  "Aprovar ou recusar o pedido: a justificativa e' opcional (a do pedido ja' diz o porque)."
  [:map {:closed true}
   ["justificativa" {:optional true} [:maybe [:string {:max 2000}]]]])

(def ReativarIn
  "Eixo 5: um operador reativa, com motivo (ex.: pagamento regularizado)."
  [:map {:closed true}
   ["justificativa" Justificativa]])

;; ---- ADR-0018 (fatia 2): encerrar ----

(def ConfirmarRecebimentoIn
  "O admin_ente confirma o recebimento DIGITANDO de volta (ou colando) o codigo que a tela mostra: confirma o arquivo
  certo, nao um clique distraido."
  [:map {:closed true}
   ["sha256" [:re #"^[0-9a-f]{64}$"]]])

(def OficioIn
  "O operador registra a confirmacao recebida por oficio: o texto do oficio (numero, data, quem assina)."
  [:map {:closed true}
   ["texto" [:string {:min 10 :max 2000}]]])

(def DestinoAcervoIn
  "Para onde foi o acervo publico (Eixo 4.4 c): https, ou null para tirar."
  [:map {:closed true}
   ["url" [:maybe [:and [:string {:max 500}] [:re #"^https://[^\s]+$"]]]]])

(def PedirApagamentoIn
  [:map {:closed true}
   ["justificativa" Justificativa]])
