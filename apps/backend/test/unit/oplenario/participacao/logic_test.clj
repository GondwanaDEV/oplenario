(ns oplenario.participacao.logic-test
  "UNIT (puro) — F6 Slice 1: o vocabulario + a matematica do prazo LAI do e-SIC (participacao/logic).
  Prova a forma disc.6 dos enums (fonte unica dos CHECK da mig 0039), o deadline por DIA-CORRIDO
  (LAI 20 dias; corridos-vs-uteis = [GAP]) e a read-derivation `dias-restantes`."
  (:require [clojure.test :refer [deftest is]]
            [oplenario.participacao.logic :as logic])
  (:import (java.time LocalDate)))

;; ---------- vocabularios (espelham os CHECK do schema) ----------

(deftest enums-do-pedido
  (is (= #{"protocolado" "em_analise" "respondido" "indeferido"} logic/estados-pedido)
      "ciclo visivel ao cidadao (pedido_esic.estado)"))

(deftest enums-do-prazo
  (is (= #{"pendente" "cumprida" "vencida" "dispensada" "cancelada"} logic/estados-prazo)
      "ciclo do prazo (forma disc.6)")
  (is (= #{"pedido_esic" "recurso_esic" "solicitacao_titular"} logic/objeto-tipos-prazo)
      "objeto_tipo polimorfico do prazo_ativo"))

;; ---------- vence-em: LAI 20 dias corridos sobre o LocalDate do recibo ----------

(deftest vence-em-soma-20-dias-corridos
  (is (= (LocalDate/of 2026 7 23) (logic/vence-em (LocalDate/of 2026 7 3)))
      "3 jul + 20 dias corridos = 23 jul (LAI 20; [GAP] corridos-vs-uteis)")
  (is (= (LocalDate/of 2027 1 15) (logic/vence-em (LocalDate/of 2026 12 26)))
      "atravessa a virada do ano por dia-corrido (sem feriados)"))

;; ---------- dias-restantes: read-derivation pura ----------

(deftest dias-restantes-e-diferenca-de-epoch-days
  (is (= 20 (logic/dias-restantes (LocalDate/of 2026 7 23) (LocalDate/of 2026 7 3)))
      "no recibo: faltam 20 dias")
  (is (= 1 (logic/dias-restantes (LocalDate/of 2026 7 23) (LocalDate/of 2026 7 22)))
      "vespera: falta 1")
  (is (= 0 (logic/dias-restantes (LocalDate/of 2026 7 23) (LocalDate/of 2026 7 23)))
      "no dia: 0")
  (is (= -2 (logic/dias-restantes (LocalDate/of 2026 7 23) (LocalDate/of 2026 7 25)))
      "vencido: negativo (o sweep de F6.3 e' quem transiciona; aqui e' read puro)"))

;; ---------- protocolo: derivado do (ano, sequencial) gapless ----------

(deftest protocolo-formata-ano-e-sequencial
  (is (= "ESIC-2026-000001" (logic/protocolo-esic 2026 1)))
  (is (= "ESIC-2026-000042" (logic/protocolo-esic 2026 42))))

;; ---------- transicoes do pedido ----------

(deftest transicao-de-pedido-valida
  (is (logic/transicao-pedido-valida? "protocolado" "em_analise"))
  (is (logic/transicao-pedido-valida? "protocolado" "respondido"))
  (is (logic/transicao-pedido-valida? "em_analise" "indeferido"))
  (is (not (logic/transicao-pedido-valida? "respondido" "em_analise")) "terminal nao volta")
  (is (not (logic/transicao-pedido-valida? "indeferido" "respondido")) "terminal nao muda"))

;; ---------- validadores (guardas de profundidade) ----------

(deftest validadores-lancam-fora-do-enum
  (is (nil? (logic/validar-estado-pedido "protocolado")))
  (is (thrown? clojure.lang.ExceptionInfo (logic/validar-estado-pedido "arquivado")))
  (is (nil? (logic/validar-estado-prazo "pendente")))
  (is (thrown? clojure.lang.ExceptionInfo (logic/validar-estado-prazo "aberta")))
  (is (nil? (logic/validar-objeto-tipo-prazo "pedido_esic")))
  (is (thrown? clojure.lang.ExceptionInfo (logic/validar-objeto-tipo-prazo "proposicao"))))
