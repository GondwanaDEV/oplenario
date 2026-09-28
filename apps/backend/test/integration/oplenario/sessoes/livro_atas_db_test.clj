(ns oplenario.sessoes.livro-atas-db-test
  "INTEGRACAO (PG real) — o LIVRO DE ATAS (Onda E, `livro-atas`): uma linha por sessao com ata publicada (a vigente),
  a mais recente primeiro, com a leitura no plenario; a ata de sessao secreta so' para a secretaria e NUNCA no portal;
  retificar nao apaga a versao anterior (`?versao=`); a ferramenta do assistente le so' ata publica."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [com.stuartsierra.component :as component]
            [next.jdbc :as jdbc]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.migracao :as migracao]
            [oplenario.sessoes.adapters.out.livro-atas :as out]
            [oplenario.sessoes.components.repositorio :as repo]
            [oplenario.sessoes.controllers :as controllers]
            [oplenario.sessoes.db.ata :as ata]
            [oplenario.sessoes.db.sessao :as sessao]
            [oplenario.sessoes.diplomat.catalogo :as catalogo-sessoes])
  (:import (java.time Instant)))

(def ^:dynamic *ds* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c)] (try (t) (finally (component/stop c)))))))

(defn- sessao! [ente tipo estado quando]
  (tenancy/com-tenant* *ds* ente
    (fn [tx]
      (let [id (:id (sessao/agendar! tx {:id (random-uuid) :ente-id ente :sessao-legislativa-id (random-uuid)
                                         :tipo-sessao tipo :agendada-para (Instant/parse quando)}))]
        (when (not= estado "agendada")
          (jdbc/execute! tx ["UPDATE sessoes.sessao SET estado = ?, aberta_em = ?::timestamptz,
                                encerrada_em = CASE WHEN ? IN ('encerrada','arquivada') THEN ?::timestamptz END
                              WHERE ente_id = ? AND id = ?"
                             estado quando estado quando ente id]))
        id))))

(def quem #uuid "40000000-0000-0000-0000-000000000004")

(defn- publicar! [ente sid texto]
  (tenancy/com-tenant* *ds* ente
    #(ata/publicar! % {:ente-id ente :sessao-id sid :texto texto :origem-redacao "redigida_externamente"
                       :conteudo-sha256 (str "sha256:" (hash texto)) :publicada-por quem
                       :motivo-retificacao "nome do vereador corrigido"})))

(defn- rs [] (repo/map->RepoSessoesPg {:datasource {:ds *ds*}}))
(defn- buscar [ente id] (tenancy/com-tenant* *ds* ente #(sessao/buscar % ente id)))
(defn- ator [ente & papeis] {:ente-id ente :identidade-id (random-uuid) :papeis (set papeis)})
(def nome (fn [_ i] (when (= i quem) "Maria Secretária")))

(defn- casa!
  "Tres sessoes com ata (a publica de 10/09 retificada; a secreta de 12/09; a publica de 14/09) e uma sem ata. A de
  15/09 (aberta) le a ata de 14/09."
  []
  (let [ente (random-uuid)
        s10 (sessao! ente "ordinaria" "encerrada" "2026-09-10T18:00:00Z")
        sec (sessao! ente "secreta" "encerrada" "2026-09-12T18:00:00Z")
        s14 (sessao! ente "ordinaria" "encerrada" "2026-09-14T18:00:00Z")
        _sem (sessao! ente "ordinaria" "encerrada" "2026-09-13T18:00:00Z")
        hoje (sessao! ente "ordinaria" "aberta" "2026-09-15T18:00:00Z")]
    (publicar! ente s10 "Ata de 10/09, primeira versao.")
    (publicar! ente s10 "Ata de 10/09, retificada.")
    (publicar! ente sec "Ata sigilosa.")
    (publicar! ente s14 "Ata de 14/09.")
    (repo/registrar-leitura-ata! (rs) ente {:sessao (buscar ente hoje) :ata-sessao-id s14 :ata-versao 1
                                            :modo "presencial" :registrada-por (random-uuid)})
    {:ente ente :s10 s10 :sec sec :s14 s14}))

(deftest o-livro-lista-a-vigente-de-cada-sessao-com-ata-a-mais-recente-primeiro
  (let [{:keys [ente s10 sec s14]} (casa!)]
    (testing "a secretaria ve todas, inclusive a secreta; sessao sem ata nao entra"
      (let [livro (controllers/livro-de-atas (rs) (ator ente "secretario"))]
        (is (= [s14 sec s10] (mapv :sessao-id livro)))
        (is (= [1 1 2] (mapv :versao livro)) "a de 10/09 foi retificada: vale a v2")
        (is (= "presencial" (:leitura-modo (first livro))) "a de 14/09 foi lida na sessao seguinte")
        (is (nil? (:leitura-modo (last livro))))))
    (testing "o vereador nao ve a secreta — nem id, nem data"
      (is (= [s14 s10] (mapv :sessao-id (controllers/livro-de-atas (rs) (ator ente "vereador"))))))
    (testing "o portal: so' sessoes publicas"
      (let [w (out/livro->wire (controllers/livro-de-atas-publico (rs) ente))]
        (is (= [(str s14) (str s10)] (mapv #(get-in % [:sessao :id]) (:atas w))))
        (is (= {:modo "presencial" :ata-versao 1} (select-keys (:leitura (first (:atas w))) [:modo :ata-versao])))))
    (testing "outra Casa nao ve nada desta (RLS)"
      (is (empty? (controllers/livro-de-atas-publico (rs) (random-uuid)))))))

(deftest a-ata-aberta-traz-o-texto-o-historico-e-a-versao-anterior-continua-legivel
  (let [{:keys [ente s10 sec]} (casa!)
        sec-ator (ator ente "secretario")]
    (testing "a vigente, com quem publicou (tela interna)"
      (let [w (out/ata-do-livro->wire (controllers/ata-do-livro (rs) nome sec-ator s10 nil) false)]
        (is (= "Ata de 10/09, retificada." (:texto w)))
        (is (true? (:vigente w)))
        (is (= [2 1] (mapv :versao (:versoes w))))
        (is (= "nome do vereador corrigido" (get-in w [:versoes 0 :motivo-retificacao])))
        (is (= "Maria Secretária" (get-in w [:versao :publicada-por-nome])))))
    (testing "retificar nao apaga: a v1 continua legivel, marcada como nao vigente"
      (let [w (out/ata-do-livro->wire (controllers/ata-do-livro (rs) nome sec-ator s10 1) false)]
        (is (= "Ata de 10/09, primeira versao." (:texto w)))
        (is (false? (:vigente w)))))
    (is (nil? (controllers/ata-do-livro (rs) nome sec-ator s10 3)) "versao que nao existe -> 404")
    (testing "a secreta: a secretaria le; o vereador recebe 404 (nao 403)"
      (is (some? (controllers/ata-do-livro (rs) nome sec-ator sec nil)))
      (is (nil? (controllers/ata-do-livro (rs) nome (ator ente "vereador") sec nil))))
    (testing "o portal: sem nome de servidor, e a secreta nao existe"
      (let [w (out/ata-do-livro->wire (controllers/ata-do-livro-publica (rs) ente s10 nil) true)]
        (is (= "Ata de 10/09, retificada." (:texto w)))
        (is (every? nil? (map :publicada-por-nome (cons (:versao w) (:versoes w))))))
      (is (nil? (controllers/ata-do-livro-publica (rs) ente sec nil)))
      (is (nil? (controllers/ata-do-livro-publica (rs) (random-uuid) s10 nil)) "a sessao de outra Casa nao responde"))))

(deftest a-ferramenta-do-assistente-le-so-ata-publica
  (let [{:keys [ente s10 sec s14]} (casa!)
        ferramenta (first (filter #(= "ata_da_sessao" (:nome %)) catalogo-sessoes/entradas))
        executar #((:executar ferramenta) {:repo-sessoes (rs)} (ator ente "secretario") %)]
    (is (= (str s14) (get-in (executar {}) [:sessao :id])) "sem sessao informada: a ata publicada mais recente")
    (is (= "Ata de 10/09, retificada." (:texto (executar {:sessao-id s10}))))
    (is (nil? (executar {:sessao-id sec})) "a secreta nunca vai a IA — nem para a secretaria")))
