(ns oplenario.identidade.autenticacao
  "Resolucao de SESSAO (§22.5 eixo D): de claims de token VERIFICADO -> o `ator` que a camada de
  autorizacao (kernel/autorizacao) consome. Carrega o vinculo ATIVO + o snapshot de papeis do tenant
  numa UNICA tx via o RepoIdentidade (§3-bis: passa pelo Repo-Component, NUNCA pelo db/ direto).
  Fail-closed: sem vinculo ativo -> nil (sem sessao). O `ator` bate a forma de kernel/autorizacao
  ({:identidade-id :ente-id :papeis ...}).

  A VERIFICACAO do token (assinatura/exp/issuer) e' do IdP port (kernel/components/idp); aqui as claims ja
  vem verificadas. Os fluxos VIVOS — login passkey, broker gov.br, provisionamento de realm-por-tenant,
  e os interceptors Pedestal que chamam isto — sao infra-gated (Keycloak vivo + credencial gov.br +
  rotas F3) -> carry F1.4. Este e' o seam estavel, testavel contra o DB real."
  (:require [oplenario.identidade.components.repositorio :as repo]
            [oplenario.identidade.models.identidade :as mod]
            [oplenario.kernel.autorizacao :as authz]))

(set! *warn-on-reflection* true)

(defn resolver-sessao
  "claims VERIFICADAS {:identidade-id :ente-id} + o RepoIdentidade -> ator {:identidade-id :ente-id
  :tipo-vinculo :vinculo-ativo-id :papeis} ou nil (fail-closed) se nao ha vinculo ATIVO da identidade
  no ente. Um vinculo SUSPENSO/ENCERRADO nao da sessao (§22.5 eixo G). O operador supratenant (sem
  ente-id) e' caso a parte (admin_sistema), nao tratado aqui. A selecao do vinculo ativo e' deterministica
  (ORDER BY no repo) — multi-vinculo nao escolhe ao acaso."
  [repo-identidade {:keys [identidade-id ente-id]}]
  (when (and identidade-id ente-id)
    (when-let [{:keys [vinculo-ativo papeis]} (repo/snapshot-ator repo-identidade ente-id identidade-id)]
      {:identidade-id    identidade-id
       :ente-id          ente-id
       :tipo-vinculo     (:tipo vinculo-ativo)
       :vinculo-ativo-id (:id vinculo-ativo)
       :papeis           papeis})))

(defn- ator-institucional
  "O ator do agente institucional (sem pessoa, 3.1 b; B.8, ADR-0013): o papel de agente institucional SO' enquanto a
  concessao do `admin_ente` esta' ativa — desligar derruba o agente na chamada seguinte, como vinculo suspenso derruba
  a pessoa — e as classes da credencial dentro das concedidas. Sem concessao: sem papel algum, nada executa."
  [repo-identidade ente-id via]
  (let [c (repo/concessao-agente repo-identidade ente-id (:agente via))]
    {:identidade-id nil :ente-id ente-id
     :papeis (if c #{authz/papel-agente-institucional} #{})
     :via (update via :classes #(into #{} (filter (into #{} (map keyword) (:classes c))) %))}))

(defn resolver-agente
  "Credencial delegada (ADR-0010) -> o `ator` de uma chamada de AGENTE, ou nil (fail-closed). A permissao nunca vem
  da credencial: a pessoa e' resolvida AGORA pela mesma `resolver-sessao` das telas (vinculo ativo + papeis do
  momento — mandato encerrado ou vinculo suspenso derruba o agente na hora, Eixo 3.2), e a credencial so' acrescenta
  `:via` — quem age (agente, execucao), o publico cujo conjunto de ferramentas vale e as classes concedidas.
  Agente institucional (sem pessoa, 3.1 b): o papel dele vem da concessao ativa do `admin_ente`, conferida AGORA."
  [repo-identidade segredo]
  (when segredo
    (when-let [{:keys [execucao-id ente-id identidade-id agente publico classes]}
               (repo/resolver-credencial-agente repo-identidade segredo)]
      (let [via {:agente agente :execucao-id execucao-id :publico (keyword publico)
                 :classes (into #{} (map keyword) classes) :institucional? (nil? identidade-id)}]
        (if identidade-id
          (some-> (resolver-sessao repo-identidade {:identidade-id identidade-id :ente-id ente-id})
                  (assoc :via via))
          (ator-institucional repo-identidade ente-id via))))))

(def prazo-credencial-agente-seg
  "Vida maxima de uma credencial delegada: uma execucao de agente, nao uma sessao de trabalho. Curta de proposito —
  vazou, expira logo; e a execucao que precisar de mais pede outra."
  (* 15 60))

(defn emitir-credencial-agente!
  "Emite a credencial delegada de UMA execucao (ADR-0010, Eixo 3.4): a pessoa do `ator` (a sessao dela, que invocou
  o agente numa tela — 3.3) como sujeito, o `agente` como ator, o `publico` cujo conjunto de ferramentas vale e as
  `classes` concedidas. Devolve {:execucao-id :credencial :expira-em}; a credencial crua existe so' aqui."
  [repo-identidade ator {:keys [agente publico classes]}]
  {:pre [(:identidade-id ator) (:ente-id ator) (seq classes)]}
  (let [execucao-id (random-uuid)
        expira-em (.plusSeconds (java.time.Instant/now) prazo-credencial-agente-seg)
        segredo (repo/emitir-credencial-agente! repo-identidade
                                                {:execucao-id execucao-id :ente-id (:ente-id ator)
                                                 :identidade-id (:identidade-id ator) :agente agente
                                                 :publico (name publico) :classes (mapv name (sort classes))
                                                 :expira-em expira-em})]
    {:execucao-id execucao-id :credencial segredo :expira-em expira-em}))

(defn emitir-credencial-institucional!
  "Emite a credencial de UMA execucao do agente INSTITUCIONAL `agente` na Casa (B.8, ADR-0013): sem pessoa, publico
  `institucional`, as classes da concessao ativa (nunca `ato`). nil = agente desconhecido ou nao concedido pelo
  `admin_ente` — o satelite nao roda nada. Devolve {:execucao-id :credencial :expira-em}."
  [repo-identidade ente-id agente]
  {:pre [(some? ente-id)]}
  (when (contains? mod/agentes-institucionais agente)
    (when-let [c (repo/concessao-agente repo-identidade ente-id agente)]
      (let [execucao-id (random-uuid)
            expira-em (.plusSeconds (java.time.Instant/now) prazo-credencial-agente-seg)
            segredo (repo/emitir-credencial-agente! repo-identidade
                                                    {:execucao-id execucao-id :ente-id ente-id :identidade-id nil
                                                     :agente agente :publico "institucional"
                                                     :classes (vec (sort (:classes c))) :expira-em expira-em})]
        {:execucao-id execucao-id :credencial segredo :expira-em expira-em}))))
