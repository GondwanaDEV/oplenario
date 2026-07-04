(ns oplenario.rotas
  "Composicao das ROTAS do host (§22.10): junta os handlers (oplenario.http) com a cadeia de interceptors
  (oplenario.interceptors) — fica separada de http.clj p/ evitar ciclo (http nao conhece interceptors). W2
  monta /saude (publica) + /eu (auth) + /painel-secretaria (auth + papel). W3 adiciona as rotas-dado de cada
  modulo (com o servidor `using` os Repo). `montar` recebe os deps ja injetados (idp + repo-identidade)."
  (:require [oplenario.cadastros.components.repositorio :as repo-cadastros-comp]
            [oplenario.compliance.diplomat.http.in :as compliance-http]
            [oplenario.http :as http]
            [oplenario.interceptors :as it]
            [oplenario.kernel.tempo :as tempo]
            [oplenario.legislativo.diplomat.http.in :as legislativo-http]
            [oplenario.paineis.diplomat.http.in :as paineis-http]
            [oplenario.participacao.diplomat.http.in :as participacao-http]
            [oplenario.sessoes.components.repositorio :as repo-sessoes-comp]
            [oplenario.sessoes.diplomat.http.in :as sessoes-http]
            [oplenario.tempo-real.diplomat.sse :as tempo-real-sse]
            [oplenario.transparencia.diplomat.http.in :as transparencia-http]))

(set! *warn-on-reflection* true)

(defn montar
  "Conjunto de rotas Pedestal (table syntax) a partir dos deps do servidor. `erro`/`cabecalhos` sao GLOBAIS
  (it/globais prepended em http/servico) — nao por rota. Aqui: `autenticacao` resolve o ator; `exige-papel`
  faz a authz grossa. As verticais de modulo (W3+) fundem seus fragmentos de rota (diplomat/http/in/rotas),
  recebendo o interceptor `auth` compartilhado + o Repo-Component do modulo. O HOST e' a raiz de composicao
  (§22.10): so ele cruza modulos — p/ o endpoint SSE (G3) e a vertical de votacao ao vivo (Slice 3, no
  legislativo) injeta `consultar-sessao` (delega ao Repo de sessoes) nos diplomats de tempo_real e legislativo,
  que NAO importam sessoes."
  [{:keys [idp repo-identidade repo-sessoes repo-legislativo repo-compliance repo-participacao
           repo-transparencia repo-paineis repo-cadastros canal-store objeto-store painel-compliance]}]
  (let [auth (it/autenticacao idp repo-identidade)
        ;; F6: relogio de producao (kernel/tempo) p/ o prazo LAI do e-SIC — determinismo em teste vem de
        ;; injetar relogio-fixo direto no fragmento de rotas (participacao-http/rotas). resolver-ente-publico
        ;; = seam da rota PUBLICA (sem ator): mapeia o :ente do path -> ente-id (V1 = UUID coagido fail-closed);
        ;; o Repo abre com-tenant* com ele e a RLS isola. Slug humano = refino futuro.
        relogio-participacao (tempo/relogio-sistema)
        ;; cross-modulo via inversao de dependencia: o host fecha sobre o Repo de sessoes e expoe a consulta-fato
        ;; que o endpoint SSE (G3) E a vertical de votacao ao vivo (F4 Slice 3, no legislativo) precisam p/
        ;; autorizar (a RLS escopa por tenant). Os modulos chamam por esta fn, nunca importam sessoes (§22.10).
        consultar-sessao (fn [ente-id sessao-id] (repo-sessoes-comp/buscar-sessao repo-sessoes ente-id sessao-id))
        ;; FE Onda A1: membros-da-casa injetado em sessoes (presenca agregada) — mesma inversao de
        ;; dependencia de consultar-sessao/painel-compliance; ZoneId fixo (fuso civil, mesmo racional de
        ;; participacao/controllers.clj).
        membros-da-casa (fn [ente-id]
                          (repo-cadastros-comp/membros-da-casa repo-cadastros ente-id
                                                                (tempo/hoje (tempo/relogio-sistema)
                                                                            (java.time.ZoneId/of "America/Fortaleza"))))
        presenca-resumo (fn [ente-id] (sessoes-http/presenca-resumo-wire repo-sessoes membros-da-casa ente-id))
        ;; FE Onda A1: cumprimento de prazo do e-SIC injetado no dashboard da Mesa (mesma inversao de
        ;; dependencia; consumido por uma task futura que compoe /paineis/mesa).
        esic-cumprimento (fn [ente-id] (participacao-http/esic-cumprimento-wire repo-participacao ente-id))
        ;; FE Onda A1: fila de relatores pendentes (self-contained no legislativo — sem cross-modulo);
        ;; consumido por uma task futura que compoe /paineis/mesa (mesmo padrao de presenca-resumo/esic-cumprimento).
        relatores-pendentes (fn [ente-id] (legislativo-http/relatores-pendentes-wire repo-legislativo ente-id))
        ;; F7 dashboard da Mesa: o host compoe compliance+paineis por INVERSAO DE DEPENDENCIA (espelha
        ;; consultar-sessao). Fecha sobre o repo de compliance e expoe uma fn (ente-id -> PainelOut projetado)
        ;; que o diplomat de paineis chama — paineis nunca importa compliance (§22.10). Passa pelo diplomat de
        ;; compliance (painel-wire), nunca pelo seu adapters/out direto (a lint proibe host->adapters). O
        ;; override injetavel (`painel-compliance` no arg) so' serve aos testes DB-free da borda de paineis.
        painel-compliance (or painel-compliance
                              (fn [ente-id] (compliance-http/painel-wire repo-compliance ente-id)))]
    (-> #{["/saude"             :get http/saude :route-name :saude]
          ["/eu"                :get [auth http/eu] :route-name :eu]
          ["/painel-secretaria" :get [auth (it/exige-papel "secretario") http/painel-secretaria]
           :route-name :painel-secretaria]}
        (into (sessoes-http/rotas {:auth auth :repo-sessoes repo-sessoes :objeto-store objeto-store}))
        (into (legislativo-http/rotas {:auth auth :repo-legislativo repo-legislativo
                                       :consultar-sessao consultar-sessao}))
        (into (compliance-http/rotas {:auth auth :repo-compliance repo-compliance}))
        (into (participacao-http/rotas {:auth auth :repo-participacao repo-participacao
                                        :resolver-ente-publico participacao-http/resolver-ente-publico-uuid
                                        :relogio relogio-participacao}))
        (into (transparencia-http/rotas {:auth auth :repo-transparencia repo-transparencia
                                         :resolver-ente-publico transparencia-http/resolver-ente-publico-uuid
                                         :objeto-store objeto-store}))
        (into (paineis-http/rotas {:auth auth :repo-paineis repo-paineis
                                   :painel-compliance painel-compliance}))
        (into (tempo-real-sse/rotas {:auth auth :canal-store canal-store :consultar-sessao consultar-sessao})))))
