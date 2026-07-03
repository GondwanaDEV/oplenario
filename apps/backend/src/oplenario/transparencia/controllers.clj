(ns oplenario.transparencia.controllers
  "Orquestracao IMPURA do portal (§22.10 controllers, ADR-0001) — coordena o Repo-Component. TODA rota desta
  fatia e' PUBLICA (sem ator, sem policy): delegacao fina ao Repo (mesmo padrao de
  participacao.controllers/comentarios-da-materia)."
  (:require [oplenario.transparencia.components.repositorio :as repo]))

(defn listar-materias
  "Portal: materias em tramitacao (sem exclusao de estado nesta fatia — lista tudo, mais recente primeiro)."
  [repo-transparencia ente-id]
  (repo/listar-materias repo-transparencia ente-id #{}))

(defn ficha-materia
  "A ficha PUBLICA de uma materia — a materia + a norma publicada, se houver (liga 'proposicao -> lei').
  Devolve nil se a materia nao existe no portal (proposicao nunca protocolada, ou tenant errado)."
  [repo-transparencia ente-id proposicao-id]
  (when-let [m (repo/buscar-materia repo-transparencia ente-id proposicao-id)]
    (assoc m :norma (repo/norma-da-materia repo-transparencia ente-id proposicao-id))))

(defn listar-normas
  "Portal: legislacao PUBLICADA as-enacted (normas publicadas, mais recente primeiro)."
  [repo-transparencia ente-id]
  (repo/listar-normas repo-transparencia ente-id))

(defn buscar-norma
  "Uma norma publicada especifica, ou nil."
  [repo-transparencia ente-id norma-id]
  (repo/buscar-norma repo-transparencia ente-id norma-id))
