(ns oplenario.identidade.components.repositorio
  "Component de PERSISTENCIA do identidade — o banco DISPONIBILIZADO como Stuart Sierra Component (ADR-0001
  §3, revisao). RepoIdentidade expoe as ACOES; RepoIdentidadePg segura o `:datasource` (via `using`); o
  `db/` e' a IMPL. SUPRATENANT (identidade/CPF, broker, sessao opaca) roda sobre o `:ds` direto (pool herda
  o role id_resolver); TENANT (vinculo/papel/consentimento) roda via `com-tenant*`. `transacao` compoe acoes
  tenant numa unica tx (ex.: snapshot de ator = vinculos + papeis no mesmo snapshot).

  `sessao-janela-ociosa-seg` (2o campo do record, default 1800s/30min via a aridade-0 de `repositorio`) e'
  a janela de deslize de `resolver-sessao-por-segredo` — existe como campo do record (nao arg extra do
  protocolo) para manter o metodo 2-arg, do qual o interceptor de auth (Task 4) depende. CARRY: reconciliar
  este default com a config `:sessao :ociosa-min` quando o mint (Task 4) existir — hoje sao DUAS fontes
  do mesmo numero (o mint crava o `ocioso-ate` inicial; este campo crava o deslize) que precisam concordar."
  (:require [clojure.string :as str]
            [oplenario.identidade.db.concessao-agente :as concessao]
            [oplenario.identidade.db.credencial-agente :as cred]
            [oplenario.identidade.db.identidade :as id]
            [oplenario.identidade.db.perfil-juridico :as pj]
            [oplenario.identidade.db.sessao :as sess]
            [oplenario.identidade.db.vinculo :as vinc]
            [oplenario.identidade.events.vinculo :as ev-vinculo]
            [oplenario.kernel.eventos :as eventos]
            [oplenario.kernel.outbox :as outbox]
            [oplenario.kernel.tenancy :as tenancy]))

(defprotocol RepoIdentidade
  (transacao [this ente-id f] "Roda (f tx) numa UNICA tx do tenant.")
  ;; SUPRATENANT (sobre o :ds; role id_resolver)
  (criar-identidade! [this identidade] "CPF -> id canonico (idempotente).")
  (identidade-por-cpf [this cpf])
  (identidade-por-id [this id])
  (nome-por-id [this id]
    "Leitura ESTREITA (so' :nome, sem :cpf) — pra caminhos que nao devem ver CPF (ex.: provisionar
    usuario no IdP). Review Task 8 IMPORTANT-2b: nao reusar `identidade-por-id` aqui de proposito.")
  (pessoas-com-cpf-mascarado [this ids]
    "Balcao de atendimento: ids -> {id {:nome :cpf-mascarado}}. Leitura ESTREITA: o CPF ja' sai mascarado do banco.")
  (identidade-existe? [this id]
    "Leitura ESTREITA (booleano, nem :nome nem :cpf) — guard default de `rotas.clj` pro
    PATCH /cadastros/vereadores/:id/identidade. Review Task 12 IMPORTANT: nao reusar `identidade-por-id`
    (nem `nome-por-id`) aqui de proposito, mesmo principio de minimizacao de dado materializado.")
  (vincular-externa! [this vinculo-externo] "Liga sub gov.br -> identidade (anti-takeover).")
  (identidade-por-sub [this provedor sub])
  (criar-sessao! [this sessao] "Sessao opaca de login (custodia BFF): gera+INSERT o hash, devolve o SEGREDO CRU.")
  (resolver-sessao-por-segredo [this segredo] "segredo -> {:identidade-id :ente-id} | nil; desliza ocioso_ate em acerto.")
  (apagar-sessao! [this segredo] "DELETE por hash (logout), idempotente.")
  (emitir-credencial-agente! [this credencial]
    "Credencial delegada do agente (ADR-0010): INSERT do hash, devolve o SEGREDO CRU (uma vez so').")
  (resolver-credencial-agente [this segredo]
    "segredo -> {:execucao-id :ente-id :identidade-id :agente :publico :classes} | nil (expirada/revogada/desconhecida).")
  (revogar-credencial-agente! [this execucao-id] "Revoga a credencial da execucao; idempotente.")
  ;; TENANT (com-tenant*)
  (criar-vinculo! [this ente-id vinculo])
  (vinculos-de [this ente-id identidade-id])
  (ja-entrou-na-casa? [this ente-id identidade-id]
    "A identidade ja' entrou nesta Casa por algum vinculo? Guarda da troca de e-mail no reconvite.")
  (mudar-estado-vinculo! [this ente-id id estado])
  (adicionar-papel! [this ente-id papel])
  (papeis-de [this ente-id identidade-id])
  (casa-tem-papel-ativo? [this ente-id papel]
    "ADR-0019 Eixo 5: a Casa tem alguem com o `papel` e vinculo ATIVO de pessoa da Casa (ex.: 'juridico')?")
  (registrar-consentimento! [this ente-id consentimento])
  (revogar-consentimento! [this ente-id id])
  (consentimentos-ativos [this ente-id identidade-id])
  (conceder-acesso!
    [this ente-id vinculo papeis]
    [this ente-id vinculo papeis perfil-juridico]
    "Vinculo + papeis numa UNICA tx (§22.5 eixo D). Idempotente. E' o passo que ABRE A PORTA — por isso
    e' o ULTIMO do fluxo de provisionamento (spec §4.2 'acesso por ultimo'): antes dele, resolver-sessao
    nao acha vinculo ativo e ninguem entra. A aridade com `perfil-juridico` ({:qualificacao :oab}, ADR-0019)
    grava o perfil do papel `juridico` NA MESMA tx dos papeis (upsert: reconceder atualiza); nil = como a curta.")
  (salvar-perfil-juridico! [this ente-id identidade-id perfil]
    "ADR-0019: UPSERT do perfil do papel `juridico` ({:qualificacao :oab}) da identidade NESTA Casa.")
  (perfil-juridico [this ente-id identidade-id]
    "ADR-0019: {:nome :qualificacao :oab} do servidor juridico da Casa (nome pela leitura estreita `nome-por-id`,
    nunca CPF) ou nil se a identidade nao tem perfil juridico nesta Casa.")
  (concessao-agente [this ente-id agente]
    "B.8 (ADR-0013): a concessao ATIVA do agente institucional na Casa ({:agente :classes :concedida-por :concedida-em})
    ou nil.")
  (conceder-agente! [this ente-id concessao]
    "Liga o agente institucional ({:agente :classes :concedida-por}); idempotente (a ativa volta como esta').")
  (revogar-agente! [this ente-id agente revogada-por] "Desliga o agente institucional; idempotente.")
  (revogar-acesso! [this ente-id revogacao]
    "ADR-0005 (adendo): o `admin_ente` revoga um PAPEL concedido ({:identidade-id :papel :por :motivo}). Na MESMA tx: fecha
    a linha do papel (quem/quando/por que; historico fica) e, se a pessoa nao tem mais papel ATIVO nenhum na Casa,
    encerra os vinculos dela (menos o de cidadao) — sem vinculo ativo a sessao cai na proxima chamada. Depois, revoga as
    credenciais delegadas do agente dela. Devolve {:revogado? bool :vinculos-encerrados n}; revogado? false = nao havia
    esse papel ativo NESTA Casa (a RLS isola: o de outra Casa nem aparece).")
  (acessos-da-casa [this ente-id papeis]
    "Os acessos concedidos da Casa para os `papeis` dados, um por (pessoa, papel) — o mais recente, ativo ou revogado:
    [{:identidade-id :nome :papel :concedido-em :revogado-em :revogado-por-nome :motivo}], por nome. Nunca CPF.")
  (snapshot-ator [this ente-id identidade-id]
    "Snapshot de SESSAO numa UNICA tx (vinculo ATIVO + papeis). Devolve {:vinculo-ativo :papeis} ou nil
    se nao ha vinculo ativo. Composto AQUI (§3-bis) p/ resolver-sessao nao importar db/ direto.")
  (snapshot-cidadao [this ente-id identidade-id]
    "ADR-0015: o vinculo de CIDADAO ativo com zero papeis (sessao aberta pelo gov.br) | nil.")
  (garantir-vinculo-cidadao! [this ente-id identidade-id consentimento]
    "ADR-0015: cria o vinculo de cidadao e registra o consentimento da 1a vinculacao, numa tx; ja' existe -> nada.")
  (pessoas-da-casa [this ente-id]
    "ADR-0020: as pessoas com vinculo ATIVO de quem trabalha na Casa (nunca cidadao), com o nome: [{:identidade-id
     :nome :tipos #{..} :papeis #{..}}], por nome. Os vinculos/papeis sob a RLS da Casa; o nome pela leitura estreita
     supratenant, so' destes ids.")
  (registrar-primeiro-acesso! [this ente-id ator]
    "ADR-0016: na 1a vez que o vinculo entra, grava a data e emite `identidade.vinculo.primeiro_acesso` na MESMA tx.
    Nas seguintes, nada. true = foi o primeiro."))

(declare registrar-primeiro-acesso-impl)

(defrecord RepoIdentidadePg [datasource sessao-janela-ociosa-seg]
  RepoIdentidade
  (transacao [_ ente-id f] (tenancy/com-tenant* (:ds datasource) ente-id f))
  ;; supratenant
  (criar-identidade! [_ identidade] (id/inserir! (:ds datasource) identidade))
  (identidade-por-cpf [_ cpf] (id/por-cpf (:ds datasource) cpf))
  (identidade-por-id [_ id] (id/por-id (:ds datasource) id))
  (nome-por-id [_ id] (id/nome-por-id (:ds datasource) id))
  (pessoas-com-cpf-mascarado [_ ids] (id/com-cpf-mascarado-por-ids (:ds datasource) ids))
  (identidade-existe? [_ id] (id/existe? (:ds datasource) id))
  (vincular-externa! [_ ve] (id/vincular-externa! (:ds datasource) ve))
  (identidade-por-sub [_ provedor sub] (id/identidade-por-sub (:ds datasource) provedor sub))
  (criar-sessao! [_ sessao] (sess/inserir! (:ds datasource) sessao))
  (resolver-sessao-por-segredo [_ segredo] (sess/resolver! (:ds datasource) segredo sessao-janela-ociosa-seg))
  (apagar-sessao! [_ segredo] (sess/apagar! (:ds datasource) segredo))
  (emitir-credencial-agente! [_ c] (cred/inserir! (:ds datasource) c))
  (resolver-credencial-agente [_ segredo] (cred/resolver (:ds datasource) segredo))
  (revogar-credencial-agente! [_ execucao-id] (cred/revogar! (:ds datasource) execucao-id))
  ;; tenant
  (criar-vinculo! [this ente-id v] (transacao this ente-id #(vinc/criar! % v)))
  (vinculos-de [this ente-id ident] (transacao this ente-id #(vinc/vinculos-de % ente-id ident)))
  (ja-entrou-na-casa? [this ente-id ident] (transacao this ente-id #(vinc/ja-entrou? % ente-id ident)))
  (mudar-estado-vinculo! [this ente-id id estado] (transacao this ente-id #(vinc/mudar-estado! % id estado)))
  (adicionar-papel! [this ente-id p] (transacao this ente-id #(vinc/adicionar-papel! % p)))
  (papeis-de [this ente-id ident] (transacao this ente-id #(vinc/papeis-de % ente-id ident)))
  (casa-tem-papel-ativo? [this ente-id papel] (transacao this ente-id #(vinc/casa-tem-papel-ativo? % ente-id papel)))
  (registrar-consentimento! [this ente-id c] (transacao this ente-id #(vinc/registrar-consentimento! % c)))
  (revogar-consentimento! [this ente-id id] (transacao this ente-id #(vinc/revogar-consentimento! % id)))
  (consentimentos-ativos [this ente-id ident] (transacao this ente-id #(vinc/consentimentos-ativos % ente-id ident)))
  (conceder-acesso! [this ente-id v papeis] (conceder-acesso! this ente-id v papeis nil))
  (conceder-acesso! [this ente-id v papeis perfil]
    (transacao this ente-id
      (fn [tx]
        (let [vinculo-id (vinc/criar! tx v)]
          ;; Task 12 achado seguranca: `criar!` e' UPSERT idempotente que NUNCA toca `:estado` (Task 7,
          ;; deliberado) — re-conceder a um vinculo suspenso fica corretamente fail-closed no BANCO, mas
          ;; sem este check o caller (handler HTTP) nao teria como saber e mandaria convite + 201 como se
          ;; tivesse reativado. Lanca AQUI, dentro da mesma tx e ANTES de adicionar papeis, pra a tx
          ;; inteira dar rollback (nao sobra papel concedido a um vinculo que continua fechado).
          ;; ADR-0005 (adendo): `encerrado` e' o que a REVOGACAO do ultimo papel faz; o admin_ente conceder de novo e' o
          ;; ato que reabre. `suspenso` e' outra coisa (alguem suspendeu) e continua exigindo reativacao propria.
          (when (= "encerrado" (vinc/estado-de tx vinculo-id))
            (vinc/mudar-estado! tx vinculo-id "ativo"))
          (when-not (= "ativo" (vinc/estado-de tx vinculo-id))
            (throw (ex-info "vinculo existente nao esta ativo — reative via mudar-estado-vinculo! antes de conceder acesso"
                            {:tipo :conflito/vinculo-nao-ativo :vinculo-id vinculo-id})))
          (doseq [p papeis]
            (vinc/adicionar-papel! tx {:id (random-uuid) :ente-id ente-id
                                       :identidade-id (:identidade-id v) :papel p}))
          ;; ADR-0019: o perfil do `juridico` entra na MESMA tx dos papeis (papel sem perfil nao existe)
          (when perfil
            (pj/salvar! tx ente-id (:identidade-id v) perfil))
          {:vinculo-id vinculo-id}))))
  (salvar-perfil-juridico! [this ente-id identidade-id perfil]
    (transacao this ente-id #(pj/salvar! % ente-id identidade-id perfil)))
  (perfil-juridico [this ente-id identidade-id]
    (when-let [perfil (transacao this ente-id #(pj/buscar % ente-id identidade-id))]
      (assoc perfil :nome (:nome (nome-por-id this identidade-id)))))
  (concessao-agente [this ente-id agente] (transacao this ente-id #(concessao/ativa % ente-id agente)))
  (conceder-agente! [this ente-id c] (transacao this ente-id #(concessao/conceder! % (assoc c :ente-id ente-id))))
  (revogar-agente! [this ente-id agente por] (transacao this ente-id #(concessao/revogar! % ente-id agente por)))
  (revogar-acesso! [this ente-id {:keys [identidade-id papel por motivo]}]
    (let [r (transacao this ente-id
              (fn [tx]
                (if (vinc/revogar-papel! tx ente-id identidade-id papel por motivo)
                  {:revogado? true
                   :vinculos-encerrados (if (empty? (vinc/papeis-de tx ente-id identidade-id))
                                          (vinc/encerrar-vinculos-da-casa! tx ente-id identidade-id)
                                          0)}
                  {:revogado? false :vinculos-encerrados 0})))]
      (when (:revogado? r) (cred/revogar-da-pessoa! (:ds datasource) ente-id identidade-id))
      r))
  (acessos-da-casa [this ente-id papeis]
    (let [linhas (transacao this ente-id #(vinc/acessos % ente-id papeis))
          nomes (id/nomes-por-ids (:ds datasource) (mapcat (juxt :identidade-id :revogado-por) linhas))]
      (->> linhas
           (keep (fn [l]
                   (when-let [n (get nomes (:identidade-id l))]
                     (-> l
                         (assoc :nome n :revogado-por-nome (get nomes (:revogado-por l)))
                         (dissoc :revogado-por)))))
           (sort-by (juxt #(some-> (:nome %) str/lower-case) :papel))
           vec)))
  (snapshot-ator [this ente-id identidade-id]
    (transacao this ente-id
      (fn [tx]
        (when-let [ativo (->> (vinc/vinculos-de tx ente-id identidade-id)
                              (filter #(= "ativo" (:estado %)))
                              first)]
          {:vinculo-ativo ativo :papeis (vinc/papeis-de tx ente-id identidade-id)}))))
  (snapshot-cidadao [this ente-id identidade-id]
    (transacao this ente-id
      (fn [tx]
        (when-let [v (->> (vinc/vinculos-de tx ente-id identidade-id)
                          (filter #(and (= "cidadao" (:tipo %)) (= "ativo" (:estado %))))
                          first)]
          {:vinculo-ativo v :papeis #{}}))))
  (garantir-vinculo-cidadao! [this ente-id identidade-id {:keys [finalidade base-legal versao-termo]}]
    (transacao this ente-id
      (fn [tx]
        (when-not (some #(= "cidadao" (:tipo %)) (vinc/vinculos-de tx ente-id identidade-id))
          (vinc/criar! tx {:id (random-uuid) :ente-id ente-id :identidade-id identidade-id :tipo "cidadao"})
          (vinc/registrar-consentimento! tx {:id (random-uuid) :ente-id ente-id :identidade-id identidade-id
                                             :finalidade finalidade :base-legal base-legal
                                             :versao-termo versao-termo})))))
  (pessoas-da-casa [this ente-id]
    (let [ps (transacao this ente-id #(vinc/pessoas-ativas % ente-id))
          nomes (id/nomes-por-ids (:ds datasource) (map :identidade-id ps))]
      (->> ps
           (keep (fn [p] (when-let [n (get nomes (:identidade-id p))] (assoc p :nome n))))
           (sort-by (juxt #(some-> (:nome %) str/lower-case) (comp str :identidade-id)))
           vec)))
  (registrar-primeiro-acesso! [this ente-id ator] (registrar-primeiro-acesso-impl this ente-id ator)))

;; o bus do outbox e' sem estado (grava na tx que recebe) — mesmo uso inline do repositorio de transparencia
(defn- registrar-primeiro-acesso-impl [repo ente-id {:keys [vinculo-ativo-id identidade-id tipo-vinculo papeis]}]
  (transacao repo ente-id
    (fn [tx]
      (let [primeiro? (vinc/marcar-primeiro-acesso! tx vinculo-ativo-id)]
        (when primeiro?
          (eventos/emitir! (outbox/bus) tx
                           (ev-vinculo/primeiro-acesso ente-id {:vinculo-id vinculo-ativo-id :identidade-id identidade-id
                                                                :tipo-vinculo (str tipo-vinculo)
                                                                :papeis (vec (sort (map name papeis)))})))
        primeiro?))))

(defn repositorio
  "Cria o Component (recebe :datasource via `using`). Aridade-1 seta a janela de deslize de ociosidade da
  sessao (segundos) — `sistema/montar` injeta `(* 60 (:sessao :ociosa-min config))`, mesma fonte que o mint
  usa p/ o ocioso-ate inicial (fonte unica). Aridade-0 default 1800s/30min = fallback só p/ testes que
  constroem o repo direto sem config."
  ([] (repositorio 1800))
  ([sessao-janela-ociosa-seg] (->RepoIdentidadePg nil sessao-janela-ociosa-seg)))
