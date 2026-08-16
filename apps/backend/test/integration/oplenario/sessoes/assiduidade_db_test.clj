(ns oplenario.sessoes.assiduidade-db-test
  "INTEGRACAO (PG real) — Etapa 6 fatia 2: as leituras em LOTE do periodo
  (`db/sessao/listar-fechadas-no-periodo`, `db/presenca/presencas-correntes-das-sessoes`,
  `db/presenca/justificativas-das-sessoes`) e os DOIS tetos (dias do periodo, sessoes do recorte).

  Companheiro deste ns: `assiduidade-cruzado-test` fecha o CRUZADO controller-a-controller (o teste que
  importa de verdade — a leitura em lote e' identica ao que a chamada nominal, sessao a sessao, devolveria).
  Aqui o foco e' a MECANICA da leitura em lote: agrupamento correto, pre-semeadura de chave vazia, o instante
  POR SESSAO (I5) e os dois tetos fail-closed (I7)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [oplenario.cadastros.components.repositorio :as repo-cadastros]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.tempo :as tempo]
            [oplenario.migracao :as migracao]
            [oplenario.sessoes.components.repositorio :as repo-sessoes]
            [oplenario.sessoes.db.presenca :as presenca]
            [oplenario.sessoes.db.sessao :as sessao]
            [oplenario.sessoes.logic :as logic])
  (:import (java.time Instant LocalDate)))

(def ^:dynamic *repo-s* nil)
(def ^:dynamic *repo-c* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*repo-s* (repo-sessoes/->RepoSessoesPg c nil)
                *repo-c* (repo-cadastros/->RepoCadastrosPg c)]
        (try (t) (finally (component/stop c)))))))

;; ---------- seeds (produtores REAIS) ----------

(defn- casa! [ente]
  (let [leg (random-uuid)]
    (repo-cadastros/criar-legislatura! *repo-c* ente
      {:id leg :ente-id ente :numero 1 :ano-inicio 2025 :ano-fim 2028 :vigente true})
    leg))

(defn- vereador! [ente nome]
  (let [id (random-uuid)]
    (repo-cadastros/criar-vereador! *repo-c* ente {:id id :ente-id ente :nome nome
                                                   :nome-parlamentar nil :identidade-id nil})
    id))

(defn- em ^Instant [^LocalDate d] (-> d (.atStartOfDay tempo/zona-civil-padrao) .toInstant))

(defn- sessao-nao-realizada!
  "Sessao FECHADA com data TOTALMENTE controlavel: agendada -> nao_realizada DIRETO (nunca passa por
  'aberta', entao `aberta_em` fica NULL e a data de referencia sai de `agendada_para`, sob controle do
  teste — nao do `now()` do banco)."
  [ente leg tipo ^LocalDate data & [motivo]]
  (repo-sessoes/transacao *repo-s* ente
    (fn [tx]
      (let [{:keys [id]} (sessao/agendar! tx {:id (random-uuid) :ente-id ente :sessao-legislativa-id leg
                                              :tipo-sessao tipo :agendada-para (em data)})]
        (sessao/transicionar! tx {:id id :ente-id ente :para "nao_realizada" :lock-version 0
                                   :motivo (or motivo "sem quorum")})
        id))))

(defn- sessao-encerrada-hoje!
  "Sessao FECHADA de verdade (agendada -> aberta -> encerrada): `aberta_em`/`encerrada_em` saem do `now()`
  do banco — fora do controle do teste, mas necessarios quando o proprio ramo 'encerrada' e' o que se testa."
  [ente leg tipo]
  (repo-sessoes/transacao *repo-s* ente
    (fn [tx]
      (let [{:keys [id]} (sessao/agendar! tx {:id (random-uuid) :ente-id ente :sessao-legislativa-id leg
                                              :tipo-sessao tipo :agendada-para (Instant/now)})]
        (sessao/transicionar! tx {:id id :ente-id ente :para "aberta" :lock-version 0})
        (sessao/transicionar! tx {:id id :ente-id ente :para "encerrada" :lock-version 1})
        id))))

(defn- ev! [tx ente sid vid tipo modalidade quando]
  (presenca/registrar-evento! tx {:id (random-uuid) :ente-id ente :sessao-id sid :vereador-id vid
                                  :tipo tipo :modalidade modalidade :fonte "manual_secretaria"
                                  :ocorrido-em quando}))

;; ---------- listar-fechadas-no-periodo ----------

(deftest so-fechadas-entram-agendada-fica-fora
  (let [ente (random-uuid) leg (casa! ente)
        hoje (tempo/hoje-de (Instant/now) tempo/zona-civil-padrao)
        fechada (sessao-nao-realizada! ente leg "ordinaria" hoje)
        _agendada (repo-sessoes/transacao *repo-s* ente
                    (fn [tx] (:id (sessao/agendar! tx {:id (random-uuid) :ente-id ente :sessao-legislativa-id leg
                                                       :tipo-sessao "ordinaria" :agendada-para (em hoje)}))))
        linhas (repo-sessoes/transacao *repo-s* ente
                 (fn [tx] (sessao/listar-fechadas-no-periodo tx ente {:de hoje :ate hoje})))]
    (is (= #{fechada} (set (map :id linhas)))
        "so' a sessao FECHADA aparece — a agendada, com a mesma data, fica de fora")))

(deftest filtro-de-tipo-e-aplicado
  (let [ente (random-uuid) leg (casa! ente)
        hoje (tempo/hoje-de (Instant/now) tempo/zona-civil-padrao)
        ord (sessao-nao-realizada! ente leg "ordinaria" hoje)
        _extra (sessao-nao-realizada! ente leg "extraordinaria" hoje)
        so-ord (repo-sessoes/transacao *repo-s* ente
                 (fn [tx] (sessao/listar-fechadas-no-periodo tx ente {:de hoje :ate hoje :tipos ["ordinaria"]})))
        todas (repo-sessoes/transacao *repo-s* ente
                (fn [tx] (sessao/listar-fechadas-no-periodo tx ente {:de hoje :ate hoje})))]
    (is (= [ord] (mapv :id so-ord)))
    (is (= 2 (count todas)) "sem filtro de tipo, os dois tipos aparecem")))

(deftest bordas-do-periodo-sao-inclusivas-e-exatas
  (let [ente (random-uuid) leg (casa! ente)
        hoje (LocalDate/of 2026 6 20)
        antes (sessao-nao-realizada! ente leg "ordinaria" (.minusDays hoje 6))
        dentro-inicio (sessao-nao-realizada! ente leg "ordinaria" (.minusDays hoje 5))
        dentro-fim (sessao-nao-realizada! ente leg "ordinaria" hoje)
        depois (sessao-nao-realizada! ente leg "ordinaria" (.plusDays hoje 1))
        linhas (repo-sessoes/transacao *repo-s* ente
                 (fn [tx] (sessao/listar-fechadas-no-periodo tx ente
                            {:de (.minusDays hoje 5) :ate hoje})))]
    (is (= #{dentro-inicio dentro-fim} (set (map :id linhas))))
    (is (not (contains? (set (map :id linhas)) antes)))
    (is (not (contains? (set (map :id linhas)) depois)))))

(deftest cada-linha-carrega-a-data-de-referencia-ja-calculada
  (let [ente (random-uuid) leg (casa! ente)
        d (LocalDate/of 2026 6 20)
        sid (sessao-nao-realizada! ente leg "ordinaria" d)
        linhas (repo-sessoes/transacao *repo-s* ente
                 (fn [tx] (sessao/listar-fechadas-no-periodo tx ente {:de d :ate d})))]
    (is (= d (:data-de-referencia (first linhas))))))

(deftest periodo-acima-do-teto-de-dias-lanca-fail-closed
  (let [ente (random-uuid)
        hoje (LocalDate/of 2026 6 20)
        de (.minusDays hoje 367)
        erro (try (repo-sessoes/transacao *repo-s* ente
                    (fn [tx] (sessao/listar-fechadas-no-periodo tx ente {:de de :ate hoje})))
                  nil (catch clojure.lang.ExceptionInfo e e))]
    (is (some? erro) "367 dias estoura o teto de 366 — a rejeicao roda ANTES de qualquer query")
    (is (= :limite/periodo-excedido (:tipo (ex-data erro))))
    (is (= 368 (:medido (ex-data erro))))
    (is (= 366 (:teto (ex-data erro))))))

(deftest sessoes-acima-do-teto-do-recorte-lanca-fail-closed-e-o-driver-para-antes
  (let [ente (random-uuid) leg (casa! ente)
        hoje (tempo/hoje-de (Instant/now) tempo/zona-civil-padrao)]
    (dotimes [n 3] (sessao-nao-realizada! ente leg "ordinaria" hoje (str "motivo " n)))
    (is (= 3 (count (repo-sessoes/transacao *repo-s* ente
                      (fn [tx] (sessao/listar-fechadas-no-periodo tx ente {:de hoje :ate hoje})))))
        "sanidade: as 3 sessoes existem e aparecem sem teto rebaixado")
    (with-redefs [logic/teto-de-sessoes-do-periodo-de-assiduidade 2]
      (let [erro (try (repo-sessoes/transacao *repo-s* ente
                        (fn [tx] (sessao/listar-fechadas-no-periodo tx ente {:de hoje :ate hoje})))
                      nil (catch clojure.lang.ExceptionInfo e e))]
        (is (some? erro) "resultado acima do teto lanca, nunca devolve pagina truncada")
        (is (= :limite/sessoes-excedido (:tipo (ex-data erro))))
        (is (= 3 (:medido-ao-menos (ex-data erro)))
            "3 = o driver PAROU de materializar ali (:max-rows = teto+1 = 3)")
        (is (= 2 (:teto (ex-data erro))))))))

;; ---------- presencas-correntes-das-sessoes ----------

(deftest lote-de-presenca-e-identico-ao-singular-para-uma-sessao
  (let [ente (random-uuid) leg (casa! ente)
        v (vereador! ente "Ana")
        sid (sessao-encerrada-hoje! ente leg "ordinaria")]
    (repo-sessoes/transacao *repo-s* ente
      (fn [tx]
        (let [agora (Instant/now)]
          (ev! tx ente sid v "entrada" "plenario" (.minusSeconds agora 60))
          (ev! tx ente sid v "saida" "plenario" (.minusSeconds agora 30)))))
    (let [encerrada-em (:encerrada-em (repo-sessoes/buscar-sessao *repo-s* ente sid))
          singular (repo-sessoes/transacao *repo-s* ente
                     (fn [tx] (presenca/presenca-corrente tx ente sid encerrada-em)))
          lote (repo-sessoes/transacao *repo-s* ente
                 (fn [tx] (presenca/presencas-correntes-das-sessoes tx ente [[sid encerrada-em]])))]
      ;; o lote projeta `:sessao-id` a mais (precisa dele p/ agrupar; o singular nao, so' ha uma sessao) — a
      ;; comparacao e' de SEQUENCIA sobre os campos que os DOIS tem, nao do mapa inteiro.
      (is (= singular (mapv #(dissoc % :sessao-id) (get lote sid)))
          "a leitura em LOTE de UMA sessao devolve a MESMA sequencia que o singular"))))

(deftest lote-de-presenca-preseeda-chave-vazia-para-sessao-sem-evento
  (let [ente (random-uuid) leg (casa! ente)
        sid (sessao-encerrada-hoje! ente leg "ordinaria")
        encerrada-em (:encerrada-em (repo-sessoes/buscar-sessao *repo-s* ente sid))
        lote (repo-sessoes/transacao *repo-s* ente
               (fn [tx] (presenca/presencas-correntes-das-sessoes tx ente [[sid encerrada-em]])))]
    (is (= {sid []} lote) "chave PRESENTE com vetor vazio — nunca chave ausente")))

(deftest instante-e-por-sessao-nao-um-global-do-lote
  (let [ente (random-uuid) leg (casa! ente)
        v (vereador! ente "Bruno")
        s1 (sessao-encerrada-hoje! ente leg "ordinaria")
        t1 (:encerrada-em (repo-sessoes/buscar-sessao *repo-s* ente s1))
        s2 (sessao-encerrada-hoje! ente leg "extraordinaria")
        t2 (:encerrada-em (repo-sessoes/buscar-sessao *repo-s* ente s2))]
    (is (.isBefore ^Instant t1 ^Instant t2) "sanidade: s1 fechou antes de s2")
    ;; evento NA SESSAO s1, com ocorrido_em DEPOIS de t1 (o fechamento de s1) mas ANTES de t2 — um instante
    ;; GLOBAL do lote (ex.: o maximo, t2) incluiria este evento na leitura de s1; o instante POR SESSAO (t1,
    ;; I5) tem de exclui-lo.
    (repo-sessoes/transacao *repo-s* ente (fn [tx] (ev! tx ente s1 v "entrada" "plenario" (.plusMillis t1 200))))
    (let [lote (repo-sessoes/transacao *repo-s* ente
                 (fn [tx] (presenca/presencas-correntes-das-sessoes tx ente [[s1 t1] [s2 t2]])))]
      (is (empty? (get lote s1)) "evento pos-fechamento de s1 NAO aparece na leitura de s1")
      (is (empty? (get lote s2)) "s2 nunca teve evento nenhum"))))

;; ---------- justificativas-das-sessoes ----------

(deftest lote-de-justificativas-agrupa-por-sessao-e-preseeda-vazio
  (let [ente (random-uuid) leg (casa! ente)
        v1 (vereador! ente "Carla")
        s1 (sessao-encerrada-hoje! ente leg "ordinaria")
        s2 (sessao-encerrada-hoje! ente leg "ordinaria")]
    (repo-sessoes/transacao *repo-s* ente
      (fn [tx] (presenca/criar-justificativa! tx {:id (random-uuid) :ente-id ente :sessao-id s1
                                                   :vereador-id v1 :motivo "doenca"})))
    (let [lote (repo-sessoes/transacao *repo-s* ente
                 (fn [tx] (presenca/justificativas-das-sessoes tx ente [s1 s2])))]
      (is (= 1 (count (get lote s1))))
      (is (= [] (get lote s2)) "sessao sem justificativa tem chave PRESENTE com vetor vazio"))))

(deftest lista-de-sessoes-vazia-devolve-mapa-vazio-sem-tocar-o-banco
  (is (= {} (repo-sessoes/transacao *repo-s* (random-uuid)
              (fn [tx] (presenca/presencas-correntes-das-sessoes tx (random-uuid) [])))))
  (is (= {} (repo-sessoes/transacao *repo-s* (random-uuid)
              (fn [tx] (presenca/justificativas-das-sessoes tx (random-uuid) []))))))
