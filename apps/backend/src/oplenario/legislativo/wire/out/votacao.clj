(ns oplenario.legislativo.wire.out.votacao
  "Representacao EXTERNA de SAIDA da votacao ao vivo (§22.10 wire/out, ADR-0001) — os recibos das acoes de
  dirigir a votacao, dos quais o Eixo 8 gera os tipos TS. Tudo serializavel a JSON (uuid -> string). NAO expoe
  campos internos — a defesa anti-vazamento mora no adapters/out. EXCECAO: `AberturaOut` expoe `lock-version`
  (ledger de prontidao Fase 8 achado #2) — aqui o token de CAS nao e' interno, e' PARTE DO PROTOCOLO de
  `POST .../encerramento` (que o exige no corpo). `ObjetoVotacaoOut` (fatia 'demo-tres-consertos' #2) e' a
  rota GET de detalhe que faltava — mas e' de EXIBICAO, nunca carrega `lock-version`: continua sendo o
  recibo de abertura, e so ele, a UNICA fonte do token de CAS. Mesmo desenho de
  `sessoes.wire.out/JustificativaAbertaOut` e de `legislativo.wire.out.documento`/`.../parecer`."
  (:require [oplenario.kernel.malli :as km]
            [oplenario.legislativo.logic :as logic]))

(def AberturaOut
  "Recibo de POST .../votacoes (201): o id da votacao recem-aberta + o estado + o `lock-version` (sempre 0
  numa votacao recem-nascida) que `POST .../encerramento` exige no corpo — sem este campo o cliente nao tem
  como montar essa segunda chamada (ledger de prontidao Fase 8 achado #2)."
  [:map {:closed true}
   [:id :string]
   [:estado (km/enum-de logic/estados-votacao)]
   [:lock-version :int]])

(def VotoOut
  "Recibo de POST .../votos (201): o id do voto registrado. NOMINAL e SECRETO compartilham a forma (sem
  identidade no recibo — sigilo §22.6)."
  [:map {:closed true}
   [:id :string]])

(def EncerramentoOut
  "Recibo de POST .../encerramento (200): o snapshot apurado (estado + resultado + totais + base). Totais/base
  ausentes na modalidade 'simbolica' (sem apuracao individual)."
  [:map {:closed true}
   [:id :string]
   [:estado (km/enum-de logic/estados-votacao)]
   [:resultado {:optional true} [:maybe [:enum "aprovada" "rejeitada"]]]
   [:total-sim {:optional true} [:maybe :int]]
   [:total-nao {:optional true} [:maybe :int]]
   [:total-abstencao {:optional true} [:maybe :int]]
   [:base-membros {:optional true} [:maybe :int]]])

(def ProposicaoResumoObjetoVotacaoOut
  "O resumo MINIMO pra identificar a materia na tela — nao a ficha completa (sem autor/estado/etc, que o
  vereador votando nao precisa e a rota `secretario`-only ja' cobre pra quem tem esse papel)."
  [:map {:closed true}
   [:tipo :string]
   [:ano :int]
   [:sequencial :int]
   [:ementa :string]])

(def ObjetoVotacaoOut
  "Recibo de GET .../votacoes/:votacao-id (200): O QUE esta em votacao (fatia 'demo-tres-consertos' #2).
  `:proposicao` resolve quando `objeto-tipo` e' `proposicao`/`redacao_final` (o `objeto-id` da votacao E' a
  propria materia, ver db/votacao.clj/objetos-que-carregam-a-materia); `nil` p/ `emenda`/`parecer`/
  `requerimento` — resolve-los por completo e' escopo maior, registrado e nao feito aqui. O cliente usa
  `objeto-tipo` pra montar um rotulo honesto do TIPO quando `:proposicao` vem nil, nunca titulo vazio."
  [:map {:closed true}
   [:objeto-tipo (km/enum-de logic/objetos-votacao)]
   [:proposicao [:maybe ProposicaoResumoObjetoVotacaoOut]]])

(def VotoNominalDetalheOut
  "Um voto nominal (fatia 'demo-tres-consertos' #2b) — a MESMA forma que `voto.registrado` nominal levaria
  ao vivo (vereador-id+voto), pra `votacao-aberta` devolver a lista inteira quando um cliente conecta sem
  ter visto os eventos individuais."
  [:map {:closed true}
   [:vereador-id :string]
   [:voto (km/enum-de logic/tipos-voto)]])

(def SemApuracaoIndividualVotacaoAbertaOut
  "Forma compartilhada por 'secreta' e 'simbolica' em VotacaoAbertaOut — NENHUMA das duas expoe voto
  individual (sigilo §22.6 pra secreta; simbolica simplesmente nao registra voto individual algum,
  `controllers/registrar-voto`). `:votos-registrados` e' o MESMO tick anonimo que `voto.registrado`
  secreto ja' expoe ao vivo — NUNCA uma apuracao por valor (sim/nao/abstencao), que vazaria MAIS do que o
  proprio stream vivo vaza antes do encerramento."
  [:map {:closed true}
   [:votacao-id :string]
   [:modalidade (km/enum-de #{"secreta" "simbolica"})]
   [:objeto-tipo (km/enum-de logic/objetos-votacao)]
   [:objeto-id :string]
   [:proposicao [:maybe ProposicaoResumoObjetoVotacaoOut]]
   [:votos-registrados :int]])

(def VotacaoAbertaOut
  "Recibo de GET .../votacao-aberta (200): QUAL votacao esta aberta na sessao, pra RECUPERACAO de estado
  (fatia 'demo-tres-consertos' #2b — achado ao vivo: o canal Valkey tem retencao de ~5min e um cliente que
  conecta depois disso nunca ve `votacao.aberta`). UNIAO DISCRIMINADA por `:modalidade` — MESMO desenho de
  `VotoRegistradoPayload` (events/votacao.clj): o ramo 'nominal' e' o UNICO que admite `:votos` (a lista
  individual); 'secreta'/'simbolica' compartilham `SemApuracaoIndividualVotacaoAbertaOut` (mapa fechado
  SEM `:votos`) — machine-enforced, uma tentativa de vazar voto individual numa secreta falha a validacao
  do schema ANTES de sair pela rede, nao depende de disciplina de destructuring no handler."
  [:multi {:dispatch :modalidade}
   ["nominal" [:map {:closed true}
               [:votacao-id :string]
               [:modalidade [:= "nominal"]]
               [:objeto-tipo (km/enum-de logic/objetos-votacao)]
               [:objeto-id :string]
               [:proposicao [:maybe ProposicaoResumoObjetoVotacaoOut]]
               [:votos [:sequential VotoNominalDetalheOut]]]]
   ["secreta" SemApuracaoIndividualVotacaoAbertaOut]
   ["simbolica" SemApuracaoIndividualVotacaoAbertaOut]])
