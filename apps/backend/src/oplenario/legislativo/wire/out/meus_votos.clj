(ns oplenario.legislativo.wire.out.meus-votos
  "Representacao EXTERNA de SAIDA dos votos do proprio vereador (§22.10 wire/out, ADR-0001) — GET /meu/votos, a tela
  'Minha atuacao'. E' uma superficie AUTENTICADA e do proprio vereador: carrega o voto de sessao secreta ou fechada ao
  publico, que o portal nao publica. Por isso e' um contrato proprio, nunca o `PerfilVereadorOut` publico — e por isso
  cada voto diz o que o portal faz com ele (`portal`).")

(def MeuVotoOut
  "Um voto nominal do vereador. `portal`: `publico` (sessao publica — o cidadao ve no portal), `sessao-fechada`
  (sessao secreta ou fechada ao publico — so' o vereador ve) ou `sem-sessao` (votacao fora de plenario — o portal nao
  publica). `anulada`: a votacao foi desfeita (correcao = nova votacao); o voto aparece, mas nao entra nos numeros.
  A materia (`materia-*`) e' nula quando a votacao nao e' sobre a propria proposicao (parecer, emenda, requerimento)."
  [:map {:closed true}
   [:votacao-id :string]
   [:voto :string]
   [:registrado-em :string]
   [:anulada :boolean]
   [:portal [:enum "publico" "sessao-fechada" "sem-sessao"]]
   [:materia-tipo [:maybe :string]]
   [:materia-ano [:maybe :int]]
   [:materia-sequencial [:maybe :int]]
   [:materia-ementa [:maybe :string]]])

(def MeusVotosPorOpcaoOut
  "Os votos de votacao NAO anulada, por opcao."
  [:map {:closed true}
   [:sim :int]
   [:nao :int]
   [:abstencao :int]])

(def MeusVotosOut
  "A resposta de GET /meu/votos. `votos` para no teto do modulo (os mais recentes); `votos-total` e' tudo o que o
  vereador votou (inclusive anulada) — quando maior que a lista, a tela diz que mostra os mais recentes. Ator sem
  cadastro de vereador: `vereador-id` nulo e tudo zerado, nunca erro."
  [:map {:closed true}
   [:vereador-id [:maybe :string]]
   [:votos [:vector MeuVotoOut]]
   [:votos-total :int]
   [:votos-por-opcao MeusVotosPorOpcaoOut]])
