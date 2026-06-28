(ns oplenario.identidade.models.identidade
  "Representacao INTERNA (dominio) do identidade — Malli (§22.10 models/). Enums batem os CHECK do
  schema (migration 0011). CPF como string de 11 digitos (validacao de digito verificador no adapter)."
  (:import (java.time Instant)))

(def tipos-vinculo #{"servidor" "vereador" "admin_ente" "cidadao"})
(def estados-vinculo #{"ativo" "suspenso" "encerrado"})

(defn- dv [digitos pesos]
  ;; digito verificador de CPF: soma(digito*peso) mod 11; <2 -> 0, senao 11-resto.
  (let [r (mod (reduce + (map * digitos pesos)) 11)]
    (if (< r 2) 0 (- 11 r))))

(defn valido-cpf?
  "Valida o CPF pelo digito verificador (alem do formato 11 digitos). Rejeita os 11-iguais (000..., 111...).
  A camada de auth/adapter chama isto ANTES de persistir — nao confiar so no formato (^\\d{11}$)."
  [cpf]
  (boolean
   (when (and (string? cpf) (re-matches #"\d{11}" cpf) (not (apply = cpf)))
     (let [d (mapv #(Character/digit ^char % 10) cpf)]
       (and (= (nth d 9) (dv (subvec d 0 9) (range 10 1 -1)))
            (= (nth d 10) (dv (subvec d 0 10) (range 11 1 -1))))))))

(defn- enum-de [s] (into [:enum] (sort s)))
(def ^:private Instante? [:fn {:error/message "deve ser java.time.Instant"} #(instance? Instant %)])

(def Identidade
  [:map {:closed true}
   [:id :uuid]
   [:cpf [:re #"^\d{11}$"]]
   [:nome :string]])

(def Vinculo
  [:map {:closed true}
   [:id :uuid]
   [:ente-id :uuid]
   [:identidade-id :uuid]
   [:tipo (enum-de tipos-vinculo)]
   [:estado (enum-de estados-vinculo)]])

(def UsuarioPapel
  [:map {:closed true}
   [:ente-id :uuid]
   [:identidade-id :uuid]
   [:papel :string]])

(def Consentimento
  [:map {:closed true}
   [:id :uuid]
   [:ente-id :uuid]
   [:identidade-id :uuid]
   [:finalidade :string]
   [:base-legal :string]
   [:versao-termo {:optional true} [:maybe :string]]])

(def CriadoEm Instante?)
