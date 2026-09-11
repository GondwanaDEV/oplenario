(ns oplenario.interceptors
  "Interceptors do host (§22.5 — a borda de autorizacao). AUTENTICACAO: bearer token -> idp/verificar-token ->
  identidade/resolver-sessao -> `ator` em (:request :ator), fail-closed (401). AUTORIZACAO GROSSA: exige-papel
  (papel estatico do snapshot) -> 403 via kernel/autorizacao (que lanca negado?). ERRO: mapeia a negacao de
  autorizacao -> 403; o resto -> 500. A camada FINA (policy.check in-domain) roda nos controllers (W3+), com o
  recurso carregado. (§22.5: authz avaliada na borda; a tenancy [GUC app.ente_id] e' por-tx no Repo.)"
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [io.pedestal.interceptor.chain :as chain]
            [jsonista.core :as json]
            [oplenario.http :as http]
            [oplenario.identidade.autenticacao :as auten]
            [oplenario.identidade.components.repositorio :as repo]
            [oplenario.kernel.autorizacao :as authz]
            [oplenario.kernel.components.idp :as idp]))

(set! *warn-on-reflection* true)

(defn- bearer [req]
  (let [h (get-in req [:headers "authorization"])]
    (when (and h (str/starts-with? h "Bearer ")) (subs h 7))))

(defn cookie-sessao
  "Extrai o valor do cookie `sessao` do header CRU `Cookie` do request (RFC 6265: pares `nome=valor`
  separados por `; `). PURA — le so `(:headers req)`, sem IO. Home aqui (nao no modulo identidade) por ser
  preocupacao CROSS-CUTTING do host, mesmo racional do `bearer` acima (que faz o mesmo p/ Authorization) —
  reusada pelo logout handler PUBLICO de identidade (Onda D Slice 2 Task 5, `apagar-sessao!` no cookie sem
  exigir interceptor) E sera' reusada pelo interceptor `sessao-cookie` (Task 6, ainda nao construido) sem
  duplicar o parsing. Sem header -> nil. `sessao=` ausente entre os pares -> nil. Par `sessao=` com valor
  VAZIO -> nil (trata como ausente; um cookie sessao='' nunca e' um segredo valido). Multiplos cookies no
  mesmo header (qualquer ordem) -> encontra o par certo."
  [req]
  (when-let [h (get-in req [:headers "cookie"])]
    (some (fn [par]
            (when (str/starts-with? par "sessao=")
              (let [v (subs par (count "sessao="))]
                (when-not (str/blank? v) v))))
          (map str/trim (str/split h #";")))))

(defn- nega! [ctx status razao]
  (chain/terminate (assoc ctx :response (http/json-resposta status {:erro razao}))))

(defn autenticacao
  "Interceptor de AUTENTICACAO (§22.5 eixo D). PRECEDENCIA: sessao de COOKIE primeiro (login real, Onda D
  Slice 2); senao BEARER (dev-token/servico — mantido vivo). Sem credencial / invalido / sem vinculo -> 401
  (fail-closed). Sucesso -> `ator` em (:request :ator). A sessao de cookie roda `resolver-sessao` A CADA
  request (authz viva: vinculo revogado derruba a sessao na hora, nao espera a expiracao do cookie)."
  [idp repo-identidade]
  {:name  ::autenticacao
   :enter (fn [ctx]
            (if-let [seg (cookie-sessao (:request ctx))]
              (if-let [claims (repo/resolver-sessao-por-segredo repo-identidade seg)]  ; {:identidade-id :ente-id} ou nil
                (if-let [ator (auten/resolver-sessao repo-identidade claims)]
                  (assoc-in ctx [:request :ator] ator)
                  (nega! ctx 401 "sem vinculo ativo"))
                (nega! ctx 401 "sessao invalida"))
              (if-let [tok (bearer (:request ctx))]
                (if-let [claims (idp/verificar-token idp tok)]
                  (if-let [ator (auten/resolver-sessao repo-identidade claims)]
                    (assoc-in ctx [:request :ator] ator)
                    (nega! ctx 401 "sem vinculo ativo"))
                  (nega! ctx 401 "token invalido"))
                (nega! ctx 401 "sem credencial"))))})

(def ^:private max-corpo-bytes
  "Teto do corpo de request JSON (256 KiB). Barra exaustao de heap por payload unico (review seg W3 MAJOR-1).
  Dominio de borda (criar/agendar) nao tem corpo grande; uploads vao por outra rota dedicada quando existirem."
  (* 256 1024))

(defn- ler-limitado
  "Le o InputStream ate `limite` bytes; estoura :corpo/grande se exceder — nunca aloca alem do teto."
  ^bytes [^java.io.InputStream in limite]
  (let [out (java.io.ByteArrayOutputStream.)
        buf (byte-array 8192)]
    (loop [total 0]
      (let [n (.read in buf)]
        (if (neg? n)
          (.toByteArray out)
          (let [t (+ total (long n))]
            (when (> t (long limite)) (throw (ex-info "corpo grande demais" {:tipo :corpo/grande})))
            (.write out buf 0 n)
            (recur t)))))))

(def corpo-json
  "Interceptor de NEGOCIACAO DE CONTEUDO de entrada (a borda anunciada em W1): parseia o corpo JSON em
  (:request :json-params) com chaves STRING. So age em content-type application/json com corpo presente
  (GET/sem-corpo passam direto). Decisoes de seguranca (review W3): (1) corpo limitado a max-corpo-bytes ->
  413 (anti-DoS de heap); (2) chaves STRING, NUNCA keyword — keyword JSON interna no metaspace e nao e' GC'd,
  entao chaves arbitrarias do cliente seriam um vazamento permanente (DoS). Cada adapters/in coage so as
  chaves esperadas p/ keyword. JSON malformado -> 400 fail-closed (nunca 500). Reusavel por toda rota de escrita
  no fan-out W3+; o Pedestal 0.7 default-interceptors NAO parseia corpo."
  {:name  ::corpo-json
   :enter (fn [ctx]
            (let [req (:request ctx)
                  ct  (get-in req [:headers "content-type"])]
              (if (and ct (str/starts-with? ct "application/json") (:body req))
                (try
                  (assoc-in ctx [:request :json-params]
                            (json/read-value (ler-limitado (:body req) max-corpo-bytes)))
                  (catch clojure.lang.ExceptionInfo e
                    (if (= :corpo/grande (:tipo (ex-data e)))
                      (chain/terminate (assoc ctx :response (http/json-resposta 413 {:erro "corpo grande demais"})))
                      (chain/terminate (assoc ctx :response (http/json-resposta 400 {:erro "json invalido"})))))
                  (catch Exception e
                    ;; corpo malformado e' erro de cliente (400), mas nao silencioso: loga p/ distinguir
                    ;; bad-client de bug de parsing/dependencia (review W3 — sem `catch _` cego).
                    (log/debug e "corpo JSON invalido na borda; respondendo 400")
                    (chain/terminate (assoc ctx :response (http/json-resposta 400 {:erro "json invalido"})))))
                ctx)))})

(defn exige-papel
  "Interceptor de AUTORIZACAO GROSSA: exige o `papel` estatico (STRING — os papeis do snapshot sao strings) no
  ator. Falta -> authz lanca negado? -> o interceptor `erro` mapeia p/ 403. Pressupoe `autenticacao` antes."
  [papel]
  {:name  (keyword "oplenario.interceptors" (str "exige-papel--" papel))
   :enter (fn [ctx]
            (authz/exige-papel! (get-in ctx [:request :ator]) papel)
            ctx)})

(defn- raiz
  "Desembrulha a excecao que o Pedestal repassa ao :error: o original que a interceptor-chain pegou vem como
  CAUSE do wrapper (Pedestal 0.7); algumas versoes expoem em (:exception ex-data). Checa ambos + o proprio ex."
  [ex]
  (or (:exception (ex-data ex)) (ex-cause ex) ex))

(defn- validacao?
  "True se a excecao (ou sua raiz) e' falha de validacao de borda (adapters/in) -> 400."
  [ex]
  (= :validacao/invalido (or (:tipo (ex-data ex)) (:tipo (ex-data (raiz ex))))))

(defn- limite-excedido
  "A `ex-data` de uma excecao de TETO — `:tipo` no namespace `limite` (ex.: `:limite/datas-excedido`,
  `:limite/linhas-excedido`) — na propria excecao ou na sua raiz; nil se nao e' uma. Reconhece o NAMESPACE
  e nao cada `:tipo` de proposito: todo teto novo (e a casa cria um por predicado de cardinalidade aberta —
  ver `vereador/teto-de-datas-lote`, `rotas/teto-de-janelas`) nasce mapeado, em vez de nascer como 500
  opaco ate' alguem lembrar de registra-lo aqui."
  [ex]
  (some (fn [d] (when (= "limite" (some-> (:tipo d) namespace)) d))
        [(ex-data ex) (ex-data (raiz ex))]))

(defn- config-do-tenant
  "A `ex-data` de uma recusa por CONFIGURACAO DO TENANT — `:tipo` no namespace `config` (ex.:
  `:config/rito-ambiguo-na-especie`) — na propria excecao ou na sua raiz; nil se nao e' uma. Reconhece o
  NAMESPACE e nao cada `:tipo`, mesma disciplina do `limite-excedido` acima e pelo mesmo motivo: toda
  recusa de config nova nasce mapeada, em vez de nascer como 500 opaco ate' alguem lembrar de registra-la
  aqui."
  [ex]
  (some (fn [d] (when (= "config" (some-> (:tipo d) namespace)) d))
        [(ex-data ex) (ex-data (raiz ex))]))

(def erro
  "Interceptor de ERRO (GLOBAL/outermost via http/servico): validacao de borda -> 400; negacao de autorizacao
  -> 403; config do tenant que impede a operacao -> 409 NOMEADO; teto de capacidade estourado -> 422 com o
  numero MEDIDO; resto -> 500. Nao vaza detalhe de erro interno no corpo.

  POR QUE CONFIG DE TENANT E' 409, e nao 400/422/500. O caso que abriu este ramo: a Casa cadastra dois
  ritos ativos para a MESMA especie de materia e `db/proposicao/resolver-rito!` recusa em vez de escolher
  o rito dela por conta. Nao e' 400 — o corpo do pedido estava correto, e reenvia-lo com outro corpo nao
  ajuda; nada que o cliente escreva conserta. Nao e' 422, que neste servico ja' tem uma forma propria
  (teto de capacidade, com `:medido`/`:teto`) e diria 'voce pediu demais', que e' falso. E nao e' 500: a
  configuracao e' DADO DA CASA, escrito pela Casa (Inv.4), e o operador pode conserta-la — devolver 5xx
  alarmaria quem opera a plataforma e esconderia do unico que pode agir qual e' o conserto. 409 e' o
  codigo que este projeto ja' usa para 'o pedido estava certo; o estado atual do recurso o recusa', em 7
  bordas — e e' EXATAMENTE o mesmo codigo que a borda irma ja' devolve para `:conflito/sem-rito` (a Casa
  nao declarou rito nenhum). 'Nenhum rito' e 'dois ritos' sao o mesmo fato — a configuracao de rito da
  Casa nao resolve esta materia — e dar-lhes codigos diferentes seria incoerente.

  O CORPO NOMEIA A CAUSA, com a mesma parcimonia do ramo 422: nao vai a mensagem da excecao (regra do
  corpo opaco continua valendo), vai o `name` do proprio `:tipo` como `:causa` (codigo estavel, legivel
  por maquina) e a `:especie` quando a ex-data a traz — que e' o unico dado acionavel: diz em QUAL especie
  de materia estao os ritos duplicados. Sem isso o operador teria de auditar a tabela de ritos inteira.

  POR QUE O 422 CARREGA NUMERO (unica excecao a regra de corpo opaco): um teto e' uma decisao de PRODUTO,
  nao um detalhe de implementacao — o operador que pediu um periodo grande demais precisa saber quanto
  pediu e qual e' o limite para reduzir o pedido, senao a unica saida e' tentativa e erro. So' as chaves
  `:medido`/`:teto` da ex-data saem; o resto (mensagem, causa, contexto interno) fica no log, como nos
  demais ramos. `:medido-e-piso` marca a medicao que e' um PISO e nao a contagem exata (o caso do teto
  aplicado via `:max-rows`, em que o driver para de contar no primeiro excedente)."
  {:name  ::erro
   :error (fn [ctx ex]
            (let [teto (limite-excedido ex)
                  conf (config-do-tenant ex)]
             (cond
              (validacao? ex)
              (assoc ctx :response (http/json-resposta 400 {:erro "requisicao invalida"}))
              (or (authz/negado? ex) (authz/negado? (raiz ex)))
              (assoc ctx :response (http/json-resposta 403 {:erro "autorizacao negada"}))
              (some? conf)
              (assoc ctx :response
                     (http/json-resposta 409
                       (cond-> {:erro "a configuracao desta Casa impede esta operacao"
                                :causa (name (:tipo conf))}
                         (some? (:especie conf)) (assoc :especie (:especie conf)))))
              (some? teto)
              (assoc ctx :response
                     (http/json-resposta 422
                       (cond-> {:erro "limite excedido"
                                :medido (or (:medido teto) (:medido-ao-menos teto))
                                :teto (:teto teto)}
                         (some? (:medido-ao-menos teto)) (assoc :medido-e-piso true))))
              :else
              ;; 500 nao tratado (ex.: drift de projecao adapters/out vs contrato wire — review sec MÉDIO-2):
              ;; LOGA a excecao no servidor (com a causa/`:campos` na ex-data) antes de devolver o corpo
              ;; opaco — senao o bug fica invisivel. O corpo NUNCA carrega detalhe interno.
              (do (log/error ex "erro interno nao tratado na cadeia de borda")
                  (assoc ctx :response (http/json-resposta 500 {:erro "erro interno"}))))))})

(def cabecalhos-seguranca
  "Interceptor de cabecalhos de seguranca (review W2): no-store (respostas de auth nao cacheiam em proxy/browser)
  + nosniff + DENY de frame. HSTS/CSP ficam no reverse-proxy (API JSON)."
  {:name  ::cabecalhos-seguranca
   :leave (fn [ctx]
            (cond-> ctx
              (:response ctx) (update-in [:response :headers] merge
                                         {"X-Content-Type-Options" "nosniff"
                                          "X-Frame-Options"        "DENY"
                                          "Cache-Control"          "no-store"})))})

(def globais
  "Interceptors OUTERMOST de TODA rota (prepend em http/servico): cabecalhos (leave por ultimo, cobre ate erros)
  + erro (envolve toda a cadeia). W3: rota nova herda isto automaticamente — nao depende de lembrar por rota."
  [cabecalhos-seguranca erro])
