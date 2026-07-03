(ns oplenario.participacao.comentario-test
  "INTEGRACAO (PG real) — FAST-FOLLOW Slice 6: COMENTARIOS/MODERACAO (feature 6.3) via o Repo-Component.
  Prova, contra o banco real sob FORCE RLS (mig 0043): (1) comentar! materializa o comentario PENDENTE com o
  autor INJETADO do ator (sem variante anonima) + emite; (2) a lista PUBLICA da materia SO mostra aprovado
  (pendente/rejeitado nunca vazam); (3) moderar (CAS pendente->aprovado|rejeitado) grava a trilha
  APPEND-ONLY em moderacao_comentario + emite; moderar 2x (ja terminal) e' CAS perdida (nil); (4) denunciar e'
  IDEMPOTENTE por (comentario, denunciante) — 2x mesmo cidadao so' grava 1 linha em denuncia_comentario, e a
  2a tentativa NAO lanca; (5) denunciar um comentario JA terminal ainda registra a denuncia (append-only) mas
  NAO tenta reabrir/mudar o CAS (a flag `denunciado` so' e' setada quando pendente — evita colidir com o
  trigger de estado terminal); (6) a fila de moderacao ordena denunciados primeiro; (7) isolamento de tenant
  (RLS). Constroi o Repo direto (datasource + outbox/bus) p/ inspecionar o outbox."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [next.jdbc :as jdbc]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.outbox :as outbox]
            [oplenario.kernel.tempo :as tempo]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.migracao :as migracao]
            [oplenario.participacao.components.repositorio :as repo-part]
            [oplenario.participacao.controllers :as controllers])
  (:import (java.time Instant)))

(def ^:dynamic *ds* nil)
(def ^:dynamic *repo* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds*   (:ds c)
                *repo* (repo-part/->RepoParticipacaoPg c (outbox/bus))]
        (try (t) (finally (component/stop c)))))))

(def ^:private t0 (Instant/parse "2026-07-03T12:00:00Z"))
(def ^:private relogio (tempo/relogio-fixo t0))

(defn- ator [ente ident] {:ente-id ente :identidade-id ident :papeis #{}})

(defn- eventos-por-tipo [ente tipo]
  (jdbc/execute! *ds*
    ["SELECT tipo, payload::text AS payload FROM shared.outbox WHERE ente_id = ? AND tipo = ? ORDER BY id" ente tipo]))

(defn- comentar! [ente cidadao proposicao-id]
  (controllers/comentar! *repo* (ator ente cidadao) proposicao-id {:corpo "Excelente proposicao, apoio."}))

;; ---------- comentar: comentario PENDENTE + autor INJETADO + evento ----------

(deftest comentar-insere-pendente-com-autor-injetado-e-emite-evento
  (let [ente (random-uuid) cidadao (random-uuid) materia (random-uuid)
        {:keys [id estado]} (comentar! ente cidadao materia)]
    (is (some? id))
    (is (= "pendente" estado) "comentario nasce pendente (aguarda moderacao)")
    (let [com (repo-part/buscar-comentario *repo* ente id)]
      (is (= "pendente" (:estado com)))
      (is (= cidadao (:autor-identidade-id com)) "autor INJETADO do ator, nunca do corpo")
      (is (= materia (:proposicao-id com)))
      (is (false? (:denunciado com))))
    (is (= 1 (count (eventos-por-tipo ente "participacao.comentario.protocolado"))))))

;; ---------- lista publica: SO aprovado (pendente/rejeitado nunca vazam) ----------

(deftest comentarios-da-materia-so-mostra-aprovado
  (let [ente (random-uuid) c1 (random-uuid) c2 (random-uuid) c3 (random-uuid) materia (random-uuid)
        {pendente-id :id}  (comentar! ente c1 materia)
        {aprovado-id :id}  (comentar! ente c2 materia)
        {rejeitado-id :id} (comentar! ente c3 materia)
        servidor (random-uuid)]
    (controllers/moderar-comentario! *repo* relogio (ator ente servidor) aprovado-id {:acao "aprovado"})
    (controllers/moderar-comentario! *repo* relogio (ator ente servidor) rejeitado-id
      {:acao "rejeitado" :motivo-rejeicao "spam"})
    (let [publicos (mapv :id (repo-part/comentarios-da-materia *repo* ente materia))]
      (is (= [aprovado-id] publicos) "so' o aprovado aparece; pendente e rejeitado ficam de fora")
      (is (not (some #{pendente-id} publicos)))
      (is (not (some #{rejeitado-id} publicos))))))

;; ---------- moderar: CAS pendente->aprovado|rejeitado + trilha APPEND-ONLY + evento ----------

(deftest moderar-aprovado-transiciona-e-grava-trilha
  (let [ente (random-uuid) cidadao (random-uuid) servidor (random-uuid) materia (random-uuid)
        {:keys [id]} (comentar! ente cidadao materia)
        r (controllers/moderar-comentario! *repo* relogio (ator ente servidor) id {:acao "aprovado"})]
    (is (= "aprovado" (:estado r)))
    (is (= "aprovado" (:estado (repo-part/buscar-comentario *repo* ente id))))
    (let [trilha (jdbc/execute! *ds*
                   ["SELECT acao, moderado_por FROM participacao.moderacao_comentario WHERE ente_id = ? AND comentario_id = ?"
                    ente id])]
      (is (= 1 (count trilha)))
      (is (= "aprovado" (:moderacao_comentario/acao (first trilha)))))
    (is (= 1 (count (eventos-por-tipo ente "participacao.comentario.moderado"))))))

(deftest moderar-rejeitado-exige-e-grava-motivo
  (let [ente (random-uuid) cidadao (random-uuid) servidor (random-uuid) materia (random-uuid)
        {:keys [id]} (comentar! ente cidadao materia)
        r (controllers/moderar-comentario! *repo* relogio (ator ente servidor) id
            {:acao "rejeitado" :motivo-rejeicao "ofensivo"})]
    (is (= "rejeitado" (:estado r)))
    (let [com (repo-part/buscar-comentario *repo* ente id)]
      (is (= "rejeitado" (:estado com)))
      (is (= "ofensivo" (:motivo-rejeicao com))))))

(deftest moderar-comentario-ja-terminal-e-cas-perdida
  (let [ente (random-uuid) cidadao (random-uuid) servidor (random-uuid) materia (random-uuid)
        {:keys [id]} (comentar! ente cidadao materia)]
    (controllers/moderar-comentario! *repo* relogio (ator ente servidor) id {:acao "aprovado"})
    (is (nil? (repo-part/moderar-comentario! *repo* ente
                {:id id :acao "rejeitado" :motivo-rejeicao "spam"
                 :moderado-por servidor :moderado-em t0}))
        "CAS nao casa mais (ja terminal) -> nil; a borda desambigua p/ 409")))

;; ---------- denunciar: IDEMPOTENTE por (comentario, denunciante) ----------

(deftest denunciar-e-idempotente-mesmo-cidadao-2x
  (let [ente (random-uuid) cidadao (random-uuid) denunciante (random-uuid) materia (random-uuid)
        {:keys [id]} (comentar! ente cidadao materia)]
    (is (some? (controllers/denunciar-comentario! *repo* relogio (ator ente denunciante) id {:motivo "spam"})))
    (is (true? (:denunciado (repo-part/buscar-comentario *repo* ente id))))
    ;; 2a denuncia do MESMO cidadao sobre o MESMO comentario: idempotente, NAO lanca, NAO duplica linha.
    (is (some? (controllers/denunciar-comentario! *repo* relogio (ator ente denunciante) id {:motivo "spam de novo"})))
    (let [linhas (jdbc/execute! *ds*
                   ["SELECT id FROM participacao.denuncia_comentario WHERE ente_id = ? AND comentario_id = ?" ente id])]
      (is (= 1 (count linhas)) "so' 1 linha de denuncia registrada (a 2a tentativa foi no-op idempotente)"))))

(deftest denunciar-comentario-inexistente-e-nil
  (let [ente (random-uuid) denunciante (random-uuid)]
    (is (nil? (controllers/denunciar-comentario! *repo* relogio (ator ente denunciante) (random-uuid) {:motivo "x"}))
        "comentario inexistente no tenant -> nil (borda desambigua p/ 404)")))

(deftest denunciar-comentario-ja-terminal-registra-mas-nao-reabre-o-cas
  (let [ente (random-uuid) cidadao (random-uuid) servidor (random-uuid) denunciante (random-uuid) materia (random-uuid)
        {:keys [id]} (comentar! ente cidadao materia)]
    (controllers/moderar-comentario! *repo* relogio (ator ente servidor) id {:acao "aprovado"})
    (is (some? (controllers/denunciar-comentario! *repo* relogio (ator ente denunciante) id {:motivo "tarde demais"}))
        "denunciar um comentario JA terminal ainda sucede (o ato de denunciar nao depende do estado)")
    (let [com (repo-part/buscar-comentario *repo* ente id)]
      (is (= "aprovado" (:estado com)) "o CAS nao reabre — o comentario segue aprovado")
      ;; a flag `denunciado` so' e' setada quando pendente (evita colidir com o trigger de estado terminal);
      ;; sobre uma linha JA terminal, o registro em denuncia_comentario e' o rastro — a flag fica como estava.
      (is (false? (:denunciado com))))
    (let [linhas (jdbc/execute! *ds*
                   ["SELECT id FROM participacao.denuncia_comentario WHERE ente_id = ? AND comentario_id = ?" ente id])]
      (is (= 1 (count linhas)) "o ato de denunciar foi registrado (append-only), mesmo sobre linha terminal"))))

;; ---------- fila de moderacao: denunciados primeiro ----------

(deftest fila-moderacao-ordena-denunciados-primeiro
  (let [ente (random-uuid) cidadao (random-uuid) servidor (random-uuid) denunciante (random-uuid) materia (random-uuid)
        {sem-denuncia :id} (comentar! ente cidadao materia)
        {com-denuncia :id} (comentar! ente cidadao materia)]
    (controllers/denunciar-comentario! *repo* relogio (ator ente denunciante) com-denuncia {:motivo "spam"})
    (let [fila (mapv :id (repo-part/fila-moderacao *repo* ente))]
      (is (= com-denuncia (first fila)) "denunciado sobe ao topo da fila, mesmo tendo sido criado depois")
      (is (some #{sem-denuncia} fila)))
    (let [servidor-ator (ator ente servidor)
          fila-via-controller (mapv :id (controllers/fila-moderacao *repo* servidor-ator))]
      (is (= com-denuncia (first fila-via-controller))))))

(deftest fila-moderacao-nao-lista-comentario-ja-moderado
  (let [ente (random-uuid) cidadao (random-uuid) servidor (random-uuid) materia (random-uuid)
        {:keys [id]} (comentar! ente cidadao materia)]
    (controllers/moderar-comentario! *repo* relogio (ator ente servidor) id {:acao "aprovado"})
    (is (not (some #{id} (mapv :id (repo-part/fila-moderacao *repo* ente))))
        "comentario ja moderado sai da fila (so' pendente e' candidato)")))

;; ---------- ISOLAMENTO de tenant (RLS) ----------

(deftest tenant-nao-le-comentario-de-outro
  (let [ente-a (random-uuid) ente-b (random-uuid) cidadao (random-uuid) materia (random-uuid)
        {:keys [id]} (comentar! ente-a cidadao materia)]
    (is (nil? (repo-part/buscar-comentario *repo* ente-b id))
        "buscar sob o tenant errado NAO encontra o comentario de outro ente (RLS isola)")
    (tenancy/com-tenant* *ds* ente-b
      (fn [tx]
        (is (empty? (jdbc/execute! tx ["SELECT id FROM participacao.comentario WHERE id = ?" id]))
            "RLS: o comentario de ente-a nao aparece na visao de ente-b nem por SQL direto")))))
