(ns oplenario.governanca.governanca-test
  "Suíte de aceitação: prova B1 (gate fail-closed), B2 (público passa com PII redigido), B3 (bloqueado
  degrada e NÃO vaza), B4 (auditoria sem conteúdo). O fake-vendor registra o que recebeu = prova de não-vazamento."
  (:require [clojure.test :refer [deftest is]]
            [oplenario.governanca.provenancia :as prov]
            [oplenario.governanca.redator :as red]
            [oplenario.governanca.filtro :as f]
            [oplenario.governanca.porta :as porta]))

(def ^:private agora 1000)
(defn- publica [t]      {:texto t :proveniencia {:origem "x" :sigilo :publico}})
(defn- secreta [t]      {:texto t :proveniencia {:origem "s" :sigilo :secreto}})
(defn- restrita [t]     {:texto t :proveniencia {:origem "d" :sigilo :restrito}})
(defn- voto-secreto [t] {:texto t :proveniencia {:origem "v" :sigilo :publico :voto-secreto? true}})
(defn- sem-tag [t]      {:texto t :proveniencia nil})

(deftest b1-gate-fail-closed
  (is (true?  (prov/liberado? (publica "lei publica"))) "pública explícita libera")
  (is (false? (prov/liberado? (sem-tag "?"))) "sem proveniência = bloqueado (fail-closed)")
  (is (false? (prov/liberado? (secreta "ata secreta"))) "sessão secreta bloqueada")
  (is (false? (prov/liberado? (restrita "e-SIC"))) "restrito bloqueado")
  (is (false? (prov/liberado? (voto-secreto "voto"))) "voto secreto bloqueado mesmo com sigilo :publico"))

(deftest b3-bloqueado-degrada-e-nao-vaza
  (let [recv (atom []) v (porta/fake-vendor recv "fake")
        r (f/chamar-com-governanca [(publica "lei") (secreta "SEGREDO")] v agora)]
    (is (= :degradado (:status r)) "qualquer peça sigilosa degrada a chamada inteira")
    (is (empty? @recv) "vendor NUNCA recebeu nada (nem o sigiloso, nem o público junto)")
    (is (= :bloqueado (:decisao (:auditoria r))) "auditoria registra bloqueio")
    (is (not (re-find #"SEGREDO" (pr-str (:auditoria r)))) "auditoria NÃO duplica conteúdo")))

(deftest b2-publico-passa-com-pii-redigido
  (let [recv (atom []) v (porta/fake-vendor recv "fake")
        texto "Vereador Joao Silva citou o CPF 123.456.789-00 e o email ze@ex.com"
        r (f/chamar-com-governanca [(publica texto)] v agora)]
    (is (= :enviado (:status r)) "público é enviado")
    (is (= 1 (count @recv)) "vendor chamado uma vez")
    (let [enviado (first (first @recv))]
      (is (re-find #"Joao Silva" enviado) "nome público PRESERVADO")
      (is (not (re-find #"123\.456\.789-00" enviado)) "CPF redigido ANTES do vendor")
      (is (not (re-find #"ze@ex\.com" enviado)) "email redigido ANTES do vendor")
      (is (re-find #"\[REDIGIDO:CPF\]" enviado) "marcador de redação presente"))
    (is (= {:cpf 1 :email 1} (:redacoes (:auditoria r))) "auditoria conta redações (sem conteúdo)")))

(deftest b4-auditoria-vendor-correto
  (let [recv (atom []) v (porta/fake-vendor recv "claude-bedrock-sa-east-1")
        r (f/chamar-com-governanca [(publica "texto legislativo publico")] v agora)]
    (is (= "claude-bedrock-sa-east-1" (:vendor (:auditoria r))) "auditoria carimba o vendor")
    (is (= agora (:timestamp (:auditoria r))) "timestamp = relógio injetado")
    (is (string? (:payload-hash (:auditoria r))) "ref de conteúdo por hash, não conteúdo")))

(deftest redator-alta-precisao
  (let [{:keys [texto redacoes]} (red/redigir "CNPJ 12.345.678/0001-90, CPF 111.222.333-44, x@y.com")]
    (is (= {:cnpj 1 :cpf 1 :email 1} redacoes) "conta cada tipo")
    (is (not (re-find #"111\.222\.333-44" texto)) "CPF sumiu")
    (is (re-find #"\[REDIGIDO:CNPJ\]" texto) "CNPJ redigido (antes do CPF, sem fragmentar)")))
