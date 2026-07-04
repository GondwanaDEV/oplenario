(ns oplenario.paineis.mesa-adapters-test
  "UNIT (puro, sem DB) — F7 dashboard da Mesa: o gate adapters/out (rollups crus + os 4 cards opacos ->
  MesaOut). Prova a derivacao das contagens (por-estado/situacao + manchetes), o embed OPACO de cada card
  (compliance/presenca/esic/relatores, verbatim, sem reprojecao), as lacunas honestas (so' o que
  genuinamente falta) e a validacao de contrato (drift -> lanca)."
  (:require [clojure.test :refer [deftest is]]
            [malli.core :as m]
            [oplenario.paineis.adapters.out.mesa :as mesa]
            [oplenario.paineis.wire.out.mesa :as wire]))

(def ^:private card-compliance-fake
  "Um PainelOut de compliance qualquer (o adapters/out de paineis o trata como mapa opaco — nao inspeciona
  a forma)."
  {:resumo {:pendente 3 :cumprida 10 :vencida 1 :dispensada 0 :cancelada 0}
   :em-aberto []
   :remessas-recentes []})

(defn- rollups-fake []
  {:tramitacao [{:estado "em_comissao" :n 5} {:estado "protocolada" :n 2}]
   :pendencias [{:estado "pendente" :n 4} {:estado "vencido" :n 1} {:estado "concluido" :n 9}]
   :sessoes    [{:estado-atual "aberta" :n 1} {:estado-atual "encerrada" :n 8}
                {:estado-atual "arquivada" :n 3} {:estado-atual "nao_realizada" :n 2}]})

(def ^:private presenca-fake {:media-percentual 78 :sessoes-consideradas 10 :membros-da-casa 43})
(def ^:private esic-fake {:total-encerrados 49 :cumpridos-no-prazo 47 :percentual 96})
(def ^:private relatores-fake {:itens []})

(deftest mesa-compoe-e-valida-o-contrato
  (let [out (mesa/mesa->wire (rollups-fake) card-compliance-fake presenca-fake esic-fake relatores-fake)]
    (is (m/validate wire/MesaOut out) "a projecao satisfaz MesaOut")))

(deftest tramitacao-rollup-total-e-por-estado
  (let [{:keys [tramitacao]} (mesa/mesa->wire (rollups-fake) card-compliance-fake presenca-fake esic-fake relatores-fake)]
    (is (= 7 (:total tramitacao)) "total = soma das contagens (5+2)")
    (is (= [{:estado "em_comissao" :n 5} {:estado "protocolada" :n 2}] (:por-estado tramitacao)))))

(deftest pendencias-rollup-so-fases-abertas-no-manchete
  (let [{:keys [pendencias]} (mesa/mesa->wire (rollups-fake) card-compliance-fake presenca-fake esic-fake relatores-fake)]
    (is (= 4 (:pendentes pendencias)))
    (is (= 1 (:vencidas pendencias)))
    (is (= 5 (:abertas pendencias)) "abertas = pendentes + vencidas (concluido nao entra)")))

(deftest sessoes-rollup-deriva-situacao-e-acumula
  (let [{:keys [sessoes]} (mesa/mesa->wire (rollups-fake) card-compliance-fake presenca-fake esic-fake relatores-fake)
        por-sit (into {} (map (juxt :situacao :n) (:por-situacao sessoes)))]
    (is (= 1 (:em-curso sessoes)) "'aberta' -> em_curso")
    (is (= 2 (:nao-realizadas sessoes)) "no-show e' o sinal de SLI (Inv.9)")
    (is (= 11 (get por-sit "realizada")) "'encerrada'(8)+'arquivada'(3) acumulam na mesma situacao 'realizada'")
    (is (= 1 (get por-sit "em_curso")))))

(deftest card-de-compliance-embutido-opaco-verbatim
  (let [out (mesa/mesa->wire (rollups-fake) card-compliance-fake presenca-fake esic-fake relatores-fake)]
    (is (= card-compliance-fake (:compliance-tce out))
        "o card de compliance entra e sai identico (embed opaco, nunca reprojetado)")))

(deftest tenant-vazio-projeta-zeros
  (let [out (mesa/mesa->wire {:tramitacao [] :pendencias [] :sessoes []} card-compliance-fake presenca-fake esic-fake relatores-fake)]
    (is (m/validate wire/MesaOut out))
    (is (= 0 (get-in out [:tramitacao :total])))
    (is (= 0 (get-in out [:pendencias :abertas])))
    (is (= 0 (get-in out [:sessoes :em-curso])))
    (is (= [] (get-in out [:sessoes :por-situacao])))))

(deftest drift-de-contrato-lanca
  ;; um card nao-mapa viola MesaOut (:compliance-tce :map) -> adapters/out lanca (nunca corpo malformado).
  (is (thrown? clojure.lang.ExceptionInfo
               (mesa/mesa->wire {:tramitacao [] :pendencias [] :sessoes []} "nao-mapa" presenca-fake esic-fake relatores-fake))))

(deftest cards-novos-embutidos-opacos-verbatim
  (let [out (mesa/mesa->wire (rollups-fake) card-compliance-fake presenca-fake esic-fake relatores-fake)]
    (is (= presenca-fake (:presenca-resumo out)))
    (is (= esic-fake (:esic-cumprimento out)))
    (is (= relatores-fake (:relatores-pendentes out)))))

(deftest cards-novos-toleram-o-sentinel-de-degradacao
  ;; Critical review finding (A5+A6 combinado): antes desta correcao, embutir {:indisponivel true} sob
  ;; presenca-resumo/esic-cumprimento/relatores-pendentes violava a forma FECHADA de cada um (diferente de
  ;; compliance-tce, que e' :map aberto) e fazia mesa->wire lancar — 500 na pagina inteira em vez de
  ;; degradacao por card. Prova que os 3 cards agora toleram o sentinel, individualmente e em combinacao.
  (let [sentinel {:indisponivel true}]
    (is (m/validate wire/MesaOut (mesa/mesa->wire (rollups-fake) card-compliance-fake sentinel esic-fake relatores-fake))
        "presenca-resumo degradado ainda valida")
    (is (m/validate wire/MesaOut (mesa/mesa->wire (rollups-fake) card-compliance-fake presenca-fake sentinel relatores-fake))
        "esic-cumprimento degradado ainda valida")
    (is (m/validate wire/MesaOut (mesa/mesa->wire (rollups-fake) card-compliance-fake presenca-fake esic-fake sentinel))
        "relatores-pendentes degradado ainda valida")
    (is (m/validate wire/MesaOut (mesa/mesa->wire (rollups-fake) card-compliance-fake sentinel sentinel sentinel))
        "os 3 cards novos degradados simultaneamente ainda validam")))

(deftest lacunas-so-lista-o-que-genuinamente-falta
  (let [{:keys [lacunas]} (mesa/mesa->wire (rollups-fake) card-compliance-fake presenca-fake esic-fake relatores-fake)]
    (is (= #{"ciencia_convocacao" "assinatura_autografo" "incidente_grant_lgpd"} (set lacunas))
        "presenca_agregada/engajamento_cidadao SAIRAM da lista (agora materializados)")))
