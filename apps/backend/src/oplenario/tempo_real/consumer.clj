(ns oplenario.tempo-real.consumer
  "CORACAO do tempo real (§22.6 eixo G): registra consumidores no bus (outbox) que PROJETAM os eventos de
  dominio em mensagens de canal e as PUBLICAM na CanalStore. 'SSE e' projecao do bus interno' — o relay
  (sistema.clj) drena o shared.outbox e despacha a estes handlers. O handler roda na tx do relay
  (effectively-once via dedup do inbox por (consumidor, idempotency-key))."
  (:require [oplenario.kernel.outbox :as outbox]
            [oplenario.tempo-real.canais :as canais]
            [oplenario.tempo-real.components :as comp]
            [oplenario.tempo-real.projecao :as projecao]))

(def ^:private nome-consumidor "tempo-real-sse")

(def tipos-consumidos
  "Tipos consumidos pelo projetor SSE — DERIVADOS de canais/tipos-plenario (fonte unica; evita drift entre o
  roteamento e o registro no bus). gravacao.segmento-captado fica de fora (nao esta no set; fronteira core->IA)."
  (vec canais/tipos-plenario))

(defn- handler
  "Handler do bus (fn [tx evento]): projeta o evento e publica cada mensagem na CanalStore. Ignora a `tx`
  (a CanalStore nao e' transacional com o Postgres do relay). Semantica AT-LEAST-ONCE: se a tx do relay
  reverter APOS o handler rodar (falha no UPDATE/commit), o evento e' redrenado e a mensagem reaparece com
  uma seq NOVA (duplicata por seq distinta) — G3 deve tolerar isso no resume por Last-Event-ID."
  [canal-store]
  (fn [_tx evento]
    (doseq [{:keys [canal] :as msg} (projecao/projetar evento)]
      (comp/publicar! canal-store canal (dissoc msg :canal)))))

(defn registro
  "Constroi o registro de consumidores do bus (outbox) p/ o projetor SSE: o mesmo handler por tipo de evento
  consumido, todos publicando na `canal-store`. Passado ao relay em sistema.clj."
  [canal-store]
  (let [h (handler canal-store)]
    (reduce (fn [reg tipo] (outbox/registrar reg nome-consumidor tipo h))
            {} tipos-consumidos)))
