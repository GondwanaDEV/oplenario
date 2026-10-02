(ns oplenario.sessoes.diplomat.catalogo
  "As entradas do CATALOGO DE ACOES que sao de sessoes (ADR-0009). A primeira: a pauta de uma sessao — por padrao a
  da sessao em curso ou da proxima, que e' como uma pessoa pergunta ('o que vai ser votado na proxima sessao?').
  So' sessao de transmissao PUBLICA: sessao secreta nunca vai a IA (§22.11, B1), e o agente e' a IA."
  (:require [clojure.string :as str]
            [oplenario.kernel.catalogo :as catalogo]
            [oplenario.kernel.tempo :as tempo]
            [oplenario.sessoes.adapters.out.livro-atas :as adapters-out-livro]
            [oplenario.sessoes.adapters.out.pauta :as adapters-out-pauta]
            [oplenario.sessoes.adapters.out.publicacao-pauta :as adapters-out-pub]
            [oplenario.sessoes.components.repositorio :as repo]
            [oplenario.sessoes.controllers :as controllers]
            [oplenario.sessoes.wire.out :as wire]))

(set! *warn-on-reflection* true)

(def ^:private em-andamento-ou-futura #{"aberta" "suspensa" "agendada"})

(defn- publica? [s] (true? (:transmite-publica s)))

(defn- sessao-da-vez
  "A sessao em curso (aberta/suspensa) ou, nao havendo, a proxima agendada — entre as que o ator pode ver E que sao
  publicas. A listagem do controller ja' vem filtrada por visibilidade e ordenada (em curso primeiro, agendadas por
  data crescente)."
  [repo-sessoes ator]
  (:id (first (filter #(and (em-andamento-ou-futura (:estado %)) (publica? %))
                      (controllers/listar-sessoes repo-sessoes ator)))))

(defn- sessao-publica? [repo-sessoes ator sid]
  (publica? (repo/buscar-sessao repo-sessoes (:ente-id ator) sid)))

(defn- deps-publicacao [deps]
  (select-keys deps [:repo-sessoes :situacao-de-parecer :cargo-na-mesa :nome-na-casa :vereadores-a-avisar]))

(defn- agora [{:keys [relogio]}] (tempo/agora (or relogio (tempo/relogio-sistema))))

(def ^:private rotulo-tipo-sessao
  {"ordinaria" "ordinária" "extraordinaria" "extraordinária" "solene" "solene" "secreta" "secreta" "especial" "especial"})

(def ^:private rotulo-aviso
  {"sem-parecer-comissao" "sem parecer da comissão"
   "pedido-juridico-pendente" "com pedido de parecer jurídico pendente"})

(defn- texto-da-proposta
  "O que a pessoa le antes de confirmar a publicacao proposta pelo agente: o tamanho da pauta, se e' republicacao (e a
  justificativa), e os AVISOS — os mesmos da tela, para ninguem publicar sem ve-los."
  [{:keys [itens-na-pauta republicacao ultima avisos antecedencia avisos-indisponiveis]} justificativa]
  (let [materias (filter :proposicao-id avisos)
        linhas (cond-> [(str "A pauta tem " itens-na-pauta (if (= 1 itens-na-pauta) " item." " itens."))]
                 republicacao (conj (str "Será uma republicação (a versão publicada é a v" (:numero-versao ultima)
                                         "). O que mudou: " (or (not-empty justificativa) "— (informe na tela)") "."))
                 (seq materias) (conj (str (count materias) " aviso(s) sobre matérias: "
                                           (str/join "; " (map #(get rotulo-aviso (:tipo %) (:tipo %)) materias)) "."))
                 avisos-indisponiveis (conj "Os avisos sobre as matérias não puderam ser conferidos agora.")
                 (and antecedencia (not (:cumprida antecedencia)))
                 (conj (str "Fora da antecedência mínima da Casa (" (:minimo-horas antecedencia) " h; faltam "
                            (:horas-reais antecedencia) " h para o início).")))]
    (str/join "\n" (conj linhas "Os avisos não impedem a publicação."))))

(def entradas
  [(catalogo/entrada
    {:nome "pauta_da_sessao"
     :descricao (str "Lista a pauta de uma sessao plenaria: os itens na ordem, com a proposicao de cada um (numero e "
                     "ementa) e o item em apreciacao agora, se houver. Sem sessao informada, usa a sessao em curso "
                     "ou, nao havendo, a proxima agendada — para 'o que vai ser votado na proxima sessao?'.")
     :classe :leitura
     :papeis #{"secretario" "vereador"}
     :entrada [:map {:closed true}
               [:sessao-id {:optional true :description "Id da sessao; ausente = a sessao em curso ou a proxima."}
                :uuid]]
     :saida wire/PautaOut
     :rotas #{:sessoes/pauta}
     :executar (fn [{:keys [repo-sessoes resumir-proposicoes]} ator {:keys [sessao-id]}]
                 (when-let [sid (if sessao-id
                                  (when (sessao-publica? repo-sessoes ator sessao-id) sessao-id)
                                  (sessao-da-vez repo-sessoes ator))]
                   (when-let [p (controllers/pauta-da-sessao repo-sessoes ator sid)]
                     (adapters-out-pauta/pauta->wire
                      p (controllers/resumos-da-pauta (or resumir-proposicoes (fn [_ _] {})) (:ente-id ator) p)))))})
   ;; Onda E — o livro de atas. Mesma regra da pauta: so' sessao publica (e nao secreta) vai a IA; por isso a
   ;; ferramenta le pelo caminho do PORTAL, que ja' filtra, e nunca devolve nome de servidor.
   (catalogo/entrada
    {:nome "ata_da_sessao"
     :descricao (str "Le a ata publicada de uma sessao plenaria: o texto da versao vigente, o numero da versao (maior "
                     "que 1 = retificada, com o motivo de cada retificacao) e como ela foi apresentada ao plenario. Sem "
                     "sessao informada, usa a ata publicada mais recente — para 'o que ficou registrado na ultima "
                     "sessao?'. So' atas de sessoes de transmissao publica.")
     :classe :leitura
     :papeis #{"secretario" "vereador"}
     :entrada [:map {:closed true}
               [:sessao-id {:optional true :description "Id da sessao; ausente = a ata publicada mais recente."}
                :uuid]]
     :saida wire/AtaDoLivroOut
     :rotas #{:sessoes/livro-atas :sessoes/ata-do-livro}
     :executar (fn [{:keys [repo-sessoes]} ator {:keys [sessao-id]}]
                 (let [ente (:ente-id ator)]
                   (when-let [sid (or sessao-id
                                      (:sessao-id (first (controllers/livro-de-atas-publico repo-sessoes ente))))]
                     (some-> (controllers/ata-do-livro-publica repo-sessoes ente sid nil)
                             (adapters-out-livro/ata-do-livro->wire true)))))})
   ;; ADR-0019 fatia 3 (Eixo 7): publicar a pauta e' ATO (congela a pauta oficial) — o agente PROPOE, a pessoa confere os
   ;; avisos e confirma (ADR-0012). Nao e' ato pessoal (voto, presenca, conducao ao vivo): e' ato de expediente.
   (catalogo/entrada
    {:nome "publicar_pauta"
     :descricao (str "Publica a pauta de uma sessao plenaria: congela a pauta atual como a pauta OFICIAL, a que o portal "
                     "do cidadao e a TV do plenario mostram. Voce NAO publica: isto cria uma PROPOSTA, e a pessoa confere "
                     "os avisos (materia sem parecer da comissao, pedido de parecer juridico pendente, antecedencia "
                     "minima da Casa) e confirma na tela. Republicar exige `justificativa` (o que mudou desde a versao "
                     "publicada). Quem pode publicar e' regra da Casa (secretaria, Presidente, 1o Secretario ou Mesa).")
     :classe :ato
     :ritual :confirmar
     :papeis #{"secretario" "vereador"}
     :entrada [:map {:closed true}
               [:sessao-id {:description "A sessao cuja pauta sera' publicada."} :uuid]
               [:justificativa {:optional true :description "Na republicacao: o que mudou desde a versao publicada."}
                [:maybe [:string {:min 1 :max 2000}]]]]
     :saida wire/PautaPublicadaOut
     :rotas #{:sessoes/publicar-pauta}
     :apresentar (fn [{:keys [repo-sessoes] :as deps} ator {:keys [sessao-id justificativa]}]
                   (when-let [r (controllers/publicacao-da-pauta (deps-publicacao deps) ator sessao-id (agora deps))]
                     (let [s (repo/buscar-sessao repo-sessoes (:ente-id ator) sessao-id)]
                       {:titulo (str "Publicar a pauta da sessão " (get rotulo-tipo-sessao (:tipo-sessao s) (:tipo-sessao s))
                                     " nº " (:numero-sequencial s))
                        :texto (texto-da-proposta r justificativa)})))
     :executar (fn [deps ator {:keys [sessao-id justificativa]}]
                 (try
                   (when-let [r (controllers/publicar-pauta! (deps-publicacao deps) ator
                                                             {:sessao-id sessao-id :justificativa justificativa}
                                                             (agora deps))]
                     (adapters-out-pub/publicada->wire
                      r (controllers/resumos-das-materias (:resumir-proposicoes deps) (:ente-id ator)
                                                          (keep :proposicao-id (:avisos (:versao r))))))
                   (catch clojure.lang.ExceptionInfo e
                     ;; a recusa do ato (pauta vazia, sem mudanca, falta de justificativa, sessao fechada) chega a quem
                     ;; confirmou como conflito legivel da proposta, nao como erro interno
                     (if (#{:conflito/publicacao-pauta :conflito/sessao-fechada} (:tipo (ex-data e)))
                       (throw (ex-info (ex-message e) {:tipo :proposta/ato-recusado} e))
                       (throw e)))))})])
