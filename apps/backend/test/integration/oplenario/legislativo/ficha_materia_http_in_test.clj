(ns oplenario.legislativo.ficha-materia-http-in-test
  "Onda B Slice 3 — a borda HTTP da ficha da materia: GET /legislativo/proposicoes/:id/ficha. DB-free (Repo
  FAKE, mesmo racional de proposicao-escrita-http-in-test): prova a silhueta ponta-a-ponta (adapters/in ->
  controller -> repo -> adapters/out -> wire/out), o corpo agregado completo, 404 (inexistente OU de outro
  tenant — o fake simula o que a RLS faria: sem linha p/ um ente diferente do dono da fixture)."
  (:require [clojure.test :refer [deftest is]]
            [io.pedestal.http :as ph]
            [oplenario.cadastros.components.repositorio :as repo-cadastros-comp]
            [io.pedestal.test :as pt]
            [jsonista.core :as json]
            [oplenario.config :as config]
            [oplenario.http :as http]
            [oplenario.identidade.components.repositorio :as repo-id]
            [oplenario.interceptors :as it]
            [oplenario.kernel.components.idp-dev :as idp-dev]
            [oplenario.legislativo.components.repositorio :as repo-leg]
            [oplenario.rotas :as rotas]))

(defn- proposicao-canonica [ente id]
  {:id id :ente-id ente :tipo "projeto_lei" :ano 2026 :sequencial 42
   :urn-lex "urn:lex:br:camara.municipal.fortaleza:projeto.lei:2026;42"
   :ementa "Cria o Programa Municipal de Hortas Comunitarias"
   :autor-tipo "vereador" :autor-texto "Helena Matos" :estado "em_comissoes" :lock-version 0
   :atualizado-em (java.time.Instant/parse "2026-05-21T10:00:00Z")})

(def ^:private ccj-id (random-uuid))

(defn- ficha-canonica [ente id]
  {:proposicao (proposicao-canonica ente id)
   :texto {:texto-inline "## Art. 1o"}
   :tramitacao [{:de-estado "protocolada" :para-estado "em_comissoes" :gatilho "despachar"
                 :contexto {} :ator-id (random-uuid)
                 :ocorrido-em (java.time.Instant/parse "2026-05-20T10:00:00Z")}]
   :apensadas [{:apensada-id (random-uuid) :apensada-em (java.time.Instant/parse "2026-05-20T11:00:00Z")
                :motivo-apensacao "materia conexa"}]
   :emendas [{:id (random-uuid) :numero-local 1 :tipo-emenda "aditiva" :momento-apresentacao "no_prazo"
              :autor-tipo "vereador" :autor-texto "Helena Matos" :estado "apresentada"}]
   :pareceres [{:id (random-uuid) :comissao-id ccj-id :relator-id (random-uuid)
                :voto-relator "favoravel" :estado "com_relator"}
               ;; 2a linha: comissao que o resolver NAO conhece (guard ref orfao, ou de outra Casa) —
               ;; prova que a ausencia de nome sai nil e nao derruba o 200 nem cai no id.
               {:id (random-uuid) :comissao-id (random-uuid) :relator-id nil
                :voto-relator nil :estado "em_elaboracao"}]})

(defn- fake-repo-legislativo
  "So' o metodo exercido (`ficha-completa-da-proposicao`). Simula a RLS: `dono` (ente-id da fixture) e' o
  UNICO tenant que enxerga a linha; qualquer outro ente-id devolve {:proposicao nil ...} — o mesmo shape que
  o Repo real produz p/ id inexistente (nunca vaza a existencia de linha de outro tenant)."
  [dono id]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-leg/RepoLegislativo
    (ficha-completa-da-proposicao [_ ente-id buscado-id]
      (if (and (= ente-id dono) (= buscado-id id))
        (ficha-canonica dono id)
        {:proposicao nil :texto nil :tramitacao [] :apensadas [] :emendas [] :pareceres []}))))

(defn- fake-repo-identidade [papeis]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-id/RepoIdentidade
    (snapshot-ator [_ _ente-id _identidade-id] {:vinculo-ativo {:id (random-uuid) :tipo "servidor"} :papeis papeis})))

(defn- fake-repo-cadastros
  "So' `nomes-de-comissoes`, a metade de `cadastros` do `resolver-comissoes` que o host injeta no
  legislativo (§22.5.3) — e' o que da' nome a comissao de cada parecer da ficha (defeito #11 do ledger de
  prontidao, em que a aba mostrava um UUID por linha). Conhece SO' a CCJ: o 2o parecer da fixture aponta
  uma comissao desconhecida de proposito."
  []
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-cadastros-comp/RepoCadastros
    (nomes-de-comissoes [_ _ente-id ids]
      (into {} (keep (fn [i] (when (= i ccj-id) [i "Comissão de Constituição e Justiça"]))) ids))))

(defn- service-fn [papeis repo-l]
  (-> (http/servico (config/carregar)
                    (rotas/montar {:idp (idp-dev/idp-dev)
                                   :repo-identidade (fake-repo-identidade papeis)
                                   :repo-legislativo repo-l
                                   :repo-cadastros (fake-repo-cadastros)})
                    it/globais)
      ph/create-server ::ph/service-fn))

(defn- token [ente-id ident-id] (json/write-value-as-string {:sub "u" :ente-id (str ente-id) :identidade-id (str ident-id)}))
(defn- com-bearer [tok] {"authorization" (str "Bearer " tok)})
(defn- ler-json [r] (json/read-value (:body r) json/keyword-keys-object-mapper))

(deftest ficha-materia-200-corpo-agregado-completo
  (let [ente (random-uuid) id (random-uuid)
        r (pt/response-for (service-fn #{"secretario"} (fake-repo-legislativo ente id))
                           :get (str "/legislativo/proposicoes/" id "/ficha")
                           :headers (com-bearer (token ente (random-uuid))))
        body (ler-json r)]
    (is (= 200 (:status r)))
    (is (= "projeto_lei" (:tipo (:proposicao body))))
    (is (string? (:id (:proposicao body))))
    (is (= "## Art. 1o" (:texto (:proposicao body))))
    (is (= 1 (count (:tramitacao body))))
    (is (= "protocolada" (:de-estado (first (:tramitacao body)))))
    (is (not (contains? (first (:tramitacao body)) :contexto)))
    (is (= 1 (count (:apensadas body))))
    (is (= 1 (count (:emendas body))))
    (is (= "aditiva" (:tipo-emenda (first (:emendas body)))))
    (is (= 2 (count (:pareceres body))))
    (is (= "favoravel" (:voto-relator (first (:pareceres body)))))
    ;; #11: a ficha nomeia a comissao de cada parecer, atravessando a fronteira de modulo pelo resolver
    ;; do host — e a linha cuja comissao o resolver nao conhece sai com nome nil, NUNCA com o id.
    (is (= ["Comissão de Constituição e Justiça" nil] (mapv :comissao-nome (:pareceres body))))
    (is (not-any? #(= (:comissao-nome %) (:comissao-id %)) (:pareceres body)))))

(deftest ficha-materia-inexistente-404
  (let [r (pt/response-for (service-fn #{"secretario"} (fake-repo-legislativo (random-uuid) (random-uuid)))
                           :get (str "/legislativo/proposicoes/" (random-uuid) "/ficha")
                           :headers (com-bearer (token (random-uuid) (random-uuid))))]
    (is (= 404 (:status r)))))

(deftest ficha-materia-de-outro-tenant-404-nunca-vaza
  (let [dono (random-uuid) id (random-uuid) outro-ente (random-uuid)
        r (pt/response-for (service-fn #{"secretario"} (fake-repo-legislativo dono id))
                           :get (str "/legislativo/proposicoes/" id "/ficha")
                           :headers (com-bearer (token outro-ente (random-uuid))))]
    (is (= 404 (:status r)) "proposicao de outro ente-id nunca vaza -> 404, nao 200")))

(deftest ficha-materia-sem-papel-403
  (let [ente (random-uuid) id (random-uuid)
        r (pt/response-for (service-fn #{"vereador"} (fake-repo-legislativo ente id))
                           :get (str "/legislativo/proposicoes/" id "/ficha")
                           :headers (com-bearer (token ente (random-uuid))))]
    (is (= 403 (:status r)))))

(deftest ficha-materia-sem-token-401
  (let [id (random-uuid)
        r (pt/response-for (service-fn #{"secretario"} (fake-repo-legislativo (random-uuid) id))
                           :get (str "/legislativo/proposicoes/" id "/ficha"))]
    (is (= 401 (:status r)))))
