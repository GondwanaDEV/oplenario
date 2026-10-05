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
(def sem-desfecho
  "No filtro \"Registro\" da tela, ao lado das classes: so' as escritas iniciadas cujo desfecho nao foi registrado."
  "sem_desfecho")
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

(def ^:private recurso-tipo-re #"^[a-z_-]{1,40}$")
(def ^:private recurso-id-re #"^[A-Za-z0-9._:-]{1,80}$")

(defn- recurso
  "O par `recurso-tipo` + `recurso-id` (\"o que aconteceu com ESTE objeto\"): os dois juntos ou nenhum. So' um deles,
  ou um valor fora do formato, e' 400 — o filtro pela metade devolveria a trilha de todos os objetos do tipo."
  [tipo id]
  (let [texto (fn [campo v] (cond (nil? v) nil
                                  (not (string? v)) (invalido! campo)   ; o parametro repetido chega como colecao
                                  (str/blank? v) nil
                                  :else v))
        tipo (texto :recurso-tipo tipo)
        id   (texto :recurso-id id)]
    (cond
      (and (nil? tipo) (nil? id)) nil
      (nil? tipo) (invalido! :recurso-tipo)
      (nil? id) (invalido! :recurso-id)
      (not (re-matches recurso-tipo-re tipo)) (invalido! :recurso-tipo)
      (not (re-matches recurso-id-re id)) (invalido! :recurso-id)
      :else {:recurso-tipo tipo :recurso-id id})))

(defn query->filtro
  "{\"desde\" \"ate\" \"ator\" \"classe\" \"objeto\" \"recurso-tipo\" \"recurso-id\" \"antes-de\"} (datas AAAA-MM-DD,
  dia civil da Casa; `ate` inclusivo). `recurso-tipo` (`[a-z_-]{1,40}`: vem do nome do parametro de caminho, que
  pode ter hifen, como `vinculo-ativo`) e `recurso-id` (`[A-Za-z0-9._:-]{1,80}`) vem JUNTOS — o registro do objeto
  (ex.: `proposicao` + o uuid): so' um deles, ou fora do formato, e' 400. Vale tambem para a exportacao (o mesmo
  filtro) e soma ao escopo do papel, nunca o alarga."
  [q]
  (let [g #(get q % (get q (name %)))
        rec   (recurso (g :recurso-tipo) (g :recurso-id))
        desde (dia :desde (g :desde))
        ate   (dia :ate (g :ate))
        antes (when-let [s (not-empty (g :antes-de))]
                (let [n (parse-long s)] (if (and n (pos? n)) n (invalido! :antes-de))))]
    (when (and desde ate (.isAfter ^LocalDate desde ^LocalDate ate)) (invalido! :desde))
    (cond-> {}
      desde (assoc :desde (inicio-do-dia desde))
      ate   (assoc :ate (inicio-do-dia (.plusDays ^LocalDate ate 1)))
      (g :ator) (assoc :ator-tipo (do-vocabulario :ator atores (g :ator)))
      (= sem-desfecho (g :classe)) (assoc :sem-desfecho true)
      (and (g :classe) (not= sem-desfecho (g :classe))) (assoc :classe (do-vocabulario :classe classes (g :classe)))
      (g :objeto) (assoc :objeto (do-vocabulario :objeto objetos (g :objeto)))
      rec   (merge rec)
      antes (assoc :antes-de antes))))
