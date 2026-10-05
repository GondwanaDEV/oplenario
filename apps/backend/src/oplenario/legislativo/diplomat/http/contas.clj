(ns oplenario.legislativo.diplomat.http.contas
  "Borda HTTP do JULGAMENTO DAS CONTAS (ADR-0021 Parte B). Ns proprio, ao lado de `in.clj` (que ja' carrega as tres
  verticais do modulo): as rotas internas (`/contas`, `/contas-da-proposicao/:id`, `/parametros-de-contas`) e as do
  portal (`/portal/casa/:ente/contas`). Os caminhos foram escolhidos para nao por literal e curinga no mesmo nivel do
  roteador do Pedestal.

  `hoje` (o dia civil da Casa, de que depende o estado derivado) sai do relogio injetado, lido aqui na borda. A base do
  quorum vem do servidor (`membros-da-casa`, seam do host), nunca do cliente — o mesmo denominador do encerramento."
  (:require [clojure.string :as str]
            [oplenario.http :as http]
            [oplenario.interceptors :as it]
            [oplenario.kernel.tempo :as tempo]
            [oplenario.legislativo.adapters.in.contas :as adapters-in]
            [oplenario.legislativo.adapters.out.contas :as adapters-out]
            [oplenario.legislativo.controllers :as controllers]
            [oplenario.legislativo.logic.contas :as logic-contas])
  (:import (java.net URLEncoder)
           (java.nio.charset StandardCharsets)
           (java.time ZoneId)))

(set! *warn-on-reflection* true)

(def ^:private zona-civil (ZoneId/of "America/Fortaleza"))

(defn- hoje [relogio] (tempo/hoje relogio zona-civil))

(def ^:private nao-encontrada (http/json-resposta 404 {:erro "prestação de contas não encontrada"}))

(def ^:private erros-notificacao
  {:mesa [409 "As contas da Mesa são só acompanhamento: não há notificação nem defesa."]
   :julgada [409 "As contas deste exercício já foram julgadas."]})

(defn- ficha [deps ente-id p]
  (adapters-out/prestacao->wire p (hoje (:relogio deps))
                                (controllers/base-do-quorum (:membros-da-casa deps) ente-id p)))

(defn- auditoria
  "O resumo do efeito para a trilha (ADR-0017): o exercicio e o tipo — nunca o conteudo dos documentos."
  [resp p]
  (assoc resp :auditoria {:rotulo (str "Contas " (:exercicio p) " (" (:tipo p) ")")
                          :recurso-tipo "prestacao_contas" :recurso-id (str (:id p))}))

;; ---------- handlers internos ----------

(defn- listar-handler [{:keys [repo-legislativo relogio]}]
  (fn [req]
    (http/json-resposta 200 (adapters-out/prestacoes->wire
                              (controllers/prestacoes-de-contas repo-legislativo (:ente-id (:ator req)))
                              (hoje relogio)))))

(defn- registrar-handler
  [{:keys [repo-legislativo resolver-municipio comissoes-vigentes relogio] :as deps}]
  (fn [req]
    (let [ator (:ator req)
          m (adapters-in/registro->dominio (:json-params req))]
      (try
        (let [p (controllers/registrar-prestacao! repo-legislativo resolver-municipio comissoes-vigentes ator
                                                  (hoje relogio) m)]
          (auditoria (http/json-resposta 201 (ficha deps (:ente-id ator) p)) p))
        (catch clojure.lang.ExceptionInfo e
          (if (= :conflito/prestacao-duplicada (:tipo (ex-data e)))
            (http/json-resposta 409 {:erro (str "Já há prestação de contas "
                                                (if (= "governo_prefeito" (:tipo m)) "do Prefeito" "da Mesa")
                                                " do exercício de " (:exercicio m) ".")})
            (throw e)))))))

(defn- detalhe-handler [{:keys [repo-legislativo] :as deps}]
  (fn [req]
    (let [ente-id (:ente-id (:ator req))]
      (if-let [p (some->> (adapters-in/id-do-path req :id) (controllers/prestacao-de-contas repo-legislativo ente-id))]
        (http/json-resposta 200 (ficha deps ente-id p))
        nao-encontrada))))

(defn- da-proposicao-handler [{:keys [repo-legislativo] :as deps}]
  (fn [req]
    (let [ente-id (:ente-id (:ator req))]
      (if-let [p (some->> (adapters-in/id-do-path req :proposicao-id)
                          (controllers/prestacao-da-proposicao repo-legislativo ente-id))]
        (http/json-resposta 200 (ficha deps ente-id p))
        (http/json-resposta 404 {:erro "esta matéria não é de julgamento de contas"})))))

(defn- editar-handler [{:keys [repo-legislativo] :as deps}]
  (fn [req]
    (let [ator (:ator req)
          m (adapters-in/edicao->dominio (:json-params req))]
      (if-let [p (when-let [id (adapters-in/id-do-path req :id)]
                   (controllers/atualizar-prestacao! repo-legislativo ator id m))]
        (auditoria (http/json-resposta 200 (ficha deps (:ente-id ator) p)) p)
        nao-encontrada))))

(defn- notificacao-handler [{:keys [repo-legislativo relogio] :as deps}]
  (fn [req]
    (let [ator (:ator req)
          m (adapters-in/notificacao->dominio (:json-params req))
          id (adapters-in/id-do-path req :id)
          {:keys [prestacao erro] :as r} (if id
                                           (controllers/notificar-prestacao! repo-legislativo ator (hoje relogio) id m)
                                           {:erro :nao-encontrada})]
      (cond
        prestacao (auditoria (http/json-resposta 200 (ficha deps (:ente-id ator) prestacao)) prestacao)
        (= :nao-encontrada erro) nao-encontrada
        (= :ja-notificada erro)
        (http/json-resposta 409 {:erro (str "O responsável já foi notificado em "
                                            (logic-contas/data-br (:notificado-em r))
                                            "; o prazo de defesa já está correndo.")})
        :else (let [[status msg] (get erros-notificacao erro [500 "erro interno"])]
                (http/json-resposta status {:erro msg}))))))

;; ---------- documentos: um arquivo por requisicao, multipart (o MESMO parser dos anexos dos comunicados e do balcao) ----------

(def documento-multipart
  "Interceptor do upload: o parser comum da borda (`it/anexo-multipart`), com o teto de 10 MB dos documentos das contas.
  Dele vem tudo o que e' generico: o teto do cabecalho da parte (um nome de 255 caracteres acentuados passa), o corpo
  limitado pelo que e' LIDO, o limite de partes, o teto global de envios simultaneos (503) e UM envio por pessoa (429),
  e o nome limpo por `kernel/arquivo` (sem caractere de formato Unicode; nome vazio vira \"anexo\"). So' o texto do 413
  e' daqui: a tela fala em documento. Resultado em `(:request :anexo)` {:nome :tipo-midia :conteudo}."
  (it/anexo-multipart {:max-bytes logic-contas/max-bytes-documento
                       :mensagem-do-teto "O documento passa de 10 MB."}))

(def ^:private tipo-antes-do-corpo
  "O `?tipo=` conferido ANTES de o parser ler o corpo: sem isto, um tipo errado so' era recusado depois de ate' 10 MB lidos
  para a memoria, segurando uma das vagas de envio do processo. So' o tipo: se a prestacao existe continua sendo visto
  DEPOIS do corpo, e a fumaca de producao depende disso (envia a uma prestacao inexistente para exercitar o parser sem
  gravar nada — `fumaca-hml`, caso 14)."
  {:name ::tipo-antes-do-corpo
   :enter (fn [ctx]
            (adapters-in/tipo-do-documento (get-in ctx [:request :query-params]))
            ctx)})

(defn- anexar-handler [{:keys [repo-legislativo objeto-store]}]
  (fn [req]
    (let [tipo (adapters-in/tipo-do-documento (:query-params req))
          id (adapters-in/id-do-path req :id)]
      (if-let [d (when id (controllers/anexar-documento-de-contas! repo-legislativo objeto-store (:ator req) id tipo
                                                                   (:anexo req)))]
        (assoc (http/json-resposta 201 (adapters-out/documento->wire d))
               :auditoria {:rotulo (str (:tipo d) ": " (:nome d)) :recurso-tipo "prestacao_contas"
                           :recurso-id (str id)})
        nao-encontrada))))

(defn- content-disposition
  "attachment com o nome em ASCII (fallback) e em UTF-8 (RFC 5987) — o nome ja' vem sem aspas nem controle."
  [nome]
  (let [ascii (str/replace nome #"[^\x20-\x7E]" "_")
        utf8 (str/replace (URLEncoder/encode ^String nome StandardCharsets/UTF_8) "+" "%20")]
    (str "attachment; filename=\"" ascii "\"; filename*=UTF-8''" utf8)))

(defn- arquivo [{:keys [documento stream]}]
  {:status 200
   :headers {"Content-Type" (:tipo-midia documento)
             "Content-Length" (str (:tamanho-bytes documento))
             "Content-Disposition" (content-disposition (:nome documento))}
   :body stream})

(defn- baixar-handler [{:keys [repo-legislativo objeto-store]}]
  (fn [req]
    (let [id (adapters-in/id-do-path req :id)
          doc-id (adapters-in/id-do-path req :doc-id)]
      (if-let [r (when (and id doc-id)
                   (controllers/baixar-documento-de-contas repo-legislativo objeto-store (:ente-id (:ator req))
                                                           id doc-id false))]
        (arquivo r)
        (http/json-resposta 404 {:erro "documento não encontrado"})))))

;; ---------- parametros da Casa ----------

(defn- parametros-handler [{:keys [repo-legislativo]}]
  (fn [req]
    (http/json-resposta 200 (adapters-out/parametros->wire
                              (controllers/parametros-de-contas repo-legislativo (:ente-id (:ator req)))))))

(defn- salvar-parametros-handler [{:keys [repo-legislativo]}]
  (fn [req]
    (let [m (adapters-in/parametros->dominio (:json-params req))]
      (http/json-resposta 200 (adapters-out/parametros->wire
                                (controllers/salvar-parametros-de-contas! repo-legislativo (:ator req) m))))))

;; ---------- portal (publico) ----------

(defn- portal-handler [{:keys [repo-legislativo relogio resolver-ente-publico casa-existe?]}]
  (fn [req]
    (let [ente-id (resolver-ente-publico (get-in req [:path-params :ente]))]
      (if (casa-existe? ente-id)
        (http/json-resposta 200 (adapters-out/publicas->wire (controllers/prestacoes-de-contas repo-legislativo ente-id)
                                                             (hoje relogio)))
        (http/json-resposta 404 {:erro "ente nao encontrado"})))))

(defn- portal-documento-handler [{:keys [repo-legislativo objeto-store resolver-ente-publico casa-existe?]}]
  (fn [req]
    (let [ente-id (resolver-ente-publico (get-in req [:path-params :ente]))
          id (adapters-in/id-do-path req :id)
          doc-id (adapters-in/id-do-path req :doc-id)]
      (if-let [r (when (and id doc-id (casa-existe? ente-id))
                   (controllers/baixar-documento-de-contas repo-legislativo objeto-store ente-id id doc-id true))]
        (arquivo r)
        (http/json-resposta 404 {:erro "documento não encontrado"})))))

(defn motivo-nao-pautavel
  "Ponto de entrada IN-PROCESS da costura da PAUTA (o host injeta em `sessoes`): nil se a proposicao pode entrar na pauta
  hoje; o motivo em palavras se e' o PDL de contas que ainda nao esta' pronto. Gemeo nao-HTTP, como
  `pareceres-juridicos-publicos-wire`: NAO verifica papel — quem chama (a inclusao na pauta) ja' passou o gate dela."
  [repo-legislativo relogio ente-id proposicao-id]
  (controllers/motivo-nao-pautavel repo-legislativo ente-id (hoje relogio) proposicao-id))

(defn rotas
  "`deps` = {:auth :repo-legislativo :objeto-store :relogio :membros-da-casa :resolver-municipio :comissoes-vigentes
  :resolver-ente-publico :casa-existe?}. Escrita: secretaria (os parametros, o admin_ente). Leitura da ficha: secretaria,
  vereador e juridico (o painel de votacao, secretaria e vereador). Portal: anonimo, so' os documentos do TCE."
  [{:keys [auth relogio comissoes-vigentes membros-da-casa resolver-ente-publico casa-existe?] :as deps}]
  (let [deps (assoc deps
                    :relogio (or relogio (tempo/relogio-sistema))
                    :comissoes-vigentes (or comissoes-vigentes (constantly []))
                    :membros-da-casa (or membros-da-casa (constantly 0))
                    :casa-existe? (or casa-existe? (constantly false))
                    :resolver-ente-publico (or resolver-ente-publico (constantly nil)))
        papel (it/exige-papel "secretario")
        papel-leitura (it/exige-algum-papel #{"secretario" "vereador" "juridico"})
        papel-painel (it/exige-algum-papel #{"secretario" "vereador"})
        papel-parametros (it/exige-algum-papel #{"secretario" "admin_ente"})
        papel-admin-ente (it/exige-papel "admin_ente")]
    #{["/contas" :get [auth papel-leitura (listar-handler deps)] :route-name :legislativo/listar-contas]
      ["/contas" :post [auth papel it/corpo-json (registrar-handler deps)] :route-name :legislativo/registrar-contas]
      ["/contas/:id" :get [auth papel-leitura (detalhe-handler deps)] :route-name :legislativo/prestacao-contas]
      ["/contas/:id" :patch [auth papel it/corpo-json (editar-handler deps)] :route-name :legislativo/editar-contas]
      ["/contas/:id/notificacao" :post [auth papel it/corpo-json (notificacao-handler deps)]
       :route-name :legislativo/notificar-contas]
      ["/contas/:id/documentos" :post [auth papel tipo-antes-do-corpo documento-multipart (anexar-handler deps)]
       :route-name :legislativo/anexar-documento-contas]
      ["/contas/:id/documentos/:doc-id" :get [auth papel-leitura (baixar-handler deps)]
       :route-name :legislativo/baixar-documento-contas]
      ["/contas-da-proposicao/:proposicao-id" :get [auth papel-painel (da-proposicao-handler deps)]
       :route-name :legislativo/contas-da-proposicao]
      ["/parametros-de-contas" :get [auth papel-parametros (parametros-handler deps)]
       :route-name :legislativo/parametros-de-contas]
      ["/parametros-de-contas" :put [auth papel-admin-ente it/corpo-json (salvar-parametros-handler deps)]
       :route-name :legislativo/salvar-parametros-de-contas]
      ["/portal/casa/:ente/contas" :get [(portal-handler deps)] :route-name :legislativo/contas-publicas]
      ["/portal/casa/:ente/contas/:id/documentos/:doc-id" :get [(portal-documento-handler deps)]
       :route-name :legislativo/documento-contas-publico]}))
