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
            [oplenario.admin-sistema.autenticacao :as auten-operador]
            [oplenario.admin-sistema.components.idp-admin :as idp-operacao]
            [oplenario.admin-sistema.components.repositorio :as repo-operacao]
            [oplenario.http :as http]
            [oplenario.identidade.autenticacao :as auten]
            [oplenario.identidade.components.repositorio :as repo]
            [oplenario.kernel.arquivo :as arquivo]
            [oplenario.kernel.autorizacao :as authz]
            [oplenario.kernel.components.idp :as idp])
  (:import (java.io FilterInputStream InputStream)
           (org.apache.commons.fileupload2.core AbstractFileUpload FileItemInput RequestContext)))

(set! *warn-on-reflection* true)

(defn- bearer [req]
  (let [h (get-in req [:headers "authorization"])]
    (when (and h (str/starts-with? h "Bearer ")) (subs h 7))))

(defn cookie
  "Valor do cookie `nome` no header CRU `Cookie` (vazio = ausente). PURA."
  [req nome]
  (let [prefixo (str nome "=")]
    (when-let [h (get-in req [:headers "cookie"])]
      (some (fn [par]
              (when (str/starts-with? par prefixo)
                (let [v (subs par (count prefixo))]
                  (when-not (str/blank? v) v))))
            (map str/trim (str/split h #";"))))))

(def nome-cookie-operacao
  "O cookie do console do operador (ADR-0016). Outro nome, outra tabela: a sessao de uma Casa nunca abre o console
  e a do console nunca abre uma Casa."
  "sessao_operacao")

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

(defn- com-restricao
  "Aplica a `restricao` da Casa (ADR-0018) depois que a autenticacao resolveu o ator: (fn [ctx] -> ctx), que pode
  terminar a cadeia (423). So' roda com o ator resolvido e a cadeia viva — 401 continua 401."
  [restricao ctx]
  (if (and restricao (nil? (:response ctx)) (get-in ctx [:request :ator]))
    (restricao ctx)
    ctx))

(defn autenticacao
  "Interceptor de AUTENTICACAO (§22.5 eixo D). PRECEDENCIA: sessao de COOKIE primeiro (login real, Onda D
  Slice 2); senao BEARER (dev-token/servico — mantido vivo). Sem credencial / invalido / sem vinculo -> 401
  (fail-closed). Sucesso -> `ator` em (:request :ator). A sessao de cookie roda `resolver-sessao` A CADA
  request (authz viva: vinculo revogado derruba a sessao na hora, nao espera a expiracao do cookie).

  `restricao` (opcional, ADR-0018 Eixo 3): o que o host aplica com o ator ja' resolvido — a Casa suspensa recusa a
  escrita fora da allowlist com 423 (`oplenario.restricao-da-casa`). Mora AQUI, e nao entre os globais, porque e' o
  primeiro ponto da cadeia em que a rota (o router ja' rodou) e a Casa (o ator) sao conhecidas; os globais rodam antes
  do router. A trilha de auditoria (global, :leave) ve o 423 com o ator, como ve um 403."
  ([idp repo-identidade] (autenticacao idp repo-identidade nil))
  ([idp repo-identidade restricao]
   {:name  ::autenticacao
    :enter (fn [ctx]
             (com-restricao
              restricao
              (if-let [seg (cookie-sessao (:request ctx))]
                (if-let [claims (repo/resolver-sessao-por-segredo repo-identidade seg)]  ; {:identidade-id :ente-id [:vinculo-tipo]} ou nil
                  (if-let [ator (auten/resolver-sessao repo-identidade claims)]
                    (assoc-in ctx [:request :ator] ator)
                    (nega! ctx 401 "sem vinculo ativo"))
                  (nega! ctx 401 "sessao invalida"))
                (if-let [tok (bearer (:request ctx))]
                  (if-let [claims (idp/verificar-token idp tok)]
                    ;; resolver-claims: login pelo gov.br resolve pelo CPF e so' como cidadao (ADR-0015)
                    (if-let [ator (auten/resolver-claims repo-identidade claims)]
                      (assoc-in ctx [:request :ator] ator)
                      (nega! ctx 401 "sem vinculo ativo"))
                    (nega! ctx 401 "token invalido"))
                  (nega! ctx 401 "sem credencial")))))}))

(defn autenticacao-operador
  "Interceptor do CONSOLE DO OPERADOR (ADR-0016, §22.5 eixo E). Cookie `sessao_operacao` primeiro; senao Bearer do
  realm do operador. O ator resolvido nao tem Casa e passou pela checagem de esfera `:supratenant`. Qualquer outra
  credencial — a sessao de uma Casa, o token de um realm de Casa, a credencial do agente — nao abre estas rotas:
  401 (fail-closed). Operador desligado cai na hora, mesmo com sessao no prazo."
  [idp-op repo-op]
  {:name  ::autenticacao-operador
   :enter (fn [ctx]
            (if-let [seg (cookie (:request ctx) nome-cookie-operacao)]
              (if-let [{:keys [operador-id]} (repo-operacao/resolver-sessao-operador repo-op seg)]
                (if-let [ator (auten-operador/ator-do-operador repo-op operador-id)]
                  (assoc-in ctx [:request :ator] ator)
                  (nega! ctx 401 "operador inativo"))
                (nega! ctx 401 "sessao invalida"))
              (if-let [tok (bearer (:request ctx))]
                (if-let [claims (idp-operacao/verificar-token-operador idp-op tok)]
                  (if-let [ator (auten-operador/ator-do-operador repo-op (:operador-id claims))]
                    (assoc-in ctx [:request :ator] ator)
                    (nega! ctx 401 "operador inativo"))
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

(defn corpo-json-ate
  "O `corpo-json` com outro teto (bytes), para a rara rota que recebe um documento inteiro (o texto de uma norma, B.4).
  O resto da borda segue com o teto padrao."
  [teto]
  {:name  (keyword "oplenario.interceptors" (str "corpo-json-ate-" teto))
   :enter (fn [ctx]
            (let [req (:request ctx)
                  ct  (get-in req [:headers "content-type"])]
              (if (and ct (str/starts-with? ct "application/json") (:body req))
                (try
                  (assoc-in ctx [:request :json-params]
                            (json/read-value (ler-limitado (:body req) teto)))
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

(def corpo-json
  "Interceptor de NEGOCIACAO DE CONTEUDO de entrada (a borda anunciada em W1): parseia o corpo JSON em
  (:request :json-params) com chaves STRING. So age em content-type application/json com corpo presente
  (GET/sem-corpo passam direto). Decisoes de seguranca (review W3): (1) corpo limitado a max-corpo-bytes ->
  413 (anti-DoS de heap); (2) chaves STRING, NUNCA keyword — keyword JSON interna no metaspace e nao e' GC'd,
  entao chaves arbitrarias do cliente seriam um vazamento permanente (DoS). Cada adapters/in coage so as
  chaves esperadas p/ keyword. JSON malformado -> 400 fail-closed (nunca 500). Reusavel por toda rota de escrita
  no fan-out W3+; o Pedestal 0.7 default-interceptors NAO parseia corpo."
  (assoc (corpo-json-ate max-corpo-bytes) :name ::corpo-json))

;; ---------- upload de UM arquivo (multipart) — compartilhado por todo modulo que recebe anexo ----------

(def ^:private folga-do-envelope
  "O multipart carrega cabecalhos de parte e fronteiras alem do arquivo: o teto do CORPO e' o do arquivo + isto."
  (* 64 1024))

(def max-envios-simultaneos
  "Quantos uploads leem corpo AO MESMO TEMPO, no processo todo (anexos de comunicado e de atendimento). Cada um segura ate'
  10 MB em memoria enquanto o pedido roda; sem teto, N conexoes lentas esgotam a heap. Saturado: 503 com Retry-After, sem
  ler o corpo."
  4)

(def ^:private ^java.util.concurrent.Semaphore vagas-de-envio (java.util.concurrent.Semaphore. (int max-envios-simultaneos)))

(def ^:private ^java.util.Set envios-em-voo
  "Quem esta' com um envio em andamento (identidade-id). UM envio por pessoa por vez (a tela ja' envia um a um): sem isto,
  uma pessoa so' abre `max-envios-simultaneos` conexoes lentas e segura todas as vagas do processo — ninguem mais anexa,
  em Casa nenhuma. O segundo envio da mesma pessoa -> 429, sem ler o corpo."
  (java.util.concurrent.ConcurrentHashMap/newKeySet))

(def max-partes-do-envio
  "Quantas partes (campos + arquivos) um multipart de anexo pode ter. A tela manda UMA; mais que isto e' lixo."
  8)

(def ^:private teto-do-cabecalho-da-parte
  "O cabecalho Content-Disposition de uma parte, em caracteres. Acima disto o envio e' malformado (400): nenhum nome de
  arquivo de verdade chega perto, e o limite poupa o scanner de uma linha de megabytes."
  8192)

(defn- separar-parametros
  "Parte o cabecalho em parametros no `;` que esta FORA de aspas (`\\\"` e `\\\\` escapam dentro das aspas). Laco linear: sem
  regex recursiva, sem recursao de pilha. Os escapes ficam no texto (quem desembrulha os resolve)."
  [^String cd]
  (let [n (.length cd)]
    (loop [i 0 aspas? false escapado? false ^StringBuilder atual (StringBuilder.) acc []]
      (if (>= i n)
        (conj acc (str atual))
        (let [ch (.charAt cd i)]
          (cond
            escapado?                      (recur (inc i) aspas? false (.append atual ch) acc)
            (and aspas? (= ch \\))         (recur (inc i) aspas? true (.append atual ch) acc)
            (= ch \")                      (recur (inc i) (not aspas?) false (.append atual ch) acc)
            (and (= ch \;) (not aspas?))   (recur (inc i) aspas? false (StringBuilder.) (conj acc (str atual)))
            :else                          (recur (inc i) aspas? false (.append atual ch) acc)))))))

(defn- desembrulhar
  "O valor de um parametro: entre aspas (resolve `\\x` -> x e para na aspa que fecha) ou como esta."
  [^String v]
  (if (str/starts-with? v "\"")
    (let [sb (StringBuilder.) n (.length v)]
      (loop [i 1]
        (when (< i n)
          (let [ch (.charAt v i)]
            (cond (= ch \\) (when (< (inc i) n) (.append sb (.charAt v (inc i))) (recur (+ i 2)))
                  (= ch \")  nil
                  :else      (do (.append sb ch) (recur (inc i)))))))
      (str sb))
    v))

(defn valor-do-filename
  "O `filename` do cabecalho Content-Disposition `cd` (texto), ou nil se a parte nao tem. Aceita o valor entre aspas (com `\\\"`
  e `\\\\` escapados) ou sem aspas, em qualquer caixa e com espacos em volta do `=`; `filename*=` (RFC 5987) nao e'
  `filename`. LINEAR: um nome de 10 mil caracteres nao estoura a pilha (a regex anterior, `(?:[^\"\\\\]|\\\\.)*`, recursava por
  caractere). Cabecalho acima de `teto-do-cabecalho-da-parte` -> ex-info `:cabecalho/grande` (a borda responde 400)."
  [^String cd]
  (when (> (.length cd) (long teto-do-cabecalho-da-parte))
    (throw (ex-info "cabecalho da parte grande demais" {:tipo :cabecalho/grande})))
  (some (fn [^String seg]
          (when-let [i (str/index-of seg "=")]
            (when (= "filename" (str/lower-case (str/trim (subs seg 0 i))))
              (desembrulhar (str/trim (subs seg (inc i)))))))
        (rest (separar-parametros cd))))

(defn- nome-do-arquivo-da-parte
  "O `filename` da parte, lido do TEXTO do cabecalho Content-Disposition (decodificado em UTF-8: a borda fixa o encoding, nao
  depende de `LANG`). nil se a parte nao tem `filename`.

  POR QUE NAO `FileItemInput/getName` (o que o Ring faz): em commons-fileupload2 ele passa o nome por `Paths.get` para
  validar, e numa JVM sem locale UTF-8 (`sun.jnu.encoding` ASCII — o container `clojure:*` de teste, um deploy com
  `LANG=C`) um nome acentuado vira `InvalidPathException` = 400. O nome aqui e' so' texto de exibicao; o caminho nunca
  toca o disco (o arquivo vai ao object storage numa chave nossa)."
  [^FileItemInput item]
  (when-let [cd (some-> (.getHeaders item) (.getHeader "Content-Disposition"))]
    (valor-do-filename cd)))

(defn- fluxo-limitado
  "O corpo como fluxo que CONTA o que passa por ele e estoura `:corpo/grande` quando o total passa de `limite`. O teto do
  corpo INTEIRO (arquivo + campos + cabecalhos de parte) vale pelo que e' LIDO, nao pelo `Content-Length` que o cliente
  declara: um corpo chunked, sem tamanho declarado, tambem para no teto."
  ^InputStream [^InputStream in limite]
  (let [lidos (long-array 1)
        conta! (fn [n]
                 (when (pos? (long n))
                   (let [total (+ (aget lidos 0) (long n))]
                     (aset lidos 0 total)
                     (when (> total (long limite)) (throw (ex-info "corpo grande demais" {:tipo :corpo/grande})))))
                 n)]
    (proxy [FilterInputStream] [in]
      (read
        ([] (let [b (.read in)] (when (>= b 0) (conta! 1)) b))
        ;; o proxy despacha TODA aridade de `read` para esta fn: sem a de 1 argumento, `read(byte[])` vira ArityException
        ([^bytes b] (conta! (.read in b 0 (alength b))))
        ([^bytes b off len] (conta! (.read in b (int off) (int len))))))))

(defn- ler-arquivos-do-multipart
  "As partes de ARQUIVO do corpo `multipart/form-data`, ate' 2 (so' para saber se passou de UM; os campos de formulario sao
  ignorados mas CONTADOS: mais de `max-partes-do-envio` partes e' `:partes/demais`). O 1o arquivo e' lido para memoria com
  teto de `max-bytes` (`:corpo/grande` se passar) e vem como {:nome :tipo-midia :conteudo}; ao ver o cabecalho do 2o arquivo a
  leitura PARA (o corpo dele nao e' lido). O corpo inteiro e' limitado em `limite-do-corpo` pelo que e' lido. O encoding do
  cabecalho da parte e' UTF-8, explicito."
  [req max-bytes limite-do-corpo]
  (let [corpo (fluxo-limitado (:body req) limite-do-corpo)
        contexto (reify RequestContext
                   (getContentType [_] (get-in req [:headers "content-type"]))
                   (getContentLength [_] (or (some-> (get-in req [:headers "content-length"]) parse-long) -1))
                   (getCharacterEncoding [_] "UTF-8")
                   (getInputStream [_] corpo)
                   (isMultipartRelated [_] false))   ; exigido pela API a partir da 2.0.0-M2 (form-data, nao multipart/related)
        it (.getItemIterator ^AbstractFileUpload (proxy [AbstractFileUpload] []) contexto)]
    (loop [arquivos [] partes 0]
      (if (.hasNext it)
        (let [^FileItemInput item (.next it)
              partes (inc partes)]
          (when (> partes (long max-partes-do-envio))
            (throw (ex-info "partes demais no envio" {:tipo :partes/demais})))
          (cond
            (.isFormField item) (recur arquivos partes)
            (>= (count arquivos) 1) (conj arquivos nil)   ; o 2o arquivo: nao precisa ler nada
            :else (recur (conj arquivos {:nome (arquivo/nome-de-arquivo (nome-do-arquivo-da-parte item))
                                         :tipo-midia (arquivo/tipo-de-midia (.getContentType item))
                                         :conteudo (ler-limitado (.getInputStream item) max-bytes)})
                         partes)))
        arquivos))))

(defn- liberar-vaga!
  "Devolve a vaga de envio do `ctx` UMA vez (o flag impede devolver em dobro: a recusa dentro do :enter e o :leave), e tira
  a pessoa de `envios-em-voo`."
  [ctx]
  (when-let [^java.util.concurrent.atomic.AtomicBoolean flag (::vaga ctx)]
    (when (.compareAndSet flag true false)
      (.release vagas-de-envio)
      (when-let [quem (::quem ctx)] (.remove envios-em-voo quem))))
  ctx)

(defn- corpo-grande?
  "A excecao, ou alguma causa dela, e' o estouro do teto do corpo?"
  [e]
  (boolean (some #(= :corpo/grande (:tipo (ex-data %))) (take-while some? (iterate ex-cause e)))))

(defn anexo-multipart
  "Fabrica o interceptor do upload de anexo: `multipart/form-data` com UM arquivo (o campo pode ter qualquer nome; a
  tela usa `arquivo`). O arquivo e' lido para memoria com teto de `max-bytes` (o object storage recebe os bytes e o
  sha256 sai deles); corpo acima do teto -> 413, sem arquivo/mais de um/partes demais/malformado/vazio -> 400. Resultado em
  `(:request :anexo)` {:nome :tipo-midia :conteudo}. So' a PARTE GENERICA mora aqui: quais tipos o modulo aceita, quantos
  e quando e' regra do `logic` de cada um (comunicacao, participacao). Nasceu em `comunicacao/diplomat/http/in` e foi
  movida sem mudar o comportamento (`participacao` nao pode importar `comunicacao`, §22.10).

  ANTES de ler o corpo (nada disto toca o stream): `Content-Length` declarado acima do teto -> 413; e o TETO GLOBAL de envios
  simultaneos (`max-envios-simultaneos`, semaforo do processo) — saturado -> 503 com `Retry-After`. A vaga fica com o envio
  ate' o fim da cadeia (o conteudo segue em memoria enquanto o handler roda) e volta no :leave/:error. DEPOIS: o corpo
  inteiro e' limitado pelo que e' LIDO (chunked tambem), e a leitura para no 1o arquivo.

  Quem precisa saber se o pedido cabe ANTES de aceitar corpo (alvo existe, e' do dono, janela, limite) poe um interceptor
  proprio antes deste: o do modulo, que conhece a regra.

  O NOME do arquivo e' lido dos cabecalhos da parte em UTF-8 explicito (`nome-do-arquivo-da-parte`): um nome acentuado
  passa em qualquer locale da JVM."
  [{:keys [max-bytes]}]
  (let [mb (quot (long max-bytes) (* 1024 1024))
        grande (str "O anexo passa de " mb " MB.")
        limite-do-corpo (+ (long max-bytes) folga-do-envelope)
        malformado "Envie um arquivo por vez (campo arquivo)."]
    {:name ::anexo-multipart
     :enter (fn [ctx]
              (let [req (:request ctx)
                    tamanho (some-> (get-in req [:headers "content-length"]) parse-long)
                    recusa (fn [ctx status msg] (liberar-vaga! ctx) (chain/terminate (assoc ctx :response (http/json-resposta status {:erro msg}))))
                    multipart? (some-> (get-in req [:headers "content-type"]) str/lower-case (str/starts-with? "multipart/form-data"))
                    quem (get-in req [:ator :identidade-id])
                    ocupado (fn [ctx status msg]
                              (chain/terminate (assoc ctx :response (assoc-in (http/json-resposta status {:erro msg})
                                                                              [:headers "Retry-After"] "5"))))]
                (cond
                  (and tamanho (> (long tamanho) limite-do-corpo)) (recusa ctx 413 grande)
                  (not multipart?) (recusa ctx 400 malformado)
                  (and quem (not (.add envios-em-voo quem)))
                  (ocupado ctx 429 "Você já tem um envio de arquivo em andamento. Espere ele terminar.")
                  (not (.tryAcquire vagas-de-envio))
                  (do (when quem (.remove envios-em-voo quem))
                      (ocupado ctx 503 "Há muitos envios de arquivo ao mesmo tempo. Tente de novo em alguns segundos."))
                  :else
                  (let [ctx (assoc ctx ::vaga (java.util.concurrent.atomic.AtomicBoolean. true) ::quem quem)]
                    (try
                      (let [arquivos (ler-arquivos-do-multipart req max-bytes limite-do-corpo)]
                        (cond
                          (not= 1 (count arquivos)) (recusa ctx 400 malformado)
                          (nil? (first arquivos)) (recusa ctx 400 malformado)
                          (zero? (alength ^bytes (:conteudo (first arquivos)))) (recusa ctx 400 "O arquivo está vazio.")
                          :else (assoc-in ctx [:request :anexo] (first arquivos))))
                      (catch Exception e
                        (cond
                          (corpo-grande? e) (recusa ctx 413 grande)
                          (= :partes/demais (:tipo (ex-data e))) (recusa ctx 400 malformado)
                          (= :cabecalho/grande (:tipo (ex-data e))) (recusa ctx 400 "O envio do arquivo veio malformado.")
                          (instance? clojure.lang.ExceptionInfo e) (recusa ctx 400 malformado)
                          :else (recusa ctx 400 "O envio do arquivo veio malformado.")))
                      ;; um Error (OutOfMemoryError ao ler 10 MB, LinkageError do parser) nao e' Exception. O :error da cadeia
                      ;; recebe o ctx de ANTES deste :enter, sem `::vaga`: sem devolver aqui, a vaga vazava, e depois de
                      ;; `max-envios-simultaneos` vazamentos todo upload respondia 503 ate' reiniciar.
                      (catch Throwable t (liberar-vaga! ctx) (throw t)))))))
     :leave liberar-vaga!
     :error (fn [ctx ex] (liberar-vaga! ctx) (assoc ctx ::chain/error ex))}))

(defn exige-papel
  "Interceptor de AUTORIZACAO GROSSA: exige o `papel` estatico (STRING — os papeis do snapshot sao strings) no
  ator. Falta -> authz lanca negado? -> o interceptor `erro` mapeia p/ 403. Pressupoe `autenticacao` antes."
  [papel]
  {:name  (keyword "oplenario.interceptors" (str "exige-papel--" papel))
   :enter (fn [ctx]
            (authz/exige-papel! (get-in ctx [:request :ator]) papel)
            ctx)})

(defn exige-algum-papel
  "Interceptor de AUTORIZACAO GROSSA (variante OU): exige QUALQUER um dos `papeis` (STRINGS) no ator.
  Para categorias de LEITURA abertas a mais de um papel (ex.: proposicoes/tramitacao/paineis por
  'secretario' OU 'vereador'). Falta de todos -> negado? -> 403. Pressupoe `autenticacao` antes."
  [papeis]
  (let [conj-papeis (set papeis)]
    {:name  (keyword "oplenario.interceptors" (str "exige-algum-papel--" (str/join "+" (sort conj-papeis))))
     :enter (fn [ctx]
              (authz/exige-algum-papel! (get-in ctx [:request :ator]) conj-papeis)
              ctx)}))

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

(defn globais-com
  "Os `globais` com `extras` ENTRE os cabecalhos e o de erro: o extra ve a resposta FINAL (o erro ja' virou
  403/400/500) e o ator que a autenticacao resolveu, e os cabecalhos de seguranca continuam por fora de tudo. E' onde
  o host poe a trilha de auditoria (ADR-0017)."
  [extras]
  (vec (concat [cabecalhos-seguranca] extras [erro])))

(defn autenticacao-agente
  "Interceptor de AUTENTICACAO de AGENTE (ADR-0010): so' a credencial delegada (bearer opaco emitido pelo core a
  cada execucao) e' aceita — nem cookie de sessao, nem token do IdP. O ator e' resolvido A CADA chamada
  (`identidade/resolver-agente`: a pessoa como esta' agora + `:via`). Usado SO' nas rotas do catalogo (o adaptador
  MCP, B.3): a credencial de agente nao abre nenhuma outra rota, porque o interceptor `autenticacao` das telas nao a
  reconhece. `restricao`: a mesma da Casa (ADR-0018) — a Casa suspensa nao tem agente (a IA dela esta' pausada)."
  ([repo-identidade] (autenticacao-agente repo-identidade nil))
  ([repo-identidade restricao]
   {:name  ::autenticacao-agente
    :enter (fn [ctx]
             (com-restricao
              restricao
              (if-let [seg (bearer (:request ctx))]
                (if-let [ator (auten/resolver-agente repo-identidade seg)]
                  (assoc-in ctx [:request :ator] ator)
                  (nega! ctx 401 "credencial de agente invalida"))
                (nega! ctx 401 "sem credencial"))))}))
