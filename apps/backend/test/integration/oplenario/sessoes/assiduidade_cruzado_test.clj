(ns oplenario.sessoes.assiduidade-cruzado-test
  "INTEGRACAO (PG real) — o CRUZADO mais importante da Etapa 6 fatia 2: para UMA sessao do periodo, a linha
  que `controllers/apurar-assiduidade` devolve e' IDENTICA ao que `controllers/chamada-da-sessao` devolve
  para a MESMA sessao (mesmo estado por vereador, mesmo quorum). Se divergirem, a Etapa 6 criou a SEGUNDA
  aritmetica de presenca que o I1 do brief proibe.

  Mais: multi-tenant fail-closed (I6), sessao sem NENHUM evento (todas as linhas ausentes, distinguivel de
  'nao perguntei'), e as duas provas de mutacao (I4 colapsado, instante GLOBAL) ficam documentadas aqui como
  comentario — a prova em si roda manualmente (mutar, rodar, reverter) e nao fica commitada como teste."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [oplenario.cadastros.components.repositorio :as repo-cadastros]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.tempo :as tempo]
            [oplenario.migracao :as migracao]
            [oplenario.sessoes.components.repositorio :as repo-sessoes]
            [oplenario.sessoes.controllers :as controllers]
            [oplenario.sessoes.db.presenca :as presenca]
            [oplenario.sessoes.db.sessao :as sessao])
  (:import (java.time Duration Instant LocalDate)))

(def ^:dynamic *repo-s* nil)
(def ^:dynamic *repo-c* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*repo-s* (repo-sessoes/->RepoSessoesPg c nil)
                *repo-c* (repo-cadastros/->RepoCadastrosPg c)]
        (try (t) (finally (component/stop c)))))))

;; ---------- seeds ----------

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
       :partido "PX" :vigencia-inicio (LocalDate/of 2020 1 1) :vigencia-fim nil})
    id))

(defn- licenciar! [ente vereador-id inicio]
  (repo-cadastros/registrar-licenca! *repo-c* ente vereador-id
    {:id (random-uuid) :ente-id ente :inicio inicio :fim nil :motivo "Tratamento de saude"}
    inicio))

(defn- ev! [tx ente sid vid tipo modalidade quando]
  (presenca/registrar-evento! tx {:id (random-uuid) :ente-id ente :sessao-id sid :vereador-id vid
                                  :tipo tipo :modalidade modalidade :fonte "manual_secretaria"
                                  :ocorrido-em quando}))

(defn- sessao-encerrada-hoje! [ente leg tipo]
  (repo-sessoes/transacao *repo-s* ente
    (fn [tx]
      (let [{:keys [id]} (sessao/agendar! tx {:id (random-uuid) :ente-id ente :sessao-legislativa-id leg
                                              :tipo-sessao tipo :agendada-para (Instant/now)})]
        (sessao/transicionar! tx {:id id :ente-id ente :para "aberta" :lock-version 0})
        (sessao/transicionar! tx {:id id :ente-id ente :para "encerrada" :lock-version 1})
        id))))

(defn- roster-seam [] (fn [ente-id data] (repo-cadastros/roster-da-casa *repo-c* ente-id data)))
(defn- roster-seam-lote [] (fn [ente-id datas] (repo-cadastros/roster-da-casa-em-datas *repo-c* ente-id datas)))
(defn- ator [ente] {:ente-id ente :identidade-id (random-uuid)})
(defn- ator-secretario [ente] {:ente-id ente :identidade-id (random-uuid) :papeis #{"secretario"}})

;; ---------- O CRUZADO ----------

(deftest apuracao-de-uma-sessao-e-identica-a-chamada-nominal
  (let [ente (random-uuid) leg (casa! ente)
        ana (vereador-com-mandato! ente leg "Ana")
        bruno (vereador-com-mandato! ente leg "Bruno")
        lic (vereador-com-mandato! ente leg "Carla")
        sid (sessao-encerrada-hoje! ente leg "ordinaria")
        encerrada-em (:encerrada-em (repo-sessoes/buscar-sessao *repo-s* ente sid))]
    (repo-sessoes/transacao *repo-s* ente
      (fn [tx]
        (ev! tx ente sid ana "entrada" "plenario" (.minusSeconds encerrada-em 100))
        (ev! tx ente sid lic "entrada" "plenario" (.minusSeconds encerrada-em 90))
        (presenca/criar-justificativa! tx {:id (random-uuid) :ente-id ente :sessao-id sid
                                           :vereador-id bruno :motivo "doenca"})))
    (licenciar! ente lic (LocalDate/of 2025 1 1))
    (let [hoje (tempo/hoje-de encerrada-em tempo/zona-civil-padrao)
          chamada (controllers/chamada-da-sessao *repo-s* (roster-seam) (ator ente) sid
                                                 (tempo/relogio-fixo encerrada-em))
          apuracao (controllers/apurar-assiduidade *repo-s* (roster-seam-lote) (ator-secretario ente)
                     {:de hoje :ate hoje})
          por-ver-chamada (into {} (map (juxt :vereador-id :estado)) (:linhas chamada))
          detalhe-sid (into {} (comp (filter #(= sid (:sessao-id %))) (map (juxt :vereador-id :estado)))
                            (:detalhe apuracao))
          sessao-out (first (filter #(= sid (:id %)) (:sessoes apuracao)))]
      (is (= 3 (count por-ver-chamada)) "sanidade: 3 vereadores na chamada")
      (is (= por-ver-chamada detalhe-sid)
          "MESMO estado por vereador nas duas leituras — I1: uma so' aritmetica de presenca")
      (is (= (:quorum chamada) (:quorum sessao-out))
          "MESMO quorum (incl. :membros-da-casa, o denominador) nas duas leituras")
      (is (true? (:inconsistencia-cadastro
                  (first (filter #(= lic (:vereador-id %)) (:linhas chamada))))))
      (is (= :presente-plenario (por-ver-chamada lic))
          "sanidade: a licenciada COM evento positivo aparece PRESENTE nos dois lados"))))

;; ---------- I5 atraves do CONTROLLER: o instante e' por-sessao, nao um global do periodo ----------

(deftest apuracao-usa-o-instante-de-cada-sessao-nao-um-global-do-periodo
  ;; Diferente de `assiduidade-db-test/instante-e-por-sessao-nao-um-global-do-lote` (que exercita
  ;; `db/presenca/presencas-correntes-das-sessoes` direto): este teste atravessa `controllers/apurar-
  ;; assiduidade` -> `repo/leituras-assiduidade` inteiro, o UNICO caminho que de fato monta os pares
  ;; [sessao-id instante] a partir de VARIAS sessoes — e' o ponto onde um instante GLOBAL (ex.: o maximo)
  ;; poderia substituir silenciosamente o per-sessao sem nenhum teste de UMA sessao so' notar.
  (let [ente (random-uuid) leg (casa! ente)
        tardio (vereador-com-mandato! ente leg "Tardio")
        s1 (sessao-encerrada-hoje! ente leg "ordinaria")
        t1 (:encerrada-em (repo-sessoes/buscar-sessao *repo-s* ente s1))
        s2 (sessao-encerrada-hoje! ente leg "extraordinaria")
        t2 (:encerrada-em (repo-sessoes/buscar-sessao *repo-s* ente s2))]
    (is (.isBefore ^Instant t1 ^Instant t2) "sanidade: s1 fechou antes de s2")
    ;; evento NA SESSAO s1, ocorrido no MEIO do caminho entre t1 e t2 (nunca um deslocamento fixo — a janela
    ;; real entre duas transacoes sequenciais pode ser menor que qualquer offset fixo) — DEPOIS do fechamento
    ;; de s1, ANTES do de s2. Um instante GLOBAL (o maximo, t2) faria este evento CONTAR para s1; o instante
    ;; POR SESSAO (t1) tem de exclui-lo.
    (let [meio (.plus ^Instant t1 (.dividedBy (Duration/between t1 t2) 2))]
      (repo-sessoes/transacao *repo-s* ente (fn [tx] (ev! tx ente s1 tardio "entrada" "plenario" meio))))
    (let [hoje (tempo/hoje-de t2 tempo/zona-civil-padrao)
          apuracao (controllers/apurar-assiduidade *repo-s* (roster-seam-lote) (ator-secretario ente)
                     {:de hoje :ate hoje})
          estado-em (fn [sid] (:estado (first (filter #(and (= sid (:sessao-id %)) (= tardio (:vereador-id %)))
                                                       (:detalhe apuracao)))))]
      (is (= :ausente (estado-em s1))
          "o evento pos-fechamento de s1 NAO pode contar para s1 — um instante global contaria")
      (is (= :ausente (estado-em s2)) "s2 nunca teve evento nenhum de `tardio`"))))

;; ---------- multi-tenant ----------

(deftest multi-tenant-fail-closed-casa-a-nao-ve-sessao-da-casa-b
  (let [ente-a (random-uuid) ente-b (random-uuid)
        leg-b (casa! ente-b)
        _ (casa! ente-a)
        _sid-b (sessao-encerrada-hoje! ente-b leg-b "ordinaria")
        hoje (tempo/hoje (tempo/relogio-sistema) tempo/zona-civil-padrao)
        apuracao-a (controllers/apurar-assiduidade *repo-s* (roster-seam-lote) (ator-secretario ente-a)
                     {:de hoje :ate hoje})]
    (is (empty? (:sessoes apuracao-a)) "a Casa A nao ve NENHUMA sessao — todas as fechadas de hoje sao da Casa B")))

;; ---------- sessao sem nenhum evento ----------

(deftest sessao-sem-nenhum-evento-todas-as-linhas-ausentes-e-distinguivel-de-nao-perguntei
  (let [ente (random-uuid) leg (casa! ente)
        v (vereador-com-mandato! ente leg "Sozinho")
        sid (sessao-encerrada-hoje! ente leg "ordinaria")
        hoje (tempo/hoje-de (:encerrada-em (repo-sessoes/buscar-sessao *repo-s* ente sid)) tempo/zona-civil-padrao)
        apuracao (controllers/apurar-assiduidade *repo-s* (roster-seam-lote) (ator-secretario ente)
                   {:de hoje :ate hoje})
        detalhe-sid (filter #(= sid (:sessao-id %)) (:detalhe apuracao))]
    (is (= 1 (count detalhe-sid)) "a linha do UNICO vereador da Casa aparece — nao ha' zero linhas")
    (is (= :ausente (:estado (first detalhe-sid))))
    (is (= v (:vereador-id (first detalhe-sid))))))

;; ---------- authz fail-closed ----------

(deftest sem-papel-secretario-e-negado
  (let [ente (random-uuid) _leg (casa! ente)
        hoje (tempo/hoje (tempo/relogio-sistema) tempo/zona-civil-padrao)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"autorizacao negada"
          (controllers/apurar-assiduidade *repo-s* (roster-seam-lote) (ator ente) {:de hoje :ate hoje}))
        "ator sem papel 'secretario' e' negado ANTES de qualquer leitura")))

;; ---------- PROVA DE MUTACAO (manual — nao roda no CI) ----------
;; (a) `:ausente-justificativa-pendente` colapsado em `:ausente`: em `logic/apurar-assiduidade`, trocar a
;;     construcao de `detalhe-out` para `(if (= :ausente-justificativa-pendente (:estado linha)) :ausente
;;     (:estado linha))` — roda `assiduidade-test` inteiro; `as-quatro-classificacoes-...` fica VERMELHO
;;     ("Expected :ausente-justificativa-pendente Actual :ausente"). Reverter.
;; (b) instante GLOBAL em vez de por-sessao: em `components/repositorio.clj`/`leituras-assiduidade`, trocar
;;     `sessoes-com-instante` para usar um UNICO instante (ex.: o da ULTIMA sessao) para TODAS — roda
;;     `assiduidade-db-test`; `instante-e-por-sessao-nao-um-global-do-lote` fica VERMELHO. Reverter.
