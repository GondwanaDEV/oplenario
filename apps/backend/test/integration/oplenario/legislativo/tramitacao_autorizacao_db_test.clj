(ns oplenario.legislativo.tramitacao-autorizacao-db-test
  "INTEGRACAO (PG real) — 3-A: QUEM pode disparar cada gatilho.

  O buraco que estes testes guardam: ate' a 3-A a unica autorizacao da borda de tramitacao era o gate
  GROSSO da rota (`exige-papel \"secretario\"`, o MESMO papel que LISTA proposicoes), e um portador desse
  papel levava a materia de ponta a ponta do rito sozinho.

  A 3-B (guarda) e a 3-A (autorizacao) respondem perguntas DIFERENTES sobre o mesmo ato:
    guarda      -> 'isto aconteceu?'            (fato sobre o mundo; nao fala do ator)
    autorizacao -> 'voce pode declarar que sim?' (fato sobre quem pede; nao fala do mundo)
  Por isso as recusas tem de ser distinguiveis: 'a Casa nao permite agora' (409, dominio) e 'voce nao
  pode' (403, autorizacao) vao para pessoas diferentes e tem consertos diferentes."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [oplenario.cadastros.relacoes.cadastro :as rel-cad]
            [oplenario.config :as config]
            [oplenario.identidade.relacoes.identidade :as rel-id]
            [oplenario.kernel.autorizacao :as authz]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.legislativo.db.proposicao :as prop]
            [oplenario.legislativo.db.tramitacao :as tram]
            [oplenario.legislativo.logic :as logic]
            [oplenario.legislativo.relacoes :as rel-legis]
            [oplenario.migracao :as migracao]
            [oplenario.motor.components.registro-fatos :as rf])
  (:import (java.time LocalDate)))

(def ^:dynamic *ds* nil)
(def ^:dynamic *registro* nil)

(use-fixtures :once
  (fn [t]
    (let [c   (component/start (datasource/datasource (config/carregar)))
          reg (component/start (rf/registro-fatos (merge rel-cad/relacoes rel-id/relacoes rel-legis/relacoes)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c) *registro* reg]
        (try (t) (finally (component/stop reg) (component/stop c)))))))

(def ^:private data (LocalDate/parse "2026-03-01"))

;; O ator como a borda o entrega (o `amb` da politica e' {"ator" ator "recurso" recurso}). `papel`
;; e' campo de FIXTURE, nao vocabulario de sistema: serve so' para escrever uma expressao de autorizacao
;; que depende do ator sem arrastar o cadastro de Mesa para dentro deste teste. Os fatos reais
;; (`é_presidente_da_mesa` e irmaos) ja tem cobertura propria em cadastros.
;;
;; DOIS ACHADOS DA REVISAO DE SEGURANCA, ambos consertados e guardados aqui:
;;   CRITICO-2 — chave kebab-case era INALCANCAVEL pela DSL (`ator.identidade_id` procurava `:identidade_id`
;;     e o mapa tem `:identidade-id`). `motor/api/alcancavel-pela-dsl` acrescenta o alias. Sem isso, os
;;     CINCO fatos que a migration 0079 cita como razao da 3-A eram todos inescreviveis — a feature
;;     entregava so' `ator.papeis`, o MESMO eixo do gate grosso que ela existia para superar.
;;   CRITICO-1 — argumento nil num fato resolvido descia ao SQL, nao casava linha e voltava `false` LIMPO,
;;     que `nao`/`!=` viravam PERMISSAO. `nao é_presidente_da_mesa(ator.identidade_id, hoje())` autorizava
;;     todo mundo. `motor/runtime/a-chamada` agora falha fechado em argumento nil.
(defn- ator
  "O ator com a FORMA REAL de producao (`identidade/autenticacao.clj`): `{:identidade-id :ente-id :papeis}`.

  A primeira versao deste arquivo inventava `:papel` (singular) — campo que NAO existe no ator real — e o
  unico teste do caminho POSITIVO so' passava por causa dele. Era a armadilha da fixture com vocabulario
  ficticio: verde sobre um mundo que o produto nao produz. Achado CRITICO-2 da revisao de seguranca.

  `:papeis` e' SET, nao vetor — `identidade/db/vinculo.clj/papeis-de` devolve `(set (map ...))`. A FORMA
  importa tanto quanto o nome: o operador `in` da DSL e' `(contains? b a)`, e em VETOR `contains?` testa
  INDICE, nao valor — entao um papel dentro de um vetor responde `false` silenciosamente. Fail-closed
  (nega), mas a feature inteira pareceria quebrada. A segunda versao desta fixture errou exatamente isso,
  na correcao do erro de forma anterior."
  [ente & papeis]
  {:ente-id ente :identidade-id (random-uuid) :papeis (set papeis)})

(defn- protocolar! [tx ente tid]
  (:id (prop/protocolar! tx {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026 :template-id tid
                             :uf "CE" :municipio-nome "Fortaleza" :ementa "Dispoe sobre X"})))

(defn- montar-rito!
  "Rito minimo de 1 passo. `autorizacao` e' a expressao sob teste."
  [tx ente autorizacao]
  (let [tid (random-uuid)]
    ;; chave DERIVADA do id: o UNIQUE e' (ente_id, chave, versao), e um teste que monte dois ritos no
    ;; mesmo ente com a mesma chave morre no banco em vez de provar o que queria.
    (tram/criar-template! tx {:id tid :ente-id ente :chave (str "rito_3a_" tid) :versao 1 :tipo "projeto_lei"
                              :nome "Rito [FIXTURE 3-A]" :estado-inicial "protocolada"})
    (doseq [[ch term] [["protocolada" false] ["em_comissoes" false] ["arquivada" true]]]
      (tram/criar-estado! tx {:id (random-uuid) :ente-id ente :template-id tid :chave ch :nome ch :terminal term}))
    (tram/criar-transicao! tx {:id (random-uuid) :ente-id ente :template-id tid :de-estado "protocolada"
                               :para-estado "em_comissoes" :gatilho "despachar" :guarda nil
                               :autorizacao autorizacao :ordem 1})
    tid))

(defn- despachar! [tx ente pid tid a]
  (tram/transicionar! tx {:registro *registro* :ente-id ente :proposicao-id pid :template-id tid
                          :gatilho "despachar" :ator a :ator-id (:identidade-id a) :agora data}))

;; ---------- o eixo: autorizacao ausente, satisfeita e negada ----------

(deftest autorizacao-NULA-preserva-o-comportamento-de-hoje
  ;; NULL = sem restricao ALEM do gate da rota. Espelha `guarda NULL`. Exigir a coluna preenchida
  ;; quebraria todo rito ja cadastrado (inclusive o da semente) — o preco esta declarado na migration 0079
  ;; e pago na LEITURA, que passa a dizer por gatilho se o ator pode dispara-lo.
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [tid (montar-rito! tx ente nil)
              pid (protocolar! tx ente tid)]
          (is (true? (:transicionou? (despachar! tx ente pid tid (ator ente "qualquer_um"))))
              "sem expressao de autorizacao, a transicao corre como antes da 3-A"))))))

(deftest autorizacao-SATISFEITA-deixa-passar
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [tid (montar-rito! tx ente "\"presidente\" in ator.papeis")
              pid (protocolar! tx ente tid)]
          (is (true? (:transicionou? (despachar! tx ente pid tid (ator ente "presidente"))))
              "quem a expressao autoriza, transiciona"))))))

(deftest autorizacao-NEGADA-lanca-negacao-e-NAO-vira-recusa-de-dominio
  ;; O ponto mais importante do arquivo. `{:transicionou? false}` significa "a Casa nao permite este ato
  ;; AGORA" -> 409, e e' informacao para o OPERADOR (tente outro ato, ou depois). "Voce nao pode" e' 403 e
  ;; e' informacao para o ADMINISTRADOR (peca acesso). Colapsar os dois manda cada um procurar no lugar
  ;; errado — e, pior, um 409 sobre negacao de autorizacao sugere que o ato e' possivel, so' que nao agora.
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [tid (montar-rito! tx ente "\"presidente\" in ator.papeis")
              pid (protocolar! tx ente tid)
              e (try (despachar! tx ente pid tid (ator ente "estagiario")) nil
                     (catch clojure.lang.ExceptionInfo ex ex))]
          (is (some? e) "negar autorizacao LANCA — nao devolve {:transicionou? false}")
          (is (true? (authz/negado? e)) "e lanca a NEGACAO do kernel (:autorizacao/negado -> 403)"))))))

(deftest negacao-acontece-ANTES-de-gravar
  ;; Autorizar depois de gravar seria auditoria, nao autorizacao.
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [tid (montar-rito! tx ente "\"presidente\" in ator.papeis")
              pid (protocolar! tx ente tid)]
          (try (despachar! tx ente pid tid (ator ente "estagiario")) (catch Exception _ nil))
          (is (= "protocolada" (:estado (prop/buscar tx ente pid)))
              "estado intacto: a negacao veio antes do mudar-estado!")
          (is (empty? (tram/historico-da-proposicao tx ente pid))
              "historico vazio: a negacao veio antes do registrar-transicao!"))))))

;; ---------- a decisao de desenho: negado NAO cai na proxima candidata ----------

(deftest negado-na-escolhida-NAO-cai-na-proxima-candidata
  ;; Se a autorizacao filtrasse candidatas (junto do guard) em vez de ser porta sobre a ESCOLHIDA, um ator
  ;; sem permissao na 1a transicao cairia na 2a — e o DESTINO da materia passaria a depender de QUEM pediu.
  ;; Duas pessoas, o mesmo gatilho, estados finais diferentes. Quem escolhe o destino e' o rito (`ordem`).
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [tid (montar-rito! tx ente "\"presidente\" in ator.papeis")
              ;; 2a candidata do MESMO gatilho, ordem 2, SEM autorizacao e com destino DIFERENTE
              _ (tram/criar-transicao! tx {:id (random-uuid) :ente-id ente :template-id tid
                                           :de-estado "protocolada" :para-estado "arquivada"
                                           :gatilho "despachar" :guarda nil :autorizacao nil :ordem 2})
              pid (protocolar! tx ente tid)
              e (try (despachar! tx ente pid tid (ator ente "estagiario")) nil
                     (catch clojure.lang.ExceptionInfo ex ex))]
          (is (true? (authz/negado? e)) "nega, em vez de escorregar para a candidata seguinte")
          (is (= "protocolada" (:estado (prop/buscar tx ente pid)))
              "e a materia NAO foi para 'arquivada' — o destino nao depende de quem pediu"))))))

;; ---------- fail-closed nas duas pontas: save e runtime ----------

(deftest expressao-de-autorizacao-MAL-FORMADA-e-recusada-no-SAVE
  ;; Mesmo gate do guard (Inv.4): config quebrada nao entra no banco. E a razao aqui e' mais forte que no
  ;; guard — `check!` traduz LANCE em NEGACAO, entao autorizacao mal-formada que passasse o save viraria
  ;; "ninguem pode tramitar", descoberto no meio de uma sessao.
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [tid (montar-rito! tx ente nil)]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"autorizacao"
                (tram/criar-transicao! tx {:id (random-uuid) :ente-id ente :template-id tid
                                           :de-estado "em_comissoes" :para-estado "arquivada"
                                           :gatilho "arquivar" :guarda nil
                                           :autorizacao "\"presidente\" in" :ordem 1}))
              "expressao que nao parseia NAO entra no banco"))))))

(deftest expressao-que-LANCA-em-runtime-NEGA-em-vez-de-explodir
  ;; Campo ausente no ator -> o avaliador lanca (fail-closed da fatia 5). `check!` traduz em negacao: quem
  ;; nao consegue decidir NEGA, nunca fica indeterminado -> nunca vira 500.
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [tid (montar-rito! tx ente "ator.campo_que_nao_existe")
              pid (protocolar! tx ente tid)
              e (try (despachar! tx ente pid tid (ator ente "presidente")) nil
                     (catch clojure.lang.ExceptionInfo ex ex))]
          (is (true? (authz/negado? e))
              "expressao inavaliavel NEGA (403), nao propaga como erro interno (500)"))))))

;; ---------- os dois criticos da revisao de seguranca, guardados ----------

(deftest CRITICO-1-negacao-de-fato-com-argumento-nil-NAO-autoriza
  ;; O achado mais grave da revisao, e o mais dificil de ver: a expressao esta SINTATICAMENTE correta e
  ;; SEMANTICAMENTE plausivel. "o relator nao pode ser quem preside" se escreve exatamente assim. Antes do
  ;; conserto, um campo de ator que nao resolve entregava nil ao fato, o fato nao casava linha, devolvia
  ;; `false` LIMPO E BOOLEANO — e o `nao` virava PERMISSAO PARA TODO MUNDO, sem erro e sem log.
  ;; Aqui o campo e' propositalmente inexistente: e' a unica forma de produzir o nil agora que
  ;; `alcancavel-pela-dsl` tornou as chaves reais alcancaveis.
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [tid (montar-rito! tx ente "nao é_presidente_da_mesa(ator.campo_que_nao_resolve, hoje())")
              pid (protocolar! tx ente tid)
              e (try (despachar! tx ente pid tid (ator ente "secretario")) nil
                     (catch clojure.lang.ExceptionInfo ex ex))]
          (is (true? (authz/negado? e))
              "fato com argumento nil NEGA — nao devolve false p/ o `nao` transformar em permissao")
          (is (= "protocolada" (:estado (prop/buscar tx ente pid)))
              "e a materia nao andou"))))))

(deftest CRITICO-2-a-identidade-do-ator-e-alcancavel-pela-DSL
  ;; Os cinco fatos que a migration 0079 cita como razao da 3-A (`é_presidente_da_mesa` e irmaos) exigem
  ;; IDENTIDADE-ID como 1o argumento. Enquanto `ator.identidade_id` respondia nil, NENHUM era escrivivel —
  ;; a 3-A entregava so' `ator.papeis`, o mesmo eixo do gate grosso que ela existia para superar.
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [a (ator ente "secretario")
              ;; a expressao le' a identidade do ATOR e a compara com ela mesma: passa se, e so' se, o
              ;; campo for de fato alcancavel. Com kebab inalcancavel, os dois lados seriam nil -> lanca.
              tid (montar-rito! tx ente "ator.identidade_id == ator.identidade_id")
              pid (protocolar! tx ente tid)]
          (is (true? (:transicionou? (despachar! tx ente pid tid a)))
              "`ator.identidade_id` resolve a chave kebab `:identidade-id` do ator real"))))))

(deftest CRITICO-3-exige-autorizacao-diz-a-verdade-na-LEITURA
  ;; `exige-autorizacao` era `false` SEMPRE: a consulta da LEITURA (`transicoes-do-estado`) trazia `:guarda`
  ;; mas nao `:autorizacao` — a coluna so' entrou na consulta da ESCRITA. O campo cuja unica razao de
  ;; existir e' tornar visivel o rito que esqueceu de declarar quem dispara estava 100% falso.
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [tid (montar-rito! tx ente "\"presidente\" in ator.papeis")
              gs (logic/gatilhos-possiveis (tram/transicoes-do-estado tx ente tid "protocolada"))]
          (is (true? (:exige-autorizacao (first gs)))
              "gatilho COM expressao declarada aparece como exigindo autorizacao"))))
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [tid (montar-rito! tx ente nil)
              gs (logic/gatilhos-possiveis (tram/transicoes-do-estado tx ente tid "protocolada"))]
          (is (false? (:exige-autorizacao (first gs)))
              "e o gatilho SEM expressao aparece como nao exigindo — e' o default permissivo, visivel"))))))
