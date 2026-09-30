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
  da 3a dimensao (estado) percorre todas as rotas montadas."
  (:require [oplenario.catalogo :as catalogo]
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
    :participacao/definir-encarregado})

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

(defn restricao
  "A `restricao` do interceptor de autenticacao: (fn [ctx] -> ctx). `estado-da-casa` = (fn [ente-id] -> estado|nil)."
  [estado-da-casa]
  (fn [ctx]
    (let [metodo (get-in ctx [:request :request-method])
          rota (get-in ctx [:route :route-name])]
      (if (escrita-permitida? metodo rota)
        ctx
        (let [estado (some-> (get-in ctx [:request :ator :ente-id]) estado-da-casa)]
          (if (restrita? estado)
            (chain/terminate (assoc ctx :response (resposta-423 estado)))
            ctx))))))

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
                             (swap! cache assoc ente-id [(+ agora (long ttl-ms)) v])
                             v))))
     :invalidar! (fn [ente-id] (swap! cache dissoc ente-id) nil)}))

(defn visao
  "O que a tela mostra na faixa \"Sistema da Camara com acesso restrito desde DD/MM\": o interno ve o motivo; o
  cidadao (e o portal) so' \"acesso restrito\". nil = Casa sem restricao."
  [estado interno?]
  (when (restrita? estado)
    (cond-> {:desde (some-> ^Instant (:desde estado) str)}
      interno? (assoc :motivo (:motivo estado)))))
