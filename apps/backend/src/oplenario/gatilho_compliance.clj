(ns oplenario.gatilho-compliance
  "HOST (§22.10) — o GATILHO das obrigacoes legais da Casa (ADR-0021 A4/B4): a primeira regra do motor de compliance que
  roda em producao. Ate' aqui nada chamava `avaliar-obrigacao!` fora da demo e dos testes: o painel so' lia.

  AS DUAS REGRAS (regra e' DADO, Inv. 4 — o texto abaixo e' o que vai ao catalogo do motor e e' o que se avalia):
    - `audiencia_metas_fiscais` (federal, aviso, LRF art. 9 §4): a audiencia de metas fiscais do quadrimestre, provada
      pelo fato `audiencia_publica_realizada` (sessoes). A competencia e' o ULTIMO mes do quadrimestre (04, 08, 12);
      a janela e' `fim_do_mes_seguinte(competencia)`, como a ADR fixou. `[GAP]` de conteudo: para o 3o quadrimestre a
      LRF diz 'fevereiro' e a janela da' 31 de janeiro — o aviso chega um mes ANTES do prazo legal, nunca depois.
    - `julgamento_contas_prefeito` (regimento_tenant, aviso, CF art. 31 §2 + LOM): as contas do Prefeito julgadas
      (`contas_julgadas`, legislativo) ate' o prazo congelado no registro da prestacao (`prazo_julgamento_contas`).

  O CATALOGO E O VINCULO, garantidos AQUI e de forma idempotente (nao na demo, nao numa migration): na primeira vez o
  template e' tipado pelo verificador do save time (`motor/verificar-fonte`; INVALIDA nao entra) e gravado `vigente`;
  cada Casa ganha o vinculo ativo na primeira vez que o gatilho roda para ela. O vinculo existente nunca e' reescrito —
  uma Casa que se desligou da regra (opt-out com motivo) continua desligada e o gatilho a pula.

  QUANDO RODA (composto em `rotas.clj`, o modulo nao conhece o compliance):
    - ANTES de ler o painel de compliance (`GET /compliance/painel` e o card de `/paineis/mesa`) — origem `sob_demanda`;
    - LOGO DEPOIS dos atos que criam ou cumprem as obrigacoes — origem `evento`: a ata publicada de uma audiencia
      publica (`POST /sessoes/:id/ata`), o registro da prestacao (`POST /contas`) e o encerramento de votacao (o do PDL
      das contas grava o julgamento na mesma tx).
  Depois de avaliar, varre os vencimentos (a unica transicao que evento nao dispara, §22.7.7 S1).

  O QUE AVALIA, por Casa: (a) as competencias de metas fiscais dos quadrimestres JA' TERMINADOS cujo prazo e' de no
  maximo 365 dias atras (o quadrimestre que acabou e os recentes); (b) cada prestacao `governo_prefeito`. A obrigacao
  ja' cumprida (ou dispensada/cancelada) nao e' reavaliada; na leitura do painel, a obrigacao aberta avaliada ha' menos
  de `intervalo-sob-demanda-min` tambem nao — a prova append-only nao ganha uma linha a cada recarga da tela.

  FALHA NUNCA DERRUBA o ato nem a leitura: `disparar-sem-falhar!` loga e segue (o ato ja' commitou na tx dele; a
  obrigacao se acerta na proxima leitura do painel)."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [oplenario.compliance.components.repositorio :as repo-compliance]
            [oplenario.legislativo.components.repositorio-contas :as repo-contas]
            [oplenario.motor.api :as motor]
            [oplenario.motor.components.repositorio :as repo-motor]
            [oplenario.motor.nucleo :as nuc])
  (:import (java.nio.charset StandardCharsets)
           (java.time Duration Instant LocalDate YearMonth)
           (java.util UUID)
           (org.postgresql.util PSQLException)))

(set! *warn-on-reflection* true)

;; ---------------- as regras (texto DSL — o que o catalogo guarda e o motor avalia) ----------------

(def chave-metas-fiscais "audiencia_metas_fiscais")
(def chave-contas "julgamento_contas_prefeito")

(def fonte-metas-fiscais
  (str "template: " chave-metas-fiscais "\n"
       "contexto: compliance\n"
       "dominio: federal\n"
       "parametros: { competencia: Competencia }\n"
       "aplica_quando: verdadeiro\n"
       "exige: audiencia_publica_realizada(\"metas_fiscais\", competencia)\n"
       "prazo:\n"
       "  janela: fim_do_mes_seguinte(competencia)\n"
       "  a_partir_de: fim_de(competencia)\n"
       "severidade: aviso\n"
       "referencia_normativa: \"LRF art. 9 §4\"\n"))

(def fonte-contas
  (str "template: " chave-contas "\n"
       "contexto: compliance\n"
       "dominio: regimento_tenant\n"
       "parametros: { prestacao: PrestacaoContasId }\n"
       "aplica_quando: verdadeiro\n"
       "exige: contas_julgadas(prestacao)\n"
       "prazo:\n"
       "  janela: prazo_julgamento_contas(prestacao)\n"
       "  a_partir_de: data_recebimento_contas(prestacao)\n"
       "severidade: aviso\n"
       "referencia_normativa: \"CF art. 31 §2; prazo da LOM\"\n"))

(def regras
  {chave-metas-fiscais {:fonte fonte-metas-fiscais
                        :descricao "Audiência pública de avaliação das metas fiscais do quadrimestre (LRF art. 9 §4)"}
   chave-contas {:fonte fonte-contas
                 :descricao "Julgamento das contas do Prefeito pela Câmara no prazo (CF art. 31 §2 + LOM)"}})

(def objeto-competencia "competencia")
(def objeto-prestacao "prestacao_contas")

(def intervalo-sob-demanda-min
  "Na leitura do painel, a obrigacao aberta avaliada ha' menos que isto nao e' reavaliada (os atos reavaliam na hora)."
  10)

(def ^:private estados-encerrados #{"cumprida" "dispensada" "cancelada"})

;; ---------------- puro: quais competencias, que objeto ----------------

(defn- fim-de ^LocalDate [{:keys [ano mes]}] (.atEndOfMonth (YearMonth/of (int ano) (int mes))))
(defn- fim-do-mes-seguinte ^LocalDate [{:keys [ano mes]}]
  (.atEndOfMonth (.plusMonths (YearMonth/of (int ano) (int mes)) 1)))

(defn competencias-de-metas-fiscais
  "As competencias ({:ano :mes}, ultimo mes do quadrimestre: 04, 08, 12) que o gatilho avalia em `hoje`: o quadrimestre
  ja' TERMINOU (o ultimo dia dele ficou para tras) e o prazo (`fim_do_mes_seguinte`) e' de no maximo 365 dias atras.
  Em ordem cronologica."
  [^LocalDate hoje]
  (let [limite (.minusDays hoje 365)]
    (vec (for [ano (range (- (.getYear hoje) 2) (inc (.getYear hoje)))
               mes [4 8 12]
               :let [c {:ano ano :mes mes}]
               :when (and (.isBefore (fim-de c) hoje)
                          (not (.isBefore (fim-do-mes-seguinte c) limite)))]
           c))))

(defn objeto-da-competencia
  "O id do objeto sob prazo da regra de metas fiscais: DERIVADO de (Casa, regra, competencia) — nunca aleatorio, porque a
  chave de idempotencia da obrigacao e' Casa⋈regra⋈objeto. Mesmo desenho da competencia do SIM na demo."
  ^UUID [ente-id {:keys [ano mes]}]
  (UUID/nameUUIDFromBytes (.getBytes (format "%s|%s|%04d-%02d" ente-id chave-metas-fiscais (int ano) (int mes))
                                     StandardCharsets/UTF_8)))

(defn- precisa-avaliar?
  [obrigacao origem ^Instant agora]
  (cond
    (nil? obrigacao) true
    (contains? estados-encerrados (:estado obrigacao)) false
    (= "sob_demanda" origem)
    (let [em ^Instant (:atualizado-em obrigacao)]
      (or (nil? em) (neg? (.compareTo (Duration/ofMinutes intervalo-sob-demanda-min) (Duration/between em agora)))))
    :else true))

;; ---------------- o catalogo e o vinculo da Casa ----------------

(defn- sem-aspas [s] (some-> s str/trim (str/replace #"^\"|\"$" "")))

(defn- garantir-template!
  "O template `chave` vigente no catalogo do motor (cria a versao 1 se faltar — tipada antes). Devolve a linha vigente."
  [repo-m chave]
  (or (repo-motor/template-vigente repo-m chave)
      (let [{:keys [fonte descricao]} (get regras chave)
            env (nuc/carregar-envelope fonte)
            {:keys [status erros registry-versao-ref]} (motor/verificar-fonte fonte)]
        (when-not (= "VALIDA" status)
          (throw (ex-info (str "regra " chave " nao passa no verificador do motor") {:erros erros})))
        (try
          (repo-motor/criar-template! repo-m
            {:id (random-uuid) :chave-template chave :versao 1 :template-pai-id nil
             :dominio (:dominio env) :chave-dominio nil :descricao descricao :severidade (:severidade env)
             :referencia-normativa (sem-aspas (:referencia-normativa env))
             :fonte-yaml fonte :forma-compilada env :assinatura-parametros (:parametros env)
             :registry-versao-ref registry-versao-ref :estado-versao "vigente"})
          (catch PSQLException e
            ;; outra instancia gravou primeiro (UNIQUE chave+versao): segue com a dela
            (when-not (= "23505" (.getSQLState e)) (throw e))))
        (or (repo-motor/template-vigente repo-m chave)
            (throw (ex-info (str "regra " chave " sem versao vigente no catalogo") {:chave chave}))))))

(defn- vinculo-ativo?
  "A Casa esta' ligada a regra? Sem vinculo, cria o ativo (a regra vale para toda Casa); com vinculo, respeita o que la'
  esta' — o opt-out com motivo nao e' desfeito por aqui."
  [repo-m ente-id chave]
  (if-let [b (repo-motor/binding-do-ente repo-m ente-id chave)]
    (boolean (:ativa b))
    (do (repo-motor/criar-binding! repo-m ente-id {:id (random-uuid) :ente-id ente-id :template-chave chave
                                                    :ativa true :parametros-tenant {}})
        true)))

;; ---------------- o gatilho ----------------

(defn- avaliar-objeto!
  [{:keys [repo-compliance registro-fatos repo-motor]} ente-id {:keys [regra reg-ver]} objeto-tipo objeto-id amb hoje
   origem agora]
  (let [atual (->> (repo-compliance/obrigacoes-do-objeto repo-compliance ente-id objeto-tipo objeto-id)
                   (filter #(= (:template regra) (:template-chave %)))
                   first)]
    (when (precisa-avaliar? atual origem agora)
      (repo-compliance/avaliar-obrigacao! repo-compliance ente-id registro-fatos repo-motor
                                          {:regra regra :reg-ver reg-ver :objeto-tipo objeto-tipo :objeto-id objeto-id
                                           :amb amb :agora hoje :origem origem})
      true)))

(defn- regra-da-casa
  "{:regra <envelope> :reg-ver} da regra vigente, ou nil se a Casa esta' desligada dela."
  [repo-m ente-id chave]
  (let [t (garantir-template! repo-m chave)]
    (when (vinculo-ativo? repo-m ente-id chave)
      {:regra (nuc/carregar-envelope (:fonte-yaml t)) :reg-ver (:registry-versao-ref t)})))

(defn- cada!
  "Avalia cada objeto isolado: a falha de UM (dado quebrado) e' logada e nao impede os outros."
  [rotulo itens f]
  (reduce (fn [acc item]
            (try (if (f item) (update acc :avaliadas inc) acc)
                 (catch Exception e
                   (log/warn e "gatilho-compliance: falhou ao avaliar" rotulo item)
                   (update acc :falhas inc))))
          {:avaliadas 0 :falhas 0} itens))

(def partes-todas #{:metas-fiscais :contas})

(defn disparar!
  "Avalia, para a Casa `ente-id`, as obrigacoes das duas regras e varre os vencimentos. Idempotente. `deps` =
  {:repo-compliance :repo-motor :registro-fatos :repo-legislativo? :hoje (fn [] LocalDate)};
  `opts` = {:origem \"sob_demanda\"|\"evento\" :partes #{:metas-fiscais :contas}}. Sem `:repo-legislativo` a parte
  das contas nao roda. Devolve {:metas-fiscais {:avaliadas :falhas} :contas {...} :vencidas N}. Lanca em falha de
  infraestrutura (catalogo, banco) — quem compoe no request usa `disparar-sem-falhar!`."
  [{:keys [repo-motor repo-legislativo hoje] :as deps} ente-id {:keys [origem partes]
                                                                 :or {origem "evento" partes partes-todas}}]
  (let [^LocalDate dia (hoje)
        ;; o carimbo da obrigacao e' o now() do banco: compara-se com o relogio real, nunca com o do dominio
        instante (Instant/now)
        metas (when (contains? partes :metas-fiscais)
                (when-let [r (regra-da-casa repo-motor ente-id chave-metas-fiscais)]
                  (cada! chave-metas-fiscais (competencias-de-metas-fiscais dia)
                         (fn [c] (avaliar-objeto! deps ente-id r objeto-competencia (objeto-da-competencia ente-id c)
                                                  {"competencia" c} dia origem instante)))))
        contas (when (and repo-legislativo (contains? partes :contas))
                 (when-let [r (regra-da-casa repo-motor ente-id chave-contas)]
                   (cada! chave-contas
                          (->> (repo-contas/prestacoes repo-legislativo ente-id)
                               (filter #(and (= "governo_prefeito" (:tipo %)) (:prazo-julgamento-ate %)))
                               (map :id))
                          (fn [pid] (avaliar-objeto! deps ente-id r objeto-prestacao pid {"prestacao" pid}
                                                     dia origem instante)))))
        vencidas (repo-compliance/varrer-vencimentos! (:repo-compliance deps) ente-id dia)]
    {:metas-fiscais metas :contas contas :vencidas (count vencidas)}))

(defn disparar-sem-falhar!
  "`disparar!` que NUNCA lanca: loga e devolve nil. E' o que o host compoe nos atos e nas leituras."
  [deps ente-id opts]
  (when ente-id
    (try (disparar! deps ente-id opts)
         (catch Throwable e
           (log/error e "gatilho-compliance: falhou; o ato/leitura segue" {:ente-id ente-id :opts opts})
           nil))))

;; ---------------- a composicao nas rotas (table syntax Pedestal) ----------------

(defn- sucesso? [ctx] (when-let [s (get-in ctx [:response :status])] (<= 200 (long s) 299)))

(defn interceptor-antes
  "Roda o gatilho ANTES do handler (a leitura do painel ja' ve o resultado). `disparar` = (fn [ente-id opts]), a
  versao que nunca lanca."
  [disparar opts]
  {:name ::antes
   :enter (fn [ctx]
            (try (disparar (get-in ctx [:request :ator :ente-id]) opts)
                 (catch Throwable e
                   (log/error e "gatilho-compliance: falhou antes da leitura; a leitura segue" opts)))
            ctx)})

(defn interceptor-depois
  "Roda o gatilho DEPOIS do handler, so' se o ato deu certo (2xx) e `quando?` (fn [request] bool) aceitar. Nada aqui
  muda a resposta do ato: uma falha (inclusive de `quando?`) e' logada e a resposta segue intacta."
  [disparar opts quando?]
  {:name ::depois
   :leave (fn [ctx]
            (try
              (when (and (sucesso? ctx) (quando? (:request ctx)))
                (disparar (get-in ctx [:request :ator :ente-id]) opts))
              (catch Throwable e
                (log/error e "gatilho-compliance: falhou depois do ato; a resposta segue" opts)))
            ctx)})

(defn com-gatilho
  "Insere o interceptor do gatilho logo antes do handler das rotas de `casos` ({[caminho metodo] interceptor}), depois da
  autenticacao e da authz da rota (o ator ja' esta' no request). Rotas fora de `casos` passam intactas."
  [rotas casos]
  (into #{}
        (map (fn [[caminho metodo cadeia & resto :as r]]
               (if-let [i (get casos [caminho metodo])]
                 (let [v (if (vector? cadeia) cadeia [cadeia])]
                   (into [caminho metodo (conj (pop v) i (peek v))] resto))
                 r)))
        rotas))
