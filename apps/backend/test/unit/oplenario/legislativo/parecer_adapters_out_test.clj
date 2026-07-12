(ns oplenario.legislativo.parecer-adapters-out-test
  "UNIT (puro, sem DB) — o gate adapters/out do EDITOR de parecer (Onda B Slice 5). `texto->relatorio-e-
  analise` e' o PARSER puro do texto-inline (duas secoes markdown); `editor->wire` projeta+valida o
  envelope ParecerEditorOut."
  (:require [clojure.test :refer [deftest is]]
            [malli.core :as m]
            [oplenario.legislativo.adapters.out.parecer :as adapters]
            [oplenario.legislativo.wire.out.parecer :as wire]))

;; ---------- texto->relatorio-e-analise (pura) ----------

(deftest parser-com-os-2-headers-presentes
  (let [texto "## Relatório\n\nTrata-se de PL sobre X.\n\n## Análise\n\nParecer favoravel."]
    (is (= {:relatorio "Trata-se de PL sobre X." :analise "Parecer favoravel."}
           (adapters/texto->relatorio-e-analise texto)))))

(deftest parser-so-relatorio-sem-header-de-analise
  (let [texto "## Relatório\n\nSo' o relatorio, sem a secao de analise."]
    (is (= {:relatorio "So' o relatorio, sem a secao de analise." :analise ""}
           (adapters/texto->relatorio-e-analise texto)))))

(deftest parser-nil-degrada-para-vazio
  (is (= {:relatorio "" :analise ""} (adapters/texto->relatorio-e-analise nil))))

(deftest parser-texto-livre-sem-headers-degrada-sem-lancar
  (let [texto "Um texto legado qualquer, sem os headers markdown esperados."]
    (is (= {:relatorio texto :analise ""} (adapters/texto->relatorio-e-analise texto)))))

;; ---------- editor->wire ----------

(defn- parecer-canonico []
  {:id (random-uuid) :ente-id (random-uuid) :objeto-tipo "proposicao" :objeto-id (random-uuid)
   :comissao-id (random-uuid) :relator-id (random-uuid) :voto-relator nil
   :estado "com_relator" :template-id (random-uuid) :texto-vigente-versao-id nil :lock-version 0
   :criado-em (java.time.Instant/parse "2026-03-01T00:00:00Z")})

(defn- objeto-canonico []
  {:id (random-uuid) :tipo "projeto_lei" :ano 2026 :sequencial 42 :urn-lex "urn:lex:x" :ementa "X"})

(defn- versao-canonica [texto-inline]
  {:id (random-uuid) :numero-versao 1 :texto-inline texto-inline})

(deftest editor->wire-com-rascunho-preferido-sobre-vigente
  (let [out (adapters/editor->wire
              {:parecer (parecer-canonico) :objeto (objeto-canonico)
               :texto-rascunho (versao-canonica "## Relatório\n\nR\n\n## Análise\n\nA")
               :texto-vigente (versao-canonica "## Relatório\n\nVELHO\n\n## Análise\n\nVELHO")})]
    (is (m/validate wire/ParecerEditorOut out))
    (is (= "rascunho" (:texto-estado out)))
    (is (= "R" (:relatorio out)))
    (is (= "A" (:analise out)))))

(deftest editor->wire-sem-rascunho-usa-vigente
  (let [out (adapters/editor->wire
              {:parecer (parecer-canonico) :objeto (objeto-canonico) :texto-rascunho nil
               :texto-vigente (versao-canonica "## Relatório\n\nR\n\n## Análise\n\nA")})]
    (is (m/validate wire/ParecerEditorOut out))
    (is (= "vigente" (:texto-estado out)))))

(deftest editor->wire-sem-nenhum-texto-e-vazio-sem-lancar
  (let [out (adapters/editor->wire
              {:parecer (parecer-canonico) :objeto (objeto-canonico)
               :texto-rascunho nil :texto-vigente nil})]
    (is (m/validate wire/ParecerEditorOut out))
    (is (= "vazio" (:texto-estado out)))
    (is (nil? (:relatorio out)))
    (is (nil? (:analise out)))))

(deftest editor->wire-objeto-nil-quando-objeto-tipo-nao-e-proposicao
  (let [out (adapters/editor->wire
              {:parecer (assoc (parecer-canonico) :objeto-tipo "emenda") :objeto nil
               :texto-rascunho nil :texto-vigente nil})]
    (is (m/validate wire/ParecerEditorOut out))
    (is (nil? (:objeto out)))))

(deftest editor->wire-inclui-assinatura-do-texto-vigente
  (let [saida (adapters/editor->wire
                {:parecer {:id (random-uuid) :objeto-tipo "proposicao" :objeto-id (random-uuid)
                           :comissao-id (random-uuid) :estado "apresentado" :template-id (random-uuid)
                           :lock-version 1 :criado-em (java.time.Instant/now)}
                 :objeto nil :texto-rascunho nil
                 :texto-vigente {:texto-inline "## Relatório\n\nX\n\n## Análise\n\nY" :numero-versao 1
                                 :assinatura-algoritmo "STUB-ICP-v0" :assinado-por (random-uuid)
                                 :assinado-em (java.time.Instant/now)}})]
    (is (= "STUB-ICP-v0" (:assinatura-algoritmo saida)))
    (is (some? (:assinado-por saida)))
    (is (some? (:assinado-em saida)))))

(deftest editor->wire-assinatura-nil-quando-fonte-e-rascunho
  (let [saida (adapters/editor->wire
                {:parecer {:id (random-uuid) :objeto-tipo "proposicao" :objeto-id (random-uuid)
                           :comissao-id (random-uuid) :estado "com_relator" :template-id (random-uuid)
                           :lock-version 1 :criado-em (java.time.Instant/now)}
                 :objeto nil
                 :texto-rascunho {:texto-inline "## Relatório\n\nX" :numero-versao 2}
                 :texto-vigente nil})]
    (is (nil? (:assinatura-algoritmo saida)) "rascunho em edicao nunca esta assinado")))
