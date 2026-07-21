(ns oplenario.transparencia.wire.out.parlamentar
  "Representacao EXTERNA de SAIDA do PERFIL PUBLICO do vereador (§22.10 wire/out, ADR-0001; Onda E fatia 2,
  Task 4) — contrato que o `adapters/out/parlamentar` produz. Tudo JSON-serializavel (Instant vira string).

  E' o contrato de uma rota SEM AUTH: o que entra aqui e' publicavel para qualquer pessoa. Por isso o mapa e'
  `:closed` em TODOS os niveis — um campo novo que vaze do dominio (ente-id, identidade-id, mandato-id,
  vereador-id de terceiro num voto) derruba a resposta como bug de servidor (500) em vez de virar vazamento
  silencioso. NUNCA acrescentar chave aqui sem responder 'isto pode ir para o portal publico?'.

  SIGILO DE VOTO: `VotoPublicoOut` so' existe porque o read-model so' contem voto NOMINAL — o ramo `secreta`
  de `VotoRegistradoPayload` nem carrega identidade, entao nao ha o que filtrar aqui. Este contrato nao e' o
  guarda do sigilo (o schema do evento e'), mas tambem nao o reabre.

  HONESTIDADE DE ACERVO: `:acervo-com-elo-de-autoria-desde` sai em TODA resposta. O elo autoria->vereador so'
  existe a partir da mig 0063 e a projecao NAO tem replay: materia protocolada antes disso nao aparece em
  perfil nenhum. Sem esta data a UI exibiria uma lista incompleta como se fosse o acervo inteiro.

  TRUNCAMENTO: `:materias` vem truncada no teto do read-model (200) e `:materias-total` diz quantas existem
  no MESMO filtro. O par e' obrigatorio, nao opcional — e' o que permite a borda dizer 'mostrando 200 de N'
  em vez de fingir completude (e o que impede o `:closed` de derrubar a resposta em acervo grande).")

(def ComissaoNome
  "Comissao no perfil publico e' so' o NOME. Cargo dentro da comissao e' informacao de gabinete, nao de
  portal; `:cargo-mesa` (o unico cargo publicamente relevante) sai em campo proprio, derivado da entrada de
  tipo 'mesa' — mesma unica-fonte de `cadastros/adapters/out/vereador`."
  :string)

(def LegislaturaOut
  "Legislatura do mandato VIGENTE. Numeros crus, sem formatacao — rotulo ('19ª Legislatura (2025–2028)') e'
  decisao de apresentacao, e formatar aqui congelaria locale no servidor. `nil` quando nao ha mandato
  vigente na data (suplente fora de exercicio, mandato encerrado)."
  [:map {:closed true}
   [:numero :int]
   [:ano-inicio :int]
   [:ano-fim :int]])

(def MateriaDeAutoriaOut
  "Item da lista 'materias de autoria'. Subconjunto DELIBERADO de `MateriaOut`: sem `:urn-lex`, `:autor-tipo`
  e `:autor-texto` — num perfil de vereador a autoria e' o proprio contexto da lista, repeti-la por linha
  so' aumenta a superficie publica."
  [:map {:closed true}
   [:proposicao-id :string]
   [:tipo :string]
   [:ano :int]
   [:sequencial :int]
   [:ementa :string]
   [:estado :string]])

(def VotoPublicoOut
  "Item da secao 'como votou'. `:materia-rotulo`/`:materia-ementa` sao `:maybe` de proposito: o read-model faz
  LEFT JOIN com `transparencia.materia` e a materia pode nao ter sido projetada (gap de projecao, ou objeto de
  votacao que nao e' proposicao). Voto sem rotulo ainda e' informacao publica legitima — melhor exibir 'voto
  em materia nao publicada' que omitir o voto."
  [:map {:closed true}
   [:votacao-id :string]
   [:voto :string]
   [:ocorrido-em :string]
   [:materia-rotulo [:maybe :string]]
   [:materia-ementa [:maybe :string]]])

(def PresencaOut
  "Os DOIS numeros, nunca um percentual. Um '100%' sobre 1 sessao mente por omissao — a UI so' pode montar a
  fracao se tiver o denominador. CARRY conhecido (I-5 da Task 2): o denominador conta as sessoes do ENTE, nao
  as do MANDATO deste vereador, logo suplente/recem-empossado recebe uma fracao injusta. O numero sai daqui
  cru; a decisao de EXIBI-LO e' da tela (Task 5), que esta' bloqueada ate' o I-5 fechar."
  [:map {:closed true}
   [:sessoes-presente :int]
   [:sessoes-com-chamada :int]])

(def PerfilVereadorOut
  [:map {:closed true}
   [:vereador-id :string]
   [:nome-parlamentar :string]
   [:nome-civil :string]
   [:legislatura [:maybe LegislaturaOut]]
   [:cargo-mesa [:maybe :string]]
   [:comissoes [:vector ComissaoNome]]
   [:materias [:vector MateriaDeAutoriaOut]]
   [:materias-total :int]
   [:normas-de-autoria :int]
   [:votos [:vector VotoPublicoOut]]
   [:presenca PresencaOut]
   [:acervo-com-elo-de-autoria-desde :string]])
