(ns oplenario.sessoes.adapters.out.folha
  "Gate de SAIDA `models -> wire/out` da FOLHA DA SESSAO (§22.10 adapters/out, ADR-0001) — Etapa 5 fatia 5.

  As DUAS projecoes de METADADOS (`folha->wire`/`folhas-da-sessao->wire`, POST 201 + GET lista) passam por
  Malli contra `wire.out/FolhaMetadadosOut`/`FolhasDaSessaoOut` — drift de campo e' bug de servidor (500 via
  a excecao lancada aqui), nunca resposta malformada. Elas NUNCA carregam o binario nem os
  `*_objeto_store_ref` (detalhe de armazenamento interno) — so' os hashes.

  As DUAS respostas de CONTEUDO (`->html-resposta`/`->pdf-download`) NAO passam por Malli: o wire de um
  binario e' o proprio binario + os headers de transporte, mesmo racional de
  `transparencia.adapters.out.artefato/->download` (que `->pdf-download` copia a forma de)."
  (:require [malli.core :as m]
            [malli.error :as me]
            [oplenario.sessoes.wire.out :as wire])
  (:import (java.io ByteArrayInputStream)))

(set! *warn-on-reflection* true)

(defn- validar! [schema out nome]
  (when-not (m/validate schema out)
    (throw (ex-info (str nome " viola o contrato de saida (bug de servidor)")
                    {:campos (keys (me/humanize (m/explain schema out)))})))
  out)

(defn folha->wire
  "Linha de `sessoes.folha_sessao` (dominio, kebab, de `repo/inserir-folha-dedup!`/`repo/buscar-folha`/
  `repo/folhas-da-sessao`) -> FolhaMetadadosOut (validado)."
  [{:keys [id versao spec-versao html-hash pdf-hash gerada-por gerada-em ja-congelada]}]
  (validar! wire/FolhaMetadadosOut
            (cond-> {:id (str id) :versao versao :spec-versao spec-versao
                     :html-hash html-hash :pdf-hash pdf-hash
                     :gerada-por (str gerada-por) :gerada-em (str gerada-em)}
              ja-congelada (assoc :ja-congelada true))
            "FolhaMetadadosOut"))

(defn folhas-da-sessao->wire
  "N linhas -> FolhasDaSessaoOut (validado, resposta 200 de GET /sessoes/:id/folhas)."
  [sessao-id folhas]
  (validar! wire/FolhasDaSessaoOut
            {:sessao-id (str sessao-id) :folhas (mapv folha->wire folhas)}
            "FolhasDaSessaoOut"))

(defn ->html-resposta
  "Resposta Ring do HTML CANONICO congelado: 200, Content-Type do proprio artefato (text/html; charset=utf-8),
  body = os BYTES DO OBJETO_STORE, SEM Content-Disposition (visualizacao INLINE — a Fatia 6 aponta um
  `<iframe>` aqui, nunca um download). NUNCA re-renderiza: os bytes servidos sao exatamente os que o
  congelamento hasheou."
  [{:keys [content-type] b :bytes}]
  {:status 200
   :headers {"Content-Type" content-type}
   :body (ByteArrayInputStream. b)})

(defn ->pdf-download
  "Resposta Ring BINARIA do PDF congelado: 200, Content-Type application/pdf, Content-Disposition attachment
  com nome deterministico `folha-<sessao-id>-v<versao>.pdf`, body = os BYTES DO OBJETO_STORE. Mesma forma de
  `transparencia.adapters.out.artefato/->download`."
  [{:keys [content-type versao] b :bytes} sessao-id]
  {:status 200
   :headers {"Content-Type" content-type
             "Content-Disposition" (str "attachment; filename=\"folha-" sessao-id "-v" versao ".pdf\"")}
   :body (ByteArrayInputStream. b)})
