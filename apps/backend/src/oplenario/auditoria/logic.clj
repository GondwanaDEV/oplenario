(ns oplenario.auditoria.logic
  "A logica PURA da trilha de auditoria da Casa (ADR-0017): o que de uma requisicao vira registro, o selo encadeado,
  o dia civil, o pseudonimo do cidadao e o escopo de leitura. Sem IO.

  O que entra na trilha (§22.5 Eixo E + ADR-0017 Eixo 1-C):
  - toda ESCRITA de um ator da Casa (POST/PUT/PATCH/DELETE), com a decisao: `permitido` (2xx/3xx), `negado` (403) ou
    `falhou` (4xx/5xx);
  - toda NEGACAO por politica (403), inclusive em leitura;
  - a ENTRADA (login), marcada pelo proprio handler do mint (`:auditoria` na resposta — ele nao tem ator ainda);
  - a LEITURA SENSIVEL: ler a trilha.
  Leitura comum nao entra. Ator sem Casa (operador, anonimo) nao entra: o operador tem a corrente dele (ADR-0016).

  O handler pode enriquecer o registro devolvendo `:auditoria` no mapa de resposta (a chave nao vai para o fio):
  `{:rotulo :campos :recurso-tipo :recurso-id :classe :ator}`. E' o \"resumo do efeito\" (ADR-0017 1-C).

  A ESCRITA tem dois registros (ADR-0017, adendo de 04/10/2026): a TENTATIVA (`decisao` = `iniciado`), gravada e
  commitada ANTES do handler, e o DESFECHO, gravado depois, que aponta a tentativa em `detalhe.tentativa` (o seq dela;
  o `detalhe` entra no selo). Tentativa sem desfecho = o ato pode ter acontecido e o registro dele nao foi gravado: a
  leitura e a conferencia a acusam, em vez de o ato sumir."
  (:require [clojure.string :as str]
            [jsonista.core :as json]
            [oplenario.kernel.segredo :as segredo])
  (:import (java.time Instant LocalDate ZoneId)))

(set! *warn-on-reflection* true)

(def zona-da-casa (ZoneId/of "America/Fortaleza"))

(def escrita? #{:post :put :patch :delete})

(def ^:private uuid-re #"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

(def acoes-da-trilha
  "As rotas que LEEM a trilha de outras pessoas sempre: registradas como leitura sensivel (§22.5 Eixo E). A listagem
  marca a si mesma (`:auditoria {:classe ...}`) quando o escopo nao e' so' a propria trilha."
  #{:auditoria/exportar :auditoria/integridade})

(defn- ator-tipo [ator]
  (cond (:via ator) "agente"
        (and (nil? (:identidade-id ator)) (seq (:papeis ator))) "agente"   ; agente institucional (sem pessoa)
        (= "cidadao" (:tipo-vinculo ator)) "cidadao"
        :else "pessoa"))

(defn- classe [metodo status acao marcada]
  (cond marcada marcada
        (= 403 status) "negacao"
        (escrita? metodo) "escrita"
        (acoes-da-trilha acao) "leitura_sensivel"))

(defn- decisao [status]
  (cond (= 403 status) "negado"
        (< (long (or status 500)) 400) "permitido"
        :else "falhou"))

(defn- recurso-do-caminho
  "O primeiro parametro de caminho que e' UUID: e' o recurso sobre o qual o ato caiu (o tipo sai do nome do param)."
  [path-params]
  (some (fn [[k v]] (when (and (string? v) (re-matches uuid-re v))
                      {:recurso-tipo (str/replace (name k) #"-id$" "") :recurso-id v}))
        (sort-by (comp name key) path-params)))

(defn ip-de
  "O IP de origem: o primeiro do `X-Forwarded-For` (o proxy da borda e' nosso) ou o do socket. So' IPv4/IPv6 literais."
  [req]
  (let [xff (some-> (get-in req [:headers "x-forwarded-for"]) (str/split #",") first str/trim)
        ip  (or (not-empty xff) (:remote-addr req))]
    (when (and ip (re-matches #"^[0-9A-Fa-f:.]{2,45}$" ip)) ip)))

(def canais #{"web" "app-vereador" "painel-mesa" "captacao" "agente"})

(defn canal-de [req ator]
  (cond (:via ator) "agente"
        :else (let [c (get-in req [:headers "x-oplenario-canal"])] (if (canais c) c "web"))))

(def iniciado
  "A `decisao` da TENTATIVA: o ato foi iniciado e o desfecho ainda nao foi registrado."
  "iniciado")

(defn- base
  "O que a tentativa e o desfecho tem em comum: quem, o que, sobre o que e de onde."
  [req ator acao]
  (merge {:ente-id       (:ente-id ator)
          :ator-tipo     (ator-tipo ator)
          :identidade-id (:identidade-id ator)
          :papeis        (vec (sort (map name (:papeis ator))))
          :via-agente    (some-> ator :via :agente name)
          :acao          (str (namespace acao) "/" (name acao))
          :canal         (canal-de req ator)
          :ip            (ip-de req)
          :campos        []
          :detalhe       (cond-> {:metodo (some-> (:request-method req) name str/upper-case)}
                           (:via ator) (assoc :execucao (some-> ator :via :execucao-id str)))}
         (recurso-do-caminho (:path-params req))))

(defn registro-da-tentativa
  "Da requisicao que VAI chegar ao handler -> o registro da tentativa (sem selo, sem seq), ou nil. So' a ESCRITA de um
  ator da Casa tem tentativa: e' ela que pode deixar um ato sem registro. A negacao antes do handler, a leitura e a
  entrada nao tem ato a perder e seguem com um registro so'."
  [req acao]
  (let [ator (:ator req)]
    (when (and (:ente-id ator) acao (escrita? (:request-method req)))
      (assoc (base req ator acao) :classe "escrita" :decisao iniciado :status-http nil))))

(defn registro-da-requisicao
  "Da requisicao/resposta ja' respondida -> o registro a gravar (sem selo, sem seq), ou nil se nao entra na trilha.
  `tentativa` (opcional) = o seq da tentativa gravada antes do handler: o desfecho a aponta em `detalhe.tentativa`."
  ([req resp acao] (registro-da-requisicao req resp acao nil))
  ([req resp acao tentativa]
   (let [marca  (:auditoria resp)
         ator   (or (:ator req) (:ator marca))
         status (:status resp)
         cl     (classe (:request-method req) status acao (:classe marca))]
     (when (and (:ente-id ator) acao cl)
       (cond-> (merge (base req ator acao)
                      {:classe cl :decisao (decisao status) :status-http status
                       :campos (vec (sort (map name (:campos marca))))}
                      (select-keys marca [:recurso-tipo :recurso-id :rotulo]))
         tentativa (assoc-in [:detalhe :tentativa] (long tentativa)))))))

;; ---- o selo encadeado ----

(defn- canonico [x] (json/write-value-as-string (into (sorted-map) (update-keys (or x {}) name))))

(defn selo-de
  "Pura: o selo do registro, dado o selo anterior (\"\" no primeiro da Casa). O IP fica de fora (e' o unico campo que
  pode mudar — vira NULL depois de 6 meses). `ocorrido-em` entra como Instant ISO."
  [selo-anterior r]
  (segredo/sha256-hex
   (str/join "|" [selo-anterior (:ente-id r) (:seq r) (:id r) (str (:ocorrido-em r)) (:ator-tipo r)
                  (or (:identidade-id r) "") (str/join "," (:papeis r)) (or (:via-agente r) "") (:acao r) (:classe r)
                  (or (:recurso-tipo r) "") (or (:recurso-id r) "") (or (:rotulo r) "") (str/join "," (:campos r))
                  (:decisao r) (or (:status-http r) "") (:canal r) (canonico (:detalhe r))])))

(defn verificar-passo
  "Pura: um passo da verificacao — `acc` {:integra :total :cabeca :quebra-em} + o proximo registro. A corrente quebra
  se falta seq, se o selo-anterior nao e' o selo do anterior ou se o selo nao confere com o conteudo."
  [{:keys [cabeca total quebra-em] :as acc} r]
  (if quebra-em
    acc
    (let [esperado (inc (long total))]
      (if (and (= esperado (:seq r))
               (= (or cabeca "") (:selo-anterior r))
               (= (:selo r) (selo-de (:selo-anterior r) r)))
        (assoc acc :total esperado :cabeca (:selo r))
        (assoc acc :integra false :quebra-em (:seq r))))))

(defn verificar
  "Pura: a corrente inteira, em ordem de seq -> {:integra :total :cabeca :quebra-em}."
  [registros]
  (reduce verificar-passo {:integra true :total 0 :cabeca nil :quebra-em nil} registros))

;; ---- o dia civil ----

(defn dia-de ^LocalDate [^Instant instante] (.toLocalDate (.atZone instante zona-da-casa)))

;; ---- quem aparece como quem ----

(defn pseudonimo
  "O cidadao na trilha da Casa aparece pseudonimizado (ADR-0017 4c): \"#a1b2c3d4e5f6\", estavel por Casa. 48 bits: com
  6 caracteres (24 bits) dois cidadaos ja' se confundiam com poucos milhares numa Casa; com 12, a chance numa Casa de
  100 mil cidadaos fica em ~2 em 100 mil (e a exportacao recusa a colisao, `encerramento.protecao/sem-colisao`)."
  [ente-id identidade-id]
  (str "#" (subs (segredo/sha256-hex (str ente-id "|" identidade-id)) 0 12)))

;; ---- escopo de leitura (ADR-0017 Eixo 2-B) ----

(defn escopo
  "Quem ve o que: o `auditor` ve a Casa inteira; o `admin_ente` ve os atos de acesso (o que ele administra) e os
  proprios; qualquer outra pessoa ve so' a propria trilha (self-action, §22.5)."
  [ator]
  (let [papeis (set (map name (:papeis ator)))]
    (cond (papeis "auditor") {:tipo :casa}
          (papeis "admin_ente") {:tipo :acessos :identidade-id (:identidade-id ator)}
          :else {:tipo :propria :identidade-id (:identidade-id ator)})))

(def prefixo-dos-acessos "identidade/")

(defn quem
  "Quem agiu, em palavras (a coluna do CSV)."
  [r]
  (case (:ator-tipo r)
    "cidadao" (str "Cidadão " (:ator-pseudonimo r))
    "agente"  (str (or (:ator-nome r) "Agente institucional") (when (:via-agente r) (str " via " (:via-agente r))))
    (str (or (:ator-nome r) "Pessoa sem nome no cadastro")
         (when (:via-agente r) (str ", via agente " (:via-agente r))))))
