(ns oplenario.destinatarios-test
  "INTEGRACAO (PG real): ADR-0020 — o HOST resolve QUEM RECEBE sobre `cadastros` + `identidade` de verdade (o que o
  modulo `comunicacao` recebe pronto pelos seams). So' recebe quem tem vinculo ATIVO de quem trabalha na Casa: o
  cidadao nunca, o vinculo suspenso nao, o vereador sem identidade e' contado em `:sem-acesso`. \"Todos os setores\" =
  servidores e administradores (nao vereadores). Quem envia a grupo: secretaria, admin, ou vereador da Mesa vigente."
  (:require [oplenario.suporte-cpf :refer [cpf-valido] :rename {cpf-valido cpf}]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.stuartsierra.component :as component]
            [next.jdbc :as jdbc]
            [oplenario.cadastros.components.repositorio :as repo-cad]
            [oplenario.cadastros.db.comissao :as comissao]
            [oplenario.cadastros.db.estrutura :as estrutura]
            [oplenario.cadastros.db.referencia :as referencia]
            [oplenario.cadastros.db.vereador :as vereador]
            [oplenario.config :as config]
            [oplenario.destinatarios :as dest]
            [oplenario.identidade.components.repositorio :as repo-id]
            [oplenario.identidade.db.identidade :as id]
            [oplenario.identidade.db.vinculo :as vinc]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.migracao :as migracao]
            [oplenario.rotas :as rotas])
  (:import (java.time LocalDate)))

(def ^:dynamic *c* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*c* c] (try (t) (finally (component/stop c)))))))

(def hoje (LocalDate/parse "2026-10-02"))

(defn- casa!
  "Uma Casa com: servidores Sara (secretario) e Saulo (sem papel), a administradora Ada (vinculo admin_ente), a
  advogada Jussara (servidor + juridico), a cidada Cida; os vereadores Vitor (Mesa, presidente; com identidade),
  Valeria (identidade, vinculo SUSPENSO) e Vicente (sem identidade); a Comissao de Financas com os tres; o setor
  Juridico (ativo: Jussara, Saulo e a cidada, lotada por fora) e o setor Antigo (desativado)."
  []
  (let [ds (:ds *c*) ente (random-uuid)
        pessoa! (fn [nome] (id/inserir! ds {:id (random-uuid) :cpf (cpf) :nome nome}))
        p (into {} (map (fn [[k n]] [k (pessoa! n)]))
                {:sara "Sara Secretária" :saulo "Saulo Servidor" :ada "Ada Administradora" :jussara "Jussara Advogada"
                 :cida "Cida Cidadã" :vitor "Vitor Vereador" :valeria "Valéria Vereadora"})
        leg (random-uuid) com (random-uuid) mesa (random-uuid)
        v {:vitor (random-uuid) :valeria (random-uuid) :vicente (random-uuid)}
        setor-j (random-uuid) setor-a (random-uuid)]
    (try (referencia/inserir-municipio! ds {:codigo-ibge "2304400" :nome "Fortaleza" :uf "CE" :capital true :populacao 1})
         (catch Exception _ nil))
    (tenancy/com-tenant* ds ente
      (fn [tx]
        (estrutura/inserir-ente! tx {:ente-id ente :municipio-ibge "2304400" :nome-oficial "Câmara Teste"})
        (estrutura/inserir-legislatura! tx {:id leg :ente-id ente :numero 1 :ano-inicio 2025 :ano-fim 2028 :vigente true})
        (doseq [[k t papeis estado] [[:sara "servidor" ["secretario"] "ativo"] [:saulo "servidor" [] "ativo"]
                                     [:ada "admin_ente" ["admin_ente"] "ativo"]
                                     [:jussara "servidor" ["juridico"] "ativo"] [:cida "cidadao" [] "ativo"]
                                     [:vitor "vereador" ["vereador"] "ativo"] [:valeria "vereador" ["vereador"] "suspenso"]]]
          (vinc/criar! tx {:id (random-uuid) :ente-id ente :identidade-id (p k) :tipo t :estado estado})
          (doseq [pp papeis] (vinc/adicionar-papel! tx {:id (random-uuid) :ente-id ente :identidade-id (p k) :papel pp})))
        (doseq [[k nome iid] [[:vitor "Vitor Alves" (p :vitor)] [:valeria "Valéria Braga" (p :valeria)]
                              [:vicente "Vicente Costa" nil]]]
          (vereador/inserir! tx {:id (v k) :ente-id ente :identidade-id iid :nome nome})
          (vereador/inserir-mandato! tx {:id (random-uuid) :ente-id ente :vereador-id (v k) :legislatura-id leg
                                         :partido "X" :estado "vigente" :natureza "titular"
                                         :vigencia-inicio (LocalDate/parse "2025-01-01")}))
        (comissao/inserir! tx {:id mesa :ente-id ente :nome "Mesa Diretora" :tipo "mesa" :legislatura-id leg
                               :vigencia-inicio (LocalDate/parse "2025-01-01")})
        (comissao/inserir-membro! tx {:id (random-uuid) :ente-id ente :comissao-id mesa :vereador-id (v :vitor)
                                      :vigencia-inicio (LocalDate/parse "2025-01-01")})
        (comissao/inserir-cargo! tx {:id (random-uuid) :ente-id ente :comissao-id mesa :vereador-id (v :vitor)
                                     :cargo "presidente" :vigencia-inicio (LocalDate/parse "2025-01-01")})
        (comissao/inserir! tx {:id com :ente-id ente :nome "Comissão de Finanças" :tipo "permanente" :legislatura-id leg
                               :vigencia-inicio (LocalDate/parse "2025-01-01")})
        (doseq [k [:vitor :valeria :vicente]]
          (comissao/inserir-membro! tx {:id (random-uuid) :ente-id ente :comissao-id com :vereador-id (v k)
                                        :vigencia-inicio (LocalDate/parse "2025-01-01")}))
        (jdbc/execute! tx ["INSERT INTO cadastros.setor (ente_id, id, nome) VALUES (?, ?, 'Jurídico'), (?, ?, 'Antigo')"
                           ente setor-j ente setor-a])
        (jdbc/execute! tx ["UPDATE cadastros.setor SET ativo = false WHERE ente_id = ? AND id = ?" ente setor-a])
        (doseq [k [:jussara :saulo :cida]]
          (jdbc/execute! tx ["INSERT INTO cadastros.setor_membro (ente_id, setor_id, identidade_id) VALUES (?, ?, ?)"
                             ente setor-j (p k)]))))
    {:ente ente :p p :v v :comissao com :mesa mesa :setor-j setor-j :setor-a setor-a}))

(defn- repos []
  (let [rc (repo-cad/->RepoCadastrosPg *c*)
        ri (repo-id/repositorio)
        ri (assoc ri :datasource *c*)]
    {:repo-cadastros rc :repo-identidade ri :hoje (constantly hoje)
     :cargo-na-mesa (fn [ente iid] (rotas/cargo-na-mesa rc ente iid hoje))}))

(deftest quem-recebe-cada-destino
  (let [{:keys [ente p v comissao mesa setor-j setor-a]} (casa!)
        rs (repos)
        resolver (fn [tipo alvo] (dest/resolver-destino rs ente hoje {:tipo tipo :alvo-id alvo}))
        ids (fn [r] (set (map :identidade-id (:pessoas r))))]
    (testing "pessoa: so' quem tem vinculo ativo de quem trabalha na Casa"
      (is (= {:alvo-nome "Saulo Servidor" :pessoas [{:identidade-id (p :saulo) :nome "Saulo Servidor"}] :sem-acesso 0}
             (resolver "pessoa" (p :saulo))))
      (is (nil? (resolver "pessoa" (p :cida))) "cidadao nao e' destino")
      (is (nil? (resolver "pessoa" (p :valeria))) "vinculo suspenso nao entra")
      (is (nil? (resolver "pessoa" (random-uuid)))))
    (testing "vereador: o nome parlamentar/civil do cadastro; sem acesso e' contado"
      (is (= #{(p :vitor)} (ids (resolver "vereador" (v :vitor)))))
      (is (= "Vitor Alves" (:alvo-nome (resolver "vereador" (v :vitor)))))
      (is (= {:pessoas [] :sem-acesso 1} (select-keys (resolver "vereador" (v :vicente)) [:pessoas :sem-acesso])))
      (is (= 1 (:sem-acesso (resolver "vereador" (v :valeria)))))
      (is (nil? (resolver "vereador" (random-uuid)))))
    (testing "setor: os lotados com acesso; desativado nao e' destino"
      (let [r (resolver "setor" setor-j)]
        (is (= #{(p :jussara) (p :saulo)} (ids r)))
        (is (= 1 (:sem-acesso r)) "a cidada lotada por fora nao recebe")
        (is (= "Jurídico" (:alvo-nome r))))
      (is (nil? (resolver "setor" setor-a))))
    (testing "comissao (e a Mesa): os membros vigentes com acesso"
      (let [r (resolver "comissao" comissao)]
        (is (= #{(p :vitor)} (ids r)))
        (is (= 2 (:sem-acesso r)))
        (is (= "Vitor Alves" (:nome (first (:pessoas r)))) "o nome de vereador na comissao"))
      (is (= #{(p :vitor)} (ids (resolver "comissao" mesa))))
      (is (nil? (resolver "comissao" (random-uuid)))))
    (testing "todos os setores: servidores e administradores, nunca vereador nem cidadao"
      (is (= #{(p :sara) (p :saulo) (p :ada) (p :jussara)} (ids (resolver "todos" nil)))))
    (testing "outra Casa: nada"
      (is (nil? (dest/resolver-destino rs (random-uuid) hoje {:tipo "setor" :alvo-id setor-j})))
      (is (nil? (dest/resolver-destino rs (random-uuid) hoje {:tipo "pessoa" :alvo-id (p :sara)}))))))

(deftest quem-envia-a-grupo-e-as-opcoes
  (let [{:keys [ente p v]} (casa!)
        rs (repos)
        ator (fn [k papeis] {:ente-id ente :identidade-id (p k) :tipo-vinculo "servidor" :papeis papeis})]
    (is (dest/pode-enviar-a-grupos? (:cargo-na-mesa rs) (ator :sara #{"secretario"})))
    (is (dest/pode-enviar-a-grupos? (:cargo-na-mesa rs) (ator :ada #{"admin_ente"})))
    (is (dest/pode-enviar-a-grupos? (:cargo-na-mesa rs) (ator :vitor #{"vereador"})) "o presidente da Mesa vigente")
    (is (not (dest/pode-enviar-a-grupos? (:cargo-na-mesa rs) (ator :saulo #{}))))
    (is (not (dest/pode-enviar-a-grupos? (:cargo-na-mesa rs) (ator :jussara #{"juridico"}))))
    (let [o (dest/opcoes-de-destino rs ente hoje true)]
      (is (= #{"Sara Secretária" "Saulo Servidor" "Ada Administradora" "Jussara Advogada" "Vitor Vereador"}
             (set (map :nome (:pessoas o)))) "sem cidadao nem suspenso")
      (is (= {"Vitor Alves" true "Valéria Braga" false "Vicente Costa" false}
             (into {} (map (juxt :nome :tem-acesso)) (:vereadores o))))
      (is (= [{:nome "Jurídico" :membros 2}] (mapv #(select-keys % [:nome :membros]) (:setores o))) "so' os ativos")
      (is (= [["Mesa Diretora" 1 0] ["Comissão de Finanças" 1 2]] (mapv (juxt :nome :membros :sem-acesso) (:comissoes o))))
      (is (= 4 (:todos-os-setores o))))
    (let [o (dest/opcoes-de-destino rs ente hoje false)]
      (is (empty? (:setores o)))
      (is (empty? (:comissoes o)))
      (is (zero? (:todos-os-setores o))))
    (testing "os avisos automaticos: vereadores com acesso; as pessoas com juridico"
      (is (= [(p :vitor)] (dest/vereadores-a-avisar rs ente hoje)))
      (is (= [(p :jussara)] (dest/pessoas-com-papel (:repo-identidade rs) ente "juridico"))))
    (is (= "Sara Secretária" ((:nome-de (dest/seams-de-comunicacao rs)) ente (p :sara))))
    (is (some? v))))
