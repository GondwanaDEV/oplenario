(ns oplenario.admin-sistema.adapters.out.ente
  "Gate de SAIDA do registro de Casas (ADR-0016): allowlist + validacao contra wire/out (drift = 500, nunca
  resposta malformada). O CPF do administrador nunca sai daqui — nem entra no registro."
  (:require [malli.core :as m]
            [malli.error :as me]
            [oplenario.admin-sistema.logic :as logic]
            [oplenario.admin-sistema.wire.out.ente :as wire]
            [oplenario.admin-sistema.wire.out.exportacao :as wire-exportacao]))

(defn- ->str [x] (some-> x str))

(defn- validado [schema nome out]
  (when-not (m/validate schema out)
    (throw (ex-info (str "projecao viola o contrato " nome " (bug de servidor)")
                    {:erros (me/humanize (m/explain schema out))})))
  out)

(defn- casa [c]
  {:ente-id (str (:ente-id c)) :nome (:nome c) :nome-curto (:nome-curto c) :uf (:uf c)
   :municipio (when (:municipio-ibge c) {:ibge (:municipio-ibge c) :nome (:municipio-nome c)})
   :estado (:estado c) :criada-em (->str (:criado-em c)) :convite-enviado-em (->str (:convite-enviado-em c))
   :ativada-em (->str (:ativada-em c))
   :restricao (when (and (= "suspenso" (:estado c)) (:motivo-restricao c))
                {:motivo (:motivo-restricao c) :desde (->str (:restrita-desde c))})
   :suspensao-agendada (some? (:suspensao-agendada c))
   :encerrada-em (->str (:encerrada-em c))
   :destino-acervo-url (:destino-acervo-url c)})

(defn- pedido [p]
  (when p
    {:id (str (:id p)) :ente-id (str (:ente-id p)) :casa-nome (:casa-nome p) :acao (:acao p) :motivo (:motivo p)
     :justificativa (:justificativa p) :estado (:estado p) :pedido-por-id (str (:pedido-por p))
     :pedido-por (:pedido-por-nome p) :pedido-em (->str (:pedido-em p)) :confirmar-ate (->str (:confirmar-ate p))
     :efetivado-em (->str (:efetivado-em p))}))

(defn lista->wire [{:keys [casas pendentes]}]
  (validado wire/ListaDeCasasOut "ListaDeCasasOut"
            {:casas (mapv casa casas)
             :resumo {:total (count casas)
                      :ativas (count (filter #(= "ativo" (:estado %)) casas))
                      :aguardando-admin (count (filter #(= "provisionar" (:estado %)) casas))
                      :suspensas (count (filter #(= "suspenso" (:estado %)) casas))
                      :encerradas (count (filter #(= "encerrado" (:estado %)) casas))}
             :pendentes (mapv pedido pendentes)}))

;; ---- ADR-0018 (fatia 2): a exportacao completa (9.6). O manifesto sai RESUMIDO (`logic/resumo-do-manifesto`) e a chave
;; do object storage nunca sai; o arquivo, so' pelo download do administrador da Casa (`exportacao->download`). ----

(defn exportacao
  "A linha -> o mapa do wire (sem validar; quem compoe valida o todo)."
  [e]
  (when e
    {:id (str (:id e)) :estado (:estado e) :solicitada-em (->str (:solicitada-em e))
     :solicitada-por (:solicitada-por-tipo e) :concluida-em (->str (:concluida-em e)) :sha256 (:sha256 e)
     :bytes (:bytes e) :manifesto (logic/resumo-do-manifesto (:manifesto e)) :erro (:erro e)
     :confirmada-em (->str (:confirmada-em e)) :confirmada-por (:confirmada-por-tipo e)
     :oficio (:confirmacao-texto e)}))

(defn exportacao->wire [e] (validado wire-exportacao/ExportacaoOut "ExportacaoOut" (exportacao e)))

(defn da-casa->wire [{:keys [disponivel em-encerramento exportacoes]}]
  (validado wire-exportacao/ExportacoesDaCasaOut "ExportacoesDaCasaOut"
            {:disponivel (boolean disponivel) :em-encerramento (boolean em-encerramento)
             :exportacoes (mapv exportacao exportacoes)}))

(defn exportacao->download
  "Resposta Ring do arquivo da exportacao, em STREAM (o InputStream do object storage, sem materializar em heap). O
  nome do arquivo leva a data e o comeco do hash, para a Casa conferir o que guardou. A marca `:auditoria` registra o
  download na trilha da Casa como leitura sensivel (a chave nao vai para o fio)."
  [e in]
  {:status 200
   :headers {"Content-Type" "application/zip"
             "Content-Disposition" (str "attachment; filename=\"exportacao-completa-"
                                        (subs (str (:concluida-em e)) 0 (min 10 (count (str (:concluida-em e)))))
                                        "-" (subs (str (:sha256 e)) 0 12) ".zip\"")
             "X-Content-SHA256" (str (:sha256 e))}
   :body in
   :auditoria {:classe "leitura_sensivel" :rotulo "baixou a exportacao completa da Camara"}})

(defn- encerramento [e]
  (when e
    {:em-curso (boolean (:em-curso e)) :desde (->str (:desde e))
     :exportacoes (mapv exportacao (:exportacoes e))
     :confirmacao (exportacao (:confirmacao e))
     :apagamento-possivel-em (->str (:apagamento-possivel-em e))
     :pode-pedir-apagamento (boolean (:pode-pedir-apagamento e))
     :exportacao-disponivel (boolean (:exportacao-disponivel e))
     :apagamento-disponivel (boolean (:apagamento-disponivel e))
     :apagamento-pendente (pedido (:apagamento-pendente e))
     :destino-acervo-url (:destino-acervo-url e)
     :encerrada-em (->str (:encerrada-em e))
     :apagamento (:apagamento e)}))

(defn ficha->wire [{:keys [primeiro-admin atuacao] :as f}]
  (validado wire/FichaDaCasaOut "FichaDaCasaOut"
            {:casa (casa (:casa f))
             :primeiro-admin (when primeiro-admin (select-keys primeiro-admin [:nome :email]))
             :pedido-aberto (pedido (:pedido-aberto f))
             :encerramento (encerramento (:encerramento f))
             :atuacao (mapv (fn [a] {:id (str (:id a)) :em (str (:em a)) :acao (:acao a)
                                     :operador (:operador-nome a) :detalhe (or (:detalhe a) {}) :selo (:selo a)})
                            atuacao)}))

(defn provisionada->wire [{c :casa convite :convite}]
  (validado wire/ProvisionadaOut "ProvisionadaOut" {:casa (casa c) :convite (name convite)}))

(defn casa->wire [c] (validado wire/CasaOut "CasaOut" (casa c)))

(defn transicao->wire
  "`r` = a Casa, ou {:casa :pedido :efeito [:erro]}."
  [r]
  (let [{c :casa p :pedido efeito :efeito erro :erro} (if (contains? r :casa) r {:casa r})]
    (validado wire/TransicaoOut "TransicaoOut" (cond-> {:casa (casa c) :pedido (pedido p) :efeito (some-> efeito name)}
                                                 erro (assoc :erro erro)))))
