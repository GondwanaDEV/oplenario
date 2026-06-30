(ns oplenario.compliance.gerador-remessa-test
  "UNIT (puro) — F5.3b: o RENDERIZADOR PROPRIO do descritor declarativo de layout (§22.7.8 dec.2b). O
  descritor e' DADO (reusa o registry de funcoes de relacao como FONTE dos valores) mas tem seu proprio
  renderizador — NAO estende a DSL de avaliacao (que reduz a booleano). `renderizar` caminha o descritor
  e projeta os valores JA RESOLVIDOS num documento intermediario (formato-agnostico); a serializacao no
  formato fisico (XML/posicional/...) e' do port SerializadorRemessa. Fail-closed: campo declarado que
  nao resolve LANCA — um artefato regulatorio nao sai com campo em branco silencioso. Layout fisico do
  SIM = [GAP] (o descritor-fixture e' ilustrativo)."
  (:require [clojure.test :refer [deftest is]]
            [malli.core :as m]
            [oplenario.compliance.gerador-remessa :as ger]
            [oplenario.compliance.models.descritor-remessa :as mod-desc]))

;; resolvidos = o que as fontes (contexto + relacoes escalares + read-ports em lote) ja' devolveram;
;; o renderizador e' PURO (nao chama fonte alguma — recebe tudo resolvido).
(def ^:private resolvidos-ok
  {:contexto {"competencia" "2099-07" "sistema" "SIM"}
   :relacoes {"nome_ente" "Camara Municipal de Fortaleza"}
   :lotes    {"despesas" [{"data" "2099-07-05" "valor" "1000,00"}
                          {"data" "2099-07-09" "valor" "250,50"}]}})

;; ---------- o descritor-fixture (ilustrativo, [GAP]) bate o model ----------

(deftest descritor-fixture-valida-o-model
  (is (m/validate mod-desc/DescritorRemessa ger/descritor-sim-fixture)
      "o descritor-fixture do SIM bate o model DescritorRemessa"))

;; ---------- renderiza cabecalho de contexto + relacao escalar ----------

(deftest renderiza-cabecalho-de-contexto-e-relacao
  (let [doc (ger/renderizar ger/descritor-sim-fixture resolvidos-ok)]
    (is (= "2099-07" (get-in doc [:cabecalho "competencia"])) "campo de :contexto resolve")
    (is (= "Camara Municipal de Fortaleza" (get-in doc [:cabecalho "nome_ente"])) "campo de :relacao resolve")
    (is (= "fixture-sim-v0" (:spec-layout-versao doc)) "o documento carrega a versao do layout")
    (is (= "SIM" (:sistema doc)) "o documento carrega o sistema")))

;; ---------- registros do lote: mapeia colunas (chave-no-registro -> campo de saida) ----------

(deftest renderiza-registros-mapeando-colunas
  (let [doc (ger/renderizar ger/descritor-sim-fixture resolvidos-ok)]
    (is (= 2 (count (:registros doc))) "um registro de saida por registro do lote")
    (is (= {"data_lancamento" "2099-07-05" "valor_total" "1000,00"} (first (:registros doc)))
        "as colunas mapeiam a chave do registro p/ o campo de saida")))

;; ---------- fail-closed: campo de cabecalho declarado mas nao resolvido LANCA ----------

(deftest cabecalho-nao-resolvido-lanca
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"campo de remessa nao resolvido"
        (ger/renderizar ger/descritor-sim-fixture
                        (assoc resolvidos-ok :relacoes {})))
      "relacao ausente -> LANCA (sem campo em branco silencioso num artefato regulatorio)"))

(deftest cabecalho-resolvido-nil-lanca
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"campo de remessa nao resolvido"
        (ger/renderizar ger/descritor-sim-fixture
                        (assoc-in resolvidos-ok [:relacoes "nome_ente"] nil)))
      "valor nil = nao resolvido -> LANCA (fail-closed estrito)"))

;; ---------- fail-closed: lote declarado mas nao resolvido LANCA ----------

(deftest lote-nao-resolvido-lanca
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"lote de remessa nao resolvido"
        (ger/renderizar ger/descritor-sim-fixture
                        (assoc resolvidos-ok :lotes {})))
      "lote ausente -> LANCA"))

;; ---------- (review sec m2) valor "" / branco = nao resolvido (campo em branco no XML regulatorio) ----------

(deftest cabecalho-resolvido-vazio-lanca
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"campo de remessa nao resolvido"
        (ger/renderizar ger/descritor-sim-fixture (assoc-in resolvidos-ok [:relacoes "nome_ente"] "  ")))
      "string branca = nao resolvido -> LANCA (artefato regulatorio nao sai com campo em branco)"))

;; ---------- (review clj M1) tipo de fonte fora do enum -> ex-info ACIONAVEL (nao IllegalArgumentException) ----------

(deftest fonte-tipo-desconhecido-lanca
  (let [desc {:spec-layout-versao "x" :sistema "S" :content-type "text/plain"
              :cabecalho [{:campo "c" :fonte [:bogus "k"]}]}]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"tipo de fonte desconhecido"
          (ger/renderizar desc {:contexto {} :relacoes {} :lotes {}}))
        "tipo de fonte invalido -> ex-info com contexto, nao o erro opaco do `case`")))

;; ---------- (review sec M1) nome de campo nao-NCName e' REJEITADO pelo model (injecao estrutural XML) ----------

(deftest nome-de-campo-invalido-rejeitado-pelo-model
  (let [mau (assoc-in ger/descritor-sim-fixture [:cabecalho 0 :campo] "foo><inj")]
    (is (false? (m/validate mod-desc/DescritorRemessa mau))
        "campo com `<`/`>` nao bate o model (NomeCampo NCName) — fecha a injecao de tag XML"))
  (is (false? (m/validate mod-desc/DescritorRemessa
                          (assoc-in ger/descritor-sim-fixture [:registros :colunas 0 :campo] "a b")))
      "coluna com espaco tambem e' rejeitada"))

;; ---------- descritor sem secao de registros: documento com :registros vazio ----------

(deftest descritor-sem-registros-ok
  (let [desc (dissoc ger/descritor-sim-fixture :registros)
        doc  (ger/renderizar desc resolvidos-ok)]
    (is (= [] (:registros doc)) "sem :registros declarados -> documento com :registros vazio")
    (is (= "2099-07" (get-in doc [:cabecalho "competencia"])) "o cabecalho segue resolvido")))
