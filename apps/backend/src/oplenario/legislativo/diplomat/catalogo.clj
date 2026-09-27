(ns oplenario.legislativo.diplomat.catalogo
  "As entradas do CATALOGO DE ACOES que sao do legislativo (ADR-0009). Cada uma e' a mesma acao de uma rota da tela —
  mesmo controller, mesmo wire/out — com descricao para o agente. As primeiras (§22.11 Eixo 2, 'migracao'): as
  consultas que um agente da Casa precisa antes de tudo: a situacao da materia e a tramitacao dela. B.6 (ADR-0012): o
  primeiro ATO — protocolar o requerimento do vereador —, que o agente so' PROPOE; a pessoa confirma e assina na tela."
  (:require [oplenario.kernel.autorizacao :as authz]
            [oplenario.kernel.catalogo :as catalogo]
            [oplenario.kernel.tempo :as tempo]
            [oplenario.legislativo.adapters.in.proposicao :as adapters-in-proposicao]
            [oplenario.legislativo.adapters.out.proposicao :as adapters-out-proposicao]
            [oplenario.legislativo.adapters.out.requerimento :as adapters-out-requerimento]
            [oplenario.legislativo.components.assinador-icp :as assinador-icp]
            [oplenario.legislativo.components.repositorio :as repo]
            [oplenario.legislativo.controllers :as controllers]
            [oplenario.legislativo.logic :as logic]
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
     :papeis #{"secretario" "vereador" authz/papel-agente-institucional}
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
     :papeis #{"secretario" "vereador"}
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
   [:modelo {:description "fornecedor:modelo que redigiu."} [:string {:min 1 :max 200}]]])

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

(def entradas (into [] cat [entradas-materia entradas-requerimento entradas-conferencia]))
