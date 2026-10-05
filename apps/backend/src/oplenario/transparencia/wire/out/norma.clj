(ns oplenario.transparencia.wire.out.norma
  "Representacao EXTERNA de SAIDA da norma (§22.10 wire/out, ADR-0001) — contrato que o `adapters/out`
  produz. Tudo JSON-serializavel (Instant vira string). Dado publico por natureza (norma promulgada+
  publicada e' ato legal publico).")

(def NormaOut
  [:map {:closed true}
   [:norma-id :string]
   [:proposicao-id :string]
   [:tipo-norma :string]
   [:numero :int]
   [:ano :int]
   [:urn :string]
   [:ementa :string]
   [:publicado-em :string]
   [:veiculo-publicacao :string]
   ;; ha' texto publicado para baixar (`/legislacao/:id/artefato`)? A tela so' mostra o botao quando e' verdadeiro: sem
   ;; isto o cidadao clicava e via um 404 em JSON.
   [:tem-texto :boolean]])

(def NormasOut
  "Resposta de GET /portal/casa/:ente/legislacao: UMA PAGINA do acervo publico as-enacted + o TOTAL (do mesmo filtro,
  sem pagina) e o tamanho da pagina — o cliente sabe quantas existem e quantas paginas faltam, nunca recebe um corte
  em silencio (frente 'truncamento-familia', sitio (c)). `:normas-total` e' o par obrigatorio (mesmo racional de
  MateriasOut/PainelOut); `:pagina`/`:por-pagina` espelham VotacoesPublicasOut."
  [:map {:closed true}
   [:normas [:sequential NormaOut]]
   [:normas-total :int]
   [:pagina :int]
   [:por-pagina :int]])
