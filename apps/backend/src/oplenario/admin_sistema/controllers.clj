(ns oplenario.admin-sistema.controllers
  "Orquestracao do `admin_sistema` (ADR-0016). O ciclo de vida do OPERADOR e' separado do das Casas (§22.5.1):
  entra por convite (linha de comando — o primeiro operador nao tem console para se convidar) e sai desligado
  (sessoes do console e do realm derrubadas). Tudo fica na atuacao."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [oplenario.admin-sistema.components.idp-admin :as idp]
            [oplenario.admin-sistema.components.repositorio :as repo]
            [oplenario.kernel.components.idp :as idp-casa]))

(defn- validar-operador! [{:keys [email nome]}]
  (when-not (and (string? email) (re-matches #"[^@\s]+@[^@\s]+\.[^@\s]+" (str/trim email)))
    (throw (ex-info "e-mail do operador invalido" {:tipo :validacao/invalido :campo :email})))
  (when (str/blank? nome)
    (throw (ex-info "nome do operador obrigatorio" {:tipo :validacao/invalido :campo :nome}))))

(defn convidar-operador!
  "Cadastra o operador (idempotente pelo e-mail), garante o realm, cria o usuario e manda o e-mail do Keycloak para
  definir a senha e registrar a chave fisica. `por` = quem convidou (operador-id, ou nil pela linha de comando)."
  [repo-op idp-op {:keys [email nome por]}]
  (validar-operador! {:email email :nome nome})
  (idp/provisionar-realm-operacao! idp-op)
  (let [o (repo/criar-operador! repo-op {:id (random-uuid) :email email :nome nome})]
    (when-not (= "ativo" (:estado o))
      (throw (ex-info "operador desligado nao volta por convite" {:tipo :conflito/operador-desligado})))
    (idp/criar-operador-no-idp! idp-op {:operador-id (:id o) :email (:email o) :nome (:nome o)})
    (idp/convidar-operador! idp-op (:id o))
    (repo/registrar-atuacao! repo-op {:operador-id por :acao "operador-convidado"
                                      :detalhe {:operador (str (:id o)) :email (:email o)}})
    o))

(defn desligar-operador!
  [repo-op idp-op {:keys [email por]}]
  (if-let [o (repo/operador-por-email repo-op email)]
    (let [d (repo/desligar-operador! repo-op (:id o))]
      (idp/desligar-operador-no-idp! idp-op (:id o))
      (repo/registrar-atuacao! repo-op {:operador-id por :acao "operador-desligado"
                                        :detalhe {:operador (str (:id o)) :email (:email o)}})
      d)
    (throw (ex-info "operador nao encontrado" {:tipo :nao-encontrado :email email}))))

;; ---------------------------------------------------------------------------------------------
;; Registro de Casas (12.1). O provisionamento e' HANDOFF, nao controle: a Operacao registra a Casa, entrega o
;; perfil ao cadastros, cria o 1o administrador e o convida; a Casa passa a ser dela quando ele entra
;; (consumidor `identidade.vinculo.primeiro_acesso`). Nenhum passo cruza modulo por import: o host injeta
;; `deps` (inversao de dependencia, §22.10) — `:garantir-perfil-da-casa!`, `:garantir-primeiro-admin!`,
;; `:nome-da-identidade` e o IdP das Casas (`:idp-casa`). Cada passo e' idempotente; o que falhar no Keycloak
;; fica visivel ('convite nao saiu') e se retoma por `reenviar-convite!`.
;; ---------------------------------------------------------------------------------------------

(defn- convidar-primeiro-admin! [repo-op {:keys [idp-casa]} ator ente-id identidade-id nome email reenvio?]
  (idp-casa/provisionar-realm! idp-casa ente-id)
  (idp-casa/criar-usuario! idp-casa ente-id {:identidade-id identidade-id :nome nome :email email})
  (idp-casa/convidar! idp-casa ente-id identidade-id)
  (repo/marcar-convite! repo-op ente-id (:operador-id ator) reenvio?))

(defn provisionar-casa!
  "Devolve {:casa <registro> :convite :enviado|:falhou}. A Casa fica registrada mesmo se o Keycloak falhar."
  [repo-op deps ator {:keys [admin] :as casa}]
  (let [ente-id (random-uuid)]
    (repo/registrar-casa! repo-op (-> casa (dissoc :admin) (assoc :ente-id ente-id :primeiro-admin-email (:email admin)))
                          ator)
    ((:garantir-perfil-da-casa! deps) ente-id casa)
    (let [iid ((:garantir-primeiro-admin! deps) ente-id admin)]
      (repo/marcar-primeiro-admin! repo-op ente-id iid)
      (let [convite (try (convidar-primeiro-admin! repo-op deps ator ente-id iid (:nome admin) (:email admin) false)
                         :enviado
                         (catch Exception e
                           (log/warn e "admin-sistema: convite do 1o administrador nao saiu" {:ente-id ente-id})
                           :falhou))]
        {:casa (repo/casa-por-id repo-op ente-id) :convite convite}))))

(defn- casa-ou-404! [repo-op ente-id]
  (or (and ente-id (repo/casa-por-id repo-op ente-id))
      (throw (ex-info "Casa nao encontrada" {:tipo :admin-sistema/nao-encontrada}))))

(defn reenviar-convite!
  "So' enquanto a Casa espera o 1o administrador. Retoma o que faltou (perfil, realm, usuario) e reenvia."
  [repo-op deps ator ente-id]
  (let [casa (casa-ou-404! repo-op ente-id)
        iid (:primeiro-admin-identidade-id casa)]
    (when-not (= "provisionar" (:estado casa))
      (throw (ex-info "a Casa ja' passou as maos dela — o acesso agora e' com o administrador da Casa"
                      {:tipo :admin-sistema/conflito})))
    (when-not iid
      (throw (ex-info "o 1o administrador nao chegou a ser criado — provisione de novo" {:tipo :admin-sistema/conflito})))
    ((:garantir-perfil-da-casa! deps) ente-id casa)
    (convidar-primeiro-admin! repo-op deps ator ente-id iid ((:nome-da-identidade deps) iid)
                              (:primeiro-admin-email casa) true)
    (repo/casa-por-id repo-op ente-id)))

(defn reprovisionar-realm!
  "Converge o realm da Casa com a config atual (ex.: gov.br ligado depois, ADR-0015). Idempotente."
  [repo-op {:keys [idp-casa]} ator ente-id]
  (casa-ou-404! repo-op ente-id)
  (idp-casa/provisionar-realm! idp-casa ente-id)
  (repo/registrar-atuacao! repo-op {:operador-id (:operador-id ator) :ente-id ente-id :acao "realm-reprovisionado"})
  (repo/casa-por-id repo-op ente-id))

;; ---------------------------------------------------------------------------------------------
;; ADR-0018 (fatia 1): suspender, reativar e iniciar o encerramento. Two-person rule (Eixo 1b): um operador pede, OUTRO
;; aprova — o incidente de seguranca suspende com um so' e exige a 2a aprovacao em 24 h. A sessao ao vivo nunca cai no
;; meio (Eixo 2): a suspensao aprovada com sessao em curso fica AGENDADA. O que e' preguicoso (o incidente vencido, a
;; agendada cuja sessao acabou) e' efetivado quando alguem olha a Casa — o seam `estado-da-casa` do host, a ficha e a
;; lista do console —, sem job: nada no runtime depende do estado antes de alguem pedir por ele.
;;
;; `deps` (do host, §22.10): `:sessao-em-curso?` (fn [ente-id] -> bool, sobre `sessoes`) e `:ao-mudar-estado` (fn
;; [ente-id], invalida o cache do seam `estado-da-casa` nesta instancia).
;; ---------------------------------------------------------------------------------------------

(defn- sessao-em-curso-de [deps ente-id]
  (fn [] (boolean (when-let [f (:sessao-em-curso? deps)] (f ente-id)))))

(defn- mudou! [deps ente-id]
  (when-let [f (:ao-mudar-estado deps)] (f ente-id)))

(defn efetivar-pendentes!
  "O que a verificacao preguicosa efetiva agora nesta Casa (ver acima). Devolve a Casa (nil = fora do registro)."
  [repo-op deps ente-id agora]
  (let [antes (repo/casa-por-id repo-op ente-id)
        depois (repo/efetivar-pendentes! repo-op ente-id agora (sessao-em-curso-de deps ente-id))]
    (when (and antes (not= (:estado antes) (:estado depois))) (mudou! deps ente-id))
    depois))

(defn estado-da-casa
  "O seam do host: a restricao vigente da Casa ({:estado :motivo :desde}), ou nil (fora do registro — as Casas de demo
  e as dos testes nao passam pelo console; sem registro nao ha' restricao)."
  [repo-op deps ente-id agora]
  (when-let [c (efetivar-pendentes! repo-op deps ente-id agora)]
    {:estado (:estado c) :motivo (:motivo-restricao c) :desde (:restrita-desde c)}))

(defn pedir-suspensao!
  [repo-op deps ator ente-id {:keys [motivo justificativa]} agora]
  (casa-ou-404! repo-op ente-id)
  (let [r (repo/pedir-restricao! repo-op {:ente-id ente-id :acao "suspender" :motivo motivo :justificativa justificativa
                                          :pedido-por (:operador-id ator)}
                                 agora)]
    (mudou! deps ente-id)
    r))

(defn iniciar-encerramento!
  "Eixo 4.1: o pedido de encerramento (da Casa ou nosso). Dois operadores aprovam; a Casa fica `suspenso` com motivo
  `encerramento_em_curso`. A exportacao completa e o apagamento sao a fatia 2."
  [repo-op deps ator ente-id {:keys [origem justificativa]} agora]
  (casa-ou-404! repo-op ente-id)
  (let [r (repo/pedir-restricao! repo-op {:ente-id ente-id :acao "encerrar" :motivo origem :justificativa justificativa
                                          :pedido-por (:operador-id ator)}
                                 agora)]
    (mudou! deps ente-id)
    r))

(defn aprovar-pedido!
  [repo-op deps ator pedido-id justificativa]
  (let [p (or (repo/pedido-por-id repo-op pedido-id)
              (throw (ex-info "pedido nao encontrado" {:tipo :admin-sistema/nao-encontrada})))
        r (repo/aprovar-pedido! repo-op pedido-id (:operador-id ator) justificativa
                                ((sessao-em-curso-de deps (:ente-id p))))]
    (mudou! deps (:ente-id p))
    r))

(defn recusar-pedido!
  "Outro operador recusa; quem pediu RETIRA o proprio pedido. O incidente recusado devolve a Casa a ativa."
  [repo-op deps ator pedido-id justificativa]
  (let [r (repo/recusar-pedido! repo-op pedido-id (:operador-id ator) justificativa)]
    (mudou! deps (get-in r [:pedido :ente-id]))
    r))

(defn reativar!
  "Eixo 5: suspenso -> ativo com um operador e o motivo (ex.: pagamento regularizado). Tambem cancela a suspensao
  agendada que ainda nao entrou."
  [repo-op deps ator ente-id justificativa]
  (casa-ou-404! repo-op ente-id)
  (let [c (repo/reativar-casa! repo-op ente-id (:operador-id ator) justificativa)]
    (mudou! deps ente-id)
    c))

(defn fila-de-aprovacao
  "Os pedidos que esperam o 2o operador, de todas as Casas."
  [repo-op]
  (repo/pedidos-abertos repo-op))

(defn listar-casas
  "As Casas do registro, com o que estava pendente efetivado (so' nas que tem algo a efetivar), e a fila."
  [repo-op deps agora]
  (let [casas (repo/listar-casas repo-op)]
    {:casas (mapv #(if (or (:suspensao-agendada %) (= "suspenso" (:estado %)))
                     (or (efetivar-pendentes! repo-op deps (:ente-id %) agora) %)
                     %)
                  casas)
     :pendentes (fila-de-aprovacao repo-op)}))

(defn ficha-da-casa
  "A Casa + o 1o administrador (nome) + o pedido que espera o 2o operador + a atuacao da Operacao nela."
  [repo-op deps ente-id agora]
  (casa-ou-404! repo-op ente-id)
  (let [casa (efetivar-pendentes! repo-op deps ente-id agora)]
    {:casa casa
     :primeiro-admin (when-let [iid (:primeiro-admin-identidade-id casa)]
                       {:nome ((:nome-da-identidade deps) iid) :email (:primeiro-admin-email casa)})
     :pedido-aberto (repo/pedido-aberto-da-casa repo-op ente-id)
     :atuacao (repo/atuacao-do-ente repo-op ente-id 50)}))
