(ns oplenario.transparencia.wire.out.dados-abertos
  "Representacao EXTERNA do catalogo de DADOS ABERTOS (§22.10 wire/out, ADR-0001). Cada dataset com o dicionario
  das colunas — o proprio arquivo e' CSV (sem Malli: o wire de um download e' o texto + os headers).")

(def ColunaDadosAbertosOut
  [:map {:closed true}
   [:nome :string]
   [:descricao :string]])

(def DatasetAbertoOut
  "`atualizado-em` nil = o dataset ainda nao tem linha. `caminho` = o arquivo, relativo a
  /portal/casa/{ente}/dados-abertos/."
  [:map {:closed true}
   [:chave :string]
   [:titulo :string]
   [:descricao :string]
   [:arquivo :string]
   [:formato [:enum "csv"]]
   [:linhas :int]
   [:atualizado-em {:optional true} [:maybe :string]]
   [:colunas [:vector ColunaDadosAbertosOut]]])

(def DadosAbertosOut
  [:map {:closed true}
   [:datasets [:vector DatasetAbertoOut]]])
