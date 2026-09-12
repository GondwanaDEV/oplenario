(ns oplenario.transparencia.artefato-publicacao-test
  "INTEGRACAO (PG real) — F6c Slice 4b: a PROJECAO do artefato de publicacao oficial (§16.5). `legislativo`
  EMITE `artefato.publicacao.gerado` no shared.outbox (na tx do INSERT — provado em
  gerar-artefato-publicacao-test); o relay DRENA e despacha ao consumer do portal
  (`transparencia.diplomat.consumers`), que PROJETA em `transparencia.artefato_publicacao` (mig 0047) — sem
  import/JOIN cross-modulo (§22.10). Este teste emite o evento DIRETO no outbox (o payload casa o contrato
  GeradoPayload; a emissao real esta' provada no legislativo), drena, e le' o ponteiro pela leitura publica.
  Prova tambem RLS (isolamento cross-tenant) e a idempotencia (ON CONFLICT DO NOTHING) do redrive."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.eventos :as eventos]
            [oplenario.kernel.outbox :as outbox]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.migracao :as migracao]
            [oplenario.transparencia.components.repositorio :as transparencia-repo]
            [oplenario.transparencia.diplomat.consumers :as consumers]))

(def ^:dynamic *ds* nil)
(def ^:dynamic *repo* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds*   (:ds c)
                *repo* (transparencia-repo/->RepoTransparenciaPg c)]
        (try (t) (finally (component/stop c)))))))

(defn- drenar! []
  (outbox/drenar! *ds* (consumers/registrar {})))

(defn- payload-gerado
  "Payload de `artefato.publicacao.gerado` como o producer do legislativo o constroi (uuid como STRING no
  jsonb; criado-em ISO). `over` sobrepoe campos (norma-id/artefato-id/versao)."
  [over]
  (merge {:norma-id (str (random-uuid)) :artefato-id (str (random-uuid)) :versao 1
          :hash "sha256:deadbeef" :objeto-store-ref "publicacoes/ref.bin"
          :content-type "text/plain; charset=utf-8" :assinatura-algoritmo "STUB-ICP-v0"
          :assinado? false :criado-em "2026-07-04T12:00:00Z"}
         over))

(defn- emitir! [ente payload]
  (tenancy/com-tenant* *ds* ente
    (fn [tx] (eventos/emitir! (outbox/bus) tx (eventos/evento "artefato.publicacao.gerado" ente payload)))))

;; ---------- emitir -> drenar -> projeta -> le' o ponteiro mais recente ----------

(deftest gerado-projeta-o-artefato-no-portal
  (let [ente (random-uuid) nid (random-uuid) aid (random-uuid)]
    (emitir! ente (payload-gerado {:norma-id (str nid) :artefato-id (str aid) :versao 1}))
    (drenar!)
    (let [ptr (transparencia-repo/artefato-mais-recente-da-norma *repo* ente nid)]
      (is (some? ptr) "o artefato foi projetado no read-model do portal")
      (is (= aid (:artefato-id ptr)))
      (is (= 1 (:versao ptr)))
      (is (= "publicacoes/ref.bin" (:objeto-store-ref ptr)) "carrega o ponteiro p/ o binario")
      (is (= "text/plain; charset=utf-8" (:content-type ptr)))
      (is (false? (:assinado ptr)) "assinado false (stub)")
      (is (some? (:criado-em ptr)) "criado-em parseado (Instant) da string ISO do evento"))
    (is (nil? (transparencia-repo/artefato-mais-recente-da-norma *repo* (random-uuid) nid))
        "RLS: outro ente nao ve o artefato projetado")))

(deftest mais-recente-devolve-a-maior-versao
  (let [ente (random-uuid) nid (random-uuid)]
    (emitir! ente (payload-gerado {:norma-id (str nid) :artefato-id (str (random-uuid)) :versao 1}))
    (drenar!)
    (emitir! ente (payload-gerado {:norma-id (str nid) :artefato-id (str (random-uuid)) :versao 2
                                   :objeto-store-ref "publicacoes/v2.bin"}))
    (drenar!)
    (let [ptr (transparencia-repo/artefato-mais-recente-da-norma *repo* ente nid)]
      (is (= 2 (:versao ptr)) "a rota serve a versao mais recente")
      (is (= "publicacoes/v2.bin" (:objeto-store-ref ptr))))))

(deftest fonte-corrompida-mesma-versao-artefato-distinto-e-no-op
  ;; DEFESA-EM-PROFUNDIDADE (idx UNIQUE ente_id,norma_id,versao, mig 0047 + ON CONFLICT DO NOTHING SEM alvo):
  ;; a fonte (legislativo) ja' garante versao unica por norma (UNIQUE + MAX+1 atomico). SE ela corrompesse e
  ;; emitisse DOIS artefatos DISTINTOS com a mesma (norma, versao), a projecao NAO pode lancar 23505 dentro da
  ;; tx do relay COMPARTILHADO (poison/head-of-line de todos os modulos). O 2o no-op pelo idx UNIQUE; o 1o fica.
  (let [ente (random-uuid) nid (random-uuid) aid1 (random-uuid) aid2 (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (is (some? (transparencia-repo/projetar-evento! tx
                     {:tipo "artefato.publicacao.gerado" :ente-id ente
                      :payload (payload-gerado {:norma-id (str nid) :artefato-id (str aid1) :versao 1})}))
            "1o artefato insere")
        (is (nil? (transparencia-repo/projetar-evento! tx
                    {:tipo "artefato.publicacao.gerado" :ente-id ente
                     :payload (payload-gerado {:norma-id (str nid) :artefato-id (str aid2) :versao 1})}))
            "mesma (norma, versao) com artefato distinto = no-op pelo idx UNIQUE, NUNCA 23505 no relay")))
    (is (= aid1 (:artefato-id (transparencia-repo/artefato-mais-recente-da-norma *repo* ente nid)))
        "o primeiro permanece (first-write-wins no slot de versao)")))

(deftest todo-tipo-consumido-tem-branch-de-projecao
  ;; DRIFT-GUARD (review clojure/architect MINOR): cada tipo em `tipos-consumidos` (o que o bus ENTREGA)
  ;; DEVE ter um branch no `case` de despachar! (o que a projecao TRATA). Se um tipo novo for adicionado
  ;; ao registro do bus sem o branch, o `case` (sem default) lanca "No matching clause" DENTRO da tx do relay
  ;; COMPARTILHADO -> rollback + redrive eterno do mesmo evento = head-of-line block de TODOS os modulos. Este
  ;; teste prova que os dois lados nao driftaram: para cada tipo, despachar! com payload vazio falha por
  ;; QUALQUER motivo MENOS "No matching clause" (i.e., entrou num branch). Espelha o guard de tempo_real/projetor.
  ;;
  ;; `despachar!` (NAO `projetar-evento!`) DE PROPOSITO — frente 'relay-tolerante': `projetar-evento!` agora
  ;; TOLERA qualquer excecao de forma-de-dado (`payload-malformado?`, que inclui IllegalArgumentException, a
  ;; classe de "No matching clause"), entao testar por ele mascararia o proprio drift que este guard existe
  ;; p/ pegar. `despachar!` e' a fn SEM tolerancia — MESMO padrao de
  ;; `paineis.components.repositorio/despachar!` (o precedente que resolveu isto antes, e que ja' apontava
  ;; este exato caminho na docstring de `projetar-evento!` de la').
  (let [ente (random-uuid)]
    (doseq [tipo consumers/tipos-consumidos]
      (tenancy/com-tenant* *ds* ente
        (fn [tx]
          (try
            (transparencia-repo/despachar! tx ente tipo {})
            (catch Throwable e
              (is (not (re-find #"No matching clause" (str (ex-message e))))
                  (str "tipo consumido sem branch de projecao (drift bus<->case): " tipo)))))))))

(deftest projecao-idempotente-no-redrive
  ;; o relay COMPARTILHADO pode re-entregar (at-least-once) — o mesmo artefato-id projetado 2x nao pode lancar
  ;; (envenenaria o bus de todos os modulos). ON CONFLICT (ente_id, artefato_id) DO NOTHING.
  (let [ente (random-uuid) nid (random-uuid) aid (random-uuid)
        pl   (payload-gerado {:norma-id (str nid) :artefato-id (str aid) :versao 1})]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (is (some? (transparencia-repo/projetar-evento! tx
                     {:tipo "artefato.publicacao.gerado" :ente-id ente :payload pl}))
            "1a projecao insere")
        (is (nil? (transparencia-repo/projetar-evento! tx
                    {:tipo "artefato.publicacao.gerado" :ente-id ente :payload pl}))
            "2a projecao (mesmo artefato-id) = no-op (ON CONFLICT DO NOTHING), NUNCA lanca")))
    (is (= aid (:artefato-id (transparencia-repo/artefato-mais-recente-da-norma *repo* ente nid)))
        "uma unica linha permanece")))
