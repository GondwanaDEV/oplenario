(ns oplenario.legislativo.wire-out-ficha-materia-test
  "UNIT (puro, sem DB) — o contrato wire/out da ficha da materia (Onda B Slice 3): valida a FORMA de cada
  schema Malli fechado (mirror de wire_out_proposicao_test.clj)."
  (:require [clojure.test :refer [deftest is]]
            [malli.core :as m]
            [oplenario.legislativo.wire.out.ficha-materia :as wire]))

(def ^:private proposicao-minima
  {:id "u" :tipo "projeto_lei" :ano 2026 :sequencial 1 :urn-lex "urn:x" :ementa "X" :estado "protocolada"
   :aprovada false :lock-version 0 :atualizado-em "2026-01-01T00:00:00Z"})

(deftest historico-item-minimo-valido
  (is (m/validate wire/HistoricoTramitacaoItemOut
                  {:de-estado "protocolada" :para-estado "em_comissoes" :gatilho "despachar"
                   :ocorrido-em "2026-01-01T00:00:00Z"})))

(deftest historico-item-com-contexto-invalido
  (is (not (m/validate wire/HistoricoTramitacaoItemOut
                       {:de-estado "protocolada" :para-estado "em_comissoes" :gatilho "despachar"
                        :ocorrido-em "2026-01-01T00:00:00Z" :contexto {}}))
      "contexto (payload interno do motor) nao e' exposto — schema fechado barra"))

(deftest apensacao-minima-valida
  (is (m/validate wire/ApensacaoOut {:apensada-id "u" :apensada-em "2026-01-01T00:00:00Z"})))

(deftest apensacao-com-motivo-valida
  (is (m/validate wire/ApensacaoOut
                  {:apensada-id "u" :apensada-em "2026-01-01T00:00:00Z" :motivo-apensacao "materia conexa"})))

(deftest apensacao-sem-motivo-obrigatorio-e-opcional
  (is (m/validate wire/ApensacaoOut
                  (assoc {:apensada-id "u" :apensada-em "x"} :motivo-apensacao nil))))

(deftest emenda-resumo-minima-valida
  (is (m/validate wire/EmendaResumoOut
                  {:id "u" :numero-local 1 :tipo-emenda "aditiva" :momento-apresentacao "no_prazo"
                   :estado "apresentada"})))

(deftest emenda-resumo-com-autor-valida
  (is (m/validate wire/EmendaResumoOut
                  {:id "u" :numero-local 1 :tipo-emenda "aditiva" :momento-apresentacao "no_prazo"
                   :autor-tipo "vereador" :autor-texto "Helena Matos" :estado "apresentada"})))

(deftest emenda-resumo-nao-vaza-campo-interno
  (is (not (m/validate wire/EmendaResumoOut
                       {:id "u" :numero-local 1 :tipo-emenda "aditiva" :momento-apresentacao "no_prazo"
                        :estado "apresentada" :texto-inline "conteudo"}))
      "texto-inline (conteudo integral) nao e' campo de resumo — schema fechado barra"))

(deftest parecer-resumo-minimo-valido
  (is (m/validate wire/ParecerResumoOut {:id "u" :comissao-id "u2" :estado "aguardando_designacao"})))

(deftest parecer-resumo-com-relator-e-voto-valido
  (is (m/validate wire/ParecerResumoOut
                  {:id "u" :comissao-id "u2" :relator-id "u3" :voto-relator "favoravel"
                   :estado "com_relator"})))

(deftest ficha-materia-out-minima-valida
  (is (m/validate wire/FichaMateriaOut
                  {:proposicao proposicao-minima :tramitacao [] :apensadas [] :emendas [] :pareceres []})))

(deftest ficha-materia-out-sem-proposicao-invalida
  (is (not (m/validate wire/FichaMateriaOut
                       {:proposicao nil :tramitacao [] :apensadas [] :emendas [] :pareceres []}))
      "o controller ja' gateia nil (-> 404 na borda); a wire/out so' projeta ficha com proposicao presente"))
