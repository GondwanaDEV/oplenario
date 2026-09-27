(ns oplenario.normas.controllers
  "As acoes das normas de referencia (ADR-0011): importar o texto de uma norma da Casa (o parser quebra em
  dispositivos; nada vale ainda), ler uma versao para conferir, e a CONFERENCIA — uma pessoa publica ou descarta
  (Eixo 7.3). O `municipio-do-ente` e' um seam do host sobre `cadastros` (normas nunca importa cadastros, §22.10)."
  (:require [oplenario.kernel.autorizacao :as authz]
            [oplenario.normas.components.repositorio :as repo]
            [oplenario.normas.logic :as logic]))

(set! *warn-on-reflection* true)

(defn listar [repo-normas ator]
  (authz/exige-papel! ator "secretario")
  (repo/listar-normas repo-normas (:ente-id ator)))

(defn importar!
  "`pedido` = {:especie :titulo :numero? :data? :consolidada-ate? :fonte :texto} (ja' validado na borda). Devolve a
  versao criada, com os dispositivos e os alertas do parser."
  [repo-normas municipio-do-ente ator {:keys [especie titulo numero data consolidada-ate fonte texto]}]
  (authz/exige-papel! ator "secretario")
  (let [{:keys [dispositivos alertas]} (logic/dispositivos texto)
        camada (logic/camada-da-especie especie)
        norma {:camada camada :especie especie :titulo titulo :data data :criada-por (:identidade-id ator)
               :numero (when-not (logic/unica-na-casa? especie) numero)
               :municipio-ibge (when (= "municipal" camada) (municipio-do-ente (:ente-id ator)))}
        id (repo/importar-versao! repo-normas (:ente-id ator) norma
                                  {:consolidada-ate consolidada-ate :fonte fonte :texto texto
                                   :texto-sha256 (logic/sha256-hex texto) :alertas alertas
                                   :enviada-por (:identidade-id ator)}
                                  dispositivos)]
    (repo/versao repo-normas (:ente-id ator) id)))

(defn versao [repo-normas ator versao-id]
  (authz/exige-papel! ator "secretario")
  (repo/versao repo-normas (:ente-id ator) versao-id))

(defn conferir!
  "A pessoa publica (vira vigente; a anterior fica guardada como substituida) ou descarta. Versao ja' decidida ->
  `:conflito/versao-ja-decidida`; inexistente -> nil."
  [repo-normas ator versao-id decisao]
  (authz/exige-papel! ator "secretario")
  (when-let [v (repo/versao repo-normas (:ente-id ator) versao-id)]
    (when-not (= "em_conferencia" (:estado v))
      (throw (ex-info "versao ja' decidida" {:tipo :conflito/versao-ja-decidida :estado (:estado v)})))
    (or (repo/decidir-versao! repo-normas (:ente-id ator) versao-id decisao (:identidade-id ator))
        (throw (ex-info "versao ja' decidida" {:tipo :conflito/versao-ja-decidida})))))
