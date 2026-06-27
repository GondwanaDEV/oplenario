(ns oplenario.marco-m2-test
  "MARCO M2 — 'compliance vivo' (F2.5, capstone do KEYSTONE): o motor SAI do atom e avalia contra
  Postgres com fatos REAIS, e `policy.check` nega por papel, por relação dinâmica e por ESTADO.
  Cross-módulo (cadastros ⋈ motor ⋈ kernel/autorizacao) pela fronteira do RegistroFatos — nenhum
  módulo de PRODUÇÃO importa o outro (§22.10); o host injeta as relações, o motor resolve por nome.
  (O teste, não sendo módulo, compõe os Repos diretamente.)

  Prova:
   - COMPLIANCE VIVO: um template avalia `tribunal_competente()` (E1, §22.7.9) resolvido do cadastros
     real (ente ⋈ municipios ⋈ jurisdicao_camara) sob a tx do tenant → obrigação materializa.
   - policy NEGA POR ESTADO: `tem_mandato_vigente(ator, hoje())` permite com mandato vigente; após a
     cassação (mudança de estado real no banco) a MESMA política nega.
   - policy NEGA POR RELAÇÃO: `é_o_próprio` (relação pura registrada).
   - authz NEGA POR PAPEL: a camada grossa `exige-papel!` (snapshot do token)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [next.jdbc :as jdbc]
            [oplenario.cadastros.components.repositorio :as repo-cad]
            [oplenario.cadastros.db.referencia :as referencia]
            [oplenario.config :as config]
            [oplenario.kernel.autorizacao :as autz]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.migracao :as migracao]
            [oplenario.motor.api :as motor]
            [oplenario.motor.nucleo :as nuc]
            [oplenario.sistema :as sistema])
  (:import (java.time LocalDate)))

(def ^:dynamic *sys* nil)

(defn- seed-referencias!
  "Referências (sem ente_id) — DONO no :ds (o app role só LÊ). Idempotente: municipio/tribunal por
  ON CONFLICT; jurisdicao (sem ON CONFLICT no insert) por DELETE+INSERT do par (CE, NULL)."
  [ds]
  (referencia/inserir-municipio! ds {:codigo-ibge "2304400" :nome "Fortaleza" :uf "CE" :capital true :populacao 2703391})
  (referencia/inserir-tribunal! ds {:codigo "TCE-CE" :nome "Tribunal de Contas do Estado do Ceara" :uf "CE" :tipo "estadual"})
  (jdbc/execute-one! ds ["DELETE FROM cadastros.jurisdicao_camara WHERE uf = 'CE' AND municipio_ibge IS NULL"])
  (referencia/inserir-jurisdicao! ds {:id (random-uuid) :uf "CE" :municipio-ibge nil :tribunal-codigo "TCE-CE"}))

(use-fixtures :once
  (fn [t]
    (let [s (component/start (sistema/novo-sistema (config/carregar)))]
      (migracao/migrar! (:ds (:datasource s)))
      (seed-referencias! (:ds (:datasource s)))
      (binding [*sys* s] (try (t) (finally (component/stop s)))))))

(def ^:private HOJE (LocalDate/of 2026 6 19))

;; template de compliance que usa um fato REAL do cadastros (tribunal_competente, E1)
(def ^:private TPL-TRIBUNAL "
template: prova_m2_tribunal
contexto: compliance
dominio: tribunal_de_contas
parametros: { competencia: Competencia }
aplica_quando: tribunal_competente() == \"TCE-CE\"
exige: tribunal_competente() == \"TCE-CE\"
prazo:
  janela: fim_de(competencia)
  a_partir_de: fim_de(competencia)
severidade: bloqueante
referencia_normativa: \"prova M2 (E1)\"
")

(defn- seed-casa!
  "Semeia o TENANT de uma Casa (ente/legislatura/vereador/mandato vigente via Repo). As referências
  (municipio/tribunal/jurisdição) já estão semeadas no fixture. Devolve {:ente :identidade}."
  [{:keys [repo-cadastros]}]
  (let [ente (random-uuid) ident (random-uuid)
        vereador (random-uuid) legislatura (random-uuid) mandato (random-uuid)]
    ;; tenant — via Repo (com-tenant*)
    (repo-cad/criar-ente! repo-cadastros ente {:ente-id ente :municipio-ibge "2304400" :nome-oficial "Camara Municipal de Fortaleza"})
    (repo-cad/criar-legislatura! repo-cadastros ente {:id legislatura :ente-id ente :numero 19 :ano-inicio 2025 :ano-fim 2028 :vigente true})
    (repo-cad/criar-vereador! repo-cadastros ente {:id vereador :ente-id ente :identidade-id ident :nome "Maria Souza" :nome-parlamentar "Maria do Povo"})
    (repo-cad/criar-mandato! repo-cadastros ente {:id mandato :ente-id ente :vereador-id vereador :legislatura-id legislatura
                                                  :partido "PT" :estado "vigente" :natureza "titular"
                                                  :vigencia-inicio (LocalDate/of 2025 1 1)})
    {:ente ente :identidade ident}))

(defn- cassar-mandato! [repo-cadastros ente identidade]
  (let [v  (repo-cad/vereador-por-identidade repo-cadastros ente identidade)
        ms (repo-cad/mandatos-do-vereador repo-cadastros ente (:id v))]
    (repo-cad/mudar-estado-mandato! repo-cadastros ente {:id (:id (first ms)) :estado "cassado" :fim-efetivo HOJE})))

(defn- nega? [f]
  (try (f) false (catch clojure.lang.ExceptionInfo e (autz/negado? e))))

(deftest m2-compliance-vivo-fato-real-do-cadastros
  (let [{:keys [datasource repo-motor registro-fatos] :as sys} *sys*
        ds (:ds datasource)
        {:keys [ente]} (seed-casa! sys)
        r (tenancy/com-tenant* ds ente
            (fn [tx]
              (motor/avaliar {:registro registro-fatos :repo-motor repo-motor :tx tx :ente-id ente
                              :regra (nuc/carregar-envelope TPL-TRIBUNAL) :reg-ver "registry-v1@2026-06-20"
                              :objeto-tipo "competencia" :objeto-id "2026-05"
                              :amb {"competencia" {:ano 2026 :mes 5}} :agora HOJE})))]
    (is (= "conforme" (:veredito (:avaliacao r))) "tribunal_competente()=='TCE-CE' (E1 real) → conforme")
    (is (= 1 (count (:obrigacoes r))) "obrigação materializou (sai do atom, avalia contra PG)")
    (is (= (LocalDate/of 2026 5 31) (:vence-em (first (:obrigacoes r)))) "vence_em = fim_de(competencia 2026/5) = 31/05")))

(deftest m2-policy-nega-por-estado-relacao-e-papel
  (let [{:keys [datasource registro-fatos repo-cadastros] :as sys} *sys*
        ds (:ds datasource)
        {:keys [ente identidade]} (seed-casa! sys)
        ator {:identidade identidade :ente-id ente :papeis #{:vereador}}]
    ;; (1) POR ESTADO: política tem_mandato_vigente — permite vigente; nega após cassação (mudança real)
    (tenancy/com-tenant* ds ente
      (fn [tx]
        (let [politica (motor/politica-dsl {:registro registro-fatos :tx tx
                                            :expr "tem_mandato_vigente(ator.identidade, hoje())" :agora HOJE})]
          (is (true? (autz/check! ator :votar {} politica)) "mandato vigente → permite"))))
    (cassar-mandato! repo-cadastros ente identidade)
    (tenancy/com-tenant* ds ente
      (fn [tx]
        (let [politica (motor/politica-dsl {:registro registro-fatos :tx tx
                                            :expr "tem_mandato_vigente(ator.identidade, hoje())" :agora HOJE})]
          (is (nega? #(autz/check! ator :votar {} politica)) "após cassação → NEGA (por estado)"))))
    ;; (2) POR RELAÇÃO dinâmica: é_o_próprio (pura)
    (let [politica (motor/politica-dsl {:registro registro-fatos :tx nil
                                        :expr "é_o_próprio(recurso.autor, ator.identidade)" :agora HOJE})]
      (is (true? (autz/check! ator :editar {:autor identidade} politica)) "é o próprio autor → permite")
      (is (nega? #(autz/check! ator :editar {:autor (random-uuid)} politica)) "não é o autor → NEGA (por relação)"))
    ;; (3) POR PAPEL: camada grossa (snapshot do token)
    (is (nega? #(autz/exige-papel! ator :presidente_mesa)) "sem o papel → NEGA (por papel, camada grossa)")))
