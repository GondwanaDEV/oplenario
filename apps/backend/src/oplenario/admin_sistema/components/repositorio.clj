(ns oplenario.admin-sistema.components.repositorio
  "Component de PERSISTENCIA do `admin_sistema` (ADR-0016). SUPRATENANT: tudo roda sobre o `:ds` direto (o pool
  herda o role oplenario_operacao), nunca via com-tenant* — nao ha' Casa aqui, este modulo e' quem as emite.
  `transacao` agrupa varias acoes numa tx so' (ex.: provisionar + registrar a atuacao)."
  (:require [next.jdbc :as jdbc]
            [oplenario.admin-sistema.db.atuacao :as atuacao]
            [oplenario.admin-sistema.db.ente :as ente]
            [oplenario.admin-sistema.db.operador :as op]
            [oplenario.admin-sistema.db.restricao :as restricao]
            [oplenario.admin-sistema.diplomat.producers :as producers]
            [oplenario.admin-sistema.logic :as logic])
  (:import (java.time Instant)))

(defprotocol RepoAdminSistema
  (transacao [this f] "Roda (f tx) numa tx supratenant.")
  (operador-por-id [this id])
  (operador-por-email [this email])
  (criar-operador! [this operador] "{:id :email :nome} -> o operador; e-mail ja' cadastrado devolve o existente.")
  (desligar-operador! [this id] "Estado 'desligado' + derruba as sessoes do console, numa tx.")
  (criar-sessao-operador! [this sessao] "{:operador-id :expira-em :ocioso-ate} -> segredo CRU.")
  (resolver-sessao-operador [this segredo] "segredo -> {:operador-id} | nil; desliza a ociosidade.")
  (apagar-sessao-operador! [this segredo])
  (registrar-atuacao! [this registro] "{:operador-id :ente-id :acao :detalhe} -> registro selado.")
  (atuacao-do-ente [this ente-id limite])
  ;; registro de Casas (12.1)
  (registrar-casa! [this casa ator]
    "Registra a Casa em 'provisionar' e sela a atuacao 'casa-provisionada', numa tx. Devolve a Casa.")
  (casa-por-id [this ente-id])
  (listar-casas [this])
  (marcar-primeiro-admin! [this ente-id identidade-id])
  (marcar-convite! [this ente-id operador-id reenvio?] "Data do convite + atuacao, numa tx.")
  ;; ADR-0018 (fatia 1): suspender, reativar, iniciar o encerramento. Cada transicao e' UMA tx: o registro, o
  ;; pedido, a atuacao selada e o evento no outbox caem juntos ou nao caem.
  (pedir-restricao! [this pedido agora]
    "{:ente-id :acao :motivo :justificativa :pedido-por} -> {:pedido :casa}. O incidente ja' suspende aqui.")
  (aprovar-pedido! [this pedido-id operador-id justificativa sessao-em-curso?]
    "O 2o operador aprova -> {:pedido :casa :efeito (:imediato|:agendado|:ja-efetivado)}.")
  (recusar-pedido! [this pedido-id operador-id justificativa]
    "Outro operador recusa (quem pediu RETIRA) -> {:pedido :casa}. O incidente recusado devolve a Casa a ativa.")
  (reativar-casa! [this ente-id operador-id justificativa]
    "suspenso -> ativo (um operador, com motivo), ou cancela a suspensao agendada. -> a Casa.")
  (efetivar-pendentes! [this ente-id agora sessao-em-curso?]
    "Verificacao PREGUICOSA (sem job): o incidente sem 2a aprovacao vencido volta a Casa a ativa; a suspensao
    agendada entra quando nao ha' mais sessao em curso (`sessao-em-curso?` = fn [] -> bool). -> a Casa.")
  (pedidos-abertos [this] "A fila 'aguardando 2o operador' de todas as Casas.")
  (pedido-por-id [this pedido-id])
  (pedido-aberto-da-casa [this ente-id]))

;; ---------------------------------------------------------------------------------------------
;; ADR-0018 — as transicoes, sobre a tx. `conflito!` vira 409 na borda (com o porque).
;; ---------------------------------------------------------------------------------------------

(defn- conflito! [msg] (throw (ex-info msg {:tipo :admin-sistema/conflito})))

(defn- casa-ou-404! [tx ente-id]
  (or (ente/por-id tx ente-id) (throw (ex-info "Casa nao encontrada" {:tipo :admin-sistema/nao-encontrada}))))

(defn- efetivar-suspensao!
  "A Casa fica suspensa agora por `pedido`: registro + atuacao + `admin_sistema.casa.suspensa`. `efeito` vai no selo."
  [tx {:keys [id ente-id] :as pedido} operador-id efeito]
  (let [motivo (logic/motivo-da-casa pedido)
        casa (or (ente/suspender! tx ente-id motivo)
                 (conflito! "a Casa nao esta' num estado que permita suspender"))]
    (restricao/marcar-efetivado! tx id)
    (atuacao/registrar! tx {:operador-id operador-id :ente-id ente-id :acao "casa-suspensa"
                            :detalhe {:motivo motivo :pedido (str id) :efeito (name efeito)}})
    (producers/emitir-suspensa! tx ente-id {:motivo motivo :desde (str (:restrita-desde casa)) :pedido-id id})
    casa))

(defn- efetivar-reativacao!
  [tx ente-id operador-id por detalhe]
  (let [antes (ente/por-id tx ente-id)
        casa (or (ente/reativar! tx ente-id) (conflito! "a Casa nao esta' suspensa"))]
    (atuacao/registrar! tx {:operador-id operador-id :ente-id ente-id :acao "casa-reativada"
                            :detalhe (merge {:por por :motivo-anterior (:motivo-restricao antes)} detalhe)})
    (producers/emitir-reativada! tx ente-id {:em (str (:atualizado-em casa)) :motivo-anterior (:motivo-restricao antes)
                                             :por por})
    casa))

(defn- acao-do-selo
  "O nome da acao na atuacao: `suspensao-pedida|aprovada|recusada` ou `encerramento-pedido|aprovado|recusado`."
  [{:keys [acao]} sufixo]
  (if (= "encerrar" acao)
    (str "encerramento-" (subs sufixo 0 (dec (count sufixo))) "o")
    (str "suspensao-" sufixo)))

(defn- pedir-em-tx! [tx {:keys [ente-id acao motivo justificativa pedido-por]} ^Instant agora]
  (let [casa (casa-ou-404! tx ente-id)]
    (when-not (or (= "ativo" (:estado casa)) (and (= "encerrar" acao) (= "suspenso" (:estado casa))))
      (conflito! (if (= "suspenso" (:estado casa)) "a Casa ja' esta' suspensa" "so' uma Casa ativa pode ser suspensa")))
    (when (= logic/motivo-do-encerramento (:motivo-restricao casa))
      (conflito! "o encerramento desta Casa ja' esta' em curso"))
    (when (:suspensao-agendada casa)
      (conflito! "ja' ha' uma suspensao agendada para o fim da sessao em curso"))
    (when (restricao/aberto-da-casa tx ente-id)
      (conflito! "ja' ha' um pedido esperando o 2o operador nesta Casa"))
    (let [incidente? (logic/um-operador-basta? acao motivo)
          pedido (restricao/inserir! tx {:id (random-uuid) :ente-id ente-id :acao acao :motivo motivo
                                         :justificativa justificativa :pedido-por pedido-por
                                         :confirmar-ate (logic/confirmar-ate acao motivo agora)
                                         :efetivado? incidente?})]
      (atuacao/registrar! tx {:operador-id pedido-por :ente-id ente-id :acao (acao-do-selo pedido "pedida")
                              :detalhe {:motivo motivo :justificativa justificativa :pedido (str (:id pedido))}})
      {:pedido pedido
       :casa (if incidente? (efetivar-suspensao! tx pedido pedido-por :incidente) casa)})))

(defn- pedido-aberto-ou-409! [tx pedido-id]
  (let [p (or (restricao/por-id tx pedido-id) (throw (ex-info "pedido nao encontrado" {:tipo :admin-sistema/nao-encontrada})))]
    (when-not (= "aguardando" (:estado p)) (conflito! "este pedido ja' foi decidido"))
    p))

(defn- aprovar-em-tx! [tx pedido-id operador-id justificativa sessao-em-curso?]
  (let [p (pedido-aberto-ou-409! tx pedido-id)]
    (when (= operador-id (:pedido-por p))
      (conflito! "quem pediu nao aprova o proprio pedido: outro operador precisa aprovar"))
    (let [casa (ente/por-id tx (:ente-id p))
          ;; so' uma Casa ATIVA tem plenario a proteger; a ja' suspensa (encerramento) muda de motivo na hora
          efeito (logic/efeito-da-aprovacao p (and sessao-em-curso? (= "ativo" (:estado casa))))
          p (or (restricao/decidir! tx pedido-id {:estado "aprovado" :decidido-por operador-id :justificativa justificativa})
                (conflito! "este pedido ja' foi decidido"))]
      (atuacao/registrar! tx {:operador-id operador-id :ente-id (:ente-id p) :acao (acao-do-selo p "aprovada")
                              :detalhe {:pedido (str pedido-id) :efeito (name efeito)}})
      {:pedido p :efeito efeito
       :casa (case efeito
               :ja-efetivado (ente/por-id tx (:ente-id p))
               :imediato (efetivar-suspensao! tx p operador-id :imediato)
               :agendado (let [c (or (ente/agendar-suspensao! tx (:ente-id p) pedido-id)
                                     (conflito! "a Casa nao esta' ativa para agendar a suspensao"))]
                           (atuacao/registrar! tx {:operador-id operador-id :ente-id (:ente-id p)
                                                   :acao "suspensao-agendada"
                                                   :detalhe {:pedido (str pedido-id) :ate "o encerramento da sessao em curso"}})
                           c))})))

(defn- recusar-em-tx! [tx pedido-id operador-id justificativa]
  (let [p (pedido-aberto-ou-409! tx pedido-id)
        retirada? (= operador-id (:pedido-por p))
        p (or (if retirada?
                (restricao/fechar-sem-decisao! tx pedido-id "retirado")
                (restricao/decidir! tx pedido-id {:estado "recusado" :decidido-por operador-id :justificativa justificativa}))
              (conflito! "este pedido ja' foi decidido"))]
    (atuacao/registrar! tx {:operador-id operador-id :ente-id (:ente-id p)
                            :acao (if retirada? "pedido-retirado" (acao-do-selo p "recusada"))
                            :detalhe (cond-> {:pedido (str pedido-id)} justificativa (assoc :justificativa justificativa))})
    {:pedido p
     ;; o incidente ja' tinha suspendido: sem a 2a aprovacao, a Casa volta a ativa na hora
     :casa (if (:efetivado-em p)
             (efetivar-reativacao! tx (:ente-id p) operador-id "suspensao_recusada" {:pedido (str pedido-id)})
             (ente/por-id tx (:ente-id p)))}))

(defn- reativar-em-tx! [tx ente-id operador-id justificativa]
  (let [casa (casa-ou-404! tx ente-id)]
    (cond
      (= "suspenso" (:estado casa))
      (do (when-let [p (restricao/aberto-da-casa tx ente-id)]
            ;; o pedido que ainda esperava a 2a aprovacao (o incidente) perde o objeto
            (when (:efetivado-em p) (restricao/fechar-sem-decisao! tx (:id p) "retirado")))
          (efetivar-reativacao! tx ente-id operador-id "operador" {:justificativa justificativa}))

      (:suspensao-agendada casa)
      (let [c (ente/cancelar-agendamento! tx ente-id)]
        (atuacao/registrar! tx {:operador-id operador-id :ente-id ente-id :acao "suspensao-agendada-cancelada"
                                :detalhe {:pedido (str (:suspensao-agendada casa)) :justificativa justificativa}})
        c)

      :else (conflito! "a Casa nao esta' suspensa"))))

(defn- efetivar-pendentes-em-tx! [tx ente-id agora sessao-em-curso?]
  (when-let [casa (ente/por-id tx ente-id)]
    (let [p (restricao/aberto-da-casa tx ente-id)]
      (cond
        (and p (logic/incidente-vencido? p agora))
        (do (restricao/fechar-sem-decisao! tx (:id p) "expirado")
            (if (= "suspenso" (:estado casa))
              (efetivar-reativacao! tx ente-id nil "incidente_sem_segunda_aprovacao" {:pedido (str (:id p))})
              casa))

        (and (:suspensao-agendada casa) (= "ativo" (:estado casa)) (not (sessao-em-curso?)))
        (efetivar-suspensao! tx (restricao/por-id tx (:suspensao-agendada casa)) nil :apos-a-sessao)

        :else casa))))

(defn- pendencia?
  "Barato, sem tx: ha' algo que a verificacao preguicosa pode efetivar nesta Casa?"
  [ds casa ^Instant agora]
  (or (:suspensao-agendada casa)
      (when (= "suspenso" (:estado casa))
        (some-> (restricao/aberto-da-casa ds (:ente-id casa)) (logic/incidente-vencido? agora)))))

(defrecord RepoAdminSistemaPg [datasource sessao-janela-ociosa-seg]
  RepoAdminSistema
  (transacao [_ f] (jdbc/with-transaction [tx (:ds datasource)] (f tx)))
  (operador-por-id [_ id] (op/por-id (:ds datasource) id))
  (operador-por-email [_ email] (op/por-email (:ds datasource) email))
  (criar-operador! [this o] (transacao this #(op/inserir! % o)))
  (desligar-operador! [this id] (transacao this #(op/desligar! % id)))
  (criar-sessao-operador! [_ s] (op/inserir-sessao! (:ds datasource) s))
  (resolver-sessao-operador [_ segredo] (op/resolver-sessao! (:ds datasource) segredo sessao-janela-ociosa-seg))
  (apagar-sessao-operador! [_ segredo] (op/apagar-sessao! (:ds datasource) segredo))
  (registrar-atuacao! [this r] (transacao this #(atuacao/registrar! % r)))
  (atuacao-do-ente [_ ente-id limite] (atuacao/do-ente (:ds datasource) ente-id limite))
  (registrar-casa! [this casa {:keys [operador-id]}]
    (transacao this
      (fn [tx]
        (ente/inserir! tx (assoc casa :provisionada-por operador-id))
        (atuacao/registrar! tx {:operador-id operador-id :ente-id (:ente-id casa) :acao "casa-provisionada"
                                :detalhe {:nome (:nome casa) :uf (:uf casa) :municipio (:municipio-ibge casa)}})
        (ente/por-id tx (:ente-id casa)))))
  (casa-por-id [_ ente-id] (ente/por-id (:ds datasource) ente-id))
  (listar-casas [_] (ente/listar (:ds datasource)))
  (marcar-primeiro-admin! [_ ente-id identidade-id] (ente/marcar-primeiro-admin! (:ds datasource) ente-id identidade-id))
  (marcar-convite! [this ente-id operador-id reenvio?]
    (transacao this
      (fn [tx]
        (ente/marcar-convite! tx ente-id)
        (atuacao/registrar! tx {:operador-id operador-id :ente-id ente-id
                                :acao (if reenvio? "convite-reenviado" "convite-enviado")}))))
  (pedir-restricao! [this pedido agora] (transacao this #(pedir-em-tx! % pedido agora)))
  (aprovar-pedido! [this pedido-id operador-id justificativa sessao-em-curso?]
    (transacao this #(aprovar-em-tx! % pedido-id operador-id justificativa sessao-em-curso?)))
  (recusar-pedido! [this pedido-id operador-id justificativa]
    (transacao this #(recusar-em-tx! % pedido-id operador-id justificativa)))
  (reativar-casa! [this ente-id operador-id justificativa]
    (transacao this #(reativar-em-tx! % ente-id operador-id justificativa)))
  (efetivar-pendentes! [this ente-id agora sessao-em-curso?]
    (let [casa (ente/por-id (:ds datasource) ente-id)]
      (if (and casa (pendencia? (:ds datasource) casa agora))
        (transacao this (fn [tx]
                          ;; serializa com as outras transicoes desta Casa (a leitura acima foi sem lock)
                          (jdbc/execute-one! tx ["SELECT 1 FROM admin_sistema.ente WHERE ente_id = ? FOR UPDATE" ente-id])
                          (efetivar-pendentes-em-tx! tx ente-id agora sessao-em-curso?)))
        casa)))
  (pedidos-abertos [_] (restricao/abertos (:ds datasource)))
  (pedido-por-id [_ pedido-id] (restricao/por-id (:ds datasource) pedido-id))
  (pedido-aberto-da-casa [_ ente-id] (restricao/aberto-da-casa (:ds datasource) ente-id)))

(defn ativar-casa-em-tx!
  "Consumidor (na tx do relay): a Casa passa a 'ativo' quando o 1o administrador entra. Sela a atuacao sem operador
  (foi a Casa, nao a Operacao). Idempotente: so' a primeira vez ativa."
  [tx ente-id detalhe]
  (when (ente/ativar! tx ente-id)
    (atuacao/registrar! tx {:ente-id ente-id :acao "casa-ativada" :detalhe detalhe})
    true))

(defn repositorio
  "Aridade-1 recebe a janela de ociosidade da sessao do console (segundos) — a mesma fonte que o mint usa
  (`:operacao :sessao :ociosa-min`). Aridade-0 (testes) = 15 min."
  ([] (repositorio 900))
  ([janela-ociosa-seg] (->RepoAdminSistemaPg nil janela-ociosa-seg)))
