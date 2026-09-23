(ns oplenario.legislativo.wire.out.documento-modelo
  "Representacao EXTERNA de SAIDA do MODELO de documento (§22.10 wire/out, ADR-0001, Onda B Slice 6) — o
  contrato do seletor de modelo na aba 'Gerar documento' (o servidor escolhe o modelo pela `chave`/`nome`
  ativos; o `corpo-template` cru NAO sai aqui — o cliente nao edita placeholders, so' preenche `dados` no
  POST de geracao). `tipo-documento` fica :string (mesmo racional de wire/out/documento). Envelope da lista
  = `{:itens [...]}` (mesmo padrao de ListaProposicoesOut/RelatoresPendentesOut — nao um vetor nu).

  `DocumentoModeloDetalheOut` (Onda B Slice 6, fatia de escrita da aba 'Modelos') e' um contrato IRMAO, nao
  uma extensao do `DocumentoModeloOut` acima: o seletor de 'Gerar documento' nunca precisa do corpo cru nem
  do estado de CAS, e widening o contrato dele so' pra servir a tela de gestao vazaria dado que a leitura
  de selecao nao usa. GET/POST/PATCH /legislativo/documento-modelos(/:id) (a tela de CRUD) usam este.")

(def DocumentoModeloOut
  [:map {:closed true}
   [:id :string]
   [:chave :string]
   [:nome :string]
   [:tipo-documento :string]])

(def ListaModelosOut
  [:map {:closed true}
   [:itens [:sequential DocumentoModeloOut]]])

(def DocumentoModeloDetalheOut
  [:map {:closed true}
   [:id :string]
   [:chave :string]
   [:nome :string]
   [:tipo-documento :string]
   [:corpo-template :string]
   [:ativo :boolean]
   [:lock-version :int]])
