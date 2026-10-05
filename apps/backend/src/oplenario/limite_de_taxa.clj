(ns oplenario.limite-de-taxa
  "Limite de tentativas por chave numa janela deslizante (ADR-0025). Primeiro uso: a entrada pelo CPF
  (`POST /auth/localizar`), por IP — quem tenta descobrir em massa quais CPFs tem acesso a quais Casas esbarra aqui.

  Em MEMORIA da instancia, de proposito: hoje o backend roda uma instancia so'. Com mais de uma, cada uma conta a sua
  janela (o teto efetivo multiplica pelo numero de instancias) e um deploy zera a conta — a troca para o Valkey e' a
  mesma interface. A tentativa recusada NAO entra na conta: quem esta' bloqueado volta assim que a mais antiga sai da
  janela.

  O estado e' um valor so' ({:chaves :podada-em :ultimo}) trocado por `swap!` com uma funcao pura (`passo`). A memoria
  e' varrida quando passa de `max-chaves` chaves, no maximo uma vez a cada decimo da janela: com muita chave viva, um
  pedido nao refaz o mapa inteiro a cada vez."
  (:require [io.pedestal.interceptor.chain :as chain]
            [oplenario.http :as http]))

(set! *warn-on-reflection* true)

(defn novo
  "`maximo` tentativas por chave em `janela-ms`. `max-chaves` = a partir de quantas chaves a memoria e' varrida (as
  chaves cujas tentativas ja' sairam da janela sao descartadas)."
  [{:keys [maximo janela-ms max-chaves] :or {max-chaves 10000}}]
  {:pre [(pos-int? maximo) (pos-int? janela-ms)]}
  {:maximo maximo :janela-ms janela-ms :max-chaves max-chaves :estado (atom {:chaves {} :podada-em 0})})

(defn- podar [chaves corte]
  (into {} (filter (fn [[_ ts]] (some #(> % corte) ts))) chaves))

(defn- passo
  "PURA: o estado depois de uma tentativa de `chave` em `agora`; o veredito vai em `:ultimo`."
  [{:keys [maximo janela-ms max-chaves]} {:keys [chaves podada-em]} chave agora]
  (let [corte (- agora janela-ms)
        podar? (and (>= (count chaves) max-chaves) (>= (- agora podada-em) (quot janela-ms 10)))
        chaves (if podar? (podar chaves corte) chaves)
        ts (filterv #(> % corte) (get chaves chave))
        ok? (< (count ts) maximo)]
    {:chaves (assoc chaves chave (if ok? (conj ts agora) ts))
     :podada-em (if podar? agora podada-em)
     :ultimo {:permitido? ok? :espera-ms (if ok? 0 (- (+ (first ts) janela-ms) agora))}}))

(defn tentar!
  "Conta uma tentativa de `chave` no instante `agora-ms`. -> {:permitido? bool :espera-ms n} (`espera-ms` = quanto
  falta para abrir uma vaga, 0 quando permitido)."
  [{:keys [estado] :as limitador} chave agora-ms]
  ;; `swap!` devolve o valor que ESTA aplicacao gravou: o `:ultimo` e' o veredito desta tentativa, mesmo concorrente
  (:ultimo (swap! estado #(passo limitador % chave agora-ms))))

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
