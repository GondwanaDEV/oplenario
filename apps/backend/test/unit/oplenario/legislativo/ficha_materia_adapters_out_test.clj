(ns oplenario.legislativo.ficha-materia-adapters-out-test
  "UNIT (puro, sem DB) — o gate adapters/out da ficha da materia (Onda B Slice 3). `ficha->wire` recebe o
   cabecalho JA PROJETADO por `adapters.out.proposicao/detalhe->wire` (reuso acontece no CALLER — adapters/
   nunca chama outro adapters/, ADR-0001 §3) e projeta tramitacao/apensadas/emendas/pareceres com o formato
   certo (uuid/instant -> string) e SEM os campos internos (:contexto/:template-id/:ator-id da tramitacao;
   os campos de armazenamento das demais)."
  (:require [clojure.test :refer [deftest is]]
            [malli.core :as m]
            [oplenario.legislativo.adapters.out.ficha-materia :as adapters]
            [oplenario.legislativo.adapters.out.proposicao :as adapters-proposicao]
            [oplenario.legislativo.wire.out.ficha-materia :as wire]))

(defn- proposicao-canonica [ente]
  {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026 :sequencial 42
   :urn-lex "urn:lex:x" :ementa "X" :estado "protocolada" :aprovada false :lock-version 0
   :atualizado-em (java.time.Instant/parse "2026-01-01T00:00:00Z")})

(defn- header [ente & [texto]] (adapters-proposicao/detalhe->wire (proposicao-canonica ente) texto))

(defn- tramitacao-canonica []
  {:id (random-uuid) :proposicao-id (random-uuid) :template-id (random-uuid)
   :de-estado "protocolada" :para-estado "em_comissoes" :gatilho "despachar"
   :contexto {:origem "teste"} :ator-id (random-uuid)
   :ocorrido-em (java.time.Instant/parse "2026-02-01T00:00:00Z")})

(defn- apensacao-canonica []
  {:id (random-uuid) :apensada-id (random-uuid)
   :apensada-em (java.time.Instant/parse "2026-02-02T00:00:00Z") :motivo-apensacao "materia conexa"})

(defn- emenda-canonica []
  {:id (random-uuid) :ente-id (random-uuid) :proposicao-mae-id (random-uuid) :numero-local 1
   :tipo-emenda "aditiva" :momento-apresentacao "no_prazo" :escopo-textual "artigo"
   :autor-tipo "vereador" :autor-id (random-uuid) :autor-texto "Helena Matos" :estado "apresentada"
   :formato "markdown" :texto-inline "Emenda 1" :conteudo-uri nil :hash-conteudo nil
   :versao-texto-resultante-id nil :lock-version 0})

(defn- parecer-canonico []
  {:id (random-uuid) :ente-id (random-uuid) :objeto-tipo "proposicao" :objeto-id (random-uuid)
   :comissao-id (random-uuid) :relator-id (random-uuid) :voto-relator "favoravel"
   :estado "com_relator" :template-id (random-uuid) :texto-vigente-versao-id nil :lock-version 0})

;; Os 6 `deftest` abaixo passaram a incluir os 4 `-truncado` (fatia 'truncamento-familia', achado
;; CRITICO da revisao adversarial): antes, `ficha->wire` coagia com `(boolean x)` e a chave AUSENTE
;; virava `false` silencioso — nenhum destes 6 fixtures reprovava mesmo sem projetar a chave nova.
;; Removida a coercao, os 6 REPROVAM (nil viola `:boolean` do schema `{:closed true}`) ate' aqui
;; passarem a declarar os 4 campos — o vermelho deles E' a prova de que a trava passou a existir.
(deftest ficha->wire-reusa-detalhe-do-cabecalho
  ;; correcao de teste pre-existente (arity mismatch achado ao rodar a suite apos o review
  ;; fe-9-ficha-materia — nao um dos achados do review, mas bloqueava o gate de suite 100% verde):
  ;; `ficha->wire` recebe o cabecalho JA PROJETADO (2 args), nao o mapa de dominio cru; o helper `header`
  ;; ja existia neste arquivo (linha 18) mas nunca era usado — os testes chamavam a fn com 1 arg so.
  (let [ente (random-uuid)
        out (adapters/ficha->wire (header ente "## Art. 1o")
                                   {:tramitacao [] :tramitacao-truncado false :apensadas [] :apensadas-truncado false
                                    :emendas [] :emendas-truncado false :pareceres [] :pareceres-truncado false})]
    (is (m/validate wire/FichaMateriaOut out))
    (is (= "## Art. 1o" (:texto (:proposicao out))))
    (is (string? (:id (:proposicao out))))
    (is (not (contains? (:proposicao out) :ente-id)))))

(deftest ficha->wire-tramitacao-sem-campos-internos
  (let [ente (random-uuid)
        out (adapters/ficha->wire (header ente)
                                   {:tramitacao [(tramitacao-canonica)] :tramitacao-truncado false :apensadas [] :apensadas-truncado false
                                    :emendas [] :emendas-truncado false :pareceres [] :pareceres-truncado false})
        item (first (:tramitacao out))]
    (is (m/validate wire/FichaMateriaOut out))
    (is (= "protocolada" (:de-estado item)))
    (is (= "em_comissoes" (:para-estado item)))
    (is (= "despachar" (:gatilho item)))
    (is (string? (:ocorrido-em item)))
    (is (not (contains? item :contexto)) "contexto (payload interno do motor) nao vaza")
    (is (not (contains? item :template-id)) "template-id nao vaza")
    (is (not (contains? item :ator-id)) "ator-id nao vaza (sem resolvedor de nome ainda)")))

(deftest ficha->wire-apensadas-projeta-uuid-e-instant-como-string
  (let [ente (random-uuid)
        out (adapters/ficha->wire (header ente)
                                   {:tramitacao [] :tramitacao-truncado false :apensadas [(apensacao-canonica)] :apensadas-truncado false
                                    :emendas [] :emendas-truncado false :pareceres [] :pareceres-truncado false})
        item (first (:apensadas out))]
    (is (m/validate wire/FichaMateriaOut out))
    (is (string? (:apensada-id item)))
    (is (string? (:apensada-em item)))
    (is (= "materia conexa" (:motivo-apensacao item)))))

(deftest ficha->wire-emendas-sem-campos-de-armazenamento
  (let [ente (random-uuid)
        out (adapters/ficha->wire (header ente)
                                   {:tramitacao [] :tramitacao-truncado false :apensadas [] :apensadas-truncado false
                                    :emendas [(emenda-canonica)] :emendas-truncado false :pareceres [] :pareceres-truncado false})
        item (first (:emendas out))]
    (is (m/validate wire/FichaMateriaOut out))
    (is (= "aditiva" (:tipo-emenda item)))
    (is (= "Helena Matos" (:autor-texto item)))
    (is (not (contains? item :texto-inline)) "conteudo integral nao vaza no resumo")
    (is (not (contains? item :lock-version)) "lock-version nao vaza no resumo")))

(deftest ficha->wire-pareceres-sem-campos-internos
  (let [ente (random-uuid)
        out (adapters/ficha->wire (header ente)
                                   {:tramitacao [] :tramitacao-truncado false :apensadas [] :apensadas-truncado false
                                    :emendas [] :emendas-truncado false :pareceres [(parecer-canonico)] :pareceres-truncado false})
        item (first (:pareceres out))]
    (is (m/validate wire/FichaMateriaOut out))
    (is (= "com_relator" (:estado item)))
    (is (= "favoravel" (:voto-relator item)))
    (is (not (contains? item :template-id)) "template-id nao vaza no resumo")
    (is (not (contains? item :objeto-tipo)) "objeto-tipo nao vaza no resumo")))

;; ---------- #11: o nome da comissao em cada linha de parecer ----------

(deftest ficha->wire-projeta-o-nome-da-comissao-de-cada-parecer
  (let [ente (random-uuid)
        out (adapters/ficha->wire (header ente nil)
              {:tramitacao [] :tramitacao-truncado false :apensadas [] :apensadas-truncado false :emendas []
               :emendas-truncado false
               :pareceres [(assoc (parecer-canonico) :comissao-nome "Comissão de Finanças")
                           (parecer-canonico)]
               :pareceres-truncado false})]
    (is (m/validate wire/FichaMateriaOut out))
    (is (= ["Comissão de Finanças" nil] (mapv :comissao-nome (:pareceres out)))
        "linha sem nome resolvido sai nil — nunca o comissao-id como substituto")))
