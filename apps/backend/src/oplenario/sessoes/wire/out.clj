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
