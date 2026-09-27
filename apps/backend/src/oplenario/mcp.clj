(ns oplenario.mcp
  "Host (§22.10): o SERVIDOR MCP do core (docs/25 Eixo 5.1, ADR-0009/ADR-0010) — mais um adaptador de entrada, ao lado
  do HTTP das telas. Serve o CATALOGO DE ACOES a quem chega com a credencial delegada de um agente: o satelite de IA
  hoje, o cliente de fora (Eixo 6) depois, com outro login e outro conjunto.

  Transporte: MCP 'Streamable HTTP' no modo sem estado — cada POST e' uma mensagem JSON-RPC 2.0 e a resposta vem em
  `application/json` (o servidor nao inicia mensagens, entao nao abre stream). Metodos: `initialize`, `ping`,
  `tools/list`, `tools/call`; notificacoes (sem `id`) recebem 202.

  Todo resultado leva `_meta` com a ORIGEM (interno | terceiro, Eixo 4.5) e o SIGILO do conteudo: o catalogo so' entrega
  ao agente o que pode ir a IA (sessao secreta fica fora na propria entrada), e o satelite recusa o que vier sem
  `publico` (fail-closed, B1).

  Erros: ferramenta desconhecida e metodo desconhecido sao erro de PROTOCOLO (JSON-RPC); negacao, entrada invalida e
  nao-encontrado sao resultado com `isError` (o agente le e corrige o rumo). Nenhum detalhe interno sai."
  (:require [clojure.tools.logging :as log]
            [jsonista.core :as json]
            [oplenario.catalogo :as catalogo]
            [oplenario.http :as http]
            [oplenario.interceptors :as it]
            [oplenario.kernel.catalogo :as kcat]))

(set! *warn-on-reflection* true)

(def versao-protocolo "2025-06-18")

(def ^:private instrucoes
  (str "Ferramentas do O Plenario, a plataforma da Camara Municipal. Cada ferramenta e' uma acao que uma pessoa da "
       "Camara faria; voce age em nome da pessoa que pediu, com as permissoes dela. Nunca afirme o que nao veio de "
       "uma ferramenta."))

(defn- ferramenta->mcp [{:keys [nome descricao classe entrada saida]}]
  {:name nome
   :description descricao
   :inputSchema entrada
   :outputSchema saida
   :annotations {:readOnlyHint (= "leitura" classe)}
   :_meta {"oplenario/classe" classe}})

(defn- texto [s] [{:type "text" :text s}])

(defn- resultado-erro [msg] {:content (texto msg) :isError true})

(defn- chamar [deps ator params]
  (let [nome (get params "name")
        argumentos (get params "arguments")]
    (try
      (if-some [saida (catalogo/executar! deps ator nome argumentos)]
        {:content (texto (json/write-value-as-string saida))
         :structuredContent saida
         :isError false
         :_meta {"oplenario/origem" (kcat/origem (get catalogo/por-nome nome) saida) "oplenario/sigilo" "publico"}}
        (resultado-erro "Nada encontrado para o que foi informado (ou nao e' visivel para esta pessoa)."))
      (catch clojure.lang.ExceptionInfo e
        (let [{:keys [tipo erros]} (ex-data e)]
          (case tipo
            :validacao/ferramenta-desconhecida ::desconhecida
            :validacao/invalido (resultado-erro (str "Entrada invalida: " (json/write-value-as-string erros)))
            :autorizacao/negado (resultado-erro "Esta pessoa nao tem permissao para esta acao.")
            (throw e)))))))

(defn- rpc-resultado [id r] {:jsonrpc "2.0" :id id :result r})
(defn- rpc-erro [id codigo msg] {:jsonrpc "2.0" :id id :error {:code codigo :message msg}})

(defn responder
  "Uma mensagem JSON-RPC (mapa de chaves string) -> resposta (mapa) ou nil para notificacao."
  [deps ator msg]
  (let [id (get msg "id")
        metodo (get msg "method")
        params (let [p (get msg "params")] (if (map? p) p {}))]
    (cond
      (not (and (map? msg) (= "2.0" (get msg "jsonrpc")) (string? metodo)))
      (rpc-erro id -32600 "requisicao invalida")

      (nil? id) nil

      (= "initialize" metodo)
      (rpc-resultado id {:protocolVersion versao-protocolo
                         :capabilities {:tools {:listChanged false}}
                         :serverInfo {:name "oplenario" :version "1"}
                         :instructions instrucoes})

      (= "ping" metodo) (rpc-resultado id {})

      (= "tools/list" metodo)
      (rpc-resultado id {:tools (mapv ferramenta->mcp (catalogo/ferramentas ator))})

      (= "tools/call" metodo)
      (let [r (chamar deps ator params)]
        (if (= ::desconhecida r)
          (rpc-erro id -32602 (str "ferramenta desconhecida: " (get params "name")))
          (rpc-resultado id r)))

      :else (rpc-erro id -32601 (str "metodo desconhecido: " metodo)))))

(defn- mcp-handler [deps]
  (fn [req]
    (let [msg (:json-params req)]
      (if (vector? msg)
        (http/json-resposta 400 (rpc-erro nil -32600 "lote nao suportado"))
        (try
          (if-some [r (responder deps (:ator req) msg)]
            (http/json-resposta 200 r)
            {:status 202 :headers {} :body ""})
          (catch Exception e
            (log/error e "erro no servidor MCP")
            (http/json-resposta 200 (rpc-erro (get msg "id") -32603 "erro interno"))))))))

(defn rotas
  "POST /integracao/ia/v1/mcp — so' com a credencial delegada (ADR-0010)."
  [{:keys [repo-identidade deps]}]
  #{["/integracao/ia/v1/mcp" :post [(it/autenticacao-agente repo-identidade) it/corpo-json (mcp-handler deps)]
     :route-name :integracao-ia/mcp]})
