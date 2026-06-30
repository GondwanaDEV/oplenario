(ns oplenario.compliance.logic-test
  "UNIT (puro) — F5.1: o ciclo da obrigacao de compliance (enum FIXO em codigo, §22.7.7) + a costura
  remessa->obrigacao (aceita CUMPRE; rejeicao NAO). O motor (oplenario.motor) avalia a DSL e da o
  VEREDITO + o vence_em; o compliance e' dono do CICLO PERSISTIDO — reconcilia o veredito contra o
  estado atual da obrigacao. Esta logica e' pura (sem banco)."
  (:require [clojure.test :refer [deftest is]]
            [oplenario.compliance.logic :as logic])
  (:import (java.time LocalDate)))

(defn- d [y m dia] (LocalDate/of (int y) (int m) (int dia)))

;; ---------- vencido? (data, nao instante — compliance opera em LocalDate) ----------

(deftest vencido?-compara-datas
  (is (true?  (logic/vencido? (d 2026 1 31) (d 2026 2 1)))  "agora apos vence_em = vencido")
  (is (false? (logic/vencido? (d 2026 1 31) (d 2026 1 31))) "no exato dia do vencimento NAO vencido")
  (is (false? (logic/vencido? (d 2026 1 31) (d 2026 1 30))) "antes do vencimento NAO vencido"))

;; ---------- proxima-fase: a transicao do ciclo (pendente|cumprida|vencida|dispensada|cancelada) ----------

(deftest proxima-fase-de-pendente
  (is (= "cumprida" (logic/proxima-fase "pendente" true  false)) "conforme cumpre, mesmo dentro do prazo")
  (is (= "cumprida" (logic/proxima-fase "pendente" true  true))  "conforme cumpre, mesmo apos o prazo (cumprimento tardio)")
  (is (= "vencida"  (logic/proxima-fase "pendente" false true))  "nao-conforme + apos prazo = vencida")
  (is (= "pendente" (logic/proxima-fase "pendente" false false)) "nao-conforme dentro do prazo segue pendente"))

(deftest proxima-fase-de-vencida-ainda-pode-cumprir
  (is (= "cumprida" (logic/proxima-fase "vencida" true  true))  "obrigacao vencida ainda CUMPRE se virar conforme (cumprimento tardio)")
  (is (= "vencida"  (logic/proxima-fase "vencida" false true))  "segue vencida enquanto nao-conforme"))

(deftest proxima-fase-terminais-nao-revertem
  (is (= "cumprida"   (logic/proxima-fase "cumprida"   false true))  "cumprida NAO reverte por avaliacao posterior")
  (is (= "dispensada" (logic/proxima-fase "dispensada" false true))  "dispensada (ato admin) e' terminal p/ a avaliacao automatica")
  (is (= "cancelada"  (logic/proxima-fase "cancelada"  true  false)) "cancelada e' terminal p/ a avaliacao automatica"))

;; ---------- costura remessa -> obrigacao (so 'aceita' cumpre; §22.7.8) ----------

(deftest cumpre-obrigacao?-so-aceita
  (is (true?  (logic/cumpre-obrigacao? "aceita"))    "remessa aceita pelo TCE CUMPRE a obrigacao")
  (is (false? (logic/cumpre-obrigacao? "rejeitada")) "remessa rejeitada NAO cumpre")
  (is (false? (logic/cumpre-obrigacao? "submetida")) "submetida (aguardando resposta) ainda NAO cumpre")
  (is (false? (logic/cumpre-obrigacao? "rascunho"))  "rascunho nao cumpre"))

;; ---------- vocabularios (guardas de profundidade) ----------

(deftest vocabularios-veredito-origem
  (is (nil? (logic/validar-veredito "conforme")) "veredito valido nao lanca")
  (is (thrown? Exception (logic/validar-veredito "talvez")) "veredito invalido lanca")
  (is (nil? (logic/validar-origem "sweep")) "origem valida nao lanca")
  (is (thrown? Exception (logic/validar-origem "magica")) "origem invalida lanca")
  (is (nil? (logic/validar-fase "cumprida")) "fase valida nao lanca")
  (is (thrown? Exception (logic/validar-fase "explodida")) "fase invalida lanca"))
