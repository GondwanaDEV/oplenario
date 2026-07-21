(ns oplenario.legislativo.events.votacao
  "Eventos de dominio da VOTACAO (eixo G; ADR-0001: events/ = nome + schema Malli do payload). Fonte do PLACAR
  AO VIVO (projetor SSE, §22.6 eixo G): a Mesa abre, os vereadores votam, a Mesa encerra — cada ato vira
  mensagem do canal `sessao/{id}/plenario`. Por isso TODO payload carrega `:sessao-id` (a rota do canal). A
  votacao SEM sessao (ato administrativo, ex.: apreciacao de veto fora de plenario) nao emite estes eventos —
  o guard mora no Repo (sem sessao-id = sem canal). §22.6 — SIGILO: a votacao SECRETA nao expoe o voto
  individual; `voto.registrado` secreto e' um TICK (contador ao vivo). Isso e' MACHINE-ENFORCED no contrato:
  `VotoRegistradoPayload` e' uma UNIAO DISCRIMINADA por `:modalidade` — o ramo 'secreta' nem ADMITE as chaves
  `:vereador-id`/`:voto` (mapa fechado), entao um tick que tente carregar identidade falha em `evento-validado`
  ANTES do outbox. O resultado AGREGADO no encerramento e' publico mesmo na secreta (so o individual e' sigiloso).
  Os vocabularios (modalidade/quorum/objeto/voto) vem de legislativo.logic (fonte unica; espelham os CHECK da
  migration 0021)."
  (:require [oplenario.kernel.eventos :as eventos]
            [oplenario.legislativo.logic :as logic]))

(defn- enum-de [s] (into [:enum] (sort s)))

(def aberta-tipo "votacao.aberta")

(def AbertaPayload
  "Votacao aberta sobre a materia (objeto polimorfico) no contexto da sessao/item de pauta — o painel mostra
  'em votacao'. `modalidade` diz ao cliente se mostra placar nominal (nominal) ou so contador (secreta)."
  [:map {:closed true}
   [:votacao-id :uuid]
   [:sessao-id :uuid]
   [:objeto-tipo (enum-de logic/objetos-votacao)]
   [:objeto-id :uuid]
   [:modalidade (enum-de logic/modalidades-votacao)]
   [:quorum-tipo (enum-de logic/quoruns)]
   [:pauta-item-id {:optional true} [:maybe :uuid]]])

(defn aberta [ente-id payload]
  (eventos/evento-validado AbertaPayload aberta-tipo ente-id payload))

(def voto-registrado-tipo "voto.registrado")

(def VotoRegistradoPayload
  "Voto computado — UNIAO DISCRIMINADA por `:modalidade` (sigilo §22.6 cravado no contrato). NOMINAL: exige
  `:vereador-id` + `:voto` (o placar nominal mostra quem votou o que) + `:ocorrido-em` (Onda E fatia 2 carry,
  mesmo racional de `TransicionouPayload`: o instante REAL do voto no dominio — `legislativo.votos.registrado_em`,
  RETURNING do INSERT — nao o momento em que um consumer eventualmente PROJETA o evento; o perfil publico do
  vereador em `transparencia` ordena 'como votou' por isto). `:proposicao-id` (revisao Task 2, achado I-2) e'
  OPCIONAL/nullable: so' carrega valor quando o objeto votado (polimorfico — `votacoes.objeto_tipo`) E' uma
  proposicao; para emenda/parecer/requerimento/redacao_final fica nil — o elo 'como votou' -> materia so' faz
  sentido no primeiro caso. SECRETA: TICK — o mapa fechado NAO admite `:vereador-id`/`:voto`; o cliente so
  incrementa o contador de votos registrados. (Voto SIMBOLICO/aclamacao nao registra individual — encerra com
  resultado explicito; por isso so 'nominal'/'secreta' aqui.)"
  [:multi {:dispatch :modalidade}
   ["nominal" [:map {:closed true}
               [:votacao-id :uuid]
               [:sessao-id :uuid]
               [:modalidade [:= "nominal"]]
               [:vereador-id :uuid]
               [:voto (enum-de logic/tipos-voto)]
               [:proposicao-id {:optional true} [:maybe :uuid]]
               [:ocorrido-em :string]]]
   ["secreta" [:map {:closed true}
               [:votacao-id :uuid]
               [:sessao-id :uuid]
               [:modalidade [:= "secreta"]]]]])

(defn voto-registrado [ente-id payload]
  (eventos/evento-validado VotoRegistradoPayload voto-registrado-tipo ente-id payload))

(def encerrada-tipo "votacao.encerrada")

(def EncerradaPayload
  "Votacao encerrada — o resultado AGREGADO (publico mesmo na secreta). `:modalidade` torna o evento
  auto-descritivo (um consumidor que reconecta e ve so o encerramento sabe como rotular o placar). Totais
  ausentes na modalidade 'simbolica' (aclamacao, sem apuracao individual): o cliente mostra so o resultado."
  [:map {:closed true}
   [:votacao-id :uuid]
   [:sessao-id :uuid]
   [:resultado [:enum "aprovada" "rejeitada"]]
   [:modalidade {:optional true} (enum-de logic/modalidades-votacao)]
   [:total-sim {:optional true} [:maybe :int]]
   [:total-nao {:optional true} [:maybe :int]]
   [:total-abstencao {:optional true} [:maybe :int]]
   [:base-membros {:optional true} [:maybe :int]]])

(defn encerrada [ente-id payload]
  (eventos/evento-validado EncerradaPayload encerrada-tipo ente-id payload))
