(ns oplenario.sessoes.presenca-chamada-cruzado-test
  "INTEGRACAO (PG real) — os CRUZADOS da revisao da Etapa 1 da CHAMADA (§22.6 eixo C). Aqui os dois lados
  rodam contra o MESMO estado semeado, com os REPOSITORIOS DE VERDADE dos dois modulos:

    - a CHAMADA (`sessoes/controllers/chamada-da-sessao` + o seam `roster-da-casa` sobre `cadastros`), que e'
      o que a Mesa le' no telao;
    - o QUORUM DO MOTOR (`relacoes/presenca/presentes-plenario|remoto`, alcancado por NOME pela DSL de
      regras de votacao), que e' o que a policy usa para deliberar.

  Por que este ns existe: a fatia 1a fechou a divergencia da ORDEM do ultimo evento, mas a revisao mostrou
  que ela reabriu uma camada acima — a chamada contava sobre `roster INTERSECAO eventos` e o motor conta
  sobre EVENTOS SOZINHOS (nenhum join a mandato; `presenca_evento.vereador_id` nao tem FK). Um evento de
  vereador fora do roster sumia da tela em silencio e a Mesa lia um numero enquanto a policy usava outro na
  MESMA votacao. O invariante que estes testes pinam e': **o numerador da chamada e' identicamente o
  numerador do motor, por modalidade, no mesmo instante**.

  O segundo cruzado e' TEMPORAL: a chamada tem de ler a sessao e a presenca no MESMO instante logico. Se a
  sessao for lida antes e a presenca depois, uma sessao encerrada no meio do request e' avaliada com o
  instante 'agora' de uma sessao que ja fechou — e um evento posterior ao fechamento entra numa chamada que
  vai para a ata."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [next.jdbc :as jdbc]
            [oplenario.cadastros.components.repositorio :as repo-cadastros]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.tempo :as tempo]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.migracao :as migracao]
            [oplenario.sessoes.components.repositorio :as repo-sessoes]
            [oplenario.sessoes.controllers :as controllers]
            [oplenario.sessoes.db.presenca :as presenca]
            [oplenario.sessoes.db.sessao :as sessao])
  (:import (java.time Duration Instant LocalDate)))

(def ^:dynamic *ds* nil)
(def ^:dynamic *repo-s* nil)
(def ^:dynamic *repo-c* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c)
                ;; bus nil: este ns so' exercita LEITURAS + seeds que NAO passam por producer (os eventos de
                ;; presenca sao semeados pelo `db/presenca/registrar-evento!`, o produtor real de linha).
                *repo-s* (repo-sessoes/->RepoSessoesPg c nil)
                *repo-c* (repo-cadastros/->RepoCadastrosPg c)]
        (try (t) (finally (component/stop c)))))))

;; ---------- seeds (produtores REAIS dos dois modulos, nunca INSERT redigitado no teste) ----------

(defn- casa! [ente]
  (let [leg (random-uuid)]
    (repo-cadastros/criar-legislatura! *repo-c* ente
      {:id leg :ente-id ente :numero 1 :ano-inicio 2025 :ano-fim 2028 :vigente true})
    leg))

(defn- vereador-com-mandato! [ente leg nome]
  (let [id (random-uuid)]
    (repo-cadastros/criar-vereador! *repo-c* ente {:id id :ente-id ente :nome nome
                                                   :nome-parlamentar nil :identidade-id nil})
    (repo-cadastros/criar-mandato! *repo-c* ente
      {:id (random-uuid) :ente-id ente :vereador-id id :legislatura-id leg :estado "vigente"
       :partido "PX" :vigencia-inicio (LocalDate/of 2025 1 1) :vigencia-fim nil})
    id))

(defn- licenciar! [ente vereador-id inicio]
  ;; produtor REAL da licenca: e' ele quem decide qual string vai para `mandato.estado`.
  (repo-cadastros/registrar-licenca! *repo-c* ente vereador-id
    {:id (random-uuid) :ente-id ente :inicio inicio :fim nil :motivo "Tratamento de saude"}
    inicio))

(defn- ev! [tx ente sessao-id vereador-id tipo modalidade ocorrido-em]
  (presenca/registrar-evento! tx {:id (random-uuid) :ente-id ente :sessao-id sessao-id
                                  :vereador-id vereador-id :tipo tipo :modalidade modalidade
                                  :fonte "manual_secretaria" :ocorrido-em ocorrido-em}))

(defn- roster-seam []
  (fn [ente-id data] (repo-cadastros/roster-da-casa *repo-c* ente-id data)))

(defn- ator [ente] {:ente-id ente :identidade-id (random-uuid)})

;; ---------- CRUZADO 1: o numerador da tela E o numerador da policy sao o MESMO conjunto ----------

(deftest numerador-da-chamada-e-identico-ao-quorum-que-o-motor-conta
  (let [ente (random-uuid)
        leg  (casa! ente)
        v1   (vereador-com-mandato! ente leg "Ana")
        _v2  (vereador-com-mandato! ente leg "Bruno")           ; sem evento nenhum -> ausente
        lic  (vereador-com-mandato! ente leg "Carla")           ; licenciada, mas ENTROU no plenario
        orfao (random-uuid)                                     ; evento sem cadeira (uuid sem cadastro)
        t0   (Instant/parse "2026-06-20T13:00:00Z")
        agora (Instant/parse "2026-06-20T14:00:00Z")
        sid  (repo-sessoes/transacao *repo-s* ente
               (fn [tx]
                 (let [{sid :id} (sessao/agendar! tx {:id (random-uuid) :ente-id ente
                                                      :sessao-legislativa-id (random-uuid)
                                                      :tipo-sessao "ordinaria" :agendada-para t0})]
                   (ev! tx ente sid v1    "entrada" "plenario" t0)
                   (ev! tx ente sid lic   "entrada" "plenario" t0)
                   (ev! tx ente sid orfao "entrada" "remoto"   t0)
                   sid)))]
    (licenciar! ente lic (LocalDate/of 2026 6 1))
    (let [chamada (controllers/chamada-da-sessao *repo-s* (roster-seam) (ator ente) sid
                                                 (tempo/relogio-fixo agora))
          q (:quorum chamada)
          instante (:instante chamada)
          motor-plenario (repo-sessoes/presentes-plenario *repo-s* ente sid instante)
          motor-remoto   (repo-sessoes/presentes-remoto *repo-s* ente sid instante)
          por-ver (into {} (map (juxt :vereador-id identity)) (:linhas chamada))]
      ;; O ASSERT QUE IMPORTA: os dois numeradores, no MESMO instante, sobre o MESMO estado.
      (is (= (:presentes-plenario q) motor-plenario)
          "presentes no plenario: a tela e a policy contam o MESMO numero")
      (is (= (:presentes-remoto q) motor-remoto)
          "presentes remoto: idem — e' o orfao que quebrava esta igualdade")
      (is (= 2 motor-plenario) "e o numero e' o esperado (nao um zero mudo dos dois lados)")
      (is (= 1 motor-remoto))
      ;; o licenciado com evento positivo EXISTE na tela e chega sinalizado
      (is (= :presente-plenario (:estado (por-ver lic))))
      (is (true? (:inconsistencia-cadastro (por-ver lic)))
          "o alarme 'cadastro diz licenciado, fato diz presente' dispara com dado REAL, nao so' em unit")
      ;; o evento sem cadeira nao some
      (is (= :presente-remoto (:estado (por-ver orfao))))
      (is (true? (:sem-assento (por-ver orfao))))
      (is (= 1 (:presencas-fora-do-roster q)) "e o desvio e' PUBLICADO, nao descartado")
      (is (= 3 (:membros-da-casa q))
          "denominador = as tres cadeiras (o orfao nao cria cadeira; a licenciada presente ocupa a dela)")
      (is (= 4 (count (:linhas chamada))) "3 do roster + 1 sem assento"))))

;; ---------- CRUZADO 2: a sessao e a presenca sao lidas no MESMO instante logico ----------

(defn- relogio-que-encerra-a-sessao-na-1a-leitura
  "Relogio de teste que SIMULA a corrida real: entre o momento em que o request comeca e o momento em que a
  presenca e' lida, a Mesa encerra a sessao e um evento tardio e' registrado. O efeito roda UMA vez, na 1a
  chamada de `agora` — exatamente onde a leitura da sessao acontecia antes da correcao."
  [ente sid vereador-tardio instante-tardio devolve]
  (let [ja (atom false)]
    (reify tempo/Relogio
      (agora [_]
        (when (compare-and-set! ja false true)
          (repo-sessoes/transacao *repo-s* ente
            (fn [tx]
              (let [{:keys [lock-version]} (sessao/buscar tx ente sid)]
                (sessao/transicionar! tx {:id sid :ente-id ente :para "encerrada"
                                          :lock-version lock-version})
                (ev! tx ente sid vereador-tardio "entrada" "plenario" instante-tardio)))))
        devolve))))

(deftest sessao-encerrada-no-meio-do-request-congela-a-chamada-no-fechamento
  (let [ente (random-uuid)
        leg  (casa! ente)
        pontual (vereador-com-mandato! ente leg "Ana")
        tardio  (vereador-com-mandato! ente leg "Bruno")
        agora-real (Instant/now)
        t-cedo  (.minus agora-real (Duration/ofHours 1))
        t-tarde (.plus agora-real (Duration/ofHours 1))   ; DEPOIS do encerramento (encerrada_em = now())
        devolve (.plus agora-real (Duration/ofHours 2))   ; o 'agora' que o relogio entrega ao controller
        sid (repo-sessoes/transacao *repo-s* ente
              (fn [tx]
                (let [{sid :id} (sessao/agendar! tx {:id (random-uuid) :ente-id ente
                                                     :sessao-legislativa-id (random-uuid)
                                                     :tipo-sessao "ordinaria" :agendada-para t-cedo})]
                  (sessao/transicionar! tx {:id sid :ente-id ente :para "aberta" :lock-version 0})
                  (ev! tx ente sid pontual "entrada" "plenario" t-cedo)
                  sid)))
        chamada (controllers/chamada-da-sessao
                  *repo-s* (roster-seam) (ator ente) sid
                  (relogio-que-encerra-a-sessao-na-1a-leitura ente sid tardio t-tarde devolve))
        por-ver (into {} (map (juxt :vereador-id identity)) (:linhas chamada))]
    (is (= "encerrada" (:sessao-estado chamada))
        "a chamada reporta o estado que a sessao TEM quando a presenca e' lida, nao um estado stale")
    (is (.isBefore ^Instant (:instante chamada) t-tarde)
        "sessao fechada -> instante CONGELADO em encerrada-em, nunca o 'agora' de uma sessao que ja fechou")
    (is (= :ausente (:estado (por-ver tardio)))
        "evento registrado DEPOIS do fechamento nao entra numa chamada que vai para a ata")
    (is (= :presente-plenario (:estado (por-ver pontual))))
    (is (= 1 (:presentes-plenario (:quorum chamada)))
        "o quorum publicado e' o do fechamento — 1, nao 2")))

;; ---------- o CHECK do banco: sessao fechada sem carimbo de encerramento nao existe ----------

(deftest banco-recusa-arquivada-sem-encerrada-em
  ;; O CHECK `sessao_encerrada_em_obrigatoria` da mig 0026 cobria so 'encerrada'/'nao_realizada'. Uma linha
  ;; 'arquivada' com `encerrada_em` NULL era ACEITA pelo banco, e a chamada dela avaliava presenca em
  ;; `ocorrido_em <= NULL` = zero linhas: a Casa inteira ausente, quorum zero, servido como 200 OK. A
  ;; derivacao agora falha fechada (unit) e o banco deixou de aceitar a linha (migration desta revisao).
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [{sid :id} (sessao/agendar! tx {:id (random-uuid) :ente-id ente
                                             :sessao-legislativa-id (random-uuid)
                                             :tipo-sessao "ordinaria"})]
          (is (thrown? org.postgresql.util.PSQLException
                       (jdbc/execute-one! tx ["UPDATE sessoes.sessao SET estado = 'arquivada'
                                               WHERE ente_id = ? AND id = ?" ente sid]))
              "estado 'arquivada' com encerrada_em NULL tem de ser RECUSADO pelo banco"))))))
