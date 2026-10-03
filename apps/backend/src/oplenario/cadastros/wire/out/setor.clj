(ns oplenario.cadastros.wire.out.setor
  "Contratos de SAIDA dos setores da Casa (ADR-0020 Eixo 1, §22.10 wire/out). O nome de cada membro vem da identidade
  pelo host (cadastros nunca importa identidade); membro cujo vinculo deixou de estar ativo sai com nome nil e
  `ativo` false — a tela mostra e o `admin_ente` decide tirar.")

(def MembroOut
  [:map {:closed true}
   [:identidade-id :string]
   [:nome [:maybe :string]]
   [:ativo :boolean]])

(def SetorOut
  [:map {:closed true}
   [:id :string]
   [:nome :string]
   [:ativo :boolean]
   [:criado-em :string]
   [:membros [:vector MembroOut]]])

(def ListaSetoresOut
  "GET /administracao/setores — envelope (nunca o array cru na raiz)."
  [:map {:closed true}
   [:setores [:vector SetorOut]]])
