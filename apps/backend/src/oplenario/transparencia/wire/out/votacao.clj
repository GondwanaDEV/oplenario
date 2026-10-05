(ns oplenario.transparencia.wire.out.votacao
  "Representacao EXTERNA das VOTACOES PUBLICAS do portal (§22.10 wire/out, ADR-0001): a lista paginada das votacoes
  encerradas de sessoes publicas e o detalhe de uma, com o voto de cada vereador quando a votacao foi nominal.
  Sem percentual, sem ranking de vereador: so' o que foi votado, o resultado e quem votou como.")

(def SessaoDaVotacaoOut
  "A sessao em que se votou. `data` = abertura (ou, sem ela, o agendamento): nil so' se a sessao nao tem nenhuma."
  [:map {:closed true}
   [:sessao-id :string]
   [:tipo-sessao :string]
   [:numero-sequencial :int]
   [:data {:optional true} [:maybe :string]]])

(def MateriaDaVotacaoOut
  "A materia votada, quando o objeto e' uma proposicao que o portal publica (link para a ficha publica)."
  [:map {:closed true}
   [:proposicao-id :string]
   [:tipo :string]
   [:sequencial :int]
   [:ano :int]
   [:ementa :string]])

(def PlacarOut
  "O placar apurado. Ausente quando a votacao foi simbolica (aclamacao: nao ha contagem)."
  [:map {:closed true}
   [:sim :int]
   [:nao :int]
   [:abstencoes :int]
   [:base-membros {:optional true} [:maybe :int]]])

(def ^:private campos-da-votacao
  [[:votacao-id :string]
   [:encerrada-em :string]
   [:sessao SessaoDaVotacaoOut]
   [:objeto-tipo :string]
   [:materia {:optional true} [:maybe MateriaDaVotacaoOut]]
   [:modalidade [:enum "nominal" "simbolica" "secreta"]]
   [:quorum-tipo :string]
   [:resultado [:enum "aprovada" "rejeitada"]]
   ;; o TURNO, quando a materia vota em mais de um (a emenda a Lei Organica, CF art. 29): 'aprovada' no 1o turno nao
   ;; e' a materia aprovada. Ausente/nil = votacao que nao e' turno (o significado de sempre).
   [:turno {:optional true} [:maybe :int]]
   [:placar {:optional true} [:maybe PlacarOut]]])

(def VotacaoPublicaOut
  "Um item da lista."
  (into [:map {:closed true}] campos-da-votacao))

(def VotacoesPublicasOut
  "Resposta de GET /portal/casa/:ente/votacoes: a pagina + o TOTAL (do mesmo predicado, sem pagina) e o tamanho da
  pagina — o cliente sabe quantas existem e quantas paginas faltam, nunca recebe um corte em silencio."
  [:map {:closed true}
   [:votacoes [:sequential VotacaoPublicaOut]]
   [:total :int]
   [:pagina :int]
   [:por-pagina :int]])

(def VotoNominalOut
  [:map {:closed true}
   [:vereador-id :string]
   [:vereador :string]
   [:voto [:enum "sim" "nao" "abstencao"]]])

(def VotacaoDetalheOut
  "Uma votacao. `votos` so' tem linhas quando `modalidade` e' nominal; na secreta e na simbolica nao ha voto por
  vereador e a lista vem vazia."
  (into [:map {:closed true}] (conj campos-da-votacao [:votos [:sequential VotoNominalOut]])))
