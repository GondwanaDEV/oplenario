(ns oplenario.integracao-ia.controllers
  "Orquestracao da fronteira core <-> IA (ADR-0008). Os dados de `sessoes` chegam por SEAMS injetados pelo host
  (§22.10 — este modulo nunca importa outro): `contexto-da-sessao`, `abrir-gravacao`, `registrar-transcricao`.
  O sigilo e' fail-closed aqui, em cima do que o seam devolve: sessao secreta ou gravacao restrita -> `:restrita`."
  (:require [oplenario.integracao-ia.components.repositorio :as repo]
            [oplenario.integracao-ia.logic :as logic]))

(defn feed [repo-ia {:keys [depois limite]}]
  (repo/listar-eventos repo-ia depois limite))

(defn contexto
  "nil (sessao inexistente no tenant) | :restrita | [contexto-sem-restritos nomes]."
  [contexto-da-sessao ente-id sessao-id]
  (when-let [c (contexto-da-sessao ente-id sessao-id)]
    (if (logic/contexto-restrito? c)
      :restrita
      [(update c :segmentos logic/segmentos-liberados) (:nomes c)])))

(defn conteudo
  "nil (inexistente/nao vinculado) | :restrita | {:stream :audio-hash}."
  [abrir-gravacao ente-id segmento-id]
  (abrir-gravacao ente-id segmento-id))

(defn receber!
  "Aplica o evento da IA uma vez so' (dedup pela chave, na tx do tenant). O efeito vai para o seam do dono do dado:
  transcricao -> `registrar-transcricao`; rascunho de ata (A.6b) -> `registrar-rascunho-ata`. Devolve {:aplicado boolean}."
  [repo-ia {:keys [registrar-transcricao registrar-rascunho-ata]} evento]
  (repo/receber-evento! repo-ia evento
    (fn [tx ente-id ev]
      (if (contains? logic/eventos-de-ata (:tipo ev))
        (registrar-rascunho-ata tx ente-id (logic/fato-do-rascunho ev))
        (registrar-transcricao tx ente-id (logic/ponteiro-da-transcricao ev))))))
