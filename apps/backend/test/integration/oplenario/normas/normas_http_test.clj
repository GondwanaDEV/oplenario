(ns oplenario.normas.normas-http-test
  "INTEGRACAO (PG real + borda HTTP): B.4 / ADR-0011 — a curadoria de uma norma de referencia. A secretaria importa o
  texto; o parser quebra em dispositivos e aponta o que conferir; nada vale ate' uma pessoa publicar; publicar de novo
  guarda a anterior; outra Casa nao ve nada; a norma federal (sem Casa) e' visivel a todas."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [com.stuartsierra.component :as component]
            [io.pedestal.http :as ph]
            [io.pedestal.test :as pt]
            [jsonista.core :as json]
            [next.jdbc :as jdbc]
            [oplenario.config :as config]
            [oplenario.http :as http]
            [oplenario.identidade.components.repositorio :as repo-id]
            [oplenario.interceptors :as it]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.components.idp-dev :as idp-dev]
            [oplenario.migracao :as migracao]
            [oplenario.normas.components.repositorio :as repo-normas]
            [oplenario.normas.diplomat.http.in :as normas-http]))

(def ^:dynamic *ds* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c)] (try (t) (finally (component/stop c)))))))

(defn- fake-identidade [papeis]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-id/RepoIdentidade
    (snapshot-ator [_ _ _] {:vinculo-ativo {:id (random-uuid) :tipo "servidor"} :papeis papeis})))

(defn- servico [& {:keys [papeis] :or {papeis #{"secretario"}}}]
  (-> (http/servico (config/carregar)
                    (normas-http/rotas {:auth (it/autenticacao (idp-dev/idp-dev) (fake-identidade papeis))
                                        :repo-normas (repo-normas/map->RepoNormasPg {:datasource {:ds *ds*}})
                                        :municipio-do-ente (constantly "2301901")})
                    it/globais)
      ph/create-server ::ph/service-fn))

(def ^:private pessoa (random-uuid))

(defn- pedir [svc metodo caminho ente & [corpo]]
  (let [r (pt/response-for svc metodo caminho
                           :headers (cond-> {"Authorization" (str "Bearer " (json/write-value-as-string
                                                                             {:sub "u" :ente-id (str ente)
                                                                              :identidade-id (str pessoa)}))}
                                      corpo (assoc "Content-Type" "application/json"))
                           :body (when corpo (json/write-value-as-string corpo)))]
    {:status (:status r) :corpo (when (seq (:body r)) (json/read-value (:body r) json/keyword-keys-object-mapper))}))

(def ^:private ri-v1
  "REGIMENTO INTERNO\n\nArt. 1º A Câmara funciona na sede do Município.\nArt. 2º As sessões são públicas.\nParágrafo único. A sessão secreta depende de deliberação do Plenário.\nArt. 4º O tempo da tribuna é de 10 minutos.")

(def ^:private importacao
  {:especie "regimento_interno" :titulo "Regimento Interno da Câmara" :fonte "enviado pela Casa"
   :consolidada-ate "2026-06-30" :texto ri-v1})

(deftest curadoria-de-ponta-a-ponta
  (let [ente (random-uuid)
        svc (servico)
        {:keys [status corpo]} (pedir svc :post "/normas/versoes" ente importacao)
        vid (get-in corpo [:versao :id])]
    (testing "importar: dispositivos com endereco e rotulo, e o que conferir"
      (is (= 201 status))
      (is (= "em_conferencia" (get-in corpo [:versao :estado])))
      (is (= "casa" (get-in corpo [:norma :camada])) "o Regimento e' da Casa")
      (is (= ["preambulo" "art1" "art2" "art2_par1u" "art4"] (mapv :endereco (:dispositivos corpo))))
      (is (= "art. 2º, parágrafo único" (:rotulo (nth (:dispositivos corpo) 3))))
      (is (some #(re-find #"art\. 4º" %) (:alertas corpo)) "o salto do 2 para o 4 vai para quem confere"))
    (testing "uma versao por vez esperando conferencia"
      (is (= 409 (:status (pedir svc :post "/normas/versoes" ente importacao)))))
    (testing "na lista, esperando conferencia e ainda sem vigente"
      (let [[n] (get-in (pedir svc :get "/normas" ente) [:corpo :normas])]
        (is (= vid (get-in n [:em-conferencia :id])))
        (is (nil? (:vigente n)))))
    (testing "publicar: vira vigente, uma vez so'"
      (let [r (pedir svc :post (str "/normas/versoes/" vid "/conferencia") ente {:decisao "publicar"})]
        (is (= 200 (:status r)))
        (is (= "vigente" (get-in r [:corpo :versao :estado]))))
      (is (= 409 (:status (pedir svc :post (str "/normas/versoes/" vid "/conferencia") ente {:decisao "descartar"})))))
    (testing "nova versao do texto: a anterior fica guardada como substituida"
      (let [v2 (get-in (pedir svc :post "/normas/versoes" ente (assoc importacao :texto (str ri-v1 "\nArt. 5º Novo.")))
                       [:corpo :versao :id])]
        (pedir svc :post (str "/normas/versoes/" v2 "/conferencia") ente {:decisao "publicar"})
        (is (= "substituida" (get-in (pedir svc :get (str "/normas/versoes/" vid) ente) [:corpo :versao :estado])))
        (let [[n] (get-in (pedir svc :get "/normas" ente) [:corpo :normas])]
          (is (= v2 (get-in n [:vigente :id])))
          (is (nil? (:em-conferencia n))))))
    (testing "descartar"
      (let [v3 (get-in (pedir svc :post "/normas/versoes" ente importacao) [:corpo :versao :id])]
        (is (= "descartada" (get-in (pedir svc :post (str "/normas/versoes/" v3 "/conferencia") ente
                                           {:decisao "descartar"})
                                    [:corpo :versao :estado])))))
    (testing "outra Casa nao ve nada disto"
      (let [outra (random-uuid)]
        (is (not-any? #(get-in % [:norma :da-casa]) (get-in (pedir svc :get "/normas" outra) [:corpo :normas]))
            "so' as de referencia (federal/estadual) aparecem para ela")
        (is (= 404 (:status (pedir svc :get (str "/normas/versoes/" vid) outra))))
        (is (= 404 (:status (pedir svc :post (str "/normas/versoes/" vid "/conferencia") outra {:decisao "publicar"}))))))))

(deftest a-lom-e-do-municipio
  (let [r (pedir (servico) :post "/normas/versoes" (random-uuid)
                 {:especie "lei_organica" :titulo "Lei Orgânica" :fonte "https://exemplo" :numero "ignorado"
                  :texto "Art. 1º O Município integra a República."})]
    (is (= 201 (:status r)))
    (is (= "municipal" (get-in r [:corpo :norma :camada])))
    (is (nil? (get-in r [:corpo :norma :numero])) "LOM nao tem numero: e' uma so' na Casa")))

(deftest a-norma-federal-e-de-todas-as-casas
  (let [nid (random-uuid) vid (random-uuid)]
    (jdbc/execute! *ds* ["INSERT INTO normas.norma (id, camada, especie, numero, titulo) VALUES (?, 'federal', 'lei_complementar', ?, 'LC de teste')" nid (str (random-uuid))])
    (jdbc/execute! *ds* ["INSERT INTO normas.versao (id, norma_id, estado, fonte, texto, texto_sha256, n_dispositivos, decidida_em)
                          VALUES (?, ?, 'vigente', 'planalto', 'Art. 1º x', 'h', 1, now())" vid nid])
    (let [normas (get-in (pedir (servico) :get "/normas" (random-uuid)) [:corpo :normas])
          lc (first (filter #(= (str nid) (get-in % [:norma :id])) normas))]
      (is (some? lc))
      (is (false? (get-in lc [:norma :da-casa]))))))

(deftest borda
  (let [ente (random-uuid)]
    (testing "so' a secretaria cura"
      (is (= 403 (:status (pedir (servico :papeis #{"vereador"}) :get "/normas" ente)))))
    (testing "entrada invalida"
      (is (= 400 (:status (pedir (servico) :post "/normas/versoes" ente (dissoc importacao :fonte)))))
      (is (= 400 (:status (pedir (servico) :post "/normas/versoes" ente (assoc importacao :especie "constituicao_federal"))))
          "a Casa nao importa norma federal: e' curadoria do produto")
      (is (= 400 (:status (pedir (servico) :post "/normas/versoes" ente (assoc importacao :data "30/06/2026"))))))
    (testing "um Regimento grande cabe (o teto padrao da borda e' 256 KiB)"
      (let [grande (apply str "REGIMENTO\n" (for [i (range 1 3001)] (str "Art. " i ". " (apply str (repeat 150 "x")) "\n")))]
        (is (> (count grande) (* 256 1024)))
        (is (= 201 (:status (pedir (servico) :post "/normas/versoes" ente (assoc importacao :texto grande)))))))))
