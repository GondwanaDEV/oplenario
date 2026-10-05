(ns oplenario.agente
  "Host (§22.10): a TELA conversa com o agente pelo core (docs/25 Eixo 5.2 — navegador -> core -> satelite; o satelite
  nunca e' exposto). A pessoa pergunta numa tela; o core emite a credencial delegada DAQUELA execucao (ADR-0010:
  leitura, e ato — que por agente so' vira PROPOSTA, ADR-0012), chama o satelite, devolve a conversa em SSE e revoga a
  credencial ao fim — com sucesso ou nao.

  O publico do agente sai do papel da pessoa: 'secretario' -> conjunto da secretaria, 'vereador' -> do vereador,
  'juridico', 'auditor' e 'admin_ente' -> o de consulta (fatia 4 da Clara). Quem tem mais de um escolhe pelo campo
  `publico`; sem escolha, secretaria > vereador > consulta; nunca um publico cujo papel nao tem (403). A credencial de
  consulta leva so' `leitura`: esses papeis nao propoem ato pela Clara.

  SSE (§22.3.2): `passo` (cada ferramenta chamada, com o desfecho), `proposta` (cada proposta de ato criada na execucao,
  para a tela levar a pessoa a confirmar), depois `resposta` (texto, citacoes conferidas,
  incerteza) ou `indisponivel` (R-IA-1: 'siga pela tela'), e `fim`. Hoje o satelite responde de uma vez e o core
  emite os eventos em sequencia; quando o fornecedor real transmitir aos poucos, a mesma forma carrega o fluxo.

  Feature 8.4 — 'reportar erro': a `resposta` leva `execucao-ia` (o id da execucao NO satelite, que o registro da
  Camada de Confianca conhece; o `execucao-id` do `fim` e' o da credencial delegada, outra coisa). A tela devolve esse
  id em POST /ia/execucoes/:execucao-id/reportes com uma categoria do vocabulario fixo; o core repassa ao satelite com
  a Casa e a pessoa da sessao. A rota e' generica (qualquer resposta de IA cujo id chegue a tela), so' de tela."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [jsonista.core :as json]
            [oplenario.http :as http]
            [oplenario.identidade.autenticacao :as auten]
            [oplenario.identidade.components.repositorio :as repo-id]
            [oplenario.integracao-ia.components.repositorio :as repo-ia]
            [oplenario.integracao-ia.diplomat.http.out :as plataforma-ia]
            [oplenario.integracao-ia.logic :as logic-ia]
            [oplenario.interceptors :as it]
            [oplenario.kernel.autorizacao :as authz]))

(set! *warn-on-reflection* true)

(def agente-da-casa "assistente-da-casa")

(def ^:private publico-por-papel
  {"secretario" :secretaria "vereador" :vereador "juridico" :consulta "auditor" :consulta "admin_ente" :consulta})

(def ^:private precedencia
  "Sem `publico` no corpo, o primeiro desta ordem que um papel da pessoa alcanca."
  [:secretaria :vereador :consulta])

(def papeis-da-clara
  "Os papeis que perguntam a' Clara e leem o proprio historico."
  (vec (sort (keys publico-por-papel))))

(defn classes-do-publico
  "As classes que a credencial da execucao recebe (ADR-0010): `ato` (que por agente so' vira PROPOSTA, ADR-0012) so'
  para a secretaria e o vereador; o publico de consulta so' le."
  [publico]
  (if (= :consulta publico) #{:leitura} #{:leitura :ato}))

(def mensagem-indisponivel "A Clara está indisponível agora. Siga pela tela — nada do seu trabalho depende dela.")

(defn- invalido! [msg] (throw (ex-info msg {:tipo :validacao/invalido})))

(defn pedido
  "O corpo JSON (chaves string) -> {:pergunta :publico :conversa-id}, ou `:validacao/invalido`. O publico e' conferido
  contra os papeis do ator (`:autorizacao/negado`). `conversa` (opcional, uuid) continua uma conversa do historico
  (ADR-0024); sem ela, a pergunta abre uma conversa nova."
  [ator corpo]
  (let [pergunta (some-> (get corpo "pergunta") str str/trim)
        conversa (get corpo "conversa")
        conversa-id (when (some? conversa)
                      (or (and (string? conversa) (parse-uuid conversa)) (invalido! "conversa deve ser um uuid")))
        pedido-publico (get corpo "publico")
        possiveis (into #{} (keep publico-por-papel) (:papeis ator))
        publico (if pedido-publico
                  (or (some #(when (= pedido-publico (name %)) %) possiveis)
                      (authz/negar! :publico-sem-papel {:publico pedido-publico}))
                  (some possiveis precedencia))]
    (when-not (and pergunta (<= 2 (count pergunta) 1000)) (invalido! "pergunta de 2 a 1000 caracteres"))
    (when-not publico (authz/negar! :sem-publico {}))
    {:pergunta pergunta :publico publico :conversa-id conversa-id}))

(defn- evento [nome dado] (str "event: " nome "\ndata: " (json/write-value-as-string dado) "\n\n"))

(def mensagem-sem-registro
  "A resposta que nao pode ser guardada nao sai (ADR-0024, falha fechada): para a pessoa, e' a IA indisponivel."
  mensagem-indisponivel)

(defn- executar-na-ia
  "Chama o satelite com a credencial da execucao. IA fora -> {:indisponivel ...} (R-IA-1), nunca excecao."
  [ia ente-id pergunta credencial execucao-id]
  (try
    (plataforma-ia/executar-agente ia ente-id {:pergunta pergunta :credencial credencial
                                               :correlation_id (str execucao-id)})
    (catch clojure.lang.ExceptionInfo e
      (if (= :ia/indisponivel (:tipo (ex-data e)))
        (do (log/warn "agente indisponivel" (:motivo (ex-data e)))
            {:passos [] :resposta nil :indisponivel {:mensagem mensagem-indisponivel}})
        (throw e)))))

(defn- registrar!
  "Grava a interacao no historico. true se gravou; sem repositorio ou com erro de banco, false (e o erro vai ao log)."
  [repo-integracao-ia i]
  (if-not repo-integracao-ia
    (do (log/error "historico da Clara sem repositorio: a resposta nao sai") false)
    (try (repo-ia/registrar-interacao-assistente! repo-integracao-ia i) true
         (catch Exception e
           (log/error e "historico da Clara: a interacao nao gravou, a resposta nao sai" {:interacao (:id i)})
           false))))

(defn conversa
  "Executa uma pergunta e devolve {:corpo <eventos SSE> :interacao <a linha gravada, ou nil>}. A credencial vive so'
  durante esta chamada. ADR-0024: a pergunta e o que voltou vao ao historico ANTES de a resposta sair; se o historico
  nao grava, sai `indisponivel` (falha fechada) e nada da resposta chega a tela."
  [{:keys [repo-identidade ia repo-integracao-ia]} ator {:keys [pergunta publico conversa-id]}]
  (when (and conversa-id repo-integracao-ia
             (not (repo-ia/conversa-da-pessoa? repo-integracao-ia (:ente-id ator) (:identidade-id ator) conversa-id)))
    (invalido! "conversa desconhecida"))
  (let [{:keys [execucao-id credencial]}
        (auten/emitir-credencial-agente! repo-identidade ator {:agente agente-da-casa :publico publico
                                                               :classes (classes-do-publico publico)})]
    (try
      (let [r (executar-na-ia ia (:ente-id ator) pergunta credencial execucao-id)
            propostas (when repo-integracao-ia
                        (repo-ia/propostas-da-execucao repo-integracao-ia (:ente-id ator) execucao-id))
            i (logic-ia/interacao {:ente-id (:ente-id ator) :id (random-uuid) :conversa-id (or conversa-id (random-uuid))
                                   :execucao-id execucao-id :identidade-id (:identidade-id ator)
                                   :agente agente-da-casa :publico publico :pergunta pergunta
                                   :resposta (:resposta r) :passos (:passos r) :propostas propostas
                                   :ocorrido-em (java.time.Instant/now)})
            fim (evento "fim" {:execucao-id (str execucao-id)
                               :interacao-id (str (:id i)) :conversa-id (str (:conversa-id i))})]
        (if-not (registrar! repo-integracao-ia i)
          {:corpo (str (evento "indisponivel" {:mensagem mensagem-sem-registro})
                       (evento "fim" {:execucao-id (str execucao-id)}))
           :interacao nil}
          {:corpo (str (apply str (for [p (:passos r)]
                                    (evento "passo" {:ferramenta (:ferramenta p) :argumentos (:argumentos p) :ok (:ok p)})))
                       (apply str (for [p propostas]
                                    (evento "proposta" {:id (str (:id p)) :titulo (:titulo p) :ritual (:ritual p)})))
                       (if-let [resp (:resposta r)]
                         (evento "resposta" (cond-> (select-keys resp [:texto :citacoes :paragrafos-sem-fonte :incerteza
                                                                       :modelo :contaminado])
                                              (:execucao-id resp) (assoc :execucao-ia (str (:execucao-id resp)))))
                         (evento "indisponivel" {:mensagem (or (get-in r [:indisponivel :mensagem]) mensagem-indisponivel)}))
                       fim)
           :interacao i}))
      (finally
        (repo-id/revogar-credencial-agente! repo-identidade execucao-id)))))

(defn- perguntar-handler [deps]
  (fn [req]
    (let [ator (:ator req)
          p (pedido ator (or (:json-params req) {}))
          {:keys [corpo interacao]} (conversa deps ator p)]
      (cond-> {:status 200
               :headers {"Content-Type" "text/event-stream; charset=utf-8" "Cache-Control" "no-store"}
               :body corpo}
        ;; ADR-0024 item 4: a trilha (sem conteudo) aponta a interacao e ancora o hash dela
        interacao (assoc :auditoria {:recurso-tipo "interacao_assistente" :recurso-id (str (:id interacao))
                                     :conteudo-sha256 (:conteudo-sha256 interacao)})))))

;; ---------- feature 8.4: reportar erro da IA ----------

(def categorias-reporte
  "O vocabulario do registro da Camada de Confianca (`CategoriaReporte` no satelite). Sem texto livre: o registro e'
  SEM conteudo (B4)."
  #{"fato_errado" "citacao_errada" "omissao" "linguagem" "outro"})

(def mensagem-reporte-indisponivel "Não foi possível registrar o erro agora. Tente de novo em instantes.")

(defn pedido-reporte
  "Corpo {categoria} (chave string, allowlist estrita) -> a categoria, ou `:validacao/invalido`."
  [corpo]
  (let [categoria (get corpo "categoria")]
    (when-not (and (map? corpo) (= #{"categoria"} (set (keys corpo))) (contains? categorias-reporte categoria))
      (invalido! (str "categoria deve ser uma de " (str/join ", " (sort categorias-reporte)))))
    categoria))

(defn- reportar-handler [{:keys [ia]}]
  (fn [req]
    (let [ator (:ator req)
          eid (or (parse-uuid (str (get-in req [:path-params :execucao-id])))
                  (invalido! "execucao-id invalido"))
          categoria (pedido-reporte (or (:json-params req) {}))]
      (try
        (if (plataforma-ia/reportar-erro ia (:ente-id ator) (str eid) {:quem (str (:identidade-id ator))
                                                                       :categoria categoria})
          (http/json-resposta 200 {:reportado true})
          (http/json-resposta 404 {:erro "execucao nao encontrada"}))
        (catch clojure.lang.ExceptionInfo e
          (if (= :ia/indisponivel (:tipo (ex-data e)))
            (do (log/warn "reportar erro: IA indisponivel" (:motivo (ex-data e)))
                (http/json-resposta 503 {:erro mensagem-reporte-indisponivel}))
            (throw e)))))))

;; ---------- ADR-0024 (fatia 2): ler o historico ----------

(def ^:private limite-padrao 30)
(def ^:private limite-maximo 50)

(defn- auditor? [ator] (contains? (set (:papeis ator)) "auditor"))

(defn- texto-instante [m k] (cond-> m (get m k) (update k str)))

(def ^:private busca-minima 2)
(def ^:private busca-maxima 100)

(defn pedido-historico
  "Query (chaves keyword) -> {:pessoa :escopo-casa? :antes :limite :busca}, ou `:validacao/invalido`. Quem nao e'
  auditor so' le o proprio historico: pedir o de outra pessoa, ou o da Casa, e' `:autorizacao/negado`. `q` (opcional)
  procura na pergunta e no texto da resposta: aparado, de 2 a 100 caracteres; em branco = sem busca."
  [ator q]
  (let [busca (some-> (:q q) (#(if (string? %) % (invalido! "q deve ser um texto so'"))) str/trim not-empty
                      (#(if (<= busca-minima (.codePointCount ^String % 0 (count %)) busca-maxima)
                          %
                          (invalido! (str "q de " busca-minima " a " busca-maxima " caracteres")))))
        pessoa (some-> (:pessoa q) str (#(or (parse-uuid %) (invalido! "pessoa deve ser um uuid"))))
        casa? (= "casa" (:escopo q))
        antes (some-> (:antes q) str (#(try (java.time.Instant/parse %)
                                            (catch java.time.format.DateTimeParseException _
                                              (invalido! "antes deve ser um instante ISO-8601")))))
        limite (if-let [l (:limite q)]
                 (let [n (parse-long (str l))]
                   (when-not (and n (<= 1 n limite-maximo)) (invalido! (str "limite de 1 a " limite-maximo)))
                   n)
                 limite-padrao)]
    (when (and (or casa? (and pessoa (not= pessoa (:identidade-id ator)))) (not (auditor? ator)))
      (authz/negar! :historico-de-outra-pessoa {}))
    {:pessoa (if casa? nil (or pessoa (:identidade-id ator))) :escopo-casa? casa? :antes antes :limite limite
     :busca busca}))

(defn- nomes [repo-identidade ids]
  (into {} (for [i (distinct ids) :when i] [i (:nome (repo-id/nome-por-id repo-identidade i))])))

(defn- historico-handler [{:keys [repo-integracao-ia repo-identidade]}]
  (fn [req]
    (let [ator (:ator req)
          {:keys [pessoa escopo-casa? antes limite busca]} (pedido-historico ator (:query-params req))
          linhas (repo-ia/historico-assistente repo-integracao-ia (:ente-id ator)
                                               {:identidade-id pessoa :antes antes :busca busca} (inc limite))
          pagina (take limite linhas)
          de-outro? (or escopo-casa? (not= pessoa (:identidade-id ator)))
          nome-de (if de-outro? (nomes repo-identidade (map :identidade-id pagina)) {})]
      (cond-> (http/json-resposta 200 {:interacoes (mapv (fn [i] (cond-> (texto-instante i :ocorrido-em)
                                                                   de-outro? (assoc :nome (nome-de (:identidade-id i)))))
                                                         pagina)
                                       :mais (> (count linhas) limite)
                                       :antes (some-> (last pagina) :ocorrido-em str)})
        ;; o auditor lendo o historico de outra pessoa (ou da Casa) e' leitura sensivel: vai a' trilha
        de-outro? (assoc :auditoria {:classe "leitura_sensivel" :recurso-tipo "historico_assistente"
                                     :recurso-id (if escopo-casa? "casa" (str pessoa))})))))

(defn- conversa-handler [{:keys [repo-integracao-ia repo-identidade]}]
  (fn [req]
    (let [ator (:ator req)
          cid (or (parse-uuid (str (get-in req [:path-params :conversa-id]))) (invalido! "conversa-id invalido"))
          linhas (repo-ia/conversa-assistente repo-integracao-ia (:ente-id ator) cid)
          dona (:identidade-id (first linhas))
          de-outro? (not= dona (:identidade-id ator))]
      (if (or (empty? linhas) (and de-outro? (not (auditor? ator))))
        ;; a conversa de outra pessoa nao existe para quem nao e' auditor: 404, sem dizer que existe
        (http/json-resposta 404 {:erro "conversa nao encontrada"})
        (cond-> (http/json-resposta 200 {:conversa-id (str cid) :identidade-id (str dona)
                                         :nome (:nome (repo-id/nome-por-id repo-identidade dona))
                                         :interacoes (mapv (fn [i] (-> i
                                                                       (assoc :integra (logic-ia/conferir-interacao i))
                                                                       (texto-instante :ocorrido-em)
                                                                       (dissoc :ente-id)))
                                                           linhas)})
          de-outro? (assoc :auditoria {:classe "leitura_sensivel" :recurso-tipo "conversa_assistente"
                                       :recurso-id (str cid)}))))))

(defn rotas
  "POST /agente/perguntas — a secretaria, o vereador, o juridico, o auditor e o administrador da Casa perguntam a'
  Clara, a assistente da Casa (os tres ultimos so' para ler). POST /ia/execucoes/:execucao-id/reportes — quem recebeu
  uma resposta de IA diz que ela esta' errada (feature 8.4). GET /agente/historico e GET /agente/conversas/:conversa-id
  — o historico da Clara (ADR-0024): cada pessoa le o seu; o `auditor` le o da Casa, e essa leitura vai a' trilha (pela
  tela: o historico e a trilha nunca sao ferramenta da Clara)."
  [{:keys [auth] :as deps}]
  (let [papel (it/exige-algum-papel papeis-da-clara)]
    #{["/agente/perguntas" :post [auth papel it/corpo-json (perguntar-handler deps)]
       :route-name :agente/perguntar]
      ["/ia/execucoes/:execucao-id/reportes" :post [auth it/corpo-json (reportar-handler deps)]
       :route-name :agente/reportar-erro-ia]
      ["/agente/historico" :get [auth papel (historico-handler deps)]
       :route-name :agente/historico]
      ["/agente/conversas/:conversa-id" :get [auth papel (conversa-handler deps)]
       :route-name :agente/conversa]}))
