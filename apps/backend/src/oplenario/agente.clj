(ns oplenario.agente
  "Host (§22.10): a TELA conversa com o agente pelo core (docs/25 Eixo 5.2 — navegador -> core -> satelite; o satelite
  nunca e' exposto). A pessoa pergunta numa tela; o core emite a credencial delegada DAQUELA execucao (ADR-0010:
  leitura, e ato — que por agente so' vira PROPOSTA, ADR-0012), chama o satelite, devolve a conversa em SSE e revoga a
  credencial ao fim — com sucesso ou nao.

  O publico do agente sai do papel da pessoa: 'secretario' -> conjunto da secretaria, 'vereador' -> do vereador; quem
  tem os dois escolhe pelo campo `publico`, e nunca um publico cujo papel nao tem (403).

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
            [oplenario.interceptors :as it]
            [oplenario.kernel.autorizacao :as authz]))

(set! *warn-on-reflection* true)

(def agente-da-casa "assistente-da-casa")

(def ^:private publico-por-papel {"secretario" :secretaria "vereador" :vereador})

(def mensagem-indisponivel "O assistente está indisponível agora. Siga pela tela — nada do seu trabalho depende dele.")

(defn- invalido! [msg] (throw (ex-info msg {:tipo :validacao/invalido})))

(defn pedido
  "O corpo JSON (chaves string) -> {:pergunta :publico}, ou `:validacao/invalido`. O publico e' conferido contra os
  papeis do ator (`:autorizacao/negado`)."
  [ator corpo]
  (let [pergunta (some-> (get corpo "pergunta") str str/trim)
        pedido-publico (get corpo "publico")
        possiveis (keep publico-por-papel (sort (:papeis ator)))
        publico (if pedido-publico
                  (or (some #(when (= pedido-publico (name %)) %) possiveis)
                      (authz/negar! :publico-sem-papel {:publico pedido-publico}))
                  (first (sort-by #(if (= :secretaria %) 0 1) possiveis)))]
    (when-not (and pergunta (<= 2 (count pergunta) 1000)) (invalido! "pergunta de 2 a 1000 caracteres"))
    (when-not publico (authz/negar! :sem-publico {}))
    {:pergunta pergunta :publico publico}))

(defn- evento [nome dado] (str "event: " nome "\ndata: " (json/write-value-as-string dado) "\n\n"))

(defn conversa
  "Executa uma pergunta e devolve os eventos SSE (string). A credencial vive so' durante esta chamada."
  [{:keys [repo-identidade ia repo-integracao-ia]} ator {:keys [pergunta publico]}]
  (let [{:keys [execucao-id credencial]}
        (auten/emitir-credencial-agente! repo-identidade ator {:agente agente-da-casa :publico publico
                                                               :classes #{:leitura :ato}})]
    (try
      (let [r (plataforma-ia/executar-agente ia (:ente-id ator) {:pergunta pergunta :credencial credencial
                                                                  :correlation_id (str execucao-id)})]
        (str (apply str (for [p (:passos r)]
                          (evento "passo" {:ferramenta (:ferramenta p) :argumentos (:argumentos p) :ok (:ok p)})))
             (apply str (for [p (when repo-integracao-ia
                                  (repo-ia/propostas-da-execucao repo-integracao-ia (:ente-id ator) execucao-id))]
                          (evento "proposta" {:id (str (:id p)) :titulo (:titulo p) :ritual (:ritual p)})))
             (if-let [resp (:resposta r)]
               (evento "resposta" (cond-> (select-keys resp [:texto :citacoes :paragrafos-sem-fonte :incerteza :modelo
                                                             :contaminado])
                                    (:execucao-id resp) (assoc :execucao-ia (str (:execucao-id resp)))))
               (evento "indisponivel" {:mensagem (or (get-in r [:indisponivel :mensagem]) mensagem-indisponivel)}))
             (evento "fim" {:execucao-id (str execucao-id)})))
      (catch clojure.lang.ExceptionInfo e
        (if (= :ia/indisponivel (:tipo (ex-data e)))
          (do (log/warn "agente indisponivel" (:motivo (ex-data e)))
              (str (evento "indisponivel" {:mensagem mensagem-indisponivel})
                   (evento "fim" {:execucao-id (str execucao-id)})))
          (throw e)))
      (finally
        (repo-id/revogar-credencial-agente! repo-identidade execucao-id)))))

(defn- perguntar-handler [deps]
  (fn [req]
    (let [ator (:ator req)
          p (pedido ator (or (:json-params req) {}))]
      {:status 200
       :headers {"Content-Type" "text/event-stream; charset=utf-8" "Cache-Control" "no-store"}
       :body (conversa deps ator p)})))

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

(defn rotas
  "POST /agente/perguntas — a secretaria ou o vereador perguntam ao assistente da Casa. POST
  /ia/execucoes/:execucao-id/reportes — quem recebeu uma resposta de IA diz que ela esta' errada (feature 8.4)."
  [{:keys [auth] :as deps}]
  #{["/agente/perguntas" :post [auth (it/exige-algum-papel ["secretario" "vereador"]) it/corpo-json
                                 (perguntar-handler deps)]
     :route-name :agente/perguntar]
    ["/ia/execucoes/:execucao-id/reportes" :post [auth it/corpo-json (reportar-handler deps)]
     :route-name :agente/reportar-erro-ia]})
