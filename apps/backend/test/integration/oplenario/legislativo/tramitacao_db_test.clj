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
            [oplenario.kernel.db-util :as db-util]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.legislativo.db.proposicao :as prop]
            [oplenario.legislativo.db.tramitacao :as tram]
            [oplenario.legislativo.db.votacao :as votacao]
            [oplenario.legislativo.relacoes :as rel-legis]
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
          ;; 3-B: +rel-legis/relacoes — sem ele o guard `aprovada_em_votacao(...)` LANCARIA
          ;; "fato sem fn registrada" (fail-closed do resolver), nao responderia falso.
          reg (component/start (rf/registro-fatos (merge rel-cad/relacoes rel-id/relacoes rel-legis/relacoes)))]
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

(defn- transicionar
  ([tx ente tid pid gatilho] (transicionar tx ente tid pid gatilho nil))
  ([tx ente tid pid gatilho alegado]
   (tram/transicionar! tx {:registro *registro* :ente-id ente :proposicao-id pid :template-id tid
                           :gatilho gatilho :alegado alegado :agora data})))

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

;; ===================== ADR-0004 — a guarda so' le' verdade apurada (frente `guarda-so-apurado`) =====
;; O gate de SINTAXE acima (`motor/validar-guarda` aridade-1) nao basta: `alegado.parecer_favoravel` e'
;; sintaticamente perfeito — e' vocabulario ERRADO. `criar-transicao!` agora declara, por COLUNA, quem
;; pode aparecer no `amb`: a guarda so' o SUJEITO do template (nunca `alegado`, o corpo do POST); a
;; autorizacao so' `ator`/`recurso` (o amb de `politica-dsl`). O motor nao conhece nenhum dos dois nomes
;; (§22.10) — quem declara e' este ns, via `nuc/identificadores-raiz`.

(deftest criar-transicao-rejeita-alegado-no-guard
  ;; `alegado` e' o corpo do POST — a mesma afirmacao-da-propria-precondicao que a Decisao B fechou um
  ;; nivel acima (porta escolhida pelo corpo). Aqui e' o CONTEUDO da porta unica que afirma a si mesma.
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [tid (random-uuid)]
          (tram/criar-template! tx {:id tid :ente-id ente :chave "rito" :versao 1
                                    :nome "Rito [FIXTURE]" :estado-inicial "protocolada"})
          (let [e (is (thrown-with-msg? clojure.lang.ExceptionInfo #"alegado"
                        (tram/criar-transicao! tx {:id (random-uuid) :ente-id ente :template-id tid
                                                   :de-estado "protocolada" :para-estado "em_comissoes"
                                                   :gatilho "avancar" :guarda "alegado.parecer_favoravel"
                                                   :ordem 1})))]
            (is (= :guarda-vocabulario-invalido (:erro (ex-data e)))
                "tag DISTINTA de :guarda-invalida (sintaxe) — quem cadastra sabe qual dos dois consertar"))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"alegado"
                (tram/criar-transicao! tx {:id (random-uuid) :ente-id ente :template-id tid
                                           :de-estado "protocolada" :para-estado "em_comissoes"
                                           :gatilho "avancar2" :guarda "alegado.x == verdadeiro"
                                           :ordem 1}))
              "recusado tambem dentro de um binop — o walk nao para na raiz"))))))

(deftest criar-transicao-rejeita-vocabulario-errado-na-autorizacao
  ;; A coluna `autorizacao` roda via `politica-dsl` — o amb dela e' SEMPRE {\"ator\" ... \"recurso\" ...},
  ;; nunca o sujeito da tramitacao. `alegado` (canal do guard) e `proposicao` (vocabulario da coluna
  ;; ERRADA) sao os dois jeitos de escrever uma autorizacao que so' falharia em RUNTIME, no meio do ato —
  ;; o buraco latente que o ADR-0004 fecha de graca.
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [tid (random-uuid)]
          (tram/criar-template! tx {:id tid :ente-id ente :chave "rito" :versao 1
                                    :nome "Rito [FIXTURE]" :estado-inicial "protocolada"})
          (let [e (is (thrown-with-msg? clojure.lang.ExceptionInfo #"alegado"
                        (tram/criar-transicao! tx {:id (random-uuid) :ente-id ente :template-id tid
                                                   :de-estado "protocolada" :para-estado "em_comissoes"
                                                   :gatilho "avancar" :guarda nil
                                                   :autorizacao "alegado.x" :ordem 1})))]
            (is (= :autorizacao-vocabulario-invalido (:erro (ex-data e)))))
          (let [e (is (thrown-with-msg? clojure.lang.ExceptionInfo #"proposicao"
                        (tram/criar-transicao! tx {:id (random-uuid) :ente-id ente :template-id tid
                                                   :de-estado "protocolada" :para-estado "em_comissoes"
                                                   :gatilho "avancar2" :guarda nil
                                                   :autorizacao "proposicao.estado == \"x\"" :ordem 1})))]
            (is (= :autorizacao-vocabulario-invalido (:erro (ex-data e)))
                "vocabulario da coluna ERRADA (o do guard), nao so' vocabulario nenhum — recusado do mesmo jeito")))))))

(deftest criar-transicao-aceita-vocabulario-legitimo-nas-duas-colunas
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [tid (random-uuid)]
          (tram/criar-template! tx {:id tid :ente-id ente :chave "rito" :versao 1
                                    :nome "Rito [FIXTURE]" :estado-inicial "protocolada"})
          (is (some? (tram/criar-transicao! tx {:id (random-uuid) :ente-id ente :template-id tid
                                                :de-estado "protocolada" :para-estado "em_comissoes"
                                                :gatilho "despachar"
                                                :guarda "proposicao.estado == \"protocolada\""
                                                ;; a MESMA forma que a 3-A usa hoje (tramitacao_autorizacao_db_test)
                                                :autorizacao "\"presidente\" in ator.papeis" :ordem 1}))
              "guard sobre o sujeito + autorizacao sobre ator: as DUAS colunas com vocabulario legitimo")
          (is (some? (tram/criar-transicao! tx {:id (random-uuid) :ente-id ente :template-id tid
                                                :de-estado "protocolada" :para-estado "arquivada"
                                                :gatilho "arquivar" :guarda nil :autorizacao nil :ordem 2}))
              "guard/autorizacao NIL continuam VALIDOS — sem restricao de vocabulario a aplicar"))))))

(deftest criar-transicao-declara-vocabulario-PELO-SUJEITO-do-template
  ;; `template_transicao` e' subject-agnostica (o mesmo INSERT governa proposicao E parecer), mas cada
  ;; TEMPLATE concreto so' fala de UM sujeito — fixado em `criar-template!`. O gate resolve a ambiguidade
  ;; lendo o `sujeito` do template (mesma consulta pontual de `db/proposicao/template-meta`), NAO com a
  ;; uniao dos dois vocabularios: um guard `parecer.x` sobre um template de PROPOSICAO tem de ser recusado
  ;; do mesmo jeito que `alegado.x` — senao um rito de parecer legitimo escrito por engano no template
  ;; errado passaria pelo cadastro e so' explodiria no meio do ato.
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [tid-parecer (random-uuid) tid-prop (random-uuid)]
          (tram/criar-template! tx {:id tid-parecer :ente-id ente :chave "rito_parecer" :versao 1
                                    :nome "Rito parecer [FIXTURE]" :estado-inicial "aberto" :sujeito "parecer"})
          (tram/criar-template! tx {:id tid-prop :ente-id ente :chave "rito_prop" :versao 1
                                    :nome "Rito proposicao [FIXTURE]" :estado-inicial "protocolada"})
          (is (some? (tram/criar-transicao! tx {:id (random-uuid) :ente-id ente :template-id tid-parecer
                                                :de-estado "aberto" :para-estado "emitido" :gatilho "emitir"
                                                :guarda "parecer.estado == \"aberto\"" :ordem 1}))
              "template de sujeito PARECER: guard sobre 'parecer' e' vocabulario legitimo")
          (let [e (is (thrown-with-msg? clojure.lang.ExceptionInfo #"proposicao"
                        (tram/criar-transicao! tx {:id (random-uuid) :ente-id ente :template-id tid-parecer
                                                   :de-estado "aberto" :para-estado "emitido"
                                                   :gatilho "emitir2" :guarda "proposicao.estado == \"x\""
                                                   :ordem 1})))]
            (is (= :guarda-vocabulario-invalido (:erro (ex-data e)))
                "MESMO template de sujeito parecer: 'proposicao' e' vocabulario do OUTRO sujeito — recusado"))
          (let [e (is (thrown-with-msg? clojure.lang.ExceptionInfo #"parecer"
                        (tram/criar-transicao! tx {:id (random-uuid) :ente-id ente :template-id tid-prop
                                                   :de-estado "protocolada" :para-estado "em_comissoes"
                                                   :gatilho "avancar" :guarda "parecer.estado == \"x\""
                                                   :ordem 1})))]
            (is (= :guarda-vocabulario-invalido (:erro (ex-data e)))
                "e o simetrico: template de sujeito proposicao recusa guard que fala de 'parecer'")))))))

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
          (is (false? (:terminal (prop/estado-no-template tx ente tid "protocolada"))))
          (is (true? (:terminal (prop/estado-no-template tx ente tid "arquivada"))))
          (is (nil? (prop/estado-no-template tx ente tid "estado_que_o_rito_nao_conhece"))
              "nil = o rito NAO declara este estado (materia anterior ao rito, ou rito trocado sob os pes)"))))))

;; ======= Fatia 2 — quem diz o que e' TERMINAL e' o RITO, nao uma string cravada em SQL =======

(defn- montar-rito-com-desarquivamento!
  "Rito que declara o DESARQUIVAMENTO — ato corriqueiro de praticamente todo regimento brasileiro (fim de
  legislatura arquiva a materia nao votada; na legislatura seguinte o autor requer o desarquivamento).
  `arquivada-terminal?` e' o unico parametro porque e' exatamente a decisao que o rito toma e que o codigo
  nao pode tomar por ele: a MESMA chave de estado e' fim-de-processo numa Casa e ponto de retorno noutra."
  [tx ente arquivada-terminal?]
  (let [tid (random-uuid)]
    (tram/criar-template! tx {:id tid :ente-id ente :chave "rito_com_desarquivamento" :versao 1
                              :nome "Rito com desarquivamento [FIXTURE]" :estado-inicial "protocolada"})
    (doseq [[ch nm term] [["protocolada" "Protocolada" false]
                          ["arquivada" "Arquivada" arquivada-terminal?]]]
      (tram/criar-estado! tx {:id (random-uuid) :ente-id ente :template-id tid :chave ch :nome nm :terminal term}))
    (tram/criar-transicao! tx {:id (random-uuid) :ente-id ente :template-id tid :de-estado "protocolada"
                               :para-estado "arquivada" :gatilho "arquivar" :guarda nil :ordem 1})
    (tram/criar-transicao! tx {:id (random-uuid) :ente-id ente :template-id tid :de-estado "arquivada"
                               :para-estado "protocolada" :gatilho "desarquivar" :guarda nil :ordem 1})
    tid))

(deftest rito-que-nao-declara-arquivada-terminal-DESARQUIVA
  ;; O caso real que estava IMPOSSIVEL, e nao por decisao de ninguem: o trigger `trg_proposicoes_imut_estado`
  ;; (mig 0013) executava `shared.imut_trava_estado_terminal('publicada','arquivada')` — DUAS palavras de
  ;; camara cravadas em SQL, Inv.4 violado no schema. Era inofensivo enquanto `estado` nunca se movia; a
  ;; borda de tramitacao o acendeu: o trigger abortava TODO UPDATE que partisse de 'arquivada', mesmo com o
  ;; rito da Casa declarando a transicao. A trava saiu do banco (mig 0078) e virou dado do rito.
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [tid (montar-rito-com-desarquivamento! tx ente false)
              pid (protocolar! tx ente)]
          (is (true? (:transicionou? (transicionar tx ente tid pid "arquivar"))))
          (is (= "arquivada" (:estado (prop/buscar tx ente pid))))
          (let [r (transicionar tx ente tid pid "desarquivar")]
            (is (true? (:transicionou? r))
                "o rito NAO declara 'arquivada' terminal -> a Casa desarquiva")
            (is (= "protocolada" (:para r)))
            (is (= "protocolada" (:estado (prop/buscar tx ente pid))))))))))

(deftest rito-que-DECLARA-terminal-recusa-a-saida-e-o-motivo-e-proprio
  ;; A protecao nao sumiu — ela mudou de lugar e de fonte: sai do literal SQL e passa a vir de
  ;; `template_estado.terminal`, que era campo DECORATIVO (a leitura o anunciava ao cliente como garantia;
  ;; a escrita nunca o consultava). A recusa por fim-de-rito e' DISTINTA de "nenhum guard passou" e de
  ;; "o rito nao declara esse ato aqui": quem recebe precisa saber se a Casa ENCERROU o processo ou se so'
  ;; negou o ato agora.
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [tid (montar-rito-com-desarquivamento! tx ente true)
              pid (protocolar! tx ente)]
          (transicionar tx ente tid pid "arquivar")
          (let [r (transicionar tx ente tid pid "desarquivar")]
            (is (false? (:transicionou? r)) "o rito declarou 'arquivada' TERMINAL -> nao ha' saida")
            (is (= :estado-terminal (:motivo r))
                "motivo PROPRIO — nao se colapsa com guard recusado nem com gatilho nao declarado")
            (is (= "arquivada" (:de r)))
            (is (= "arquivada" (:estado (prop/buscar tx ente pid))) "estado inalterado")
            (is (= 1 (count (tram/historico-da-proposicao tx ente pid)))
                "recusa por fim de rito NAO vai ao historico")))))))

(deftest os-tres-motivos-de-nao-transicao-sao-distintos
  ;; Antes os tres desfechos saiam como o MESMO `{:transicionou? false}`, e a borda respondia a mesma frase
  ;; para situacoes que pedem acoes OPOSTAS do operador: esperar/juntar o documento que o guard pede,
  ;; procurar outro ato, ou parar de tentar porque o processo acabou.
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [tid (montar-template! tx ente)                 ; 'arquivada' terminal; 'concluir' guard "falso"
              pid (protocolar! tx ente)]
          (transicionar tx ente tid pid "despachar")         ; -> em_comissoes
          (is (= :guarda-recusou (:motivo (transicionar tx ente tid pid "concluir")))
              "ha' candidata, o guard e' que negou AGORA")
          (is (= :gatilho-nao-declarado (:motivo (transicionar tx ente tid pid "gatilho_inexistente")))
              "o rito conhece o estado, mas nao declara ESTE ato a partir dele")
          )))
    ;; tenant PROPRIO p/ o 3o motivo: dois ritos GENERICOS ativos no mesmo ente sao config ambigua desde a
    ;; resolucao por especie (mig 0077) e `resolver-rito!` recusa o protocolo — o que provaria outra coisa.
    (let [ente2 (random-uuid)]
      (tenancy/com-tenant* *ds* ente2
        (fn [tx]
          (let [tid (montar-rito-com-desarquivamento! tx ente2 true)
                pid (protocolar! tx ente2)]
            (transicionar tx ente2 tid pid "arquivar")
            (is (= :estado-terminal (:motivo (transicionar tx ente2 tid pid "desarquivar")))
                "o rito encerrou o processo neste estado")))))))

(deftest estado-fora-do-rito-nao-se-disfarca-de-gatilho-nao-declarado
  ;; `template_transicao.de_estado` NAO tem FK p/ `template_estado.chave` (mig 0016) — um rito PODE declarar
  ;; transicao a partir de um estado que ele nunca listou. Por isso a checagem de terminal nao pode RECUSAR
  ;; quando o estado e' desconhecido (recusar mudaria comportamento de rito ja' em producao); ela so'
  ;; DIAGNOSTICA. O caminho feliz de um estado nao-declarado segue funcionando exatamente como antes.
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [tid (random-uuid)
              ;; o rito nasce ANTES da materia de proposito (fatia 4): `transicionar!` passou a CONFRONTAR o
              ;; `template-id` do argumento com o `template_id` da LINHA, entao uma materia protocolada sem
              ;; rito nao tramita mais por um rito passado a mao — e' o caminho que a engine agora recusa,
              ;; e nao e' o que este teste quer provar. O que ele prova continua identico: o rito da
              ;; materia declara TRANSICAO a partir de 'protocolada' sem nunca ter declarado o ESTADO.
              _ (tram/criar-template! tx {:id tid :ente-id ente :chave "rito_sem_estados" :versao 1
                                          :nome "Rito sem template_estado [FIXTURE]" :estado-inicial "protocolada"})
              pid (protocolar! tx ente)]
          ;; nenhum `criar-estado!`: o rito so' tem transicoes
          (tram/criar-transicao! tx {:id (random-uuid) :ente-id ente :template-id tid :de-estado "protocolada"
                                     :para-estado "em_comissoes" :gatilho "despachar" :guarda nil :ordem 1})
          (is (= :estado-fora-do-rito (:motivo (transicionar tx ente tid pid "gatilho_inexistente")))
              "o rito nem conhece o estado atual — diagnostico proprio, nao 'ato nao declarado'")
          (is (true? (:transicionou? (transicionar tx ente tid pid "despachar")))
              "e o ato que o rito DECLARA continua funcionando (a checagem diagnostica, nao recusa)"))))))


;; ============ 3-B — a transicao exige o ATO, nao o botao (decisao do Daouda) ============

(defn- montar-rito-que-exige-o-ato! 
  "Rito minimo cuja unica saida de 'protocolada' e' o estado de aprovacao, GUARDADA pelo fato do
  legislativo. Nenhuma string deste rito e' conhecida do codigo: 'protocolada'/'aprovada'/'aprovar' sao
  DADO do tenant (Inv.4) e so' existem nesta fixture."
  [tx ente]
  (let [tid (random-uuid)]
    (tram/criar-template! tx {:id tid :ente-id ente :chave "rito_3b" :versao 1
                              :nome "Rito que exige o ato [FIXTURE]" :estado-inicial "protocolada"})
    (doseq [[ch nm term] [["protocolada" "Protocolada" false] ["aprovada" "Aprovada" false]]]
      (tram/criar-estado! tx {:id (random-uuid) :ente-id ente :template-id tid :chave ch :nome nm :terminal term}))
    (tram/criar-transicao! tx {:id (random-uuid) :ente-id ente :template-id tid :de-estado "protocolada"
                               :para-estado "aprovada" :gatilho "aprovar"
                               :guarda "aprovada_em_votacao(proposicao.id)" :ordem 1})
    tid))

(defn- votar! 
  "Abre e encerra uma votacao 'simbolica' sobre a materia com `resultado`. Devolve o id da votacao.
  Simbolica de proposito: e' a modalidade que nao apura voto individual (o resultado e' explicito), o que
  mantem a fixture sobre o EIXO desta fatia — a existencia do ato — sem arrastar roster/quorum."
  [tx ente pid resultado]
  (let [{:keys [id lock-version]} (votacao/abrir! tx {:id (random-uuid) :ente-id ente :objeto-tipo "proposicao"
                                                      :objeto-id pid :modalidade "simbolica"
                                                      :quorum-tipo "maioria_simples"})]
    (votacao/encerrar! tx {:id id :ente-id ente :resultado resultado :lock-version lock-version})
    id))

(deftest a-materia-so-avanca-porque-a-casa-fez-o-ato
  ;; O BURACO que 3-B fecha: com todo guard nil (o estado do produto ate' aqui), quem tem senha de
  ;; `secretario` levava a materia de 'protocolada' a 'aprovada' num POST, sem votacao, sem sessao, sem
  ;; ninguem na Casa. Aqui o rito declara que aquela transicao exige o ATO, e o motor cobra.
  ;;
  ;; A prova tem de passar pelos QUATRO estados do mundo, porque tres deles produzem a mesma aparencia
  ;; ("ha' alguma votacao por ai'") e desfechos opostos:
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [tid (montar-rito-que-exige-o-ato! tx ente)]

          ;; (1) NENHUMA votacao -> nao avanca. E o motivo e' `:guarda-recusou`, nao um dos outros tres
          ;; desfechos: o rito DECLARA o ato, quem negou foi o mundo. E' a diferenca entre "ainda nao" e
          ;; "nunca por aqui", e o operador precisa dela.
          (let [pid (protocolar! tx ente)
                r (transicionar tx ente tid pid "aprovar")]
            (is (false? (:transicionou? r)) "sem votacao a materia NAO avanca — o botao deixou de bastar")
            (is (= :guarda-recusou (:motivo r)) "ha' ato declarado; foi o guard que negou AGORA")
            (is (= "protocolada" (:estado (prop/buscar tx ente pid))) "o rotulo tambem nao se move")
            (is (empty? (tram/historico-da-proposicao tx ente pid)) "guard que nega nao deixa historico"))

          ;; (2) votacao ABERTA (ainda apurando) -> nao avanca. Existe uma votacao sobre a materia; ela so'
          ;; nao terminou. Sem esta exclusao bastaria ABRIR a votacao para destravar o rito.
          (let [pid (protocolar! tx ente)]
            (votacao/abrir! tx {:id (random-uuid) :ente-id ente :objeto-tipo "proposicao" :objeto-id pid
                                :modalidade "simbolica" :quorum-tipo "maioria_simples"})
            (is (false? (:transicionou? (transicionar tx ente tid pid "aprovar")))
                "votacao ABERTA nao e' aprovacao — o ato ainda nao aconteceu"))

          ;; (3) votacao encerrada e REJEITADA -> nao avanca. O ato aconteceu e disse NAO.
          (let [pid (protocolar! tx ente)]
            (votar! tx ente pid "rejeitada")
            (is (false? (:transicionou? (transicionar tx ente tid pid "aprovar")))
                "a Casa deliberou e REJEITOU — o guard nao confunde 'houve votacao' com 'foi aprovada'"))

          ;; (4) votacao encerrada e APROVADA -> avanca, e so' agora o rotulo vem atras do ato.
          (let [pid (protocolar! tx ente)]
            (votar! tx ente pid "aprovada")
            (let [r (transicionar tx ente tid pid "aprovar")]
              (is (true? (:transicionou? r)) "a Casa aprovou — agora a materia avanca")
              (is (= "aprovada" (:para r)))
              (is (= "aprovada" (:estado (prop/buscar tx ente pid))) "o rotulo segue o ato, nao o contrario")
              (is (= 1 (count (tram/historico-da-proposicao tx ente pid)))
                  "e a transicao que OCORREU foi ao historico append-only"))))))))

(deftest a-aprovacao-de-OUTRA-materia-nao-destrava-esta
  ;; `votacoes.objeto_id` e' POLIMORFICO e NAO tem FK (mig 0021 disc.2). O fato tem de amarrar a votacao a'
  ;; ESTA materia — senao qualquer aprovacao na Casa destravaria qualquer rito, que e' o mesmo buraco de
  ;; antes com um passo a mais.
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [tid (montar-rito-que-exige-o-ato! tx ente)
              outra (protocolar! tx ente)
              minha (protocolar! tx ente)]
          (votar! tx ente outra "aprovada")
          (is (false? (:transicionou? (transicionar tx ente tid minha "aprovar")))
              "a aprovacao da materia vizinha nao vale para esta"))))))

;; ==============================================================================================
;; Fatia 4 — os tres dentes que a revisao adversarial mostrou que faltavam na ENGINE
;; ==============================================================================================
;; ADR-0004 (frente `guarda-so-apurado`, 11/09/2026) REVERTEU a premissa que os quatro testes abaixo
;; afirmavam: `alegado` (o corpo do POST) NAO E' MAIS vocabulario legitimo em guard nenhum, nem visivel
;; nem invisivel. Os tres primeiros sobrevivem trocando a FONTE da expressao (o sujeito `proposicao`, que
;; e' vocabulario legal) — o FENOMENO que provam (fail-closed sobre nao-booleano) e' independente de QUAL
;; canal alimenta o guard. O quarto virou o inverso do que era: de "ler o cliente continua permitido" para
;; "alegado e' vetado, nos dois niveis que o ADR fecha".

(defn- inserir-transicao-legado!
  "INSERT DIRETO em `template_transicao`, CONTORNANDO `criar-transicao!` (e o gate de vocabulario da
  Fatia 2) — simula exatamente o caso que o gate de save nao alcanca: rito gravado por import/SQL direto,
  ou uma linha anterior ao ADR-0004."
  [tx ente tid de-estado para-estado gatilho guarda]
  (jdbc/execute-one! tx
    ["INSERT INTO legislativo.template_transicao
      (ente_id, id, template_id, de_estado, para_estado, gatilho, guarda, ordem, efetivado_em)
      VALUES (?, ?, ?, ?, ?, ?, ?, 1, now())"
     ente (random-uuid) tid de-estado para-estado gatilho guarda]))

(defn- montar-rito-com-guarda!
  "Rito cuja unica saida de 'protocolada' tem por guard a EXPRESSAO passada — usado para exercitar o
  avaliador (`exigir-booleano!`) contra vocabulario LEGITIMO (o sujeito do template). Ate' o ADR-0004 este
  helper media o canal `alegado` (o corpo do POST); hoje so' aceita o que `criar-transicao!` deixa passar
  no gate de vocabulario — nunca `alegado`. Nenhuma string do rito e' conhecida do codigo (Inv.4); `guarda`
  e' o parametro porque e' justamente a expressao sob teste."
  [tx ente guarda]
  (let [tid (random-uuid)]
    (tram/criar-template! tx {:id tid :ente-id ente :chave "rito_fatia4" :versao 1
                              :nome "Rito com guarda parametrizada [FIXTURE]" :estado-inicial "protocolada"})
    (doseq [[ch term] [["protocolada" false] ["em_pauta" false]]]
      (tram/criar-estado! tx {:id (random-uuid) :ente-id ente :template-id tid :chave ch :nome ch :terminal term}))
    (tram/criar-transicao! tx {:id (random-uuid) :ente-id ente :template-id tid :de-estado "protocolada"
                               :para-estado "em_pauta" :gatilho "avancar" :guarda guarda :ordem 1})
    tid))

(deftest guarda-que-nao-avalia-para-booleano-falha-FECHADO
  ;; O ACHADO: `motor/api/guarda-dsl` fechava com `(boolean (rt/avaliar ...))`, e `boolean` nao CHECA nada —
  ;; ele CONVERTE. Um guard que avaliasse para qualquer valor truthy nao-booleano virava `true`, isto e',
  ;; autorizacao concedida. Duas docstrings da borda ja' afirmavam que esse caso LANCA; nao lancava.
  ;;
  ;; A FONTE mudou com o ADR-0004 (antes lia `alegado`, o corpo do POST — vocabulario banido hoje), mas o
  ;; FENOMENO e' o mesmo lendo vocabulario LEGITIMO: `proposicao.estado` (sem `== "..."`, que e' o mesmo
  ;; esquecimento banal de quem escreve regimento como quem escreve planilha) avalia para a STRING
  ;; "protocolada" — truthy em Clojure, nao-booleana. A materia AVANCARIA com um estado por rotulo, nao
  ;; por decisao, se `boolean` ainda convertesse em vez de checar.
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [tid (montar-rito-com-guarda! tx ente "proposicao.estado")
              pid (protocolar! tx ente)
              e (is (thrown-with-msg? clojure.lang.ExceptionInfo #"(?i)booleano"
                      (transicionar tx ente tid pid "avancar")))]
          (is (= :runtime (:erro (ex-data e)))
              "tag :runtime = a mesma que fato-sem-fn usa; e' o que a borda traduz em 500 NOMEADO (rito
               inavaliavel), nunca num 409 'a Casa nao permite' — o cliente nao tem o que consertar aqui")
          (is (= "protocolada" (:estado (prop/buscar tx ente pid)))
              "e a materia NAO andou: fail-closed e' nao-transicionar, nao transicionar-reclamando")
          (is (empty? (tram/historico-da-proposicao tx ente pid))
              "nada no historico append-only — o ato nao aconteceu"))))))

(deftest guarda-booleana-de-verdade-continua-passando-e-recusando
  ;; O par obrigatorio do teste acima: fail-closed que recusa TUDO nao e' fail-closed, e' pane. Fonte
  ;; trocada para um FATO (`aprovada_em_votacao`, o mesmo canal apurado que 3-B abriu — vocabulario
  ;; legitimo, nunca `alegado`): `true`/`false` do fato atravessam intactos, e a recusa por `false`
  ;; continua sendo DOMINIO NORMAL (`:guarda-recusou`), nao excecao.
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [tid (montar-rito-que-exige-o-ato! tx ente)]
          (let [pid (protocolar! tx ente)
                r (transicionar tx ente tid pid "aprovar")]
            (is (false? (:transicionou? r)))
            (is (= :guarda-recusou (:motivo r)) "booleano FALSO (sem votacao) e' recusa de dominio, nao erro de rito"))
          (let [pid (protocolar! tx ente)]
            (votar! tx ente pid "aprovada")
            (let [r (transicionar tx ente tid pid "aprovar")]
              (is (true? (:transicionou? r)) "booleano VERDADEIRO (votacao aprovada) passa")
              (is (= "aprovada" (:estado (prop/buscar tx ente pid)))))))))))

(deftest campo-AUSENTE-no-sujeito-nega-em-vez-de-passar
  ;; O mesmo defeito por outra porta, portado do canal `alegado` (banido) para o SUJEITO: um guard pode
  ;; referenciar um CAMPO que o objeto `proposicao` simplesmente nao tem — `(get amb :campo)` avalia para
  ;; `nil`, exatamente como `alegado.x` sobre um corpo que nao mandou `x`. `nil` e' TRUTHY em Clojure; que
  ;; `(boolean nil)` transformava em `false` (recusa muda) e que hoje LANCA. A escolha e' deliberada: `nil`
  ;; no topo significa que o rito perguntou algo sem resposta, e chamar isso de "a Casa nao permite" e' o
  ;; diagnostico errado. O vocabulario continua legitimo (a RAIZ `proposicao` e' que o gate de save checa —
  ;; nao o nome do campo, ver `nuc/identificadores-raiz`), entao isto passa o cadastro e so' lanca em
  ;; runtime, que e' exatamente o que este teste prova.
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [tid (montar-rito-com-guarda! tx ente "proposicao.campo_que_nao_existe")
              pid (protocolar! tx ente)]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"(?i)booleano"
                (transicionar tx ente tid pid "avancar")))
          (is (= "protocolada" (:estado (prop/buscar tx ente pid))) "a materia nao andou"))))))

(deftest alegado-e-VETADO-no-cadastro-e-um-rito-legado-com-ele-lanca-no-runtime
  ;; ATE' ONTEM este teste provava o OPOSTO: que `alegado.x` no guard continuava PERMITIDO, so' com o nome
  ;; tornado visivel (a renomeacao `contexto` -> `alegado` da fatia 4 antiga). O ADR-0004 (11/09/2026)
  ;; pesou esse argumento e o derrubou — ver o proprio ADR, secao "O precedente que esta ADR reverte": o
  ;; unico uso legitimo citado (escolher destino por `alegado.comissao`) e' melhor modelado como
  ;; GATILHO-POR-DESTINO, e o segundo (carimbar quem pediu) nunca precisou da guarda — a auditoria ja'
  ;; grava o corpo inteiro. Este teste agora prova o VETO, nos DOIS niveis que o fecham:
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [tid (random-uuid)]
          (tram/criar-template! tx {:id tid :ente-id ente :chave "rito_veto_alegado" :versao 1
                                    :nome "Rito veto alegado [FIXTURE]" :estado-inicial "protocolada"})
          (doseq [ch ["protocolada" "em_pauta"]]
            (tram/criar-estado! tx {:id (random-uuid) :ente-id ente :template-id tid :chave ch :nome ch :terminal false}))
          ;; NIVEL 1 — CADASTRO: `criar-transicao!` recusa `alegado` no vocabulario da coluna guarda
          ;; (Fatia 2/ADR-0004). A escrita NOVA nunca chega a persistir.
          (let [e (is (thrown-with-msg? clojure.lang.ExceptionInfo #"alegado"
                        (tram/criar-transicao! tx {:id (random-uuid) :ente-id ente :template-id tid
                                                   :de-estado "protocolada" :para-estado "em_pauta"
                                                   :gatilho "avancar"
                                                   :guarda "alegado.parecer_favoravel == verdadeiro"
                                                   :ordem 1})))]
            (is (= :guarda-vocabulario-invalido (:erro (ex-data e)))
                "recusado no SAVE — o cliente nao le' o rito, o rito nunca chega a existir"))
          ;; NIVEL 2 — RUNTIME: o MESMO guard, gravado por FORA do save (import/SQL direto — a linha que o
          ;; nivel 1 nao alcanca), escapa do gate; a REDE pega porque `alegado` nem existe mais no `amb`.
          (inserir-transicao-legado! tx ente tid "protocolada" "em_pauta" "avancar"
                                     "alegado.parecer_favoravel == verdadeiro")
          (let [pid (protocolar! tx ente)
                e2 (is (thrown-with-msg? clojure.lang.ExceptionInfo #"(?i)identificador sem valor"
                        (transicionar tx ente tid pid "avancar")))]
            (is (= :runtime (:erro (ex-data e2)))
                "MESMA tag de fato-sem-fn/tipo-nao-booleano — e' a rede de RUNTIME, nao o gate de save, que pega este rito")
            (is (= "protocolada" (:estado (prop/buscar tx ente pid)))
                "nem o cadastro nem o import deixam o corpo decidir o guard")))))))

(deftest engine-recusa-rito-que-nao-e-o-DA-MATERIA
  ;; O ACHADO: `transicionar!` recebia `template-id` por argumento e NUNCA o confrontava com o
  ;; `template_id` da propria linha. Ate' a mig 0076 conferir era impossivel (a materia nao tinha onde
  ;; guardar o rito dela); com a coluna, passaram a existir duas fontes da mesma verdade e ninguem as
  ;; casava. Um caller que passasse outro rito fazia a materia andar por um regimento que nao e' o dela —
  ;; candidatas do template errado, guard de outra especie — e o historico gravava esse template sem que
  ;; nada no banco barrasse: `proposicao_transicao_historico.template_id` so' tem FK para o TENANT.
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [meu (montar-template! tx ente)
              ;; a materia nasce ANTES do segundo rito de proposito: dois ritos GENERICOS ativos no mesmo
              ;; ente sao config ambigua e `resolver-rito!` recusaria o protocolo (mig 0077), o que provaria
              ;; outra coisa. O que se quer aqui e' o rito ALHEIO existindo e legitimo — a FK same-tenant
              ;; passa, e e' exatamente por isso que o banco sozinho nao resolve o problema.
              pid (protocolar! tx ente)
              alheio (montar-rito-com-desarquivamento! tx ente false)
              e (is (thrown-with-msg? clojure.lang.ExceptionInfo #"(?i)rito"
                      (transicionar tx ente alheio pid "arquivar")))]
          (is (= :rito-divergente (:erro (ex-data e))))
          (is (= meu (:template-id-da-linha (ex-data e))) "a ex-data nomeia os DOIS ritos, p/ o log dizer qual era qual")
          (is (= alheio (:template-id-argumento (ex-data e))))
          (is (= "protocolada" (:estado (prop/buscar tx ente pid))) "a materia nao andou por rito alheio")
          (is (empty? (tram/historico-da-proposicao tx ente pid)))
          ;; e o rito CERTO segue funcionando — a checagem confronta, nao trava
          (is (true? (:transicionou? (transicionar tx ente meu pid "despachar")))))))))

;; ==============================================================================================
;; ADR-0004 (frente `guarda-so-apurado`) — Fatia 3: `alegado` sai do `amb` de RUNTIME
;; ==============================================================================================
;; A Fatia 2 (ja' commitada) fecha o GATE DE SAVE: `criar-transicao!` recusa `alegado` no vocabulario do
;; guard. Sozinho isso e' falso senso de seguranca — um rito gravado por FORA do save (import, SQL direto,
;; linha anterior a esta ADR) escapa do gate e continuaria lendo o corpo em silencio. Esta secao prova a
;; REDE DE RUNTIME: o `amb` que `transicionar!` monta nao carrega mais a chave `alegado`, entao um guard
;; assim LANCA, nunca transiciona e nunca vira `{:transicionou? false}` disfarcado.
;; `inserir-transicao-legado!` mora la' em cima (Fatia 4), ao lado de `montar-rito-com-guarda!` — os testes
;; de vocabulario/cadastro e de rito legado usam o MESMO helper.

(deftest rito-legado-que-le-ALEGADO-lanca-em-runtime-e-nao-transiciona
  ;; O ACHADO que esta fatia existe para fechar: sem a REDE, um rito assim passaria batido (o gate so'
  ;; alcanca escrita NOVA) e o guard continuaria lendo o corpo do POST como precondicao — a mesma falha
  ;; que a Decisao B fechou um nivel acima, agora pela porta que os testes de save nao cobrem.
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [tid (random-uuid)]
          (tram/criar-template! tx {:id tid :ente-id ente :chave "rito_legado" :versao 1
                                    :nome "Rito legado [FIXTURE]" :estado-inicial "protocolada"})
          (doseq [ch ["protocolada" "em_pauta"]]
            (tram/criar-estado! tx {:id (random-uuid) :ente-id ente :template-id tid :chave ch :nome ch :terminal false}))
          (inserir-transicao-legado! tx ente tid "protocolada" "em_pauta" "avancar" "alegado.parecer_favoravel")
          (let [pid (protocolar! tx ente)
                e (is (thrown-with-msg? clojure.lang.ExceptionInfo #"(?i)identificador sem valor"
                        (transicionar tx ente tid pid "avancar" {:parecer_favoravel true})))]
            (is (= :runtime (:erro (ex-data e)))
                "MESMA tag de fato-sem-fn/tipo-nao-booleano — e' a rede de RUNTIME, nao o gate de save, que pega este rito")
            (is (= "protocolada" (:estado (prop/buscar tx ente pid)))
                "a materia NAO tramitou — nem {:transicionou? false} silencioso, nem transicao de verdade")
            (is (empty? (tram/historico-da-proposicao tx ente pid))
                "nada foi ao historico append-only: o ato nao aconteceu")))))))

(deftest auditoria-continua-gravando-o-corpo-mesmo-que-a-guarda-nao-o-leia-mais
  ;; O QUE NAO PODE MUDAR: `alegado` sai do `amb` (o guard nao decide mais com ele), mas continua
  ;; alimentando `registrar-transicao!` (`:contexto alegado`) — o corpo deixa de DECIDIR, nao deixa de ser
  ;; REGISTRADO. Le' a COLUNA e compara o CONTEUDO, nao so' que a linha existe.
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [tid (montar-template! tx ente)
              pid (protocolar! tx ente)
              corpo {:motivo "urgencia" :protocolo "OF-123"}]
          (transicionar tx ente tid pid "despachar" corpo)   ; guard nil: nao le' alegado, so' EXISTE no corpo
          (let [linha (first (tram/historico-da-proposicao tx ente pid))
                gravado (db-util/jsonb->kw (:contexto linha))]
            (is (= corpo gravado)
                (str "a coluna 'contexto' segue com o corpo do POST INTEGRAL, mesmo o guard nao lendo "
                     "mais 'alegado' — lido: " (pr-str gravado)))))))))
