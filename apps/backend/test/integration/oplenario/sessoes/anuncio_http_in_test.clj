(ns oplenario.sessoes.anuncio-http-in-test
  "docs/23 Fatia 4b — a BORDA HTTP do anuncio de item da pauta: `POST /sessoes/:id/pauta/itens/:item-id/anuncio`
  (sem corpo; papel 'secretario'). 201 quando o anuncio e' criado, 200 no reenvio (`:ja-anunciado`), 404 para
  sessao/item ausente ou item de OUTRA sessao (anti confused-deputy, mesmo guard de reordenar/remover), 409 para
  sessao fechada e para o gate do Repo (`:conflito/anuncio`), 400 para uuid malformado, 403 sem o papel. E a
  LEITURA: `GET .../pauta` carrega `em-apreciacao` so' quando o item anunciado segue na pauta.
  DB-free: RepoSessoes FAKE + idp-dev real — espelha o pauta-write-http-in-test."
  (:require [clojure.test :refer [deftest is]]
            [io.pedestal.http :as ph]
            [io.pedestal.test :as pt]
            [jsonista.core :as json]
            [oplenario.config :as config]
            [oplenario.http :as http]
            [oplenario.identidade.components.repositorio :as repo-id]
            [oplenario.interceptors :as it]
            [oplenario.kernel.components.idp-dev :as idp-dev]
            [oplenario.rotas :as rotas]
            [oplenario.sessoes.components.repositorio :as repo-sessoes])
  (:import (java.time Instant)))

(def ^:private pauta-id (random-uuid))
(def ^:private anunciado-em (Instant/parse "2026-09-24T12:05:00Z"))

(defn- sessao [estado] (fn [ente-id id] {:id id :ente-id ente-id :estado estado :tipo-sessao "ordinaria"}))

(defn- fake-repo-sessoes
  "RepoSessoes fake (parcial). `item` = o que `buscar-item` devolve (nil = inexistente); `anunciar-fn` simula
  o Repo (recibo, reenvio ou conflito). `cap` guarda o mapa que chegou ao Repo."
  [& {:keys [sessao-fn item anunciar-fn cap itens anuncio]
      :or {sessao-fn (sessao "aberta") cap (atom nil)}}]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-sessoes/RepoSessoes
    (buscar-sessao [_ ente-id id] (sessao-fn ente-id id))
    (buscar-pauta-por-sessao [_ _ente-id _sessao-id] {:id pauta-id})
    (buscar-item [_ _ente-id id] (when item (assoc item :id id)))
    (listar-itens [_ _ente-id _pauta-sessao-id] itens)
    (item-em-apreciacao [_ _ente-id _sessao-id]
      (if (instance? Exception anuncio) (throw anuncio) anuncio))
    (anunciar-item! [_ ente-id m]
      (reset! cap (assoc m :ente-id ente-id))
      ((or anunciar-fn (fn [mm] {:id (:id mm) :pauta-item-id (:pauta-item-id mm) :anunciado-em anunciado-em})) m))))

(defn- fake-repo-identidade [papeis]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-id/RepoIdentidade
    (snapshot-ator [_ _ente-id _identidade-id]
      {:vinculo-ativo {:id (random-uuid) :tipo "servidor"} :papeis papeis})))

(defn- service-fn* [papeis repo-s]
  (-> (http/servico (config/carregar)
                    (rotas/montar {:idp (idp-dev/idp-dev)
                                   :repo-identidade (fake-repo-identidade papeis)
                                   :repo-sessoes repo-s
                                   :objeto-store nil})
                    it/globais)
      ph/create-server ::ph/service-fn))

(defn- token [ente-id ident-id]
  (json/write-value-as-string {:sub "u" :ente-id (str ente-id) :identidade-id (str ident-id)}))
(defn- com-bearer [tok] {"authorization" (str "Bearer " tok)})
(defn- ler-json [r] (json/read-value (:body r) json/keyword-keys-object-mapper))
(defn- url [sid iid] (str "/sessoes/" sid "/pauta/itens/" iid "/anuncio"))

(def ^:private item-desta-pauta {:pauta-sessao-id pauta-id :ativo true :tipo-item "proposicao"})

(deftest anunciar-201-com-autor-e-instante-do-servidor
  (let [ente (random-uuid) sid (random-uuid) iid (random-uuid) op (random-uuid)
        cap (atom nil)
        r (pt/response-for (service-fn* #{"secretario"} (fake-repo-sessoes :item item-desta-pauta :cap cap))
                           :post (url sid iid) :headers (com-bearer (token ente op)))
        body (ler-json r)]
    (is (= 201 (:status r)))
    (is (= (str iid) (:item-id body)))
    (is (= "2026-09-24T12:05:00Z" (:anunciado-em body)))
    (is (= #{:id :item-id :anunciado-em} (set (keys body))) "o recibo e' fechado: sem created-by/registrado-em")
    (is (= sid (:sessao-id @cap)))
    (is (= iid (:pauta-item-id @cap)))
    (is (= op (:created-by @cap)) "created-by = o ator, nunca do cliente")
    (is (instance? Instant (:anunciado-em @cap)) "o instante vem do relogio do servidor")))

(deftest reanunciar-o-mesmo-item-200
  (let [r (pt/response-for (service-fn* #{"secretario"}
                                        (fake-repo-sessoes :item item-desta-pauta
                                                           :anunciar-fn (fn [mm] {:id (random-uuid) :pauta-item-id (:pauta-item-id mm)
                                                                                  :anunciado-em anunciado-em :ja-anunciado true})))
                           :post (url (random-uuid) (random-uuid)) :headers (com-bearer (token (random-uuid) (random-uuid))))]
    (is (= 200 (:status r)) "reenvio: 200 com o anuncio existente, nao 201")))

(deftest item-de-outra-sessao-404
  (let [r (pt/response-for (service-fn* #{"secretario"}
                                        (fake-repo-sessoes :item {:pauta-sessao-id (random-uuid) :ativo true}))
                           :post (url (random-uuid) (random-uuid)) :headers (com-bearer (token (random-uuid) (random-uuid))))]
    (is (= 404 (:status r)) "item de outra pauta -> 404 (anti confused-deputy)")))

(deftest item-ou-sessao-inexistente-404
  (let [h (com-bearer (token (random-uuid) (random-uuid)))]
    (is (= 404 (:status (pt/response-for (service-fn* #{"secretario"} (fake-repo-sessoes :item nil))
                                         :post (url (random-uuid) (random-uuid)) :headers h))))
    (is (= 404 (:status (pt/response-for (service-fn* #{"secretario"} (fake-repo-sessoes :sessao-fn (fn [_ _] nil)))
                                         :post (url (random-uuid) (random-uuid)) :headers h))))))

(deftest sessao-fechada-409-sem-chegar-ao-repo
  (let [cap (atom nil)
        r (pt/response-for (service-fn* #{"secretario"} (fake-repo-sessoes :sessao-fn (sessao "encerrada")
                                                                           :item item-desta-pauta :cap cap))
                           :post (url (random-uuid) (random-uuid)) :headers (com-bearer (token (random-uuid) (random-uuid))))]
    (is (= 409 (:status r)))
    (is (nil? @cap) "a porta da sessao fechada fecha antes do Repo")))

(deftest gate-do-repo-409-com-a-mensagem-do-dominio
  (let [r (pt/response-for (service-fn* #{"secretario"}
                                        (fake-repo-sessoes :item item-desta-pauta
                                                           :anunciar-fn (fn [_] (throw (ex-info "so' se anuncia item com a sessao aberta"
                                                                                                {:tipo :conflito/anuncio :motivo :sessao-nao-aberta})))))
                           :post (url (random-uuid) (random-uuid)) :headers (com-bearer (token (random-uuid) (random-uuid))))]
    (is (= 409 (:status r)))
    (is (= "so' se anuncia item com a sessao aberta" (:erro (ler-json r))))))

(deftest uuid-malformado-400
  (let [r (pt/response-for (service-fn* #{"secretario"} (fake-repo-sessoes :item item-desta-pauta))
                           :post (url (random-uuid) "nao-e-uuid") :headers (com-bearer (token (random-uuid) (random-uuid))))]
    (is (= 400 (:status r)))))

(deftest sem-papel-secretario-403
  (let [cap (atom nil)
        r (pt/response-for (service-fn* #{"vereador"} (fake-repo-sessoes :item item-desta-pauta :cap cap))
                           :post (url (random-uuid) (random-uuid)) :headers (com-bearer (token (random-uuid) (random-uuid))))]
    (is (= 403 (:status r)))
    (is (nil? @cap))))

;; ---------- a leitura: GET .../pauta com `em-apreciacao` ----------

(defn- item-ativo [id] {:id id :pauta-sessao-id pauta-id :fase "ordem_do_dia" :tipo-item "leitura"
                        :texto-descricao "Leitura da ata" :ordem 1 :ativo true :lock-version 0})

(deftest pauta-traz-o-item-em-apreciacao-quando-ele-segue-na-pauta
  (let [iid (random-uuid)
        r (pt/response-for (service-fn* #{"secretario"}
                                        (fake-repo-sessoes :itens [(item-ativo iid)]
                                                           :anuncio {:id (random-uuid) :pauta-item-id iid :anunciado-em anunciado-em}))
                           :get (str "/sessoes/" (random-uuid) "/pauta") :headers (com-bearer (token (random-uuid) (random-uuid))))]
    (is (= 200 (:status r)))
    (is (= {:item-id (str iid) :anunciado-em "2026-09-24T12:05:00Z"} (:em-apreciacao (ler-json r))))))

(deftest pauta-sem-em-apreciacao-quando-o-item-anunciado-saiu-da-pauta
  (let [r (pt/response-for (service-fn* #{"secretario"}
                                        (fake-repo-sessoes :itens [(item-ativo (random-uuid))]
                                                           :anuncio {:id (random-uuid) :pauta-item-id (random-uuid) :anunciado-em anunciado-em}))
                           :get (str "/sessoes/" (random-uuid) "/pauta") :headers (com-bearer (token (random-uuid) (random-uuid))))]
    (is (= 200 (:status r)))
    (is (not (contains? (ler-json r) :em-apreciacao)) "item retirado depois de anunciado nao fica 'em apreciacao'")))

(deftest pauta-sai-sem-em-apreciacao-quando-a-leitura-do-anuncio-falha
  ;; Enriquecimento, nao nucleo: API nova contra schema sem `item_anunciado` (o `serve` de producao nao aplica
  ;; migration) — a pauta segue 200, sem o campo, em vez de derrubar a TV, o Comando da Mesa e a Central.
  (let [r (pt/response-for (service-fn* #{"secretario"}
                                        (fake-repo-sessoes :itens [(item-ativo (random-uuid))]
                                                           :anuncio (ex-info "ERROR: relation \"sessoes.item_anunciado\" does not exist" {})))
                           :get (str "/sessoes/" (random-uuid) "/pauta") :headers (com-bearer (token (random-uuid) (random-uuid))))
        body (ler-json r)]
    (is (= 200 (:status r)))
    (is (= 1 (count (:itens body))) "os itens da pauta continuam la'")
    (is (not (contains? body :em-apreciacao)))))
