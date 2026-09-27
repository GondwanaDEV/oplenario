(ns oplenario.normas.normas-catalogo-test
  "INTEGRACAO (PG real): B.5 — o agente consulta as normas da Casa pelo catalogo. `ler_dispositivo` devolve o artigo
  inteiro da versao VIGENTE, com a citacao pronta e a data da conferencia; `buscar_dispositivos` so' devolve o que ainda
  e' da vigente (a IA pode estar um passo atras), e com a IA fora cai nas palavras exatas (R-IA-1)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [com.stuartsierra.component :as component]
            [oplenario.catalogo :as catalogo]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.migracao :as migracao]
            [oplenario.normas.components.repositorio :as repo-normas]
            [oplenario.normas.logic :as logic]))

(def ^:dynamic *ds* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c)] (try (t) (finally (component/stop c)))))))

(defn- repo [] (repo-normas/map->RepoNormasPg {:datasource {:ds *ds*}}))

(defn- publicar! [ente especie titulo texto & {:keys [publicar?] :or {publicar? true}}]
  (let [{:keys [dispositivos alertas]} (logic/dispositivos texto)
        vid (repo-normas/importar-versao! (repo) ente {:camada "casa" :especie especie :titulo titulo}
                                          {:fonte "enviado pela Casa" :texto texto :consolidada-ate (java.time.LocalDate/parse "2026-06-30")
                                           :texto-sha256 (logic/sha256-hex texto) :alertas alertas}
                                          dispositivos)]
    (when publicar? (repo-normas/decidir-versao! (repo) ente vid "publicar" (random-uuid)))
    vid))

(def ^:private ri
  "Art. 1º A Câmara funciona na sede do Município.\nArt. 2º As deliberações exigem maioria absoluta:\nI - para rejeitar o veto;\nII - para aprovar lei complementar.\nParágrafo único. O Presidente vota só para desempatar.\nArt. 3º As sessões são públicas.")

(defn- agente [ente] {:identidade-id (random-uuid) :ente-id ente :papeis #{"vereador"}
                      :via {:agente "assistente-da-casa" :execucao-id (random-uuid) :publico :vereador
                            :classes #{:leitura} :institucional? false}})

(defn- deps [& {:keys [ia]}] (cond-> {:repo-normas (repo)} ia (assoc :buscar-dispositivos-ia ia)))

(deftest ler-o-artigo-inteiro-da-vigente
  (let [ente (random-uuid)
        v1 (publicar! ente "regimento_interno" "Regimento Interno" ri)
        r (catalogo/executar! (deps) (agente ente) "ler_dispositivo" {"especie" "regimento_interno" "endereco" "art2"})]
    (is (= "Regimento Interno, art. 2º" (:citacao r)))
    (is (= ["art2" "art2_cpt_inc1" "art2_cpt_inc2" "art2_par1u"] (mapv :endereco (:dispositivos r)))
        "o artigo com os incisos e o paragrafo, e nada do art. 3º")
    (is (= "2026-06-30" (get-in r [:versao :consolidada-ate])))
    (is (= (str v1) (get-in r [:versao :id])))
    (is (some? (get-in r [:versao :conferida-em])))
    (testing "pelo id da norma tambem"
      (is (= "art. 2º, parágrafo único"
             (:rotulo (last (:dispositivos (catalogo/executar! (deps) (agente ente) "ler_dispositivo"
                                                               {"norma-id" (get-in r [:norma :id]) "endereco" "art2_par1u"})))))))
    (testing "versao em conferencia nao e' lida: vale a vigente"
      (publicar! ente "regimento_interno" "Regimento Interno" "Art. 2º Texto novo ainda nao conferido." :publicar? false)
      (is (= (str v1) (get-in (catalogo/executar! (deps) (agente ente) "ler_dispositivo"
                                                  {"especie" "regimento_interno" "endereco" "art2"})
                              [:versao :id]))))
    (testing "endereco inexistente, norma que a Casa nao tem, e outra Casa: nada"
      (is (nil? (catalogo/executar! (deps) (agente ente) "ler_dispositivo" {"especie" "regimento_interno" "endereco" "art99"})))
      (is (nil? (catalogo/executar! (deps) (agente ente) "ler_dispositivo" {"especie" "lei_organica" "endereco" "art1"})))
      (is (nil? (catalogo/executar! (deps) (agente (random-uuid)) "ler_dispositivo"
                                    {"norma-id" (get-in r [:norma :id]) "endereco" "art2"}))))
    (testing "sem norma-id nem especie e' entrada invalida"
      (is (thrown? clojure.lang.ExceptionInfo
                   (catalogo/executar! (deps) (agente ente) "ler_dispositivo" {"endereco" "art2"}))))))

(deftest buscar-so-devolve-o-que-ainda-vale
  (let [ente (random-uuid)
        v1 (publicar! ente "regimento_interno" "Regimento Interno" ri)
        antiga (random-uuid)
        ia (fn [e consulta limite]
             (is (= [ente "quorum veto" 5] [e consulta limite]) "a Casa vem do ator; o limite padrao e' 5")
             [{:tipo "dispositivo" :meta {:versao-id (str v1) :endereco "art2_cpt_inc1"}}
              {:tipo "dispositivo" :meta {:versao-id (str antiga) :endereco "art2"}}
              {:tipo "dispositivo" :meta {:versao-id (str v1) :endereco "art2_cpt_inc1"}}
              {:tipo "dispositivo" :meta {:versao-id "nao-e-uuid" :endereco "art1"}}])
        r (catalogo/executar! (deps :ia ia) (agente ente) "buscar_dispositivos" {"consulta" "quorum veto"})]
    (is (= "ia" (:modo r)))
    (is (= ["Regimento Interno, art. 2º, I"] (mapv :citacao (:resultados r)))
        "versao que nao vale mais e lixo somem; repetido aparece uma vez")
    (is (= "para rejeitar o veto;" (:texto (first (:resultados r)))))
    (testing "outra Casa nao hidrata o que e' desta"
      (is (= [] (:resultados (catalogo/executar! (deps :ia (fn [_ _ _] [{:meta {:versao-id (str v1) :endereco "art1"}}]))
                                                 (agente (random-uuid)) "buscar_dispositivos" {"consulta" "sede"})))))))

(deftest ia-fora-busca-pelas-palavras
  (let [ente (random-uuid)]
    (publicar! ente "regimento_interno" "Regimento Interno" ri)
    (let [fora (fn [_ _ _] (throw (ex-info "fora" {:tipo :ia/indisponivel})))
          r (catalogo/executar! (deps :ia fora) (agente ente) "buscar_dispositivos" {"consulta" "sessões públicas"})]
      (is (= "literal" (:modo r)))
      (is (re-find #"palavras exatas" (:aviso r)))
      (is (= ["Regimento Interno, art. 3º"] (mapv :citacao (:resultados r)))))
    (testing "sem o seam da IA, tambem literal"
      (is (= "literal" (:modo (catalogo/executar! (deps) (agente ente) "buscar_dispositivos" {"consulta" "desempatar"})))))))
