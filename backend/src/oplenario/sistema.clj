(ns oplenario.sistema
  "Composicao do host (§22.10): system-map Component que faz o merge da infra do kernel + os
  sub-systems dos modulos. F0.1 fia o minimo (datasource); F0.2+ adicionam outbox-relay, scheduler e
  os Components de cada modulo via `using`. So recurso stateful e' componente. O host (raiz de
  composicao) PODE requerer modulos — e' aqui que os Repo-Components recebem o :datasource."
  (:require [com.stuartsierra.component :as component]
            [oplenario.cadastros.components.repositorio :as repo-cadastros]
            [oplenario.identidade.components.repositorio :as repo-identidade]
            [oplenario.kernel.components.datasource :as datasource]))

(defn novo-sistema
  "Monta o sistema a partir do config carregado. Cresce por agregacao conforme os modulos chegam."
  [config]
  (component/system-map
   :datasource      (datasource/datasource config)
   :repo-cadastros  (component/using (repo-cadastros/repositorio) [:datasource])
   :repo-identidade (component/using (repo-identidade/repositorio) [:datasource])))
