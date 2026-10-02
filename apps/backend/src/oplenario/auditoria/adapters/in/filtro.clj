(ns oplenario.auditoria.adapters.in.filtro
  "Gate de ENTRADA dos filtros da trilha (ADR-0017): query string -> filtro de dominio. Valor fora do vocabulario =
  400 (nunca \"ignora e devolve tudo\")."
  (:require [clojure.string :as str]
            [oplenario.auditoria.logic :as logic])
  (:import (java.time LocalDate)
           (java.time.format DateTimeParseException)))

(set! *warn-on-reflection* true)

(def atores #{"pessoa" "cidadao" "agente"})
(def classes #{"escrita" "negacao" "entrada" "leitura_sensivel"})
(def objetos
  "O modulo da acao (o prefixo do route-name): o \"Objeto\" do filtro da tela."
  #{"legislativo" "sessoes" "cadastros" "identidade" "participacao" "transparencia" "compliance" "normas"
    "integracao-ia" "paineis" "auditoria" "comunicacao"})

(defn- invalido! [campo]
  (throw (ex-info (str "filtro invalido: " (name campo)) {:tipo :validacao/invalido :campo campo})))

(defn- dia [campo s]
  (when-not (str/blank? s)
    (try (LocalDate/parse s) (catch DateTimeParseException _ (invalido! campo)))))

(defn- inicio-do-dia [^LocalDate d] (.toInstant (.atStartOfDay d logic/zona-da-casa)))

(defn- do-vocabulario [campo vocab s]
  (when-not (str/blank? s) (if (vocab s) s (invalido! campo))))

(defn query->filtro
  "{\"desde\" \"ate\" \"ator\" \"classe\" \"objeto\" \"antes-de\"} (datas AAAA-MM-DD, dia civil da Casa; `ate` inclusivo)."
  [q]
  (let [g #(get q % (get q (name %)))
        desde (dia :desde (g :desde))
        ate   (dia :ate (g :ate))
        antes (when-let [s (not-empty (g :antes-de))]
                (let [n (parse-long s)] (if (and n (pos? n)) n (invalido! :antes-de))))]
    (when (and desde ate (.isAfter ^LocalDate desde ^LocalDate ate)) (invalido! :desde))
    (cond-> {}
      desde (assoc :desde (inicio-do-dia desde))
      ate   (assoc :ate (inicio-do-dia (.plusDays ^LocalDate ate 1)))
      (g :ator) (assoc :ator-tipo (do-vocabulario :ator atores (g :ator)))
      (g :classe) (assoc :classe (do-vocabulario :classe classes (g :classe)))
      (g :objeto) (assoc :objeto (do-vocabulario :objeto objetos (g :objeto)))
      antes (assoc :antes-de antes))))
