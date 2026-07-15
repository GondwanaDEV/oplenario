(ns oplenario.identidade.fail-closed-test
  "A afirmacao de seguranca da spec §4.2: 'acesso por ultimo' — parar em QUALQUER ponto do provisionamento
  de 3 passos deixa o sistema FECHADO. Se este teste cair, o fluxo virou fail-OPEN e alguem entra antes da
  hora — o desenho e' que muda, nunca a assercao.

  Os 3 passos do provisionamento (spec §4.2): (1) criar a identidade [supratenant, carrega CPF] ->
  (2) ligar o cadastro de vereador a ela [tenant] -> (3) conceder acesso [tenant] = o vinculo + papeis que
  ABREM A PORTA. Cada deftest para num passo e prova que `resolver-sessao` devolve nil ate' o passo 3.

  Split de privilegio de CPF (disc.1 §22.5.3): o passo 1 roda no `:ds` cru (role id_resolver); os passos 2-3
  rodam em `transacao` (SET LOCAL ROLE oplenario_app, que PERDE o acesso a CPF). Sao chamadas SEPARADAS de
  proposito — nunca uma tx unica atravessando os dois niveis (e' assim que o Repo ja' faz).

  Cross-modulo (cadastros + identidade) como `marco_m1_test` — precedente estabelecido p/ teste de
  integracao que atravessa modulos (§22.10 proibe o import entre modulos de PRODUCAO, nao entre testes)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [oplenario.cadastros.components.repositorio :as repo-cad]
            [oplenario.config :as config]
            [oplenario.identidade.autenticacao :as auten]
            [oplenario.identidade.components.repositorio :as repo]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.migracao :as migracao]))

(def ^:dynamic *datasource* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*datasource* c] (try (t) (finally (component/stop c)))))))

(defn- repo-identidade
  "RepoIdentidade sobre o datasource do teste — mesmo padrao de db_test.clj/marco_m1_test.clj."
  []
  (assoc (repo/repositorio) :datasource {:ds (:ds *datasource*)}))

(defn- repo-cadastros
  "RepoCadastros sobre o MESMO datasource — padrao de cadastros/repositorio_escrita_test.clj (o record
  recebe o Component inteiro, que ja' carrega o :ds)."
  []
  (repo-cad/->RepoCadastrosPg *datasource*))

;; CPF valido (digito verificador correto). `criar-identidade!` e' idempotente POR CPF: os deftests deste
;; arquivo compartilham UMA identidade canonica — o isolamento vem do `ente` aleatorio de cada teste, que e'
;; exatamente o eixo sob teste aqui (vinculo e' TENANT).
(def ^:private cpf-helena "52998224725")
(def ^:private nome-helena "Helena Matos")

(defn- criar-identidade! [r]
  (repo/criar-identidade! r {:id (random-uuid) :cpf cpf-helena :nome nome-helena}))

(deftest parar-apos-criar-identidade-nao-loga
  (let [ente (random-uuid)
        r (repo-identidade)
        ident (criar-identidade! r)]
    (is (some? ident) "o passo 1 de fato aconteceu (a identidade existe) — o nil abaixo nao e' por setup vazio")
    (is (nil? (auten/resolver-sessao r {:identidade-id ident :ente-id ente}))
        "PASSO 1 so': identidade existe, vinculo NAO -> resolver-sessao nil -> ninguem entra")))

(deftest parar-apos-ligar-vereador-nao-loga
  (let [ente (random-uuid)
        r (repo-identidade)
        rc (repo-cadastros)
        ident (criar-identidade! r)
        ;; `criar-vereador!` NAO devolve :id (sem RETURNING) — o id nasce no caller, como em marco_m1_test.
        vid (random-uuid)]
    (repo-cad/criar-vereador! rc ente {:id vid :ente-id ente :nome nome-helena :nome-parlamentar nome-helena})
    (is (= 1 (repo-cad/ligar-identidade! rc ente vid ident))
        "o passo 2 de fato ligou o cadastro (update-count 1) — o nil abaixo nao e' por setup falho")
    (is (nil? (auten/resolver-sessao r {:identidade-id ident :ente-id ente}))
        "PASSOS 1+2: cadastro ligado, acesso NAO concedido -> ainda nil -> ainda ninguem entra")))

(deftest so-apos-conceder-acesso-loga
  (let [ente (random-uuid)
        r (repo-identidade)
        ident (criar-identidade! r)]
    (is (nil? (auten/resolver-sessao r {:identidade-id ident :ente-id ente}))
        "antes do passo 3: fechado")
    (repo/conceder-acesso! r ente {:id (random-uuid) :ente-id ente :identidade-id ident
                                   :tipo "vereador" :estado "ativo"} ["vereador"])
    (let [ator (auten/resolver-sessao r {:identidade-id ident :ente-id ente})]
      (is (some? ator) "PASSO 3: a porta abre — e SO' aqui")
      (is (= "vereador" (:tipo-vinculo ator)))
      (is (= #{"vereador"} (:papeis ator))))))

(deftest vinculo-suspenso-fecha-a-porta-na-hora
  (let [ente (random-uuid)
        r (repo-identidade)
        ident (criar-identidade! r)
        {:keys [vinculo-id]} (repo/conceder-acesso! r ente {:id (random-uuid) :ente-id ente
                                                            :identidade-id ident :tipo "vereador"
                                                            :estado "ativo"} ["vereador"])]
    (is (some? (auten/resolver-sessao r {:identidade-id ident :ente-id ente}))
        "a porta estava ABERTA — sem isto, o nil pos-suspensao nao provaria nada")
    (is (= 1 (:next.jdbc/update-count (repo/mudar-estado-vinculo! r ente vinculo-id "suspenso")))
        "a suspensao atingiu a linha (update-count 1)")
    (is (nil? (auten/resolver-sessao r {:identidade-id ident :ente-id ente}))
        "authz viva: resolver-sessao roda a cada request — suspender derruba na hora, nao espera o cookie expirar")))

(deftest mesmo-cpf-duas-casas-nao-vaza-poder
  (let [casa-a (random-uuid)
        casa-b (random-uuid)
        r (repo-identidade)
        ident (criar-identidade! r)]
    ;; disc.1 (§22.5.3): mesmo CPF, uma identidade, dois vinculos. Vereadora em A, cidada em B.
    (repo/conceder-acesso! r casa-a {:id (random-uuid) :ente-id casa-a :identidade-id ident
                                     :tipo "vereador" :estado "ativo"} ["vereador"])
    (let [em-a (auten/resolver-sessao r {:identidade-id ident :ente-id casa-a})
          em-b (auten/resolver-sessao r {:identidade-id ident :ente-id casa-b})]
      (is (= #{"vereador"} (:papeis em-a)) "poderes em A")
      (is (nil? em-b)
          "MESMA identidade, ZERO poder em B — 'vereador acessando como cidadao no mesmo CPF nao traz
           consigo poderes de vereador' (§22.5.2 eixo D)"))
    ;; e agora com vinculo REAL em B: a porta abre em B, mas SO' como cidada — os papeis de A nao atravessam.
    (repo/conceder-acesso! r casa-b {:id (random-uuid) :ente-id casa-b :identidade-id ident
                                     :tipo "cidadao" :estado "ativo"} [])
    (let [em-b (auten/resolver-sessao r {:identidade-id ident :ente-id casa-b})]
      (is (= "cidadao" (:tipo-vinculo em-b)) "mesma identidade = cidada na Casa B")
      (is (= #{} (:papeis em-b)) "ZERO papeis em B — o 'vereador' de A nao vaza pelo CPF compartilhado"))))
