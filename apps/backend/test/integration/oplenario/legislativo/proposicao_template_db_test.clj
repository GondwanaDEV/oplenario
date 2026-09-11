(ns oplenario.legislativo.proposicao-template-db-test
  "INTEGRACAO (PG real): Fatia 1 da borda de tramitacao — o elo MATERIA <-> TEMPLATE.

  A engine do eixo C (`db/tramitacao/transicionar!`) sempre exigiu `template-id`, e a proposicao nunca
  teve onde guardar o seu: por isso a borda HTTP nunca existiu. Esta fatia so' constroi o elo — a
  proposicao passa a saber POR QUAL RITO ela corre — espelhando o que `legislativo.pareceres` ja' faz
  (`template_id` + FK same-tenant + estado inicial DERIVADO do template).

  O que estes testes guardam:
  - Inv.4 (regra e' DADO): o `estado` da materia nova vem de `template.estado_inicial`, NUNCA de uma
    string cravada no codigo. As fixtures usam de proposito `estado-inicial` DIFERENTE de 'protocolada'
    (um teste que usasse 'protocolada' nao saberia distinguir o template do literal antigo).
  - Fail-closed: tenant com config AMBIGUA (mais de um rito ativo) recusa em vez de escolher.
  - Eixo H (numeracao gapless): a recusa acontece ANTES de consumir o sequencial — config errada nao
    abre buraco na numeracao oficial.
  - O elo tem dentes no BANCO tambem (FK same-tenant), nao so' no service."
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
          reg (component/start (rf/registro-fatos (merge rel-cad/relacoes rel-id/relacoes)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c) *registro* reg]
        (try (t) (finally (component/stop reg) (component/stop c)))))))

(defn- template!
  "Cria um template de tramitacao do tenant (sujeito default 'proposicao', ativo default true) e devolve
  o id. `estado-inicial` de proposito NUNCA e' 'protocolada' nas fixtures que provam a derivacao."
  [tx ente {:keys [chave estado-inicial sujeito ativo]}]
  (let [tid (random-uuid)]
    (tram/criar-template! tx {:id tid :ente-id ente :chave chave :versao 1
                              :nome (str "FIXTURE " chave) :estado-inicial estado-inicial
                              :sujeito sujeito :ativo ativo})
    tid))

(defn- protocolar! [tx ente m]
  (prop/protocolar! tx (merge {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026
                               :uf "CE" :municipio-nome "Fortaleza" :ementa "Dispoe sobre X"}
                              m)))

;; ---------------------------------------------------------------------------------------------------
;; O caminho feliz: um rito por Casa (o caso de hoje)
;; ---------------------------------------------------------------------------------------------------

(deftest template-unico-do-tenant-resolve-sozinho-e-da-o-estado-inicial
  ;; GUARDA: (1) o elo nasce preenchido na LINHA (nao so' no retorno); (2) o estado vem do TEMPLATE.
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [tid   (template! tx ente {:chave "rito_ordinario" :estado-inicial "recebida"})
              r     (protocolar! tx ente {})
              linha (prop/buscar tx ente (:id r))]
          (is (= tid (:template-id r)) "protocolar! devolve o rito resolvido")
          (is (= "recebida" (:estado r)) "estado devolvido = estado_inicial do template")
          (is (= tid (:template-id linha)) "a LINHA guarda o elo")
          (is (= "recebida" (:estado linha))
              "Inv.4: o estado da linha vem do template, nao do literal 'protocolada'"))))))

(deftest o-elo-e-o-que-a-engine-do-eixo-C-aceita
  ;; A razao de existir da fatia: o `template_id` LIDO DA LINHA e' exatamente o que `transicionar!` pede.
  ;; Sem este elo, a borda HTTP nao tinha de onde tirar o template — por isso nunca existiu.
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [tid (template! tx ente {:chave "rito_ordinario" :estado-inicial "recebida"})]
          (doseq [[ch term] [["recebida" false] ["em_comissoes" false]]]
            (tram/criar-estado! tx {:id (random-uuid) :ente-id ente :template-id tid :chave ch
                                    :nome ch :terminal term}))
          (tram/criar-transicao! tx {:id (random-uuid) :ente-id ente :template-id tid
                                     :de-estado "recebida" :para-estado "em_comissoes"
                                     :gatilho "despachar" :guarda nil :ordem 1})
          (let [{pid :id} (protocolar! tx ente {})
                tid-da-linha (:template-id (prop/buscar tx ente pid))
                r (tram/transicionar! tx {:registro *registro* :ente-id ente :proposicao-id pid
                                          :template-id tid-da-linha :gatilho "despachar"
                                          :agora (LocalDate/parse "2026-03-01")})]
            (is (true? (:transicionou? r)) "a materia tramita com o rito que ela mesma guarda")
            (is (= "em_comissoes" (:estado (prop/buscar tx ente pid))))))))))

;; ---------------------------------------------------------------------------------------------------
;; As tres saidas da regra de resolucao (explicito / zero / ambiguo)
;; ---------------------------------------------------------------------------------------------------

(deftest template-explicito-vence-a-resolucao-automatica
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (template! tx ente {:chave "rito_a" :estado-inicial "recebida"})
        (let [alvo (template! tx ente {:chave "rito_b" :estado-inicial "autuada"})
              r    (protocolar! tx ente {:template-id alvo})]
          (is (= alvo (:template-id r)) "o explicito vence — inclusive num tenant que seria ambiguo")
          (is (= "autuada" (:estado r)) "estado = estado_inicial do template EXPLICITO"))))))

(deftest zero-templates-a-materia-nasce-sem-rito
  ;; Comportamento de HOJE, agora explicito: materia sem template simplesmente NAO TRAMITA.
  ;; O 'protocolada' aqui e' o DEFAULT DA COLUNA (schema), nao um literal de decisao no codigo.
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [r     (protocolar! tx ente {})
              linha (prop/buscar tx ente (:id r))]
          (is (nil? (:template-id r)) "sem rito no tenant, a materia nasce SEM rito")
          (is (nil? (:template-id linha)))
          (is (= "protocolada" (:estado linha)) "estado = default da coluna (comportamento de hoje)")
          (is (= "protocolada" (:estado r)) "o retorno espelha a linha, nao um chute do codigo"))))))

(deftest mais-de-um-rito-ativo-recusa-em-vez-de-escolher
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (template! tx ente {:chave "rito_a" :estado-inicial "recebida"})
        (template! tx ente {:chave "rito_b" :estado-inicial "autuada"})
        (is (thrown-with-msg? Exception #"mais de um template"
              (protocolar! tx ente {}))
            "config ambigua FALHA FECHADA — o codigo nao escolhe o rito da Casa por conta")))))

(deftest recusa-por-ambiguidade-nao-queima-numero
  ;; Eixo H: a numeracao oficial e' GAPLESS. Se a resolucao do rito rodasse DEPOIS de `sequencial/proximo!`,
  ;; toda tentativa recusada abriria um buraco na numeracao da Casa.
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [t1 (template! tx ente {:chave "rito_a" :estado-inicial "recebida"})]
          (is (= 1 (:sequencial (protocolar! tx ente {}))))
          (template! tx ente {:chave "rito_b" :estado-inicial "autuada"})
          (is (thrown? Exception (protocolar! tx ente {})))
          (is (= 2 (:sequencial (protocolar! tx ente {:template-id t1})))
              "a tentativa RECUSADA nao consumiu numero"))))))

(deftest template-aposentado-nao-torna-o-tenant-ambiguo
  ;; Versionamento de template e' por COPIA INTEGRAL (mig 0016): apos o primeiro bump de versao, a Casa
  ;; tem 2 linhas de rito. Sem olhar `ativo`, protocolar! passaria a recusar TODA materia dessa Casa.
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (template! tx ente {:chave "rito_v1" :estado-inicial "recebida" :ativo false})
        (let [v2 (template! tx ente {:chave "rito_v2" :estado-inicial "autuada"})
              r  (protocolar! tx ente {})]
          (is (= v2 (:template-id r)) "so' o rito ATIVO conta na resolucao automatica")
          (is (= "autuada" (:estado r))))))))

;; ---------------------------------------------------------------------------------------------------
;; Fail-closed no explicito
;; ---------------------------------------------------------------------------------------------------

(deftest template-explicito-inexistente-recusa
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (is (thrown-with-msg? Exception #"template inexistente"
              (protocolar! tx ente {:template-id (random-uuid)})))))))

(deftest template-de-sujeito-parecer-e-recusado
  ;; Anti-misconfig cross-sujeito — o espelho exato de `db/parecer/criar!`, que recusa template de
  ;; 'proposicao'. As tabelas de template sao subject-agnosticas; quem valida e' o service do sujeito.
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [tp (template! tx ente {:chave "parecer_ccj" :estado-inicial "aguardando_designacao"
                                     :sujeito "parecer"})]
          (is (thrown-with-msg? Exception #"sujeito 'proposicao'"
                (protocolar! tx ente {:template-id tp}))
              "template de parecer nao rege materia (explicito)")
          (let [r (protocolar! tx ente {})]
            (is (nil? (:template-id r))
                "e o automatico tambem nao o enxerga — sujeito 'parecer' fica fora da busca")))))))

;; ---------------------------------------------------------------------------------------------------
;; O elo tem dentes no BANCO, nao so' no service
;; ---------------------------------------------------------------------------------------------------

(deftest fk-recusa-template-inexistente
  (let [ente (random-uuid) pid (atom nil)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx] (reset! pid (:id (protocolar! tx ente {})))))
    (is (thrown? Exception
          (tenancy/com-tenant* *ds* ente
            (fn [tx]
              (jdbc/execute-one! tx
                ["UPDATE legislativo.proposicoes SET template_id = ? WHERE ente_id = ? AND id = ?"
                 (random-uuid) ente @pid]))))
        "FK (ente_id, template_id) -> template_tramitacao recusa rito inexistente")))

(deftest template-de-outro-tenant-nao-serve
  (let [ente-a (random-uuid) ente-b (random-uuid) tid-b (atom nil) pid (atom nil)]
    (tenancy/com-tenant* *ds* ente-b
      (fn [tx] (reset! tid-b (template! tx ente-b {:chave "rito_b" :estado-inicial "recebida"}))))
    (tenancy/com-tenant* *ds* ente-a
      (fn [tx] (reset! pid (:id (protocolar! tx ente-a {})))))
    ;; (a) o service nao ve' o template do vizinho (RLS) — de dentro do tenant A ele nao existe
    (is (thrown-with-msg? Exception #"template inexistente"
          (tenancy/com-tenant* *ds* ente-a
            (fn [tx] (protocolar! tx ente-a {:template-id @tid-b})))))
    ;; (b) e o banco recusa o mesmo elo por baixo do service (FK same-tenant, nao so' FK)
    (is (thrown? Exception
          (tenancy/com-tenant* *ds* ente-a
            (fn [tx]
              (jdbc/execute-one! tx
                ["UPDATE legislativo.proposicoes SET template_id = ? WHERE ente_id = ? AND id = ?"
                 @tid-b ente-a @pid]))))
        "o par (ente_id, template_id) e' que fecha — template do vizinho nao casa")))
