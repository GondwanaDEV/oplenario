(ns oplenario.integracao-ia.diplomat.http.out
  "Saida HTTP core -> satelite de IA (ADR-0008, §22.3.2: sincrono = HTTP/JSON). Protocolo + impl co-localizados
  (ADR-0001: sem pasta port/). Hoje uma operacao: LER uma transcricao (o texto vive na IA, §22.3.4 — o core so' o
  exibe). O tenant vai explicito no caminho e o satelite o confere. Mesmo segredo de servico da fronteira.

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
    "A transcricao (trechos com orador) ou nil (inexistente para o tenant). Lanca `:ia/indisponivel`."))

(defn- indisponivel! [motivo]
  (throw (ex-info "plataforma de IA indisponivel" {:tipo :ia/indisponivel :motivo motivo})))

(defrecord PlataformaIAHttp [url segredo ^HttpClient cliente]
  PlataformaIA
  (ler-transcricao [_ ente-id transcricao-id]
    (when (or (str/blank? url) (str/blank? segredo)) (indisponivel! "integracao nao configurada"))
    (let [req (-> (HttpRequest/newBuilder (URI/create (str (str/replace url #"/+$" "") "/v1/entes/" ente-id
                                                           "/transcricoes/" transcricao-id)))
                  (.header "Authorization" (str "Bearer " segredo))
                  (.timeout (Duration/ofSeconds 10))
                  (.GET) (.build))
          ^HttpResponse r (try (.send cliente req (HttpResponse$BodyHandlers/ofString))
                               (catch java.io.IOException e (indisponivel! (.getMessage e)))
                               (catch InterruptedException e (indisponivel! (.getMessage e))))]
      (case (.statusCode r)
        200 (json/read-value ^String (.body r) json/keyword-keys-object-mapper)
        404 nil
        (indisponivel! (str "status " (.statusCode r)))))))

(defn plataforma-ia
  "{:url :segredo} -> PlataformaIA. url/segredo em branco = toda leitura responde indisponivel (R-IA-1)."
  [{:keys [url segredo]}]
  (->PlataformaIAHttp url segredo (-> (HttpClient/newBuilder) (.connectTimeout (Duration/ofSeconds 5)) (.build))))
