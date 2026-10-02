(ns oplenario.restricao-da-casa
  "HOST (§22.10) — a Casa SUSPENSA no runtime (ADR-0018, Eixos 2 e 3). O estado vem do registro de Casas
  (`admin_sistema`) por um seam com cache curto; a regra e' aplicada no interceptor de autenticacao da Casa (ver
  `interceptors/autenticacao`), o primeiro ponto em que a rota e a Casa sao conhecidas:

  - LEITURA passa sempre — o portal e' transparencia ativa da Casa (LAI art. 8) e o servidor le e exporta;
  - ESCRITA fora da allowlist recebe 423 Locked com o motivo PUBLICO (\"acesso restrito\"), nunca o comercial;
  - a allowlist e' o que a Casa nao pode deixar de fazer por nossa causa: os protocolos do cidadao (e-SIC, ouvidoria,
    LGPD, comentarios), os servidores RESPONDENDO a eles (prazo legal correndo, LAI 20+10, Lei 13.460) e a remessa ao
    TCE (o compliance segue).

  Leitura = metodo GET/HEAD/OPTIONS, ou rota que no catalogo de acoes (ADR-0009) so' aparece como `:leitura`. Escrita
  nova nasce BLOQUEADA (fail-closed): so' entra na allowlist por nome, aqui, revisada em PR — e o teste de vazamento
  da 3a dimensao (estado) percorre todas as rotas montadas.

  A Casa ENCERRADA (ADR-0018 fatia 2, Eixo 4.4 a + c) e' outra coisa: os dados dela foram apagados e nada da Casa
  responde — nem leitura. Toda rota da Casa (a pessoa, o agente) e as publicas dela (o portal `/portal/casa/:ente/...`
  e a descoberta do login) respondem 410 Gone com a data e, se a Casa informou, para onde foi o acervo publico."
  (:require [clojure.string :as str]
            [oplenario.catalogo :as catalogo]
            [oplenario.http :as http]
            [io.pedestal.interceptor.chain :as chain])
  (:import (java.time Instant)))

(set! *warn-on-reflection* true)

(def allowlist
  "Eixo 2 (c): as escritas que seguem numa Casa suspensa, por rota nomeada."
  #{;; os protocolos do cidadao — o direito de pedir nao depende do contrato da Casa
    :participacao/protocolar-esic :participacao/interpor-recurso :participacao/protocolar-manifestacao
    :participacao/solicitar-titular :participacao/comentar :participacao/denunciar-comentario
    :transparencia/seguir :transparencia/deixar-de-seguir
    ;; os servidores respondendo aos protocolos do cidadao (prazo legal correndo)
    :participacao/responder-pedido :participacao/decidir-recurso :participacao/responder-manifestacao
    :participacao/prorrogar-manifestacao :participacao/arquivar-manifestacao :participacao/responder-solicitacao
    :participacao/moderar-comentario
    ;; ler a propria caixa de notificacoes (marcar como lida nao e' ato da Casa)
    :paineis/marcar-notificacao-lida
    ;; o motor de compliance SEGUE (Eixo 2: prazo que vence em silencio e' o pior incidente, CLAUDE.md §5): a Casa
    ;; suspensa ainda valida, envia a remessa ao TCE e registra o recibo — perder a janela do TCE seria culpa nossa
    :compliance/validar-remessa :compliance/submeter-remessa :compliance/resposta-remessa
    ;; o encarregado de dados (LGPD art. 41) e' quem responde o titular: a Casa precisa poder nomea-lo
    :participacao/definir-encarregado
    ;; ADR-0018 (fatia 2): a portabilidade (9.6) — a Casa suspensa (inclusive com o encerramento em curso) gera a
    ;; exportacao completa e confirma que a recebeu; baixar e' GET e ja' passa. Sem isso o encerramento nao anda.
    :exportacao-da-casa/gerar :exportacao-da-casa/confirmar-recebimento})

;; FICA BLOQUEADO, de proposito: conceder acesso (`:identidade/conceder-acesso`, convites, papeis). Suspensa, a Casa nao
;; ganha gente nova no sistema — quem ja' tem acesso responde os protocolos; acesso novo espera a reativacao, que e' o
;; que torna a suspensao uma restricao de verdade e nao um aviso.

(def motivo-publico "acesso restrito")

(def ^:private metodos-de-leitura #{:get :head :options})

(def rotas-de-leitura
  "Rotas que o catalogo classifica SO' como `:leitura` (alguma leitura pode ser POST)."
  (let [por-rota (reduce (fn [m e] (reduce #(update %1 %2 (fnil conj #{}) (:classe e)) m (:rotas e)))
                         {} catalogo/entradas)]
    (into #{} (keep (fn [[r classes]] (when (= #{:leitura} classes) r))) por-rota)))

(defn escrita-permitida?
  "PURA: a requisicao (`metodo`, `rota`) passa numa Casa suspensa?"
  [metodo rota]
  (boolean (or (metodos-de-leitura metodo) (rotas-de-leitura rota) (allowlist rota))))

(defn restrita?
  "PURA: o estado (do seam) restringe a Casa?"
  [estado]
  (= "suspenso" (:estado estado)))

(defn resposta-423
  "O corpo da recusa: so' o motivo publico e desde quando. A marca `:auditoria` rotula o registro da trilha (a chave
  e' consumida pelo interceptor da trilha e nao vai para o fio)."
  [estado]
  (-> (http/json-resposta 423 {:erro motivo-publico :desde (some-> ^Instant (:desde estado) str)})
      (assoc :auditoria {:rotulo "recusado: Casa com acesso restrito"})))

(defn encerrada?
  "PURA: o estado (do seam) e' de uma Casa encerrada — ou cujo apagamento ja' comecou (`:apagando?`, mig 0177: dado sendo
  apagado nao recebe linha nova, nem da allowlist do cidadao, nem o registro da trilha)?"
  [estado]
  (or (= "encerrado" (:estado estado)) (boolean (:apagando? estado))))

(defn em-encerramento?
  "PURA: a Casa esta' com o encerramento em curso (suspensa para encerrar)? E' o estado que nao entra no cache: o
  apagamento pode comecar a qualquer momento e tem de fechar a Casa em todas as instancias na hora."
  [estado]
  (and (restrita? estado) (= "encerramento_em_curso" (:motivo estado))))

(defn resposta-410
  "A Casa encerrada: o nome (publico, do registro), quando e para onde foi o acervo publico (se a Casa informou).
  Sobria, sem o motivo."
  [estado]
  (http/json-resposta 410 {:erro "esta Camara nao usa mais O Plenario"
                           :nome (:nome estado)
                           :encerrada-em (some-> ^Instant (:encerrada-em estado) str)
                           :destino-acervo-url (:destino-acervo-url estado)}))

(defn- encerrar-cadeia
  "Termina com o 410 e TIRA o ator da requisicao: a trilha de auditoria da Casa foi apagada com ela, e nada novo deve
  nascer ali (sem ator de Casa, o interceptor da trilha nao grava)."
  [ctx estado]
  (chain/terminate (-> ctx (assoc :response (resposta-410 estado)) (update :request dissoc :ator))))

(defn restricao
  "A `restricao` do interceptor de autenticacao: (fn [ctx] -> ctx). `estado-da-casa` = (fn [ente-id] -> estado|nil).
  A Casa encerrada recusa tudo (410); a suspensa, a escrita fora da allowlist (423). Por isso o estado e' lido em toda
  requisicao da Casa — do cache de 30 s (`com-cache`), nao do banco."
  [estado-da-casa]
  (fn [ctx]
    (let [metodo (get-in ctx [:request :request-method])
          rota (get-in ctx [:route :route-name])
          estado (some-> (get-in ctx [:request :ator :ente-id]) estado-da-casa)]
      (cond
        (encerrada? estado) (encerrar-cadeia ctx estado)
        (escrita-permitida? metodo rota) ctx
        (restrita? estado) (chain/terminate (assoc ctx :response (resposta-423 estado)))
        :else ctx))))

(defn- ente-do-caminho [ctx]
  (let [v (get-in ctx [:request :path-params :ente])]
    (try (java.util.UUID/fromString (str v)) (catch IllegalArgumentException _ nil))))

(defn casa-encerrada-publica
  "Interceptor das rotas PUBLICAS de uma Casa (sem ator: o portal e a descoberta do login), que trazem a Casa no
  caminho (`:ente`). Casa encerrada -> 410; o resto segue para o handler (que faz o 400/404 dele)."
  [estado-da-casa]
  {:name ::casa-encerrada-publica
   :enter (fn [ctx]
            (let [estado (some-> (ente-do-caminho ctx) estado-da-casa)]
              (if (encerrada? estado) (encerrar-cadeia ctx estado) ctx)))})

(def ^:private prefixos-publicos-da-casa
  "As rotas publicas cuja Casa vem no caminho: o portal inteiro e a descoberta do login."
  ["/portal/casa/:ente" "/auth/descoberta/:ente"])

(defn rota-publica-da-casa?
  [caminho]
  (boolean (some #(or (= caminho %) (str/starts-with? caminho (str % "/"))) prefixos-publicos-da-casa)))

(defn com-casa-encerrada
  "Acrescenta `casa-encerrada-publica` na frente de toda rota publica da Casa (table syntax). Feito no HOST sobre as
  rotas montadas, nao rota a rota: a pagina nova do portal nasce coberta."
  [rotas estado-da-casa]
  (let [i (casa-encerrada-publica estado-da-casa)]
    (into #{}
          (map (fn [[caminho metodo handler & resto :as r]]
                 (if (rota-publica-da-casa? caminho)
                   (into [caminho metodo (if (vector? handler) (into [i] handler) [i handler])] resto)
                   r)))
          rotas)))

(defn com-cache
  "O seam `estado-da-casa` com cache curto por instancia (Eixo 3: 30 s). `ler` = (fn [ente-id] -> estado|nil), a
  leitura real (que tambem efetiva o preguicoso do registro). Devolve {:estado-da-casa (fn [ente-id]) :invalidar!
  (fn [ente-id])}: o console desta instancia invalida ao transicionar; as outras instancias enxergam em ate' `ttl-ms`."
  [ler ttl-ms]
  (let [cache (atom {})]
    {:estado-da-casa (fn [ente-id]
                       (let [agora (System/currentTimeMillis)
                             [expira v] (get @cache ente-id)]
                         (if (and expira (< agora (long expira)))
                           v
                           (let [v (ler ente-id)]
                             ;; a Casa com o encerramento em curso e' lida do registro a cada requisicao (rara, e o
                             ;; inicio do apagamento a fecha em TODAS as instancias na hora — ver `em-encerramento?`)
                             (if (em-encerramento? v)
                               (swap! cache dissoc ente-id)
                               (swap! cache assoc ente-id [(+ agora (long ttl-ms)) v]))
                             v))))
     :invalidar! (fn [ente-id] (swap! cache dissoc ente-id) nil)}))

(defn visao
  "O que a tela mostra na faixa \"Sistema da Camara com acesso restrito desde DD/MM\": o interno ve o motivo; o
  cidadao (e o portal) so' \"acesso restrito\". nil = Casa sem restricao."
  [estado interno?]
  (when (restrita? estado)
    (cond-> {:desde (some-> ^Instant (:desde estado) str)}
      interno? (assoc :motivo (:motivo estado)))))
