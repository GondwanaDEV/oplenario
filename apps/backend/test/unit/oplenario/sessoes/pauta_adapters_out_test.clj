(ns oplenario.sessoes.pauta-adapters-out-test
  "UNIT (puro, sem DB) — o gate adapters/out da PAUTA com o resumo da proposicao (Modo TV, docs/22): o campo
  `proposicao` so' aparece quando ha' resumo para aquele id, nunca e' inventado, e a projecao segue validando
  contra PautaOut."
  (:require [clojure.test :refer [deftest is]]
            [malli.core :as m]
            [oplenario.sessoes.adapters.out.pauta :as pauta-out]
            [oplenario.sessoes.wire.out :as wire]))

(defn- item [ordem tipo-item proposicao-id texto]
  {:id (random-uuid) :ente-id (random-uuid) :pauta-sessao-id (random-uuid) :fase "ordem_do_dia"
   :tipo-item tipo-item :proposicao-id proposicao-id :texto-descricao texto :ordem ordem
   :ativo true :lock-version 0})

(deftest sem-resumos-projeta-como-antes
  (let [prop (random-uuid)
        out (pauta-out/pauta->wire {:sessao-id (random-uuid) :itens [(item 1 "proposicao" prop nil)]})]
    (is (m/validate wire/PautaOut out))
    (is (= (str prop) (:proposicao-id (first (:itens out)))))
    (is (not (contains? (first (:itens out)) :proposicao)) "aridade antiga: sem resumo, sem o campo")))

(deftest com-resumo-acrescenta-proposicao-so-no-item-certo
  (let [prop (random-uuid) outra (random-uuid)
        resumos {prop {:tipo "projeto_lei" :ano 2026 :sequencial 22 :ementa "Energia solar" :extra "nao vaza"}}
        out (pauta-out/pauta->wire {:sessao-id (random-uuid)
                                    :itens [(item 1 "proposicao" prop nil)
                                            (item 2 "proposicao" outra nil)
                                            (item 3 "comunicado" nil "Comunicado")]}
                                   resumos)
        [i1 i2 i3] (:itens out)]
    (is (m/validate wire/PautaOut out) "a projecao enriquecida segue o contrato fechado")
    (is (= {:tipo "projeto_lei" :ano 2026 :sequencial 22 :ementa "Energia solar"} (:proposicao i1))
        "so' as 4 chaves do contrato — nada extra do mapa de resumo vaza")
    (is (not (contains? i2 :proposicao)) "id sem resumo (ex.: fora do tenant) -> sem o campo, nunca inventado")
    (is (not (contains? i3 :proposicao)) "item de texto nunca ganha o campo")))
