(ns oplenario.integracao-ia.diplomat.http.out
  "Saida HTTP core -> satelite de IA (ADR-0008, §22.3.2: sincrono = HTTP/JSON). Protocolo + impl co-localizados
  (ADR-0001: sem pasta port/). Hoje uma operacao: LER uma transcricao (o texto vive na IA, §22.3.4 — o core so' o
  exibe) e, desde a A.6b, LER o rascunho da ata para a revisao. O tenant vai explicito no caminho e o satelite o confere. Mesmo segredo de servico da fronteira.

  Falha da IA nunca e' 500 do core: `ler-transcricao` devolve nil (nao existe para este tenant) ou lanca
  `:ia/indisponivel` — a borda traduz em 503 com a mensagem R-IA-1 ('siga pela tela')."
  (:require [clojure.string :as str]
            [jsonista.core :as json])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse HttpResponse$BodyHandlers)
           (java.time Duration)))

(set! *warn-on-reflection* true)

(defprotocol PlataformaIA
  (ler-transcricao [this ente-id transcricao-id]
    "A transcricao (trechos com orador) ou nil (inexistente para o tenant). Lanca `:ia/indisponivel`.")
  (ler-rascunho-ata [this ente-id rascunho-id]
    "Faixa A / A.6b: o rascunho da ata (texto, texto limpo, citacoes conferidas, incerteza, pontos a confirmar) ou
    nil. Lanca `:ia/indisponivel`.")
  (ler-rascunho-resumo [this ente-id rascunho-id]
    "Faixa A / A.8: o rascunho do resumo cidadao (texto, texto limpo, citacoes conferidas, incerteza) ou nil. Lanca
    `:ia/indisponivel`.")
  (buscar [this ente-id pedido]
    "Faixa A / A.5: a busca no indice do satelite. `pedido` = {:consulta :tipos? :limite?} -> {:modelo :resultados
    [{:tipo :ref-id :parte :texto :meta :score}]}: ids e trechos, nunca a decisao do que o usuario ve (o core hidrata).
    Timeout curto (busca e' interativa, §22.3.4 <2s). Lanca `:ia/indisponivel`.")
  (executar-agente [this ente-id pedido]
    "Faixa B / B.3: uma execucao do agente. `pedido` = {:pergunta :credencial :correlation_id} -> {:passos :resposta
    :indisponivel}. A credencial e' a delegada da execucao (ADR-0010): e' com ela, e so' com ela, que o satelite
    volta ao core pelo MCP. Timeout de uma conversa (60 s). Lanca `:ia/indisponivel`.")
  (rascunhar-requerimento [this ente-id pedido]
    "Faixa B / B.7: o copiloto do requerimento. `pedido` = {:descricao :modelos [{:id :nome :campos}] :correlation_id}
    -> {:preenchimento :justificativa :indisponivel}. Rascunho: o core confere contra os modelos e a tela devolve ao
    formulario. Timeout de 30 s (dois passos de modelo). Lanca `:ia/indisponivel`.")
  (consumo [this ente-id mes]
    "Faixa B / B.9: o consumo de IA da Casa no mes `mes` ('AAAA-MM') x o orcamento que o satelite esta' aplicando, o
    estado da cota e, por capacidade, execucoes, custo e revisao humana. So' contagens e valores. Lanca
    `:ia/indisponivel`."))

(defn- indisponivel! [motivo]
  (throw (ex-info "plataforma de IA indisponivel" {:tipo :ia/indisponivel :motivo motivo})))

(defn- ler-json
  "GET (ou POST com `corpo`) no satelite: 200 -> mapa (chaves keyword), 404 -> nil, qualquer outra coisa ->
  `:ia/indisponivel`."
  ([url segredo cliente caminho] (ler-json url segredo cliente caminho nil (Duration/ofSeconds 10)))
  ([url segredo ^HttpClient cliente caminho corpo ^Duration timeout]
   (when (or (str/blank? url) (str/blank? segredo)) (indisponivel! "integracao nao configurada"))
   (let [b (-> (HttpRequest/newBuilder (URI/create (str (str/replace url #"/+$" "") caminho)))
               (.header "Authorization" (str "Bearer " segredo))
               (.timeout timeout))
         req (.build (if corpo
                       (-> b (.header "Content-Type" "application/json")
                           (.POST (HttpRequest$BodyPublishers/ofString (json/write-value-as-string corpo))))
                       (.GET b)))
         ^HttpResponse r (try (.send cliente req (HttpResponse$BodyHandlers/ofString))
                              (catch java.io.IOException e (indisponivel! (.getMessage e)))
                              (catch InterruptedException e (indisponivel! (.getMessage e))))]
     (case (.statusCode r)
       200 (json/read-value ^String (.body r) json/keyword-keys-object-mapper)
       404 nil
       (indisponivel! (str "status " (.statusCode r)))))))

(defrecord PlataformaIAHttp [url segredo ^HttpClient cliente]
  PlataformaIA
  (ler-transcricao [_ ente-id transcricao-id]
    (ler-json url segredo cliente (str "/v1/entes/" ente-id "/transcricoes/" transcricao-id)))
  (ler-rascunho-ata [_ ente-id rascunho-id]
    (ler-json url segredo cliente (str "/v1/entes/" ente-id "/atas/rascunhos/" rascunho-id)))
  (ler-rascunho-resumo [_ ente-id rascunho-id]
    (ler-json url segredo cliente (str "/v1/entes/" ente-id "/resumos/rascunhos/" rascunho-id)))
  (buscar [_ ente-id pedido]
    (or (ler-json url segredo cliente (str "/v1/entes/" ente-id "/busca") pedido (Duration/ofSeconds 2))
        (indisponivel! "busca sem resposta")))
  (executar-agente [_ ente-id pedido]
    (or (ler-json url segredo cliente (str "/v1/entes/" ente-id "/agente/execucoes") pedido (Duration/ofSeconds 60))
        (indisponivel! "agente sem resposta")))
  (rascunhar-requerimento [_ ente-id pedido]
    (or (ler-json url segredo cliente (str "/v1/entes/" ente-id "/requerimentos/rascunhos") pedido (Duration/ofSeconds 30))
        (indisponivel! "copiloto sem resposta")))
  (consumo [_ ente-id mes]
    (or (ler-json url segredo cliente (str "/v1/entes/" ente-id "/consumo?mes=" mes))
        (indisponivel! "consumo sem resposta"))))

(defn plataforma-ia
  "{:url :segredo} -> PlataformaIA. url/segredo em branco = toda leitura responde indisponivel (R-IA-1)."
  [{:keys [url segredo]}]
  (->PlataformaIAHttp url segredo (-> (HttpClient/newBuilder) (.connectTimeout (Duration/ofSeconds 5)) (.build))))
