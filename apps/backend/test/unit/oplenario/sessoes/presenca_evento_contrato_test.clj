(ns oplenario.sessoes.presenca-evento-contrato-test
  "UNIT (sem Postgres) — o CONTRATO do evento publico `presenca.registrada`.

  Ate' a revisao da fatia 1 do carry I-5, `RegistradaPayload` tipava `tipo`, `modalidade` e `fonte` como
  `:string` CRU, enquanto o produtor (`sessoes/db/presenca/registrar-evento!`) e o modelo interno
  (`sessoes/models/presenca`) ja' eram fail-closed contra `sessoes.logic`. O resultado: o unico contrato que
  atravessa a fronteira do modulo — o que `transparencia` consome e projeta — era o mais frouxo dos tres, e
  aceitava `\"presente\"`/`\"presencial\"`/`\"mesa\"`, valores que produtor nenhum emite. Foi por essa porta
  que o vocabulario ficticio entrou nos testes e manteve vivo o numerador morto (`tipo = 'presente'`).

  Estes casos fecham a porta no PRODUTOR, nao no teste: nenhuma fixture consegue mais emitir o envelope com
  vocabulario inexistente."
  (:require [clojure.test :refer [deftest is testing]]
            [oplenario.sessoes.events.presenca :as ev-presenca]
            [oplenario.sessoes.logic :as logic]))

(def ^:private valido
  {:sessao-id (random-uuid) :vereador-id (random-uuid) :tipo "entrada" :modalidade "plenario"
   :fonte "manual_secretaria" :ocorrido-em "2026-05-18T14:00:00Z"})

(deftest envelope-de-presenca-recusa-vocabulario-que-produtor-nenhum-emite
  (let [ente (random-uuid)]
    (testing "o vocabulario REAL passa — o enum nao e' um `throw` incondicional"
      (is (= "presenca.registrada" (:tipo (ev-presenca/registrada ente valido)))))
    (testing "`tipo` fora de sessoes.logic/tipos-evento-presenca e' recusado NA EMISSAO"
      (is (thrown? clojure.lang.ExceptionInfo
                   (ev-presenca/registrada ente (assoc valido :tipo "presente")))
          "'presente' era exatamente o valor do numerador morto de transparencia")
      (is (thrown? clojure.lang.ExceptionInfo
                   (ev-presenca/registrada ente (assoc valido :tipo "ausente")))
          "ausencia NUNCA e' gravada — nao existe evento 'ausente'"))
    (testing "`modalidade` e `fonte` idem"
      (is (thrown? clojure.lang.ExceptionInfo
                   (ev-presenca/registrada ente (assoc valido :modalidade "presencial")))
          "modalidades-presenca = plenario|remoto")
      (is (thrown? clojure.lang.ExceptionInfo
                   (ev-presenca/registrada ente (assoc valido :fonte "mesa")))
          "fontes-presenca nao tem 'mesa'"))))

(deftest envelope-de-presenca-aceita-todo-o-vocabulario-do-produtor
  ;; A costura: o enum do EVENTO nao pode ser mais estreito que o do produtor, senao um `retorno` legitimo
  ;; (ou um `painel_eletronico`) derrubaria a emissao dentro da tx do ato e travaria a Mesa ao vivo.
  (let [ente (random-uuid)]
    (doseq [t logic/tipos-evento-presenca]
      (is (some? (ev-presenca/registrada ente (assoc valido :tipo t))) (str "tipo " t)))
    (doseq [m logic/modalidades-presenca]
      (is (some? (ev-presenca/registrada ente (assoc valido :modalidade m))) (str "modalidade " m)))
    (doseq [f logic/fontes-presenca]
      (is (some? (ev-presenca/registrada ente (assoc valido :fonte f))) (str "fonte " f)))))
