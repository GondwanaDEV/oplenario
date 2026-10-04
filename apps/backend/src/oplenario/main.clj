(ns oplenario.main
  "Entrypoint do processo (host, §22.10): le a config e dispatcha por subcomando. `migrate` aplica
  as migrations e sai (passo de init separado do app, §22.9 — evita corrida de migration entre
  replicas); sem arg = `serve` (sobe o sistema Component e bloqueia ate o shutdown). Glue fino
  sobre pecas ja testadas (config/sistema/migracao/datasource)."
  (:gen-class)
  (:require [clojure.string :as str]
            [com.stuartsierra.component :as component]
            [oplenario.admin-sistema.components.idp-admin :as idp-admin]
            [oplenario.admin-sistema.components.repositorio :as repo-admin]
            [oplenario.admin-sistema.controllers :as admin-sistema]
            [oplenario.config :as config]
            [oplenario.comunicacao.components.repositorio :as repo-comunicacao]
            [oplenario.ia-republicar :as ia-republicar]
            [oplenario.integracao-ia.components.repositorio :as repo-ia]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.components.objeto-store :as objeto-store]
            [oplenario.kernel.tempo :as tempo]
            [oplenario.migracao :as migracao]
            [oplenario.participacao.components.repositorio :as repo-participacao]
            [oplenario.reconciliar-anexos :as reconciliar-anexos]
            [oplenario.sistema :as sistema]))

(defn- migrar!
  "Aplica as migrations num datasource efemero e o fecha (passo de init)."
  [cfg]
  (let [ds (component/start (datasource/datasource cfg))]
    (try (migracao/migrar! (:ds ds))
         (finally (component/stop ds)))))

(defn -main [& args]
  (let [cfg (config/carregar)]
    (cond
      (= "migrate" (first args))
      (do (migrar! cfg)
          (println "[oplenario] migrations aplicadas"))

      ;; Track IA A.4: carga inicial do indice de busca de uma Casa (idempotente)
      (= "ia-republicar-proposicoes" (first args))
      (let [ente (or (parse-uuid (str (second args)))
                     (throw (ex-info "uso: ia-republicar-proposicoes <ente-id>" {})))
            ds   (component/start (datasource/datasource cfg))]
        (try (println "[oplenario]" (ia-republicar/republicar-proposicoes! (:ds ds) ente)
                      "proposicao(oes) publicada(s) no feed da IA")
             (finally (component/stop ds))))

      ;; Track IA B.9 (ADR-0014): o OPERADOR define o orcamento de IA da Casa conforme o plano (valores comerciais)
      (= "ia-orcamento" (first args))
      (let [[_ ente mensal teto moeda] args
            uso "uso: ia-orcamento <ente-id> <mensal> <teto-duro> [moeda=USD]  (na moeda da tabela de precos da IA)"
            ente (or (parse-uuid (str ente)) (throw (ex-info uso {})))
            valor #(try (bigdec %) (catch Exception _ (throw (ex-info uso {}))))
            ds (component/start (datasource/datasource cfg))]
        (try (let [d (repo-ia/definir-orcamento! (repo-ia/map->RepoIntegracaoIAPg {:datasource ds})
                                                 {:ente-id ente :mensal (valor mensal) :teto-duro (valor teto)
                                                  :moeda (or moeda "USD") :definido-por "operador (linha de comando)"})]
               (println "[oplenario] orcamento de IA definido:" (str (:mensal d)) "/ teto" (str (:teto-duro d))
                        (:moeda d) "— a IA recebe pelo feed"))
             (finally (component/stop ds))))

      ;; ADR-0022/ADR-0020: compara os anexos do banco com os blobs do object storage (relata; --apagar-orfaos so' tira do
      ;; STORAGE o blob sem linha com mais de 24 h). Sai com 0 = integro, 1 = divergencia, 2 = uso/erro.
      (= "reconciliar-anexos" (first args))
      (let [ds (component/start (datasource/datasource cfg))
            os (component/start (objeto-store/objeto-store cfg))
            codigo (try (let [{:keys [saida codigo]}
                              (reconciliar-anexos/executar
                               {:repo-admin (assoc (repo-admin/repositorio) :datasource ds)
                                :objeto-store os
                                :fontes (reconciliar-anexos/fontes
                                         {:repo-participacao (assoc (repo-participacao/repositorio) :datasource ds)
                                          :repo-comunicacao (assoc (repo-comunicacao/repositorio) :datasource ds)})
                                :agora (tempo/agora (tempo/relogio-sistema))}
                               (rest args))]
                          (println saida)
                          codigo)
                        (finally (component/stop os) (component/stop ds)))]
        (System/exit codigo))

      ;; ADR-0016: o ciclo de vida do OPERADOR da plataforma. O primeiro nao tem console para se convidar.
      (#{"operador-convidar" "operador-desligar"} (first args))
      (let [[cmd email & nome] args
            uso "uso: operador-convidar <email> <nome completo> | operador-desligar <email>"
            _ (when (or (nil? email) (and (= "operador-convidar" cmd) (empty? nome))) (throw (ex-info uso {})))
            ds (component/start (datasource/datasource cfg))
            idp (component/start (idp-admin/keycloak-operacao (:operacao cfg)))
            repo (assoc (repo-admin/repositorio) :datasource ds)]
        (try (if (= "operador-convidar" cmd)
               (let [o (admin-sistema/convidar-operador! repo idp {:email email :nome (str/join " " nome)})]
                 (println "[oplenario] operador convidado:" (:email o) "— o e-mail pede a senha e a chave fisica"))
               (let [o (admin-sistema/desligar-operador! repo idp {:email email})]
                 (println "[oplenario] operador desligado:" (:email o))))
             (finally (component/stop idp) (component/stop ds))))

      :else
      (let [sys (component/start (sistema/sistema-serve cfg))]
        (.addShutdownHook (Runtime/getRuntime)
                          (Thread. ^Runnable (fn [] (component/stop sys))))
        (println "[oplenario] sistema no ar")
        @(promise)))))
