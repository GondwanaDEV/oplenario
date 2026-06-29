(ns seed-demo
  "Semente de DEMO (não é produto; só p/ ver o painel ao vivo end-to-end). Usa o PRÓPRIO código do projeto:
  cria uma Casa + um ator (identidade+vínculo) + uma sessão ABERTA e pública, e injeta eventos de presença/
  tribuna via os Repo — que emitem no shared.outbox; o relay do app servido (docker) os drena → projetor →
  CanalStore → SSE → browser. Rodar do host apontando p/ o Postgres em :5544 (DATABASE_URL).

  uso:
    DATABASE_URL=jdbc:postgresql://localhost:5544/oplenario MINIO_ENDPOINT=http://localhost:9100 \\
      clojure -X:dev seed-demo/base
    ... (abrir o browser na URL impressa) ...
    DATABASE_URL=... clojure -X:dev seed-demo/eventos"
  (:require [com.stuartsierra.component :as component]
            [oplenario.cadastros.db.estrutura :as estrutura]
            [oplenario.cadastros.db.referencia :as referencia]
            [oplenario.config :as config]
            [oplenario.identidade.db.identidade :as id]
            [oplenario.identidade.db.vinculo :as vinc]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.outbox :as outbox]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.sessoes.components.repositorio :as repo]
            [clojure.edn :as edn])
  (:import (java.time Instant)))

(def ids-file "/private/tmp/claude-501/-Users-daoudatraore-oplenario/fb0b8172-5838-4585-9188-536f405b4b01/scratchpad/demo-ids.edn")

(defn- dv [ds] (let [r (mod (reduce + (map * ds (range (inc (count ds)) 1 -1))) 11)] (if (< r 2) 0 (- 11 r))))
(defn- cpf-valido [] (let [b (vec (repeatedly 9 #(rand-int 10))) d1 (dv b)] (apply str (concat b [d1 (dv (conj b d1))]))))

(defn- com-ds [f]
  (let [c (component/start (datasource/datasource (config/carregar)))]
    (try (f (:ds c)) (finally (component/stop c)))))

(defn- repo-sessoes [ds] (assoc (repo/repositorio) :datasource {:ds ds} :bus (outbox/bus)))

(defn base [_]
  (com-ds
   (fn [ds]
     (let [ente (random-uuid) ident (random-uuid)
           r    (repo-sessoes ds)]
       ;; município (FK do ente) — idempotente p/ re-runs
       (try (referencia/inserir-municipio! ds {:codigo-ibge "2304400" :nome "Fortaleza" :uf "CE" :capital true :populacao 2703391})
            (catch Exception _ nil))
       ;; identidade supratenant + ente + vínculo (o ator)
       (id/inserir! ds {:id ident :cpf (cpf-valido) :nome "Secretária da Mesa"})
       (tenancy/com-tenant* ds ente
         (fn [tx]
           (estrutura/inserir-ente! tx {:ente-id ente :municipio-ibge "2304400" :nome-oficial "Câmara Municipal de Fortaleza"})
           (vinc/criar! tx {:id (random-uuid) :ente-id ente :identidade-id ident :tipo "servidor"})))
       ;; sessão ordinária (transmite-publica=true) -> abrir
       (let [sid (:id (repo/agendar-sessao! r ente {:id (random-uuid) :sessao-legislativa-id (random-uuid)
                                                    :tipo-sessao "ordinaria" :modalidade "presencial"}))]
         (repo/transicionar-sessao! r ente {:id sid :para "aberta" :updated-by ident :lock-version 0})
         (spit ids-file (pr-str {:ente ente :ident ident :sessao sid}))
         (let [token (format "{\"identidade-id\":\"%s\",\"ente-id\":\"%s\"}" ident ente)]
           (println "\n=== DEMO PRONTA ===")
           (println "sessao-id:" sid)
           (println "token    :" token)
           (println "URL      : http://localhost:3000/sessoes/" (str sid) "/plenario?token=" (java.net.URLEncoder/encode token "UTF-8"))
           (println "===================\n")))))))

(defn eventos [_]
  (com-ds
   (fn [ds]
     (let [{:keys [ente sessao]} (edn/read-string (slurp ids-file))
           r        (repo-sessoes ds)
           vers     (repeatedly 7 random-uuid)]
       (println "injetando presenças (entrada) ...")
       (doseq [v vers]
         (repo/registrar-presenca! r ente {:id (random-uuid) :sessao-id sessao :vereador-id v
                                           :tipo "entrada" :modalidade "plenario" :fonte "manual_secretaria"
                                           :ocorrido-em (Instant/now) :created-by v})
         (Thread/sleep 600))
       (println "inscrição + início de fala (tribuna) ...")
       (let [orad (first vers)]
         (repo/inscrever! r ente {:id (random-uuid) :sessao-id sessao :vereador-id (second vers)
                                  :origem-inscricao "pre_sessao_app" :fase "ordem_do_dia" :created-by orad})
         (repo/iniciar-fala! r ente {:id (random-uuid) :sessao-id sessao :orador-id orad :tipo-fala "principal"
                                     :fase "ordem_do_dia" :iniciou-em (Instant/now) :created-by orad}))
       (println "feito — o painel deve mostrar quórum 7 + tribuna com cronômetro correndo.")))))
