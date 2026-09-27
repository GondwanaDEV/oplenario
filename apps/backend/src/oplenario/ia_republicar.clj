(ns oplenario.ia-republicar
  "Host (§22.10: raiz de composicao, pode requerer modulos): publica no feed da IA as proposicoes que JA' existem numa
  Casa, para o indice de busca (Track IA A.4). As novas chegam sozinhas (o relay promove `proposicao.protocolada` e
  `.editada`); esta e' a carga inicial do onboarding — e a de depois de trocar o modelo de embeddings. Usa a MESMA
  promocao do relay (`proposicao.editada` -> `ProposicaoAtualizada`): idempotente pela chave, rodar de novo nao
  duplica nada. Por Casa (a leitura e' na tx do tenant, RLS)."
  (:require [next.jdbc :as jdbc]
            [oplenario.integracao-ia.components.repositorio :as repo-ia]
            [oplenario.legislativo.components.repositorio :as repo-leg]))

(def ^:private tamanho-pagina 200)

(defn republicar-proposicoes!
  "Todas as proposicoes da Casa `ente-id` -> feed da IA. Devolve quantas foram lidas."
  [ds ente-id]
  (let [leg (repo-leg/map->RepoLegislativoPg {:datasource {:ds ds}})]
    (loop [pagina 1 n 0]
      (let [{:keys [itens]} (repo-leg/listar-e-contar-proposicoes
                             leg ente-id {:pagina pagina :tamanho tamanho-pagina
                                          :ordenar-por "atualizado_em" :ordenar-dir "asc"})]
        (jdbc/with-transaction [tx ds]
          (doseq [p itens]
            (repo-ia/promover-em-tx! tx {:tipo "proposicao.editada" :ente-id ente-id
                                         :payload (cond-> {:proposicao-id (:id p) :ementa (:ementa p)}
                                                    (:autor-texto p) (assoc :autor-texto (:autor-texto p)))})))
        (if (< (count itens) tamanho-pagina)
          (+ n (count itens))
          (recur (inc pagina) (+ n (count itens))))))))
