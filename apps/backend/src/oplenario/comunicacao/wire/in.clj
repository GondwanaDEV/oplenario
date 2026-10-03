(ns oplenario.comunicacao.wire.in
  "Contratos de ENTRADA dos comunicados (ADR-0020, §22.10 wire/in). `:closed true` recusa campo extra: quem envia, a
  Casa, a data e o numero NUNCA vem do corpo. Ids e datas sao :string no fio (o adapters/in coage); a mesma forma serve
  ao catalogo de acoes (com ids ja' UUID — o adapter aceita os dois).")

(def tipos-de-objeto ["sessao" "proposicao" "protocolo"])

(def Destino
  [:map {:closed true}
   [:tipo [:enum "pessoa" "vereador" "setor" "comissao" "todos"]]
   [:alvo-id {:optional true} [:maybe [:or :string :uuid]]]])

(def Objeto
  [:map {:closed true}
   [:tipo (into [:enum] tipos-de-objeto)]
   [:id [:or :string :uuid]]])

(def EnviarComunicado
  [:map {:closed true}
   [:assunto [:string {:min 1 :max 200}]]
   [:corpo [:string {:min 1 :max 20000}]]
   [:exige-ciencia {:optional true} [:maybe :boolean]]
   [:ciencia-ate {:optional true} [:maybe :string]]
   [:substitui-id {:optional true} [:maybe [:or :string :uuid]]]
   [:objeto {:optional true} [:maybe Objeto]]
   [:destinos [:vector {:min 1 :max 20} Destino]]])
