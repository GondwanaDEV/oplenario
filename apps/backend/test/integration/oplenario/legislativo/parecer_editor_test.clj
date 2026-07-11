(ns oplenario.legislativo.parecer-editor-test
  "INTEGRACAO (PG real): a COMPOSICAO do Repo-Component (ADR-0001 §3-bis) do EDITOR de parecer (Onda B
  Slice 5) — `buscar-parecer-para-editor` (leitura agregada NUMA tx) + `emitir-parecer!` (promove
  rascunho->vigente se houver + registra o voto SEMPRE + tenta transicionar + emite parecer.transicionou,
  1 tx; fail-closed se nao houver NENHUM conteudo de texto p/ emitir)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [next.jdbc :as jdbc]
            [oplenario.cadastros.relacoes.cadastro :as rel-cad]
            [oplenario.config :as config]
            [oplenario.identidade.relacoes.identidade :as rel-id]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.outbox :as outbox]
            [oplenario.legislativo.components.repositorio :as repo]
            [oplenario.migracao :as migracao]
            [oplenario.motor.components.registro-fatos :as rf])
  (:import (java.time LocalDate)))

(def ^:dynamic *ds* nil)
(def ^:dynamic *repo* nil)
(def ^:dynamic *registro* nil)

(use-fixtures :once
  (fn [t]
    (let [c   (component/start (datasource/datasource (config/carregar)))
          reg (component/start (rf/registro-fatos (merge rel-cad/relacoes rel-id/relacoes)))]
      (migracao/migrar! (:ds c))
      (binding [*ds*       (:ds c)
                *repo*     (repo/->RepoLegislativoPg c (outbox/bus))
                *registro* reg]
        (try (t) (finally (component/stop reg) (component/stop c)))))))

(def ^:private data (LocalDate/parse "2026-03-01"))

(defn- montar-template!
  "Template minimo p/ o editor: 'rascunho' (inicial) -[emitir, guard nil]-> 'emitido' (TERMINAL)."
  [ente]
  (let [tid (random-uuid)]
    (repo/criar-template! *repo* ente {:id tid :chave "parecer_editor" :versao 1 :sujeito "parecer"
                                       :nome "Parecer editor [FIXTURE]" :estado-inicial "rascunho"})
    (repo/criar-estado! *repo* ente {:id (random-uuid) :template-id tid :chave "rascunho" :nome "Rascunho" :terminal false})
    (repo/criar-estado! *repo* ente {:id (random-uuid) :template-id tid :chave "emitido" :nome "Emitido" :terminal true})
    (repo/criar-transicao! *repo* ente {:id (random-uuid) :template-id tid :de-estado "rascunho"
                                        :para-estado "emitido" :gatilho "emitir" :ordem 1})
    tid))

(defn- protocolar! [ente]
  (:id (repo/protocolar! *repo* ente {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026
                                      :uf "CE" :municipio-nome "Fortaleza" :ementa "Dispoe sobre X"})))

(defn- iniciar-parecer! [ente tid pid]
  (:id (repo/iniciar-parecer! *repo* ente {:id (random-uuid) :objeto-tipo "proposicao" :objeto-id pid
                                           :comissao-id (random-uuid) :template-id tid})))

;; ========================= buscar-parecer-para-editor =========================

(deftest buscar-parecer-para-editor-sem-rascunho-ainda
  (let [ente (random-uuid) tid (montar-template! ente) pid (protocolar! ente) pcid (iniciar-parecer! ente tid pid)
        r (repo/buscar-parecer-para-editor *repo* ente pcid)]
    (is (some? r))
    (is (= pcid (:id (:parecer r))))
    (is (= "proposicao" (:objeto-tipo (:parecer r))))
    (is (= pid (:id (:objeto r))) "objeto-tipo='proposicao' -> objeto resolvido")
    (is (nil? (:texto-rascunho r)))
    (is (nil? (:texto-vigente r)))))

(deftest buscar-parecer-para-editor-com-rascunho
  (let [ente (random-uuid) tid (montar-template! ente) pid (protocolar! ente) pcid (iniciar-parecer! ente tid pid)
        _ (repo/nova-versao-parecer! *repo* ente {:id (random-uuid) :parecer-id pcid
                                                  :texto-inline "## Relatório\n\nR\n\n## Análise\n\nA"
                                                  :origem-versao "redacao" :formato "markdown"})
        r (repo/buscar-parecer-para-editor *repo* ente pcid)]
    (is (some? (:texto-rascunho r)))
    (is (= "## Relatório\n\nR\n\n## Análise\n\nA" (:texto-inline (:texto-rascunho r))))
    (is (nil? (:texto-vigente r)) "ainda nao promovido")))

(deftest buscar-parecer-para-editor-parecer-inexistente-e-nil
  (let [ente (random-uuid)]
    (is (nil? (repo/buscar-parecer-para-editor *repo* ente (random-uuid))))))

;; ========================= emitir-parecer! =========================

(defn- eventos-parecer [ente]
  (jdbc/execute! *ds*
    ["SELECT tipo FROM shared.outbox WHERE ente_id = ? AND tipo = 'parecer.transicionou' ORDER BY id" ente]))

(deftest emitir-parecer-sem-rascunho-e-sem-vigente-lanca
  (let [ente (random-uuid) tid (montar-template! ente) pid (protocolar! ente) pcid (iniciar-parecer! ente tid pid)]
    (is (thrown-with-msg? Exception #"nenhum conteudo de texto"
          (repo/emitir-parecer! *repo* ente *registro*
            {:parecer-id pcid :template-id tid :gatilho "emitir" :voto-relator "favoravel"
             :updated-by nil :agora data :contexto {} :lock-version 0})))
    (is (empty? (eventos-parecer ente)) "nada emitido — a validacao barrou antes de qualquer escrita")))

(deftest emitir-parecer-com-rascunho-promove-vota-e-transiciona
  (let [ente (random-uuid) tid (montar-template! ente) pid (protocolar! ente) pcid (iniciar-parecer! ente tid pid)]
    (repo/nova-versao-parecer! *repo* ente {:id (random-uuid) :parecer-id pcid
                                            :texto-inline "## Relatório\n\nR\n\n## Análise\n\nA"
                                            :origem-versao "redacao" :formato "markdown"})
    (let [r (repo/emitir-parecer! *repo* ente *registro*
              {:parecer-id pcid :template-id tid :gatilho "emitir" :voto-relator "favoravel"
               :updated-by nil :agora data :contexto {} :lock-version 0})]
      (is (= "emitido" (:estado r)) "transicionou (guard nil) — estado final devolvido")
      (is (= "favoravel" (:voto-relator r)))
      (is (some? (:texto-vigente-versao-id r)) "o rascunho foi PROMOVIDO a vigente")
      (is (= 1 (count (eventos-parecer ente))) "parecer.transicionou emitido"))))

(deftest emitir-parecer-com-vigente-ja-gravado-sem-rascunho-novo-nao-lanca
  (let [ente (random-uuid) tid (montar-template! ente) pid (protocolar! ente) pcid (iniciar-parecer! ente tid pid)
        {vid :id} (repo/nova-versao-parecer! *repo* ente {:id (random-uuid) :parecer-id pcid
                                                          :texto-inline "## Relatório\n\nV\n\n## Análise\n\nV"
                                                          :origem-versao "redacao" :formato "markdown"})]
    ;; promove ANTES de emitir-parecer! — no momento de emitir, ja' nao ha' rascunho (o unico virou vigente).
    (repo/promover-versao-parecer! *repo* ente {:parecer-id pcid :versao-id vid :updated-by nil :lock-version 0})
    (is (nil? (:texto-rascunho (repo/buscar-parecer-para-editor *repo* ente pcid))) "precondicao: sem rascunho")
    ;; promover-versao-parecer! ja incrementou pareceres.lock_version (o reaponte do pointer) — o lock-version
    ;; do CLIENTE aqui e' 1, o mesmo que um GET do editor teria devolvido apos a promocao.
    (let [r (repo/emitir-parecer! *repo* ente *registro*
              {:parecer-id pcid :template-id tid :gatilho "emitir" :voto-relator "contrario"
               :updated-by nil :agora data :contexto {} :lock-version 1})]
      (is (= "emitido" (:estado r)) "transicionou mesmo sem rascunho novo (o vigente ja existente basta)")
      (is (= "contrario" (:voto-relator r)) "voto seta SEMPRE, mesmo sem promocao de texto novo")
      (is (= 1 (count (eventos-parecer ente)))))))

;; ========================= emitir-parecer! — CAS otimista (review HIGH fe-11-parecer) =========================

(deftest emitir-parecer-com-lock-version-desatualizado-lanca-sem-escrever
  (let [ente (random-uuid) tid (montar-template! ente) pid (protocolar! ente) pcid (iniciar-parecer! ente tid pid)]
    (repo/nova-versao-parecer! *repo* ente {:id (random-uuid) :parecer-id pcid
                                            :texto-inline "## Relatório\n\nR\n\n## Análise\n\nA"
                                            :origem-versao "redacao" :formato "markdown"})
    ;; simula outro usuario ja tendo mudado o parecer (designar-relator! incrementa lock_version pra 1) —
    ;; o cliente ainda manda o lock-version STALE (0, o que ele viu antes dessa mudanca concorrente).
    (repo/designar-relator! *repo* ente {:id pcid :relator-id (random-uuid) :updated-by nil :lock-version 0})
    (is (thrown-with-msg? Exception #"conflito de escrita"
          (repo/emitir-parecer! *repo* ente *registro*
            {:parecer-id pcid :template-id tid :gatilho "emitir" :voto-relator "favoravel"
             :updated-by nil :agora data :contexto {} :lock-version 0})))
    (is (empty? (eventos-parecer ente)) "conflito detectado ANTES de qualquer escrita (nem voto, nem promocao)")
    (is (nil? (:texto-vigente (repo/buscar-parecer-para-editor *repo* ente pcid)))
        "o rascunho NAO foi promovido — a CAS barrou antes da promocao")))
