(ns personas
  "A 5a semente da demo — CREDENCIAIS Keycloak p/ as 5 personas nomeadas da Casa (secretaria/presidente/
  vereador comum/cidada/apresentacao), sobre a Casa JA' SEMEADA por `casa/semear!` (via `demo/semear-tudo.sh`).

  ENTRYPOINT PROPRIO (nao entra em `semear_tudo.clj`): o Keycloak so' existe sob `--profile auth`;
  embutir esta semente na narrativa quebraria `semear-tudo.sh` p/ quem roda sem auth. Rodar via
  `demo/semear-credenciais.sh` (o script de host que sobe o container efemero com KEYCLOAK_BASE_URL
  apontando pro DNS do compose).

  NUNCA SEMEIA A CASA — so' LE. Resolve as 5 identidades pelos CPFs fixos de `casa.clj` (publicos de
  proposito — ver comentario la') via `identidade.db.identidade/por-cpf` (leitura pura, sem upsert); se
  alguma faltar, a Casa nao esta semeada e a funcao FALHA ALTO com uma mensagem acionavel — nunca cria a
  Casa por conta propria (essa e' responsabilidade exclusiva de `casa/semear!`).

  O QUE FAZ, nesta ordem:
   1. resolve os 5 identidade-ids pelos CPFs fixos (leitura, idempotente por natureza);
   2. mata a ARMADILHA DO TOKEN DE 56s (`keycloak-admin/estender-lifespan-admin-token!`) ANTES de
      qualquer outra chamada admin — um token pego uma vez e reusado por ~10 chamadas (5 personas × 2
      operacoes) expiraria no meio numa tentativa e falharia numa etapa DIFERENTE na proxima, o que lê
      como flakiness mas e' relogio;
   3. `idp/provisionar-realm!` p/ o ente da demo (idempotente — declara o atributo `identidade-id`,
      habilita passkey, configura SMTP/Mailpit, cria os clients `oplenario-backend` e `oplenario-web`
      PKCE);
   4. p/ cada persona: `idp/criar-usuario!` (idempotente — GET-then-create) + `keycloak-admin/
      limpar-required-actions!` (o KC crava `webauthn-register-passwordless` sempre; sem limpar,
      ninguem loga so' com senha) + `keycloak-admin/setar-senha!` (senha fixa de demo, ver `senha-demo`);
   5. grava `credenciais.edn` em `casa/diretorio-de-artefatos` + imprime um cartao legivel em stdout.

  IDEMPOTENCIA: `provisionar-realm!` e `criar-usuario!` sao idempotentes por desenho (GET-then-create);
  `limpar-required-actions!`/`setar-senha!` sao PUTs que afirmam o estado desejado — reexecutar a
  semente convergem p/ o MESMO estado, nunca duplica nem falha por 409. Rodar duas vezes e' seguro."
  (:require [casa]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [com.stuartsierra.component :as component]
            [keycloak-admin :as kc-admin]
            [oplenario.config :as config]
            [oplenario.identidade.db.identidade :as id]
            [oplenario.identidade.db.vinculo :as vinc]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.components.idp :as idp]
            [oplenario.kernel.components.keycloak-idp :as keycloak-idp]
            [oplenario.kernel.tenancy :as tenancy])
  (:import (java.net.http HttpClient)))

(def senha-demo
  "Senha UNICA das 5 personas — credencial de DEMONSTRACAO LOCAL, nunca de producao. Este repositorio
  NAO TEM REMOTE (`git remote -v` vazio — confirmado, ver `docs/18-personas-da-demo.md`): nao ha' canal
  de vazamento por push, isto e' FIXTURE de dev, nao segredo. Se o Keycloak recusar por politica de
  senha do realm, a resposta e' AJUSTAR A POLITICA no provisionamento (`idp/provisionar-realm!` ou um
  passo adicional aqui), nunca enfraquecer a senha silenciosamente — o realm provisionado por
  `provisionar-realm-impl` nasce SEM `passwordPolicy` (Keycloak 26 default = nenhuma restricao), entao
  esta senha (12 chars, maiuscula+minuscula+digito+simbolo) passa sob qualquer politica razoavel; se um
  realm pre-existente tiver uma politica mais estrita, o operador ve o erro 400 do KC (`setar-senha!`
  falha alto) e ajusta aqui, nao no valor da senha."
  "Plenario@2026")

;; As 5 personas nomeadas — MESMOS CPFs fixos e MESMOS nomes que `casa/criar-identidades!` usa. A fonte
;; do dado (nome, vinculo, papeis) e' SEMPRE a Casa ja semeada (lida abaixo); os campos aqui sao so' a
;; CHAVE de resolucao (cpf) + o rotulo humano p/ o cartao impresso.
(def ^:private personas
  [{:chave :secretaria    :cpf casa/cpf-secretaria :rotulo "Secretária da Mesa"}
   {:chave :presidente    :cpf casa/cpf-presidente :rotulo "Presidente da Mesa"}
   {:chave :vereador      :cpf casa/cpf-vereador-comum :rotulo "Vereadora"}
   {:chave :cidadao       :cpf casa/cpf-cidadao :rotulo "Cidadã"}
   {:chave :apresentacao  :cpf casa/cpf-apresentacao :rotulo "Apresentação (acesso total)"}])

(defn- resolver-identidade
  "Le a identidade pelo CPF fixo — LEITURA PURA (`identidade/por-cpf`, sem upsert). Lanca alto e claro
  se a Casa nao existir: esta semente NUNCA cria a Casa por conta propria (contrato do briefing) —
  quem semeia a Casa e' `casa/semear!`, via `./demo/semear-tudo.sh`."
  [ds {:keys [cpf rotulo]}]
  (or (id/por-cpf ds cpf)
      (throw (ex-info
              (str "personas/semear-credenciais!: a Casa da demo nao esta semeada — identidade '"
                   rotulo "' nao encontrada pelo CPF fixo " cpf ". Rode ./demo/semear-tudo.sh primeiro.")
              {:tipo :personas/casa-ausente :cpf cpf :rotulo rotulo}))))

(defn- vinculo-e-papeis
  "Le o vinculo (tipo) + o conjunto de papeis da identidade NA Casa da demo — dado REAL, nunca inferido
  do rotulo (a persona 'presidente' tem 2 papeis; 'cidadao' tem 0 — ver `casa/criar-identidades!`)."
  [ds identidade-id]
  (tenancy/com-tenant* ds casa/ente-id
    (fn [tx]
      {:tipo-vinculo (:tipo (first (vinc/vinculos-de tx casa/ente-id identidade-id)))
       :papeis (vinc/papeis-de tx casa/ente-id identidade-id)})))

(defn- url-entrada
  "A URL de entrada e' a MESMA p/ as 5 personas — um unico realm-por-tenant (§22.5.1), o mesmo padrao
  ja' usado por `seed-demo/login-kc`/`seed-demo/slice5`. Depois do login, o app roteia cada persona
  pelo PAPEL do token — o roteiro de exploracao por persona esta em `docs/18-personas-da-demo.md`."
  [ente-id]
  (str "http://localhost:3000/entrar/" ente-id))

(defn- provisionar-persona!
  "Cria (ou reusa) o usuario Keycloak da persona + limpa a required-action de passkey + seta a senha
  fixa de demo. `token` e' o token admin JA' obtido sob o lifespan estendido (reusado pelas 5 personas —
  exatamente o cenario que `keycloak-admin/estender-lifespan-admin-token!` existe p/ proteger: ~10
  chamadas admin sequenciais, 2 por persona)."
  [idp http cfg token realm {:keys [id nome]}]
  (let [{:keys [base-url]} cfg
        email (str id "@demo.oplenario.local")
        {:keys [keycloak-user-id]} (idp/criar-usuario! idp casa/ente-id
                                     {:identidade-id id :nome nome :email email})]
    (kc-admin/limpar-required-actions! http base-url token realm keycloak-user-id)
    (kc-admin/setar-senha! http base-url token realm keycloak-user-id senha-demo)
    keycloak-user-id))

(defn- gravar-artefato!
  "Grava `credenciais.edn` em `casa/diretorio-de-artefatos` — mesma pasta/regra de `casa/semear!`, uma
  unica fonte de artefatos da demo. FALHA ALTO se nao conseguir escrever (mesma disciplina de
  `casa/gravar-artefato!` — nunca engolir em silencio uma falha de escrita)."
  [resultado]
  (let [dir (casa/diretorio-de-artefatos)]
    (.mkdirs dir)
    (let [alvo (io/file dir "credenciais.edn")]
      (spit alvo (pr-str resultado))
      (println "personas/semear-credenciais!: credenciais gravadas em" (.getAbsolutePath alvo))
      alvo)))

(defn- papeis->str [papeis]
  (if (seq papeis) (str/join ", " (sort papeis)) "(sem papel)"))

(defn- imprimir-cartao!
  [realm resultado]
  (println "\n=== CREDENCIAIS DA DEMO (realm" realm ") ===")
  (doseq [{:keys [rotulo nome tipo-vinculo papeis username url]} resultado]
    (println "---")
    (println "persona     :" rotulo (str "(" nome ")"))
    (println "vinculo     :" tipo-vinculo "·" (papeis->str papeis))
    (println "username    :" username " (= identidade-id)")
    (println "senha       :" senha-demo)
    (println "URL         :" url))
  (println "===============================================\n"))

(defn semear-credenciais!
  "Ponto de entrada do `-X` (`clojure -X:seed personas/semear-credenciais!`). Le a Casa ja' semeada,
  provisiona o realm + os 5 usuarios Keycloak, grava o artefato e imprime o cartao. FALHA ALTO em
  qualquer etapa (Casa ausente, Keycloak fora do ar, erro de infra do admin-API) — nunca degrada em
  silencio."
  [_]
  (let [cfg          (config/carregar)
        kc-cfg       (:keycloak cfg)
        {:keys [base-url admin-usuario admin-senha realm-prefixo]} kc-cfg
        ds-component (component/start (datasource/datasource cfg))
        ds           (:ds ds-component)
        idp          (component/start (keycloak-idp/keycloak-idp kc-cfg))
        http         (HttpClient/newHttpClient)]
    (try
      ;; 1. resolve as 5 identidades — LEITURA, falha alto se a Casa nao existir.
      (let [resolvidas (mapv (fn [p] (assoc p :identidade (resolver-identidade ds p))) personas)]
        ;; 2. mata a armadilha do token de 56s ANTES de qualquer outra chamada admin.
        (let [tok0 (kc-admin/admin-token! http base-url admin-usuario admin-senha)]
          (kc-admin/estender-lifespan-admin-token! http base-url tok0))
        ;; 3. provisiona o realm do ente (idempotente).
        (idp/provisionar-realm! idp casa/ente-id)
        (let [realm (str realm-prefixo casa/ente-id)
              ;; token NOVO, obtido DEPOIS da extensao — este e' o que vive 3600s e e' reusado pelas 5
              ;; personas (~8 chamadas admin: limpar-required-actions!+setar-senha! por persona).
              tok   (kc-admin/admin-token! http base-url admin-usuario admin-senha)
              resultado
              (mapv (fn [{:keys [chave rotulo identidade]}]
                      (let [{:keys [id nome]} identidade
                            keycloak-user-id (provisionar-persona! idp http kc-cfg tok realm identidade)
                            {:keys [tipo-vinculo papeis]} (vinculo-e-papeis ds id)]
                        {:persona chave :rotulo rotulo :nome nome
                         :identidade-id id :keycloak-user-id keycloak-user-id
                         :tipo-vinculo tipo-vinculo :papeis papeis
                         :username (str id) :senha senha-demo
                         :url (url-entrada casa/ente-id)}))
                    resolvidas)]
          (gravar-artefato! resultado)
          (imprimir-cartao! realm resultado)
          resultado))
      (finally
        (component/stop idp)
        (component/stop ds-component)))))
