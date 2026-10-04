(ns oplenario.participacao.indeferimento-logic-test
  "UNIT (puro) — o INDEFERIMENTO fundamentado do e-SIC (LAI art. 11 §1º II) e da solicitacao LGPD (art. 18 §4º): o que o
  balcao diz que cabe (`:pode-indeferir`, a MESMA condicao do `:pode-responder`), o gate de entrada da fundamentacao
  (obrigatoria, mesmo teto do `corpo` da resposta), os eventos novos (contrato fechado, sem o texto da fundamentacao) e a
  forma de saida (recibo + historico distinguivel da resposta)."
  (:require [clojure.test :refer [deftest is testing]]
            [oplenario.participacao.adapters.in.indeferimento :as in]
            [oplenario.participacao.adapters.out.atendimento :as out-atendimento]
            [oplenario.participacao.adapters.out.resposta-esic :as out-esic]
            [oplenario.participacao.adapters.out.solicitacao-titular :as out-titular]
            [oplenario.participacao.events.pedido-esic :as ev-pedido]
            [oplenario.participacao.events.solicitacao-titular :as ev-titular]
            [oplenario.participacao.logic :as logic])
  (:import (java.time Instant LocalDate)))

;; ---------- o que o balcao diz que cabe ----------

(deftest pode-indeferir-tem-a-mesma-condicao-de-pode-responder
  (testing "e-SIC: o pedido ainda sem desfecho (protocolado|em_analise)"
    (doseq [estado ["protocolado" "em_analise"]]
      (let [a (logic/acoes-do-balcao :esic {:estado estado})]
        (is (true? (:pode-indeferir a)) estado)
        (is (= (:pode-responder a) (:pode-indeferir a)) estado)))
    (doseq [estado ["respondido" "indeferido"]]
      (let [a (logic/acoes-do-balcao :esic {:estado estado})]
        (is (false? (:pode-indeferir a)) estado)
        (is (= (:pode-responder a) (:pode-indeferir a)) estado))))
  (testing "e-SIC: o recurso pendente NAO reabre o pedido (decidir o recurso e' outra acao, ja' existente)"
    (let [rid (random-uuid)
          a (logic/acoes-do-balcao :esic {:estado "indeferido" :recurso {:id rid :estado "protocolado"}})]
      (is (false? (:pode-indeferir a)))
      (is (false? (:pode-responder a)))
      (is (= rid (:recurso-pendente-id a)))))
  (testing "LGPD: a solicitacao ainda sem desfecho (protocolada|em_analise)"
    (doseq [estado ["protocolada" "em_analise"]]
      (is (= {:pode-responder true :pode-indeferir true} (logic/acoes-do-balcao :lgpd {:estado estado})) estado))
    (doseq [estado ["respondida" "indeferida"]]
      (is (= {:pode-responder false :pode-indeferir false} (logic/acoes-do-balcao :lgpd {:estado estado})) estado)))
  (testing "ouvidoria nao ganha a acao (ela ja' tem arquivar)"
    (is (not (contains? (logic/acoes-do-balcao :ouvidoria {:estado "protocolada"}) :pode-indeferir)))))

(deftest o-grafo-ja-admite-o-indeferimento-de-cada-estado-aberto
  (doseq [de ["protocolado" "em_analise"]]
    (is (logic/transicao-pedido-valida? de "indeferido") de))
  (doseq [de ["protocolada" "em_analise"]]
    (is (logic/transicao-solicitacao-titular-valida? de "indeferida") de))
  (testing "terminal nao muda: nem indeferido -> respondido, nem o contrario"
    (is (not (logic/transicao-pedido-valida? "indeferido" "respondido")))
    (is (not (logic/transicao-pedido-valida? "respondido" "indeferido")))
    (is (not (logic/transicao-solicitacao-titular-valida? "indeferida" "respondida")))
    (is (not (logic/transicao-solicitacao-titular-valida? "respondida" "indeferida")))))

;; ---------- o gate de entrada da fundamentacao ----------

(defn- invalido? [json]
  (= :validacao/invalido (:tipo (ex-data (try (in/coagir-indeferimento json) (catch Exception e e))))))

(deftest fundamentacao-e-obrigatoria-texto-livre
  (is (= {:fundamentacao "O pedido pede dado pessoal de terceiro (LAI art. 31)."}
         (in/coagir-indeferimento {"fundamentacao" "O pedido pede dado pessoal de terceiro (LAI art. 31)."})))
  (testing "o mesmo teto do `corpo` da resposta: 50000 passa, 50001 nao"
    (is (= {:fundamentacao (apply str (repeat 50000 "x"))}
           (in/coagir-indeferimento {"fundamentacao" (apply str (repeat 50000 "x"))})))
    (is (invalido? {"fundamentacao" (apply str (repeat 50001 "x"))})))
  (testing "ausente, vazia, so' espaco, do tipo errado, ou corpo que nao e' objeto -> 400 (fail-closed)"
    (doseq [ruim [nil [] "texto" {} {"fundamentacao" ""} {"fundamentacao" "   "} {"fundamentacao" 3}
                  {"fundamentacao" nil} {"corpo" "so' o corpo da resposta nao serve"}]]
      (is (invalido? ruim) (pr-str ruim)))))

(deftest o-gate-descarta-chave-forjada
  (is (= {:fundamentacao "Fora do escopo da LAI."}
         (in/coagir-indeferimento {"fundamentacao" "Fora do escopo da LAI." "estado" "respondido"
                                   "respondido_por" (str (random-uuid)) "pedido_id" (str (random-uuid))
                                   "indeferido_em" "2020-01-01T00:00:00Z"}))
      "so' `fundamentacao` passa: quem, quando e em que estado vem do ator, do relogio e da rota"))

;; ---------- os eventos novos ----------

(deftest evento-de-pedido-indeferido
  (let [ente (random-uuid) pid (random-uuid)
        ev (ev-pedido/indeferido ente {:pedido-id pid :protocolo "ESIC-2026-000001"
                                       :indeferido-em "2026-07-03T12:00:00Z"})]
    (is (= "participacao.pedido_esic.indeferido" ev-pedido/indeferido-tipo (:tipo ev)))
    (is (not= ev-pedido/respondido-tipo ev-pedido/indeferido-tipo) "tipo PROPRIO, nao o do respondido")
    (is (= ente (:ente-id ev)))
    (is (= {:pedido-id pid :protocolo "ESIC-2026-000001" :indeferido-em "2026-07-03T12:00:00Z"} (:payload ev))))
  (testing "contrato fechado: a fundamentacao (texto do servidor) NUNCA viaja no payload"
    (is (thrown? clojure.lang.ExceptionInfo
                 (ev-pedido/indeferido (random-uuid) {:pedido-id (random-uuid) :protocolo "ESIC-2026-000001"
                                                      :indeferido-em "2026-07-03T12:00:00Z"
                                                      :fundamentacao "dado pessoal"}))))
  (testing "chave faltando tambem e' recusada na fonte"
    (is (thrown? clojure.lang.ExceptionInfo
                 (ev-pedido/indeferido (random-uuid) {:pedido-id (random-uuid) :protocolo "ESIC-2026-000001"})))))

(deftest evento-de-solicitacao-do-titular-indeferida
  (let [ente (random-uuid) sid (random-uuid)
        ev (ev-titular/indeferida ente {:solicitacao-id sid :indeferida-em "2026-07-03T12:00:00Z"})]
    (is (= "participacao.solicitacao_titular.indeferida" ev-titular/indeferida-tipo (:tipo ev)))
    (is (not= ev-titular/respondida-tipo ev-titular/indeferida-tipo))
    (is (= {:solicitacao-id sid :indeferida-em "2026-07-03T12:00:00Z"} (:payload ev))))
  (is (thrown? clojure.lang.ExceptionInfo
               (ev-titular/indeferida (random-uuid) {:solicitacao-id (random-uuid) :indeferida-em "x"
                                                     :fundamentacao "dado pessoal"}))))

;; ---------- a saida ----------

(deftest recibos-do-indeferimento
  (let [t (Instant/parse "2026-07-03T12:00:00Z")]
    (is (= {:indeferido-em "2026-07-03T12:00:00Z"} (out-esic/indeferimento-recibo->wire {:indeferido-em t :protocolo "ESIC-2026-000001"}))
        "so' o carimbo do ato: o protocolo e o resto nao vazam")
    (is (= {:indeferida-em "2026-07-03T12:00:00Z"} (out-titular/indeferimento-recibo->wire {:indeferida-em t :protocolo "LGPD-2026-000001"})))))

(def ^:private d (LocalDate/parse "2026-07-21"))

(deftest o-historico-do-balcao-distingue-o-indeferimento-da-resposta
  (let [base {:id (random-uuid) :protocolo "ESIC-2026-000001" :assunto "Contratos" :descricao "Lista."
              :estado "indeferido" :recibo-em (Instant/parse "2026-07-01T12:00:00Z") :aberto false
              :prazo-vigente d :dias-restantes nil :prorrogado false :recurso nil :requerente nil
              :acoes {:pode-responder false :pode-indeferir false :pode-prorrogar false :recurso-pendente-id nil}
              :historico [{:tipo "indeferimento" :em (Instant/parse "2026-07-03T12:00:00Z")
                           :texto "Dado pessoal de terceiro." :por "Joana"}]}
        w (out-atendimento/esic->wire base)]
    (is (= "indeferimento" (:tipo (first (:historico w)))))
    (is (= false (get-in w [:acoes :pode-indeferir])))
    (testing "a chave de acao ausente vira false (nunca nil: o contrato e' boolean)"
      (is (false? (get-in (out-atendimento/esic->wire (update base :acoes dissoc :pode-indeferir))
                          [:acoes :pode-indeferir]))))
    (testing "LGPD idem"
      (let [lgpd {:id (random-uuid) :protocolo "LGPD-2026-000001" :tipo "acessar" :detalhe nil :estado "indeferida"
                  :recibo-em (Instant/parse "2026-07-01T12:00:00Z") :aberto false :prazo-vigente d :dias-restantes nil
                  :prorrogado false :titular nil :acoes {:pode-responder false :pode-indeferir false}
                  :historico [{:tipo "indeferimento" :em (Instant/parse "2026-07-03T12:00:00Z")
                               :texto "Eliminar contraria obrigacao legal." :por nil}]}
            w (out-atendimento/lgpd->wire lgpd)]
        (is (= "indeferimento" (:tipo (first (:historico w)))))
        (is (= {:pode-responder false :pode-indeferir false :pode-anexar false :pode-complementar false} (:acoes w))
            "o contrato ganhou `pode-anexar`; sem a chave no dominio, vira false (boolean, nunca nil)")))))
