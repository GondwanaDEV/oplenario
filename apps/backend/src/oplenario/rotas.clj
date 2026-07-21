(ns oplenario.rotas
  "Composicao das ROTAS do host (§22.10): junta os handlers (oplenario.http) com a cadeia de interceptors
  (oplenario.interceptors) — fica separada de http.clj p/ evitar ciclo (http nao conhece interceptors). W2
  monta /saude (publica) + /eu (auth) + /painel-secretaria (auth + papel). W3 adiciona as rotas-dado de cada
  modulo (com o servidor `using` os Repo). `montar` recebe os deps ja injetados (idp + repo-identidade)."
  (:require [oplenario.cadastros.components.repositorio :as repo-cadastros-comp]
            [oplenario.cadastros.diplomat.http.in :as cadastros-http]
            [oplenario.compliance.diplomat.http.in :as compliance-http]
            [oplenario.config :as config]
            [oplenario.http :as http]
            [oplenario.identidade.components.repositorio :as repo-identidade-comp]
            [oplenario.identidade.diplomat.http.auth-in :as auth-http]
            [oplenario.identidade.diplomat.http.in :as identidade-http]
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

(defn resolver-vereador
  "identidade-id -> vereador-id NESTA Casa — host wiring (§22.5.3, exceção nomeada; mesma forma de
  `membros-da-casa`/`resolver-municipio` em `montar`). Resolve via o Repo-Component de `cadastros`
  (`vereador-por-identidade`) e devolve só o `:id`. `nil` quando a identidade não tem cadastro de
  vereador NESTE ente — não é erro: a borda `/meu` (Onda C1, `legislativo`) trata como painel vazio,
  nunca 500. O `legislativo` recebe esta fn JÁ RESOLVIDA pelo host (§22.10) — nunca importa `cadastros`.
  Extraída como defn de topo (em vez de closure só-inline) p/ ser testável direto contra Postgres real,
  sem subir o sistema inteiro (mesmo racional de `presenca-resumo-wire`/`esic-cumprimento-wire`)."
  [repo-cadastros ente-id identidade-id]
  (:id (repo-cadastros-comp/vereador-por-identidade repo-cadastros ente-id identidade-id)))

(defn montar
  "Conjunto de rotas Pedestal (table syntax) a partir dos deps do servidor. `erro`/`cabecalhos` sao GLOBAIS
  (it/globais prepended em http/servico) — nao por rota. Aqui: `autenticacao` resolve o ator; `exige-papel`
  faz a authz grossa. As verticais de modulo (W3+) fundem seus fragmentos de rota (diplomat/http/in/rotas),
  recebendo o interceptor `auth` compartilhado + o Repo-Component do modulo. O HOST e' a raiz de composicao
  (§22.10): so ele cruza modulos — p/ o endpoint SSE (G3) e a vertical de votacao ao vivo (Slice 3, no
  legislativo) injeta `consultar-sessao` (delega ao Repo de sessoes) nos diplomats de tempo_real e legislativo,
  que NAO importam sessoes."
  [{:keys [idp repo-identidade repo-sessoes repo-legislativo repo-compliance repo-participacao
           repo-transparencia repo-paineis repo-cadastros canal-store objeto-store painel-compliance
           presenca-resumo esic-cumprimento relatores-pendentes info-ente registro-fatos
           keycloak sessao identidade-existe? ficha-vereador-publica]}]
  (let [auth (it/autenticacao idp repo-identidade)
        ;; F6: relogio de producao (kernel/tempo) p/ o prazo LAI do e-SIC — determinismo em teste vem de
        ;; injetar relogio-fixo direto no fragmento de rotas (participacao-http/rotas). resolver-ente-publico
        ;; = seam da rota PUBLICA (sem ator): mapeia o :ente do path -> ente-id (V1 = UUID coagido fail-closed);
        ;; o Repo abre com-tenant* com ele e a RLS isola. Slug humano = refino futuro.
        ;; relogio de PRODUCAO compartilhado (stateless — le o relogio do sistema a cada chamada, kernel/tempo):
        ;; participacao (prazo LAI) e legislativo (Onda B Slice 5, `agora` do gatilho de emissao do parecer,
        ;; review MEDIUM fe-11-parecer) usam a MESMA instancia; determinismo em teste vem de cada fragmento de
        ;; rotas receber `relogio-fixo` no lugar, direto.
        relogio-producao (tempo/relogio-sistema)
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
        ;; Onda B Slice 2: uf/nome-do-municipio do ente, p/ o legislativo computar a URN em protocolar! —
        ;; mesma inversao de dependencia de consultar-sessao/membros-da-casa/info-ente (§22.10).
        resolver-municipio (fn [ente-id] (repo-cadastros-comp/uf-e-municipio repo-cadastros ente-id))
        ;; Onda C1: identidade->vereador-id NESTA Casa, injetado na borda /meu do legislativo (mesma
        ;; inversao de dependencia de resolver-municipio/membros-da-casa; nome DISTINTO do defn de topo
        ;; `resolver-vereador` p/ nao sombrear — a chave passada a legislativo-http/rotas continua
        ;; :resolver-vereador).
        resolver-vereador-fn (fn [ente-id identidade-id] (resolver-vereador repo-cadastros ente-id identidade-id))
        ;; Fix da review Onda E fatia 2 (achados I-1+M-1): autor-id cru do corpo de POST/PATCH proposicao
        ;; vira o elo de autoria PUBLICA (transparencia.materia) — precisa apontar pra um vereador de
        ;; verdade NESTE ente antes de virar afirmacao publica. Mesma inversao de dependencia de
        ;; resolver-vereador/resolver-municipio; reusa `buscar-vereador` (ja' ente-escopado, RLS + filtro
        ;; explicito) — "vinculo" aqui e' CADASTRO existente neste ente, mesmo contrato fraco de
        ;; resolver-vereador (nao exige mandato vigente; apertar p/ so' mandato ativo fica de carry se um
        ;; cliente pedir). `legislativo` recebe so' esta fn ja' resolvida, nunca importa cadastros (§22.10).
        vereador-vinculado? (fn [ente-id vereador-id]
                              (some? (repo-cadastros-comp/buscar-vereador repo-cadastros ente-id vereador-id)))
        ;; Override injetavel (mesmo racional de `painel-compliance` — so' serve aos testes DB-free da borda
        ;; de paineis); em producao `montar` e' chamado sem estas chaves e o `or` fecha sobre o repo real.
        presenca-resumo (or presenca-resumo
                            (fn [ente-id] (sessoes-http/presenca-resumo-wire repo-sessoes membros-da-casa ente-id)))
        ;; FE Onda A1: cumprimento de prazo do e-SIC injetado no dashboard da Mesa (mesma inversao de
        ;; dependencia; consumido por /paineis/mesa — task A6).
        esic-cumprimento (or esic-cumprimento
                             (fn [ente-id] (participacao-http/esic-cumprimento-wire repo-participacao ente-id)))
        ;; FE Onda A1: fila de relatores pendentes (self-contained no legislativo — sem cross-modulo);
        ;; consumido por /paineis/mesa (mesmo padrao de presenca-resumo/esic-cumprimento — task A6).
        relatores-pendentes (or relatores-pendentes
                                (fn [ente-id] (legislativo-http/relatores-pendentes-wire repo-legislativo ente-id)))
        ;; F7 dashboard da Mesa: o host compoe compliance+paineis por INVERSAO DE DEPENDENCIA (espelha
        ;; consultar-sessao). Fecha sobre o repo de compliance e expoe uma fn (ente-id -> PainelOut projetado)
        ;; que o diplomat de paineis chama — paineis nunca importa compliance (§22.10). Passa pelo diplomat de
        ;; compliance (painel-wire), nunca pelo seu adapters/out direto (a lint proibe host->adapters). O
        ;; override injetavel (`painel-compliance` no arg) so' serve aos testes DB-free da borda de paineis.
        painel-compliance (or painel-compliance
                              (fn [ente-id] (compliance-http/painel-wire repo-compliance ente-id)))
        ;; FE Onda A2 fast-follow: nome real do ente injetado no portal publico (barra institucional/rodape
        ;; mostravam o UUID cru da rota) — mesma inversao de dependencia de consultar-sessao/membros-da-casa;
        ;; transparencia nunca importa cadastros (§22.10). Ente sem perfil cadastrado -> nil -> 404 na borda.
        info-ente (or info-ente
                      (fn [ente-id] (repo-cadastros-comp/buscar-ente repo-cadastros ente-id)))
        ;; Onda E fatia 2 (Task 4): a IDENTIDADE do vereador no perfil PUBLICO — irmao de `info-ente`, mesma
        ;; inversao de dependencia (transparencia nunca importa cadastros, §22.10). `ficha-vereador` e' de
        ;; aridade 4 e a 4a e' a DATA que decide mandato vigente + comissoes vigentes: usa o mesmo `hoje` no
        ;; fuso civil de `membros-da-casa` (nao `LocalDate/now` do fuso do container — um deploy em UTC
        ;; viraria o dia 3h antes e um mandato encerrado ontem ainda apareceria vigente). Vereador
        ;; inexistente NESTA Casa -> nil -> 404 fail-closed na borda (nunca 200 com perfil vazio).
        ficha-vereador-publica
        (or ficha-vereador-publica
            (fn [ente-id vereador-id]
              (repo-cadastros-comp/ficha-vereador repo-cadastros ente-id vereador-id
                                                  (tempo/hoje (tempo/relogio-sistema)
                                                              (java.time.ZoneId/of "America/Fortaleza")))))
        ;; Onda D Slice 5 Task 9: guard de SERVICO — cadastros NUNCA importa identidade (§22.10) e nao ha'
        ;; FK cross-schema em cadastros.vereador.identidade_id (so' GUARD ref). O host injeta a existencia
        ;; via o Repo-Component de identidade (`identidade-existe?`, SUPRATENANT); mesma inversao de
        ;; dependencia de info-ente/consultar-sessao/resolver-municipio. Override injetavel p/ os testes
        ;; DB-free da borda de cadastros. Review Task 12 IMPORTANT: usa a leitura ESTREITA
        ;; `repo/identidade-existe?` (SELECT 1), NAO `identidade-por-id` — este guard so' precisa de um
        ;; booleano e nao deveria materializar CPF+nome so' pra jogar os dois fora.
        identidade-existe? (or identidade-existe?
                               (fn [ident-id] (repo-identidade-comp/identidade-existe? repo-identidade ident-id)))
        ;; Onda D Slice 2 Task 3: identidade/auth-in (GET /auth/descoberta/:ente, rota PUBLICA pre-login)
        ;; reusa este MESMO `info-ente` (existencia = `(some? (info-ente id))`) — inversao de dependencia
        ;; sobre cadastros, mesma forma de resolver-municipio/membros-da-casa; identidade nunca importa
        ;; cadastros (§22.10). Tambem expoe o nome PUBLICO do ente na resposta de descoberta.
        ;; `keycloak` = o bloco :keycloak da config (realm-prefixo/base-url-publico/web-client-id) que
        ;; auth-in usa p/ montar a resposta de descoberta. Injetavel p/ os testes DB-free da borda;
        ;; em producao cai no default carregado do config.edn+env (mesmo racional dos demais seams `or`).
        keycloak (or keycloak (:keycloak (config/carregar)))
        ;; Onda D Slice 2 Task 4: POST /auth/sessoes (mint) precisa do bloco :sessao da config
        ;; (:absoluta-h/:ociosa-min) — mesmo padrao `or` de `keycloak`/`info-ente` acima (fallback pra
        ;; config/carregar aqui no HOST; auth-http/rotas recebe ja' resolvido, nunca chama config/carregar
        ;; ela mesma).
        sessao (or sessao (:sessao (config/carregar)))]
    (-> #{["/saude"             :get http/saude :route-name :saude]
          ["/eu"                :get [auth http/eu] :route-name :eu]
          ["/painel-secretaria" :get [auth (it/exige-papel "secretario") http/painel-secretaria]
           :route-name :painel-secretaria]}
        (into (sessoes-http/rotas {:auth auth :repo-sessoes repo-sessoes :objeto-store objeto-store
                                   :resolver-vereador resolver-vereador-fn :relogio relogio-producao}))
        (into (legislativo-http/rotas {:auth auth :repo-legislativo repo-legislativo
                                       :consultar-sessao consultar-sessao
                                       :resolver-municipio resolver-municipio
                                       :resolver-vereador resolver-vereador-fn
                                       :vereador-vinculado? vereador-vinculado?
                                       :registro registro-fatos
                                       :relogio relogio-producao}))
        (into (compliance-http/rotas {:auth auth :repo-compliance repo-compliance}))
        (into (cadastros-http/rotas {:auth auth :repo-cadastros repo-cadastros :relogio relogio-producao
                                     :identidade-existe? identidade-existe?}))
        (into (participacao-http/rotas {:auth auth :repo-participacao repo-participacao
                                        :resolver-ente-publico participacao-http/resolver-ente-publico-uuid
                                        :relogio relogio-producao}))
        (into (transparencia-http/rotas {:auth auth :repo-transparencia repo-transparencia
                                         :resolver-ente-publico transparencia-http/resolver-ente-publico-uuid
                                         :objeto-store objeto-store
                                         :info-ente info-ente
                                         :ficha-vereador-publica ficha-vereador-publica}))
        (into (paineis-http/rotas {:auth auth :repo-paineis repo-paineis
                                   :painel-compliance painel-compliance
                                   :presenca-resumo presenca-resumo
                                   :esic-cumprimento esic-cumprimento
                                   :relatores-pendentes relatores-pendentes}))
        (into (tempo-real-sse/rotas {:auth auth :canal-store canal-store :consultar-sessao consultar-sessao}))
        (into (auth-http/rotas {:info-ente info-ente :keycloak keycloak
                                :idp idp :repo-identidade repo-identidade
                                :relogio relogio-producao :sessao sessao}))
        (into (identidade-http/rotas {:auth auth :repo-identidade repo-identidade :idp idp})))))
