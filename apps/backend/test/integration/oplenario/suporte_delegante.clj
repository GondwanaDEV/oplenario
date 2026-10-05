(ns oplenario.suporte-delegante
  "Suporte de teste: um Repo que DELEGA a um Repo de verdade e troca so' o que o teste escolhe — para fazer uma gravacao
  falhar de forma controlada (o banco da atuacao fora do ar) sem escrever os ~40 metodos do protocolo.

  Por que nao `with-redefs` no metodo do protocolo: o compilador emite a chamada direta da interface quando o Repo e' um
  defrecord, entao a var redefinida nunca e' consultada."
  (:require [oplenario.admin-sistema.components.repositorio :as repo-admin]
            [oplenario.integracao-ia.components.repositorio :as repo-ia]))

(deftype Delegante [real trocas])

(defn- estender! [protocolo]
  (extend Delegante protocolo
          (into {}
                (for [[k {:keys [name]}] (:sigs protocolo)
                      :let [var-do-metodo (requiring-resolve
                                           (symbol (-> protocolo :var meta :ns str) (clojure.core/name name)))]]
                  [k (fn [^Delegante this & args]
                       (if-let [f (get (.-trocas this) k)]
                         (apply f (.-real this) args)
                         (apply @var-do-metodo (.-real this) args)))]))))

(estender! repo-admin/RepoAdminSistema)
(estender! repo-ia/RepoIntegracaoIA)

(defn delegando
  "`real` com os metodos de `trocas` ({:metodo (fn [real & args])}) no lugar dos dele."
  [real trocas]
  (->Delegante real trocas))
