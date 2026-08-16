(ns oplenario.sessoes.models.folha
  "Representacao INTERNA (dominio) do DOCUMENTO DA FOLHA DE PRESENCA (§22.6 eixo C, Etapa 5 fatia 1, D2/D6)
  — Malli (§22.10 models/), produzido por `sessoes.gerador-folha/renderizar`. NAO e' entidade persistida
  nesta fatia: a linha do banco (`sessoes.folha_sessao`, os dois hashes+refs) e' Fatia 4. Este model valida
  o DOCUMENTO INTERMEDIARIO formato-agnostico antes de ele virar HTML canonico (Fatia 2) ou PDF (Fatia 3).

  D6 (so' sessao FECHADA tem folha) entra na FORMA, nao so' na regra do controller: `:sessao/:estado` e'
  restrito a `logic/estados-sessao-fechada` (allowlist), nao ao vocabulario inteiro de `estados-sessao` — um
  documento com estado 'aberta' e' invalido por CONSTRUCAO.

  `:linhas` NAO reusa `sessoes.models.presenca/LinhaChamada` (a FONTE corrigiu a hipotese original do brief:
  `chamada-da-sessao*` nao devolve a linha crua de `derivar-linha-chamada` — devolve o resultado de
  `controllers/linha-para-o-adapter`, que a ENRIQUECE com `cargo-mesa`/`desde`/`fonte`/`registrado-em`/
  `justificativa`. `LinhaChamada` reprovaria toda linha real com `:malli.core/extra-key`; `LinhaDaFolha`
  aqui embaixo e' o espelho FIEL dessa forma enriquecida.

  `:justificativas` (o insumo TOPO, separado) e' um subconjunto PROPRIO (`JustificativaDaFolha`) porque
  `listar-justificativas-da-sessao` (com `motivo`) NAO projeta `ente-id`/`sessao-id` — reusar
  `presenca/JustificativaAusencia` aqui rejeitaria dado legitimo."
  (:require [oplenario.kernel.malli :as km]
            [oplenario.sessoes.logic :as logic]))

(def LinhaDaFolha
  "O espelho FIEL de `controllers/linha-para-o-adapter` — a linha da chamada (`derivar-linha-chamada`/
  `derivar-linha-sem-assento`) MAIS `cargo-mesa`/`desde`/`fonte`/`registrado-em`/`justificativa`, os campos
  que a derivacao PURA nao carrega (vem do roster e do ULTIMO evento). `desde`/`fonte`/`registrado-em` sao
  nil quando o vereador nao tem nenhum evento na sessao; `justificativa` e' nil quando ele nao tem
  justificativa aberta (so' expoe {:estado :motivo :decidido-em} — o mesmo recorte de `LinhaChamadaOut`,
  aqui em forma de dominio, nao de wire)."
  [:map {:closed true}
   [:vereador-id :uuid]
   [:nome [:maybe [:string {:min 1}]]]
   [:nome-parlamentar [:maybe :string]]
   [:partido [:maybe :string]]
   [:cargo-mesa [:maybe :string]]
   [:estado (km/enum-de logic/estados-chamada)]
   [:inconsistencia-cadastro :boolean]
   [:sem-assento :boolean]
   [:desde [:maybe km/Instante]]
   [:fonte [:maybe (km/enum-de logic/fontes-presenca)]]
   [:registrado-em [:maybe km/Instante]]
   [:justificativa [:maybe [:map {:closed true}
                            [:estado (km/enum-de logic/estados-justificativa)]
                            [:motivo [:string {:min 1}]]
                            [:decidido-em [:maybe km/Instante]]]]]])

(def SessaoDaFolha
  "O recorte da sessao que a folha precisa no cabecalho — nao o `models.sessao/Sessao` inteiro (capabilities,
  lock-version etc. nao pertencem ao documento congelado)."
  [:map {:closed true}
   [:id :uuid]
   [:estado (km/enum-de logic/estados-sessao-fechada)]
   [:motivo-nao-realizada {:optional true} [:maybe :string]]])

(def CabecalhoDaCasa
  "O que o seam `dados-da-casa` (irmao de `roster-da-casa`, rotas.clj) resolve sobre `cadastros` — nome do
  ente + a legislatura. Tudo nullable: um ente sem perfil cadastrado, ou sem legislatura vigente resolvida
  na data, nao pode travar a folha inteira (o cabecalho e' proveniencia, nao o dado que decide quorum)."
  [:map {:closed true}
   [:nome-oficial [:maybe :string]]
   [:nome-curto [:maybe :string]]
   [:legislatura-numero [:maybe :int]]
   [:legislatura-ano-inicio [:maybe :int]]
   [:legislatura-ano-fim [:maybe :int]]])

(def EventoDaSerie
  "Uma linha crua da SERIE (`db/presenca/serie-de-eventos-da-sessao`) — o mesmo vocabulario de
  `PresencaEvento`, mais `fonte`/`efetivado-em` (a folha mostra fonte de captura + o par dominio/audit, a
  mesma disciplina de `presenca-corrente`)."
  [:map {:closed true}
   [:ente-id :uuid]
   [:id :uuid]
   [:sessao-id :uuid]
   [:vereador-id :uuid]
   [:tipo (km/enum-de logic/tipos-evento-presenca)]
   [:modalidade (km/enum-de logic/modalidades-presenca)]
   [:fonte (km/enum-de logic/fontes-presenca)]
   [:ocorrido-em km/Instante]
   [:efetivado-em km/Instante]])

(def JustificativaDaFolha
  "O insumo REAL de `repo/listar-justificativas` (`db/presenca/listar-justificativas-da-sessao`) — sem
  `ente-id`/`sessao-id` (a query nao os projeta; um unico documento ja e' escopado por sessao)."
  [:map {:closed true}
   [:id :uuid]
   [:vereador-id :uuid]
   [:estado (km/enum-de logic/estados-justificativa)]
   [:motivo [:string {:min 1}]]
   [:lock-version :int]
   [:decidido-por {:optional true} [:maybe :uuid]]
   [:decidido-em {:optional true} [:maybe km/Instante]]])

(def AtoDeChamadaConduzida
  "O ato append-only (`db/chamada/listar-da-sessao`) — pode haver mais de um por sessao (reconducao)."
  [:map {:closed true}
   [:id :uuid]
   [:ente-id :uuid]
   [:sessao-id :uuid]
   [:conduzida-por :uuid]
   [:membros-da-casa :int]
   [:ocorrido-em km/Instante]
   [:registrado-em km/Instante]])

(def Quorum
  "A saida de `logic/contar-quorum` — repetida aqui (nao reimportada de `wire/`, que `models/` nao toca)
  para o documento validar o MESMO formato que a chamada publica."
  [:map {:closed true}
   [:presentes-plenario :int]
   [:presentes-remoto :int]
   [:presentes-total :int]
   [:membros-da-casa :int]
   [:presencas-fora-do-roster :int]])

(def FolhaDocumento
  "O DOCUMENTO INTERMEDIARIO inteiro (`:spec-versao \"folha-sessao-v1\"`), formato-agnostico — produzido por
  `gerador-folha/renderizar`, consumido pelos serializadores de HTML (Fatia 2) e PDF (Fatia 3)."
  [:map {:closed true}
   [:spec-versao [:enum "folha-sessao-v1"]]
   [:sessao SessaoDaFolha]
   [:instante km/Instante]
   [:cabecalho-da-casa CabecalhoDaCasa]
   [:linhas [:sequential LinhaDaFolha]]
   [:quorum Quorum]
   [:serie [:map-of :uuid [:sequential EventoDaSerie]]]
   [:justificativas [:sequential JustificativaDaFolha]]
   [:atos-de-chamada-conduzida [:sequential AtoDeChamadaConduzida]]])
