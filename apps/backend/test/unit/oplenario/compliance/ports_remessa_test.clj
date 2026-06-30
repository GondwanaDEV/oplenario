(ns oplenario.compliance.ports-remessa-test
  "UNIT — F5.3b: os PORTS de saida da remessa (§22.7.8). SerializadorRemessa (D4): renderiza o documento
  intermediario no formato FISICO (1 adapter SIM na V1; generaliza no 2o TCE, S2/disc.6). TransporteRemessa
  (D8): adapter 'download manual' na V1 (operador baixa, sobe no portal, confirma -> assere remessa_enviada;
  API do TCE deferida [GAP]). FontesRemessa (D7): read-ports EM LOTE p/ proveniencia — leitura de N
  registros, NAO predicado booleano (reusa o padrao, nomeia o limite). Layout fisico do SIM = [GAP]: o
  adapter fixture e' ilustrativo, e o teste assere PROPRIEDADES (determinismo, escaping, content-type),
  nao um formato congelado."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [oplenario.compliance.components.fontes :as fontes]
            [oplenario.compliance.components.serializador-remessa :as ser]
            [oplenario.compliance.components.transporte-remessa :as transp]))

(def ^:private descritor
  {:spec-layout-versao "fixture-sim-v0" :sistema "SIM" :content-type "application/xml"
   :cabecalho [{:campo "competencia" :fonte [:contexto "competencia"]}
               {:campo "nome_ente"   :fonte [:relacao "nome_ente"]}]
   :registros {:fonte [:lote "despesas"]
               :colunas [{:campo "data_lancamento" :de "data"} {:campo "valor_total" :de "valor"}]}})

(def ^:private documento
  {:spec-layout-versao "fixture-sim-v0" :sistema "SIM"
   :cabecalho {"competencia" "2099-07" "nome_ente" "Camara Municipal de Fortaleza"}
   :registros [{"data_lancamento" "2099-07-05" "valor_total" "1000,00"}
               {"data_lancamento" "2099-07-09" "valor_total" "250,50"}]})

(defn- ->str [b] (String. ^bytes b "UTF-8"))

;; ---------- SerializadorRemessa fixture (SIM): bytes + content-type ----------

(deftest serializa-devolve-bytes-e-content-type
  (let [s (ser/serializador-sim)
        {b :bytes content-type :content-type} (ser/serializar s descritor documento)]
    (is (bytes? b) "devolve um byte-array")
    (is (= "application/xml" content-type) "o content-type vem do descritor")
    (let [txt (->str b)]
      (is (str/includes? txt "2099-07") "carrega o valor do cabecalho")
      (is (str/includes? txt "Camara Municipal de Fortaleza") "carrega a relacao escalar")
      (is (str/includes? txt "1000,00") "carrega um valor de registro"))))

;; ---------- determinismo: MESMO documento -> MESMOS bytes (hash estavel = integridade) ----------

(deftest serializa-e-deterministico
  (let [s (ser/serializador-sim)]
    (is (java.util.Arrays/equals ^bytes (:bytes (ser/serializar s descritor documento))
                                 ^bytes (:bytes (ser/serializar s descritor documento)))
        "mesma entrada -> mesmos bytes (re-emissao reproduz; hash estavel)")))

;; ---------- escaping: caractere especial de XML nao quebra o artefato ----------

(deftest serializa-escapa-caractere-especial
  (let [s (ser/serializador-sim)
        doc (assoc-in documento [:cabecalho "nome_ente"] "Câmara A & B <test>")
        txt (->str (:bytes (ser/serializar s descritor doc)))]
    (is (str/includes? txt "&amp;") "& e' escapado")
    (is (str/includes? txt "&lt;test&gt;") "< e > sao escapados")
    (is (not (str/includes? txt "A & B")) "nenhum & cru no artefato")))

;; ---------- TransporteRemessa: adapter 'download manual' V1 ----------

(deftest transporte-manual-nao-submete-automatico
  (let [t (transp/transporte-download-manual)
        r (transp/submeter! t {:objeto-store-ref "remessas/x.bin" :sistema "SIM" :competencia "2099-07"})]
    (is (= :manual (:modo r)) "V1 = download manual (sem submissao automatica)")
    (is (= "aguardando_confirmacao_operador" (:status r))
        "o operador baixa, sobe no portal e CONFIRMA o envio (-> assere remessa_enviada)")
    (is (= "remessas/x.bin" (:objeto-store-ref r)) "devolve o ponteiro p/ o operador baixar")))

;; ---------- FontesRemessa fixture: read-port EM LOTE (N registros) ----------

(deftest fontes-fixture-devolve-lote
  (let [f (fontes/fontes-fixture {"despesas" [{"data" "2099-07-05" "valor" "1000,00"}]})
        lote (fontes/buscar-lote f ::tx (random-uuid) "despesas" {"competencia" "2099-07"})]
    (is (= [{"data" "2099-07-05" "valor" "1000,00"}] lote) "devolve os N registros do lote (leitura em lote)")
    (is (= [] (fontes/buscar-lote f ::tx (random-uuid) "inexistente" {}))
        "lote nao configurado no fixture -> vazio")))
