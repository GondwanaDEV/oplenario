(ns oplenario.sistema
  "Composicao do host (§22.10): system-map Component que faz o merge da infra do kernel + os
  sub-systems dos modulos. F0.1 fia o minimo (datasource); F0.2+ adicionam outbox-relay, scheduler e
  os Components de cada modulo via `using`. So recurso stateful e' componente. O host (raiz de
  composicao) PODE requerer modulos — e' aqui que os Repo-Components recebem o :datasource."
  (:require [com.stuartsierra.component :as component]
            [oplenario.cadastros.components.repositorio :as repo-cadastros]
            [oplenario.cadastros.relacoes.cadastro :as rel-cadastros]
            [oplenario.identidade.components.repositorio :as repo-identidade]
            [oplenario.identidade.relacoes.identidade :as rel-identidade]
            [oplenario.legislativo.components.repositorio :as repo-legislativo]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.components.http-servidor :as http-servidor]
            [oplenario.kernel.components.idp-dev :as idp-dev]
            [oplenario.kernel.components.outbox-relay :as outbox-relay]
            [oplenario.kernel.outbox :as outbox]
            [oplenario.rotas :as rotas]
            [oplenario.motor.components.registro-fatos :as registro-fatos]
            [oplenario.motor.components.repositorio :as repo-motor]
            [oplenario.sessoes.components.repositorio :as repo-sessoes]
            [oplenario.sessoes.relacoes.presenca :as rel-sessoes]
            [oplenario.tempo-real.components :as tr-comp]
            [oplenario.tempo-real.consumer :as tr-consumer]))

(defn- fundir-relacoes
  "Funde os mapas {nome → fn} de relação dos módulos FALHANDO em colisão de nome (fail-closed na borda
  do registry — o `merge` cru descartaria o duplicado em silêncio, a classe de erro que F2 existe p/
  barrar). Conforme o fanout (F4/F5) adiciona registradores, uma relação copiada p/ o módulo errado
  NÃO sobe."
  [& mapas]
  (reduce (fn [acc m]
            (when-let [dup (seq (filter (set (keys acc)) (keys m)))]
              (throw (ex-info "colisão de nome de relação entre módulos (fail-closed)" {:duplicadas (vec dup)})))
            (merge acc m))
          {} mapas))

(defn novo-sistema
  "Monta o sistema a partir do config carregado. Cresce por agregacao conforme os modulos chegam."
  [config]
  ;; §22.6 eixo G — backplane do tempo real: a CanalStore (em memoria, G2; Valkey em G3) e' construida eagerly
  ;; (sem Lifecycle) p/ que o registro de consumidores do bus feche sobre ela. O relay drena o shared.outbox e
  ;; despacha aos consumidores do projetor SSE => 'SSE e' projecao do bus interno'.
  (let [canal-store (tr-comp/canal-store-memoria)
        registro    (tr-consumer/registro canal-store)]
   (component/system-map
   :datasource      (datasource/datasource config)
   ;; EventBus (producer): grava no shared.outbox na tx do ato. Stateless (sem Lifecycle); os Repo que
   ;; emitem eventos de dominio o recebem via `using`.
   :bus             (outbox/bus)
   :canal-store     canal-store
   ;; relay (lider unico): drena o outbox e despacha ao projetor SSE (registro). Depende de :datasource.
   :relay           (component/using (outbox-relay/relay {:registro registro}) [:datasource])
   :repo-cadastros  (component/using (repo-cadastros/repositorio) [:datasource])
   :repo-identidade (component/using (repo-identidade/repositorio) [:datasource])
   :repo-legislativo (component/using (repo-legislativo/repositorio) [:datasource :bus])
   ;; §22.6 eixo G: o Repo de sessoes recebe :bus — os caminhos de escrita emitem os eventos de tempo real
   ;; (sessao/presenca/fala) no shared.outbox na tx do ato; o projetor SSE (G2) os consome.
   :repo-sessoes    (component/using (repo-sessoes/repositorio) [:datasource :bus])
   :repo-motor      (component/using (repo-motor/repositorio) [:datasource])
   ;; o host É a fronteira (§22.10): importa as `relacoes` dos módulos e as injeta no registry do motor.
   ;; O motor chama por nome (resolver-para), nunca importa o módulo. Sem :datasource — a `tx` do tenant
   ;; entra por-chamada (quem avalia abre a tx via Repo). O `start` roda o assert de costura (fail-closed).
   :registro-fatos  (registro-fatos/registro-fatos
                     (fundir-relacoes rel-cadastros/relacoes rel-identidade/relacoes rel-sessoes/relacoes)))))

(defn- idp-para
  "Seleciona a impl do IdP por ambiente — GUARD DE BOOT fail-closed (review de seguranca W2, CRÍTICO): producao
  EXIGE a impl Keycloak; como ela e' carry F1.4 (indisponivel), `production` LANCA e bloqueia o boot — NUNCA cai
  no idp-dev (que confia claims sem verificar assinatura). dev/test usam idp-dev."
  [config]
  (if (= "production" (:env config))
    (throw (ex-info "idp-dev proibido em producao e a impl Keycloak e' carry F1.4 (indisponivel) — boot bloqueado"
                    {:env (:env config)}))
    (idp-dev/idp-dev)))

(defn sistema-serve
  "Sistema do host com o SERVIDOR HTTP (caminho `serve` do main). Separado de `novo-sistema` p/ os testes de
  boot do dominio (sistema_test/motor/repo/marco) NAO subirem o Jetty (sem bind de porta em teste). W1 serve so
  /saude; W2/W3 enriquecem as rotas (auth/tenancy + rotas-dado de modulo, com o servidor `using` os Repo)."
  [config]
  (assoc (novo-sistema config)
         ;; IdP por ambiente (idp-para = guard: idp-dev so dev/test; prod exige Keycloak, carry F1.4 -> lanca).
         :idp (idp-para config)
         ;; servidor `using` idp + repo-identidade -> a rotas-fn (rotas/montar) monta o interceptor de auth
         ;; sobre as instancias iniciadas. W3: +repo-sessoes p/ a vertical de rotas de sessoes (o fan-out por
         ;; modulo acrescenta cada Repo aqui). G3: +canal-store p/ o endpoint SSE do painel ao vivo.
         :servidor-http (component/using
                         (http-servidor/servidor-http config rotas/montar)
                         [:idp :repo-identidade :repo-sessoes :canal-store])))
