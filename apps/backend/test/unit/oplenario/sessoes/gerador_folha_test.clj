(ns oplenario.sessoes.gerador-folha-test
  "UNIT (puro) — o RENDERIZADOR da FOLHA (§22.6 eixo C, Etapa 5 fatia 1, D1/D2/D6): projeta os valores JA
  RESOLVIDOS pelo controller num DOCUMENTO INTERMEDIARIO formato-agnostico. D1 por construcao: este ns nao
  importa `sessoes.logic` — `:linhas`/`:quorum` chegam prontos, sem uma segunda passada de derivacao aqui."
  (:require [clojure.test :refer [deftest is]]
            [malli.core :as m]
            [oplenario.sessoes.gerador-folha :as ger]
            [oplenario.sessoes.models.folha :as mod]))

(def ^:private vid #uuid "00000000-0000-0000-0000-0000000000a1")
(def ^:private sid #uuid "00000000-0000-0000-0000-0000000000b1")

(def ^:private instante (java.time.Instant/parse "2026-06-20T18:00:00Z"))

(def ^:private linha-ok
  ;; forma REAL de `controllers/linha-para-o-adapter` (a linha derivada + o que a derivacao pura nao
  ;; carrega) — nao a `LinhaChamada` crua, que `chamada-da-sessao*` nunca devolve sozinha.
  {:vereador-id vid :nome "Ana" :nome-parlamentar nil :partido "PX" :cargo-mesa nil
   :estado :presente-plenario :inconsistencia-cadastro false :sem-assento false
   :desde instante :fonte "manual_secretaria" :registrado-em instante :justificativa nil})

(def ^:private quorum-ok
  {:presentes-plenario 1 :presentes-remoto 0 :presentes-total 1 :membros-da-casa 1 :presencas-fora-do-roster 0})

(def ^:private cabecalho-ok
  {:nome-oficial "Camara Municipal de Fortaleza" :nome-curto "CMF"
   :legislatura-numero 1 :legislatura-ano-inicio 2025 :legislatura-ano-fim 2028})

(def ^:private dados-sessao-encerrada
  {:sessao {:id sid :estado "encerrada" :motivo-nao-realizada nil}
   :instante instante
   :cabecalho-da-casa cabecalho-ok
   :linhas [linha-ok]
   :quorum quorum-ok
   :serie {vid [{:ente-id sid :id (random-uuid) :sessao-id sid :vereador-id vid :tipo "entrada"
                :modalidade "plenario" :fonte "manual_secretaria" :ocorrido-em instante :efetivado-em instante}]}
   :justificativas []
   :atos-de-chamada-conduzida []})

;; ---------- o documento sai carimbado e valida contra o model ----------

(deftest renderiza-documento-com-spec-versao-e-bate-o-model
  (let [doc (ger/renderizar dados-sessao-encerrada)]
    (is (= "folha-sessao-v1" (:spec-versao doc)))
    (is (m/validate mod/FolhaDocumento doc)
        (str "documento deveria bater FolhaDocumento: " (m/explain mod/FolhaDocumento doc)))))

(deftest documento-carrega-linhas-e-quorum-tal-qual-recebidos-sem-recalculo
  ;; D1 por construcao: este teste prova que renderizar NAO toca em :linhas/:quorum, so' repassa.
  (let [doc (ger/renderizar dados-sessao-encerrada)]
    (is (= [linha-ok] (:linhas doc)))
    (is (= quorum-ok (:quorum doc)))))

;; ---------- D6: nao_realizada SEM motivo e' documento incompleto -> LANCA ----------

(deftest nao-realizada-sem-motivo-lanca
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"nao_realizada sem motivo"
        (ger/renderizar (assoc-in dados-sessao-encerrada [:sessao :estado] "nao_realizada")))
      "sessao nao_realizada sem motivo-nao-realizada e' documento incompleto -> fail-closed"))

(deftest nao-realizada-com-motivo-branco-tambem-lanca
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"nao_realizada sem motivo"
        (ger/renderizar (-> dados-sessao-encerrada
                            (assoc-in [:sessao :estado] "nao_realizada")
                            (assoc-in [:sessao :motivo-nao-realizada] "   "))))
      "motivo so' de espaco em branco e' o mesmo que ausente"))

(deftest nao-realizada-com-motivo-renderiza-normal
  (let [doc (ger/renderizar (-> dados-sessao-encerrada
                                (assoc-in [:sessao :estado] "nao_realizada")
                                (assoc-in [:sessao :motivo-nao-realizada] "falta de quorum")))]
    (is (= "falta de quorum" (get-in doc [:sessao :motivo-nao-realizada])))))

;; ---------- fail-closed estrutural: campo obrigatorio ausente ----------

(deftest sessao-ausente-lanca
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"sessao ausente"
        (ger/renderizar (dissoc dados-sessao-encerrada :sessao)))))

(deftest instante-ausente-lanca
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"instante ausente"
        (ger/renderizar (dissoc dados-sessao-encerrada :instante)))))

(deftest linhas-ausentes-lanca
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"linhas ausente"
        (ger/renderizar (dissoc dados-sessao-encerrada :linhas)))))

(deftest quorum-ausente-lanca
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"quorum ausente"
        (ger/renderizar (dissoc dados-sessao-encerrada :quorum)))))

;; ---------- serie/justificativas/atos default para vazio quando nil (documento legitimo) ----------

(deftest sem-serie-nem-justificativas-nem-atos-e-documento-legitimo
  (let [doc (ger/renderizar (dissoc dados-sessao-encerrada :serie :justificativas :atos-de-chamada-conduzida))]
    (is (= {} (:serie doc)))
    (is (= [] (:justificativas doc)))
    (is (= [] (:atos-de-chamada-conduzida doc)))
    (is (m/validate mod/FolhaDocumento doc))))
