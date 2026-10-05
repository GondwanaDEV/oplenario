(ns oplenario.limite-de-taxa
  "Limite de tentativas por chave numa janela deslizante (ADR-0025). Primeiro uso: a entrada pelo CPF
  (`POST /auth/localizar`), por IP — quem tenta descobrir em massa quais CPFs tem acesso a quais Casas esbarra aqui.

  Em MEMORIA da instancia, de proposito: hoje o backend roda uma instancia so'. Com mais de uma, cada uma conta a sua
  janela (o teto efetivo multiplica pelo numero de instancias) — a troca para o Valkey e' a mesma interface.
  A tentativa recusada NAO entra na conta: quem esta' bloqueado volta assim que a mais antiga sai da janela."
  (:require [io.pedestal.interceptor.chain :as chain]
            [oplenario.http :as http]))

(set! *warn-on-reflection* true)

(defn novo
  "`maximo` tentativas por chave em `janela-ms`. `max-chaves` = a partir de quantas chaves a memoria e' varrida (as
  chaves cujas tentativas ja' sairam da janela sao descartadas)."
  [{:keys [maximo janela-ms max-chaves] :or {max-chaves 10000}}]
  {:pre [(pos-int? maximo) (pos-int? janela-ms)]}
  {:maximo maximo :janela-ms janela-ms :max-chaves max-chaves :estado (atom {})})

(defn- podar [m corte]
  (into {} (filter (fn [[_ ts]] (some #(> % corte) ts))) m))

(defn tentar!
  "Conta uma tentativa de `chave` no instante `agora-ms`. -> {:permitido? bool :espera-ms n} (`espera-ms` = quanto
  falta para abrir uma vaga, 0 quando permitido)."
  [{:keys [maximo janela-ms max-chaves estado]} chave agora-ms]
  (locking estado
    (let [corte (- agora-ms janela-ms)
          ts (filterv #(> % corte) (get @estado chave))
          ok? (< (count ts) maximo)]
      (swap! estado (fn [m] (-> (if (>= (count m) max-chaves) (podar m corte) m)
                                (assoc chave (if ok? (conj ts agora-ms) ts)))))
      {:permitido? ok? :espera-ms (if ok? 0 (- (+ (first ts) janela-ms) agora-ms))})))

(defn interceptor
  "Pedestal: recusa com 429 (e `Retry-After` em segundos) quando a chave do pedido passou do limite. `chave-de` =
  (fn [request] -> chave); chave nil vira um balde comum (fail-closed: sem IP nao se ganha cota propria)."
  [limitador {:keys [nome chave-de mensagem agora-ms] :or {agora-ms #(System/currentTimeMillis)}}]
  {:name (or nome ::limite)
   :enter (fn [ctx]
            (let [{:keys [permitido? espera-ms]} (tentar! limitador (or (chave-de (:request ctx)) ::sem-chave) (agora-ms))]
              (if permitido?
                ctx
                (chain/terminate
                 (assoc ctx :response
                        (assoc-in (http/json-resposta 429 {:erro mensagem})
                                  [:headers "Retry-After"] (str (max 1 (long (Math/ceil (/ espera-ms 1000.0)))))))))))})
