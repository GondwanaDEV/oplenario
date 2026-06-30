(ns oplenario.rotas
  "Composicao das ROTAS do host (§22.10): junta os handlers (oplenario.http) com a cadeia de interceptors
  (oplenario.interceptors) — fica separada de http.clj p/ evitar ciclo (http nao conhece interceptors). W2
  monta /saude (publica) + /eu (auth) + /painel-secretaria (auth + papel). W3 adiciona as rotas-dado de cada
  modulo (com o servidor `using` os Repo). `montar` recebe os deps ja injetados (idp + repo-identidade)."
  (:require [oplenario.http :as http]
            [oplenario.interceptors :as it]
            [oplenario.legislativo.diplomat.http.in :as legislativo-http]
            [oplenario.sessoes.components.repositorio :as repo-sessoes-comp]
            [oplenario.sessoes.diplomat.http.in :as sessoes-http]
            [oplenario.tempo-real.diplomat.sse :as tempo-real-sse]))

(set! *warn-on-reflection* true)

(defn montar
  "Conjunto de rotas Pedestal (table syntax) a partir dos deps do servidor. `erro`/`cabecalhos` sao GLOBAIS
  (it/globais prepended em http/servico) — nao por rota. Aqui: `autenticacao` resolve o ator; `exige-papel`
  faz a authz grossa. As verticais de modulo (W3+) fundem seus fragmentos de rota (diplomat/http/in/rotas),
  recebendo o interceptor `auth` compartilhado + o Repo-Component do modulo. O HOST e' a raiz de composicao
  (§22.10): so ele cruza modulos — p/ o endpoint SSE (G3) e a vertical de votacao ao vivo (Slice 3, no
  legislativo) injeta `consultar-sessao` (delega ao Repo de sessoes) nos diplomats de tempo_real e legislativo,
  que NAO importam sessoes."
  [{:keys [idp repo-identidade repo-sessoes repo-legislativo canal-store objeto-store]}]
  (let [auth (it/autenticacao idp repo-identidade)
        ;; cross-modulo via inversao de dependencia: o host fecha sobre o Repo de sessoes e expoe a consulta-fato
        ;; que o endpoint SSE (G3) E a vertical de votacao ao vivo (F4 Slice 3, no legislativo) precisam p/
        ;; autorizar (a RLS escopa por tenant). Os modulos chamam por esta fn, nunca importam sessoes (§22.10).
        consultar-sessao (fn [ente-id sessao-id] (repo-sessoes-comp/buscar-sessao repo-sessoes ente-id sessao-id))]
    (-> #{["/saude"             :get http/saude :route-name :saude]
          ["/eu"                :get [auth http/eu] :route-name :eu]
          ["/painel-secretaria" :get [auth (it/exige-papel "secretario") http/painel-secretaria]
           :route-name :painel-secretaria]}
        (into (sessoes-http/rotas {:auth auth :repo-sessoes repo-sessoes :objeto-store objeto-store}))
        (into (legislativo-http/rotas {:auth auth :repo-legislativo repo-legislativo
                                       :consultar-sessao consultar-sessao}))
        (into (tempo-real-sse/rotas {:auth auth :canal-store canal-store :consultar-sessao consultar-sessao})))))
