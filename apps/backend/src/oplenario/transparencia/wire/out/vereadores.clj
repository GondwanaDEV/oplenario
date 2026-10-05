(ns oplenario.transparencia.wire.out.vereadores
  "Representacao EXTERNA de SAIDA da LISTA PUBLICA dos vereadores em exercicio (§22.10 wire/out, ADR-0001) —
  GET /portal/casa/:ente/vereadores. Contrato de uma rota SEM AUTH: o mapa e' `:closed`, entao um campo que vaze
  do dominio (identidade-id, CPF, e-mail, estado do mandato) derruba a resposta como bug de servidor (500) em vez
  de virar vazamento silencioso. NUNCA acrescentar chave aqui sem responder 'isto pode ir para o portal publico?'.

  So' o que identifica o parlamentar na lista e leva ao perfil: o id do perfil (o mesmo de
  `PerfilVereadorOut/vereador-id`), o nome parlamentar (`:maybe`: apelido ausente e' NULL de primeira classe, e o
  fallback para o nome civil e' decisao de APRESENTACAO), o nome civil, o partido do mandato em exercicio e o cargo
  na Mesa. Quem esta' em exercicio e' decidido no seam do host, nao aqui.")

(def VereadorDaListaOut
  [:map {:closed true}
   [:vereador-id :string]
   [:nome-parlamentar [:maybe :string]]
   [:nome-civil :string]
   [:partido [:maybe :string]]
   [:cargo-mesa [:maybe :string]]])

(def VereadoresOut
  [:map {:closed true}
   [:vereadores [:vector VereadorDaListaOut]]])
