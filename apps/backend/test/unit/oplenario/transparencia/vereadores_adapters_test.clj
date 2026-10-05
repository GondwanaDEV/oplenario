(ns oplenario.transparencia.vereadores-adapters-test
  "UNIT (puro, sem DB) — o gate adapters/out da LISTA PUBLICA de vereadores em exercicio do portal. A rota e'
  anonima: o que sai tem de ser so' o que o contrato `VereadoresOut` (`:closed`) lista, e a linha que o host
  entrega (a do roster de cadastros) traz mais coisa do que isso — estado do mandato, nome e id internos."
  (:require [clojure.test :refer [deftest is]]
            [malli.core :as m]
            [oplenario.transparencia.adapters.out.vereadores :as vereadores]
            [oplenario.transparencia.wire.out.vereadores :as wire]))

(defn- linha-do-roster [& {:as extra}]
  (merge {:vereador-id (random-uuid) :nome "Helena Pastore de Andrade" :nome-parlamentar "Helena Past"
          :partido "PT" :estado-mandato "vigente" :cargo-mesa nil}
         extra))

(deftest projeta-so-o-que-o-contrato-publica
  (let [id (random-uuid)
        out (vereadores/->wire [(linha-do-roster :vereador-id id :cargo-mesa "Presidente")])]
    (is (m/validate wire/VereadoresOut out) "a projecao satisfaz VereadoresOut")
    (is (= [{:vereador-id (str id) :nome-parlamentar "Helena Past" :nome-civil "Helena Pastore de Andrade"
             :partido "PT" :cargo-mesa "Presidente"}]
           (:vereadores out))
        "id como string, e so' as cinco chaves publicas — estado-mandato nao vaza")))

(deftest campo-ausente-sai-nulo-e-nunca-vazio
  (let [[v] (:vereadores (vereadores/->wire [(linha-do-roster :nome-parlamentar nil :partido "  " :cargo-mesa "")]))]
    (is (nil? (:nome-parlamentar v)) "sem apelido -> nulo (a apresentacao decide o nome civil)")
    (is (nil? (:partido v)) "partido em branco -> nulo, nao uma string vazia na tela")
    (is (nil? (:cargo-mesa v)))))

(deftest chave-a-mais-no-wire-derruba-a-resposta
  ;; o mesmo contrato `:closed` do perfil: um campo que vaze do dominio e' bug de servidor (500), nao dado publico.
  (is (not (m/validate wire/VereadoresOut
                       {:vereadores [{:vereador-id "x" :nome-parlamentar nil :nome-civil "A" :partido nil
                                      :cargo-mesa nil :identidade-id "vazou"}]}))))

(deftest lista-vazia-e-um-estado-valido
  (is (= {:vereadores []} (vereadores/->wire [])))
  (is (= {:vereadores []} (vereadores/->wire nil)) "o seam sem dado nunca vira erro"))
