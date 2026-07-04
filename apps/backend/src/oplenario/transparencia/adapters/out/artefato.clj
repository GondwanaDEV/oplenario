(ns oplenario.transparencia.adapters.out.artefato
  "Gate de SAIDA `models -> wire/out` do artefato de publicacao (§22.10 adapters/out, ADR-0001) — a resposta
  BINARIA de download (F6c Slice 4b). PRIMEIRA resposta binaria do portal: bytes -> ByteArrayInputStream no
  body (o Jetty consome o stream de forma sincrona; o content-type + Content-Disposition vem do ponteiro
  projetado). Sem Malli aqui: o 'wire' de um download e' o proprio binario + os headers de transporte, nao um
  mapa JSON validavel.

  [CARRY — hardening da 1a rota binaria PUBLICA (review security MAJOR), casado com o [GAP] do layout fisico:
   (a) `os/obter` materializa o blob inteiro em heap (.readAllBytes) — aceitavel p/ o stub de texto atual;
       quando o layout fisico do DO/PDF real chegar, adicionar leitura em STREAMING no ObjetoStore (espelha
       guardar-stream!) + teto de tamanho, servindo o InputStream do S3 direto no body (sem re-bufferizar).
   (b) o interceptor global de seguranca forca `Cache-Control: no-store`, defeituoso p/ conteudo IMUTAVEL e
       hash-enderecavel — mas a URL serve a versao MAIS RECENTE (muta entre re-geracoes), entao a mitigacao
       correta e' `no-cache` + `ETag`(=hash) + revalidacao com 304 (NAO `immutable`/max-age longo, que
       serviria versao velha de um ato OFICIAL). Exige tornar o `no-store` do interceptor um DEFAULT que a
       rota sobrepoe. Deixado p/ decisao/gate: forma aguda do DoS = blobs grandes ([GAP]); rate-limit da rota
       publica ja' e' carry de infra aceito.]"
  (:require [clojure.string :as str])
  (:import (java.io ByteArrayInputStream)))

(set! *warn-on-reflection* true)

(defn- extensao-de
  "Extensao de arquivo a partir do content-type (so' p/ o nome sugerido no Content-Disposition — o binario
  real e' o mesmo). [GAP] layout fisico do DO: enquanto o serializador emite texto (stub), e' 'txt'."
  [content-type]
  (cond
    (str/starts-with? (or content-type "") "text/") "txt"
    (= content-type "application/pdf")               "pdf"
    :else                                            "bin"))

(defn ->download
  "Resposta Ring BINARIA do artefato: 200 + Content-Type do artefato + Content-Disposition attachment com nome
  deterministico (norma-id + versao + extensao do content-type) + body streamado (ByteArrayInputStream)."
  [{:keys [content-type versao] b :bytes} norma-id]
  {:status  200
   :headers {"Content-Type"        content-type
             "Content-Disposition" (str "attachment; filename=\"publicacao-" norma-id "-v" versao
                                        "." (extensao-de content-type) "\"")}
   :body    (ByteArrayInputStream. b)})
