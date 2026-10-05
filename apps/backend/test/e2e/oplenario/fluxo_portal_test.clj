(ns oplenario.fluxo-portal-test
  "E2E da BORDA HTTP do portal (F6c Slice 1, feature 16.5) — a vertical de rota ponta-a-ponta (adapters/in ->
  controller -> repo -> adapters/out -> wire/out). DB-free: RepoTransparencia FAKE (reify), mesmo precedente
  de fluxo_ouvidoria_test. TODA rota e' PUBLICA (sem auth, sem it/globais de erro/authz necessarios) — so'
  prova o contrato de wire (200/404) e o fail-closed do :ente malformado (400)."
  (:require [clojure.test :refer [deftest is]]
            [io.pedestal.http :as ph]
            [io.pedestal.test :as pt]
            [jsonista.core :as json]
            [oplenario.config :as config]
            [oplenario.http :as http]
            [oplenario.interceptors :as it]
            [oplenario.kernel.components.objeto-store :as os]
            [oplenario.transparencia.components.repositorio :as repo-transparencia]
            [oplenario.transparencia.diplomat.http.in :as transparencia-http])
  (:import (java.time Instant)))

(def ^:private t0 (Instant/parse "2026-07-03T12:00:00Z"))

(defn- fake-repo
  [{:keys [buscar-materia listar-materias buscar-norma norma-da-materia listar-normas filtro-capturado
           pagina-capturada artefato-ptr]}]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-transparencia/RepoTransparencia
    (buscar-materia [_ _ente _pid] buscar-materia)
    (listar-materias [_ _ente _excl] listar-materias)
    (buscar-norma [_ _ente _nid] buscar-norma)
    (norma-da-materia [_ _ente _pid] norma-da-materia)
    ;; F6c Slice 3: o filtro do acervo + a pagina (limite/deslocamento). `filtro-capturado` e `pagina-capturada`
    ;; (atoms opcionais) gravam o que a borda coagiu — prova a fiacao query-params -> {:tipo :ano :numero} e
    ;; ?pagina -> [limite deslocamento] sem tocar no banco.
    (listar-normas [_ _ente filtro limite deslocamento]
      (when filtro-capturado (reset! filtro-capturado filtro))
      (when pagina-capturada (reset! pagina-capturada [limite deslocamento]))
      listar-normas)
    ;; F6c Slice 4b: ponteiro do artefato mais recente (a rota de download resolve dai').
    (artefato-mais-recente-da-norma [_ _ente _nid] artefato-ptr)))

(defn- fake-os
  "ObjetoStore FAKE (reify): `blobs` = mapa ref->byte-array; `obter` devolve os bytes ou nil (ausente = a
  ANCORA-antes-do-blob: a linha existe mas o binario nao — condicao de ALERTA da rota, mig 0046/0047)."
  [blobs]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify os/ObjetoStore
    (obter [_ chave] (get blobs chave))))

;; auth no-op só p/ o fragmento de rotas EXPANDIR (as rotas do Slice 1 testadas aqui sao publicas; as do
;; Slice 2, que exigem `auth`, coexistem na tabela e precisam de um interceptor nao-nil no expand). O
;; comportamento de auth em si e' provado em fluxo_acompanhamento_test.
(def ^:private auth-noop {:name ::auth-noop :enter identity})

(defn- service-fn
  ([repo] (service-fn repo (fake-os {})))
  ([repo objeto-store]
   (let [rotas (transparencia-http/rotas {:auth auth-noop :repo-transparencia repo
                                          :resolver-ente-publico transparencia-http/resolver-ente-publico-uuid
                                          ;; seam de EXISTENCIA do ente: as rotas de colecao agora 404 p/ Casa
                                          ;; inexistente (achado B). Aqui o ente da fixture sempre existe — o
                                          ;; 404 tem teste proprio em portal_ente_inexistente_test.clj.
                                          :info-ente (constantly {:nome-oficial "Camara de Teste"})
                                          :objeto-store objeto-store})]
     (-> (http/servico (config/carregar) rotas it/globais)
         ph/create-server ::ph/service-fn))))

(defn- ler-json [r] (json/read-value (:body r) json/keyword-keys-object-mapper))

(def ^:private ente (random-uuid))
(def ^:private pid (random-uuid))
(def ^:private nid (random-uuid))

(def ^:private materia-fixture
  {:proposicao-id pid :tipo "projeto_lei" :ano 2026 :sequencial 1
   :urn-lex "urn:lex:br;ce;fortaleza:projeto.lei:2026;1" :ementa "Dispoe sobre X"
   :autor-tipo "vereador" :autor-texto "Fulano de Tal" :estado "protocolada"})

(def ^:private norma-fixture
  {:norma-id nid :proposicao-id pid :tipo-norma "lei" :numero 1 :ano 2026
   :urn "urn:lex:br;ce;fortaleza:lei:2026-06-28;1" :ementa "Dispoe sobre X"
   :publicado-em t0 :veiculo-publicacao "Diario Oficial do Municipio" :tem-texto true})

;; ---------- GET /portal/casa/:ente/materias ----------

(deftest listar-materias-200
  (let [repo (fake-repo {:listar-materias {:materias [materia-fixture] :materias-total 1}})
        r    (pt/response-for (service-fn repo) :get (str "/portal/casa/" ente "/materias"))
        body (ler-json r)]
    (is (= 200 (:status r)))
    (is (= 1 (count (:materias body))))
    (is (= 1 (:materias-total body)))
    (is (= "Fulano de Tal" (:autor-texto (first (:materias body)))))
    (is (not (contains? (first (:materias body)) :norma)) "listagem NAO embute a norma (so' a ficha)")))

(deftest listar-materias-total-diverge-de-proposito-da-count-da-lista-200
  ;; :materias-total DIVERGE DE PROPOSITO da count da lista (5, nao 1) — mesma disciplina de
  ;; pendencias-total-vem-do-repo-verbatim-200 (paineis): prova que a borda repassa o numero do Repo
  ;; VERBATIM, nunca `(count materias)` (frente 'truncamento-familia', sitio (b)).
  (let [repo (fake-repo {:listar-materias {:materias [materia-fixture] :materias-total 5}})
        r    (pt/response-for (service-fn repo) :get (str "/portal/casa/" ente "/materias"))
        body (ler-json r)]
    (is (= 200 (:status r)))
    (is (= 5 (:materias-total body)) "o total vem do Repo, nao de (count materias)")))

(deftest listar-materias-ente-malformado-400
  (let [repo (fake-repo {:listar-materias {:materias [] :materias-total 0}})
        r    (pt/response-for (service-fn repo) :get "/portal/casa/nao-e-uuid/materias")]
    (is (= 400 (:status r)) "ente malformado -> 400 fail-closed (nunca vaza cross-tenant)")))

;; ---------- GET /portal/casa/:ente/materias/:proposicao_id ----------

(deftest ficha-materia-200-com-norma
  (let [repo (fake-repo {:buscar-materia materia-fixture :norma-da-materia norma-fixture})
        r    (pt/response-for (service-fn repo) :get (str "/portal/casa/" ente "/materias/" pid))
        body (ler-json r)]
    (is (= 200 (:status r)))
    (is (= "protocolada" (:estado body)))
    (is (= "lei" (get-in body [:norma :tipo-norma])) "a ficha liga proposicao -> lei")))

(deftest ficha-materia-sem-norma-200
  (let [repo (fake-repo {:buscar-materia materia-fixture :norma-da-materia nil})
        r    (pt/response-for (service-fn repo) :get (str "/portal/casa/" ente "/materias/" pid))
        body (ler-json r)]
    (is (= 200 (:status r)))
    (is (nil? (:norma body)) "materia ainda nao virou lei -> :norma ausente/nil")))

(deftest ficha-materia-404
  (let [repo (fake-repo {:buscar-materia nil})
        r    (pt/response-for (service-fn repo) :get (str "/portal/casa/" ente "/materias/" pid))]
    (is (= 404 (:status r)))))

;; ---------- GET /portal/casa/:ente/legislacao(/:norma_id) ----------

(deftest listar-normas-200
  (let [cap  (atom :nao-chamado)
        repo (fake-repo {:listar-normas {:normas [norma-fixture] :normas-total 1} :filtro-capturado cap})
        r    (pt/response-for (service-fn repo) :get (str "/portal/casa/" ente "/legislacao"))
        body (ler-json r)]
    (is (= 200 (:status r)))
    (is (= "Diario Oficial do Municipio" (:veiculo-publicacao (first (:normas body)))))
    (is (true? (:tem-texto (first (:normas body)))) "o wire diz se ha texto para baixar")
    (is (= 1 (:normas-total body)))
    (is (= {:tipo nil :ano nil :numero nil} @cap) "sem query-params -> filtro vazio (compat Slice 1)")))

(deftest listar-normas-pagina-repassa-limite-e-deslocamento
  ;; ?pagina=3 com 20 por pagina = as linhas 41..60 (limite 20, deslocamento 40); sem ?pagina e' a primeira. A borda
  ;; devolve pagina/por-pagina ao cliente, junto do total VERBATIM do Repo.
  (let [pag  (atom nil)
        repo (fake-repo {:listar-normas {:normas [] :normas-total 45} :pagina-capturada pag})
        url  (str "/portal/casa/" ente "/legislacao")
        r3   (pt/response-for (service-fn repo) :get (str url "?pagina=3"))
        b3   (ler-json r3)]
    (is (= 200 (:status r3)))
    (is (= [20 40] @pag))
    (is (= [45 3 20] ((juxt :normas-total :pagina :por-pagina) b3)))
    (let [r1 (pt/response-for (service-fn repo) :get url)]
      (is (= [20 0] @pag) "sem ?pagina: a primeira")
      (is (= [1 20] ((juxt :pagina :por-pagina) (ler-json r1)))))))

(deftest listar-normas-pagina-invalida-400
  (let [repo (fake-repo {:listar-normas {:normas [] :normas-total 0}})]
    (is (= [400 400 400]
           (mapv #(:status (pt/response-for (service-fn repo) :get (str "/portal/casa/" ente "/legislacao" %)))
                 ["?pagina=0" "?pagina=abc" "?pagina=1&pagina=2"]))
        "pagina invalida e' 400, nunca vira a pagina 1 em silencio")))

(deftest listar-normas-total-diverge-de-proposito-da-count-da-lista-200
  ;; mesma disciplina de listar-materias-total-diverge-de-proposito: a borda repassa o numero do Repo
  ;; VERBATIM, nunca `(count normas)`.
  (let [repo (fake-repo {:listar-normas {:normas [norma-fixture] :normas-total 7}})
        r    (pt/response-for (service-fn repo) :get (str "/portal/casa/" ente "/legislacao"))
        body (ler-json r)]
    (is (= 7 (:normas-total body)) "o total vem do Repo, nao de (count normas)")))

;; F6c Slice 3: navegacao do acervo por query-params (especie/ano/numero)
(deftest listar-normas-repassa-filtro-coagido
  (let [cap  (atom nil)
        repo (fake-repo {:listar-normas {:normas [norma-fixture] :normas-total 1} :filtro-capturado cap})
        r    (pt/response-for (service-fn repo) :get
               (str "/portal/casa/" ente "/legislacao?tipo=lei&ano=2026&numero=1"))]
    (is (= 200 (:status r)))
    (is (= {:tipo "lei" :ano 2026 :numero 1} @cap)
        "query-params coagidos na borda: ano/numero viram Long; tipo string")))

(deftest listar-normas-ano-malformado-400
  (let [repo (fake-repo {:listar-normas {:normas [] :normas-total 0}})
        r    (pt/response-for (service-fn repo) :get (str "/portal/casa/" ente "/legislacao?ano=abc"))]
    (is (= 400 (:status r)) "ano nao-inteiro -> 400 fail-closed (nunca 500)")))

;; review clojure MAJOR: param repetido na URL -> Pedestal entrega VETOR -> antes: ClassCastException -> 500.
(deftest listar-normas-param-repetido-400
  (let [repo (fake-repo {:listar-normas {:normas [] :normas-total 0}})
        r    (pt/response-for (service-fn repo) :get (str "/portal/casa/" ente "/legislacao?ano=1&ano=2"))]
    (is (= 400 (:status r)) "query-param repetido -> 400 fail-closed, NUNCA 500")))

(deftest buscar-norma-200
  (let [repo (fake-repo {:buscar-norma norma-fixture})
        r    (pt/response-for (service-fn repo) :get (str "/portal/casa/" ente "/legislacao/" nid))
        body (ler-json r)]
    (is (= 200 (:status r)))
    (is (= "urn:lex:br;ce;fortaleza:lei:2026-06-28;1" (:urn body)))))

(deftest buscar-norma-404
  (let [repo (fake-repo {:buscar-norma nil})
        r    (pt/response-for (service-fn repo) :get (str "/portal/casa/" ente "/legislacao/" nid))]
    (is (= 404 (:status r)))))

;; ---------- GET /portal/casa/:ente/legislacao/:norma_id/artefato (download BINARIO, Slice 4b) ----------

(def ^:private artefato-ref "publicacoes/ref.bin")
(def ^:private artefato-ptr-fixture
  {:norma-id nid :artefato-id (random-uuid) :versao 1 :objeto-store-ref artefato-ref
   :content-type "text/plain; charset=utf-8" :hash "sha256:abc" :assinatura-algoritmo "STUB-ICP-v0"
   :assinado false})

(deftest baixar-artefato-200-binario
  (let [repo (fake-repo {:artefato-ptr artefato-ptr-fixture})
        os   (fake-os {artefato-ref (.getBytes "LEI N. 1 ... conteudo oficial" "UTF-8")})
        r    (pt/response-for (service-fn repo os) :get
               (str "/portal/casa/" ente "/legislacao/" nid "/artefato"))]
    (is (= 200 (:status r)))
    (is (= "text/plain; charset=utf-8" (get (:headers r) "Content-Type")) "Content-Type do artefato")
    (is (re-find #"attachment" (get (:headers r) "Content-Disposition" "")) "download como anexo")
    (is (= "LEI N. 1 ... conteudo oficial" (:body r)) "serve o binario do objeto_store")))

(deftest baixar-artefato-sem-ponteiro-404
  (let [repo (fake-repo {:artefato-ptr nil})
        r    (pt/response-for (service-fn repo (fake-os {})) :get
               (str "/portal/casa/" ente "/legislacao/" nid "/artefato"))]
    (is (= 404 (:status r)) "norma sem artefato gerado -> 404")))

;; CARRY 4a/mig 0046: ancora-antes-do-blob. Ponteiro EXISTE mas o objeto_store nao tem o binario (S3 falhou
;; pos-commit). NUNCA 404 silencioso nem "documento oficial" confiavel — e' ALERTA (500).
(deftest baixar-artefato-ponteiro-sem-blob-500-alerta
  (let [repo (fake-repo {:artefato-ptr artefato-ptr-fixture})
        os   (fake-os {})                          ; ponteiro resolve, mas o ref nao tem blob -> obter=nil
        r    (pt/response-for (service-fn repo os) :get
               (str "/portal/casa/" ente "/legislacao/" nid "/artefato"))]
    (is (= 500 (:status r)) "ponteiro sem blob = ALERTA (500), NUNCA 404 silencioso")))

(deftest baixar-artefato-ente-malformado-400
  (let [repo (fake-repo {:artefato-ptr artefato-ptr-fixture})
        r    (pt/response-for (service-fn repo (fake-os {})) :get
               (str "/portal/casa/nao-e-uuid/legislacao/" nid "/artefato"))]
    (is (= 400 (:status r)) "ente malformado -> 400 fail-closed")))
