(ns oplenario.demo.acervo-test
  "INTEGRACAO (PG real): `acervo/semear!` (plano `docs/superpowers/plans/2026-09-07-prontidao-de-
  apresentacao.md`, Task 0.4) — 24 proposicoes cobrindo os 6 estados de um RITO REAL da Casa (dado,
  Invariante 4), 3 pareceres, 2 autografos e 4 normas. TESTE LITERAL do Passo 1 da Task 0.4 (plano
  L317-333) — ancorado nas DUAS autoridades reais: `legislativo.logic/tipos` (vocabulario de `tipo`,
  `src/oplenario/legislativo/logic.clj:13`) e o proprio `legislativo.template_estado` que este ns
  instala (Invariante 4: os estados do rito sao DADO, `legislativo.proposicoes.estado` NAO TEM CHECK —
  migration 20260620000013:29, comentario 'coarse; a maquina fina e' a tramitacao'). `with-sistema`
  reusada de `oplenario.demo.casa-test` (carry #1 do briefing: nao existe em nenhum outro lugar do repo)."
  (:require [acervo]
            [casa]
            [clojure.set]
            [clojure.test :refer [deftest is testing]]
            [oplenario.cadastros.components.repositorio :as repo-cadastros]
            [oplenario.demo.casa-test :refer [with-sistema]]
            [oplenario.identidade.components.repositorio :as repo-identidade]
            [oplenario.legislativo.components.repositorio :as repo-legislativo]
            [oplenario.legislativo.components.repositorio-juridico :as repo-juridico]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.legislativo.db.tramitacao :as tram]
            [oplenario.legislativo.logic :as legislativo.logic]
            [oplenario.legislativo.logic.rito :as rito]
            [oplenario.kernel.db-util :as comum]
            [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [seed-demo]))

;; `template_estado.ordem` (migration 20261005000261): o rito que a Casa recebe NASCE aqui (nenhuma rota, tela ou
;; provisionamento cria estado de rito); entao e' aqui que se prova que ele declara a ordem da linha, em vez de deixar
;; a faixa "Onde esta a materia" depender so' da topologia das transicoes.
(defn- ordem-da-linha-principal
  "Problemas (lista de textos; vazia = ok) da `ordem` de um rito `{:estado-inicial :estados :transicoes}`."
  [{:keys [estado-inicial estados transicoes]}]
  (let [etapas (remove :terminal estados)
        desfechos (filter :terminal estados)
        por-ordem (sort-by :ordem etapas)
        ordem-de (into {} (map (juxt :chave :ordem)) etapas)
        max-etapa (apply max 0 (map :ordem etapas))]
    (concat
     (when (empty? etapas) ["rito sem etapa"])
     (for [e etapas :when (not (pos? (or (:ordem e) 0)))] (str "etapa sem ordem declarada: " (:chave e)))
     (for [{:keys [ordem chaves]} (rito/ordem-repetida estados)] (str "ordem " ordem " repetida em " chaves))
     (when (and (seq por-ordem) (not= estado-inicial (:chave (first por-ordem))))
       [(str "o estado inicial " estado-inicial " nao tem a menor ordem")])
     (for [e desfechos :when (<= (or (:ordem e) 0) max-etapa)] (str "desfecho antes do ultimo passo: " (:chave e)))
     ;; a linha principal avanca de um em um: cada transicao entre etapas vai ao proximo ou volta
     (for [{:keys [de-estado para-estado]} transicoes
           :when (and (contains? ordem-de de-estado) (contains? ordem-de para-estado))
           :let [de (ordem-de de-estado) para (ordem-de para-estado)]
           :when (> para (inc de))]
       (str "transicao salta etapa: " de-estado " -> " para-estado)))))

(defn- chaves-da-linha [{:keys [estado-inicial estados transicoes]} atual]
  (mapv :chave (:etapas (rito/rito-da-materia {:estado-inicial estado-inicial :estados estados :transicoes transicoes
                                               :atual atual :tramitacao [] :tramitacao-truncado false}))))

(deftest ordem-da-linha-principal-reprova-o-que-a-regra-proibe
  (let [est (fn [chave ordem terminal] {:chave chave :nome chave :ordem ordem :terminal terminal})
        bom {:estado-inicial "a" :estados [(est "a" 1 false) (est "b" 2 false) (est "fim" 3 true)]
             :transicoes [{:de-estado "a" :para-estado "b"} {:de-estado "b" :para-estado "fim"}]}]
    (is (empty? (ordem-da-linha-principal bom)) "o instrumento aceita o rito bom (senao nao prova nada)")
    (is (some #(re-find #"sem ordem declarada" %)
              (ordem-da-linha-principal (assoc bom :estados [(est "a" 0 false) (est "b" 0 false) (est "fim" 3 true)]))))
    (is (some #(re-find #"repetida" %)
              (ordem-da-linha-principal (assoc bom :estados [(est "a" 1 false) (est "b" 1 false) (est "fim" 3 true)]))))
    (is (some #(re-find #"menor ordem" %)
              (ordem-da-linha-principal (assoc bom :estados [(est "a" 2 false) (est "b" 1 false) (est "fim" 3 true)]))))
    (is (some #(re-find #"desfecho antes" %)
              (ordem-da-linha-principal (assoc bom :estados [(est "a" 1 false) (est "b" 3 false) (est "fim" 2 true)]))))
    (is (some #(re-find #"salta etapa" %)
              (ordem-da-linha-principal
               {:estado-inicial "a"
                :estados [(est "a" 1 false) (est "b" 2 false) (est "c" 3 false) (est "fim" 4 true)]
                :transicoes [{:de-estado "a" :para-estado "b"} {:de-estado "a" :para-estado "c"}]})))))

(deftest os-templates-da-demo-declaram-a-ordem-da-linha
  (with-sistema [s]
    (let [{:keys [ente identidades]} (casa/semear! s)
          _ (acervo/semear! s ente (:vereador identidades))
          ds (get-in s [:datasource :ds])
          ritos (tenancy/com-tenant* ds ente
                  (fn [tx]
                    (let [ts (comum/linhas->kebab
                              (jdbc/execute! tx (sql/format {:select [:id :chave]
                                                             :from [:legislativo.template_tramitacao]
                                                             :where [:and [:= :ente_id ente]
                                                                     [:in :chave ["rito_ordinario"
                                                                                  "parecer_comissao_permanente"]]]})))]
                      (mapv (fn [{:keys [id chave]}] (assoc (tram/rito-do-template tx ente id) :chave chave))
                            ts))))]
      (testing "a demo instala o rito da materia E o do parecer (senao o teste nao prova nada)"
        (is (= #{"rito_ordinario" "parecer_comissao_permanente"} (set (map :chave ritos)))))
      (doseq [r ritos]
        (testing (str "rito " (:chave r))
          (is (empty? (ordem-da-linha-principal r))
              "estados com ordem declarada, distinta e crescente na linha principal")
          (testing "a ordem declarada e a topologia contam a MESMA linha (a ordem nao contradiz o que a engine executa)"
            (let [ultima (:chave (last (sort-by :ordem (remove :terminal (:estados r)))))
                  zerados (update r :estados (partial mapv #(assoc % :ordem 0)))]
              (is (seq (chaves-da-linha r ultima)))
              (is (= (chaves-da-linha r ultima) (chaves-da-linha zerados ultima))))))))))

(deftest o-rito-fixture-do-portal-declara-a-ordem-da-linha
  ;; seed-demo/materias: o rito [FIXTURE] que alimenta o portal na demo. Nao passa pelo banco neste teste: e' o dado
  ;; que a semente grava, lido da mesma definicao que ela usa.
  (let [estados @#'seed-demo/rito-fixture-estados
        transicoes (mapv (fn [[de para]] {:de-estado de :para-estado para}) @#'seed-demo/rito-fixture-transicoes)
        r {:estado-inicial "protocolada" :estados estados
           :transicoes (conj transicoes {:de-estado "protocolada" :para-estado "arquivada"})}]
    (is (empty? (ordem-da-linha-principal r)))
    (is (= ["protocolada" "em_comissoes" "em_pauta" "segundo_turno" "em_sancao" "aprovada"]
           (chaves-da-linha r "aprovada")))))

(deftest acervo-usa-vocabulario-real-e-cobre-o-rito-que-instala
  (with-sistema [s]
    (let [{:keys [ente identidades]} (casa/semear! s)
          {:keys [template-id]} (acervo/semear! s ente (:vereador identidades))
          por-estado (acervo/contar-por-estado s ente)
          estados-do-template (acervo/estados-do-template s ente template-id)]
      (testing "todo tipo usado existe em legislativo.logic/tipos — a autoridade real"
        (is (empty? (clojure.set/difference (acervo/tipos-usados s ente)
                                             legislativo.logic/tipos))))
      (testing "todo estado que o template DECLARA tem pelo menos uma matéria nele"
        (is (seq estados-do-template) "template sem estado não prova nada")
        (doseq [estado estados-do-template]
          (is (pos? (get por-estado estado 0))
              (str "o rito declara '" estado "' e nenhuma matéria está nele"))))
      (testing "nenhuma matéria em estado que o template não declara"
        (is (empty? (clojure.set/difference (set (keys por-estado))
                                             (set estados-do-template))))))))

(deftest o-vereador-da-identidade-e-relator-de-parecer-assinavel
  ;; Ledger #12 (docs/16-ledger-prontidao.md): a identidade `:vereador` da demo NUNCA era relatora de
  ;; parecer nenhum (`semear-pareceres!` designava relator entre os 3 primeiros do roster, sem vinculo
  ;; com nenhuma identidade — `vereador/listar` nem é chamado por identidade) — GET
  ;; /parecer/:id/assinar respondia 404 "parecer não encontrado" pro login vereador, e a jornada J3
  ;; (o parecer) não podia ser demonstrada de ponta a ponta.
  (with-sistema [s]
    (let [{:keys [ente identidades]} (casa/semear! s)
          vereador-identidade (:vereador identidades)
          _ (acervo/semear! s ente vereador-identidade)
          repo-cad (:repo-cadastros s)
          repo-leg (:repo-legislativo s)
          vereador-id (:id (repo-cadastros/vereador-por-identidade repo-cad ente vereador-identidade))
          pareceres (:pareceres (repo-legislativo/meu-painel repo-leg ente vereador-id))]
      (testing "o vereador da identidade :vereador e' relator de pelo menos 1 parecer"
        (is (seq pareceres)
            "identidade :vereador nunca e' relatora — GET /parecer/:id/assinar responde 404"))
      (testing "existe parecer NAO terminal (assinavel) entre eles"
        (is (some #(not (contains? legislativo.logic/estados-parecer-terminais (:estado %))) pareceres)
            "todo parecer do relator ja' esta' num dos 4 estados terminais — nao ha' o que assinar")))))

;; A3 (revisao adversarial de conserta-3-mata, #14): a inscricao das 24 proposicoes no Livro do Protocolo
;; Geral (acervo.clj/protocolar-e-tramitar!, repo-leg/protocolar-geral!) so' tinha sido verificada AO VIVO
;; pelo revisor — nenhum teste provava. REPROVA se `protocolar-e-tramitar!` parar de chamar
;; `protocolar-geral!` (Livro vazio), se o `objeto-tipo`/`sentido` regredirem, ou se a numeracao deixar de
;; ser gapless 1..24 (ex.: um crash no meio do loop, ou dois entes compartilhando o mesmo escopo por
;; engano).
(deftest acervo-inscreve-as-24-proposicoes-no-livro-do-protocolo-geral
  (with-sistema [s]
    (let [{:keys [ente identidades]} (casa/semear! s)
          _ (acervo/semear! s ente (:vereador identidades))
          repo-leg (:repo-legislativo s)
          livro-2026 (repo-legislativo/protocolos-do-ano repo-leg ente 2026)
          proposicoes (acervo/tipos-usados s ente)
          entradas-de-proposicao (filterv #(= "proposicao" (:objeto-tipo %)) livro-2026)]
      (testing "as 24 proposicoes da semente estao TODAS inscritas no Livro"
        (is (= 24 (count entradas-de-proposicao))
            "o Livro nao tem as 24 entradas 'proposicao' esperadas — protocolar-geral! parou de ser chamado?"))
      (testing "sentido 'interno' — vereador da PROPRIA Casa, nunca 'recebido' (externo)"
        (is (every? #(= "interno" (:sentido %)) entradas-de-proposicao)
            "alguma entrada regrediu p/ sentido diferente de 'interno'"))
      (testing "numeracao gapless 1..24, sem furo (append-only, escopo protocolo_geral:2026 do ente)"
        (is (= (range 1 25) (sort (mapv :numero entradas-de-proposicao)))
            "numeracao nao e' 1..24 gapless — sinal de crash-no-meio-do-loop ou escopo de sequencial compartilhado"))
      (testing "cada entrada aponta pra uma proposicao real (objeto-id existe entre as protocoladas)"
        (is (seq proposicoes) "acervo/tipos-usados vazio — a semente rodou?")
        (is (every? some? (map :objeto-id entradas-de-proposicao))
            "entrada 'proposicao' sem objeto-id — guard ref orfao no Livro")))))

(deftest comissao-id-do-parecer-aponta-para-comissao-real-da-casa
  ;; Ledger #11 (docs/16-ledger-prontidao.md): `semear-pareceres!` gravava `comissao-id` como
  ;; `(random-uuid)` — guard ref ORFAO (sem FK, §22.10) — e a tela /parecer/:id mostrava esse UUID
  ;; cru onde deveria ir o nome da comissao.
  (with-sistema [s]
    (let [{:keys [ente identidades]} (casa/semear! s)
          _ (acervo/semear! s ente (:vereador identidades))
          ids-comissoes-reais (set (map :id (acervo/comissoes s ente)))
          ids-comissoes-dos-pareceres (set (map :comissao-id (acervo/pareceres s ente)))]
      (testing "nenhum parecer aponta pra comissao inexistente"
        (is (seq ids-comissoes-dos-pareceres) "nenhum parecer achado — a semente rodou?")
        (is (empty? (clojure.set/difference ids-comissoes-dos-pareceres ids-comissoes-reais))
            "comissao-id do parecer nao bate com nenhuma comissao real da Casa — guard ref orfao")))))

;; Fatia 2b: o rito da demo exige RECEBIMENTO na chegada as comissoes. O acervo recebe (assinado, em nome da
;; secretaria) toda carga do caminho — senao as materias que ja' sairam de 'em_comissoes' nem teriam saido —
;; e deixa exatamente 2 em carga, para a fila de pendentes da demo nao nascer vazia.
(deftest acervo-recebe-as-cargas-e-deixa-duas-na-fila
  (with-sistema [s]
    (let [{:keys [ente identidades]} (casa/semear! s)
          _ (acervo/semear! s ente (:vereador identidades) (:secretaria identidades))
          repo-leg (:repo-legislativo s)
          pendentes (repo-legislativo/recebimentos-pendentes repo-leg ente)]
      (is (= 2 (count pendentes)) "em-comissoes-3 e em-comissoes-4 ficam em carga")
      (is (every? #(= "em_comissoes" (:estado %)) pendentes))
      (let [recebida (->> (repo-legislativo/listar-e-contar-proposicoes repo-leg ente {:estado "aguardando_pauta" :pagina 1 :tamanho 1
                                                                                   :ordenar-por "atualizado_em" :ordenar-dir "desc"})
                          :itens first :id)
            {:keys [historico recebimentos]} (repo-legislativo/tramitacao-da-proposicao repo-leg ente recebida 10)
            chegada (first (filter #(= "em_comissoes" (:para-estado %)) historico))]
        ;; `uuid?`, nao `= (:secretaria ...)`: o acervo e' idempotente e o ente da demo e' fixo — quem semeia
        ;; PRIMEIRO no banco (a aridade-3, em outro deftest, recebe em nome do vereador) decide o recebedor, e
        ;; o kaocha randomiza a ordem. A secretaria como recebedora e' provada pelo seed_demo/e2e.
        (is (uuid? (:recebido-por (get recebimentos (:id chegada))))
            "a carga que andou foi recebida, com recibo ligado a movimentacao")))))

;; Fatia 2c: o requerimento COLETIVO da demo — o presidente convida o vereador da jornada J3, que ve o pedido na home.
(deftest acervo-deixa-um-pedido-de-subscricao-para-o-vereador
  (with-sistema [s]
    (let [{:keys [ente identidades]} (casa/semear! s)
          _ (acervo/semear! s ente (:vereador identidades) (:secretaria identidades))
          id (acervo/semear-proposta-coletiva! s ente (:presidente identidades) (:vereador identidades))
          repo-leg (:repo-legislativo s)
          vereador-id (:id (repo-cadastros/vereador-por-identidade (:repo-cadastros s) ente (:vereador identidades)))]
      (is (some? id))
      (is (some #(= id (:proposta-id %)) (repo-legislativo/convites-de-subscricao repo-leg ente vereador-id))
          "o vereador da demo tem o pedido do presidente esperando resposta")
      (is (= id (acervo/semear-proposta-coletiva! s ente (:presidente identidades) (:vereador identidades)))
          "idempotente: rodar de novo rele, nao convida duas vezes"))))

;; ADR-0019: o parecer juridico da demo. A visita ve' o advogado (persona de apresentacao, com perfil), um parecer
;; assinado sobre materia JA' DELIBERADA (o portal o mostra), um pedido pendente com rascunho e uma consulta avulsa.
(deftest o-parecer-juridico-da-demo-existe-e-a-semente-e-idempotente
  (with-sistema [s]
    (let [{:keys [ente identidades]} (casa/semear! s)
          _ (acervo/semear! s ente (:vereador identidades) (:secretaria identidades))
          repo (:repo-legislativo s)
          r1 (acervo/semear-parecer-juridico! s ente (:secretaria identidades) (:apresentacao identidades))
          r2 (acervo/semear-parecer-juridico! s ente (:secretaria identidades) (:apresentacao identidades))
          pedidos (repo-juridico/pedidos-juridicos repo ente nil 100)]
      (is (boolean? (:ja-semeado r1)) "1a vez false; o banco de teste e' compartilhado, entao nao cravamos")
      (is (true? (:ja-semeado r2)) "reexecutar nao duplica")
      (testing "2 pedidos de materia e 1 consulta avulsa; 1 atendido e 2 pendentes"
        (is (= 3 (count pedidos)))
        (is (= 1 (count (remove :proposicao-id pedidos))))
        (is (= {"atendido" 1 "pendente" 2} (frequencies (map :estado pedidos)))))
      (testing "o parecer assinado e' de materia aprovada, e o portal o mostra"
        (let [atendido (first (filter #(= "atendido" (:estado %)) pedidos))
              publicos (repo-juridico/pareceres-juridicos-publicos repo ente (:proposicao-id atendido))]
          (is (= ["favoravel" "Paulo Henrique Bezerra" "CE 12345"]
                 ((juxt :conclusao :assinatura-nome :assinatura-oab) (first publicos))))))
      (testing "a persona de apresentacao tem o papel juridico e o perfil"
        (let [ri (:repo-identidade s)]
          (is (= {:qualificacao "efetivo" :oab "CE 12345"}
                 (select-keys (repo-identidade/perfil-juridico ri ente (:apresentacao identidades))
                              [:qualificacao :oab]))))))))
