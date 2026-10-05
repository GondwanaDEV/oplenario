(ns oplenario.comunicacao.diplomat.catalogo
  "As entradas do CATALOGO DE ACOES dos comunicados internos (ADR-0009, ADR-0020): ler a caixa, ler um comunicado, ler
  o painel de leitura, enviar e registrar a ciencia. As mesmas acoes das rotas da tela, com o mesmo controller.

  O agente LE sem marcar: a caixa lida por um agente nao grava `recebido`, e o comunicado lido por ele nao grava `lido`
  — a marca e' a pessoa abrindo a caixa, nao um agente lendo por ela. ENVIAR e' `ato`: o agente so' PROPOE (ADR-0012);
  a pessoa le os destinatarios e o texto em /propostas e confirma. `registrar_ciencia` tambem e' ato (a pessoa
  confirma), e o host nao o poe em nenhum conjunto de agente: a ciencia e' a prova de que a PESSOA reconheceu.

  `deps` = o mapa de deps do catalogo do host; o modulo le o seu em `(:comunicacao deps)` (ver controllers)."
  (:require [clojure.string :as str]
            [oplenario.comunicacao.adapters.in.comunicado :as adapters-in]
            [oplenario.comunicacao.adapters.out.comunicado :as adapters-out]
            [oplenario.comunicacao.controllers :as controllers]
            [oplenario.comunicacao.wire.in :as wire-in]
            [oplenario.comunicacao.wire.out :as wire]
            [oplenario.kernel.catalogo :as catalogo]))

(set! *warn-on-reflection* true)

(def ^:private papeis-da-casa
  "Os papeis dos dois publicos de agente que PROPOEM estas acoes (secretaria e vereador). Na TELA, toda pessoa da Casa
  usa a caixa (a regra mora no controller); pelo agente, enviar e dar ciencia so' quem tem um destes papeis — o agente
  nunca tem mais que a pessoa (Eixo 3.2), e o administrador/juridico/auditor sem um destes papeis nao delega comunicado
  a agente."
  #{"secretario" "vereador"})

(def ^:private papeis-de-leitura
  "Quem LE a caixa, o comunicado e o painel de leitura pelo agente: os dois publicos acima e o de consulta (juridico,
  auditor, admin_ente; fatia 4 da Clara). As rotas da tela (`/meu/comunicados`, `/comunicados/:id`,
  `/comunicados/:id/leitura`) so' autenticam e o controller atende toda pessoa da Casa, decidindo por linha (destinatario,
  quem enviou, secretaria e admin): o agente nunca le mais que a tela."
  (into papeis-da-casa #{"juridico" "auditor" "admin_ente"}))

(defn- d [deps] (or (:comunicacao deps) (throw (ex-info "catalogo sem as deps de comunicacao" {}))))

(def ^:private EntradaComunicado
  [:map {:closed true}
   [:comunicado-id {:description "O id do comunicado (vem da caixa ou dos enviados)."} :uuid]])

(def ^:private EntradaEnvio
  [:map {:closed true}
   [:assunto {:description "O assunto, curto: 'Sessão extraordinária na quinta'."} [:string {:min 1 :max 200}]]
   [:corpo {:description "O texto do comunicado, completo."} [:string {:min 1 :max 20000}]]
   [:exige-ciencia {:optional true :description "true se cada pessoa precisa confirmar 'Estou ciente'."} :boolean]
   [:ciencia-ate {:optional true :description "Prazo da ciencia, AAAA-MM-DD (so' com exige-ciencia)."}
    [:re #"^\d{4}-\d{2}-\d{2}$"]]
   [:substitui-id {:optional true :description "Para CORRIGIR um comunicado: o id do que este substitui."} :uuid]
   [:objeto {:optional true :description "Link para algo do sistema: uma sessao, uma proposicao ou um protocolo."}
    [:map {:closed true} [:tipo (into [:enum] wire-in/tipos-de-objeto)] [:id :uuid]]]
   [:destinos {:description (str "A quem: [{tipo, alvo-id}]. tipo = pessoa (alvo-id = identidade-id), vereador "
                                 "(alvo-id = id do vereador), setor, comissao, ou todos (todos os setores, sem alvo-id).")}
    [:vector {:min 1 :max 20}
     [:map {:closed true}
      [:tipo [:enum "pessoa" "vereador" "setor" "comissao" "todos"]]
      [:alvo-id {:optional true} :uuid]]]]])

(defn- caixa [deps ator _]
  (let [dd (d deps)] (adapters-out/caixa->wire (controllers/caixa! dd ator) (controllers/hoje dd))))

(defn- ler [deps ator {:keys [comunicado-id]}]
  (let [dd (d deps)]
    (when-let [c (controllers/ler! dd ator comunicado-id)]
      (adapters-out/comunicado->wire c (controllers/hoje dd) (controllers/extras-do-detalhe dd ator c)))))

(defn- leitura [deps ator {:keys [comunicado-id]}]
  (let [dd (d deps)]
    (when-let [{:keys [comunicado linhas]} (controllers/leitura dd ator comunicado-id)]
      (adapters-out/leitura->wire comunicado linhas (controllers/hoje dd)))))

(defn- enviar [deps ator entrada]
  (let [dd (d deps)
        {:keys [comunicado sem-acesso]} (controllers/enviar! dd ator (adapters-in/envio->dominio entrada))]
    (adapters-out/enviado->wire comunicado (controllers/hoje dd) (controllers/extras-do-detalhe dd ator comunicado)
                                sem-acesso)))

(defn- apresentar-envio
  "O que a pessoa le antes de confirmar o envio proposto pelo agente: a quem vai (e quantas pessoas), o prazo e o texto."
  [deps ator entrada]
  (let [pedido (adapters-in/envio->dominio entrada)
        {:keys [destinos destinatarios sem-acesso]} (controllers/previa-do-envio (d deps) ator pedido)
        para (str/join ", " (map (fn [x] (if (= "todos" (:tipo x)) "todos os setores" (:alvo-nome x))) destinos))]
    {:titulo (str "Enviar comunicado: " (:assunto pedido))
     :texto (str "Para: " para " (" (count destinatarios) (if (= 1 (count destinatarios)) " pessoa" " pessoas") ")"
                 (when (pos? sem-acesso) (str "; " sem-acesso " sem acesso ao sistema não recebem"))
                 (when (:exige-ciencia pedido)
                   (str "\nExige ciência" (when-let [p (:ciencia-ate pedido)] (str " até " p))))
                 "\n\n" (:assunto pedido) "\n\n" (:corpo pedido))}))

(defn- ciencia [deps ator {:keys [comunicado-id]}]
  (let [dd (d deps)]
    (when-let [{:keys [comunicado marcas]} (controllers/registrar-ciencia! dd ator comunicado-id)]
      (adapters-out/ciencia->wire comunicado marcas (controllers/hoje dd)))))

(defn- apresentar-ciencia [deps ator {:keys [comunicado-id]}]
  (when-let [c (controllers/ler! (d deps) ator comunicado-id)]
    {:titulo (str "Registrar ciência do " (:protocolo c))
     :texto (str "Você confirma que está ciente do comunicado " (:protocolo c) " — " (:assunto c) ".")}))

(def entradas
  [(catalogo/entrada
    {:nome "ler_caixa"
     :descricao (str "Le a caixa de comunicados internos da pessoa: os 50 mais recentes enviados a ela (por um servidor, "
                     "pela secretaria, pela Mesa), com o caminho (direto, setor, comissao), se ja' leu, se o comunicado "
                     "pede ciencia e ate' quando. Use para 'tenho algum comunicado?', 'o que falta eu dar ciencia?'. "
                     "Ler pelo agente nao marca nada como recebido ou lido.")
     :classe :leitura
     :papeis papeis-de-leitura
     :entrada [:map {:closed true}]
     :saida wire/CaixaOut
     :rotas #{:comunicacao/caixa}
     :executar caixa})
   (catalogo/entrada
    {:nome "ler_comunicado"
     :descricao (str "Le um comunicado interno inteiro pelo id: o texto, quem enviou, a quem foi, os anexos, o link para "
                     "sessao/proposicao/protocolo, se pede ciencia e se foi substituido por outro. So' destinatario, quem "
                     "enviou, a secretaria e o administrador da Casa veem. Ler pelo agente nao marca como lido.")
     :classe :leitura
     :papeis papeis-de-leitura
     :entrada EntradaComunicado
     :saida wire/ComunicadoOut
     :rotas #{:comunicacao/ler}
     :executar ler})
   (catalogo/entrada
    {:nome "ler_leitura_do_comunicado"
     :descricao (str "O painel de leitura de um comunicado: quantos receberam, leram e deram ciencia, e uma linha por "
                     "pessoa com o caminho e a hora de cada marca (e se a ciencia venceu). So' quem enviou, a secretaria e "
                     "o administrador da Casa. Use para 'quem ainda nao leu o COM-2026-000123?'.")
     :classe :leitura
     :papeis papeis-de-leitura
     :entrada EntradaComunicado
     :saida wire/LeituraOut
     :rotas #{:comunicacao/leitura}
     :executar leitura})
   (catalogo/entrada
    {:nome "enviar_comunicado"
     :descricao (str "Envia um comunicado interno da Casa a pessoas, vereadores, setores, comissoes ou todos os setores, "
                     "com assunto, texto, ciencia opcional (com prazo) e link para sessao/proposicao/protocolo. Enviar a "
                     "setor, comissao ou todos so' pode a secretaria, o administrador ou a Mesa. O agente so' PROPOE: a "
                     "pessoa confere a lista e o texto e confirma na plataforma. Para corrigir um enviado, mande outro "
                     "com substitui-id.")
     :classe :ato
     :ritual :confirmar
     :papeis papeis-da-casa
     :entrada EntradaEnvio
     :saida wire/EnviadoOut
     :rotas #{:comunicacao/enviar}
     :executar enviar
     :apresentar apresentar-envio})
   (catalogo/entrada
    {:nome "registrar_ciencia"
     :descricao (str "Registra 'Estou ciente' de um comunicado que pede ciencia, para a propria pessoa destinataria. E' "
                     "a prova de que a pessoa reconheceu o comunicado; idempotente (a primeira ciencia vale).")
     :classe :ato
     :ritual :confirmar
     :papeis papeis-da-casa
     :entrada EntradaComunicado
     :saida wire/CienciaOut
     :rotas #{:comunicacao/ciencia}
     :executar ciencia
     :apresentar apresentar-ciencia})])
