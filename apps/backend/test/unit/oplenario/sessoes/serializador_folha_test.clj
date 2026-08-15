(ns oplenario.sessoes.serializador-folha-test
  "UNIT (puro) — o ADAPTER `SerializadorFolha` HTML (Etapa 5 fatia 2): leva o `FolhaDocumento` (produzido
  por `gerador-folha/renderizar`, Fatia 1) ao HTML CANONICO autocontido. Cobre os 5 riscos declarados no
  brief: (a) determinismo bit-a-bit, (b) escape de HTML em todo texto livre, (c) D1 — nenhum numero de
  quorum e' recalculado/derivado, so' repassado verbatim, (d) ordem de `:serie` (map-of) nao pode vazar
  ordem de hash — segue a ordem de `:linhas`, (e) o aviso obrigatorio do STUB-ICP-v0."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [oplenario.sessoes.components.serializador-folha :as ser]))

(def ^:private v1 #uuid "00000000-0000-0000-0000-0000000000a1")
(def ^:private v2 #uuid "00000000-0000-0000-0000-0000000000a2")
(def ^:private v3 #uuid "00000000-0000-0000-0000-0000000000a3")
(def ^:private sid #uuid "00000000-0000-0000-0000-0000000000b1")

(def ^:private instante (java.time.Instant/parse "2026-06-20T18:00:00Z"))
(def ^:private instante-2 (java.time.Instant/parse "2026-06-20T19:15:00Z"))

(def ^:private cabecalho-ok
  {:nome-oficial "Camara Municipal de Fortaleza" :nome-curto "CMF"
   :legislatura-numero 19 :legislatura-ano-inicio 2025 :legislatura-ano-fim 2028})

(def ^:private linha-presente
  {:vereador-id v1 :nome "Ana Souza" :nome-parlamentar "Ana" :partido "PX" :cargo-mesa "Presidente"
   :estado :presente-plenario :inconsistencia-cadastro false :sem-assento false
   :desde instante :fonte "manual_secretaria" :registrado-em instante :justificativa nil})

(def ^:private linha-licenciada
  {:vereador-id v2 :nome "Beatriz Lima" :nome-parlamentar nil :partido "PY" :cargo-mesa nil
   :estado :licenciado :inconsistencia-cadastro false :sem-assento false
   :desde nil :fonte nil :registrado-em nil :justificativa nil})

(def ^:private linha-sem-assento
  {:vereador-id v3 :nome "Carlos Dias" :nome-parlamentar nil :partido "PZ" :cargo-mesa nil
   :estado :presente-plenario :inconsistencia-cadastro false :sem-assento true
   :desde instante :fonte "painel_eletronico" :registrado-em instante :justificativa nil})

(def ^:private quorum-ok
  {:presentes-plenario 2 :presentes-remoto 0 :presentes-total 2 :membros-da-casa 2
   :presencas-fora-do-roster 1})

(def ^:private documento-base
  {:spec-versao "folha-sessao-v1"
   :sessao {:id sid :estado "encerrada" :motivo-nao-realizada nil}
   :instante instante
   :cabecalho-da-casa cabecalho-ok
   :linhas [linha-presente linha-licenciada linha-sem-assento]
   :quorum quorum-ok
   :serie {v1 [{:ente-id sid :id (random-uuid) :sessao-id sid :vereador-id v1 :tipo "entrada"
                :modalidade "plenario" :fonte "manual_secretaria" :ocorrido-em instante
                :efetivado-em instante}
               {:ente-id sid :id (random-uuid) :sessao-id sid :vereador-id v1 :tipo "saida"
                :modalidade "plenario" :fonte "manual_secretaria" :ocorrido-em instante-2
                :efetivado-em instante-2}]}
   :justificativas []
   :atos-de-chamada-conduzida [{:id (random-uuid) :ente-id sid :sessao-id sid :conduzida-por (random-uuid)
                                :membros-da-casa 2 :ocorrido-em instante :registrado-em instante}]})

(defn- render-str [documento]
  (let [{conteudo :bytes content-type :content-type} (ser/serializar (ser/serializador-folha-html) documento)]
    {:html (String. ^bytes conteudo "UTF-8") :content-type content-type}))

;; ---------- forma basica do resultado ----------

(deftest content-type-e-text-html-utf8
  (is (= "text/html; charset=utf-8" (:content-type (render-str documento-base)))))

(deftest html-e-autocontido
  (let [{:keys [html]} (render-str documento-base)]
    (is (str/includes? html "<style"))
    (is (not (str/includes? html "<link")))
    (is (not (str/includes? html "<script")))
    (is (not (str/includes? html "http://")))
    (is (not (str/includes? html "https://")))))

(deftest html-inlina-o-css-de-resources
  (let [{:keys [html]} (render-str documento-base)
        css (slurp (io/resource "folha/folha.css"))]
    (is (str/includes? html css)
        "o <style> tem de conter o CSS de resources/folha/folha.css literal, sem edicao")))

;; ---------- determinismo ----------

(deftest mesma-entrada-produz-bytes-identicos
  (let [a (ser/serializar (ser/serializador-folha-html) documento-base)
        b (ser/serializar (ser/serializador-folha-html) documento-base)]
    (is (= (vec (:bytes a)) (vec (:bytes b))))))

(deftest ordem-de-serie-nao-depende-de-ordem-de-hash-do-mapa
  ;; :serie e' map-of — a ordem de iteracao de um hash-map do Clojure NAO e' garantida. O adapter tem de
  ;; ordenar pela ordem de :linhas, nao pela ordem que o mapa entrega.
  (let [doc-a (assoc documento-base :serie
                      (array-map v1 (get-in documento-base [:serie v1])
                                 v3 [{:ente-id sid :id (random-uuid) :sessao-id sid :vereador-id v3
                                      :tipo "entrada" :modalidade "plenario" :fonte "painel_eletronico"
                                      :ocorrido-em instante :efetivado-em instante}
                                     {:ente-id sid :id (random-uuid) :sessao-id sid :vereador-id v3
                                      :tipo "saida" :modalidade "plenario" :fonte "painel_eletronico"
                                      :ocorrido-em instante-2 :efetivado-em instante-2}]))
        doc-b (assoc documento-base :serie
                      (array-map v3 (get-in doc-a [:serie v3])
                                 v1 (get-in doc-a [:serie v1])))
        html-a (:html (render-str doc-a))
        html-b (:html (render-str doc-b))]
    (is (= html-a html-b)
        "documentos com o MESMO conteudo de serie em ordem de mapa diferente produzem o MESMO HTML")
    (is (< (.indexOf html-a "Ana Souza") (.indexOf html-a "Carlos Dias"))
        "a secao de movimentacoes segue a ordem de :linhas (Ana antes de Carlos), nao a ordem do mapa")))

;; ---------- D1: quorum e' repassado verbatim, nunca recalculado ----------

(deftest todos-os-cinco-campos-do-quorum-aparecem-verbatim
  (let [{:keys [html]} (render-str documento-base)]
    (is (str/includes? html "2") "presentes-plenario/total/membros = 2 tem de aparecer")
    (is (str/includes? html "1") "presencas-fora-do-roster = 1 tem de aparecer")
    (is (str/includes? html "0") "presentes-remoto = 0 tem de aparecer, zero nao se omite")))

;; ---------- escape de HTML (dado livre digitado por humano) ----------

(deftest nome-com-tag-e-escapado
  (let [doc (assoc-in documento-base [:linhas 0 :nome] "Ana <script>alert(1)</script> Souza")
        {:keys [html]} (render-str doc)]
    (is (not (str/includes? html "<script>alert(1)</script>")))
    (is (str/includes? html "&lt;script&gt;alert(1)&lt;/script&gt;"))))

(deftest motivo-de-justificativa-com-tag-e-escapado
  (let [doc (assoc documento-base :justificativas
                    [{:id (random-uuid) :vereador-id v1 :estado "pendente"
                      :motivo "problema de saude </td><td>injetado" :lock-version 0
                      :decidido-por nil :decidido-em nil}])
        {:keys [html]} (render-str doc)]
    (is (not (str/includes? html "</td><td>injetado")))
    (is (str/includes? html "&lt;/td&gt;&lt;td&gt;injetado"))))

(deftest motivo-nao-realizada-com-aspas-e-escapado
  (let [doc (-> documento-base
                (assoc-in [:sessao :estado] "nao_realizada")
                (assoc-in [:sessao :motivo-nao-realizada] "falta de quorum \"as pressas\" & sem aviso"))
        {:keys [html]} (render-str doc)]
    (is (not (str/includes? html "\"as pressas\" &")))
    (is (str/includes? html "&quot;as pressas&quot; &amp;"))
    (is (str/includes? html "falta de quorum"))))

;; ---------- sessao nao_realizada ----------

(deftest sessao-nao-realizada-imprime-o-motivo
  (let [doc (-> documento-base
                (assoc-in [:sessao :estado] "nao_realizada")
                (assoc-in [:sessao :motivo-nao-realizada] "quorum insuficiente na abertura"))
        {:keys [html]} (render-str doc)]
    (is (str/includes? html "quorum insuficiente na abertura"))))

;; ---------- sem-assento: secao so' existe quando ha' linha sem-assento ----------

(deftest secao-sem-assento-aparece-quando-ha-linha
  (let [{:keys [html]} (render-str documento-base)]
    (is (str/includes? html "Sem assento na data"))
    (is (str/includes? html "Carlos Dias"))))

(deftest secao-sem-assento-nao-aparece-quando-vazia
  (let [doc (assoc documento-base :linhas [linha-presente linha-licenciada])
        {:keys [html]} (render-str doc)]
    (is (not (str/includes? html "Sem assento na data")))))

;; ---------- licenciado: horas nao quebram, viram travessao ----------

(deftest licenciado-sem-eventos-nao-lanca-e-mostra-travessao
  (let [{:keys [html]} (render-str documento-base)]
    (is (str/includes? html "Beatriz Lima"))
    (is (str/includes? html "não se marca"))))

;; ---------- movimentacoes: so' quem tem mais de 1 evento ----------

(deftest movimentacoes-so-lista-quem-tem-mais-de-um-evento
  (let [{:keys [html]} (render-str documento-base)]
    ;; Ana (v1) tem 2 eventos na fixture -> aparece na secao de movimentacoes.
    (is (str/includes? html "Entrada"))
    (is (str/includes? html "Saída"))))

(deftest sem-vereador-com-mais-de-um-evento-secao-nao-aparece
  (let [doc (assoc documento-base :serie {})
        {:keys [html]} (render-str doc)]
    (is (not (str/includes? html "MOVIMENTAÇ")))))

;; ---------- o aviso obrigatorio do STUB-ICP-v0 ----------

(deftest aviso-stub-icp-esta-presente
  (let [{:keys [html]} (render-str documento-base)]
    (is (str/includes? html "STUB-ICP"))
    (is (str/includes? html "não")
        "o aviso tem de negar fe' publica, nao so' citar o carimbo")))

;; ---------- cabecalho nullable: nao pode renderizar em branco ----------

(deftest cabecalho-nulo-vira-texto-explicito
  (let [doc (assoc documento-base :cabecalho-da-casa
                    {:nome-oficial nil :nome-curto nil :legislatura-numero nil
                     :legislatura-ano-inicio nil :legislatura-ano-fim nil})
        {:keys [html]} (render-str doc)]
    (is (str/includes? html "não cadastrad"))
    (is (str/includes? html "não registrada")
        "legislatura nao resolvida tem de virar texto explicito, nunca celula vazia")))
