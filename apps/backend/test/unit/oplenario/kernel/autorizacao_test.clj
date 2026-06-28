(ns oplenario.kernel.autorizacao-test
  "Mecanica de policy.check two-layer (§22.5 eixo E): camada GROSSA (esfera + papel, independe do
  recurso) e camada FINA (policy.check com o recurso carregado). Pura — sem I/O."
  (:require [clojure.test :refer [deftest is]]
            [oplenario.kernel.autorizacao :as authz]))

(def ^:private servidor {:identidade-id "id-1" :ente-id "ente-a" :papeis #{:servidor_protocolo}})
(def ^:private operador {:identidade-id "op-1" :ente-id nil :papeis #{:operador_saas}})

;; --- camada grossa: esfera (cross-esfera, §22.10) ---
(deftest esfera-tenant-exige-ente-id
  (is (= servidor (authz/checar-esfera! servidor :tenant)) "ator com ente-id passa em op de tenant")
  (is (thrown? clojure.lang.ExceptionInfo (authz/checar-esfera! operador :tenant))
      "operador (sem ente-id) NAO faz op de tenant (cross-esfera 403)"))

(deftest esfera-supratenant-rejeita-ente-id
  (is (= operador (authz/checar-esfera! operador :supratenant)) "operador faz op supratenant")
  (is (thrown? clojure.lang.ExceptionInfo (authz/checar-esfera! servidor :supratenant))
      "ator de tenant NAO faz op supratenant (cross-esfera 403)"))

;; --- camada grossa: papel estatico (categoria da acao) ---
(deftest exige-papel-da-categoria
  (is (= servidor (authz/exige-papel! servidor :servidor_protocolo)) "papel presente passa")
  (is (thrown? clojure.lang.ExceptionInfo (authz/exige-papel! servidor :presidente_mesa))
      "papel ausente nega"))

;; --- camada fina: policy.check com recurso carregado ---
(deftest check-permite-quando-politica-verdadeira
  (let [recurso {:tipo :proposicao :id "p1" :autor "id-1"}
        e-autor? (fn [ator rec] (= (:identidade-id ator) (:autor rec)))]
    (is (true? (authz/check! servidor :proposicao.editar recurso e-autor?))
        "policy.check permite: servidor e' autor do recurso")))

(deftest check-nega-quando-politica-falsa
  (let [recurso {:tipo :proposicao :id "p1" :autor "outro"}
        e-autor? (fn [ator rec] (= (:identidade-id ator) (:autor rec)))]
    (is (thrown? clojure.lang.ExceptionInfo (authz/check! servidor :proposicao.editar recurso e-autor?))
        "policy.check nega: servidor NAO e' autor")))

(deftest negado-e-reconhecivel-pelo-interceptor
  (is (true? (try (authz/checar-esfera! operador :tenant)
                  (catch clojure.lang.ExceptionInfo e (authz/negado? e))))
      "a excecao de negacao e' reconhecivel (-> 403 no interceptor)"))

(deftest ator-sistema-existe-para-jobs
  (is (true? (:sistema? authz/ator-sistema)) "ha um ator-sistema explicito p/ jobs/workers"))

;; --- fail-closed defensivo (achados de review) ---
(deftest fail-closed-com-ator-nil
  (is (thrown? clojure.lang.ExceptionInfo (authz/checar-esfera! nil :supratenant))
      "ator nil NEGA supratenant (nao fail-open — maior blast radius)")
  (is (thrown? clojure.lang.ExceptionInfo (authz/checar-esfera! nil :tenant)) "ator nil nega tenant")
  (is (thrown? clojure.lang.ExceptionInfo (authz/exige-papel! nil :servidor_protocolo)) "ator nil nega papel")
  (is (thrown? clojure.lang.ExceptionInfo (authz/check! nil :x {} (fn [_ _] true))) "ator nil nega check"))

(deftest esfera-desconhecida-nega
  (is (thrown? clojure.lang.ExceptionInfo (authz/checar-esfera! servidor :esfera-invalida))
      "esfera desconhecida NEGA (default-deny)"))

(deftest politica-que-lanca-vira-negacao
  (let [explode (fn [_ _] (throw (ex-info "erro de db na politica" {})))]
    (is (true? (try (authz/check! servidor :x {:tipo :t :id "1"} explode)
                    (catch clojure.lang.ExceptionInfo e (authz/negado? e))))
        "politica que LANCA vira NEGACAO classificada (nao erro cru indeterminado)")))

(deftest negado-false-para-excecao-comum
  (is (false? (authz/negado? (ex-info "outro erro" {}))) "excecao nao-authz nao e' classificada como negacao"))
