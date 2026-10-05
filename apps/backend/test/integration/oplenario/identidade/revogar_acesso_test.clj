(ns oplenario.identidade.revogar-acesso-test
  "INTEGRACAO (PG real + borda HTTP): ADR-0005, adendo \"Revogar acesso\". O `admin_ente` concedia acesso e nao tinha como
  tirar. Prova o ato (quem, quando, por que, historico preservado), o efeito na PROXIMA chamada da pessoa (o ator e'
  recalculado a cada requisicao — sessao viva nao guarda papel), a credencial delegada do agente, o isolamento por
  Casa e a reconcessao."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [com.stuartsierra.component :as component]
            [io.pedestal.http :as ph]
            [io.pedestal.test :as pt]
            [jsonista.core :as json]
            [next.jdbc :as jdbc]
            [oplenario.config :as config]
            [oplenario.http :as http]
            [oplenario.identidade.autenticacao :as auten]
            [oplenario.identidade.components.repositorio :as repo]
            [oplenario.identidade.db.identidade :as id]
            [oplenario.identidade.diplomat.http.in :as identidade-in]
            [oplenario.interceptors :as it]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.components.idp :as idp]
            [oplenario.kernel.components.idp-dev :as idp-dev]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.migracao :as migracao]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.time Instant)))

(def ^:dynamic *ds* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c)] (try (t) (finally (component/stop c)))))))

(defn- repo-id [] (assoc (repo/repositorio) :datasource {:ds *ds*}))

(defn- dv [ds] (let [r (mod (reduce + (map * ds (range (inc (count ds)) 1 -1))) 11)] (if (< r 2) 0 (- 11 r))))
(defn- cpf-valido [] (let [b (vec (repeatedly 9 #(rand-int 10))) d1 (dv b)] (apply str (concat b [d1 (dv (conj b d1))]))))

(defn- identidade! [nome] (id/inserir! *ds* {:id (random-uuid) :cpf (cpf-valido) :nome nome}))

(defn- conceder!
  "Concede o acesso pelo mesmo caminho da tela (vinculo + papeis numa tx)."
  [ente iid tipo papeis]
  (repo/conceder-acesso! (repo-id) ente {:id (random-uuid) :ente-id ente :identidade-id iid :tipo tipo :estado "ativo"}
                         papeis))

(defn- admin! [ente]
  (let [iid (identidade! "Administradora")]
    (conceder! ente iid "admin_ente" ["admin_ente"])
    iid))

(defn- linhas-de-papel [ente iid]
  (tenancy/com-tenant* *ds* ente
    (fn [tx] (jdbc/execute! tx ["SELECT papel, revogado_em, revogado_por, motivo_revogacao
                                   FROM identidade.usuario_papel WHERE ente_id = ? AND identidade_id = ?
                                  ORDER BY criado_em, id" ente iid]))))

(defn- revogar! [ente iid papel por motivo]
  (repo/revogar-acesso! (repo-id) ente {:identidade-id iid :papel papel :por por :motivo motivo}))

;; ---- o ato ----

(deftest revogar-fecha-a-linha-e-guarda-quem-quando-e-por-que
  (let [ente (random-uuid) adm (admin! ente) iid (identidade! "Rui Vereador")]
    (conceder! ente iid "vereador" ["vereador"])
    (is (= #{"vereador"} (repo/papeis-de (repo-id) ente iid)))
    (let [r (revogar! ente iid "vereador" adm "Mandato encerrado em 31/12")]
      (is (true? (:revogado? r))))
    (is (= #{} (repo/papeis-de (repo-id) ente iid)) "o papel revogado nao vale mais")
    (let [[linha :as linhas] (linhas-de-papel ente iid)]
      (is (= 1 (count linhas)) "a linha NAO foi apagada: e' historico")
      (is (some? (:usuario_papel/revogado_em linha)) "quando")
      (is (= adm (:usuario_papel/revogado_por linha)) "quem")
      (is (= "Mandato encerrado em 31/12" (:usuario_papel/motivo_revogacao linha)) "por que"))))

(deftest revogar-sem-motivo-e-recusado-no-banco-tambem
  (let [ente (random-uuid) adm (admin! ente) iid (identidade! "Ana")]
    (conceder! ente iid "vereador" ["vereador"])
    (is (thrown? Exception (revogar! ente iid "vereador" adm "   ")) "a rede do banco: motivo em branco nao grava")
    (is (= #{"vereador"} (repo/papeis-de (repo-id) ente iid)) "e a tx deu rollback: o papel segue ativo")))

(deftest revogar-o-que-nao-esta-ativo-devolve-falso
  (let [ente (random-uuid) adm (admin! ente) iid (identidade! "Bia")]
    (is (false? (:revogado? (revogar! ente iid "vereador" adm "nunca teve"))) "nao tinha o papel")
    (conceder! ente iid "vereador" ["vereador"])
    (is (true? (:revogado? (revogar! ente iid "vereador" adm "saiu"))))
    (is (false? (:revogado? (revogar! ente iid "vereador" adm "de novo"))) "ja' revogado: nada a revogar")
    (is (= 1 (count (linhas-de-papel ente iid))))))

(deftest linha-revogada-nao-se-reescreve
  (let [ente (random-uuid) adm (admin! ente) iid (identidade! "Caio")]
    (conceder! ente iid "vereador" ["vereador"])
    (revogar! ente iid "vereador" adm "motivo original")
    (is (thrown? Exception
                 (tenancy/com-tenant* *ds* ente
                   (fn [tx] (jdbc/execute! tx ["UPDATE identidade.usuario_papel SET motivo_revogacao = 'outro'
                                                 WHERE ente_id = ? AND identidade_id = ?" ente iid]))))
        "o historico nao e' reescrito")
    (is (thrown? Exception
                 (tenancy/com-tenant* *ds* ente
                   (fn [tx] (jdbc/execute! tx ["UPDATE identidade.usuario_papel SET revogado_em = NULL, revogado_por = NULL,
                                                       motivo_revogacao = NULL
                                                 WHERE ente_id = ? AND identidade_id = ?" ente iid]))))
        "e a linha revogada nao se reabre por baixo do pano")))

;; ---- efeito imediato ----

(deftest o-ultimo-papel-encerra-o-vinculo-e-a-sessao-cai-na-proxima-chamada
  (let [ente (random-uuid) adm (admin! ente) iid (identidade! "Duda")]
    (conceder! ente iid "vereador" ["vereador"])
    (is (some? (auten/resolver-sessao (repo-id) {:identidade-id iid :ente-id ente})) "antes: tem sessao")
    (let [r (revogar! ente iid "vereador" adm "Renunciou")]
      (is (= 1 (:vinculos-encerrados r))))
    (is (nil? (auten/resolver-sessao (repo-id) {:identidade-id iid :ente-id ente}))
        "depois: sem vinculo ativo, a mesma sessao nao resolve ator algum (fail-closed)")
    (is (= ["encerrado"] (map :estado (repo/vinculos-de (repo-id) ente iid))))))

(deftest ficando-outro-papel-o-vinculo-segue-e-so-o-papel-sai
  (let [ente (random-uuid) adm (admin! ente) iid (identidade! "Edu")]
    (conceder! ente iid "servidor" ["auditor"])
    (repo/adicionar-papel! (repo-id) ente {:id (random-uuid) :ente-id ente :identidade-id iid :papel "secretario"})
    (let [r (revogar! ente iid "auditor" adm "Saiu do controle interno")]
      (is (zero? (:vinculos-encerrados r))))
    (let [ator (auten/resolver-sessao (repo-id) {:identidade-id iid :ente-id ente})]
      (is (= #{"secretario"} (:papeis ator)) "a proxima chamada ja' ve so' o que sobrou")
      (is (some? ator)))))

(deftest cidadao-nunca-e-tocado
  (let [ente (random-uuid) adm (admin! ente) iid (identidade! "Fabio")]
    (conceder! ente iid "vereador" ["vereador"])
    (repo/garantir-vinculo-cidadao! (repo-id) ente iid {:finalidade "participacao_cidada" :base-legal "consentimento"
                                                        :versao-termo "t"})
    (revogar! ente iid "vereador" adm "Mandato encerrado")
    (is (some? (repo/snapshot-cidadao (repo-id) ente iid)) "a participacao como cidadao nao e' acesso da Casa")))

(deftest reconceder-depois-de-revogar-abre-outra-linha
  (let [ente (random-uuid) adm (admin! ente) iid (identidade! "Gil")]
    (conceder! ente iid "vereador" ["vereador"])
    (revogar! ente iid "vereador" adm "Afastado")
    (conceder! ente iid "vereador" ["vereador"])
    (is (= #{"vereador"} (repo/papeis-de (repo-id) ente iid)))
    (is (some? (auten/resolver-sessao (repo-id) {:identidade-id iid :ente-id ente})) "o vinculo reabriu")
    (let [linhas (linhas-de-papel ente iid)]
      (is (= 2 (count linhas)) "a revogada fica no historico e a nova e' outra linha")
      (is (= [true false] (mapv #(some? (:usuario_papel/revogado_em %)) linhas))))))

(deftest vinculo-suspenso-continua-sem-reabrir-por-reconceder
  (let [ente (random-uuid) iid (identidade! "Helio")
        {:keys [vinculo-id]} (conceder! ente iid "vereador" ["vereador"])]
    (repo/mudar-estado-vinculo! (repo-id) ente vinculo-id "suspenso")
    (is (thrown? clojure.lang.ExceptionInfo (conceder! ente iid "vereador" ["vereador"]))
        "suspender e' outro ato: so' o encerramento (que a revogacao faz) reabre ao conceder de novo")))

;; ---- isolamento por Casa ----

(deftest revogar-numa-casa-nao-mexe-na-outra
  (let [casa-a (random-uuid) casa-b (random-uuid) adm-b (admin! casa-b) iid (identidade! "Ivo")]
    (conceder! casa-a iid "vereador" ["vereador"])
    (conceder! casa-b iid "vereador" ["vereador"])
    (is (true? (:revogado? (revogar! casa-b iid "vereador" adm-b "so' na B"))))
    (is (= #{"vereador"} (repo/papeis-de (repo-id) casa-a iid)) "A intacta")
    (is (= #{} (repo/papeis-de (repo-id) casa-b iid)))
    (is (some? (auten/resolver-sessao (repo-id) {:identidade-id iid :ente-id casa-a})))
    (is (nil? (auten/resolver-sessao (repo-id) {:identidade-id iid :ente-id casa-b})))))

;; ---- credencial delegada do agente (ADR-0010) ----

(defn- credencial! [ente iid]
  (repo/emitir-credencial-agente! (repo-id) {:execucao-id (random-uuid) :ente-id ente :identidade-id iid
                                             :agente "assistente" :publico "vereador" :classes ["leitura"]
                                             :expira-em (.plusSeconds (Instant/now) 900)}))

(deftest credencial-do-agente-da-pessoa-deixa-de-valer
  (let [ente (random-uuid) adm (admin! ente) iid (identidade! "Jana")]
    (conceder! ente iid "servidor" ["auditor"])
    (repo/adicionar-papel! (repo-id) ente {:id (random-uuid) :ente-id ente :identidade-id iid :papel "secretario"})
    (let [segredo (credencial! ente iid)]
      (is (some? (auten/resolver-agente (repo-id) segredo)) "antes: a credencial resolve a pessoa")
      (revogar! ente iid "auditor" adm "Saiu")
      (is (nil? (repo/resolver-credencial-agente (repo-id) segredo))
          "mesmo com outro papel sobrando, a credencial emitida sob o papel antigo e' revogada junto")
      (is (nil? (auten/resolver-agente (repo-id) segredo))))))

(deftest credencial-de-quem-ficou-sem-vinculo-nao-resolve
  (let [ente (random-uuid) adm (admin! ente) iid (identidade! "Kleber")]
    (conceder! ente iid "vereador" ["vereador"])
    (let [segredo (credencial! ente iid)]
      (revogar! ente iid "vereador" adm "Renunciou")
      (is (nil? (auten/resolver-agente (repo-id) segredo))))))

;; ---- a lista que a tela usa ----

(deftest acessos-da-casa-lista-ativos-e-revogados-so-da-propria-casa
  (let [casa-a (random-uuid) casa-b (random-uuid) adm (admin! casa-a)
        ativo (identidade! "Lia Ativa") revogado (identidade! "Mauro Revogado") de-b (identidade! "Nina da B")]
    (conceder! casa-a ativo "servidor" ["auditor"])
    (conceder! casa-a revogado "vereador" ["vereador"])
    (conceder! casa-b de-b "vereador" ["vereador"])
    (revogar! casa-a revogado "vereador" adm "Mandato encerrado")
    (let [lista (repo/acessos-da-casa (repo-id) casa-a ["vereador" "auditor" "juridico"])
          por-nome (into {} (map (juxt :nome identity)) lista)]
      (is (= #{"Lia Ativa" "Mauro Revogado"} (set (keys por-nome))) "so' os da Casa, so' os papeis concediveis (o admin fica de fora)")
      (is (nil? (:revogado-em (por-nome "Lia Ativa"))))
      (let [m (por-nome "Mauro Revogado")]
        (is (some? (:revogado-em m)))
        (is (= "Administradora" (:revogado-por-nome m)))
        (is (= "Mandato encerrado" (:motivo m)))))))

(deftest acessos-da-casa-mostra-o-estado-mais-recente-da-pessoa-e-papel
  (let [ente (random-uuid) adm (admin! ente) iid (identidade! "Otavio")]
    (conceder! ente iid "vereador" ["vereador"])
    (revogar! ente iid "vereador" adm "Afastado")
    (conceder! ente iid "vereador" ["vereador"])
    (let [lista (repo/acessos-da-casa (repo-id) ente ["vereador"])]
      (is (= 1 (count lista)) "uma linha por pessoa e papel: a mais recente")
      (is (nil? (:revogado-em (first lista))) "foi concedido de novo: aparece ativo"))))

;; ---- a borda HTTP ----

(defn- servico []
  (let [r (repo-id) auth (it/autenticacao (idp-dev/idp-dev) r)]
    (-> (http/servico (config/carregar)
                      (identidade-in/rotas {:auth auth :repo-identidade r :idp (idp-dev/idp-dev)})
                      it/globais)
        ph/create-server ::ph/service-fn)))

(defn- bearer [ente iid]
  (str "Bearer " (json/write-value-as-string {:sub "u" :ente-id (str ente) :identidade-id (str iid)})))

(defn- post-revogacao [svc ente por alvo corpo]
  (pt/response-for svc :post (str "/identidade/acessos/" alvo "/revogacao")
                   :headers {"Content-Type" "application/json" "Authorization" (bearer ente por)}
                   :body (json/write-value-as-string corpo)))

(defn- json-de [r] (json/read-value (:body r) json/keyword-keys-object-mapper))

(deftest http-admin-revoga-e-o-efeito-vale-na-proxima-chamada-da-pessoa
  (let [svc (servico) ente (random-uuid) adm (admin! ente) iid (identidade! "Paula")]
    (conceder! ente iid "servidor" ["auditor"])
    (repo/adicionar-papel! (repo-id) ente {:id (random-uuid) :ente-id ente :identidade-id iid :papel "secretario"})
    (let [ver #(json-de (pt/response-for svc :get "/meu/identidade" :headers {"Authorization" (bearer ente iid)}))]
      (is (= ["auditor" "secretario"] (:papeis (ver))) "antes")
      (let [r (post-revogacao svc ente adm iid {:papel "auditor" :motivo "Saiu do controle interno"})]
        (is (= 200 (:status r)))
        (is (false? (:vinculo-encerrado (json-de r)))))
      (is (= ["secretario"] (:papeis (ver))) "a MESMA sessao, a proxima chamada: o papel ja' saiu"))))

(deftest http-ultimo-papel-derruba-a-sessao-com-401
  (let [svc (servico) ente (random-uuid) adm (admin! ente) iid (identidade! "Quiteria")]
    (conceder! ente iid "vereador" ["vereador"])
    (is (= 200 (:status (pt/response-for svc :get "/meu/identidade" :headers {"Authorization" (bearer ente iid)}))))
    (let [r (post-revogacao svc ente adm iid {:papel "vereador" :motivo "Renunciou ao mandato"})]
      (is (= 200 (:status r)))
      (is (true? (:vinculo-encerrado (json-de r)))))
    (is (= 401 (:status (pt/response-for svc :get "/meu/identidade" :headers {"Authorization" (bearer ente iid)})))
        "sem vinculo ativo a pessoa nao tem mais sessao")))

(deftest http-so-admin-ente-revoga-e-lista
  (let [svc (servico) ente (random-uuid) iid (identidade! "Rafa")
        secretaria (identidade! "Secretaria")]
    (conceder! ente iid "vereador" ["vereador"])
    (conceder! ente secretaria "servidor" ["secretario"])
    (is (= 403 (:status (post-revogacao svc ente secretaria iid {:papel "vereador" :motivo "tentando"})))
        "o `secretario` mantem o cadastro, nao tira acesso")
    (is (= 403 (:status (post-revogacao svc ente iid iid {:papel "vereador" :motivo "eu mesmo"}))) "nem o proprio vereador")
    (is (= 403 (:status (pt/response-for svc :get "/identidade/acessos" :headers {"Authorization" (bearer ente secretaria)}))))
    (is (= #{"vereador"} (repo/papeis-de (repo-id) ente iid)) "nada mudou")))

(deftest http-corpo-invalido-e-400
  (let [svc (servico) ente (random-uuid) adm (admin! ente) iid (identidade! "Sara")]
    (conceder! ente iid "vereador" ["vereador"])
    (testing "motivo obrigatorio"
      (is (= 400 (:status (post-revogacao svc ente adm iid {:papel "vereador"}))))
      (is (= 400 (:status (post-revogacao svc ente adm iid {:papel "vereador" :motivo "  "}))))
      (is (= 400 (:status (post-revogacao svc ente adm iid {:papel "vereador" :motivo (apply str (repeat 501 "x"))})))))
    (testing "so' o conjunto de papeis que a tela concede — nem `admin_ente`, nem `secretario`"
      (is (= 400 (:status (post-revogacao svc ente adm iid {:papel "admin_ente" :motivo "tirar o administrador"}))))
      (is (= 400 (:status (post-revogacao svc ente adm iid {:papel "secretario" :motivo "tirar a secretaria"})))))
    (testing "chave forjada e' recusada"
      (is (= 400 (:status (post-revogacao svc ente adm iid {:papel "vereador" :motivo "ok" :revogado-por (str adm)})))))
    (is (= #{"vereador"} (repo/papeis-de (repo-id) ente iid)))))

(deftest http-administrador-nunca-fica-sem-administrador
  ;; admin_ente nao e' concedivel e, pelo mesmo conjunto, nao e' revogavel por esta rota: a Casa nao tem como perder o
  ;; ultimo administrador por aqui. O admin que revoga o PROPRIO acesso de outro papel segue administrador.
  (let [svc (servico) ente (random-uuid) adm (admin! ente)]
    (conceder! ente adm "servidor" ["auditor"])
    (is (= 200 (:status (post-revogacao svc ente adm adm {:papel "auditor" :motivo "nao preciso mais da trilha"}))))
    (let [r (post-revogacao svc ente adm adm {:papel "admin_ente" :motivo "sair"})]
      (is (= 400 (:status r))))
    (is (= #{"admin_ente"} (repo/papeis-de (repo-id) ente adm)))
    (is (some? (auten/resolver-sessao (repo-id) {:identidade-id adm :ente-id ente})))))

(deftest http-404-quando-nao-ha-acesso-ativo-ou-e-de-outra-casa
  (let [svc (servico) casa-a (random-uuid) casa-b (random-uuid) adm-b (admin! casa-b) iid (identidade! "Tiago")]
    (conceder! casa-a iid "vereador" ["vereador"])
    (testing "o admin da Casa B nao enxerga nem revoga o acesso que a pessoa tem na Casa A"
      (let [r (post-revogacao svc casa-b adm-b iid {:papel "vereador" :motivo "invadindo a outra Casa"})]
        (is (= 404 (:status r))))
      (is (= #{"vereador"} (repo/papeis-de (repo-id) casa-a iid)) "A intacta"))
    (testing "id que nao e' UUID"
      (is (= 404 (:status (post-revogacao svc casa-b adm-b "nao-e-uuid" {:papel "vereador" :motivo "xxx"})))))
    (testing "ja' revogado"
      (let [adm-a (admin! casa-a)]
        (is (= 200 (:status (post-revogacao svc casa-a adm-a iid {:papel "vereador" :motivo "Mandato encerrado"}))))
        (is (= 404 (:status (post-revogacao svc casa-a adm-a iid {:papel "vereador" :motivo "de novo"}))))))))

(deftest http-lista-de-acessos-so-da-propria-casa-e-sem-cpf
  (let [svc (servico) casa-a (random-uuid) casa-b (random-uuid) adm-a (admin! casa-a)
        a (identidade! "Ursula da A") b (identidade! "Vitor da B")]
    (conceder! casa-a a "vereador" ["vereador"])
    (conceder! casa-b b "vereador" ["vereador"])
    (let [r (pt/response-for svc :get "/identidade/acessos" :headers {"Authorization" (bearer casa-a adm-a)})
          corpo (json-de r)]
      (is (= 200 (:status r)))
      (is (= ["Ursula da A"] (map :nome (:acessos corpo))) "so' a Casa A, so' os papeis concediveis")
      (is (= "vereador" (:papel (first (:acessos corpo)))))
      (is (nil? (re-find #"\d{11}" (:body r))) "nunca CPF"))))

(deftest http-reconceder-pela-rota-de-sempre-reabre-o-acesso
  (let [svc (servico) ente (random-uuid) adm (admin! ente) iid (identidade! "Wanda")
        idp-fake (reify idp/IdentityProvider
                   (verificar-token [_ _] nil) (provisionar-realm! [_ _] true)
                   (criar-usuario! [_ _ _] {:keycloak-user-id "k"}) (convidar! [_ _ _] true) (resetar-mfa! [_ _ _] true))
        svc2 (-> (http/servico (config/carregar)
                               (identidade-in/rotas {:auth (it/autenticacao (idp-dev/idp-dev) (repo-id))
                                                     :repo-identidade (repo-id) :idp idp-fake})
                               it/globais)
                 ph/create-server ::ph/service-fn)]
    (conceder! ente iid "vereador" ["vereador"])
    (is (= 200 (:status (post-revogacao svc ente adm iid {:papel "vereador" :motivo "Afastada"}))))
    (let [r (pt/response-for svc2 :post "/identidade/acessos"
                             :headers {"Content-Type" "application/json" "Authorization" (bearer ente adm)}
                             :body (json/write-value-as-string {:identidade-id (str iid) :tipo "vereador" :papeis ["vereador"]
                                                                :email "wanda@camara.gov.br"}))]
      (is (= 201 (:status r))))
    (is (= 200 (:status (pt/response-for svc :get "/meu/identidade" :headers {"Authorization" (bearer ente iid)}))))))

;; ---- a migration nao pode ressuscitar acesso ----

(defn- instrucoes-do-down []
  (->> (str/split (slurp (io/resource "migrations/20261004000191-identidade-revogar-acesso.down.sql")) #"--;;")
       (map str/trim) (remove str/blank?)))

(deftest down-da-migration-nao-ressuscita-o-acesso-revogado
  ;; up -> revoga -> down: a pessoa revogada segue SEM o papel (a linha revogada sai antes de a coluna cair; sem isso a
  ;; linha voltaria a valer). Quem tinha o papel ativo o mantem. Tudo numa tx que desfaz: o banco de teste nao regride.
  (let [ente (random-uuid) adm (admin! ente) revogada (identidade! "Xuxa") ativa (identidade! "Yuri")]
    (conceder! ente revogada "vereador" ["vereador"])
    (conceder! ente ativa "vereador" ["vereador"])
    (revogar! ente revogada "vereador" adm "Mandato encerrado")
    (let [n (fn [tx iid] (count (jdbc/execute! tx ["SELECT 1 FROM identidade.usuario_papel WHERE ente_id = ? AND identidade_id = ?
                                                      AND papel = 'vereador'" ente iid])))]
      (jdbc/with-transaction [tx *ds* {:rollback-only true}]
        (jdbc/execute! tx ["SELECT set_config('app.ente_id', ?, true)" (str ente)])
        (is (= 1 (n tx revogada)) "antes do down a linha revogada existe (a leitura enxerga: o teste nao e' vacuo)")
        (doseq [i (instrucoes-do-down)] (jdbc/execute! tx [i]))
        (is (zero? (n tx revogada)) "depois do down o acesso revogado NAO voltou")
        (is (= 1 (n tx ativa)) "o ativo segue")))
    (is (= #{} (repo/papeis-de (repo-id) ente revogada)) "a tx desfez: o banco segue na versao nova")))
