(ns oplenario.tempo-real.projecao
  "wire/out do tempo real (§22.6 eixo G): projeta um evento de dominio em mensagens de canal {:canal :tipo
  :dados}. `:dados` = o payload do evento (ja e' a representacao publica do painel — os eventos de sessao nao
  carregam CPF nem voto secreto). Este e' o SEAM onde, quando os canais publico/dashboard chegarem (G3), entra
  a FILTRAGEM de campos sensiveis por canal. O cliente recomputa o cronometro a partir dos marcos."
  (:require [oplenario.tempo-real.canais :as canais]))

(def ^:private tick-secreto-chaves
  "WHITELIST das chaves do tick de voto SECRETO (§22.6). So o agregado/contador ao vivo — JAMAIS identidade.
  Whitelist (nao blacklist): se uma chave nova surgir no payload secreto, ela e' DESCARTADA por default
  (fail-closed), em vez de vazar ate alguem lembrar de adiciona-la a um dissoc."
  #{:votacao-id :sessao-id :modalidade})

(defn- dados-publicos
  "A representacao PUBLICA do payload p/ o canal plenario. Default = o payload tal qual (os eventos de sessao
  ja sao publicos; o voto NOMINAL e' publico por definicao — o placar mostra quem votou o que; o resultado
  AGREGADO do encerramento e' publico mesmo na secreta). SIGILO §22.6 — DEFESA EM PROFUNDIDADE: o contrato do
  evento (uniao discriminada, legislativo/events/votacao.clj) ja barra identidade no ramo secreta ANTES do
  outbox; esta projecao e' o ULTIMO portao antes do canal.
   - voto.registrado SECRETO -> WHITELIST so as chaves do tick (um payload secreto malformado nao vaza
     `:vereador-id`/`:voto`);
   - voto.registrado NOMINAL  -> pass-through (vereador/voto sao PUBLICOS no placar nominal);
   - voto.registrado com modalidade INESPERADA (nem nominal nem secreta) -> LANCA. O contrato
     VotoRegistradoPayload e' um `:multi` fechado sobre {nominal,secreta}, entao isto so ocorre sob violacao
     de contrato a montante; falhar fechado aqui torna o invariante MACHINE-ENFORCED (nao so comentario) e
     impede que uma 3a modalidade futura vaze identidade por pass-through cego se alguem esquecer este gate.

  A `ex-info` carrega `:tempo-real/payload-malformado? true` em `ex-data` (frente 'relay-poison-tolerante') —
  MARCADOR aditivo, nao mudanca de comportamento: o gate continua fechando fail-closed exatamente como
  antes; a unica diferenca e' que quem captura esta excecao na fronteira de despacho (tempo_real/consumer.clj)
  agora sabe, sem casar a MENSAGEM (string de humano), que isto e' forma-de-dado e nao infra."
  [{:keys [tipo payload]}]
  (if (= tipo "voto.registrado")
    (case (:modalidade payload)
      "secreta" (select-keys payload tick-secreto-chaves)
      "nominal" payload
      (throw (ex-info "voto.registrado com modalidade inesperada (contrato violado a montante)"
                      {:modalidade (:modalidade payload)
                       :tempo-real/payload-malformado? true})))
    payload))

(defn projetar
  "Evento de dominio -> vetor de mensagens {:canal :ente-id :tipo :dados}. Uma mensagem por canal roteado
  (vazio p/ eventos nao-SSE). `:tipo` = o tipo do evento (o cliente discrimina o render). `:dados` = a
  representacao PUBLICA do payload (vide `dados-publicos` — sigilo do voto secreto mora aqui). `:ente-id` viaja
  na mensagem (defesa-em-profundidade: a store deixa de ser tenant-cega) — MAS NAO e' a barreira de
  autorizacao: o endpoint SSE (G3) DEVE verificar a posse do tenant por consulta ao banco na ABERTURA da
  conexao (vide o checklist de authz em canais.clj). Filtragem por canal publico/dashboard segue seam de G3."
  [{:keys [tipo ente-id] :as evento}]
  (let [dados (dados-publicos evento)]
    (mapv (fn [canal] {:canal canal :ente-id ente-id :tipo tipo :dados dados})
          (canais/rotas-do-evento evento))))
