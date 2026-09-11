(ns oplenario.legislativo.meu-parecer-http-in-test
  "Onda C4 (feature 7.3) — a borda HTTP /meu/pareceres: GET (leitura p/ assinar) + POST .../emissao
  (assinar=emitir). DB-free (Repo FAKE, mesmo racional de meu-painel-http-in-test): a logica de
  posse/assinatura ja tem cobertura de integracao real em parecer-repo-test/parecer-controllers-test. Foco
  AQUI e' a borda: gate de papel 'vereador' + o contrato 404-sem-distinguir-motivo quando o parecer nao e'
  do vereador ATOR."
  (:require [clojure.test :refer [deftest is]]
            [io.pedestal.http :as ph]
            [io.pedestal.test :as pt]
            [jsonista.core :as json]
            [oplenario.cadastros.components.repositorio :as repo-cadastros-comp]
            [oplenario.config :as config]
            [oplenario.http :as http]
            [oplenario.identidade.components.repositorio :as repo-id]
            [oplenario.interceptors :as it]
            [oplenario.kernel.components.idp-dev :as idp-dev]
            [oplenario.legislativo.components.repositorio :as repo-leg]
            [oplenario.rotas :as rotas])
  (:import (java.time Instant)))

(defn- fake-repo-legislativo [{:keys [relator? parecer emitido lanca]}]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-leg/RepoLegislativo
    (relator-do-parecer? [_ _ente-id _vereador-id _parecer-id] relator?)
    (buscar-parecer-para-editor [_ _ente-id _id] parecer)
    (emitir-parecer! [_ _ente-id _registro m]
      (when lanca (throw lanca))
      (when emitido (reset! emitido m))
      (:parecer parecer))))

(defn- fake-repo-identidade
  ([] (fake-repo-identidade #{"vereador"}))
  ([papeis]
   #_{:clj-kondo/ignore [:missing-protocol-method]}
   (reify repo-id/RepoIdentidade
     (snapshot-ator [_ _ente-id _identidade-id]
       {:vinculo-ativo {:id (random-uuid) :tipo "vereador"} :papeis papeis}))))

(def ^:private ccj-id (random-uuid))

(defn- fake-repo-cadastros
  "Os DOIS resolvers que o host injeta no legislativo (§22.5.3): `vereador-por-identidade` (borda /meu) e
  `nomes-de-comissoes` (o nome da comissao do parecer — defeito #11 do ledger de prontidao, em que o
  editor do vereador mostrava o UUID)."
  [resolver]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-cadastros-comp/RepoCadastros
    (vereador-por-identidade [_ ente-id identidade-id]
      (when-let [v (resolver ente-id identidade-id)] {:id v}))
    (nomes-de-comissoes [_ _ente-id ids]
      (into {} (keep (fn [i] (when (= i ccj-id) [i "Comissão de Educação e Cultura"]))) ids))))

(defn- service-fn
  ([repo-l resolver-vereador] (service-fn repo-l resolver-vereador #{"vereador"}))
  ([repo-l resolver-vereador papeis]
  (-> (http/servico (config/carregar)
                    (rotas/montar {:idp (idp-dev/idp-dev)
                                   :repo-identidade (fake-repo-identidade papeis)
                                   :repo-legislativo repo-l
                                   :repo-cadastros (fake-repo-cadastros resolver-vereador)
                                   :registro :registro-fake
                                   :relogio (constantly (Instant/parse "2026-07-11T12:00:00Z"))})
                    it/globais)
      ph/create-server ::ph/service-fn)))

(defn- token [ente-id ident-id] (json/write-value-as-string {:sub "u" :ente-id (str ente-id) :identidade-id (str ident-id)}))
(defn- com-bearer [tok] {"authorization" (str "Bearer " tok) "Content-Type" "application/json"})
(defn- ler-json [r] (json/read-value (:body r) json/keyword-keys-object-mapper))

(deftest get-meu-parecer-404-quando-nao-e-o-relator
  (let [ente (random-uuid) identidade (random-uuid) vereador (random-uuid) pid (random-uuid)
        svc (service-fn (fake-repo-legislativo {:relator? false :parecer nil}) (fn [_ _] vereador))
        r (pt/response-for svc :get (str "/meu/pareceres/" pid) :headers (com-bearer (token ente identidade)))]
    (is (= 404 (:status r)))))

(deftest get-meu-parecer-200-quando-e-o-relator
  (let [ente (random-uuid) identidade (random-uuid) vereador (random-uuid) pid (random-uuid)
        parecer {:parecer {:id pid :objeto-tipo "proposicao" :objeto-id (random-uuid) :comissao-id ccj-id
                           :estado "com_relator" :template-id (random-uuid) :lock-version 0
                           :criado-em (Instant/now)}
                 :objeto nil :texto-rascunho {:texto-inline "## Relatório\n\nX" :numero-versao 1} :texto-vigente nil}
        svc (service-fn (fake-repo-legislativo {:relator? true :parecer parecer}) (fn [_ _] vereador))
        r (pt/response-for svc :get (str "/meu/pareceres/" pid) :headers (com-bearer (token ente identidade)))
        body (ler-json r)]
    (is (= 200 (:status r)))
    (is (= (str pid) (:id body)))
    ;; #11: a borda /meu tambem nomeia a comissao — o vereador-relator via o UUID igual ao servidor.
    (is (= "Comissão de Educação e Cultura" (:comissao-nome body)))))

(deftest post-meu-emissao-404-quando-nao-e-o-relator
  (let [ente (random-uuid) identidade (random-uuid) vereador (random-uuid) pid (random-uuid)
        svc (service-fn (fake-repo-legislativo {:relator? false :parecer nil}) (fn [_ _] vereador))
        r (pt/response-for svc :post (str "/meu/pareceres/" pid "/emissao")
            :headers (com-bearer (token ente identidade))
            :body (json/write-value-as-string {:voto-relator "favoravel" :lock-version 0}))]
    (is (= 404 (:status r)))))

(deftest post-meu-emissao-200-e-assina-quando-e-o-relator
  (let [ente (random-uuid) identidade (random-uuid) vereador (random-uuid) pid (random-uuid)
        emitido (atom nil)
        parecer {:parecer {:id pid :objeto-tipo "proposicao" :objeto-id (random-uuid) :comissao-id (random-uuid)
                           :estado "com_relator" :template-id (random-uuid) :lock-version 0
                           :criado-em (Instant/now)}
                 :objeto nil :texto-rascunho {:texto-inline "## Relatório\n\nX" :numero-versao 1} :texto-vigente nil}
        svc (service-fn (fake-repo-legislativo {:relator? true :parecer parecer :emitido emitido}) (fn [_ _] vereador))
        r (pt/response-for svc :post (str "/meu/pareceres/" pid "/emissao")
            :headers (com-bearer (token ente identidade))
            :body (json/write-value-as-string {:voto-relator "favoravel" :lock-version 0}))]
    (is (= 200 (:status r)))
    (is (some? (:assinador @emitido)) "o handler construiu e repassou o assinador-stub")))

;; ---------------------------------------------------------------------------------------------------
;; ADR-0004, paridade de handler. A rota IRMA (POST /legislativo/proposicoes/:id/tramitacao) ja' traduz
;; uma excecao do AVALIADOR da DSL (`{:erro :runtime}` / `{:erro :sintaxe}`) num 500 NOMEADO — quem opera
;; recebe 'o rito desta Casa nao pode ser avaliado', que aponta p/ quem administra os templates. As duas
;; rotas de EMISSAO DE PARECER nao tinham esse catch: a excecao subia crua ate' o interceptor global e
;; virava `{"erro":"erro interno"}`, indistinguivel de um bug do servidor.
;;
;; Este caminho ficou MAIS alcancavel com o ADR-0004, e e' por isso que o conserto entra nesta frente e
;; nao numa futura: ate' 11/09/2026 um rito legado com guarda `alegado.x` LIA o corpo e passava em
;; silencio; agora ele LANCA sempre. A frente que fechou o vazamento aumentou a frequencia da resposta
;; opaca — entrega-la assim seria piorar a rota que acabamos de esquentar.
;; ---------------------------------------------------------------------------------------------------

(def ^:private parecer-pronto-para-emitir
  {:parecer {:id (random-uuid) :objeto-tipo "proposicao" :objeto-id (random-uuid) :comissao-id (random-uuid)
             :estado "com_relator" :template-id (random-uuid) :lock-version 0 :criado-em (Instant/now)}
   :objeto nil :texto-rascunho {:texto-inline "## Relatorio\n\nX" :numero-versao 1} :texto-vigente nil})

(deftest post-meu-emissao-guard-inavaliavel-vira-500-NOMEADO-nao-erro-interno
  (doseq [[rotulo ex] [["runtime (fato sem fn, identificador sem valor, tipo nao-booleano)"
                        (ex-info "identificador sem valor em runtime" {:erro :runtime})]
                       ["sintaxe (rito gravado por import/SQL direto, fora do gate de cadastro)"
                        (ex-info "guarda nao parseia" {:erro :sintaxe})]]]
    (let [ente (random-uuid) identidade (random-uuid) vereador (random-uuid)
          pid (:id (:parecer parecer-pronto-para-emitir))
          svc (service-fn (fake-repo-legislativo {:relator? true :parecer parecer-pronto-para-emitir :lanca ex})
                          (fn [_ _] vereador))
          r (pt/response-for svc :post (str "/meu/pareceres/" pid "/emissao")
              :headers (com-bearer (token ente identidade))
              :body (json/write-value-as-string {:voto-relator "favoravel" :lock-version 0}))
          corpo (ler-json r)]
      (is (= 500 (:status r)) (str rotulo " — segue 500: e' incidente de CONFIG da Casa, nao recusa de dominio"))
      (is (re-find #"rito desta Casa" (str (:erro corpo)))
          (str rotulo " — a resposta NOMEIA a causa, em vez de 'erro interno'"))
      (is (not= "erro interno" (:erro corpo))
          (str rotulo " — o 500 opaco do interceptor global NAO e' mais o que chega ao cliente"))
      (is (= "emitir" (:gatilho corpo))
          (str rotulo " — e diz QUAL ato ficou inavaliavel, igual a rota irma de tramitacao")))))

(deftest post-emissao-DESKTOP-guard-inavaliavel-vira-500-NOMEADO
  ;; O espelho da rota de desktop: as DUAS bordas de emissao precisam do catch. Testar so' uma provaria o
  ;; helper, nunca que a outra foi fiada.
  (let [ente (random-uuid) identidade (random-uuid)
        pid (:id (:parecer parecer-pronto-para-emitir))
        svc (service-fn (fake-repo-legislativo {:relator? true :parecer parecer-pronto-para-emitir
                                                :lanca (ex-info "fato sem fn no registry" {:erro :runtime})})
                        (fn [_ _] (random-uuid))
                        ;; a rota de DESKTOP e' gateada por `secretario` (it/exige-papel, in.clj:668) —
                        ;; com o papel de vereador o teste pararia em 403 e nunca chegaria ao handler.
                        #{"secretario"})
        r (pt/response-for svc :post (str "/legislativo/pareceres/" pid "/emissao")
            :headers (com-bearer (token ente identidade))
            :body (json/write-value-as-string {:voto-relator "favoravel" :lock-version 0}))
        corpo (ler-json r)]
    (is (= 500 (:status r)))
    (is (re-find #"rito desta Casa" (str (:erro corpo))) "a borda de desktop NOMEIA a causa igual a /meu")
    (is (= "emitir" (:gatilho corpo)))))
