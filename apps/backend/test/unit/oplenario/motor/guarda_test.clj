(ns oplenario.motor.guarda-test
  "Save-time (Inv.4 / disciplina 5): `motor/validar-guarda` tira a falha do GUARD de uma transicao
  de tramitacao do caminho critico — um regimento com guard mal-escrito e' REJEITADO na configuracao,
  nunca no meio de um fluxo (o clerk tentando despachar uma proposicao). F3.3b cobre a validacao
  SINTATICA (parse). Type-check estatico completo contra o vocabulario de tramitacao (registros
  proposicao/contexto) e' carry — depende da catalogacao do eixo C (analogo a §22.7.5)."
  (:require [clojure.test :refer [deftest is]]
            [oplenario.motor.api :as motor]))

(deftest guard-ausente-e-valido
  (is (= "VALIDA" (:status (motor/validar-guarda nil))) "guard nil = sempre passa = valido")
  (is (= "VALIDA" (:status (motor/validar-guarda ""))) "guard vazio = ausente = valido")
  (is (= "VALIDA" (:status (motor/validar-guarda "   "))) "guard so-espaco = ausente = valido"))

(deftest guard-bem-formado-e-valido
  (is (= "VALIDA" (:status (motor/validar-guarda "verdadeiro"))) "literal booleano parseia")
  (is (= "VALIDA" (:status (motor/validar-guarda "proposicao.estado == \"protocolada\"")))
      "comparacao de campo parseia")
  (is (= "VALIDA" (:status (motor/validar-guarda "nao falso ou verdadeiro e verdadeiro")))
      "expressao booleana composta parseia"))

(deftest guard-mal-formado-e-rejeitado
  (let [r (motor/validar-guarda "( verdadeiro")]
    (is (= "INVALIDA" (:status r)) "parentese sem fechar e' rejeitado")
    (is (seq (:erros r)) "traz a causa do erro de sintaxe"))
  (is (= "INVALIDA" (:status (motor/validar-guarda "ou"))) "operador sozinho e' rejeitado")
  (is (= "INVALIDA" (:status (motor/validar-guarda "verdadeiro verdadeiro")))
      "sobra de tokens apos a expressao e' rejeitada"))

;; ===========================================================================
;; Aridade 2 — allowlist de vocabulario (a frente `guarda-so-apurado`, 11/09/2026).
;; O motor nao conhece "alegado" nem nenhum outro vocabulario — so' compara os
;; identificadores-raiz que a expressao usa contra o conjunto que o CHAMADOR passa em `opts`.
;; ===========================================================================
(def ^:private vocab-proposicao #{"proposicao" "contexto"})

(deftest aridade-1-continua-so-sintatica
  ;; a aridade antiga nao ganhou vocabulario nenhum — "alegado" nunca era rejeitado antes,
  ;; e continua nao sendo pela aridade-1 (ela nem RECEBE opts p/ julgar vocabulario).
  (is (= "VALIDA" (:status (motor/validar-guarda "alegado.parecer == \"favoravel\"")))
      "aridade-1 e' so' sintatica — sem opts, nenhum identificador e' ilegal"))

(deftest aridade-2-vocabulario-permitido-passa
  (is (= "VALIDA" (:status (motor/validar-guarda "proposicao.estado == \"protocolada\""
                                                  {:vocabulario vocab-proposicao})))
      "so' usa identificador do vocabulario permitido")
  (is (= "VALIDA" (:status (motor/validar-guarda
                             "proposicao.estado == \"protocolada\" e contexto.ano > 2020"
                             {:vocabulario vocab-proposicao})))
      "dois identificadores, ambos permitidos"))

(deftest aridade-2-fato-e-campo-nao-contam-como-identificador
  (is (= "VALIDA" (:status (motor/validar-guarda "é_presidente(proposicao.autor_id)"
                                                  {:vocabulario vocab-proposicao})))
      "o nome do FATO (chamada) nao e' identificador do amb — nao reprova mesmo fora do vocabulario")
  (is (= "VALIDA" (:status (motor/validar-guarda "proposicao.estado"
                                                  {:vocabulario vocab-proposicao})))
      "o nome do CAMPO ('estado') nao e' identificador — so' a raiz ('proposicao') e' checada"))

(deftest aridade-2-identificador-alegado-e-rejeitado
  (let [r (motor/validar-guarda "alegado.parecer == \"favoravel\"" {:vocabulario vocab-proposicao})]
    (is (= "INVALIDA" (:status r)) "'alegado' fora do vocabulario permitido — REJEITADO")
    (is (some #(clojure.string/includes? % "alegado") (:erros r))
        "a mensagem NOMEIA o identificador ilegal")
    (is (some #(clojure.string/includes? % "proposicao") (:erros r))
        "a mensagem lista o vocabulario permitido")
    (is (some #(clojure.string/includes? % "contexto") (:erros r))
        "a mensagem lista o vocabulario permitido por inteiro")))

(deftest aridade-2-identificador-alegado-aninhado-e-rejeitado
  (is (= "INVALIDA" (:status (motor/validar-guarda "é_presidente(alegado.x)"
                                                    {:vocabulario vocab-proposicao})))
      "'alegado' dentro dos args de uma chamada tambem e' pego"))

(deftest aridade-2-fonte-em-branco-continua-valida
  (is (= "VALIDA" (:status (motor/validar-guarda nil {:vocabulario vocab-proposicao}))))
  (is (= "VALIDA" (:status (motor/validar-guarda "" {:vocabulario vocab-proposicao})))))

(deftest aridade-2-sem-vocabulario-LANCA-em-vez-de-degradar
  ;; Este e' o ponto exato que a frente existe p/ fechar, um nivel acima: se "esqueci de passar o
  ;; vocabulario" degradasse p/ so'-sintatico, o gate teria um caminho PERMISSIVO acionado por omissao —
  ;; a mesma classe de fail-open de `nao <fato>(arg nil)`. Quem nao quer vocabulario chama a ARIDADE-1,
  ;; que diz isso em voz alta na assinatura; a aridade-2 sem vocabulario e' erro de programacao, nao um
  ;; modo de uso.
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"vocabulario"
        (motor/validar-guarda "alegado.x" {}))
      "opts sem :vocabulario LANCA — nunca degrada silenciosamente p/ permissivo")
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"vocabulario"
        (motor/validar-guarda "alegado.x" {:vocabulario nil}))
      ":vocabulario nil tambem LANCA — nil nao e' 'sem restricao'")
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"vocabulario"
        (motor/validar-guarda "alegado.x" nil))
      "opts nil tambem LANCA — quem quer so' sintaxe usa a aridade-1")
  ;; vocabulario VAZIO e' declaracao legitima ('nada e' permitido aqui') e NAO lanca: devolve INVALIDA.
  ;; E' o caso do template inexistente em `criar-transicao!` (sujeito nil -> #{}) — fail-CLOSED com
  ;; resposta de dominio, nao excecao de programacao.
  (let [r (motor/validar-guarda "alegado.x" {:vocabulario #{}})]
    (is (= "INVALIDA" (:status r)) "vocabulario vazio reprova qualquer identificador")
    (is (re-find #"alegado" (first (:erros r))) "e nomeia o identificador recusado"))
  (is (= "VALIDA" (:status (motor/validar-guarda "" {:vocabulario #{}})))
      "fonte em branco continua VALIDA mesmo com vocabulario vazio (guarda ausente = sempre passa)"))
