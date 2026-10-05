(ns oplenario.identidade.wire.out.acessos
  "Representacao EXTERNA de SAIDA dos acessos concedidos da Casa (ADR-0005, adendo \"Revogar acesso\"): o que o
  `admin_ente` ve em /administracao para revogar. Nome e papel, quando foi concedido, e — se foi revogado — quando, por
  quem (o NOME, nunca o id) e por que. Nunca CPF: `:closed true` torna um `merge` descuidado um erro, nao um vazamento.")

(def AcessoOut
  [:map {:closed true}
   [:identidade-id :string]
   [:nome :string]
   [:papel :string]
   [:concedido-em :string]
   [:revogado-em [:maybe :string]]
   [:revogado-por-nome [:maybe :string]]
   [:motivo [:maybe :string]]])

(def AcessosOut
  [:map {:closed true} [:acessos [:vector AcessoOut]]])

(def RevogacaoOut
  [:map {:closed true}
   [:revogado :boolean]
   [:vinculo-encerrado :boolean]])
