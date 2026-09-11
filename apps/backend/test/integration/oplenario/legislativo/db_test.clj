(ns oplenario.legislativo.db-test
  "INTEGRACAO (PG real): o db/ do legislativo sob a tx do tenant. Prova o GATE eixo H ponta-a-ponta —
  protocolo atomico (sequencial gapless + URN/LexML), gaplessness em ROLLBACK, conformidade de model,
  RLS cross-tenant, imutabilidade da identidade canonica (sempre) e do estado terminal (nivel b, com a
  excecao de correcao auditada), e os CHECK por tipo (eixo A)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [malli.core :as m]
            [next.jdbc :as jdbc]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.legislativo.db.proposicao :as prop]
            [oplenario.legislativo.logic :as logic]
            [oplenario.legislativo.models.proposicao :as mod]
            [oplenario.migracao :as migracao]))

(def ^:dynamic *ds* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c)] (try (t) (finally (component/stop c)))))))

(def ^:private fortaleza {:uf "CE" :municipio-nome "Fortaleza"})

(defn- pl [ente extra]
  (merge {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026
          :ementa "Dispoe sobre X" :autor-tipo "vereador"} fortaleza extra))

(deftest protocolo-numera-gapless-e-computa-urn
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [r1 (prop/protocolar! tx (pl ente {}))
              r2 (prop/protocolar! tx (pl ente {}))                       ; mesmo tipo:ano -> seq anda
              rr (prop/protocolar! tx (pl ente {:tipo "projeto_resolucao"}))] ; outro escopo -> seq reinicia
          (is (= 1 (:sequencial r1)) "primeiro PL do ano = sequencial 1")
          (is (= 2 (:sequencial r2)) "segundo PL do ano = sequencial 2 (gapless)")
          (is (= 1 (:sequencial rr)) "outro tipo tem contador proprio (escopo tipo:ano)")
          (is (= "urn:lex:br;ce;fortaleza:camara.municipal;projeto.lei:2026;1" (:urn-lex r1))
              "URN/LexML computada no formato do ADR-0002")
          (let [p (prop/buscar tx ente (:id r1))]
            (is (m/validate mod/Proposicao (select-keys p (map (comp keyword name) (keys p))))
                "a proposicao buscada bate o model interno")
            (is (= "PL 001/2026" (logic/numero-exibicao p)) "numero de exibicao humano")
            (is (= "protocolada" (:estado p)) "nasce protocolada")))))))

(deftest sequencial-gapless-em-rollback
  (let [ente (random-uuid)]
    ;; protocola e ROLLBACK (excecao dentro da tx desfaz tudo, inclusive o bump do contador)
    (is (thrown? Exception
                 (tenancy/com-tenant* *ds* ente
                   (fn [tx] (prop/protocolar! tx (pl ente {})) (throw (ex-info "rollback proposital" {}))))))
    ;; fresh: o contador NAO pulou (gapless) -> ainda comeca em 1
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (is (= 1 (:sequencial (prop/protocolar! tx (pl ente {}))))
            "rollback nao deixa buraco na numeracao (contador-por-linha, nao SEQUENCE)")))))

(deftest rls-isola-proposicao-cross-tenant
  (let [a (random-uuid) b (random-uuid) id (random-uuid)]
    (tenancy/com-tenant* *ds* a (fn [tx] (prop/protocolar! tx (pl a {:id id}))))
    (is (some? (tenancy/com-tenant* *ds* a (fn [tx] (prop/buscar tx a id)))) "ente A ve a propria proposicao")
    (is (nil? (tenancy/com-tenant* *ds* b (fn [tx] (prop/buscar tx b id)))) "ente B NAO ve a de A (RLS)")))

(deftest identidade-canonica-e-imutavel
  (let [ente (random-uuid) id (random-uuid)]
    (tenancy/com-tenant* *ds* ente (fn [tx] (prop/protocolar! tx (pl ente {:id id}))))
    (is (thrown? Exception
                 (tenancy/com-tenant* *ds* ente
                   (fn [tx] (jdbc/execute-one! tx ["UPDATE legislativo.proposicoes SET urn_lex = 'falsa' WHERE id = ?" id]))))
        "alterar urn_lex apos o protocolo e' bloqueado (ADR-0002 §4a)")
    (is (thrown? Exception
                 (tenancy/com-tenant* *ds* ente
                   (fn [tx] (jdbc/execute-one! tx ["UPDATE legislativo.proposicoes SET sequencial = 999 WHERE id = ?" id]))))
        "alterar o sequencial apos o protocolo e' bloqueado")))

(deftest mudar-estado-cas-detecta-conflito
  (let [ente (random-uuid) id (random-uuid)]
    (tenancy/com-tenant* *ds* ente (fn [tx] (prop/protocolar! tx (pl ente {:id id}))))
    (is (thrown? Exception
                 (tenancy/com-tenant* *ds* ente (fn [tx] (prop/mudar-estado! tx {:id id :ente-id ente :estado "em_comissoes" :lock-version 99}))))
        "lock_version desatualizada = conflito de escrita (0 linhas), lanca")
    (tenancy/com-tenant* *ds* ente (fn [tx] (prop/mudar-estado! tx {:id id :ente-id ente :estado "em_comissoes" :lock-version 0})))
    (is (= "em_comissoes" (:estado (tenancy/com-tenant* *ds* ente (fn [tx] (prop/buscar tx ente id)))))
        "lock_version certa muda o estado")))

(deftest estado-nao-e-mais-travado-por-palavra-cravada-em-sql
  ;; ESTE TESTE FOI INVERTIDO NA FATIA 2 (mig 0078), e a inversao e' o ponto. Antes ele provava o trigger
  ;; `trg_proposicoes_imut_estado`, que executava `imut_trava_estado_terminal('publicada','arquivada')` —
  ;; duas palavras de camara cravadas em SQL. Enquanto `proposicoes.estado` nao se movia isso era
  ;; inofensivo; a borda de tramitacao o acendeu e o DESARQUIVAMENTO (ato corriqueiro de praticamente todo
  ;; regimento) ficou impossivel ATE' para a Casa cujo rito o declara — Inv.4 violado no schema.
  ;;
  ;; A protecao nao sumiu: mudou de fonte. Quem declara o fim do processo agora e' o RITO
  ;; (`template_estado.terminal`), e quem a aplica e' `db/tramitacao/transicionar!` — provado em
  ;; `tramitacao-db-test/rito-que-DECLARA-terminal-recusa-a-saida-e-o-motivo-e-proprio` e
  ;; `.../rito-que-nao-declara-arquivada-terminal-DESARQUIVA`. O que este teste fixa e' o outro lado do
  ;; contrato: no BANCO nao ha' mais trava nenhuma, entao a engine e' a unica camada (ver a docstring de
  ;; `mudar-estado!`). Se alguem reintroduzir o trigger, esta linha reprova e diz por que.
  (let [ente (random-uuid) id (random-uuid)]
    (tenancy/com-tenant* *ds* ente (fn [tx] (prop/protocolar! tx (pl ente {:id id}))))
    (tenancy/com-tenant* *ds* ente (fn [tx] (prop/mudar-estado! tx {:id id :ente-id ente :estado "arquivada" :lock-version 0})))
    (is (= "arquivada" (:estado (tenancy/com-tenant* *ds* ente (fn [tx] (prop/buscar tx ente id))))))
    (tenancy/com-tenant* *ds* ente
      (fn [tx] (prop/mudar-estado! tx {:id id :ente-id ente :estado "protocolada" :lock-version 1})))
    (is (= "protocolada" (:estado (tenancy/com-tenant* *ds* ente (fn [tx] (prop/buscar tx ente id)))))
        "sair de 'arquivada' nao e' mais abortado pelo banco — quem decide isso e' o rito, na engine")
    (is (empty? (tenancy/com-tenant* *ds* ente
                  (fn [tx] (jdbc/execute! tx ["SELECT tgname FROM pg_trigger
                                               WHERE tgrelid = 'legislativo.proposicoes'::regclass
                                                 AND tgname = 'trg_proposicoes_imut_estado'"]))))
        "o trigger com o vocabulario de camara nao existe mais na tabela")
    (is (seq (tenancy/com-tenant* *ds* ente
               (fn [tx] (jdbc/execute! tx ["SELECT tgname FROM pg_trigger
                                            WHERE tgrelid = 'legislativo.proposicoes'::regclass
                                              AND tgname = 'trg_proposicoes_imut_identidade'"]))))
        "a imutabilidade da IDENTIDADE legal (tipo/ano/sequencial/urn_lex) continua no banco — ela nao tem
         vocabulario de rito dentro, e' imutavel em qualquer regimento")))

(deftest check-por-tipo-exige-atributo-quente
  (let [ente (random-uuid)]
    (is (thrown? Exception
                 (tenancy/com-tenant* *ds* ente
                   (fn [tx] (prop/protocolar! tx (pl ente {:tipo "indicacao"})))))   ; sem objeto_indicacao
        "indicacao sem objeto_indicacao viola o CHECK por tipo")
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (is (= 1 (:sequencial (prop/protocolar! tx (pl ente {:tipo "indicacao" :objeto-indicacao "Pavimentar a rua Y"}))))
            "indicacao COM objeto_indicacao protocola")))))
