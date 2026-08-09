(ns oplenario.sessoes.justificativa-db-test
  "INTEGRACAO (PG real) — Etapa 2 da CHAMADA, fatia 2b: a PORTA DA JUSTIFICATIVA DE AUSENCIA (§22.6 eixo C).

  A tabela `sessoes.justificativa_ausencia`, a maquina de estados (`logic/transicoes-justificativa`) e os tres
  metodos do protocolo (`criar-justificativa!` / `buscar-justificativa` / `decidir-justificativa!`) existiam
  desde F4.3a e NUNCA tiveram rota: o dominio estava pronto e inalcancavel. Esta fatia abre a porta; este ns
  prova o lado do DOMINIO dela contra o banco de verdade (o lado da BORDA, com fake, vive em
  `justificativa-http-in-test`).

  Por que integracao e nao unit — tres razoes que so' o banco decide:
    1. a UNICIDADE por (sessao, vereador) e' um UNIQUE da mig 0029; checar-e-inserir sem ele e' uma CORRIDA,
       nao uma garantia, e so' um teste contra o Postgres prova que a segunda tentativa nao passa;
    2. o CAS por `lock_version` + o trigger `trg_justificativa_ausencia_terminal` sao mecanica de linha;
    3. o CRUZADO com a leitura da Etapa 1: aprovar a justificativa tem de mudar o que
       `controllers/chamada-da-sessao` publica para AQUELE vereador — e' o que prova que a porta nova
       conversa com a chamada que ja existia, em vez de escrever numa tabela que ninguem le'.

  LGPD (§22.5 / F6): `motivo` pode carregar DADO DE SAUDE ('internacao', 'cirurgia'). O ultimo deftest deste
  ns e' um GUARD: varre o `shared.outbox` do ente e exige que NENHUM payload contenha o texto do motivo. O bus
  aqui e' o REAL (`outbox/bus`) exatamente para esse guard ter o que varrer — com bus nil ele seria vacuo."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [next.jdbc :as jdbc]
            [oplenario.cadastros.components.repositorio :as repo-cadastros]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.outbox :as outbox]
            [oplenario.kernel.tempo :as tempo]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.migracao :as migracao]
            [oplenario.sessoes.components.repositorio :as repo-sessoes]
            [oplenario.sessoes.controllers :as controllers]
            [oplenario.sessoes.db.presenca :as presenca]
            [oplenario.sessoes.db.sessao :as sessao])
  (:import (java.time Instant LocalDate)))

(def ^:dynamic *ds* nil)
(def ^:dynamic *repo-s* nil)
(def ^:dynamic *repo-c* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c)
                *repo-s* (repo-sessoes/->RepoSessoesPg c (outbox/bus))
                *repo-c* (repo-cadastros/->RepoCadastrosPg c)]
        (try (t) (finally (component/stop c)))))))

;; ---------- seeds: os PRODUTORES REAIS dos dois modulos, nunca INSERT redigitado ----------

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

(def ^:private t0    (Instant/parse "2026-06-20T13:00:00Z"))
(def ^:private agora (Instant/parse "2026-06-20T14:00:00Z"))

(defn- sessao! [ente]
  (repo-sessoes/transacao *repo-s* ente
    (fn [tx]
      (:id (sessao/agendar! tx {:id (random-uuid) :ente-id ente :sessao-legislativa-id (random-uuid)
                                :tipo-sessao "ordinaria" :agendada-para t0})))))

(defn- ev! [ente sid vereador-id]
  (repo-sessoes/transacao *repo-s* ente
    (fn [tx]
      (presenca/registrar-evento! tx {:id (random-uuid) :ente-id ente :sessao-id sid :vereador-id vereador-id
                                      :tipo "entrada" :modalidade "plenario" :fonte "manual_secretaria"
                                      :ocorrido-em t0}))))

(defn- roster-seam [] (fn [ente-id data] (repo-cadastros/roster-da-casa *repo-c* ente-id data)))
(defn- ator [ente] {:ente-id ente :identidade-id (random-uuid)})
(def ^:private sem-vereador (constantly nil))

(def ^:private motivo-sensivel
  "Motivo com DADO DE SAUDE de proposito: e' esta string que o guard de vazamento procura no outbox."
  "Internacao hospitalar para cirurgia cardiaca")

(defn- abrir! [ente sid vereador-id motivo]
  (controllers/abrir-justificativa *repo-s* (roster-seam) (ator ente)
    {:sessao-id sid :vereador-id vereador-id :motivo motivo}))

(defn- conflito
  "Roda `f`, exige que ela lance `:conflito/justificativa` e devolve a ex-data. Falha (em vez de devolver nil)
  se nada for lancado — senao a guarda que sumiu passaria como verde."
  [f]
  (try
    (f)
    (is false "esperava recusa :conflito/justificativa, mas a escrita passou")
    nil
    (catch clojure.lang.ExceptionInfo e
      (is (= :conflito/justificativa (:tipo (ex-data e)))
          (str "tag de conflito errada: " (pr-str (ex-data e))))
      (ex-data e))))

(defn- linhas-de-justificativa [ente sid]
  (tenancy/com-tenant* *ds* ente
    (fn [tx]
      (:n (jdbc/execute-one! tx ["SELECT count(*) AS n FROM sessoes.justificativa_ausencia
                                  WHERE ente_id = ? AND sessao_id = ?" ente sid])))))

;; ---------- J1: abrir cria o ato 'pendente' ----------

(deftest j1-abrir-justificativa-cria-pendente
  (let [ente (random-uuid)
        leg  (casa! ente)
        v    (vereador-com-mandato! ente leg "Ana")
        sid  (sessao! ente)
        r    (abrir! ente sid v motivo-sensivel)]
    (is (some? (:id r)) "o recibo carrega o id do ato criado")
    (is (= "pendente" (:estado r)) "nasce PENDENTE — quem abre nao decide")
    (is (= 0 (:lock-version r))
        "o recibo ja' entrega o token de CAS: a Mesa decide sem uma segunda leitura")
    (let [linha (repo-sessoes/buscar-justificativa *repo-s* ente (:id r))]
      (is (= "pendente" (:estado linha)) "a LINHA no banco esta pendente (nao so' o recibo)")
      (is (= v (:vereador-id linha)))
      (is (= sid (:sessao-id linha)))
      (is (= motivo-sensivel (:motivo linha)))
      (is (nil? (:decidido-por linha)) "pendente nao tem decisor (CHECK justificativa_decisao_coerente)")
      (is (nil? (:decidido-em linha))))))

;; ---------- a justificativa e' de MEMBRO da Casa: uuid sem assento na data e' recusado ----------

(deftest abrir-justificativa-de-quem-nao-compoe-a-casa-e-recusado
  (let [ente (random-uuid)
        leg  (casa! ente)
        _v   (vereador-com-mandato! ente leg "Ana")
        sid  (sessao! ente)
        forasteiro (random-uuid)
        d    (conflito #(abrir! ente sid forasteiro "Viagem"))]
    (is (= :sem-assento (:motivo d))
        "a recusa diz QUAL limite foi violado (a mensagem da borda sai daqui)")
    (is (zero? (linhas-de-justificativa ente sid))
        "e nao grava: uma justificativa de quem nao tem cadeira e' dado morto que a chamada nunca le'")))

;; ---------- J3: a segunda justificativa nao passa, e a primeira fica INTACTA ----------

(deftest j3-segunda-justificativa-para-o-mesmo-vereador-e-recusada
  (let [ente (random-uuid)
        leg  (casa! ente)
        v    (vereador-com-mandato! ente leg "Ana")
        sid  (sessao! ente)
        primeira (abrir! ente sid v motivo-sensivel)
        d    (conflito #(abrir! ente sid v "Motivo totalmente outro"))]
    (is (= (:id primeira) (:justificativa-id d))
        "o 409 e' ACIONAVEL: aponta a justificativa que ja' existe, em vez de so' dizer 'conflito'")
    (is (= 1 (linhas-de-justificativa ente sid)) "nao criou a segunda linha")
    (let [linha (repo-sessoes/buscar-justificativa *repo-s* ente (:id primeira))]
      (is (= motivo-sensivel (:motivo linha))
          "a PRIMEIRA fica intacta — sobrescrever apagaria a trilha do que foi alegado antes")
      (is (= "pendente" (:estado linha))))))

;; ---------- J5 (CRUZADO): aprovar muda o que a CHAMADA da Etapa 1 publica ----------

(deftest j5-aprovar-justificativa-muda-a-chamada-para-ausente-justificado
  (let [ente (random-uuid)
        leg  (casa! ente)
        presente (vereador-com-mandato! ente leg "Ana")
        faltou   (vereador-com-mandato! ente leg "Bruno")
        sid  (sessao! ente)
        _    (ev! ente sid presente)
        j    (abrir! ente sid faltou motivo-sensivel)
        antes (controllers/chamada-da-sessao *repo-s* (roster-seam) (ator ente) sid
                                             (tempo/relogio-fixo agora))
        por-ver-antes (into {} (map (juxt :vereador-id identity)) (:linhas antes))]
    (is (= :ausente-justificativa-pendente (:estado (por-ver-antes faltou)))
        "antes da decisao a chamada NAO diz 'ausente' — a Mesa ainda nao apreciou")
    (let [d (controllers/decidir-justificativa *repo-s* sem-vereador (ator ente)
              {:sessao-id sid :justificativa-id (:id j) :estado "aprovada" :lock-version 0})
          depois (controllers/chamada-da-sessao *repo-s* (roster-seam) (ator ente) sid
                                                (tempo/relogio-fixo agora))
          por-ver (into {} (map (juxt :vereador-id identity)) (:linhas depois))]
      (is (= {:justificativa-id (:id j) :de "pendente" :para "aprovada"} d)
          "o recibo da decisao carrega o par de/para (espelha o recibo de transicao da sessao)")
      (is (= :ausente-justificado (:estado (por-ver faltou)))
          "A PORTA NOVA CONVERSA COM A LEITURA QUE JA EXISTIA — e' este assert que prova a fatia")
      (is (= :presente-plenario (:estado (por-ver presente))) "quem estava presente nao mudou")
      (is (= 1 (:presentes-plenario (:quorum depois)))
          "a justificativa aprovada nao entra no NUMERADOR (ausente justificado nao e' presente)")
      (is (= 2 (:membros-da-casa (:quorum depois)))
          "nem sai do denominador: a cadeira continua existindo"))))

;; ---------- J6: CAS — lock-version divergente nao decide ----------

(deftest j6-decisao-com-lock-version-divergente-e-recusada
  (let [ente (random-uuid)
        leg  (casa! ente)
        v    (vereador-com-mandato! ente leg "Ana")
        sid  (sessao! ente)
        j    (abrir! ente sid v motivo-sensivel)
        _d   (conflito #(controllers/decidir-justificativa *repo-s* sem-vereador (ator ente)
                          {:sessao-id sid :justificativa-id (:id j) :estado "aprovada" :lock-version 7}))]
    (is (= "pendente" (:estado (repo-sessoes/buscar-justificativa *repo-s* ente (:id j))))
        "o estado NAO muda — um 409 que ainda assim escreve e' pior que nenhum CAS")))

;; ---------- J7: terminal nao sai (a maquina ja existia; a porta nao pode contorna-la) ----------

(deftest j7-decidir-justificativa-ja-terminal-e-recusado
  (let [ente (random-uuid)
        leg  (casa! ente)
        v    (vereador-com-mandato! ente leg "Ana")
        sid  (sessao! ente)
        j    (abrir! ente sid v motivo-sensivel)
        _    (controllers/decidir-justificativa *repo-s* sem-vereador (ator ente)
               {:sessao-id sid :justificativa-id (:id j) :estado "indeferida" :lock-version 0})
        _d   (conflito #(controllers/decidir-justificativa *repo-s* sem-vereador (ator ente)
                          {:sessao-id sid :justificativa-id (:id j) :estado "aprovada" :lock-version 1}))]
    (is (= "indeferida" (:estado (repo-sessoes/buscar-justificativa *repo-s* ente (:id j))))
        "indeferida CONGELA (trg_justificativa_ausencia_terminal); reverter e' outro ato, nao um PATCH")))

;; ---------- J8: ninguem e' juiz em causa propria ----------

(deftest j8-vereador-nao-decide-a-propria-justificativa
  (let [ente (random-uuid)
        leg  (casa! ente)
        v    (vereador-com-mandato! ente leg "Ana")
        sid  (sessao! ente)
        j    (abrir! ente sid v motivo-sensivel)
        eu   (ator ente)
        ;; o seam identidade->vereador resolve o ATOR como sendo o proprio vereador da justificativa
        sou-eu (fn [_ente _identidade] v)
        negado (try
                 (controllers/decidir-justificativa *repo-s* sou-eu eu
                   {:sessao-id sid :justificativa-id (:id j) :estado "aprovada" :lock-version 0})
                 (is false "esperava negacao de autorizacao, mas a decisao passou")
                 nil
                 (catch clojure.lang.ExceptionInfo e (ex-data e)))]
    (is (= :autorizacao/negado (:tipo negado)) "e' 403 (impedimento), nao 409 nem 400")
    (is (= "pendente" (:estado (repo-sessoes/buscar-justificativa *repo-s* ente (:id j))))
        "e a falta continua sob juizo da Mesa")))

;; ---------- J9: multi-tenant — a Casa A nao alcanca o ato da Casa B ----------

(deftest j9-ator-de-outra-casa-nao-alcanca-a-justificativa
  (let [ente-b (random-uuid)
        leg    (casa! ente-b)
        v      (vereador-com-mandato! ente-b leg "Ana")
        sid    (sessao! ente-b)
        j      (abrir! ente-b sid v motivo-sensivel)
        ente-a (random-uuid)]
    (is (nil? (controllers/decidir-justificativa *repo-s* sem-vereador (ator ente-a)
                {:sessao-id sid :justificativa-id (:id j) :estado "aprovada" :lock-version 0}))
        "nil de retorno (-> 404 na borda): a Casa A nao aprende sequer que o ato existe")
    (is (nil? (controllers/justificativas-da-sessao *repo-s* (ator ente-a) sid)))
    (is (= "pendente" (:estado (repo-sessoes/buscar-justificativa *repo-s* ente-b (:id j))))
        "e nada mudou do outro lado")))

;; ---------- a decisao e' sobre justificativa DESTA sessao (anti confused-deputy) ----------

(deftest decidir-justificativa-de-outra-sessao-da-mesma-casa-e-404
  (let [ente (random-uuid)
        leg  (casa! ente)
        v    (vereador-com-mandato! ente leg "Ana")
        sid-a (sessao! ente)
        sid-b (sessao! ente)
        j    (abrir! ente sid-a v motivo-sensivel)]
    (is (nil? (controllers/decidir-justificativa *repo-s* sem-vereador (ator ente)
                {:sessao-id sid-b :justificativa-id (:id j) :estado "aprovada" :lock-version 0}))
        "a URL da sessao B nao decide o ato da sessao A (espelha o guard fala.sessao-id da tribuna)")
    (is (= "pendente" (:estado (repo-sessoes/buscar-justificativa *repo-s* ente (:id j)))))))

;; ---------- a listagem ----------

(deftest listar-justificativas-da-sessao-entrega-o-token-de-cas
  (let [ente (random-uuid)
        leg  (casa! ente)
        a    (vereador-com-mandato! ente leg "Ana")
        b    (vereador-com-mandato! ente leg "Bruno")
        sid  (sessao! ente)
        ja   (abrir! ente sid a motivo-sensivel)
        _jb  (abrir! ente sid b "Compromisso institucional em Brasilia")
        _    (controllers/decidir-justificativa *repo-s* sem-vereador (ator ente)
               {:sessao-id sid :justificativa-id (:id ja) :estado "aprovada" :lock-version 0})
        r    (controllers/justificativas-da-sessao *repo-s* (ator ente) sid)
        por-ver (into {} (map (juxt :vereador-id identity)) (:justificativas r))]
    (is (= sid (:sessao-id r)))
    (is (= 2 (count (:justificativas r))))
    (is (= "aprovada" (:estado (por-ver a))))
    (is (= 1 (:lock-version (por-ver a)))
        "o lock sobe com a decisao — a lista devolve o valor CORRENTE, nao o do INSERT")
    (is (some? (:decidido-por (por-ver a))) "e carimba quem decidiu")
    (is (= "pendente" (:estado (por-ver b))))
    (is (= 0 (:lock-version (por-ver b))))))

;; ---------- J10 (GUARD LGPD): `motivo` nao vaza para o outbox ----------

(defn- payloads-do-ente [ente]
  (map :payload (jdbc/execute! *ds* ["SELECT tipo, payload::text AS payload FROM shared.outbox
                                      WHERE ente_id = ? ORDER BY id" ente])))

(deftest j10-motivo-da-justificativa-nunca-entra-em-payload-de-evento
  (let [ente (random-uuid)
        leg  (casa! ente)
        v    (vereador-com-mandato! ente leg "Ana")
        sid  (sessao! ente)
        ;; um evento de presenca de OUTRO vereador garante que o outbox NAO esta vazio: um guard que varre
        ;; zero linhas passaria por acidente e nao provaria nada.
        outro (vereador-com-mandato! ente leg "Bruno")
        _     (controllers/registrar-presenca *repo-s* (fn [_e _d] [{:vereador-id outro}]) (ator ente)
                {:sessao-id sid :vereador-id outro :tipo "entrada" :modalidade "plenario" :ocorrido-em t0}
                agora)
        j     (abrir! ente sid v motivo-sensivel)
        _     (controllers/decidir-justificativa *repo-s* sem-vereador (ator ente)
                {:sessao-id sid :justificativa-id (:id j) :estado "aprovada" :lock-version 0})
        ps    (payloads-do-ente ente)]
    (is (seq ps) "o outbox TEM linhas (senao este guard seria vacuo)")
    (is (not-any? #(str/includes? % motivo-sensivel) ps)
        (str "motivo de justificativa (dado de saude, LGPD) vazou para o outbox — o relay o entrega a "
             "consumidores de outros modulos, inclusive transparencia (PUBLICO). Payloads: " (pr-str ps)))
    (is (not-any? #(str/includes? % (str (:id j))) ps)
        "nem o id do ato: hoje esta fatia nao emite evento de justificativa (decisao registrada no commit)")))
