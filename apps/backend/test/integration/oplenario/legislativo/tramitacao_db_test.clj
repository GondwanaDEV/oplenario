(ns oplenario.legislativo.tramitacao-db-test
  "INTEGRACAO (PG real): eixo C — tramitacao por motor declarativo. Prova o ENGINE: do estado atual, sob
  um gatilho, escolhe a 1a transicao cujo GUARD passa (reusa o avaliador do motor, disciplina 5), grava o
  historico APPEND-ONLY e muda o estado da proposicao; guard que bloqueia = resultado normal (sem transicao).
  Usa um template FIXTURE ilustrativo (nao regulacao real — [GAP] de §22.7.5 segue GAP)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [next.jdbc :as jdbc]
            [oplenario.cadastros.relacoes.cadastro :as rel-cad]
            [oplenario.config :as config]
            [oplenario.identidade.relacoes.identidade :as rel-id]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.legislativo.db.proposicao :as prop]
            [oplenario.legislativo.db.tramitacao :as tram]
            [oplenario.migracao :as migracao]
            [oplenario.motor.components.registro-fatos :as rf])
  (:import (java.time LocalDate)))

(def ^:dynamic *ds* nil)
(def ^:dynamic *registro* nil)

(use-fixtures :once
  (fn [t]
    (let [c   (component/start (datasource/datasource (config/carregar)))
          ;; RegistroFatos como o sistema monta (relacoes de cadastros+identidade) — o guard nil/'falso'
          ;; nao resolve fato, mas guarda-dsl exige um registro startado (assert de costura, F2).
          reg (component/start (rf/registro-fatos (merge rel-cad/relacoes rel-id/relacoes)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c) *registro* reg]
        (try (t) (finally (component/stop reg) (component/stop c)))))))

(def ^:private data (LocalDate/parse "2026-03-01"))

(defn- montar-template! [tx ente]
  (let [tid (random-uuid)]
    (tram/criar-template! tx {:id tid :ente-id ente :chave "rito_ordinario" :versao 1
                              :nome "Rito Ordinario [FIXTURE]" :estado-inicial "protocolada"})
    (doseq [[ch nm term] [["protocolada" "Protocolada" false] ["em_comissoes" "Em comissoes" false]
                          ["em_pauta" "Em pauta" false] ["arquivada" "Arquivada" true]]]
      (tram/criar-estado! tx {:id (random-uuid) :ente-id ente :template-id tid :chave ch :nome nm :terminal term}))
    ;; protocolada --despachar--> em_comissoes (sem guard = sempre)
    (tram/criar-transicao! tx {:id (random-uuid) :ente-id ente :template-id tid :de-estado "protocolada"
                               :para-estado "em_comissoes" :gatilho "despachar" :guarda nil :ordem 1})
    ;; em_comissoes --concluir--> em_pauta (guard 'falso' = sempre bloqueia, p/ testar o ramo de bloqueio)
    (tram/criar-transicao! tx {:id (random-uuid) :ente-id ente :template-id tid :de-estado "em_comissoes"
                               :para-estado "em_pauta" :gatilho "concluir" :guarda "falso" :ordem 1})
    tid))

(defn- protocolar! [tx ente]
  (:id (prop/protocolar! tx {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026
                             :uf "CE" :municipio-nome "Fortaleza" :ementa "Dispoe sobre X"})))

(defn- transicionar [tx ente tid pid gatilho]
  (tram/transicionar! tx {:registro *registro* :ente-id ente :proposicao-id pid :template-id tid
                          :gatilho gatilho :agora data}))

(deftest engine-guard-e-mudanca-de-estado
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [tid (montar-template! tx ente)
              pid (protocolar! tx ente)]               ; nasce 'protocolada'
          (let [r1 (transicionar tx ente tid pid "despachar")]
            (is (true? (:transicionou? r1)) "guard nil -> transiciona")
            (is (= "em_comissoes" (:para r1)))
            (is (= "em_comissoes" (:estado (prop/buscar tx ente pid))) "estado da proposicao mudou"))
          (let [r2 (transicionar tx ente tid pid "concluir")]
            (is (false? (:transicionou? r2)) "guard 'falso' bloqueia TODAS as candidatas")
            (is (= "em_comissoes" (:estado (prop/buscar tx ente pid))) "estado inalterado apos bloqueio"))
          (let [r3 (transicionar tx ente tid pid "gatilho_inexistente")]
            (is (false? (:transicionou? r3)) "gatilho sem transicao = sem candidata"))
          (is (= 1 (count (tram/historico-da-proposicao tx ente pid)))
              "so a transicao que OCORREU foi ao historico"))))))

(deftest historico-append-only
  (let [ente (random-uuid) hid (atom nil)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [tid (montar-template! tx ente) pid (protocolar! tx ente)]
          (transicionar tx ente tid pid "despachar")
          (reset! hid (:id (first (tram/historico-da-proposicao tx ente pid)))))))
    (is (thrown? Exception
                 (tenancy/com-tenant* *ds* ente
                   (fn [tx] (jdbc/execute-one! tx ["UPDATE legislativo.proposicao_transicao_historico SET gatilho = 'hack' WHERE id = ?" @hid]))))
        "historico de transicao e' append-only (sem UPDATE/DELETE)")))

(deftest criar-transicao-rejeita-guard-mal-formado
  ;; Inv.4: o guard mal-escrito NAO persiste — a falha de tramitacao sai do caminho critico (rejeitada
  ;; na config, nao no meio do fluxo). criar-transicao! gateia via motor/validar-guarda.
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [tid (random-uuid)]
          (tram/criar-template! tx {:id tid :ente-id ente :chave "rito" :versao 1
                                    :nome "Rito [FIXTURE]" :estado-inicial "protocolada"})
          (is (thrown? Exception
                       (tram/criar-transicao! tx {:id (random-uuid) :ente-id ente :template-id tid
                                                  :de-estado "protocolada" :para-estado "em_comissoes"
                                                  :gatilho "despachar" :guarda "( falso" :ordem 1}))
              "guard que nao parseia e' rejeitado no save")
          (is (some? (tram/criar-transicao! tx {:id (random-uuid) :ente-id ente :template-id tid
                                                :de-estado "protocolada" :para-estado "em_comissoes"
                                                :gatilho "despachar" :guarda "verdadeiro" :ordem 2}))
              "guard bem-formado persiste normalmente"))))))

(deftest historico-com-limite-traz-os-mais-recentes-nao-os-mais-antigos
  ;; review MAJOR fe-9-ficha-materia (repositorio.clj:260/264, db/tramitacao.clj:82): o teto anterior era um
  ;; `(take 100 ...)` EM MEMORIA sobre o resultado ASC — silenciosamente preservava as transicoes MAIS
  ;; ANTIGAS e descartava as MAIS RECENTES quando o historico passava do teto (o oposto do que uma ficha
  ;; viva deveria mostrar), e ainda pagava o fetch da tabela inteira do Postgres so' pra' descartar a
  ;; maioria depois. Prova: 150 linhas com `ocorrido_em` EXPLICITO e distinto (insert direto — o Postgres
  ;; congela now() no inicio da tx, entao 150 chamadas a registrar-transicao! na MESMA tx empatariam no
  ;; MESMO instante e a ordem ASC/DESC ficaria indeterminada); com limite=100 a mais RECENTE (i=149)
  ;; sobrevive, a mais ANTIGA (i=0) e' descartada, a ordem devolvida continua cronologica ASC (o contrato
  ;; de ordem e' o MESMO com ou sem limite — so' o conjunto retido muda), e sem limite o comportamento
  ;; historico (todas as linhas) e' preservado.
  (let [ente (random-uuid) tid (random-uuid) pid (atom nil)
        base (java.time.Instant/parse "2026-01-01T00:00:00Z")
        ocorrido-em (fn [i] (.plusSeconds base i))]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (reset! pid (protocolar! tx ente))
        (dotimes [i 150]
          (jdbc/execute-one! tx
            ["INSERT INTO legislativo.proposicao_transicao_historico
              (ente_id, id, proposicao_id, template_id, de_estado, para_estado, gatilho, efetivado_em, ocorrido_em)
              VALUES (?, ?, ?, ?, ?, ?, ?, now(), ?)"
             ente (random-uuid) @pid tid "de" "para" "gatilho" (ocorrido-em i)]))))
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [todas (tram/historico-da-proposicao tx ente @pid)
              limitadas (tram/historico-da-proposicao tx ente @pid 100)]
          (is (= 150 (count todas)) "sem limite: comportamento historico preservado (todas as linhas)")
          (is (= 100 (count limitadas)) "com limite: o SQL aplica o teto")
          (is (= (ocorrido-em 149) (:ocorrido-em (last limitadas)))
              "a transicao MAIS RECENTE (i=149) sobrevive ao corte")
          (is (not-any? #(= (ocorrido-em 0) (:ocorrido-em %)) limitadas)
              "a transicao MAIS ANTIGA (i=0) foi descartada — o corte preserva o recente, nao o antigo")
          (is (= (map ocorrido-em (range 50 150)) (map :ocorrido-em limitadas))
              "os 100 mais recentes (i=50..149), em ordem cronologica ASC"))))))

(deftest rls-isola-template-cross-tenant
  (let [a (random-uuid) b (random-uuid) tid (atom nil)]
    (tenancy/com-tenant* *ds* a (fn [tx] (reset! tid (montar-template! tx a))))
    (is (seq (tenancy/com-tenant* *ds* a (fn [tx] (tram/transicoes-de tx a @tid "protocolada" "despachar")))) "A ve as proprias transicoes")
    (is (empty? (tenancy/com-tenant* *ds* b (fn [tx] (tram/transicoes-de tx b @tid "protocolada" "despachar")))) "B NAO ve o template de A (RLS)")))

(deftest materia-inexistente-nao-se-disfarca-de-guard-bloqueado
  ;; Fatia 2 (a borda HTTP da tramitacao) — ESPELHO do fail-closed que `transicionar-parecer!` ganhou na
  ;; review F3.6a, cumprindo o [CARRY disc.6] que pede paridade explicita entre os dois sujeitos.
  ;; ANTES: materia inexistente no tenant lia estado `nil` em `estado+lock`, nao casava transicao candidata
  ;; nenhuma e SAIA COMO `{:transicionou? false}` — que o contrato de `transicionar!` define como "o guard
  ;; bloqueou". A borda HTTP entao responderia 409 "a Casa nao permite este ato agora" sobre uma materia
  ;; que NAO EXISTE: plausivel, confiante e errada. O 404 e o 409 dizem coisas diferentes ao operador.
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [tid (montar-template! tx ente)
              fantasma (random-uuid)
              e (is (thrown-with-msg? clojure.lang.ExceptionInfo #"inexistente"
                      (transicionar tx ente tid fantasma "despachar")))]
          (is (= :conflito/transicao (:tipo (ex-data e)))
              "tag de conflito (a borda traduz), NUNCA {:transicionou? false}"))))))

;; ======================= Fatia 3 — a LEITURA: o que a Casa permite AGORA =======================

(deftest transicoes-do-estado-lista-TODOS-os-gatilhos-do-estado-atual
  ;; `transicoes-de` (o que a ENGINE usa) filtra por gatilho: ela ja' sabe qual ato foi pedido. A LEITURA
  ;; tem o problema inverso — o operador ainda nao sabe qual ato pedir, e sem esta lista teria de adivinhar
  ;; a string do gatilho, o que torna a rota de escrita inutilizavel pela interface.
  ;; O `guarda` vem JUNTO de proposito: e' ele, e so' ele, que distingue um ato que o rito declara
  ;; incondicional de um que pode ser recusado no momento do disparo.
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [tid (montar-template! tx ente)]
          ;; segundo gatilho a partir de 'protocolada' — e com guard, ao contrario de 'despachar'
          (tram/criar-transicao! tx {:id (random-uuid) :ente-id ente :template-id tid :de-estado "protocolada"
                                     :para-estado "arquivada" :gatilho "arquivar" :guarda "falso" :ordem 2})
          (let [linhas (tram/transicoes-do-estado tx ente tid "protocolada")]
            (is (= ["despachar" "arquivar"] (mapv :gatilho linhas))
                "TODOS os gatilhos do estado, na ORDEM declarada no rito — nao so' os de UM gatilho")
            (is (= [nil "falso"] (mapv :guarda linhas))
                "o guard acompanha a linha: e' o unico dado que separa ato incondicional de ato recusavel")
            (is (= ["em_comissoes" "arquivada"] (mapv :para-estado linhas))
                "o destino que o rito declara p/ cada ato"))
          (is (empty? (tram/transicoes-do-estado tx ente tid "arquivada"))
              "estado terminal do fixture nao declara ato nenhum"))))))

(deftest estado-no-template-separa-TERMINAL-de-DESCONHECIDO
  ;; Lista de gatilhos vazia tem DUAS causas que pedem acoes opostas do operador: (a) o rito acabou
  ;; (estado terminal) — nada a fazer; (b) o rito nao declara saida deste estado, ou nem conhece o estado —
  ;; a materia esta' num beco, e alguem tem de mexer na CONFIG. Sem este dado a borda so' poderia dizer
  ;; "nenhum ato disponivel", que e' verdadeiro e inutil.
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [tid (montar-template! tx ente)]
          (is (false? (:terminal (tram/estado-no-template tx ente tid "protocolada"))))
          (is (true? (:terminal (tram/estado-no-template tx ente tid "arquivada"))))
          (is (nil? (tram/estado-no-template tx ente tid "estado_que_o_rito_nao_conhece"))
              "nil = o rito NAO declara este estado (materia anterior ao rito, ou rito trocado sob os pes)"))))))
