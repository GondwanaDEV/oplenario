(ns oplenario.admin-sistema.controllers
  "Orquestracao do `admin_sistema` (ADR-0016). O ciclo de vida do OPERADOR e' separado do das Casas (§22.5.1):
  entra por convite (linha de comando — o primeiro operador nao tem console para se convidar) e sai desligado
  (sessoes do console e do realm derrubadas). Tudo fica na atuacao."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [oplenario.admin-sistema.components.exportacao :as exportacao]
            [oplenario.admin-sistema.components.idp-admin :as idp]
            [oplenario.admin-sistema.components.repositorio :as repo]
            [oplenario.admin-sistema.logic :as logic]
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

(defn- convidar-primeiro-admin! [repo-op {:keys [idp-casa]} ator ente-id nome-da-casa identidade-id nome email reenvio?]
  ;; ADR-0025: o nome da Casa vira o titulo da tela de login do realm
  (idp-casa/provisionar-realm! idp-casa ente-id {:nome nome-da-casa})
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
      (let [convite (try (convidar-primeiro-admin! repo-op deps ator ente-id (:nome casa) iid (:nome admin) (:email admin)
                                                   false)
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
    (convidar-primeiro-admin! repo-op deps ator ente-id (:nome casa) iid ((:nome-da-identidade deps) iid)
                              (:primeiro-admin-email casa) true)
    (repo/casa-por-id repo-op ente-id)))

(defn reprovisionar-realm!
  "Converge o realm da Casa com a config atual (ex.: gov.br ligado depois, ADR-0015; o nome, o portugues, o tema e a
  defesa contra forca bruta, ADR-0025). Idempotente."
  [repo-op {:keys [idp-casa]} ator ente-id]
  (let [casa (casa-ou-404! repo-op ente-id)]
    (idp-casa/provisionar-realm! idp-casa ente-id {:nome (:nome casa)}))
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
  "O seam do host: a restricao vigente da Casa ({:estado :motivo :desde}; encerrada, tambem {:encerrada-em
  :destino-acervo-url :nome} — o nome publico do registro, que sobrevive ao apagamento), ou nil (fora do registro — as Casas de demo e as dos testes nao passam pelo console; sem
  registro nao ha' restricao)."
  [repo-op deps ente-id agora]
  (when-let [c (efetivar-pendentes! repo-op deps ente-id agora)]
    (cond-> {:estado (:estado c) :motivo (:motivo-restricao c) :desde (:restrita-desde c)}
      (= "encerrado" (:estado c)) (assoc :encerrada-em (:encerrada-em c) :destino-acervo-url (:destino-acervo-url c)
                                         :nome (:nome c))
      ;; o apagamento comecou (mig 0177): a Casa ja' fechou, mesmo antes do `encerrado` — dado sendo apagado nao
      ;; recebe linha nova (`restricao-da-casa/encerrada?`)
      (and (not= "encerrado" (:estado c)) (:apagamento-iniciado-em c))
      (assoc :apagando? true :destino-acervo-url (:destino-acervo-url c) :nome (:nome c)))))

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

(declare aprovar-apagamento!)

(defn aprovar-pedido!
  "O 2o operador aprova. O pedido de APAGAMENTO (fatia 2) segue o caminho dele: aprovado, dispara o apagamento."
  [repo-op deps ator pedido-id justificativa agora]
  (let [p (or (repo/pedido-por-id repo-op pedido-id)
              (throw (ex-info "pedido nao encontrado" {:tipo :admin-sistema/nao-encontrada})))]
    (if (= "apagar" (:acao p))
      (aprovar-apagamento! repo-op deps ator p justificativa agora)
      (let [r (repo/aprovar-pedido! repo-op pedido-id (:operador-id ator) justificativa
                                    ((sessao-em-curso-de deps (:ente-id p))))]
        (mudou! deps (:ente-id p))
        r))))

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

(declare encerramento-da-casa)

(defn ficha-da-casa
  "A Casa + o 1o administrador (nome) + o pedido que espera o 2o operador + o encerramento (fatia 2) + a atuacao da
  Operacao nela."
  [repo-op deps ente-id agora]
  (casa-ou-404! repo-op ente-id)
  (let [casa (efetivar-pendentes! repo-op deps ente-id agora)]
    {:casa casa
     :primeiro-admin (when-let [iid (:primeiro-admin-identidade-id casa)]
                       {:nome ((:nome-da-identidade deps) iid) :email (:primeiro-admin-email casa)})
     :pedido-aberto (repo/pedido-aberto-da-casa repo-op ente-id)
     :encerramento (encerramento-da-casa repo-op deps casa agora)
     :atuacao (repo/atuacao-do-ente repo-op ente-id 50)}))

;; ---------------------------------------------------------------------------------------------
;; ADR-0018 (fatia 2): ENCERRAR (Eixo 4). A sequencia, por etapas:
;;   1. a EXPORTACAO completa (9.6) — o admin_ente gera quando quiser (portabilidade); o operador manda gerar durante o
;;      encerramento. Gera em segundo plano. Quem BAIXA e' so' o admin_ente da Casa: a Operacao e' operadora (LGPD) e ve
;;      so' metadado (estado, tamanho, hash, manifesto resumido);
;;   2. a CONFIRMACAO de recebimento — o admin_ente na tela (vendo o hash) ou o operador registrando o oficio; nao se
;;      desfaz;
;;   3. a GUARDA de 90 dias desde a confirmacao, so' leitura + exportar de novo;
;;   4. o DESTINO do acervo publico (opcional), que o portal mostra depois;
;;   5. o APAGAMENTO — um operador pede, outro aprova (two-person rule); a aprovacao dispara o apagamento (seam do host,
;;      retomavel). Terminou: `encerrado`, com o resumo e o hash da exportacao, para sempre.
;;
;; Os dois trabalhos pesados vem do host como seams (§22.10 — o plano de dados cruza todos os modulos):
;;   `:exportar-casa` (fn [ente-id exportacao-id] -> {:chave :sha256 :bytes :manifesto}; falha = excecao);
;;   `:apagar-casa`   (fn [ente-id pedido-id] -> {:tabelas :linhas-total :objetos :realm-apagado? :ia
;;                                                :exportacoes-apagadas}; retomavel).
;; Sem eles (nil), o passo responde 503 nomeado — nunca 500. `:executar-exportacao` (fn [f]) e' o executor (o pool
;; dedicado em producao; sincrono nos testes).
;; ---------------------------------------------------------------------------------------------

(defn- indisponivel! [msg causa]
  (throw (ex-info msg {:tipo :admin-sistema/indisponivel :causa causa})))

(defn- mensagem-de [^Throwable t]
  (let [m (or (ex-message t) (.getName (class t)))]
    (subs m 0 (min 500 (count m)))))

(defn- resultado-valido? [{:keys [chave sha256 bytes]}]
  (and (string? chave) (string? sha256) (re-matches #"^[0-9a-f]{64}$" sha256) (integer? bytes) (not (neg? bytes))))

(defn- gerar-agora!
  "Roda a geracao e fecha a linha: `pronta` com o que voltou, ou `falhou` com a mensagem (nunca dado da Casa)."
  [repo-op exportar {:keys [id ente-id]}]
  (try
    (let [r (exportar ente-id id)]
      (if (resultado-valido? r)
        (repo/concluir-exportacao! repo-op id r)
        (repo/falhar-exportacao! repo-op id "a geracao devolveu um resultado sem arquivo ou sem hash")))
    (catch Throwable t
      (log/warn t "admin-sistema: a exportacao da Casa falhou" {:ente-id ente-id :exportacao id})
      (repo/falhar-exportacao! repo-op id (mensagem-de t)))))

(defn gerar-exportacao!
  "Abre a geracao (uma por Casa) e a entrega ao executor. `solicitante` = {:tipo \"operador\"|\"admin_ente\" :id}.
  Devolve a linha como esta' depois de submeter (`gerando`, ou ja' `pronta` com o executor sincrono)."
  [repo-op deps {:keys [tipo id]} ente-id agora]
  (let [exportar (or (:exportar-casa deps)
                     (indisponivel! "a exportacao completa ainda nao esta' disponivel nesta instalacao"
                                    "exportacao-indisponivel"))
        executar (or (:executar-exportacao deps) exportacao/em-segundo-plano)
        e (repo/iniciar-exportacao! repo-op {:ente-id ente-id :solicitante-tipo tipo :solicitante id} agora)]
    (try
      (executar #(gerar-agora! repo-op exportar e))
      (catch clojure.lang.ExceptionInfo ex
        (repo/falhar-exportacao! repo-op (:id e) (ex-message ex))
        (throw ex)))
    (repo/exportacao-por-id repo-op (:id e))))

(defn exportacao-da-casa!
  "A exportacao `id` vista pela Casa `ente-id` (o admin_ente): a de outra Casa nao existe (404)."
  [repo-op ente-id id]
  (let [e (and id (repo/exportacao-por-id repo-op id))]
    (when-not (and e (= ente-id (:ente-id e)))
      (throw (ex-info "exportacao nao encontrada" {:tipo :admin-sistema/nao-encontrada})))
    e))

(defn arquivo-para-baixar!
  "A exportacao PRONTA da propria Casa, para servir o arquivo. Fora disso: 404 (outra Casa) ou 409 (nao pronta)."
  [repo-op ente-id id]
  (let [e (exportacao-da-casa! repo-op ente-id id)]
    (when-not (= "pronta" (:estado e))
      (throw (ex-info "esta exportacao ainda nao tem arquivo" {:tipo :admin-sistema/conflito
                                                               :causa "exportacao-nao-pronta"})))
    e))

(defn confirmar-recebimento!
  "A Casa confirma que recebeu (o admin_ente, com o hash que ela ve na tela)."
  [repo-op deps ator ente-id exportacao-id sha256 agora]
  (let [e (repo/confirmar-recebimento! repo-op exportacao-id {:ente-id ente-id :tipo "admin_ente"
                                                               :por (:identidade-id ator) :sha256 sha256 :em agora})]
    (mudou! deps ente-id)
    e))

(defn registrar-oficio!
  "O operador registra a confirmacao de recebimento que a Casa mandou por oficio (o texto do oficio fica selado)."
  [repo-op deps ator exportacao-id texto agora]
  (let [e (repo/confirmar-recebimento! repo-op exportacao-id {:tipo "oficio" :por (:operador-id ator) :texto texto
                                                               :em agora})]
    (mudou! deps (:ente-id e))
    e))

(defn exportacoes-para-a-casa
  "O bloco \"Exportar os dados da Camara\" do admin_ente: se a exportacao existe nesta instalacao, se o encerramento
  esta' em curso (a confirmacao abre a contagem da guarda) e as exportacoes, mais recente primeiro."
  [repo-op deps ente-id]
  (let [casa (and ente-id (repo/casa-por-id repo-op ente-id))]
    {:disponivel (boolean (and casa (:exportar-casa deps)))
     :em-encerramento (boolean (and casa (logic/em-encerramento? casa)))
     :exportacoes (if casa (vec (take 10 (repo/exportacoes-da-casa repo-op ente-id))) [])}))

(defn definir-destino-acervo!
  [repo-op deps ator ente-id url]
  (casa-ou-404! repo-op ente-id)
  (let [c (repo/definir-destino-acervo! repo-op ente-id (:operador-id ator) url)]
    (mudou! deps ente-id)
    c))

(defn pedir-apagamento!
  [repo-op deps ator ente-id justificativa agora]
  (casa-ou-404! repo-op ente-id)
  (when-not (:apagar-casa deps)
    (indisponivel! "o apagamento ainda nao esta' disponivel nesta instalacao" "apagamento-indisponivel"))
  (let [r (repo/pedir-apagamento! repo-op ente-id (:operador-id ator) justificativa agora)]
    (mudou! deps ente-id)
    r))

(defn- campo
  "O campo `k` do mapa, com chave keyword OU string (o resumo volta do jsonb com chaves string). `false` e' valor."
  [m k]
  (when (map? m) (if (contains? m k) (get m k) (get m (name k)))))

(defn- resumo-parcial-anterior
  "O resumo do que as execucoes anteriores DESTE pedido ja' apagaram (o mais recente `apagamento-interrompido` com
  resumo), ou nil. O banco apaga uma vez so' — sem isto, a retomada concluiria com zero linhas e perderia a prova."
  [repo-op ente-id pedido-id]
  (->> (repo/atuacao-do-ente repo-op ente-id 1000)
       (filter #(and (= "apagamento-interrompido" (:acao %))
                     (= (str pedido-id) (str (campo (:detalhe %) :pedido)))
                     (campo (:detalhe %) :resumo)))
       first
       :detalhe
       (#(campo % :resumo))))

(defn- pendencias-de [resumo]
  (when (false? (campo resumo :completo?))
    (let [ps (campo resumo :pendencias)]
      (str "ficou pendente: " (if (seq ps) (str/join ", " (map name ps)) "um passo externo")
           " — retome quando o servico voltar"))))

(defn- executar-apagamento!
  "Chama o seam `:apagar-casa` FORA da tx (ele confere de novo no banco o pedido aprovado) e fecha: `encerrado` com o
  resumo, ou o apagamento interrompido selado (o pedido segue aprovado e o console oferece retomar)."
  [repo-op deps ator pedido agora]
  (let [apagar (:apagar-casa deps)
        {:keys [ente-id]} pedido
        pedido-id (:id pedido)]
    ;; uma execucao por Casa ENTRE instancias (lease no registro, mig 0177); a 1a FECHA a Casa: dali em diante o
    ;; interceptor da Casa responde 410 a tudo, e o estado dela nao fica no cache (`restricao-da-casa/com-cache`)
    (when-not (repo/reservar-apagamento! repo-op ente-id (:operador-id ator) agora)
      (throw (ex-info "o apagamento desta Casa ja' esta' rodando" {:tipo :admin-sistema/conflito
                                                                    :causa "apagamento-rodando"})))
    (mudou! deps ente-id)
    (try
      (try
        (let [resumo (logic/somar-resumos (resumo-parcial-anterior repo-op ente-id pedido-id) (apagar ente-id pedido-id))]
          (if-let [pendente (pendencias-de resumo)]
            ;; o seam voltou sem lancar mas com passo externo pendente (IdP ou satelite fora): a Casa NAO vira
            ;; `encerrado` com dado vivo em outro lugar — fica interrompido, com o parcial guardado para a retomada
            (do (log/warn "admin-sistema: o apagamento da Casa ficou com pendencia" {:ente-id ente-id :pedido pedido-id
                                                                                    :pendencias (campo resumo :pendencias)})
                (repo/registrar-apagamento-interrompido! repo-op pedido-id (:operador-id ator) pendente resumo)
                {:casa (repo/casa-por-id repo-op ente-id) :pedido (repo/pedido-por-id repo-op pedido-id)
                 :efeito :apagamento-interrompido :erro pendente})
            (let [casa (repo/concluir-apagamento! repo-op pedido-id resumo (:operador-id ator) agora)]
              {:casa casa :pedido (repo/pedido-por-id repo-op pedido-id) :efeito :encerrada})))
        (catch Throwable t
          (log/error t "admin-sistema: o apagamento da Casa parou no meio" {:ente-id ente-id :pedido pedido-id})
          (repo/registrar-apagamento-interrompido! repo-op pedido-id (:operador-id ator) (mensagem-de t) nil)
          {:casa (repo/casa-por-id repo-op ente-id) :pedido (repo/pedido-por-id repo-op pedido-id)
           :efeito :apagamento-interrompido :erro (mensagem-de t)}))
      (finally
        ;; concluido, o `encerrado` ja' soltou o lease junto; interrompido, solta aqui (a Casa segue fechada)
        (repo/liberar-apagamento! repo-op ente-id)
        (mudou! deps ente-id)))))

(defn- aprovar-apagamento!
  [repo-op deps ator pedido justificativa agora]
  (when-not (:apagar-casa deps)
    (indisponivel! "o apagamento ainda nao esta' disponivel nesta instalacao" "apagamento-indisponivel"))
  (let [{p :pedido} (repo/aprovar-apagamento! repo-op (:id pedido) (:operador-id ator) justificativa agora)]
    (mudou! deps (:ente-id p))
    (executar-apagamento! repo-op deps ator p agora)))

(defn retomar-apagamento!
  "O apagamento aprovado que parou no meio roda de novo (o seam e' retomavel)."
  [repo-op deps ator ente-id agora]
  (casa-ou-404! repo-op ente-id)
  (when-not (:apagar-casa deps)
    (indisponivel! "o apagamento ainda nao esta' disponivel nesta instalacao" "apagamento-indisponivel"))
  (let [p (or (repo/apagamento-pendente repo-op ente-id)
              (throw (ex-info "nao ha' apagamento aprovado esperando ser retomado nesta Casa"
                              {:tipo :admin-sistema/conflito :causa "sem-apagamento-pendente"})))]
    (executar-apagamento! repo-op deps ator p agora)))

(defn- encerramento-da-casa
  "O bloco do encerramento na ficha do console (nil quando nao ha' nada a mostrar: Casa ativa sem exportacao)."
  [repo-op deps casa agora]
  (let [ente-id (:ente-id casa)
        exportacoes (repo/exportacoes-da-casa repo-op ente-id)
        em-curso? (logic/em-encerramento? casa)
        encerrada? (= "encerrado" (:estado casa))]
    (when (or em-curso? encerrada? (seq exportacoes))
      (let [conf (when (or em-curso? encerrada?)
                   (logic/confirmacao-do-encerramento exportacoes (:restrita-desde casa)))]
        {:em-curso em-curso?
         :desde (when (or em-curso? encerrada?) (:restrita-desde casa))
         :exportacoes (vec (take 10 exportacoes))
         :confirmacao conf
         :apagamento-possivel-em (some-> conf :confirmada-em logic/apagamento-possivel-em)
         :pode-pedir-apagamento (logic/pode-pedir-apagamento? casa conf agora)
         :exportacao-disponivel (boolean (:exportar-casa deps))
         :apagamento-disponivel (boolean (:apagar-casa deps))
         :apagamento-pendente (when em-curso? (repo/apagamento-pendente repo-op ente-id))
         :destino-acervo-url (:destino-acervo-url casa)
         :encerrada-em (:encerrada-em casa)
         :apagamento (:apagamento casa)}))))
