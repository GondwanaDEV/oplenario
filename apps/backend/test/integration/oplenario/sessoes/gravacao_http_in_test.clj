(ns oplenario.sessoes.gravacao-http-in-test
  "Slice F4 — eixo D (§22.6) wire/W3: a borda HTTP de INGESTAO de gravacao. O utilitario CLI/watch-folder
  (§22.3.4) faz upload do container bruto (octet-stream); o servidor o transmite ao objeto_store, computa o
  sha256 NO FLUXO (DigestInputStream, sem bufferizar heap), registra o `gravacao_segmento` e emite
  `gravacao.segmento-captado` (fronteira core->IA) — tudo na MESMA tx do Repo (atomicidade outbox). O segmento
  pode chegar SEM sessao (Opcao A: vincula depois) ou com `sessao-id` (link-at-ingest). Metadata via query
  params (o corpo e' o binario, nao passa pelo corpo-json). DB-free: RepoSessoes FAKE + ObjetoStore FAKE +
  idp-dev real — espelha o votacao-http-in-test (W3)."
  (:require [clojure.test :refer [deftest is]]
            [io.pedestal.http :as ph]
            [io.pedestal.test :as pt]
            [jsonista.core :as json]
            [oplenario.config :as config]
            [oplenario.http :as http]
            [oplenario.identidade.components.repositorio :as repo-id]
            [oplenario.interceptors :as it]
            [oplenario.kernel.components.idp-dev :as idp-dev]
            [oplenario.kernel.components.objeto-store :as store]
            [oplenario.rotas :as rotas]
            [oplenario.sessoes.components.repositorio :as repo-sessoes])
  (:import (java.security MessageDigest)))

(defn- sessao-canonica
  "Sessao como buscar-sessao devolve (kebab) — so o que a authz fina (pode-ver-sessao? = mesma Casa) le."
  [ente-id id]
  {:id id :ente-id ente-id :estado "aberta" :tipo-sessao "ordinaria"})

(defn- fake-repo-sessoes
  "RepoSessoes fake (parcial): `buscar-sessao` resolve a sessao (p/ a authz do link); `registrar-segmento!`
  ECOA {:id} e GRAVA o mapa recebido em `capturado` — p/ provar uri/hash/sessao-id/created-by passados ao Repo."
  [busca-fn capturado]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-sessoes/RepoSessoes
    (buscar-sessao [_ ente-id id] (busca-fn ente-id id))
    (registrar-segmento! [_ ente-id m]
      (reset! capturado (assoc m :ente-id ente-id))
      {:id (:id m) :lock-version 0})
    (listar-gravacoes-pendentes [_ ente-id limite]
      (reset! capturado {:ente-id ente-id :limite limite})
      {:segmentos [{:id #uuid "00000000-0000-0000-0000-0000000000a1" :iniciou-em (java.time.Instant/parse "2026-09-22T17:50:00Z")
                    :fonte-ingestao "gravacao_local_pos_sessao" :acesso-restrito false :audio-hash "ab" :lock-version 0
                    :container-bruto-uri "gravacao/x/y"}
                   {:id #uuid "00000000-0000-0000-0000-0000000000a2" :iniciou-em (java.time.Instant/parse "2026-09-10T10:00:00Z")
                    :fonte-ingestao "gravacao_local_pos_sessao" :acesso-restrito false :audio-hash "cd" :lock-version 3}]
       :sessoes [{:id #uuid "00000000-0000-0000-0000-0000000000b1" :tipo-sessao "ordinaria" :numero-sequencial 12
                  :estado "encerrada" :aberta-em (java.time.Instant/parse "2026-09-22T18:00:00Z")
                  :encerrada-em (java.time.Instant/parse "2026-09-22T21:00:00Z")}]})))

(defn- fake-store
  "ObjetoStore fake: `guardar-stream!` CONSOME o InputStream (popula o DigestInputStream) e grava os bytes +
  a chave em `capturado`; devolve a chave (contrato do store real)."
  [capturado]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify store/ObjetoStore
    (guardar-stream! [_ chave in content-type]
      (let [bytes (.readAllBytes ^java.io.InputStream in)]
        (reset! capturado {:chave chave :bytes bytes :content-type content-type})
        chave))))

(defn- fake-repo-identidade [papeis]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-id/RepoIdentidade
    (snapshot-ator [_ _ente-id _identidade-id]
      {:vinculo-ativo {:id (random-uuid) :tipo "servidor"} :papeis papeis})))

(defn- service-fn*
  [papeis repo-s obj-store]
  (-> (http/servico (config/carregar)
                    (rotas/montar {:idp (idp-dev/idp-dev)
                                   :repo-identidade (fake-repo-identidade papeis)
                                   :repo-sessoes repo-s
                                   :objeto-store obj-store})
                    it/globais)
      ph/create-server ::ph/service-fn))

(defn- token [ente-id ident-id]
  (json/write-value-as-string {:sub "u" :ente-id (str ente-id) :identidade-id (str ident-id)}))

(defn- com-bin [tok] {"authorization" (str "Bearer " tok) "Content-Type" "application/octet-stream"})
(defn- ler-json [r] (json/read-value (:body r) json/keyword-keys-object-mapper))

(defn- sha256-hex [^String s]
  (let [md (MessageDigest/getInstance "SHA-256")
        b  (.digest md (.getBytes s "UTF-8"))]
    ;; bit-and 0xff: SEM ele a sign-extension do byte negativo daria hex de 16 chars (mesmo bug do servidor)
    (apply str (map #(format "%02x" (bit-and % 0xff)) b))))

(def ^:private meta-ok
  "Query string minima valida: fonte + motivo-inicio + iniciou-em (os obrigatorios)."
  "?fonte-ingestao=gravacao_local_pos_sessao&motivo-inicio=inicio_sessao&iniciou-em=2026-06-30T12:00:00Z")

;; ---------- POST /gravacoes — ingestao (Opcao A: sem sessao) ----------

(deftest ingestao-201-sem-sessao
  (let [ente (random-uuid)
        cap-repo (atom nil) cap-store (atom nil)
        repo-s (fake-repo-sessoes (fn [_ _] nil) cap-repo)
        corpo "conteudo-bruto-do-container"
        r (pt/response-for (service-fn* #{"secretario"} repo-s (fake-store cap-store))
                           :post (str "/gravacoes" meta-ok)
                           :headers (com-bin (token ente (random-uuid))) :body corpo)
        body (ler-json r)]
    (is (= 201 (:status r)) "ingestao com papel + metadata valida + binario -> 201 (Opcao A: sem sessao-id)")
    (is (string? (:id body)) "recibo carrega o id do segmento (string)")
    (is (= (sha256-hex corpo) (:audio-hash body)) "recibo carrega o sha256 do conteudo (integridade/dedup §22.3.4)")
    (is (= 64 (count (:audio-hash body))) "sha256 hex = 64 chars (trava o bug de sign-extension do byte)")
    (is (= 0 (:lock-version body))
        "ledger de prontidao Fase 8 achado #2: um segmento nao-vinculado nunca aparece em GET .../gravacao -- este recibo e' a UNICA fonte do lock-version que POST .../vincular exige no corpo")
    (is (= corpo (String. ^bytes (:bytes @cap-store) "UTF-8")) "o store recebeu os bytes do container, integros")
    (is (= "gravacao_local_pos_sessao" (:fonte-ingestao @cap-repo)) "o Repo registrou a fonte de ingestao")
    (is (= (:chave @cap-store) (:container-bruto-uri @cap-repo)) "o container-bruto-uri registrado = a chave do store")
    (is (= (sha256-hex corpo) (:audio-hash @cap-repo)) "o Repo registrou o hash computado no fluxo")
    (is (nil? (:sessao-id @cap-repo)) "Opcao A: segmento nasce sem vinculo de sessao")))

(deftest ingestao-201-com-sessao
  ;; link-at-ingest: sessao-id na query, sessao existe na mesma Casa -> 201 + segmento ja vinculado.
  (let [ente (random-uuid) sid (random-uuid)
        cap-repo (atom nil)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) cap-repo)
        r (pt/response-for (service-fn* #{"secretario"} repo-s (fake-store (atom nil)))
                           :post (str "/gravacoes" meta-ok "&sessao-id=" sid)
                           :headers (com-bin (token ente (random-uuid))) :body "x")]
    (is (= 201 (:status r)) "ingestao vinculada a sessao da mesma Casa -> 201")
    (is (= sid (:sessao-id @cap-repo)) "o segmento foi registrado vinculado a sessao (uuid coagido na borda)")))

(deftest ingestao-sessao-secreta-forca-acesso-restrito
  ;; sigilo §22.6 (review sec CRÍTICO): ainda que o cliente envie acesso-restrito=false, vincular a uma sessao
  ;; SECRETA tem de gravar acesso-restrito=true (o flag viaja ao pipeline de IA respeitar o sigilo).
  (let [ente (random-uuid) sid (random-uuid)
        cap-repo (atom nil)
        repo-s (fake-repo-sessoes (fn [_ id] (assoc (sessao-canonica ente id) :tipo-sessao "secreta")) cap-repo)
        r (pt/response-for (service-fn* #{"secretario"} repo-s (fake-store (atom nil)))
                           :post (str "/gravacoes" meta-ok "&sessao-id=" sid "&acesso-restrito=false")
                           :headers (com-bin (token ente (random-uuid))) :body "x")]
    (is (= 201 (:status r)) "ingestao vinculada a sessao secreta -> 201")
    (is (true? (:acesso-restrito @cap-repo))
        "sessao secreta + cliente mandou false -> servidor FORCA acesso-restrito=true (sigilo)")))

;; NB: a guarda de corpo-nulo -> 400 (controllers/diplomat) fica como defesa, mas o Jetty/pt sempre fornece um
;; ServletInputStream (mesmo vazio), entao o caso nulo nao e' exercitavel por pt/response-for — sem teste aqui.

(deftest ingestao-papel-captacao-201
  ;; Faixa A / A.2: o utilitario de captacao no PC do OBS usa uma credencial de papel `captacao` — o MINIMO para
  ;; enviar arquivos. Nao precisa (nem deve ter) os poderes da secretaria.
  (let [ente (random-uuid)
        cap-repo (atom nil)
        repo-s (fake-repo-sessoes (fn [_ _] nil) cap-repo)
        r (pt/response-for (service-fn* #{"captacao"} repo-s (fake-store (atom nil)))
                           :post (str "/gravacoes" meta-ok)
                           :headers (com-bin (token ente (random-uuid))) :body "x")]
    (is (= 201 (:status r)) "papel captacao envia a gravacao")
    (is (nil? (:sessao-id @cap-repo)) "sem sessao: a secretaria vincula depois")))

(deftest ingestao-sessao-nao-realizada-409
  (let [ente (random-uuid)
        cap-repo (atom nil) cap-store (atom nil)
        repo-s (fake-repo-sessoes (fn [_ id] (assoc (sessao-canonica ente id) :estado "nao_realizada")) cap-repo)
        r (pt/response-for (service-fn* #{"secretario"} repo-s (fake-store cap-store))
                           :post (str "/gravacoes" meta-ok "&sessao-id=" (random-uuid))
                           :headers (com-bin (token ente (random-uuid))) :body "x")]
    (is (= 409 (:status r)) "sessao que nao aconteceu nao recebe gravacao")
    (is (nil? @cap-store) "o arquivo nem chegou ao object store")
    (is (nil? @cap-repo) "nada registrado")))

(deftest ingestao-sessao-inexistente-404
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ _] nil) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s (fake-store (atom nil)))
                           :post (str "/gravacoes" meta-ok "&sessao-id=" (random-uuid))
                           :headers (com-bin (token ente (random-uuid))) :body "x")]
    (is (= 404 (:status r)) "sessao-id informado mas inexistente no tenant -> 404")))

(deftest ingestao-sessao-casa-alheia-403
  ;; buscar-sessao devolve sessao de OUTRA Casa (escapou da RLS por bug): pode-ver-sessao? (mesma Casa) NEGA.
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica (random-uuid) id)) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s (fake-store (atom nil)))
                           :post (str "/gravacoes" meta-ok "&sessao-id=" (random-uuid))
                           :headers (com-bin (token ente (random-uuid))) :body "x")]
    (is (= 403 (:status r)) "sessao de ente alheio -> policy.check (pode-ver-sessao?) nega -> 403")))

(deftest ingestao-sem-papel-403
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ _] nil) (atom nil))
        r (pt/response-for (service-fn* #{"vereador"} repo-s (fake-store (atom nil)))
                           :post (str "/gravacoes" meta-ok)
                           :headers (com-bin (token ente (random-uuid))) :body "x")]
    (is (= 403 (:status r)) "ator sem papel 'secretario' -> authz grossa nega -> 403")))

(deftest ingestao-sem-token-401
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ _] nil) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s (fake-store (atom nil)))
                           :post (str "/gravacoes" meta-ok)
                           :headers {"Content-Type" "application/octet-stream"} :body "x")]
    (is (= 401 (:status r)) "rota herda a cadeia de auth: sem token -> 401 (fail-closed)")
    (is (= ente ente))))

(deftest ingestao-metadata-incompleta-400
  ;; falta motivo-inicio + iniciou-em -> a borda (adapters/in) barra com 400, nunca 500.
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ _] nil) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s (fake-store (atom nil)))
                           :post "/gravacoes?fonte-ingestao=gravacao_local_pos_sessao"
                           :headers (com-bin (token ente (random-uuid))) :body "x")]
    (is (= 400 (:status r)) "metadata sem os obrigatorios -> 400 (validacao na borda)")))

(deftest ingestao-fonte-invalida-400
  ;; fonte fora do allowlist -> 400 na borda (nao 500 do CHECK do banco).
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ _] nil) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s (fake-store (atom nil)))
                           :post "/gravacoes?fonte-ingestao=fonte-pirata&motivo-inicio=inicio_sessao&iniciou-em=2026-06-30T12:00:00Z"
                           :headers (com-bin (token ente (random-uuid))) :body "x")]
    (is (= 400 (:status r)) "fonte-ingestao desconhecida -> 400 (enum fail-closed na borda)")))

(deftest ingestao-sessao-id-malformado-400
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ _] nil) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s (fake-store (atom nil)))
                           :post (str "/gravacoes" meta-ok "&sessao-id=nao-e-uuid")
                           :headers (com-bin (token ente (random-uuid))) :body "x")]
    (is (= 400 (:status r)) "sessao-id malformado na query -> 400, nunca 500")))

;; ---------- GET /sessoes/:id/gravacao — read-model do painel ----------

(deftest listar-gravacoes-200
  (let [ente (random-uuid) sid (random-uuid)
        seg {:id (random-uuid) :sessao-id sid :iniciou-em (java.time.Instant/parse "2026-06-30T12:00:00Z")
             :encerrou-em nil :motivo-inicio "inicio_sessao" :motivo-fim nil
             :fonte-ingestao "gravacao_local_pos_sessao" :audio-uri nil :acesso-restrito false}
        repo-s #_{:clj-kondo/ignore [:missing-protocol-method]}
               (reify repo-sessoes/RepoSessoes
                 (buscar-sessao [_ e id] (sessao-canonica e id))
                 (listar-segmentos-da-sessao [_ _ _] [seg]))
        r (pt/response-for (service-fn* #{"secretario"} repo-s (fake-store (atom nil)))
                           :get (str "/sessoes/" sid "/gravacao")
                           :headers {"authorization" (str "Bearer " (token ente (random-uuid)))})
        body (ler-json r)]
    (is (= 200 (:status r)) "listar gravacoes da sessao -> 200")
    (is (= 1 (count (:segmentos body))) "projeta os segmentos vinculados")
    (is (= "gravacao_local_pos_sessao" (-> body :segmentos first :fonte-ingestao)))
    (is (not (contains? (-> body :segmentos first) :container-bruto-uri))
        "NAO vaza a chave interna do store (container-bruto-uri) no read-model")))

(deftest listar-gravacoes-sessao-inexistente-404
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ _] nil) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s (fake-store (atom nil)))
                           :get (str "/sessoes/" (random-uuid) "/gravacao")
                           :headers {"authorization" (str "Bearer " (token ente (random-uuid)))})]
    (is (= 404 (:status r)) "listar de sessao inexistente -> 404")))

;; ---------- GET /gravacoes/pendentes — Faixa A / A.2 ----------

(deftest pendentes-200-com-sugestao
  (let [ente (random-uuid) cap (atom nil)
        r (pt/response-for (service-fn* #{"secretario"} (fake-repo-sessoes (fn [_ _] nil) cap) nil)
                           :get "/gravacoes/pendentes"
                           :headers {"authorization" (str "Bearer " (token ente (random-uuid)))})
        [a b] (:segmentos (ler-json r))]
    (is (= 200 (:status r)))
    (is (= ente (:ente-id @cap)) "o tenant vem do ator")
    (is (= "00000000-0000-0000-0000-0000000000b1" (get-in a [:sugestao :sessao-id]))
        "a gravacao das 17:50 casa com a sessao das 18:00")
    (is (= {:tipo-sessao "ordinaria" :numero-sequencial 12 :estado "encerrada" :inicio "2026-09-22T18:00:00Z"}
           (dissoc (:sugestao a) :sessao-id)))
    (is (= 0 (:lock-version a)) "o token de CAS que o vinculo exige viaja")
    (is (nil? (:container-bruto-uri a)) "a chave do store nao vaza")
    (is (nil? (:sugestao b)) "fora de qualquer janela: sem sugestao")
    (is (= 3 (:lock-version b)))))

(deftest pendentes-so-secretaria
  (doseq [papel ["captacao" "vereador"]]
    (let [r (pt/response-for (service-fn* #{papel} (fake-repo-sessoes (fn [_ _] nil) (atom nil)) nil)
                             :get "/gravacoes/pendentes"
                             :headers {"authorization" (str "Bearer " (token (random-uuid) (random-uuid)))})]
      (is (= 403 (:status r)) (str papel " nao ve a fila de gravacoes")))))
