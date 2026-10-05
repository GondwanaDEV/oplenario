(ns oplenario.portal-republicar-rito
  "Host (§22.10: raiz de composicao, pode requerer modulos): a faixa 'Onde este projeto esta'' da ficha PUBLICA segue o
  rito da Casa desde a mig 20261005000262, mas so' para a materia que teve evento (protocolo ou transicao) depois dela;
  a anterior ficou com `rito` NULL e no mapa fixo. Esta e' a carga dessas materias: `legislativo` calcula o rito como o
  calcularia no evento (`rito-publico-da-proposicao`) e `transparencia` o grava so' onde ainda falta. Idempotente:
  rodar de novo nao troca nada (a materia ja' com rito e' pulada). Por Casa (cada leitura e escrita na tx do tenant)."
  (:require [oplenario.legislativo.components.repositorio :as repo-leg]
            [oplenario.transparencia.components.repositorio :as repo-tr]))

(defn republicar-rito!
  "Grava o rito nas materias da Casa `ente-id` que estao sem. Devolve {:sem-rito n :gravadas n :sem-rito-na-casa n}:
  quantas faltavam, quantas ganharam o rito e quantas seguem no mapa fixo porque a materia nao tem rito (sem template,
  ou rito que nao casa com o contrato publico)."
  [ds ente-id]
  (let [leg (repo-leg/map->RepoLegislativoPg {:datasource {:ds ds}})
        tr  (repo-tr/->RepoTransparenciaPg {:ds ds})
        ids (repo-tr/materias-sem-rito tr ente-id)
        gravadas (count (filter (fn [pid]
                                  (when-let [rito (repo-leg/rito-publico-da-proposicao leg ente-id pid)]
                                    (repo-tr/gravar-rito-ausente! tr ente-id pid rito)))
                                ids))]
    {:sem-rito (count ids) :gravadas gravadas :sem-rito-na-casa (- (count ids) gravadas)}))
