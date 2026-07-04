(ns oplenario.sessoes.wire.out
  "Representacao EXTERNA de SAIDA da sessao (§22.10 wire/out, ADR-0001) — o contrato de borda que o
  `adapters/out` produz e do qual o Eixo 8 gera os tipos TS do front. Tudo como tipo serializavel a JSON:
  uuid/Instant viram string. NAO expoe o token interno de concorrencia (`lock-version`) nem campos
  sensiveis — a defesa anti-vazamento mora no adapters/out."
  (:require [oplenario.kernel.malli :as km]
            [oplenario.sessoes.logic :as logic]))

(def SessaoOut
  "Projecao publica de uma sessao (resposta REST). Strings p/ uuid; ISO-8601 p/ Instant; marcos opcionais."
  [:map {:closed true}
   [:id :string]
   [:sessao-legislativa-id :string]
   [:tipo-sessao (km/enum-de logic/tipos-sessao)]
   [:numero-sequencial :int]
   [:estado (km/enum-de logic/estados-sessao)]
   [:modalidade (km/enum-de logic/modalidades-sessao)]
   [:delibera :boolean]
   [:transmite-publica :boolean]
   [:gera-ata-regimental :boolean]
   [:permite-voto-secreto :boolean]
   [:permite-modalidade-remota :boolean]
   [:agendada-para {:optional true} [:maybe :string]]
   [:aberta-em {:optional true} [:maybe :string]]
   [:encerrada-em {:optional true} [:maybe :string]]
   [:motivo-nao-realizada {:optional true} [:maybe :string]]])

(def TransicaoSessaoOut
  "Recibo da transicao de estado da sessao (resposta 200 de POST /sessoes/:id/transicao, Mesa de conducao).
  Carrega a `sessao-id` + o par `de`/`para` (espelha o payload do evento sessao.transicionou). NAO expoe o
  lock-version. O canal SSE do plenario ja recebeu o mesmo fato pelo evento; este recibo confirma ao chamador."
  [:map {:closed true}
   [:sessao-id :string]
   [:de (km/enum-de logic/estados-sessao)]
   [:para (km/enum-de logic/estados-sessao)]])

(def PresencaReciboOut
  "Recibo do registro de presenca (resposta 201 de POST /sessoes/:id/presenca). So o `id` do evento gravado — o
  canal SSE do plenario ja recebeu o fato (presenca.registrada) p/ o quorum ao vivo; este recibo confirma ao
  chamador. NAO expoe internos."
  [:map {:closed true}
   [:id :string]])

(def InscricaoReciboOut
  "Recibo da inscricao de orador (resposta 201 de POST /sessoes/:id/inscricoes). `id` da inscricao + `ordem` na
  fila (por sessao+fase). O canal SSE ja recebeu inscricao.registrada; este recibo confirma ao chamador."
  [:map {:closed true}
   [:id :string]
   [:ordem :int]])

(def DesistenciaInscricaoOut
  "Recibo da desistencia de inscricao (resposta 200 de POST /sessoes/:id/inscricoes/:insc-id/desistir). Carrega
  a `inscricao-id` + o par `de`/`para` (espelha o recibo de transicao da sessao). NAO expoe o lock-version."
  [:map {:closed true}
   [:inscricao-id :string]
   [:de (km/enum-de logic/estados-inscricao)]
   [:para (km/enum-de logic/estados-inscricao)]])

(def FalaReciboOut
  "Recibo do inicio de fala (resposta 201 de POST /sessoes/:id/falas). So a `fala-id` criada — o canal SSE ja
  recebeu fala.iniciada; este recibo confirma ao chamador a fala a cronometrar/encerrar a seguir."
  [:map {:closed true}
   [:fala-id :string]])

(def CronometroEventoReciboOut
  "Recibo do registro de evento do cronometro (resposta 201 de .../cronometro). So o `id` do evento append-only
  gravado — o canal SSE ja recebeu fala.cronometro p/ atualizar o relogio ao vivo; este recibo confirma."
  [:map {:closed true}
   [:id :string]])

(def FalaEncerradaOut
  "Recibo do encerramento de fala (resposta 200 de .../encerrar). Carrega a `fala-id` + o `tempo-segundos`
  EFETIVAMENTE usado (computado dos eventos do cronometro, projecao). NAO expoe o lock-version."
  [:map {:closed true}
   [:fala-id :string]
   [:tempo-segundos :int]])

(def DecisaoMesaReciboOut
  "Recibo do registro da decisao da mesa (resposta 201 de POST /sessoes/:id/decisoes-mesa). So o `id` da decisao
  append-only gravada — confirma ao chamador o ato lavrado p/ a ata. NAO expoe internos."
  [:map {:closed true}
   [:id :string]])

(def IncidenteReciboOut
  "Recibo do registro de incidente processual (resposta 201 de POST /sessoes/:id/incidentes, §16.13). So o `id`
  do incidente append-only gravado — confirma ao chamador o ato lavrado p/ a ata. NAO expoe internos."
  [:map {:closed true}
   [:id :string]])

(def PautaItemOut
  "Projecao publica de um item ATIVO da pauta (§22.6 eixo B). NAO expoe internos (ente-id, pauta-sessao-id,
  lock-version, ativo). FK-por-tipo: 'proposicao' carrega proposicao-id (string); os demais, texto-descricao."
  [:map {:closed true}
   [:id :string]
   [:fase (km/enum-de logic/fases-pauta)]
   [:tipo-item (km/enum-de logic/tipos-item-pauta)]
   [:proposicao-id {:optional true} [:maybe :string]]
   [:texto-descricao {:optional true} [:maybe :string]]
   [:ordem :int]])

(def PautaOut
  "Pauta viva da sessao (resposta de GET /sessoes/:id/pauta) — o sessao-id + os itens ativos em ordem.
  Pauta opcional: sessao sem pauta criada projeta `itens` vazio."
  [:map {:closed true}
   [:sessao-id :string]
   [:itens [:sequential PautaItemOut]]])

(def GravacaoReciboOut
  "Recibo da ingestao de gravacao (resposta 201 de POST /gravacoes). Carrega o `id` do segmento + o
  `audio-hash` (sha256) p/ o utilitario CLI confirmar integridade/dedup (§22.3.4). NAO expoe a chave interna
  do store (container-bruto-uri)."
  [:map {:closed true}
   [:id :string]
   [:audio-hash :string]])

(def SegmentoOut
  "Projecao publica de um segmento de gravacao no read-model do painel (GET /sessoes/:id/gravacao). NAO expoe
  internos: container-bruto-uri (chave do store), audio-hash, ente-id, lock-version. `audio-disponivel` = se a
  IA ja extraiu o audio (audio-uri presente)."
  [:map {:closed true}
   [:id :string]
   [:sessao-id {:optional true} [:maybe :string]]
   [:iniciou-em :string]
   [:encerrou-em {:optional true} [:maybe :string]]
   [:motivo-inicio (km/enum-de logic/motivos-inicio-gravacao)]
   [:motivo-fim {:optional true} [:maybe (km/enum-de logic/motivos-fim-gravacao)]]
   [:fonte-ingestao (km/enum-de logic/fontes-ingestao-gravacao)]
   [:acesso-restrito :boolean]
   [:audio-disponivel :boolean]])

(def SegmentosOut
  "Read-model dos segmentos de gravacao de uma sessao (GET /sessoes/:id/gravacao)."
  [:map {:closed true}
   [:sessao-id :string]
   [:segmentos [:sequential SegmentoOut]]])

(def VinculoGravacaoOut
  "Recibo da VINCULACAO de um segmento a uma sessao (resposta 201 de POST /sessoes/:id/gravacao/:seg-id/vincular,
  Opcao A pos-upload). Carrega o `id` do segmento + a `sessao-id` a que foi vinculado. NAO expoe internos
  (lock-version, acesso-restrito recalculado, chave do store)."
  [:map {:closed true}
   [:id :string]
   [:sessao-id :string]])

(def PautaItemAdicionadoOut
  "Recibo da adicao de item a pauta (resposta 201 de POST /sessoes/:id/pauta/itens). `id` do item criado +
  `ordem` numerada server-side (max+1). NAO expoe internos (pauta-sessao-id, lock-version, ativo)."
  [:map {:closed true}
   [:id :string]
   [:ordem :int]])

(def PautaItemReordenadoOut
  "Recibo da reordenacao de item (resposta 200 de PATCH /sessoes/:id/pauta/itens/:item-id). `id` do item + o
  par `de`/`para` (ordem anterior/destino). NAO expoe o lock-version."
  [:map {:closed true}
   [:id :string]
   [:de :int]
   [:para :int]])

(def PautaItemRemovidoOut
  "Recibo da remocao SOFT de item (resposta 200 de DELETE /sessoes/:id/pauta/itens/:item-id). So o `id` do item
  removido — a remocao e' ativo=false (nunca DELETE fisico, Inv.10), detalhe interno nao exposto."
  [:map {:closed true}
   [:id :string]])

(def PresencaResumoOut
  "Presenca agregada do tenant (§16.11, FE Onda A1 — card 'o que a Casa entregou'). `media-percentual`
  nil quando nao ha sessao encerrada ainda (0/0 e' indefinido, o FE NAO mostra '0%')."
  [:map {:closed true}
   [:media-percentual [:maybe :int]]
   [:sessoes-consideradas :int]
   [:membros-da-casa :int]])
