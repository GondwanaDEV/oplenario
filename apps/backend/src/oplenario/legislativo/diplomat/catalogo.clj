(ns oplenario.legislativo.diplomat.catalogo
  "As entradas do CATALOGO DE ACOES que sao do legislativo (ADR-0009). Cada uma e' a mesma acao de uma rota da tela —
  mesmo controller, mesmo wire/out — com descricao para o agente. As primeiras (§22.11 Eixo 2, 'migracao'): as
  consultas que um agente da Casa precisa antes de tudo: a situacao da materia e a tramitacao dela. B.6 (ADR-0012): o
  primeiro ATO — protocolar o requerimento do vereador —, que o agente so' PROPOE; a pessoa confirma e assina na tela."
  (:require [oplenario.kernel.autorizacao :as authz]
            [oplenario.kernel.catalogo :as catalogo]
            [oplenario.kernel.tempo :as tempo]
            [oplenario.legislativo.adapters.in.contas :as adapters-in-contas]
            [oplenario.legislativo.adapters.in.juridico :as adapters-in-juridico]
            [oplenario.legislativo.adapters.out.contas :as adapters-out-contas]
            [oplenario.legislativo.adapters.in.proposicao :as adapters-in-proposicao]
            [oplenario.legislativo.adapters.out.juridico :as adapters-out-juridico]
            [oplenario.legislativo.adapters.out.proposicao :as adapters-out-proposicao]
            [oplenario.legislativo.adapters.out.requerimento :as adapters-out-requerimento]
            [oplenario.legislativo.components.assinador-icp :as assinador-icp]
            [oplenario.legislativo.components.repositorio :as repo]
            [oplenario.legislativo.controllers :as controllers]
            [oplenario.legislativo.logic :as logic]
            [oplenario.legislativo.logic.contas :as logic-contas]
            [oplenario.legislativo.wire.out.contas :as wire-contas]
            [oplenario.legislativo.wire.out.juridico :as wire-jur]
            [oplenario.legislativo.wire.out.proposicao :as wire]
            [oplenario.legislativo.wire.out.requerimento :as wire-req])
  (:import (java.time ZoneId)))

(set! *warn-on-reflection* true)

(def IdentificacaoMateria
  "Como o agente aponta a materia: pelo id, OU pela identificacao que uma pessoa usa (especie + numero + ano)."
  [:and
   [:map {:closed true :description "Informe proposicao-id, ou tipo + sequencial + ano."}
    [:proposicao-id {:optional true :description "Id da proposicao, se ja' for conhecido."} :uuid]
    [:tipo {:optional true :description "Especie da proposicao (ex.: projeto_lei para PL, requerimento para REQ)."}
     (into [:enum] (sort logic/tipos))]
    [:sequencial {:optional true :description "O numero da proposicao (o 12 de 'PL 12/2026')."} [:int {:min 1}]]
    [:ano {:optional true :description "O ano da proposicao (o 2026 de 'PL 12/2026')."} [:int {:min 1900 :max 2200}]]]
   [:fn {:error/message "informe proposicao-id, ou tipo + sequencial + ano"}
    (fn [m] (or (some? (:proposicao-id m)) (every? #(some? (get m %)) [:tipo :sequencial :ano])))]])

(defn- resolver-id
  "O id da materia no tenant do ator, ou nil."
  [repo-legislativo ente-id {:keys [proposicao-id tipo ano sequencial]}]
  (or proposicao-id
      (:id (repo/buscar-proposicao-por-numero repo-legislativo ente-id tipo ano sequencial))))

(def ^:private entradas-materia
  [(catalogo/entrada
    {:nome "situacao_da_materia"
     :descricao (str "Consulta uma proposicao da Camara (projeto de lei, requerimento, indicacao, mocao...): numero, "
                     "ementa, autoria, em que pe' esta (estado da tramitacao) e o texto vigente. Use quando alguem "
                     "perguntar sobre uma materia especifica, como 'qual a situacao do PL 12/2026?'.")
     :classe :leitura
     ;; os papeis de `GET /legislativo/proposicoes/:id` (papel-leitura) e o agente institucional
     :papeis #{"secretario" "vereador" "juridico" authz/papel-agente-institucional}
     :entrada IdentificacaoMateria
     :saida wire/ProposicaoDetalheOut
     :rotas #{:legislativo/detalhe-proposicao}
     :executar (fn [{:keys [repo-legislativo]} ator m]
                 (let [ente-id (:ente-id ator)]
                   (when-let [id (resolver-id repo-legislativo ente-id m)]
                     (when-let [{:keys [proposicao texto]} (controllers/buscar-proposicao-ficha repo-legislativo ente-id id)]
                       (adapters-out-proposicao/detalhe->wire proposicao texto)))))})
   (catalogo/entrada
    {:nome "tramitacao_da_materia"
     :descricao (str "Mostra o historico de tramitacao de uma proposicao (cada movimentacao, de que estado para qual, "
                     "quando e quem recebeu) e os proximos atos que o rito da Casa permite a partir do estado atual. "
                     "Use para 'por onde passou o PL 12/2026?' ou 'o que falta para o requerimento 5/2026 andar?'.")
     :classe :leitura
     ;; os papeis de `GET /legislativo/proposicoes/:id/tramitacao` (papel-leitura)
     :papeis #{"secretario" "vereador" "juridico"}
     :entrada IdentificacaoMateria
     :saida wire/TramitacaoOut
     :rotas #{:legislativo/tramitacao-proposicao}
     :executar (fn [{:keys [repo-legislativo nome-na-casa]} ator m]
                 (let [ente-id (:ente-id ator)]
                   (when-let [id (resolver-id repo-legislativo ente-id m)]
                     (when-let [t (controllers/buscar-tramitacao repo-legislativo (or nome-na-casa (constantly nil))
                                                                 ente-id id
                                                                 (:limite (adapters-in-proposicao/tramitacao-query->dominio {})))]
                       (adapters-out-proposicao/tramitacao->wire t)))))})])

;; ---------- B.6 (ADR-0012): o requerimento do vereador — o agente propoe, a pessoa assina ----------

(def ^:private Campos
  [:map-of {:max 50 :description "Os campos que o modelo pede, pelo nome (ex.: destinatario, assunto)."}
   [:string {:min 1 :max 100}] [:string {:max 2000}]])

(def ProtocolarRequerimento
  [:map {:closed true}
   [:modelo-id {:description "Id do modelo de requerimento (vem de modelos_de_requerimento)."} :uuid]
   [:ementa {:description "Uma linha que resume o pedido; aparece nas listas e no portal."}
    [:and [:string {:min 1 :max 2000}] [:re #"\S"]]]
   [:campos {:optional true} Campos]])

;; a mesma data civil da borda HTTP do legislativo (`diplomat/http/in`, zona propria do modulo — o legislativo ainda nao
;; consome a constante de fuso do kernel): o texto assinado pela proposta e pela tela tem a mesma data.
(def ^:private zona-civil (ZoneId/of "America/Fortaleza"))

(defn- hoje [{:keys [relogio]}] (tempo/hoje (or relogio (tempo/relogio-sistema)) zona-civil))

(def ^:private entradas-requerimento
  [(catalogo/entrada
    {:nome "modelos_de_requerimento"
     :descricao (str "Lista os modelos de requerimento que a Casa oferece ao vereador (ex.: 'Requerimento de "
                     "informacao'), cada um com os campos que o texto pede. Use antes de propor um requerimento, "
                     "para escolher o modelo e saber o que preencher. Autor e data entram sozinhos no texto.")
     :classe :leitura
     :papeis #{"vereador"}
     :entrada [:map {:closed true}]
     :saida wire-req/ModelosRequerimentoOut
     :rotas #{:legislativo/meus-modelos-requerimento}
     :executar (fn [{:keys [repo-legislativo]} ator _]
                 (adapters-out-requerimento/modelos->wire (controllers/modelos-de-requerimento repo-legislativo ator)))})
   (catalogo/entrada
    {:nome "protocolar_requerimento"
     :descricao (str "Prepara o protocolo de um requerimento do proprio vereador: o modelo da Casa, a ementa e os "
                     "campos que o modelo pede. Voce NAO protocola: isto cria uma PROPOSTA com o texto ja' formatado, "
                     "e o vereador revisa, assina e protocola na tela da plataforma (ou recusa). Consulte antes "
                     "modelos_de_requerimento para o modelo-id e os nomes dos campos.")
     :classe :ato
     :ritual :assinatura
     :papeis #{"vereador"}
     :entrada ProtocolarRequerimento
     :saida wire-req/RequerimentoProtocoladoOut
     :rotas #{:legislativo/protocolar-requerimento}
     :apresentar (fn [{:keys [repo-legislativo resolver-autor] :as deps} ator {:keys [modelo-id campos ementa]}]
                   (when-let [{:keys [texto]} (controllers/previa-requerimento repo-legislativo resolver-autor ator
                                                                               {:modelo-id modelo-id
                                                                                :campos (or campos {})
                                                                                :hoje (hoje deps)})]
                     {:titulo (str "Protocolar o requerimento “" ementa "”") :texto texto}))
     :executar (fn [{:keys [repo-legislativo resolver-municipio resolver-autor] :as deps} ator
                    {:keys [modelo-id campos ementa]}]
                 (some-> (controllers/meu-protocolar-requerimento
                           repo-legislativo resolver-municipio resolver-autor (assinador-icp/assinador-stub) ator
                           {:id (random-uuid) :modelo-id modelo-id :campos (or campos {}) :ementa ementa
                            :hoje (hoje deps)})
                         adapters-out-requerimento/protocolado->wire))})])

;; ---------- B.8 (ADR-0013): a nota tecnica do agente institucional ----------

(def ^:private CitacaoNota
  [:map {:closed true}
   [:fonte-id {:description "A fonte citada, como no texto: norma:<id>#<endereco> ou materia:<id>."} [:string {:min 1 :max 300}]]
   [:trecho {:optional true} [:maybe [:string {:max 2000}]]]
   [:status {:description "O resultado da conferencia da citacao nesta execucao."}
    [:enum "conferida" "sem_trecho" "trecho_nao_encontrado" "fonte_nao_lida"]]
   [:rotulo {:optional true} [:maybe [:string {:max 500}]]]])

(def RegistrarNotaTecnica
  [:map {:closed true}
   [:proposicao-id {:description "A proposicao conferida."} :uuid]
   [:texto {:description "A nota, com as marcas de citacao [[fonte | trecho]]; um paragrafo por ponto conferido."}
    [:and [:string {:min 1 :max 20000}] [:re #"\S"]]]
   [:citacoes {:optional true} [:vector {:max 100} CitacaoNota]]
   [:paragrafos-sem-fonte {:optional true} [:vector {:max 200} [:int {:min 0}]]]
   [:incerteza [:enum "normal" "revisar_com_atencao"]]
   [:motivos-incerteza {:optional true}
    [:vector {:max 10} [:enum "truncado" "sem_fonte" "citacao_nao_conferida" "conteudo_de_terceiro"]]]
   [:modelo {:description "fornecedor:modelo que redigiu."} [:string {:min 1 :max 200}]]
   [:execucao-ia {:optional true
                  :description (str "O id desta execucao no satelite de IA (UUID). Opcional: so' serve para o 'Reportar "
                                    "erro' achar a execucao; nao e' identidade nem da credencial.")}
    [:maybe :uuid]]])

(def NotaRegistradaOut
  [:map {:closed true} [:nota-id :string] [:estado :string] [:mensagem :string]])

(def ^:private mensagem-nota
  (str "Nota registrada na fila da secretaria como RASCUNHO. Ninguem decidiu nada: a secretaria le, aproveita ou "
       "descarta."))

(def ^:private entradas-conferencia
  [(catalogo/entrada
    {:nome "registrar_nota_tecnica"
     :descricao (str "Registra na fila da secretaria o RASCUNHO de nota tecnica da conferencia de uma proposicao "
                     "protocolada contra as normas da Casa (LOM, Regimento): o texto com as citacoes dos dispositivos "
                     "lidos nesta execucao e o resultado da conferencia de cada citacao. Nao decide nada e nao move a "
                     "materia: a secretaria revisa, aproveita ou descarta. Uma nota por proposicao — registrar de "
                     "novo devolve a que ja' existe.")
     :classe :rascunho
     :papeis #{authz/papel-agente-institucional}
     :entrada RegistrarNotaTecnica
     :saida NotaRegistradaOut
     :rotas #{}
     :executar (fn [{:keys [repo-legislativo]} ator m]
                 (when-let [n (controllers/registrar-nota-tecnica! repo-legislativo ator m)]
                   {:nota-id (str (:id n)) :estado (:estado n) :mensagem mensagem-nota}))})])

;; ---------- ADR-0019 fatia 2: o caminho da materia — o agente propoe, a secretaria confirma ----------
;;
;; O agente da secretaria PODE PROPOR o pedido de parecer juridico, o encaminhamento as comissoes e a designacao do
;; relator: sao atos administrativos da secretaria que a pessoa le e confirma em `/propostas/:id`, e a MESMA entrada roda
;; como ela (o ator do pedido e' a pessoa, nunca o agente). NAO entram: salvar, assinar e substituir o parecer juridico —
;; assinar e' ato pessoal e intransferivel do advogado (nome, OAB e qualificacao do perfil dele), e o texto de maquina
;; nunca vira parecer (a rascunho-IA do advogado e' a fatia 2 do satelite, na tela dele). Ficam em `fora-do-catalogo.edn`.

(defn- materia-ref
  "`PL 012/2026` da materia (o que a pessoa le), ou nil se a materia nao existe nesta Casa."
  [repo-legislativo ente-id proposicao-id]
  (when-let [p (repo/buscar-proposicao repo-legislativo ente-id proposicao-id)]
    {:ref (logic/numero-exibicao p) :ementa (:ementa p)}))

(defn- invalido! [msg campo] (throw (ex-info msg {:tipo :validacao/invalido :campo campo})))

(def PedirParecerJuridico
  [:and
   [:map {:closed true}
    [:proposicao-id {:optional true
                     :description "A materia sobre a qual se pede o parecer. Sem ela, e' uma CONSULTA AVULSA (decoro, contas, prazo regimental...) e o assunto e' obrigatorio."}
     :uuid]
    [:assunto {:optional true :description "O que se pede ao juridico, de 5 a 300 caracteres. Com materia, o padrao e' a analise juridica da materia."}
     [:string {:min 5 :max 300}]]
    [:prazo {:optional true :description "Ate' quando (AAAA-MM-DD). Opcional: nao ha' relogio legal, e' combinado."}
     [:re #"^\d{4}-\d{2}-\d{2}$"]]
    [:em-nome-de {:optional true :description "Em nome de quem a secretaria pede (ex.: Presidencia)."}
     [:string {:min 1 :max 80}]]]
   [:fn {:error/message "a consulta avulsa (sem proposicao-id) precisa de um assunto"}
    (fn [m] (or (some? (:proposicao-id m)) (some? (:assunto m))))]])

(defn- pedido->json
  "A entrada validada do agente como o corpo JSON da tela: a mesma validacao de `adapters/in` vale nos dois caminhos."
  [{:keys [proposicao-id assunto prazo em-nome-de]}]
  (cond-> {}
    proposicao-id (assoc "proposicao-id" (str proposicao-id))
    assunto (assoc "assunto" assunto)
    prazo (assoc "prazo" prazo)
    em-nome-de (assoc "em-nome-de" em-nome-de)))

(def ComissoesDoEncaminhamento
  [:vector {:min 1 :max 10 :description "As comissoes que vao dar parecer, com o relator de cada uma se ja' se sabe."}
   [:map {:closed true}
    [:comissao-id {:description "Id da comissao (vem de GET /legislativo/comissoes, na tela)."} :uuid]
    [:relator-id {:optional true :description "Id do vereador relator; se omitido, a Mesa designa depois."} :uuid]]])

(def EncaminharAsComissoes
  [:map {:closed true}
   [:proposicao-id {:description "A materia a encaminhar."} :uuid]
   [:comissoes ComissoesDoEncaminhamento]])

(def DesignarRelator
  [:map {:closed true}
   [:parecer-id {:description "O parecer de comissao em curso (o parecer da comissao sobre a materia), nao o parecer juridico."} :uuid]
   [:relator-id {:description "O vereador que passa a relatar."} :uuid]])

(defn- comissoes->json [comissoes]
  {"comissoes" (mapv (fn [{:keys [comissao-id relator-id]}]
                       (cond-> {"comissao-id" (str comissao-id)}
                         relator-id (assoc "relator-id" (str relator-id))))
                     comissoes)})

(def ^:private entradas-juridico
  [(catalogo/entrada
    {:nome "vereadores_da_casa"
     :descricao (str "Lista os vereadores com mandato vigente na Casa: id, nome de exibicao e partido. Use para achar "
                     "o vereador-id de quem sera relator antes de propor designar_relator ou encaminhar_as_comissoes "
                     "(ex.: 'a vereadora Ana Prado'). Nao traz CPF nem contato.")
     :classe :leitura
     ;; fatia 4 da Clara: quem esta' em exercicio e' publico (portal /vereadores); quem consulta tambem le
     :papeis #{"secretario" "vereador" "juridico" "auditor" "admin_ente"}
     :entrada [:map {:closed true}]
     :saida wire-jur/VereadoresDaCasaOut
     :rotas #{}
     :executar (fn [{:keys [colegas-da-casa]} ator _]
                 (adapters-out-juridico/vereadores->wire ((or colegas-da-casa (constantly [])) (:ente-id ator))))})
   (catalogo/entrada
    {:nome "comissoes_da_casa"
     :descricao (str "Lista as comissoes vigentes da Casa (sem a Mesa), com o id de cada uma. Use antes de propor "
                     "encaminhar_as_comissoes, para saber o comissao-id de 'Comissao de Justica e Redacao' e das demais.")
     :classe :leitura
     :papeis #{"secretario"}
     :entrada [:map {:closed true}]
     :saida wire-jur/ComissoesOut
     :rotas #{:legislativo/comissoes}
     :executar (fn [{:keys [comissoes-vigentes]} ator _]
                 (adapters-out-juridico/comissoes->wire
                   (controllers/comissoes-da-casa (or comissoes-vigentes (constantly [])) (:ente-id ator))))})
   (catalogo/entrada
    {:nome "pareceres_juridicos_da_materia"
     :descricao (str "Lista os pareceres juridicos ASSINADOS sobre uma proposicao (com relatorio, fundamentacao, "
                     "conclusao, quem assinou com a OAB e o hash do texto) e os pedidos de parecer ainda abertos. O "
                     "parecer juridico e' OPINATIVO: orienta a Casa, nao decide a materia. Use para 'o juridico ja' "
                     "opinou sobre o PL 12/2026?'. Nunca chame de parecer o texto de uma IA.")
     :classe :leitura
     :papeis #{"secretario" "vereador" "juridico"}
     :entrada IdentificacaoMateria
     :saida wire-jur/PareceresDaMateriaOut
     :rotas #{:legislativo/pareceres-juridicos-da-materia}
     :executar (fn [{:keys [repo-legislativo]} ator m]
                 (let [ente-id (:ente-id ator)]
                   (when-let [id (resolver-id repo-legislativo ente-id m)]
                     (when (repo/buscar-proposicao repo-legislativo ente-id id)
                       (adapters-out-juridico/da-materia->wire
                         (controllers/pareceres-juridicos-da-materia repo-legislativo ente-id id))))))})
   (catalogo/entrada
    {:nome "pedir_parecer_juridico"
     :descricao (str "Prepara o pedido de parecer juridico da Casa, sobre uma proposicao ou como consulta avulsa da "
                     "Presidencia. Voce NAO pede: isto cria uma PROPOSTA, e a secretaria le e confirma na tela "
                     "Propostas — so' entao o pedido entra na fila do advogado. O parecer e' opinativo e assinado "
                     "pelo advogado; voce nao redige nem assina parecer.")
     :classe :ato
     :ritual :confirmar
     :papeis #{"secretario"}
     :entrada PedirParecerJuridico
     :saida wire-jur/PedidoJuridicoOut
     :rotas #{:legislativo/pedir-parecer-juridico}
     :apresentar (fn [{:keys [repo-legislativo]} ator m]
                   (let [ente-id (:ente-id ator)
                         {:keys [assunto prazo em-nome-de proposicao-id]} (adapters-in-juridico/pedido->dominio (pedido->json m))
                         materia (some->> proposicao-id (materia-ref repo-legislativo ente-id))]
                     (when (or (nil? proposicao-id) materia)
                       {:titulo (if materia
                                  (str "Pedir parecer jurídico sobre " (:ref materia))
                                  "Pedir parecer jurídico (consulta avulsa)")
                        :texto (str (if materia
                                      (str "A secretaria pede ao jurídico da Casa o parecer sobre " (:ref materia)
                                           " — " (:ementa materia) ".")
                                      "A secretaria faz uma consulta avulsa ao jurídico da Casa, sem matéria.")
                                    "\nAssunto: " assunto
                                    (when prazo (str "\nPrazo: " prazo))
                                    (when em-nome-de (str "\nEm nome de: " em-nome-de))
                                    "\nO parecer jurídico é opinativo e será assinado pelo advogado da Casa.")})))
     :executar (fn [{:keys [repo-legislativo nome-na-casa juridicos-a-avisar]} ator m]
                 (some-> (controllers/pedir-parecer-juridico! repo-legislativo (or nome-na-casa (constantly nil))
                                                              juridicos-a-avisar ator
                                                              (adapters-in-juridico/pedido->dominio (pedido->json m)))
                         adapters-out-juridico/pedido->wire))})
   (catalogo/entrada
    {:nome "encaminhar_as_comissoes"
     :descricao (str "Prepara o encaminhamento de uma proposicao as comissoes: abre um parecer por comissao, com o "
                     "relator de cada uma se ja' se sabe. Voce NAO encaminha: isto cria uma PROPOSTA, e a secretaria "
                     "le e confirma na tela Propostas. Os ids das comissoes vem do cadastro da Casa; comissao ou "
                     "relator que nao sao desta Casa invalidam o pedido.")
     :classe :ato
     :ritual :confirmar
     :papeis #{"secretario"}
     :entrada EncaminharAsComissoes
     :saida wire-jur/PareceresAbertosOut
     :rotas #{:legislativo/encaminhar-comissoes}
     :apresentar (fn [{:keys [repo-legislativo resolver-comissoes nomes-de-vereadores vereador-vinculado?]} ator
                      {:keys [proposicao-id comissoes]}]
                   (let [ente-id (:ente-id ator)
                         itens (adapters-in-juridico/comissoes->dominio (comissoes->json comissoes))]
                     (when-let [materia (materia-ref repo-legislativo ente-id proposicao-id)]
                       (let [nomes-comissao ((or resolver-comissoes (constantly {})) ente-id (mapv :comissao-id itens))
                             nomes-relator ((or nomes-de-vereadores (constantly {})) ente-id (keep :relator-id itens))]
                         (doseq [{:keys [comissao-id relator-id]} itens]
                           (when-not (contains? nomes-comissao comissao-id) (invalido! "comissao inexistente nesta Casa" :comissoes))
                           (when (and relator-id (not ((or vereador-vinculado? (constantly false)) ente-id relator-id)))
                             (invalido! "relator nao e' vereador desta Casa" :relator-id)))
                         {:titulo (str "Encaminhar " (:ref materia) " às comissões")
                          :texto (str "A secretaria encaminha " (:ref materia) " — " (:ementa materia)
                                      ", abrindo um parecer em cada comissão:\n"
                                      (apply str (for [{:keys [comissao-id relator-id]} itens]
                                                   (str "• " (get nomes-comissao comissao-id)
                                                        (if relator-id
                                                          (str " — relator: " (or (get nomes-relator relator-id) "vereador da Casa"))
                                                          " — relator a designar")
                                                        "\n")))
                                      "O encaminhamento usa o rito de parecer configurado pela Casa.")}))))
     :executar (fn [{:keys [repo-legislativo resolver-comissoes vereador-vinculado? nomes-de-vereadores]} ator
                    {:keys [proposicao-id comissoes]}]
                 (some-> (controllers/encaminhar-as-comissoes! repo-legislativo (or resolver-comissoes (constantly {}))
                                                               (or vereador-vinculado? (constantly false))
                                                               (or nomes-de-vereadores (constantly {}))
                                                               ator proposicao-id
                                                               (adapters-in-juridico/comissoes->dominio (comissoes->json comissoes)))
                         adapters-out-juridico/pareceres-abertos->wire))})
   (catalogo/entrada
    {:nome "designar_relator"
     :descricao (str "Prepara a designacao (ou troca) do relator de um parecer de comissao em curso. Voce NAO "
                     "designa: isto cria uma PROPOSTA, e a secretaria le e confirma na tela Propostas. Vale so' "
                     "para parecer de comissao ainda nao terminal e vereador desta Casa.")
     :classe :ato
     :ritual :confirmar
     :papeis #{"secretario"}
     :entrada DesignarRelator
     :saida wire-jur/RelatorDesignadoOut
     :rotas #{:legislativo/designar-relator}
     :apresentar (fn [{:keys [repo-legislativo resolver-comissoes nomes-de-vereadores vereador-vinculado?]} ator
                      {:keys [parecer-id relator-id]}]
                   (let [ente-id (:ente-id ator)]
                     (when ((or vereador-vinculado? (constantly false)) ente-id relator-id)
                       (when-let [pc (repo/buscar-parecer repo-legislativo ente-id parecer-id)]
                         (let [comissao (get ((or resolver-comissoes (constantly {})) ente-id [(:comissao-id pc)]) (:comissao-id pc))
                               relator (get ((or nomes-de-vereadores (constantly {})) ente-id [relator-id]) relator-id)
                               materia (when (= "proposicao" (:objeto-tipo pc))
                                         (materia-ref repo-legislativo ente-id (:objeto-id pc)))]
                           {:titulo (str "Designar " (or relator "o vereador") " relator")
                            :texto (str "A secretaria designa " (or relator "o vereador indicado")
                                        " relator do parecer da " (or comissao "comissão")
                                        (when materia (str " sobre " (:ref materia) " — " (:ementa materia)))
                                        ".")})))))
     :executar (fn [{:keys [repo-legislativo vereador-vinculado? nomes-de-vereadores]} ator {:keys [parecer-id relator-id]}]
                 (some-> (controllers/designar-relator-do-parecer! repo-legislativo
                                                                   (or vereador-vinculado? (constantly false))
                                                                   (or nomes-de-vereadores (constantly {}))
                                                                   ator parecer-id relator-id)
                         adapters-out-juridico/relator->wire))})])

;; ---------- ADR-0021 Parte B: o julgamento das contas ----------

(def ^:private RegistrarPrestacao
  [:map {:closed true}
   [:tipo {:description "governo_prefeito (contas do Prefeito: protocola o projeto de decreto legislativo) ou gestao_camara (contas da Mesa: so' acompanhamento)."}
    [:enum "governo_prefeito" "gestao_camara"]]
   [:exercicio {:description "O ano das contas (ex.: 2024)."} [:int {:min 1990 :max 9999}]]
   [:responsavel {:description "Quem prestou as contas: o Prefeito DAQUELE exercicio (ou o Presidente da Mesa)."}
    [:string {:min 1 :max 200}]]
   [:recebida-em {:description "Quando a Camara recebeu o processo do TCE (AAAA-MM-DD)."} [:re #"^\d{4}-\d{2}-\d{2}$"]]
   [:processo-tce {:optional true :description "O numero do processo no Tribunal de Contas."} [:string {:min 1 :max 80}]]
   [:parecer-previo {:optional true :description "O parecer previo do TCE; obrigatorio nas contas do Prefeito."}
    [:enum "favoravel" "favoravel_com_ressalvas" "desfavoravel"]]
   [:comissao-autora-id {:optional true :description "A comissao autora do projeto de decreto legislativo (use comissoes_da_casa); obrigatoria nas contas do Prefeito."}
    :uuid]
   [:situacao-tce {:optional true :description "A situacao do processo no TCE (contas da Mesa)."} [:string {:min 1 :max 500}]]])

(defn- prestacao->json [m]
  (into {} (keep (fn [[k v]] (when (some? v) [(name k) (if (uuid? v) (str v) v)]))) m))

(def ^:private RegistrarNotificacao
  [:map {:closed true}
   [:prestacao-id {:description "A prestacao de contas do Prefeito (use contas_da_casa)."} :uuid]
   [:notificado-em {:description "O dia em que o responsavel foi notificado (AAAA-MM-DD)."} [:re #"^\d{4}-\d{2}-\d{2}$"]]
   [:meio {:description "Como foi notificado (ex.: 'oficio entregue em maos', 'AR dos Correios')."} [:string {:min 1 :max 200}]]])

(def ^:private de-quem-sao-as-contas {"governo_prefeito" "do Prefeito" "gestao_camara" "da Mesa"})

(def ^:private entradas-contas
  [(catalogo/entrada
    {:nome "contas_da_casa"
     :descricao (str "Lista as prestacoes de contas que a Camara recebeu do TCE: as do Prefeito (que a Camara julga) "
                     "e as da Mesa (so' acompanhamento), com o parecer previo, o estado (aguardando notificacao, prazo "
                     "de defesa, pronta para pauta, julgada) e o resultado. Use para 'as contas de 2024 ja' foram "
                     "julgadas?'.")
     :classe :leitura
     :papeis #{"secretario" "vereador" "juridico"}
     :entrada [:map {:closed true}]
     :saida wire-contas/PrestacoesOut
     :rotas #{:legislativo/listar-contas}
     :executar (fn [{:keys [repo-legislativo] :as deps} ator _]
                 (adapters-out-contas/prestacoes->wire
                   (controllers/prestacoes-de-contas repo-legislativo (:ente-id ator)) (hoje deps)))})
   (catalogo/entrada
    {:nome "prestacao_de_contas"
     :descricao (str "A ficha de uma prestacao de contas: parecer previo do TCE, o projeto de decreto legislativo, a "
                     "notificacao e o prazo de defesa do responsavel, se ja' pode ir a pauta (e por que nao), quantos "
                     "votos rejeitam o parecer (2/3 dos membros, CF art. 31 §2) e o resultado em palavras.")
     :classe :leitura
     :papeis #{"secretario" "vereador" "juridico"}
     :entrada [:map {:closed true} [:prestacao-id {:description "Id da prestacao (de contas_da_casa)."} :uuid]]
     :saida wire-contas/PrestacaoOut
     :rotas #{:legislativo/prestacao-contas}
     :executar (fn [{:keys [repo-legislativo membros-da-casa] :as deps} ator {:keys [prestacao-id]}]
                 (let [ente-id (:ente-id ator)]
                   (when-let [p (controllers/prestacao-de-contas repo-legislativo ente-id prestacao-id)]
                     (adapters-out-contas/prestacao->wire
                       p (hoje deps) (controllers/base-do-quorum (or membros-da-casa (constantly 0)) ente-id p)))))})
   (catalogo/entrada
    {:nome "registrar_prestacao_de_contas"
     :descricao (str "Prepara o registro de uma prestacao de contas recebida do TCE. Nas contas do Prefeito, o registro "
                     "PROTOCOLA o projeto de decreto legislativo de autoria da comissao indicada. Voce NAO registra: "
                     "isto cria uma PROPOSTA, e a secretaria le e confirma na tela Propostas. Nao invente parecer nem "
                     "processo: use o que esta' no documento do TCE.")
     :classe :ato
     :ritual :confirmar
     :papeis #{"secretario"}
     :entrada RegistrarPrestacao
     :saida wire-contas/PrestacaoOut
     :rotas #{:legislativo/registrar-contas}
     :apresentar (fn [{:keys [resolver-comissoes]} ator m]
                   (let [{:keys [tipo exercicio responsavel recebida-em processo-tce parecer-previo comissao-autora-id]}
                         (adapters-in-contas/registro->dominio (prestacao->json m))
                         comissao (when comissao-autora-id
                                    (get ((or resolver-comissoes (constantly {})) (:ente-id ator) [comissao-autora-id])
                                         comissao-autora-id))]
                     (when (and comissao-autora-id (nil? comissao))
                       (invalido! "comissao inexistente nesta Casa" :comissao-autora-id))
                     {:titulo (str "Registrar as contas " (de-quem-sao-as-contas tipo) " de " exercicio)
                      :texto (str "A secretaria registra a prestação de contas " (de-quem-sao-as-contas tipo) " do exercício de "
                                  exercicio ", de " responsavel ", recebida em " (logic-contas/data-br recebida-em) "."
                                  (when processo-tce (str "\nProcesso no TCE: " processo-tce))
                                  (when parecer-previo (str "\nParecer prévio: " parecer-previo))
                                  (when comissao
                                    (str "\nO registro protocola o projeto de decreto legislativo de autoria da "
                                         comissao ".")))}))
     :executar (fn [{:keys [repo-legislativo resolver-municipio comissoes-vigentes membros-da-casa] :as deps} ator m]
                 (let [p (controllers/registrar-prestacao! repo-legislativo resolver-municipio
                                                           (or comissoes-vigentes (constantly [])) ator (hoje deps)
                                                           (adapters-in-contas/registro->dominio (prestacao->json m)))]
                   (adapters-out-contas/prestacao->wire
                     p (hoje deps) (controllers/base-do-quorum (or membros-da-casa (constantly 0)) (:ente-id ator) p))))})
   (catalogo/entrada
    {:nome "registrar_notificacao_das_contas"
     :descricao (str "Prepara o registro da notificacao do responsavel pelas contas do Prefeito: a data e o meio. A "
                     "notificacao abre o prazo de defesa (dias da Casa, congelados aqui); a pauta so' aceita o projeto "
                     "depois que o prazo vence ou a defesa e' juntada. Voce NAO registra: isto cria uma PROPOSTA que a "
                     "secretaria confirma na tela Propostas.")
     :classe :ato
     :ritual :confirmar
     :papeis #{"secretario"}
     :entrada RegistrarNotificacao
     :saida wire-contas/PrestacaoOut
     :rotas #{:legislativo/notificar-contas}
     :apresentar (fn [{:keys [repo-legislativo]} ator {:keys [prestacao-id notificado-em meio]}]
                   (when-let [p (controllers/prestacao-de-contas repo-legislativo (:ente-id ator) prestacao-id)]
                     (let [{dia :notificado-em} (adapters-in-contas/notificacao->dominio {"notificado-em" notificado-em
                                                                                          "meio" meio})]
                       {:titulo (str "Registrar a notificação das contas de " (:exercicio p))
                        :texto (str "A secretaria registra que " (:responsavel p) " foi notificado em "
                                    (logic-contas/data-br dia) " (" meio ") sobre as contas do exercício de "
                                    (:exercicio p) ". O prazo de defesa começa a correr e fica fixado.")})))
     :executar (fn [{:keys [repo-legislativo membros-da-casa] :as deps} ator {:keys [prestacao-id notificado-em meio]}]
                 (let [{:keys [prestacao]} (controllers/notificar-prestacao!
                                             repo-legislativo ator (hoje deps) prestacao-id
                                             (adapters-in-contas/notificacao->dominio {"notificado-em" notificado-em
                                                                                       "meio" meio}))]
                   (when prestacao
                     (adapters-out-contas/prestacao->wire
                       prestacao (hoje deps)
                       (controllers/base-do-quorum (or membros-da-casa (constantly 0)) (:ente-id ator) prestacao)))))})])

(def entradas (into [] cat [entradas-materia entradas-requerimento entradas-conferencia entradas-juridico entradas-contas]))
