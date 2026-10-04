(ns oplenario.comunicacao.diplomat.http.in
  "Borda HTTP dos comunicados internos da Casa (ADR-0020). Todas as rotas pedem login de PESSOA DA CASA (o controller
  recusa o cidadao com 403). As leituras da tela gravam marca: a caixa grava `recebido` dos que entrega, o detalhe grava
  `lido` para o destinatario. Comunicado que o ator nao pode ver responde 404 (nao confirma que existe).

  `hoje` (o dia civil da Casa) e `agora` vem do relogio injetado, lidos aqui na borda via o controller."
  (:require [clojure.string :as str]
            [oplenario.comunicacao.adapters.in.comunicado :as adapters-in]
            [oplenario.comunicacao.adapters.out.comunicado :as adapters-out]
            [oplenario.comunicacao.controllers :as controllers]
            [oplenario.comunicacao.logic :as logic]
            [oplenario.http :as http]
            [oplenario.interceptors :as it])
  (:import (java.net URLEncoder)
           (java.nio.charset StandardCharsets)))

(set! *warn-on-reflection* true)

(def ^:private nao-encontrado (http/json-resposta 404 {:erro "comunicado não encontrado"}))

(defn- conflito
  "Os conflitos de dominio -> a resposta nomeada (nunca 500). nil = nao e' conflito daqui."
  [e]
  (let [d (ex-data e)]
    (case (:tipo d)
      :conflito/destino-inexistente
      (http/json-resposta 422 {:erro "Um dos destinos não existe nesta Casa (ou o setor foi desativado)."
                               :destino (:destino d)})
      :conflito/lista-vazia
      (http/json-resposta 422 {:erro "Ninguém com acesso ao sistema recebe este comunicado."
                               :sem-acesso (long (or (:sem-acesso d) 0))})
      :conflito/substituicao-invalida
      (http/json-resposta 422 {:erro "O comunicado a substituir não foi encontrado (ou você não pode corrigi-lo)."})
      :conflito/ja-substituido (http/json-resposta 409 {:erro "Este comunicado já foi substituído."})
      :conflito/sem-ciencia (http/json-resposta 409 {:erro "Este comunicado não pede ciência."})
      :conflito/fora-da-janela
      (http/json-resposta 409 {:erro "Os anexos vão junto com o comunicado: os 10 minutos depois do envio já passaram."})
      :conflito/anexos-demais
      (http/json-resposta 409 {:erro (str "O comunicado já tem " logic/max-anexos " anexos.")})
      nil)))

(defmacro ^:private com-conflitos [& corpo]
  `(try ~@corpo
        (catch clojure.lang.ExceptionInfo e# (or (conflito e#) (throw e#)))))

(defn- auditoria
  "O resumo do efeito para a trilha (ADR-0017): o protocolo — nunca o assunto nem o texto."
  [resp c]
  (assoc resp :auditoria {:rotulo (:protocolo c) :recurso-tipo "comunicado" :recurso-id (str (:id c))}))

;; ---------- handlers ----------

(defn- destinos-handler [deps]
  (fn [req]
    (http/json-resposta 200 (adapters-out/destinos->wire (controllers/destinos deps (:ator req))))))

(defn- enviar-handler [deps]
  (fn [req]
    (let [ator (:ator req)
          pedido (adapters-in/envio->dominio (:json-params req))]
      (com-conflitos
       (let [{:keys [comunicado sem-acesso]} (controllers/enviar! deps ator pedido)]
         (auditoria (http/json-resposta 201 (adapters-out/enviado->wire
                                             comunicado (controllers/hoje deps)
                                             (controllers/extras-do-detalhe deps ator comunicado) sem-acesso))
                    comunicado))))))

(defn- caixa-handler [deps]
  (fn [req]
    (http/json-resposta 200 (adapters-out/caixa->wire (controllers/caixa! deps (:ator req)) (controllers/hoje deps)))))

(defn- contagem-handler [deps]
  (fn [req]
    (http/json-resposta 200 (adapters-out/contagem->wire (controllers/contagem deps (:ator req)) (controllers/hoje deps)))))

(defn- ler-handler [deps]
  (fn [req]
    (let [ator (:ator req)]
      (if-let [c (some->> (adapters-in/id-do-path req :id) (controllers/ler! deps ator))]
        (http/json-resposta 200 (adapters-out/comunicado->wire c (controllers/hoje deps)
                                                               (controllers/extras-do-detalhe deps ator c)))
        nao-encontrado))))

(defn- ciencia-handler [deps]
  (fn [req]
    (com-conflitos
     (if-let [{:keys [comunicado marcas]} (some->> (adapters-in/id-do-path req :id)
                                                   (controllers/registrar-ciencia! deps (:ator req)))]
       (auditoria (http/json-resposta 200 (adapters-out/ciencia->wire comunicado marcas (controllers/hoje deps)))
                  comunicado)
       nao-encontrado))))

(defn- enviados-handler [deps]
  (fn [req]
    (let [escopo (adapters-in/escopo req)]
      (http/json-resposta 200 (adapters-out/enviados->wire escopo (controllers/enviados deps (:ator req) escopo)
                                                           (controllers/hoje deps))))))

(defn- leitura-handler [deps]
  (fn [req]
    (if-let [{:keys [comunicado linhas]} (some->> (adapters-in/id-do-path req :id) (controllers/leitura deps (:ator req)))]
      (http/json-resposta 200 (adapters-out/leitura->wire comunicado linhas (controllers/hoje deps)))
      nao-encontrado)))

;; ---------- anexos (fatia 2): um arquivo por requisicao, multipart ----------

;; O interceptor do upload (`it/anexo-multipart`) e' o compartilhado do host; aqui so' o teto de 10 MB do comunicado.
(def anexo-multipart
  "O interceptor do upload de anexo do comunicado: o generico do host com o teto de `logic/max-bytes-anexo`."
  (it/anexo-multipart {:max-bytes logic/max-bytes-anexo}))

(defn- anexar-handler [deps]
  (fn [req]
    (com-conflitos
     (if-let [a (when-let [id (adapters-in/id-do-path req :id)]
                  (controllers/anexar! deps (:ator req) id (:anexo req)))]
       (assoc (http/json-resposta 201 (adapters-out/anexo->wire a))
              :auditoria {:rotulo (:nome a) :recurso-tipo "comunicado" :recurso-id (str (:comunicado-id a))})
       nao-encontrado))))

(defn- content-disposition
  "attachment com o nome em ASCII (fallback) e em UTF-8 (RFC 5987) — o nome ja' vem sem aspas nem controle."
  [nome]
  (let [ascii (str/replace nome #"[^\x20-\x7E]" "_")
        utf8 (str/replace (URLEncoder/encode ^String nome StandardCharsets/UTF_8) "+" "%20")]
    (str "attachment; filename=\"" ascii "\"; filename*=UTF-8''" utf8)))

(defn- baixar-anexo-handler [deps]
  (fn [req]
    (if-let [{:keys [anexo stream]} (let [id (adapters-in/id-do-path req :id)
                                          aid (adapters-in/id-do-path req :anexo)]
                                      (when (and id aid) (controllers/baixar-anexo deps (:ator req) id aid)))]
      {:status 200
       :headers {"Content-Type" (:tipo-midia anexo)
                 "Content-Length" (str (:bytes anexo))
                 "Content-Disposition" (content-disposition (:nome anexo))}
       :body stream}
      nao-encontrado)))

(defn rotas
  "`deps` = ver `oplenario.comunicacao.controllers`. O `auth` e' o interceptor de Casa (a pessoa); a regra de quem pode
  o que mora no controller (pessoa da Casa; grupo so' com papel ou Mesa; destinatario/remetente/secretaria).

  DIVERGENCIA DO CONTRATO DA ADR (registrada): a ADR escreve `GET /comunicados/caixa`, `/comunicados/enviados` e
  `/comunicados/destinos` ao lado de `GET /comunicados/:id`. O roteador do Pedestal (prefix-tree) da' prioridade ao
  parametro: com `/comunicados/:id` montado, `/comunicados/caixa` cai em `:id = \"caixa\"` (404). As tres leituras de
  colecao moram em `/meu/comunicados` (a caixa — irma de `/meu/notificacoes`, as duas metades da \"caixa so'\" do
  Eixo 7), `/meu/comunicados/enviados` (`?escopo=casa`) e `/meu/comunicados/destinos`. As rotas do recurso
  (`/comunicados/:id/...`) seguem a ADR."
  [{:keys [auth] :as deps}]
  #{["/meu/comunicados/destinos" :get [auth (destinos-handler deps)] :route-name :comunicacao/destinos]
    ["/comunicados" :post [auth it/corpo-json (enviar-handler deps)] :route-name :comunicacao/enviar]
    ["/meu/comunicados" :get [auth (caixa-handler deps)] :route-name :comunicacao/caixa]
    ["/meu/comunicados/contagem" :get [auth (contagem-handler deps)] :route-name :comunicacao/contagem]
    ["/meu/comunicados/enviados" :get [auth (enviados-handler deps)] :route-name :comunicacao/enviados]
    ["/comunicados/:id" :get [auth (ler-handler deps)] :route-name :comunicacao/ler]
    ["/comunicados/:id/ciencia" :post [auth (ciencia-handler deps)] :route-name :comunicacao/ciencia]
    ["/comunicados/:id/leitura" :get [auth (leitura-handler deps)] :route-name :comunicacao/leitura]
    ["/comunicados/:id/anexos" :post [auth anexo-multipart (anexar-handler deps)] :route-name :comunicacao/anexar]
    ["/comunicados/:id/anexos/:anexo" :get [auth (baixar-anexo-handler deps)] :route-name :comunicacao/baixar-anexo]})
