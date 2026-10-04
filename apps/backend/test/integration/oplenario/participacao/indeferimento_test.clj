(ns oplenario.participacao.indeferimento-test
  "INTEGRACAO (PG real) — o INDEFERIMENTO fundamentado do e-SIC (LAI art. 11 §1º II) e da solicitacao LGPD (art. 18 §4º):
  ato PROPRIO (nao um flag do responder) que, numa UNICA tx, faz o CAS do estado (so' a partir dos abertos), grava a
  fundamentacao como a resposta (append-only), cumpre o prazo (negar e' responder, dentro do prazo) e emite o evento
  proprio no outbox. Prova, contra o banco real sob FORCE RLS: a transicao a partir de CADA estado aberto; 409 no ja'
  terminal; 404 no inexistente e no de outra Casa; a atomicidade (prazo orfao aborta tudo); o recurso admitido depois do
  indeferimento (e-SIC); o balcao (historico distinguivel, `:pode-indeferir`); a visao da cidada; e a borda HTTP
  (papel, 400, 404, 409)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.stuartsierra.component :as component]
            [io.pedestal.http :as ph]
            [io.pedestal.test :as pt]
            [jsonista.core :as json]
            [next.jdbc :as jdbc]
            [oplenario.config :as config]
            [oplenario.http :as http]
            [oplenario.identidade.components.repositorio :as repo-id]
            [oplenario.interceptors :as it]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.components.idp-dev :as idp-dev]
            [oplenario.kernel.outbox :as outbox]
            [oplenario.kernel.tempo :as tempo]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.migracao :as migracao]
            [oplenario.participacao.adapters.out.atendimento :as out-atendimento]
            [oplenario.participacao.adapters.out.meus-protocolos :as out-meus]
            [oplenario.participacao.components.repositorio :as repo-part]
            [oplenario.participacao.controllers :as controllers]
            [oplenario.participacao.db.pedido-esic :as db-pedido]
            [oplenario.participacao.diplomat.http.in :as participacao-http])
  (:import (java.time Instant LocalDate)))

(def ^:dynamic *ds* nil)
(def ^:dynamic *repo* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds*   (:ds c)
                *repo* (repo-part/->RepoParticipacaoPg c (outbox/bus))]
        (try (t) (finally (component/stop c)))))))

;; relogio fixo: 12:00Z de 2026-07-03 -> Fortaleza = 2026-07-03. pedido vence 2026-07-23; solicitacao LGPD, 2026-07-18.
(def ^:private t0 (Instant/parse "2026-07-03T12:00:00Z"))
(def ^:private relogio (tempo/relogio-fixo t0))
(def ^:private t1 (Instant/parse "2026-07-08T15:30:00Z"))
(def ^:private relogio-do-ato (tempo/relogio-fixo t1))

(defn- ator [ente ident] {:ente-id ente :identidade-id ident :papeis #{}})
(defn- servidor [ente ident] {:ente-id ente :identidade-id ident :papeis #{"secretario"}})

(defn- conflito? [f]
  (= :conflito/participacao (:tipo (ex-data (try (f) nil (catch clojure.lang.ExceptionInfo e e))))))

(defn- eventos [ente tipo]
  (mapv #(json/read-value (val (first %)) json/keyword-keys-object-mapper)
        (jdbc/execute! *ds* ["SELECT payload::text FROM shared.outbox WHERE ente_id = ? AND tipo = ?
                              ORDER BY id" ente tipo])))

(defn- respostas-do-pedido [ente pedido-id]
  (tenancy/com-tenant* *ds* ente
    (fn [tx] (jdbc/execute! tx ["SELECT corpo, respondido_por, respondida_em FROM participacao.resposta_esic
                                 WHERE ente_id = ? AND pedido_id = ?" ente pedido-id]))))

(defn- respostas-da-solicitacao [ente solicitacao-id]
  (tenancy/com-tenant* *ds* ente
    (fn [tx] (jdbc/execute! tx ["SELECT corpo, respondido_por, respondida_em FROM participacao.resposta_titular
                                 WHERE ente_id = ? AND solicitacao_id = ?" ente solicitacao-id]))))

(defn- protocolar-pedido! [ente cidadao]
  (controllers/protocolar-pedido *repo* relogio (ator ente cidadao)
                                 {:assunto "Folha de pagamento" :descricao "Quero a folha de todos os servidores."}))

(defn- solicitar! [ente titular]
  (controllers/solicitar-titular! *repo* relogio (ator ente titular) {:tipo "eliminar" :detalhe "Apaguem meus dados."}))

(defn- em-analise-pedido! [ente id]
  (repo-part/transacao *repo* ente
    #(db-pedido/transicionar-estado! % {:id id :ente-id ente :de "protocolado" :para "em_analise"})))

(defn- em-analise-solicitacao! [ente id]
  (tenancy/com-tenant* *ds* ente
    (fn [tx] (jdbc/execute-one! tx ["UPDATE participacao.solicitacao_titular SET estado = 'em_analise'
                                     WHERE ente_id = ? AND id = ? AND estado = 'protocolada'" ente id]))))

(def ^:private fundamentacao-esic
  "A informacao pedida e' pessoal e de terceiros (LAI art. 31): o acesso exige consentimento ou lei, que nao ha'.")
(def ^:private fundamentacao-lgpd
  "A eliminacao nao cabe: os dados sao mantidos por obrigacao legal (LGPD art. 16, I) enquanto durar o vinculo.")

;; ---------------------------------------------------------------- e-SIC

(deftest indeferir-pedido-a-partir-de-cada-estado-aberto
  (doseq [estado-de-partida ["protocolado" "em_analise"]]
    (testing estado-de-partida
      (let [ente (random-uuid) cidadao (random-uuid) sec (random-uuid)
            {pedido-id :id protocolo :protocolo} (protocolar-pedido! ente cidadao)]
        (when (= "em_analise" estado-de-partida) (em-analise-pedido! ente pedido-id))
        (is (= estado-de-partida (:estado (repo-part/buscar-pedido *repo* ente pedido-id))) "ponto de partida")
        (let [r (controllers/indeferir-pedido! *repo* relogio-do-ato (servidor ente sec) pedido-id
                                               {:fundamentacao fundamentacao-esic})]
          (is (= t1 (:indeferido-em r)) "devolve o instante do ato (relogio injetado)")
          (is (= protocolo (:protocolo r))))
        (let [pedido (repo-part/buscar-pedido *repo* ente pedido-id)
              prazo  (repo-part/prazo-do-objeto *repo* ente "pedido_esic" pedido-id)
              resps  (respostas-do-pedido ente pedido-id)]
          (is (= "indeferido" (:estado pedido)) "CAS -> indeferido")
          (testing "a fundamentacao e' a resposta (append-only), de quem indeferiu, no instante do ato"
            (is (= 1 (count resps)))
            (is (= fundamentacao-esic (:resposta_esic/corpo (first resps))))
            (is (= sec (:resposta_esic/respondido_por (first resps))) "o servidor vem do ATOR, nunca do corpo")
            (is (= t1 (:resposta_esic/respondida_em (first resps)))))
          (testing "o prazo cumpre na MESMA tx: negar e' responder dentro do prazo"
            (is (= "cumprida" (:estado prazo)))
            (is (= t1 (:cumprida-em prazo)))
            (is (= (LocalDate/of 2026 7 23) (:vence-em prazo)) "o vencimento original segue o mesmo")))
        (testing "o evento PROPRIO, no outbox (e nao o do respondido); payload sem o texto da fundamentacao"
          (let [[ev & mais] (eventos ente "participacao.pedido_esic.indeferido")]
            (is (nil? mais) "exatamente um")
            (is (= {:pedido-id (str pedido-id) :protocolo protocolo :indeferido-em "2026-07-08T15:30:00Z"} ev))
            (is (not (str/includes? (pr-str ev) "terceiros")) "a fundamentacao nao viaja no evento"))
          (is (empty? (eventos ente "participacao.pedido_esic.respondido"))))))))

(deftest indeferir-pedido-ja-terminal-e-conflito-e-nao-deixa-rastro
  (doseq [desfecho [:respondido :indeferido]]
    (testing (name desfecho)
      (let [ente (random-uuid) cidadao (random-uuid) sec (random-uuid)
            {pedido-id :id} (protocolar-pedido! ente cidadao)]
        (case desfecho
          :respondido (controllers/responder-pedido! *repo* relogio (servidor ente sec) pedido-id {:corpo "Segue."})
          :indeferido (controllers/indeferir-pedido! *repo* relogio (servidor ente sec) pedido-id
                                                     {:fundamentacao "Primeira fundamentacao."}))
        (is (conflito? #(controllers/indeferir-pedido! *repo* relogio-do-ato (servidor ente sec) pedido-id
                                                       {:fundamentacao "Segunda fundamentacao."}))
            "terminal -> :conflito/participacao (409 na borda)")
        (is (= 1 (count (respostas-do-pedido ente pedido-id))) "o CAS que falha nao grava resposta nenhuma")
        (is (= (name desfecho) (:estado (repo-part/buscar-pedido *repo* ente pedido-id))) "o desfecho original fica")
        (is (= 1 (+ (count (eventos ente "participacao.pedido_esic.respondido"))
                    (count (eventos ente "participacao.pedido_esic.indeferido"))))
            "e nenhum evento a mais")))))

(deftest responder-pedido-ja-indeferido-e-conflito
  (let [ente (random-uuid) sec (random-uuid) {pedido-id :id} (protocolar-pedido! ente (random-uuid))]
    (controllers/indeferir-pedido! *repo* relogio (servidor ente sec) pedido-id {:fundamentacao "Nao cabe."})
    (is (conflito? #(controllers/responder-pedido! *repo* relogio (servidor ente sec) pedido-id {:corpo "Cabe."}))
        "o indeferimento fecha o pedido: responder depois e' conflito, como sempre foi o responder-depois-de-responder")))

(deftest indeferir-pedido-inexistente-ou-de-outra-casa-e-nil
  (let [ente-a (random-uuid) ente-b (random-uuid) sec (random-uuid)
        {pedido-id :id} (protocolar-pedido! ente-a (random-uuid))]
    (is (nil? (controllers/indeferir-pedido! *repo* relogio (servidor ente-a sec) (random-uuid)
                                             {:fundamentacao "x"}))
        "id que nao existe -> nil (a borda mapeia 404)")
    (is (nil? (controllers/indeferir-pedido! *repo* relogio (servidor ente-b sec) pedido-id
                                             {:fundamentacao "tentativa cross-tenant"}))
        "o pedido de A sob o servidor de B -> nil (a RLS isola; 404, nunca 409 que denunciaria a existencia)")
    (is (= "protocolado" (:estado (repo-part/buscar-pedido *repo* ente-a pedido-id))) "o de A segue intocado")
    (is (empty? (respostas-do-pedido ente-a pedido-id)) "e sem fundamentacao gravada")))

(deftest indeferir-pedido-com-prazo-orfao-aborta-a-tx-inteira
  ;; Inv.10 (a mesma do responder): o pedido so' vira terminal ATOMICO com o fechamento do prazo. Prazo cancelado
  ;; por fora (inconsistencia) -> a tx aborta (500 auditavel, NAO 409) e NADA persiste: nem estado, nem resposta, nem evento.
  (let [ente (random-uuid) sec (random-uuid) {pedido-id :id} (protocolar-pedido! ente (random-uuid))]
    (tenancy/com-tenant* *ds* ente
      (fn [tx] (jdbc/execute-one! tx ["UPDATE participacao.prazo_ativo SET estado = 'cancelada'
                                       WHERE ente_id = ? AND objeto_tipo = 'pedido_esic' AND objeto_id = ?" ente pedido-id])))
    (let [e (try (controllers/indeferir-pedido! *repo* relogio (servidor ente sec) pedido-id {:fundamentacao "x"})
                 nil (catch clojure.lang.ExceptionInfo e e))]
      (is (= :invariante/prazo-orfao (:tipo (ex-data e))) "falha ruidosa, e de tipo != :conflito (nao vira 409)"))
    (is (= "protocolado" (:estado (repo-part/buscar-pedido *repo* ente pedido-id))) "o CAS deu rollback junto")
    (is (empty? (respostas-do-pedido ente pedido-id)) "a fundamentacao nao ficou gravada")
    (is (empty? (eventos ente "participacao.pedido_esic.indeferido")) "nem o evento")))

(deftest indeferir-depois-do-vencimento-ainda-cumpre-o-prazo
  ;; como o responder: uma resposta APOS o vencimento (prazo `vencida` pelo sweep) ainda carimba o desfecho
  (let [ente (random-uuid) sec (random-uuid) {pedido-id :id} (protocolar-pedido! ente (random-uuid))]
    (repo-part/varrer-vencimentos! *repo* ente (LocalDate/of 2026 8 1))
    (is (= "vencida" (:estado (repo-part/prazo-do-objeto *repo* ente "pedido_esic" pedido-id))) "ponto de partida")
    (controllers/indeferir-pedido! *repo* relogio-do-ato (servidor ente sec) pedido-id {:fundamentacao "Fora do escopo."})
    (let [prazo (repo-part/prazo-do-objeto *repo* ente "pedido_esic" pedido-id)]
      (is (= "cumprida" (:estado prazo)))
      (is (= t1 (:cumprida-em prazo))))))

(deftest o-cidadao-recorre-do-indeferimento-e-a-casa-decide
  (let [ente (random-uuid) cidadao (random-uuid) sec (random-uuid)
        {pedido-id :id} (protocolar-pedido! ente cidadao)
        _ (controllers/indeferir-pedido! *repo* relogio (servidor ente sec) pedido-id {:fundamentacao fundamentacao-esic})
        rec (controllers/interpor-recurso! *repo* relogio (ator ente cidadao) pedido-id
                                           {:motivo "Peco so' os valores agregados, sem nomes."})]
    (is (re-matches #"REC-2026-\d{6}" (:protocolo rec)) "o grafo ja' admitia: o pedido indeferido e' recorrivel")
    (is (= "indeferido" (:estado (repo-part/buscar-pedido *repo* ente pedido-id))) "o pedido segue indeferido")
    (is (= "pendente" (:estado (repo-part/prazo-do-objeto *repo* ente "recurso_esic" (:id rec)))) "relogio proprio")
    (is (:decidido-em (controllers/decidir-recurso! *repo* relogio (servidor ente sec) (:id rec)
                                                    {:corpo "Recurso provido em parte."}))
        "e o servidor decide o recurso pela acao que ja' existia")
    (testing "quem nao e' o solicitante nao recorre"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"autorizacao negada"
            (controllers/interpor-recurso! *repo* relogio (ator ente (random-uuid)) pedido-id {:motivo "intruso"}))))))

(deftest o-balcao-mostra-o-indeferimento-distinto-da-resposta
  (let [ente (random-uuid) cidadao (random-uuid) sec-id (random-uuid)
        sec (servidor ente sec-id)
        pessoas (fn [ids] (into {} (map (fn [i] [i {:nome (if (= i sec-id) "Joana Secretaria" "Requerente")
                                                    :cpf-mascarado "***.456.789-**"}]) ids)))
        {aberto-id :id} (protocolar-pedido! ente cidadao)
        {fechado-id :id} (protocolar-pedido! ente cidadao)
        {respondido-id :id} (protocolar-pedido! ente cidadao)
        ver (fn [id] (out-atendimento/esic->wire (controllers/atendimento-esic *repo* relogio-do-ato pessoas sec id)))]
    (controllers/indeferir-pedido! *repo* relogio-do-ato sec fechado-id {:fundamentacao fundamentacao-esic})
    (controllers/responder-pedido! *repo* relogio-do-ato sec respondido-id {:corpo "Segue a folha."})
    (testing "aberto: as DUAS acoes cabem"
      (is (= {:pode-responder true :pode-indeferir true :pode-prorrogar true :pode-anexar false :recurso-pendente-id nil}
             (:acoes (ver aberto-id)))))
    (testing "indeferido: historico `indeferimento` (e nao `resposta`), com o texto e quem agiu; nada mais cabe"
      (let [d (ver fechado-id)]
        (is (= "indeferido" (:estado d)))
        (is (= [{:tipo "indeferimento" :texto fundamentacao-esic :por "Joana Secretaria"}]
               (map #(select-keys % [:tipo :texto :por]) (:historico d))))
        (is (false? (:aberto d)))
        (is (= {:pode-responder false :pode-indeferir false :pode-prorrogar false :pode-anexar true :recurso-pendente-id nil}
               (:acoes d)))
        (is (nil? (:dias-restantes d)) "encerrado: o prazo nao corre mais")))
    (testing "o respondido segue como `resposta`"
      (is (= ["resposta"] (map :tipo (:historico (ver respondido-id))))))
    (testing "a fila: o indeferido sai dos abertos e aparece em respondidos, com o estado em palavras do contrato"
      (let [fila (fn [situacao] (:itens (out-atendimento/fila->wire
                                          :esic situacao (controllers/fila-do-balcao *repo* relogio-do-ato sec :esic situacao))))]
        (is (= [(str aberto-id)] (map :id (fila "abertos"))))
        (is (= #{[(str fechado-id) "indeferido"] [(str respondido-id) "respondido"]}
               (set (map (juxt :id :estado) (fila "respondidos")))))))
    (testing "recurso pendente de um indeferido: o item reabre na fila, mas indeferir NAO volta a caber"
      (let [rec (controllers/interpor-recurso! *repo* relogio-do-ato (ator ente cidadao) fechado-id {:motivo "Recorro."})
            d (ver fechado-id)]
        (is (:aberto d))
        (is (= {:pode-responder false :pode-indeferir false :pode-prorrogar false :pode-anexar true :recurso-pendente-id (str (:id rec))}
               (:acoes d)))
        (is (= ["indeferimento" "recurso"] (map :tipo (:historico d))))))))

(deftest a-cidada-ve-o-indeferimento-com-a-fundamentacao
  (let [ente (random-uuid) cidadao (random-uuid) sec (servidor ente (random-uuid))
        {pedido-id :id} (protocolar-pedido! ente cidadao)
        {solic-id :id} (solicitar! ente cidadao)]
    (controllers/indeferir-pedido! *repo* relogio-do-ato sec pedido-id {:fundamentacao fundamentacao-esic})
    (controllers/indeferir-solicitacao! *repo* relogio-do-ato sec solic-id {:fundamentacao fundamentacao-lgpd})
    (let [wire (out-meus/meus-protocolos->wire (controllers/meus-protocolos *repo* (ator ente cidadao) relogio-do-ato))
          p (first (:pedidos-esic wire))
          s (first (:solicitacoes-lgpd wire))]
      (is (= "indeferido" (:estado p)))
      (is (= {:corpo fundamentacao-esic :respondida-em "2026-07-08T15:30:00Z"} (:resposta p))
          "a fundamentacao chega pela mesma `resposta` que o cidadao ja' le")
      (is (nil? (:recurso p)) "ainda sem recurso: a tela oferece recorrer")
      (is (= "indeferida" (:estado s)))
      (is (= fundamentacao-lgpd (get-in s [:resposta :corpo]))))
    (testing "depois do recurso, a tela mostra o recurso no lugar do botao"
      (controllers/interpor-recurso! *repo* relogio-do-ato (ator ente cidadao) pedido-id {:motivo "Recorro."})
      (let [p (first (:pedidos-esic (out-meus/meus-protocolos->wire
                                      (controllers/meus-protocolos *repo* (ator ente cidadao) relogio-do-ato))))]
        (is (= "indeferido" (:estado p)))
        (is (= "protocolado" (get-in p [:recurso :estado])))))))

;; ---------------------------------------------------------------- LGPD

(deftest indeferir-solicitacao-a-partir-de-cada-estado-aberto
  (doseq [estado-de-partida ["protocolada" "em_analise"]]
    (testing estado-de-partida
      (let [ente (random-uuid) titular (random-uuid) enc (random-uuid)
            {solic-id :id protocolo :protocolo} (solicitar! ente titular)]
        (when (= "em_analise" estado-de-partida) (em-analise-solicitacao! ente solic-id))
        (is (= estado-de-partida (:estado (repo-part/buscar-solicitacao-titular *repo* ente solic-id))))
        (let [r (controllers/indeferir-solicitacao! *repo* relogio-do-ato (servidor ente enc) solic-id
                                                    {:fundamentacao fundamentacao-lgpd})]
          (is (= t1 (:indeferida-em r)))
          (is (= protocolo (:protocolo r))))
        (let [solic (repo-part/buscar-solicitacao-titular *repo* ente solic-id)
              prazo (repo-part/prazo-do-objeto *repo* ente "solicitacao_titular" solic-id)
              resps (respostas-da-solicitacao ente solic-id)]
          (is (= "indeferida" (:estado solic)))
          (is (= 1 (count resps)))
          (is (= fundamentacao-lgpd (:resposta_titular/corpo (first resps))))
          (is (= enc (:resposta_titular/respondido_por (first resps))) "o encarregado vem do ATOR")
          (is (= "cumprida" (:estado prazo)) "prazo do TITULAR cumprido na mesma tx")
          (is (= t1 (:cumprida-em prazo))))
        (let [[ev & mais] (eventos ente "participacao.solicitacao_titular.indeferida")]
          (is (nil? mais))
          (is (= {:solicitacao-id (str solic-id) :indeferida-em "2026-07-08T15:30:00Z"} ev))
          (is (not (str/includes? (pr-str ev) "obrigacao")) "a fundamentacao nao viaja no evento"))
        (is (empty? (eventos ente "participacao.solicitacao_titular.respondida")))))))

(deftest indeferir-solicitacao-ja-terminal-e-conflito
  (doseq [desfecho [:respondida :indeferida]]
    (testing (name desfecho)
      (let [ente (random-uuid) enc (random-uuid) {solic-id :id} (solicitar! ente (random-uuid))]
        (case desfecho
          :respondida (controllers/responder-solicitacao! *repo* relogio (servidor ente enc) solic-id {:corpo "Segue."})
          :indeferida (controllers/indeferir-solicitacao! *repo* relogio (servidor ente enc) solic-id
                                                          {:fundamentacao "Primeira."}))
        (is (conflito? #(controllers/indeferir-solicitacao! *repo* relogio-do-ato (servidor ente enc) solic-id
                                                            {:fundamentacao "Segunda."})))
        (is (= 1 (count (respostas-da-solicitacao ente solic-id))))
        (is (= (name desfecho) (:estado (repo-part/buscar-solicitacao-titular *repo* ente solic-id))))))))

(deftest responder-solicitacao-ja-indeferida-e-conflito
  (let [ente (random-uuid) enc (random-uuid) {solic-id :id} (solicitar! ente (random-uuid))]
    (controllers/indeferir-solicitacao! *repo* relogio (servidor ente enc) solic-id {:fundamentacao "Nao cabe."})
    (is (conflito? #(controllers/responder-solicitacao! *repo* relogio (servidor ente enc) solic-id {:corpo "Cabe."})))))

(deftest indeferir-solicitacao-inexistente-ou-de-outra-casa-e-nil
  (let [ente-a (random-uuid) ente-b (random-uuid) enc (random-uuid) {solic-id :id} (solicitar! ente-a (random-uuid))]
    (is (nil? (controllers/indeferir-solicitacao! *repo* relogio (servidor ente-a enc) (random-uuid) {:fundamentacao "x"})))
    (is (nil? (controllers/indeferir-solicitacao! *repo* relogio (servidor ente-b enc) solic-id {:fundamentacao "x"})))
    (is (= "protocolada" (:estado (repo-part/buscar-solicitacao-titular *repo* ente-a solic-id))))
    (is (empty? (respostas-da-solicitacao ente-a solic-id)))))

(deftest indeferir-solicitacao-com-prazo-orfao-aborta-a-tx-inteira
  (let [ente (random-uuid) enc (random-uuid) {solic-id :id} (solicitar! ente (random-uuid))]
    (tenancy/com-tenant* *ds* ente
      (fn [tx] (jdbc/execute-one! tx ["UPDATE participacao.prazo_ativo SET estado = 'cancelada'
                                       WHERE ente_id = ? AND objeto_tipo = 'solicitacao_titular' AND objeto_id = ?"
                                      ente solic-id])))
    (let [e (try (controllers/indeferir-solicitacao! *repo* relogio (servidor ente enc) solic-id {:fundamentacao "x"})
                 nil (catch clojure.lang.ExceptionInfo e e))]
      (is (= :invariante/prazo-orfao (:tipo (ex-data e)))))
    (is (= "protocolada" (:estado (repo-part/buscar-solicitacao-titular *repo* ente solic-id))))
    (is (empty? (respostas-da-solicitacao ente solic-id)))
    (is (empty? (eventos ente "participacao.solicitacao_titular.indeferida")))))

(deftest o-balcao-lgpd-mostra-o-indeferimento
  (let [ente (random-uuid) titular (random-uuid) enc-id (random-uuid) enc (servidor ente enc-id)
        pessoas (fn [ids] (into {} (map (fn [i] [i {:nome (if (= i enc-id) "Camila Encarregada" "Titular")
                                                    :cpf-mascarado "***.456.789-**"}]) ids)))
        {aberta-id :id} (solicitar! ente titular)
        {fechada-id :id} (solicitar! ente titular)
        ver (fn [id] (out-atendimento/lgpd->wire (controllers/atendimento-lgpd *repo* relogio-do-ato pessoas enc id)))]
    (controllers/indeferir-solicitacao! *repo* relogio-do-ato enc fechada-id {:fundamentacao fundamentacao-lgpd})
    (is (= {:pode-responder true :pode-indeferir true :pode-anexar false} (:acoes (ver aberta-id))))
    (let [d (ver fechada-id)]
      (is (= "indeferida" (:estado d)))
      (is (= [{:tipo "indeferimento" :texto fundamentacao-lgpd :por "Camila Encarregada"}]
             (map #(select-keys % [:tipo :texto :por]) (:historico d))))
      (is (= {:pode-responder false :pode-indeferir false :pode-anexar true} (:acoes d)))
      (is (false? (:aberto d))))
    (let [fila (fn [situacao] (:itens (out-atendimento/fila->wire
                                        :lgpd situacao (controllers/fila-do-balcao *repo* relogio-do-ato enc :lgpd situacao))))]
      (is (= [(str aberta-id)] (map :id (fila "abertos"))))
      (is (= [{:id (str fechada-id) :estado "indeferida"}]
             (map #(select-keys % [:id :estado]) (fila "respondidos")))))))

;; ---------------------------------------------------------------- a borda HTTP

(defn- fake-repo-identidade [papeis]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-id/RepoIdentidade
    (snapshot-ator [_ _ente-id _identidade-id]
      {:vinculo-ativo {:id (random-uuid) :tipo "servidor"} :papeis papeis})))

(defn- service-fn [papeis]
  (let [auth (it/autenticacao (idp-dev/idp-dev) (fake-repo-identidade papeis))]
    (-> (http/servico (config/carregar)
                      (participacao-http/rotas {:auth auth :repo-participacao *repo* :relogio relogio-do-ato
                                                :resolver-ente-publico participacao-http/resolver-ente-publico-uuid})
                      it/globais)
        ph/create-server ::ph/service-fn)))

(defn- cabecalhos [ente]
  {"authorization" (str "Bearer " (json/write-value-as-string {:sub "u" :ente-id (str ente)
                                                               :identidade-id (str (random-uuid))}))
   "Content-Type" "application/json"})

(defn- ler [r] (json/read-value (:body r) json/keyword-keys-object-mapper))
(defn- corpo [m] (json/write-value-as-string m))

(deftest a-borda-http-do-indeferimento
  (let [ente (random-uuid) sec (service-fn #{"secretario"})
        {p1 :id} (protocolar-pedido! ente (random-uuid))
        {s1 :id} (solicitar! ente (random-uuid))
        rotas {:esic {:caminho #(str "/esic/pedidos/" % "/indeferir") :id p1 :chave :indeferido-em
                      :estado-final #(:estado (repo-part/buscar-pedido *repo* ente %)) :esperado "indeferido"}
               :lgpd {:caminho #(str "/lgpd/solicitacoes/" % "/indeferir") :id s1 :chave :indeferida-em
                      :estado-final #(:estado (repo-part/buscar-solicitacao-titular *repo* ente %)) :esperado "indeferida"}}]
    (doseq [[especie {:keys [caminho id chave estado-final esperado]}] rotas]
      (testing (name especie)
        (testing "quem nao e' secretario: 403 (o cidadao, o vereador, o juridico) — e nada muda"
          (doseq [papeis [#{} #{"vereador"} #{"juridico"}]]
            (is (= 403 (:status (pt/response-for (service-fn papeis) :post (caminho id) :headers (cabecalhos ente)
                                                 :body (corpo {:fundamentacao "tentativa"}))))
                (str papeis)))
          (is (not= esperado (estado-final id))))
        (testing "sem token: 401"
          (is (= 401 (:status (pt/response-for sec :post (caminho id) :headers {"Content-Type" "application/json"}
                                               :body (corpo {:fundamentacao "x"}))))))
        (testing "a fundamentacao e' obrigatoria: ausente, vazia, so' espaco, tipo errado, estourada -> 400"
          (doseq [ruim [{} {:fundamentacao ""} {:fundamentacao "   "} {:fundamentacao 3} {:corpo "so' o corpo"}
                        {:fundamentacao (apply str (repeat 50001 "x"))}]]
            (is (= 400 (:status (pt/response-for sec :post (caminho id) :headers (cabecalhos ente) :body (corpo ruim))))
                (pr-str (update-vals ruim #(if (string? %) (subs % 0 (min 10 (count %))) %)))))
          (is (= 400 (:status (pt/response-for sec :post (caminho "nao-e-uuid") :headers (cabecalhos ente)
                                               :body (corpo {:fundamentacao "x"}))))
              "id malformado"))
        (testing "outra Casa: 404, nunca 409 (nao denuncia que o protocolo existe) — e o de A segue aberto"
          (is (= 404 (:status (pt/response-for sec :post (caminho id) :headers (cabecalhos (random-uuid))
                                               :body (corpo {:fundamentacao "cross-tenant"})))))
          (is (not= esperado (estado-final id))))
        (testing "inexistente: 404"
          (is (= 404 (:status (pt/response-for sec :post (caminho (random-uuid)) :headers (cabecalhos ente)
                                               :body (corpo {:fundamentacao "x"}))))))
        (testing "secretario: 200 com so' o carimbo do ato, e o estado final no banco"
          (let [r (pt/response-for sec :post (caminho id) :headers (cabecalhos ente)
                                   :body (corpo {:fundamentacao "Fundamentada pela borda." :estado "respondido"}))]
            (is (= 200 (:status r)))
            (is (= {chave "2026-07-08T15:30:00Z"} (ler r)) "so' o carimbo: sem protocolo, id, fundamentacao")
            (is (= esperado (estado-final id)) "a chave `estado` forjada no corpo foi descartada")))
        (testing "de novo: 409"
          (is (= 409 (:status (pt/response-for sec :post (caminho id) :headers (cabecalhos ente)
                                               :body (corpo {:fundamentacao "De novo."}))))))))
    (testing "responder um indeferido pela borda: 409 (o indeferimento fechou o protocolo)"
      (is (= 409 (:status (pt/response-for sec :post (str "/esic/pedidos/" p1 "/resposta") :headers (cabecalhos ente)
                                           :body (corpo {:corpo "tarde demais"}))))))))
