(ns oplenario.compliance.gerar-remessa-test
  "INTEGRACAO (PG + MinIO real) — F5.3b: a ORQUESTRACAO do Repo `gerar-remessa!` (§22.7.8): resolve as
  fontes (contexto + relacoes escalares + read-ports em lote) -> renderiza (puro) -> serializa (port) ->
  hash -> guarda o binario no objeto_store -> insere `remessa_gerada` rascunho VERSIONADO (versao MAX+1
  ATOMICA — fecha o carry TOCTOU F5.3a-1). + as transicoes do Repo (validar/submeter/registrar-resposta)
  sobre o CAS guardado-por-grafo do db/. As fontes/serializador/objeto_store entram POR CHAMADA (precedente
  do motor-biblioteca em avaliar-obrigacao!); aqui injeto fixtures + o objeto_store real do sistema. Layout
  fisico do SIM = [GAP] (descritor/serializador ilustrativos)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [clojure.string :as str]
            [com.stuartsierra.component :as component]
            [malli.core :as m]
            [oplenario.compliance.components.fontes :as fontes]
            [oplenario.compliance.components.repositorio :as repo-compliance]
            [oplenario.compliance.components.serializador-remessa :as ser]
            [oplenario.compliance.gerador-remessa :as ger]
            [oplenario.compliance.models.remessa :as mod-rem]
            [oplenario.config :as config]
            [oplenario.kernel.components.objeto-store :as os]
            [oplenario.migracao :as migracao]
            [oplenario.sistema :as sistema]))

(def ^:dynamic *sys* nil)

(use-fixtures :once
  (fn [t]
    (let [s (component/start (sistema/novo-sistema (config/carregar)))]
      (migracao/migrar! (:ds (:datasource s)))
      (binding [*sys* s] (try (t) (finally (component/stop s)))))))

(defn- resolver-relacao-ok [_tx nome]
  (get {"nome_ente" "Camara Municipal de Fortaleza"} nome))

(defn- m-base [_ente]
  {:descritor ger/descritor-sim-fixture
   :template-chave "remessa_mensal_sim" :sistema "SIM" :competencia "2099-07"
   :contexto {"competencia" "2099-07" "sistema" "SIM"}
   :resolver-relacao resolver-relacao-ok
   :fontes (fontes/fontes-fixture {"despesas" [{"data" "2099-07-05" "valor" "1000,00"}]})
   :serializador (ser/serializador-sim)
   :objeto-store (:objeto-store *sys*)
   :registry-versao-ref "registry-v1@2026-06-20"})

;; ---------- gerar! materializa rascunho versionado + grava o binario no objeto_store ----------

(deftest gerar-materializa-rascunho-e-binario
  (let [repo (:repo-compliance *sys*) ente (random-uuid)
        r (repo-compliance/gerar-remessa! repo ente (m-base ente))]
    (is (= "rascunho" (:estado r)) "nasce rascunho")
    (is (= 1 (:versao r)) "primeira versao")
    (is (str/starts-with? (:hash r) "sha256:") "carimba o hash do binario")
    (is (m/validate mod-rem/Remessa r) "bate o model Remessa")
    (let [conteudo (os/obter (:objeto-store *sys*) (:objeto-store-ref r))]
      (is (some? conteudo) "o binario esta no objeto_store sob o ref carimbado")
      (is (str/includes? (String. ^bytes conteudo "UTF-8") "Camara Municipal de Fortaleza")
          "o binario carrega a relacao escalar resolvida")
      (is (str/includes? (String. ^bytes conteudo "UTF-8") "1000,00") "e o registro do lote"))))

;; ---------- re-emissao = NOVA versao (versionamento ATOMICO; fecha o TOCTOU) ----------

(deftest re-emissao-incrementa-versao
  (let [repo (:repo-compliance *sys*) ente (random-uuid)]
    (is (= 1 (:versao (repo-compliance/gerar-remessa! repo ente (m-base ente)))) "1a geracao -> v1")
    (is (= 2 (:versao (repo-compliance/gerar-remessa! repo ente (m-base ente)))) "2a geracao -> v2 (MAX+1 atomico)")))

;; ---------- fail-closed: campo declarado nao resolvido aborta a geracao (sem linha espuria) ----------

(deftest campo-nao-resolvido-aborta
  (let [repo (:repo-compliance *sys*) ente (random-uuid)
        m (assoc (m-base ente) :resolver-relacao (fn [_ _] nil))]   ; nome_ente -> nil
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"campo de remessa nao resolvido"
          (repo-compliance/gerar-remessa! repo ente m))
        "renderizar fail-closed -> a geracao aborta antes de persistir")
    (is (empty? (repo-compliance/listar-remessas repo ente "remessa_mensal_sim" "2099-07"))
        "nenhuma remessa foi materializada (a falha veio antes do insert)")))

;; ---------- transicoes do Repo: rascunho -> validada -> submetida -> aceita ----------

(deftest transicoes-do-repo-seguem-o-ciclo
  (let [repo (:repo-compliance *sys*) ente (random-uuid)
        r (repo-compliance/gerar-remessa! repo ente (m-base ente))
        id (:id r)]
    (is (= "validada" (:estado (repo-compliance/validar-remessa! repo ente id))) "rascunho->validada")
    (let [sub (repo-compliance/submeter-remessa! repo ente id)]
      (is (= "submetida" (:estado sub)) "validada->submetida")
      (is (some? (:submetida-em sub)) "carimba submetida_em"))
    (let [ac (repo-compliance/registrar-resposta-remessa! repo ente id "aceita")]
      (is (= "aceita" (:estado ac)) "submetida->aceita")
      (is (some? (:resposta-em ac)) "carimba resposta_em"))))

;; ---------- registrar-resposta so aceita 'aceita' | 'rejeitada' (guarda de dominio) ----------

(deftest registrar-resposta-valida-o-estado-terminal
  (let [repo (:repo-compliance *sys*) ente (random-uuid)
        r (repo-compliance/gerar-remessa! repo ente (m-base ente))
        id (:id r)]
    (repo-compliance/validar-remessa! repo ente id)
    (repo-compliance/submeter-remessa! repo ente id)
    (is (thrown? clojure.lang.ExceptionInfo
          (repo-compliance/registrar-resposta-remessa! repo ente id "submetida"))
        "resposta != aceita|rejeitada -> LANCA (nao e' resposta do TCE)")))
