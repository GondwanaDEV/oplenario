(ns oplenario.comunicacao.avisos-automaticos-test
  "INTEGRACAO (PG real): ADR-0020 fatia 2 — os primeiros AVISOS AUTOMATICOS pela caixa do sistema
  (`notificacao.requisitada`, canal `in_app`): a pauta PUBLICADA avisa os vereadores (sessoes); o parecer juridico
  PEDIDO avisa as pessoas com `juridico` (legislativo). Cada aviso nasce NA MESMA tx do ato (outbox), um por pessoa,
  sem quem fez o ato, com chave de idempotencia por (ato, pessoa); e o projetor de `paineis` o poe na caixa. O seam do
  host que diz quem avisar falhar nunca impede o ato."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [com.stuartsierra.component :as component]
            [jsonista.core :as json]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.outbox :as outbox]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.legislativo.components.repositorio :as repo-leg]
            [oplenario.legislativo.controllers :as leg-controllers]
            [oplenario.migracao :as migracao]
            [oplenario.paineis.components.repositorio :as paineis]
            [oplenario.sessoes.components.repositorio :as repo-s]
            [oplenario.sessoes.controllers :as sessoes-controllers])
  (:import (java.time Instant)))

(def ^:dynamic *c* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*c* c] (try (t) (finally (component/stop c)))))))

(defn- avisos [ente]
  (->> (jdbc/execute! (:ds *c*) ["SELECT id, ente_id, payload FROM shared.outbox
                                  WHERE ente_id = ? AND tipo = 'notificacao.requisitada' ORDER BY id" ente]
                      {:builder-fn rs/as-unqualified-maps})
       (mapv (fn [r] {:id (:id r) :ente-id (:ente_id r)
                      :payload (json/read-value (.getValue ^org.postgresql.util.PGobject (:payload r))
                                                json/keyword-keys-object-mapper)}))))

(defn- caixa-do-sistema [ente iid]
  (tenancy/com-tenant* (:ds *c*) ente
    #(jdbc/execute! % ["SELECT categoria, objeto_tipo, assunto FROM paineis.notificacao_caixa
                        WHERE ente_id = ? AND destinatario_identidade_id = ?" ente iid]
                    {:builder-fn rs/as-unqualified-maps})))

(defn- projetar! [avs]
  (doseq [a avs] (jdbc/with-transaction [tx (:ds *c*)] (paineis/projetar-inbox! tx a))))

(def sec (random-uuid))
(def v1 (random-uuid))
(def v2 (random-uuid))
(def j1 (random-uuid))

(deftest a-pauta-publicada-avisa-os-vereadores
  (let [ente (random-uuid)
        rs (repo-s/->RepoSessoesPg *c* (outbox/bus))
        sid (:id (repo-s/agendar-sessao! rs ente {:id (random-uuid) :sessao-legislativa-id (random-uuid)
                                                  :tipo-sessao "ordinaria" :modalidade "presencial"
                                                  :agendada-para (Instant/parse "2026-10-07T12:00:00Z")}))
        _ (repo-s/adicionar-item-na-sessao! rs ente {:id (random-uuid) :sessao-id sid :fase "ordem_do_dia"
                                                     :tipo-item "proposicao" :proposicao-id (random-uuid)})
        ator {:identidade-id sec :ente-id ente :papeis #{"secretario"} :tipo-vinculo "servidor"}
        deps {:repo-sessoes rs :vereadores-a-avisar (constantly [v1 v2 sec])}
        r (sessoes-controllers/publicar-pauta! deps ator {:sessao-id sid} (Instant/parse "2026-10-06T12:00:00Z"))
        avs (avisos ente)]
    (is (= 1 (get-in r [:versao :numero-versao])))
    (testing "um aviso por vereador, sem quem publicou, na caixa do sistema"
      (is (= #{(str v1) (str v2)} (set (map (comp :destinatario-identidade-id :payload) avs))))
      (is (every? #(= {:canal "in_app" :categoria "pauta_publicada" :objeto-tipo "sessao" :objeto-id (str sid)}
                      (select-keys (:payload %) [:canal :categoria :objeto-tipo :objeto-id]))
                  avs))
      (is (re-find #"Pauta publicada: sessão ordinária" (get-in (first avs) [:payload :assunto])))
      (is (re-find #"07/10/2026" (get-in (first avs) [:payload :corpo]))))
    (testing "o projetor de paineis poe na caixa, uma vez (redrive e' no-op)"
      (projetar! avs)
      (projetar! avs)
      (is (= [{:categoria "pauta_publicada" :objeto_tipo "sessao"}]
             (mapv #(select-keys % [:categoria :objeto_tipo]) (caixa-do-sistema ente v1)))))
    (testing "a republicacao avisa de novo (a pauta mudou)"
      (repo-s/adicionar-item-na-sessao! rs ente {:id (random-uuid) :sessao-id sid :fase "ordem_do_dia"
                                                 :tipo-item "proposicao" :proposicao-id (random-uuid)})
      (sessoes-controllers/publicar-pauta! deps ator {:sessao-id sid :justificativa "entrou o PL 13"}
                                           (Instant/parse "2026-10-06T13:00:00Z"))
      (let [novos (drop (count avs) (avisos ente))]
        (is (= 2 (count novos)))
        (is (re-find #"republicada" (get-in (first novos) [:payload :corpo])))
        (is (re-find #"entrou o PL 13" (get-in (first novos) [:payload :corpo])))))
    (testing "o seam de quem avisar falhar nao impede publicar"
      (let [ente2 (random-uuid)
            sid2 (:id (repo-s/agendar-sessao! rs ente2 {:id (random-uuid) :sessao-legislativa-id (random-uuid)
                                                        :tipo-sessao "ordinaria" :modalidade "presencial"}))]
        (repo-s/adicionar-item-na-sessao! rs ente2 {:id (random-uuid) :sessao-id sid2 :fase "ordem_do_dia"
                                                    :tipo-item "proposicao" :proposicao-id (random-uuid)})
        (is (some? (sessoes-controllers/publicar-pauta! (assoc deps :vereadores-a-avisar (fn [_] (throw (ex-info "fora" {}))))
                                                        (assoc ator :ente-id ente2) {:sessao-id sid2}
                                                        (Instant/parse "2026-10-06T12:00:00Z"))))
        (is (empty? (avisos ente2)))))))

(deftest o-pedido-de-parecer-juridico-avisa-o-juridico
  (let [ente (random-uuid)
        rl (repo-leg/->RepoLegislativoPg *c* (outbox/bus))
        ator {:identidade-id sec :ente-id ente :papeis #{"secretario"} :tipo-vinculo "servidor"}
        p (leg-controllers/pedir-parecer-juridico! rl (constantly nil) (constantly [j1 sec]) ator
                                                   {:assunto "Competência para legislar sobre feiras livres"})
        avs (avisos ente)]
    (is (some? (:id p)))
    (is (= [(str j1)] (mapv (comp :destinatario-identidade-id :payload) avs)) "so' o juridico, nunca quem pediu")
    (is (= {:canal "in_app" :categoria "parecer_juridico_pedido" :objeto-tipo "pedido_parecer_juridico"
            :objeto-id (str (:id p))}
           (select-keys (:payload (first avs)) [:canal :categoria :objeto-tipo :objeto-id])))
    (is (re-find #"\(consulta avulsa\): Competência" (get-in (first avs) [:payload :corpo])))
    (projetar! avs)
    (is (= 1 (count (caixa-do-sistema ente j1))))
    (testing "sem o seam (ou sem ninguem com juridico), o pedido segue sem aviso"
      (let [ente2 (random-uuid)]
        (is (some? (leg-controllers/pedir-parecer-juridico! rl (constantly nil) nil (assoc ator :ente-id ente2)
                                                            {:assunto "Outro"})))
        (is (empty? (avisos ente2)))))))
