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

;; ---------- vencido?: boundary do sweep (ESTRITO — coerente com dias-restantes '0 = ultimo dia') ----------

(deftest vencido?-e-estrito-no-dia-do-vencimento
  (is (not (logic/vencido? (LocalDate/of 2026 7 23) (LocalDate/of 2026 7 22))) "vespera: nao vencido")
  (is (not (logic/vencido? (LocalDate/of 2026 7 23) (LocalDate/of 2026 7 23)))
      "NO DIA do vencimento: NAO vencido (ultimo dia ainda valido — o sweep so vence vence_em < hoje)")
  (is (logic/vencido? (LocalDate/of 2026 7 23) (LocalDate/of 2026 7 24)) "dia seguinte: vencido"))

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

;; ========================= SLICE 2: recurso + resposta =========================

;; ---------- vocabulario do recurso (espelha o CHECK da mig 0040) ----------

(deftest enums-do-recurso
  (is (= #{"protocolado" "decidido"} logic/estados-recurso)
      "ciclo visivel do recurso (recurso_esic.estado)"))

;; ---------- transicoes do recurso ----------

(deftest transicao-de-recurso-valida
  (is (logic/transicao-recurso-valida? "protocolado" "decidido"))
  (is (not (logic/transicao-recurso-valida? "decidido" "protocolado")) "terminal nao volta")
  (is (not (logic/transicao-recurso-valida? "protocolado" "protocolado")) "sem no-op no grafo"))

(deftest terminal-recurso-so-decidido
  (is (logic/terminal-recurso? "decidido"))
  (is (not (logic/terminal-recurso? "protocolado"))))

(deftest validar-estado-recurso-lanca-fora-do-enum
  (is (nil? (logic/validar-estado-recurso "protocolado")))
  (is (nil? (logic/validar-estado-recurso "decidido")))
  (is (thrown? clojure.lang.ExceptionInfo (logic/validar-estado-recurso "arquivado"))))

;; ---------- quais estados do PEDIDO admitem recurso (so os desfechos) ----------

(deftest pedido-admite-recurso-so-nos-desfechos
  (is (logic/pedido-admite-recurso? "respondido") "respondido admite recurso")
  (is (logic/pedido-admite-recurso? "indeferido") "indeferido admite recurso")
  (is (not (logic/pedido-admite-recurso? "protocolado")) "protocolado ainda nao ha o que recorrer")
  (is (not (logic/pedido-admite-recurso? "em_analise")) "em_analise ainda em curso"))

;; ---------- prazo PROPRIO do recurso (relogio independente do pedido) ----------

(deftest prazo-do-recurso-e-default-documentado-distinto-do-pedido
  (is (not= logic/dias-recurso-esic logic/dias-lai-esic)
      "o default do recurso e' deliberadamente != o do pedido (nao mascara a independencia dos relogios; ambos [GAP])"))

(deftest vence-em-recurso-soma-dias-corridos
  (is (= (LocalDate/of 2026 7 13) (logic/vence-em-recurso (LocalDate/of 2026 7 3)))
      "recibo do recurso + dias-recurso-esic (10) dias corridos [GAP default]")
  (is (not= (logic/vence-em (LocalDate/of 2026 7 3)) (logic/vence-em-recurso (LocalDate/of 2026 7 3)))
      "MESMO recibo: pedido e recurso vencem em datas DISTINTAS (relogios independentes)"))

;; ---------- protocolo do recurso (namespace distinto do pedido) ----------

(deftest protocolo-recurso-formata-com-prefixo-proprio
  (is (= "REC-2026-000001" (logic/protocolo-recurso 2026 1)))
  (is (= "REC-2026-000042" (logic/protocolo-recurso 2026 42)))
  (is (not= (logic/protocolo-recurso 2026 1) (logic/protocolo-esic 2026 1))
      "protocolo de recurso e de pedido nao colidem na leitura humana"))

;; ========================= SLICE 4: LGPD — solicitacao do titular (contador SEPARADO) =========================

;; ---------- vocabulario da solicitacao do titular (espelha o CHECK da mig 0041) ----------

(deftest tipos-e-estados-do-titular
  (is (= #{"acessar" "corrigir" "eliminar" "com_quem_compartilhado" "revogar_consentimento"}
         logic/tipos-solicitacao-titular)
      "os 5 direitos do titular (LGPD art. 18)")
  (is (= #{"protocolada" "em_analise" "respondida" "indeferida"} logic/estados-solicitacao-titular)
      "ciclo da solicitacao do titular (state-machine)")
  (is (contains? logic/objeto-tipos-prazo "solicitacao_titular")
      "o prazo polimorfico aceita a 3a especie: solicitacao_titular"))

;; ---------- transicoes da solicitacao ----------

(deftest transicao-de-solicitacao-titular-valida
  (is (logic/transicao-solicitacao-titular-valida? "protocolada" "em_analise"))
  (is (logic/transicao-solicitacao-titular-valida? "protocolada" "respondida"))
  (is (logic/transicao-solicitacao-titular-valida? "em_analise" "indeferida"))
  (is (not (logic/transicao-solicitacao-titular-valida? "respondida" "em_analise")) "terminal nao volta")
  (is (not (logic/transicao-solicitacao-titular-valida? "indeferida" "respondida")) "terminal nao muda"))

(deftest terminal-solicitacao-titular
  (is (logic/terminal-solicitacao-titular? "respondida"))
  (is (logic/terminal-solicitacao-titular? "indeferida"))
  (is (not (logic/terminal-solicitacao-titular? "protocolada")))
  (is (not (logic/terminal-solicitacao-titular? "em_analise"))))

(deftest validadores-do-titular-lancam-fora-do-enum
  (is (nil? (logic/validar-tipo-solicitacao-titular "acessar")))
  (is (thrown? clojure.lang.ExceptionInfo (logic/validar-tipo-solicitacao-titular "vender")))
  (is (nil? (logic/validar-estado-solicitacao-titular "protocolada")))
  (is (thrown? clojure.lang.ExceptionInfo (logic/validar-estado-solicitacao-titular "arquivada"))))

;; ---------- prazo LGPD: CONTADOR SEPARADO do e-SIC ([GAP] de conteudo) ----------

(deftest prazo-do-titular-e-contador-separado-do-esic
  (is (not= logic/dias-titular logic/dias-lai-esic)
      "o prazo LGPD ([GAP], default 15) e' um CONTADOR SEPARADO do e-SIC (LAI 20) — nao reusa o mesmo numero"))

(deftest vence-em-titular-soma-dias-corridos
  (is (= (LocalDate/of 2026 7 18) (logic/vence-em-titular (LocalDate/of 2026 7 3)))
      "recibo + dias-titular (15) dias corridos [GAP default]")
  (is (not= (logic/vence-em (LocalDate/of 2026 7 3)) (logic/vence-em-titular (LocalDate/of 2026 7 3)))
      "MESMO recibo: e-SIC (20) e LGPD (15) vencem em datas DISTINTAS (contadores separados)"))

;; ---------- protocolo do titular (namespace LGPD- distinto de ESIC-/REC-) ----------

(deftest protocolo-titular-formata-com-prefixo-proprio
  (is (= "LGPD-2026-000001" (logic/protocolo-titular 2026 1)))
  (is (= "LGPD-2026-000042" (logic/protocolo-titular 2026 42)))
  (is (not= (logic/protocolo-titular 2026 1) (logic/protocolo-esic 2026 1))
      "protocolo LGPD nao colide com o do e-SIC na leitura humana"))
