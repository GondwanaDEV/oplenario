(ns oplenario.legislativo.wire.in.parecer
  "Representacao EXTERNA de ENTRADA do EDITOR de parecer (§22.10 wire/in, ADR-0001, Onda B Slice 5) — os
  corpos de PATCH (salvar rascunho) e POST .../emissao (emitir). `:closed true` recusa campo extra; o
  tenant/autor NAO vem do corpo (vem do ator resolvido na auth); parecer-id/template-id vem do path/estado
  ja' carregado, nunca do cliente.")

(def SalvarRascunhoParecer
  "Corpo de PATCH /legislativo/pareceres/:id. `relatorio`/`analise` sao STRINGS (\"\" se ausentes — o
  adapters/in aplica o default), nunca opcionais no wire: o editor sempre manda as duas secoes, mesmo
  vazias (o form da tela nao distingue 'nao mandou' de 'mandou vazio')."
  [:map {:closed true}
   [:relatorio {:optional true} [:maybe :string]]
   [:analise {:optional true} [:maybe :string]]])

(def EmitirParecer
  "Corpo de POST /legislativo/pareceres/:id/emissao. `voto-relator` e' OBRIGATORIO e nao-branco (vocabulario
  regimental ABERTO, §22.4.4 — sem enum aqui, como em models/parecer)."
  [:map {:closed true}
   [:voto-relator [:string {:min 1}]]])
