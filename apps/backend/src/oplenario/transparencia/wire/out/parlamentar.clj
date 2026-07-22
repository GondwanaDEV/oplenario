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

  HONESTIDADE DE ACERVO: DUAS constantes de deploy saem em TODA resposta, e cada uma declara o recorte de
  uma projecao SEM REPLAY. `:acervo-com-elo-de-autoria-desde` — o elo autoria->vereador so' existe a partir
  da mig 0063, e materia protocolada antes disso nao aparece em perfil nenhum. `:presenca-projetada-desde`
  (I-5 fatia 6) — `transparencia.presenca_parlamentar` so' existe a partir da mig 0064, entao um mandato
  iniciado antes dessa data tem denominador MENOR que a realidade nos dois lados da fracao. Sem elas a UI
  exibiria um recorte parcial como se fosse o acervo inteiro.

  UMA DATA SOZINHA NAO E' AVALIAVEL PELA TELA (achado da revisao da fatia 6, e e' por isso que
  `PresencaOut` ganhou `:janela-anterior-a-projecao`): este contrato NAO publica nenhuma data da janela de
  exercicio, e a unica que sai — `LegislaturaOut` — e' a do mandato VIGENTE, logo `nil` justamente para
  ex-vereador, que e' o perfil em que o recorte mais importa. Comparar `:presenca-projetada-desde` com o
  periodo exibido era, ate' a revisao, uma obrigacao que a tela nao tinha como cumprir; o booleano derivado
  faz a comparacao no servidor, onde a janela existe.

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
  fracao se tiver o denominador. O servidor NUNCA calcula percentual.

  A FRASE PUBLICADA (I-5 fatia 6 — a que a tela tem de usar, palavra por palavra no sentido): 'compareceu a
  X das Y sessoes com registro de presenca que a Camara realizou ENQUANTO ESTE VEREADOR ESTAVA EM EXERCICIO
  DO MANDATO, descontados os periodos de licenca registrados'. O denominador deixou de ser o do ente inteiro:
  o suplente convocado para 3 sessoes publica '3 de 3', nao '3 de 600'.

  `:janela-de-exercicio-conhecida` E' OBRIGATORIO PARA LER OS OUTROS DOIS. Sem ele, o par de inteiros colapsa
  TRES estados distintos num 0/0 indistinguivel: 'sem periodo de exercicio registrado' (eleito nao empossado,
  vereador sem mandato cadastrado), 'em exercicio e ainda nao houve sessao com chamada' e 'faltou a tudo' —
  e a tela seria obrigada a adivinhar sob o nome de uma pessoa. `false` = a Casa NAO tem periodo de exercicio
  registrado para este parlamentar; a tela DEVE dizer isso, e JAMAIS '0%'.
  LIMITE DECLARADO desse `false` (revisao da fatia 6): ele tambem sai quando HA' mandato registrado mas uma
  licenca consome o stint inteiro (licenca de prazo indeterminado, caminho de primeira classe no cadastro).
  Nesse caso a frase 'a Casa nao tem periodo de exercicio registrado' e' falsa — e' 'a pessoa esta'
  licenciada'. O contrato nao distingue os dois hoje; a decisao esta' presa ao carry da licenca irreversivel.

  `:janela-anterior-a-projecao` E' O QUARTO ESTADO (achado da revisao da fatia 6). `true` = parte do periodo
  de exercicio deste parlamentar e' ANTERIOR a `:presenca-projetada-desde`, ou seja o read-model nao tem
  dado para aquele trecho e o denominador e' MENOR que a realidade — no limite, um mandato inteiramente
  anterior a essa data publica 0/0 COM `:janela-de-exercicio-conhecida true`, que sem este campo a tela leria
  como 'esta' em exercicio e ainda nao houve sessao' ou 'faltou a tudo', as duas falsas, sob o nome de uma
  pessoa. Com ele a tela tem a ressalva de recorte: 'ha' periodo de exercicio anterior aos dados publicados'.
  Quem calcula e' o servidor (`adapters/out/parlamentar`), porque a janela NAO sai neste contrato — publicar
  as datas de exercicio seria expor mandato+licenca em forma direta, e o denominador ja' as expoe demais (ver
  abaixo). Janela vazia -> `false`: nao ha periodo nenhum a declarar.

  O DENOMINADOR E' UM OBSERVAVEL DERIVADO DE MANDATO+LICENCA, e isso e' consequencia aceita, nao acidente
  (revisao da fatia 6). Como ele conta so' as sessoes da janela de exercicio, um observador que leia esta
  rota dia apos dia ve o denominador PARAR de crescer no inicio de uma licenca e voltar a crescer no fim
  dela — ou seja, o INTERVALO da licenca e' derivavel por diferenca, numa rota publica, anonima e nominal.
  O MOTIVO da licenca continua protegido (`cadastros` recusa devolve-lo a leitura publica de proposito, por
  ser dado potencialmente sensivel de saude); o TIMING deixou de estar. E' julgado aceitavel porque licenca
  de vereador e' ato de plenario publicado — mas e' premissa JURIDICO-INSTITUCIONAL, nao tecnica, e entra na
  nota de metodologia que a decisao do I-5 ja' pede antes do deploy.

  O QUE `:sessoes-presente` SIGNIFICA (mudou na fatia 1 do I-5 e o NOME do campo carrega a conotacao antiga):
  = COMPARECEU, isto e', TEM REGISTRO DE PRESENCA naquela sessao — quem assinou e saiu no primeiro item da
  pauta conta. NAO e' 'esteve presente o tempo todo': o numerador nao filtra `tipo`, de proposito
  (`db/parlamentar/resumo-presenca` explica por que). E `:sessoes-com-chamada` sao as sessoes com registro de
  presenca de ALGUEM DENTRO DA JANELA — nao 'sessoes realizadas': sessao sem nenhum check-in nao existe no
  read-model e some dos DOIS lados da fracao. Por isso o rotulo da tela DEVE dizer 'compareceu a X das Y
  sessoes com registro de presenca', e nunca 'esteve presente em X de Y sessoes realizadas' — a pagina e'
  publica e NOMINAL, e o rotulo errado afirma sobre uma pessoa algo que o numero nao sustenta."
  [:map {:closed true}
   [:sessoes-presente :int]
   [:sessoes-com-chamada :int]
   [:janela-de-exercicio-conhecida :boolean]
   [:janela-anterior-a-projecao :boolean]])

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
   [:acervo-com-elo-de-autoria-desde :string]
   [:presenca-projetada-desde :string]])
