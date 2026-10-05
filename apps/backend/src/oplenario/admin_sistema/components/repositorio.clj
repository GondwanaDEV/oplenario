(ns oplenario.admin-sistema.components.repositorio
  "Component de PERSISTENCIA do `admin_sistema` (ADR-0016). SUPRATENANT: tudo roda sobre o `:ds` direto (o pool
  herda o role oplenario_operacao), nunca via com-tenant* — nao ha' Casa aqui, este modulo e' quem as emite.
  `transacao` agrupa varias acoes numa tx so' (ex.: provisionar + registrar a atuacao)."
  (:require [next.jdbc :as jdbc]
            [oplenario.admin-sistema.db.atuacao :as atuacao]
            [oplenario.admin-sistema.db.ente :as ente]
            [oplenario.admin-sistema.db.exportacao :as exp]
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
  (tentativas-sem-desfecho [this antes-de]
    "ADR-0017 (adendo de 05/10/2026): as tentativas da Operacao (entrada no console, comando sobre uma Casa) mais
    antigas que `antes-de` (Instant) que nenhum desfecho aponta. Conferencia, nao tela.")
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
  (pedido-aberto-da-casa [this ente-id])
  ;; ADR-0018 (fatia 2): ENCERRAR. A exportacao (9.6) e a confirmacao de recebimento, o destino do acervo, o pedido de
  ;; apagamento (two-person rule de novo) e o estado final `encerrado`. Cada passo e' UMA tx com a atuacao selada.
  (iniciar-exportacao! [this pedido agora]
    "{:ente-id :solicitante-tipo (operador|admin_ente) :solicitante} -> a linha `gerando` (uma por Casa). O operador so'
    manda gerar durante o encerramento; o admin_ente, a qualquer momento (portabilidade).")
  (concluir-exportacao! [this exportacao-id resultado] "{:chave :sha256 :bytes :manifesto} -> a linha `pronta`.")
  (falhar-exportacao! [this exportacao-id erro] "-> a linha `falhou`, com o erro.")
  (confirmar-recebimento! [this exportacao-id confirmacao]
    "{:ente-id (a Casa de quem confirma, nil = operador) :tipo (admin_ente|oficio) :por :texto :sha256 :em} -> a
    exportacao confirmada. Uma vez so'.")
  (exportacoes-da-casa [this ente-id] "As exportacoes da Casa, mais recente primeiro.")
  (exportacao-por-id [this exportacao-id])
  (definir-destino-acervo! [this ente-id operador-id url] "Para onde foi o acervo publico (Eixo 4.4 c) -> a Casa.")
  (pedir-apagamento! [this ente-id operador-id justificativa agora]
    "Eixo 4.5: so' depois da guarda de 90 dias desde a confirmacao -> {:pedido :casa}.")
  (aprovar-apagamento! [this pedido-id operador-id justificativa agora]
    "O 2o operador aprova o apagamento (as mesmas salvaguardas de novo) -> {:pedido :casa}. Nao apaga: quem apaga e'
    o seam do host, depois desta tx.")
  (concluir-apagamento! [this pedido-id resumo operador-id agora]
    "O apagamento terminou: a Casa vira `encerrado` com o resumo + o hash da exportacao entregue, selado, e o evento
    `admin_sistema.casa.encerrada` sai -> a Casa.")
  (registrar-apagamento-interrompido! [this pedido-id operador-id erro resumo-parcial]
    "Sela que o apagamento parou no meio. `resumo-parcial` (ou nil) = o que ja' foi apagado ate' aqui (o banco apaga uma vez
    so': a retomada conta zero linhas), somado de volta na conclusao.")
  (apagamento-pendente [this ente-id] "O pedido de apagamento aprovado que ainda nao terminou (retomavel), ou nil.")
  (reservar-apagamento! [this ente-id operador-id agora]
    "Reserva a execucao do apagamento (uma por Casa, entre instancias) e fecha a Casa na 1a vez -> a Casa, ou nil se
    outra execucao esta' rodando.")
  (liberar-apagamento! [this ente-id] "Solta a reserva de execucao (a Casa segue fechada).")
  (apagamento-efetivado [this ente-id] "O pedido de apagamento que encerrou a Casa, ou nil."))

;; ---------------------------------------------------------------------------------------------
;; ADR-0018 — as transicoes, sobre a tx. `conflito!` vira 409 na borda (com o porque).
;; ---------------------------------------------------------------------------------------------

(defn- conflito!
  "409 na borda. `causa` (opcional) e' o codigo estavel que a tela le; `extra` vai junto no corpo."
  ([msg] (throw (ex-info msg {:tipo :admin-sistema/conflito})))
  ([msg causa] (conflito! msg causa nil))
  ([msg causa extra] (throw (ex-info msg {:tipo :admin-sistema/conflito :causa causa :extra extra}))))

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
  "O nome da acao na atuacao: `suspensao-pedida|aprovada|recusada`, `encerramento-pedido|aprovado|recusado` ou
  `apagamento-pedido|aprovado|recusado`."
  [{:keys [acao]} sufixo]
  (let [masculino (str (subs sufixo 0 (dec (count sufixo))) "o")]
    (case acao
      "encerrar" (str "encerramento-" masculino)
      "apagar" (str "apagamento-" masculino)
      (str "suspensao-" sufixo))))

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
    (when (= "apagar" (:acao p))
      (conflito! "o pedido de apagamento se aprova pelo caminho do apagamento"))
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
    ;; ADR-0018 (fatia 2): o apagamento aprovado nao tem volta (pode ter parado no meio); o pedido que espera o 2o
    ;; operador se recusa ou se retira antes
    (when (restricao/apagamento-aprovado-pendente tx ente-id)
      (conflito! "o apagamento desta Casa ja' foi aprovado e nao tem volta — retome-o" "apagamento-aprovado"))
    (when (= "apagar" (:acao (restricao/aberto-da-casa tx ente-id)))
      (conflito! "ha' um pedido de apagamento esperando o 2o operador — recuse-o ou retire-o antes de reativar"
                 "apagamento-pedido"))
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


;; ---------------------------------------------------------------------------------------------
;; ADR-0018 (fatia 2): ENCERRAR — a exportacao, a confirmacao, a guarda, o apagamento e o estado final.
;; ---------------------------------------------------------------------------------------------

(defn- travar-casa!
  "Serializa as transicoes desta Casa (a leitura do estado e a escrita que depende dele caem juntas)."
  [tx ente-id]
  (jdbc/execute-one! tx ["SELECT 1 FROM admin_sistema.ente WHERE ente_id = ? FOR UPDATE" ente-id]))

(defn- iniciar-exportacao-em-tx! [tx {:keys [ente-id solicitante-tipo solicitante]} ^Instant agora]
  (travar-casa! tx ente-id)
  (let [casa (casa-ou-404! tx ente-id)]
    (case solicitante-tipo
      "operador" (when-not (logic/em-encerramento? casa)
                   (conflito! (str "a Operacao so' manda gerar a exportacao com o encerramento em curso — fora dele, "
                                  "quem gera e' o administrador da Casa")
                             "fora-do-encerramento"))
      "admin_ente" (when-not (#{"ativo" "suspenso"} (:estado casa))
                     (conflito! "esta Casa nao esta' num estado que permita exportar" "estado-da-casa")))
    (when-let [g (exp/gerando-da-casa tx ente-id)]
      (if (logic/geracao-abandonada? g agora)
        (do (exp/falhar! tx (:id g) "a geracao nao terminou (o processo foi interrompido)")
            (atuacao/registrar! tx {:ente-id ente-id :acao "exportacao-falhou"
                                    :detalhe {:exportacao (str (:id g)) :erro "interrompida"}}))
        (conflito! "ja' ha' uma exportacao sendo gerada para esta Casa — espere ela terminar" "exportacao-em-andamento")))
    (let [e (exp/inserir! tx {:id (random-uuid) :ente-id ente-id :solicitada-por-tipo solicitante-tipo
                              :solicitada-por solicitante})]
      (atuacao/registrar! tx {:operador-id (when (= "operador" solicitante-tipo) solicitante) :ente-id ente-id
                              :acao "exportacao-solicitada"
                              :detalhe {:exportacao (str (:id e)) :por solicitante-tipo}})
      e)))

(defn- concluir-exportacao-em-tx! [tx id resultado]
  (when-let [e (exp/concluir! tx id resultado)]
    (atuacao/registrar! tx {:ente-id (:ente-id e) :acao "exportacao-pronta"
                            :detalhe {:exportacao (str id) :sha256 (:sha256 e) :bytes (:bytes e)}})
    e))

(defn- falhar-exportacao-em-tx! [tx id erro]
  (when-let [e (exp/falhar! tx id erro)]
    (atuacao/registrar! tx {:ente-id (:ente-id e) :acao "exportacao-falhou"
                            :detalhe {:exportacao (str id) :erro erro}})
    e))

(defn- confirmar-em-tx! [tx id {:keys [ente-id tipo por texto sha256 em]}]
  (let [e (or (exp/por-id tx id) (throw (ex-info "exportacao nao encontrada" {:tipo :admin-sistema/nao-encontrada})))]
    ;; o admin_ente de OUTRA Casa nao enxerga esta exportacao (404, nunca 403: nem a existencia vaza)
    (when (and ente-id (not= ente-id (:ente-id e)))
      (throw (ex-info "exportacao nao encontrada" {:tipo :admin-sistema/nao-encontrada})))
    (travar-casa! tx (:ente-id e))
    (when (= "encerrado" (:estado (ente/por-id tx (:ente-id e))))
      (conflito! "a Casa ja' esta' encerrada" "casa-encerrada"))
    (when-not (= "pronta" (:estado e))
      (conflito! "so' se confirma o recebimento de uma exportacao pronta" "exportacao-nao-pronta"))
    (when (:confirmada-em e)
      (conflito! "o recebimento desta exportacao ja' foi confirmado — a confirmacao nao se desfaz" "ja-confirmada"))
    (when (and sha256 (not= sha256 (:sha256 e)))
      (conflito! "o codigo informado nao e' o desta exportacao" "codigo-nao-confere"))
    (let [c (or (exp/confirmar! tx id {:tipo tipo :por por :texto texto :em em})
                (conflito! "o recebimento desta exportacao ja' foi confirmado" "ja-confirmada"))]
      (atuacao/registrar! tx {:operador-id (when (= "oficio" tipo) por) :ente-id (:ente-id c)
                              :acao "recebimento-confirmado"
                              :detalhe (cond-> {:exportacao (str id) :sha256 (:sha256 c) :por tipo}
                                         texto (assoc :oficio texto))})
      c)))

(defn- confirmacao-vigente
  "A confirmacao que vale para o encerramento desta Casa (ver `logic/confirmacao-do-encerramento`)."
  [tx casa]
  (logic/confirmacao-do-encerramento (exp/da-casa tx (:ente-id casa) 500) (:restrita-desde casa)))

(defn- salvaguardas-do-apagamento!
  "Eixo 4.5: encerramento em curso, exportacao confirmada, guarda de 90 dias cumprida. Devolve a confirmacao."
  [tx casa ^Instant agora]
  (when (= "encerrado" (:estado casa)) (conflito! "a Casa ja' esta' encerrada" "casa-encerrada"))
  (when-not (logic/em-encerramento? casa)
    (conflito! "so' se apaga uma Casa com o encerramento em curso" "fora-do-encerramento"))
  (let [conf (confirmacao-vigente tx casa)]
    (when-not conf
      (conflito! "a Casa ainda nao confirmou o recebimento da exportacao — sem confirmacao, nao se apaga"
                 "sem-confirmacao"))
    (when-not (logic/guarda-cumprida? (:confirmada-em conf) agora)
      (conflito! "a janela de guarda de 90 dias desde a confirmacao ainda nao passou" "guarda-em-curso"
                 {:apagamento-possivel-em (str (logic/apagamento-possivel-em (:confirmada-em conf)))}))
    conf))

(defn- pedir-apagamento-em-tx! [tx ente-id operador-id justificativa ^Instant agora]
  (travar-casa! tx ente-id)
  (let [casa (casa-ou-404! tx ente-id)
        conf (salvaguardas-do-apagamento! tx casa agora)]
    (when (restricao/apagamento-aprovado-pendente tx ente-id)
      (conflito! "o apagamento desta Casa ja' foi aprovado — retome-o" "apagamento-aprovado"))
    (when (restricao/aberto-da-casa tx ente-id)
      (conflito! "ja' ha' um pedido esperando o 2o operador nesta Casa" "pedido-aberto"))
    (let [p (restricao/inserir! tx {:id (random-uuid) :ente-id ente-id :acao "apagar" :motivo "fim_da_guarda"
                                    :justificativa justificativa :pedido-por operador-id})]
      (atuacao/registrar! tx {:operador-id operador-id :ente-id ente-id :acao "apagamento-pedido"
                              :detalhe {:pedido (str (:id p)) :justificativa justificativa
                                        :exportacao-sha256 (:sha256 conf)}})
      {:pedido p :casa casa})))

(defn- aprovar-apagamento-em-tx! [tx pedido-id operador-id justificativa ^Instant agora]
  (let [p (pedido-aberto-ou-409! tx pedido-id)]
    (when-not (= "apagar" (:acao p)) (conflito! "este pedido nao e' de apagamento"))
    (when (= operador-id (:pedido-por p))
      (conflito! "quem pediu nao aprova o proprio pedido: outro operador precisa aprovar" "mesmo-operador"))
    (travar-casa! tx (:ente-id p))
    (let [casa (ente/por-id tx (:ente-id p))
          conf (salvaguardas-do-apagamento! tx casa agora)
          p (or (restricao/decidir! tx pedido-id {:estado "aprovado" :decidido-por operador-id :justificativa justificativa})
                (conflito! "este pedido ja' foi decidido"))]
      (atuacao/registrar! tx {:operador-id operador-id :ente-id (:ente-id p) :acao "apagamento-aprovado"
                              :detalhe {:pedido (str pedido-id) :exportacao-sha256 (:sha256 conf)}})
      {:pedido p :casa casa})))

(defn- pares-ordenados
  "Um mapa de contagens -> [\"chave=valor\" ...] ordenado. A atuacao sela o detalhe como ele volta do jsonb, e o jsonb
  reordena chaves de mapas aninhados: em vetor de texto, a ordem e' a nossa e o selo confere."
  [m]
  (when (map? m) (vec (sort (map (fn [[k v]] (str (name k) "=" v)) m)))))

(defn- concluir-apagamento-em-tx! [tx pedido-id resumo operador-id ^Instant agora]
  (let [p (or (restricao/por-id tx pedido-id) (throw (ex-info "pedido nao encontrado" {:tipo :admin-sistema/nao-encontrada})))]
    (when-not (and (= "apagar" (:acao p)) (= "aprovado" (:estado p)))
      (conflito! "este pedido nao e' um apagamento aprovado"))
    (travar-casa! tx (:ente-id p))
    (when (:efetivado-em p) (conflito! "a Casa ja' esta' encerrada" "casa-encerrada"))
    (let [ente-id (:ente-id p)
          casa (ente/por-id tx ente-id)
          _ (when-not (logic/transicao-permitida? (:estado casa) "encerrado")
              (conflito! "a Casa nao esta' num estado que permita encerrar" "estado-da-casa"))
          conf (or (confirmacao-vigente tx casa) (conflito! "a confirmacao de recebimento sumiu" "sem-confirmacao"))
          registro (merge (select-keys resumo [:tabelas :linhas-total :objetos :realm-apagado? :ia :exportacoes-apagadas :varredura])
                          {:pedido (str pedido-id)
                           :exportacao {:id (str (:id conf)) :sha256 (:sha256 conf) :bytes (:bytes conf)
                                        :confirmada-em (str (:confirmada-em conf))
                                        :confirmada-por (:confirmada-por-tipo conf)}})
          c (or (ente/encerrar! tx ente-id agora registro)
                (conflito! "a Casa nao esta' com o encerramento em curso" "fora-do-encerramento"))]
      (restricao/marcar-efetivado! tx pedido-id)
      (atuacao/registrar! tx {:operador-id operador-id :ente-id ente-id :acao "casa-encerrada"
                              :detalhe {:pedido (str pedido-id) :exportacao-sha256 (:sha256 conf)
                                        :linhas-total (:linhas-total resumo) :objetos (:objetos resumo)
                                        :realm-apagado (boolean (:realm-apagado? resumo))
                                        :exportacoes-apagadas (:exportacoes-apagadas resumo)
                                        :tabelas (pares-ordenados (:tabelas resumo))
                                        :ia (pares-ordenados (:ia resumo))}})
      (producers/emitir-encerrada! tx ente-id {:encerrada-em (str (:encerrada-em c)) :pedido-id pedido-id
                                               :exportacao-sha256 (:sha256 conf)})
      c)))

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
  (tentativas-sem-desfecho [_ antes-de] (atuacao/tentativas-sem-desfecho (:ds datasource) antes-de))
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
  (pedido-aberto-da-casa [_ ente-id] (restricao/aberto-da-casa (:ds datasource) ente-id))
  (iniciar-exportacao! [this pedido agora] (transacao this #(iniciar-exportacao-em-tx! % pedido agora)))
  (concluir-exportacao! [this id resultado] (transacao this #(concluir-exportacao-em-tx! % id resultado)))
  (falhar-exportacao! [this id erro] (transacao this #(falhar-exportacao-em-tx! % id erro)))
  (confirmar-recebimento! [this id confirmacao] (transacao this #(confirmar-em-tx! % id confirmacao)))
  (exportacoes-da-casa [_ ente-id] (exp/da-casa (:ds datasource) ente-id 50))
  (exportacao-por-id [_ id] (exp/por-id (:ds datasource) id))
  (definir-destino-acervo! [this ente-id operador-id url]
    (transacao this
      (fn [tx]
        (casa-ou-404! tx ente-id)
        (let [c (or (ente/definir-destino-acervo! tx ente-id url)
                    (conflito! "o destino do acervo se informa no encerramento" "fora-do-encerramento"))]
          (atuacao/registrar! tx {:operador-id operador-id :ente-id ente-id :acao "destino-do-acervo-informado"
                                  :detalhe {:url (or url "")}})
          c))))
  (pedir-apagamento! [this ente-id operador-id justificativa agora]
    (transacao this #(pedir-apagamento-em-tx! % ente-id operador-id justificativa agora)))
  (aprovar-apagamento! [this pedido-id operador-id justificativa agora]
    (transacao this #(aprovar-apagamento-em-tx! % pedido-id operador-id justificativa agora)))
  (concluir-apagamento! [this pedido-id resumo operador-id agora]
    (transacao this #(concluir-apagamento-em-tx! % pedido-id resumo operador-id agora)))
  (registrar-apagamento-interrompido! [this pedido-id operador-id erro resumo-parcial]
    (transacao this
      (fn [tx]
        (let [p (restricao/por-id tx pedido-id)]
          (atuacao/registrar! tx {:operador-id operador-id :ente-id (:ente-id p) :acao "apagamento-interrompido"
                                  :detalhe (cond-> {:pedido (str pedido-id) :erro erro}
                                             resumo-parcial (assoc :resumo resumo-parcial))})))))
  (apagamento-pendente [_ ente-id] (restricao/apagamento-aprovado-pendente (:ds datasource) ente-id))
  (reservar-apagamento! [this ente-id operador-id agora]
    (transacao this (fn [tx]
                      (let [antes (ente/por-id tx ente-id)]
                        (when-let [c (ente/reservar-apagamento! tx ente-id agora)]
                          ;; a 1a execucao FECHA a Casa: fica selado quando e por quem
                          (when-not (:apagamento-iniciado-em antes)
                            (atuacao/registrar! tx {:operador-id operador-id :ente-id ente-id :acao "apagamento-iniciado"
                                                    :detalhe {:desde (str (:apagamento-iniciado-em c))}}))
                          c)))))
  (liberar-apagamento! [this ente-id] (transacao this #(ente/liberar-apagamento! % ente-id)))
  (apagamento-efetivado [_ ente-id] (restricao/apagamento-efetivado (:ds datasource) ente-id)))

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
