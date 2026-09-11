(ns oplenario.motor.registro-fatos-test
  "F2.2 — o CONTRATO (docs/12 §1): o assert de costura catálogo⋈registry (a rede contra o risco
  CRÍTICO) e o resolver-para. Prova: (a) as relações REAIS de F1 (cadastros+identidade) passam a
  costura; (b) fn com aridade divergente é pega; (c) fn sem assinatura é pega; (d) assinatura-sem-fn
  é LEGAL (uni-direcional); (e) resolver-para resolve por nome, injeta a tx, e é fail-closed."
  (:require [clojure.test :refer [deftest is]]
            [com.stuartsierra.component :as component]
            [oplenario.cadastros.relacoes.cadastro :as rel-cad]
            [oplenario.identidade.relacoes.identidade :as rel-id]
            [oplenario.legislativo.relacoes :as rel-legis]
            [oplenario.motor.catalogo :as cat]
            [oplenario.motor.components.registro-fatos :as rf]))

(def ^:private fns-reais (merge rel-cad/relacoes rel-id/relacoes))

(deftest costura-das-relacoes-reais-passa
  ;; toda fn de F1 tem assinatura :relacao no catálogo com aridade de domínio compatível (a tx não conta)
  (let [r (rf/verificar-costura fns-reais)]
    (is (:ok r) (str "as relações reais devem casar o catálogo; erros=" (:erros r))))
  ;; cada fn registrada tem MESMO uma assinatura :relacao
  (doseq [nome (keys fns-reais)]
    (is (= "relacao" (:categoria (cat/buscar-assinatura nome))) (str nome " é :relacao no catálogo"))))

(deftest costura-do-fato-do-legislativo-3b
  ;; 3-B: o legislativo passou a publicar fato proprio (`aprovada_em_votacao`), e a costura dele e' o que
  ;; impede a classe de erro mais barata desta frente — o guard de um rito ja' cadastrado escreve um nome
  ;; que o registry nao tem mais. A costura e' fail-closed no boot; aqui vira falha de TESTE, com a causa
  ;; escrita, em vez de um sistema que so' nao sobe.
  (let [r (rf/verificar-costura rel-legis/relacoes)]
    (is (:ok r) (str "costura catálogo⋈relacoes do legislativo; erros=" (:erros r))))
  (is (contains? rel-legis/relacoes "aprovada_em_votacao")
      "o nome canonico e' o que o guard do rito ESCREVE — renomea-lo quebra rito ja' cadastrado")
  (is (= "relacao" (:categoria (cat/buscar-assinatura "aprovada_em_votacao"))))
  ;; guard e' PREDICADO: o catalogo tem de tipar Booleano, senao o type-check de save-time (quando o
  ;; eixo C for catalogado, o [CARRY] de motor/api/validar-guarda) aceitaria a expressao errada.
  (is (= "Booleano" (:nome (:retorno (cat/buscar-assinatura "aprovada_em_votacao")))))
  (is (:ok (rf/verificar-costura (merge fns-reais rel-legis/relacoes)))
      "e o registry COMPLETO (como o host o funde) tambem costura"))

(deftest costura-pega-aridade-divergente
  ;; populacao real é (tx) → domínio 0; finge uma fn de 2 args de domínio sob o mesmo nome
  (let [r (rf/verificar-costura {"populacao" (fn [_tx _a _b] 1)})]
    (is (not (:ok r)) "aridade divergente NÃO passa")
    (is (some #(re-find #"populacao" %) (:erros r)) (str "erro aponta populacao; erros=" (:erros r)))))

(deftest costura-pega-fn-sem-assinatura
  (let [r (rf/verificar-costura {"fato_fantasma" (fn [_tx] true)})]
    (is (not (:ok r)) "fn sem assinatura no catálogo NÃO passa")
    (is (some #(re-find #"sem assinatura" %) (:erros r)) (str "erro de assinatura ausente; erros=" (:erros r)))))

(deftest costura-rejeita-fn-variadica
  ;; relações têm aridade FIXA (docs/12 §4); uma fn variádica (RestFn) é rejeitada com erro EXPLÍCITO
  ;; (não o opaco "aridade #{}") — fail-closed com diagnóstico legível.
  (let [r (rf/verificar-costura {"populacao" (fn [_tx & _resto] 1)})]
    (is (not (:ok r)) "fn variádica NÃO passa")
    (is (some #(re-find #"variádic" %) (:erros r)) (str "erro aponta variádico; erros=" (:erros r)))))

(deftest boot-do-component-real-passa
  ;; fecha o elo: o Component REAL (relações de F1 mescladas) sobe — o `start` roda o assert sem lançar.
  (let [c (component/start (rf/registro-fatos fns-reais))]
    (is (= fns-reais (:fns c)) "RegistroFatos bootou com as relações reais (assert de costura passou)")
    (component/stop c)))

(deftest assinatura-sem-fn-e-legal
  ;; remessa_enviada/votos_favoraveis existem no catálogo (módulo futuro) SEM fn registrada — a costura
  ;; é uni-direcional (fns ⊆ assinaturas), então um registry vazio passa; o catálogo legitimamente
  ;; declara mais do que está implementado neste deploy (C1).
  (is (:ok (rf/verificar-costura {})) "registry vazio é costura válida (uni-direcional)")
  (is (= "relacao" (:categoria (cat/buscar-assinatura "votos_favoraveis"))) "assinatura de fato futuro existe sem fn"))

(deftest resolver-para-injeta-tx-e-e-fail-closed
  (let [registro (rf/registro-fatos {"populacao" (fn [tx] (str "tx=" tx))})
        resolver (rf/resolver-para registro :tx-fake)]
    (is (= "tx=:tx-fake" (resolver "populacao" [])) "resolver injeta a tx como 1º arg e chama por nome")
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"fato sem fn"
          (resolver "inexistente" [])) "fato sem fn registrada = fail-closed (lança)")))
