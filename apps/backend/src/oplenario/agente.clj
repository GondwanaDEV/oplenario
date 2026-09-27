(ns oplenario.agente
  "Host (§22.10): a TELA conversa com o agente pelo core (docs/25 Eixo 5.2 — navegador -> core -> satelite; o satelite
  nunca e' exposto). A pessoa pergunta numa tela; o core emite a credencial delegada DAQUELA execucao (ADR-0010, so'
  leitura por enquanto), chama o satelite, devolve a conversa em SSE e revoga a credencial ao fim — com sucesso ou nao.

  O publico do agente sai do papel da pessoa: 'secretario' -> conjunto da secretaria, 'vereador' -> do vereador; quem
  tem os dois escolhe pelo campo `publico`, e nunca um publico cujo papel nao tem (403).

  SSE (§22.3.2): `passo` (cada ferramenta chamada, com o desfecho), depois `resposta` (texto, citacoes conferidas,
  incerteza) ou `indisponivel` (R-IA-1: 'siga pela tela'), e `fim`. Hoje o satelite responde de uma vez e o core
  emite os eventos em sequencia; quando o fornecedor real transmitir aos poucos, a mesma forma carrega o fluxo."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [jsonista.core :as json]
            [oplenario.identidade.autenticacao :as auten]
            [oplenario.identidade.components.repositorio :as repo-id]
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
  [{:keys [repo-identidade ia]} ator {:keys [pergunta publico]}]
  (let [{:keys [execucao-id credencial]}
        (auten/emitir-credencial-agente! repo-identidade ator {:agente agente-da-casa :publico publico
                                                               :classes #{:leitura}})]
    (try
      (let [r (plataforma-ia/executar-agente ia (:ente-id ator) {:pergunta pergunta :credencial credencial
                                                                  :correlation_id (str execucao-id)})]
        (str (apply str (for [p (:passos r)]
                          (evento "passo" {:ferramenta (:ferramenta p) :argumentos (:argumentos p) :ok (:ok p)})))
             (if-let [resp (:resposta r)]
               (evento "resposta" (select-keys resp [:texto :citacoes :paragrafos-sem-fonte :incerteza :modelo
                                                     :contaminado]))
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

(defn rotas
  "POST /agente/perguntas — a secretaria ou o vereador perguntam ao assistente da Casa."
  [{:keys [auth] :as deps}]
  #{["/agente/perguntas" :post [auth (it/exige-algum-papel ["secretario" "vereador"]) it/corpo-json
                                 (perguntar-handler deps)]
     :route-name :agente/perguntar]})
