(ns oplenario.tempo-real.wire.out.evento-sse
  "Contrato EXTERNO de SAIDA do evento SSE (§22.10 wire/out, ADR-0001) — a forma logica do evento emitido no
  canal plenario, da qual o Eixo 8 gera os tipos TS do cliente. Na linha do fio um EventoSse vira um frame SSE
  (`event: <tipo>` / `data: <dados em JSON>` / `id: <seq>`); este e' o contrato que o adapters/out satisfaz."
  (:require [oplenario.tempo-real.canais :as canais]))

(defn- enum-de [valores] (into [:enum] (sort valores)))

(def EventoSse
  "Evento do painel ao vivo. `:tipo` = o tipo do evento de dominio (o cliente discrimina o render); `:seq` = a
  posicao monotonica no canal (vira o Last-Event-ID); `:dados` = o payload publico do evento (mapa aberto,
  especifico por tipo — os eventos de sessao nao carregam CPF nem voto secreto). `:tipo` restrito aos tipos
  que de fato podem chegar ao cliente (`canais/tipos-emitidos-ao-cliente` = os roteados ao painel +
  `tipo-lacuna`, o sinal sintetico de buraco de replay — frente 'truncamento-familia' sitio (d)) — fonte
  unica, evita drift com o roteamento/backplane."
  [:map {:closed true}
   [:tipo (enum-de canais/tipos-emitidos-ao-cliente)]
   [:seq :int]
   [:dados [:map]]])
