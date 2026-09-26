(ns oplenario.integracao-ia.diplomat.http.out
  "Saida HTTP core -> satelite de IA (ADR-0008, §22.3.2: sincrono = HTTP/JSON). Protocolo + impl co-localizados
  (ADR-0001: sem pasta port/). Hoje uma operacao: LER uma transcricao (o texto vive na IA, §22.3.4 — o core so' o
  exibe) e, desde a A.6b, LER o rascunho da ata para a revisao. O tenant vai explicito no caminho e o satelite o confere. Mesmo segredo de servico da fronteira.

  Falha da IA nunca e' 500 do core: `ler-transcricao` devolve nil (nao existe para este tenant) ou lanca
  `:ia/indisponivel` — a borda traduz em 503 com a mensagem R-IA-1 ('siga pela tela')."
  (:require [clojure.string :as str]
            [jsonista.core :as json])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpResponse HttpResponse$BodyHandlers)
           (java.time Duration)))

(set! *warn-on-reflection* true)

(defprotocol PlataformaIA
  (ler-transcricao [this ente-id transcricao-id]
    "A transcricao (trechos com orador) ou nil (inexistente para o tenant). Lanca `:ia/indisponivel`.")
  (ler-rascunho-ata [this ente-id rascunho-id]
    "Faixa A / A.6b: o rascunho da ata (texto, texto limpo, citacoes conferidas, incerteza, pontos a confirmar) ou
    nil. Lanca `:ia/indisponivel`."))

(defn- indisponivel! [motivo]
  (throw (ex-info "plataforma de IA indisponivel" {:tipo :ia/indisponivel :motivo motivo})))

(defn- ler-json
  "GET no satelite: 200 -> mapa (chaves keyword), 404 -> nil, qualquer outra coisa -> `:ia/indisponivel`."
  [url segredo ^HttpClient cliente caminho]
  (when (or (str/blank? url) (str/blank? segredo)) (indisponivel! "integracao nao configurada"))
  (let [req (-> (HttpRequest/newBuilder (URI/create (str (str/replace url #"/+$" "") caminho)))
                (.header "Authorization" (str "Bearer " segredo))
                (.timeout (Duration/ofSeconds 10))
                (.GET) (.build))
        ^HttpResponse r (try (.send cliente req (HttpResponse$BodyHandlers/ofString))
                             (catch java.io.IOException e (indisponivel! (.getMessage e)))
                             (catch InterruptedException e (indisponivel! (.getMessage e))))]
    (case (.statusCode r)
      200 (json/read-value ^String (.body r) json/keyword-keys-object-mapper)
      404 nil
      (indisponivel! (str "status " (.statusCode r))))))

(defrecord PlataformaIAHttp [url segredo ^HttpClient cliente]
  PlataformaIA
  (ler-transcricao [_ ente-id transcricao-id]
    (ler-json url segredo cliente (str "/v1/entes/" ente-id "/transcricoes/" transcricao-id)))
  (ler-rascunho-ata [_ ente-id rascunho-id]
    (ler-json url segredo cliente (str "/v1/entes/" ente-id "/atas/rascunhos/" rascunho-id))))

(defn plataforma-ia
  "{:url :segredo} -> PlataformaIA. url/segredo em branco = toda leitura responde indisponivel (R-IA-1)."
  [{:keys [url segredo]}]
  (->PlataformaIAHttp url segredo (-> (HttpClient/newBuilder) (.connectTimeout (Duration/ofSeconds 5)) (.build))))
