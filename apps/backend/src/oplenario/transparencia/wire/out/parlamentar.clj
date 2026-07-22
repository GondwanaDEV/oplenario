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

  TRUNCAMENTO: DUAS listas vem truncadas no teto do read-model, e cada uma sai com o seu total.
  `:materias` para no teto 200 e `:materias-total` diz quantas existem no MESMO filtro; `:votos` para no teto
  50 (`db/parlamentar/teto-votos`) e `:votos-total` diz quantos existem. O par lista+total e' obrigatorio,
  nao opcional — e' o que permite a borda dizer 'mostrando 50 de N' em vez de fingir completude (e o que
  impede o `:closed` de derrubar a resposta em acervo grande). O cap de votos e' 4x menor que o de materias e
  um mandato de 4 anos o ultrapassa em meses: sem o total, o `:closed` fecharia a unica via de o cliente
  descobrir o truncamento (achado C-4, revisao Task 4).")

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
  "Item da secao 'como votou' — a lista PARA no teto de 50 do read-model (`db/parlamentar/teto-votos`,
  rigido: o Repo sequer expoe como passar limite maior). Quantos existem no total sai em `:votos-total`, no
  mapa de cima. `:materia-rotulo`/`:materia-ementa` sao `:maybe` de proposito: o read-model faz
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
  cru; a decisao de EXIBI-LO e' da tela (Task 5), que esta' bloqueada ate' o I-5 fechar.

  O QUE `:sessoes-presente` SIGNIFICA (mudou na fatia 1 do I-5 e o NOME do campo carrega a conotacao antiga):
  = COMPARECEU, isto e', TEM REGISTRO DE PRESENCA naquela sessao — quem assinou e saiu no primeiro item da
  pauta conta. NAO e' 'esteve presente o tempo todo': o numerador nao filtra `tipo`, de proposito
  (`db/parlamentar/resumo-presenca` explica por que). E `:sessoes-com-chamada` sao as sessoes com registro de
  presenca de ALGUEM — nao 'sessoes realizadas': sessao sem nenhum check-in nao existe no read-model e some
  dos DOIS lados da fracao. Por isso o rotulo da tela DEVE dizer 'compareceu a X das Y sessoes com registro
  de presenca', e nunca 'esteve presente em X de Y sessoes realizadas' — a pagina e' publica e NOMINAL, e o
  rotulo errado afirma sobre uma pessoa algo que o numero nao sustenta."
  [:map {:closed true}
   [:sessoes-presente :int]
   [:sessoes-com-chamada :int]])

(def PerfilVereadorOut
  "`:nome-parlamentar` e' `:maybe` porque o APELIDO e' opcional no cadastro: `cadastros.vereador
  .nome_parlamentar` e' NULLABLE, `cadastros/adapters/in/vereador` limpa `\"\"` para NULL de proposito e o
  proprio modulo dono declara `[:maybe :string]` na sua rota. Exigir `:string` aqui derrubava o perfil de
  qualquer vereador sem apelido em 500 PERMANENTE — e, pior, reabria o oraculo de existencia que o handler
  fecha (200 = existe com apelido · 500 = existe sem apelido · 404 = nao existe). NAO cair em `:nome-civil`
  aqui: 'apelido' e 'nome civil' sao campos distintos e a UI e' quem decide o fallback — `:nome-civil` e'
  NOT NULL (achado C-1, revisao Task 4)."
  [:map {:closed true}
   [:vereador-id :string]
   [:nome-parlamentar [:maybe :string]]
   [:nome-civil :string]
   [:legislatura [:maybe LegislaturaOut]]
   [:cargo-mesa [:maybe :string]]
   [:comissoes [:vector ComissaoNome]]
   [:materias [:vector MateriaDeAutoriaOut]]
   [:materias-total :int]
   [:normas-de-autoria :int]
   [:votos [:vector VotoPublicoOut]]
   [:votos-total :int]
   [:presenca PresencaOut]
   [:acervo-com-elo-de-autoria-desde :string]])
