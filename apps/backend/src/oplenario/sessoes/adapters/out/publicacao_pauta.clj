(ns oplenario.sessoes.adapters.out.publicacao-pauta
  "Gate de SAIDA `models -> wire/out` de PUBLICAR A PAUTA, da REGRA DA PAUTA e da PAUTA OFICIAL do portal (ADR-0019
  fatia 3; §22.10 adapters/out). Chamado SO pelo diplomat/ (e pelo catalogo). Projeta e VALIDA contra o wire/out
  (drift = bug de servidor -> 500, nunca resposta malformada). O que nao viaja: ente-id, pauta-sessao-id, o id de
  quem publicou (so' o nome, e so' na tela interna), o snapshot na lista, lock-version nos itens oficiais."
  (:require [malli.core :as m]
            [malli.error :as me]
            [oplenario.sessoes.wire.out :as wire]))

(set! *warn-on-reflection* true)

(defn- ->str [x] (some-> x str))

(defn- validado [schema out msg]
  (when-not (m/validate schema out)
    (throw (ex-info msg {:campos (me/humanize (m/explain schema out))})))
  out)

(defn- resumo->wire [resumo]
  (let [autor (:autor-texto resumo)]
    (cond-> (select-keys resumo [:tipo :ano :sequencial :ementa])
      (and (string? autor) (seq autor)) (assoc :autor-texto autor))))

(defn- aviso->wire
  "Um aviso de dominio -> AvisoPautaOut. `resumos` = {proposicao-id(uuid) resumo}; o aviso guardado na versao tem os ids
  como texto, por isso a busca tenta os dois."
  [resumos {:keys [tipo item-id proposicao-id pareceres-em-andamento pedidos-pendentes minimo-horas horas-reais]}]
  (let [resumo (when proposicao-id
                 (or (get resumos proposicao-id) (some-> proposicao-id str parse-uuid (->> (get resumos)))))]
    (cond-> {:tipo tipo}
      item-id                (assoc :item-id (->str item-id))
      proposicao-id          (assoc :proposicao-id (->str proposicao-id))
      resumo                 (assoc :proposicao (resumo->wire resumo))
      pareceres-em-andamento (assoc :pareceres-em-andamento (long pareceres-em-andamento))
      pedidos-pendentes      (assoc :pedidos-pendentes (long pedidos-pendentes))
      minimo-horas           (assoc :minimo-horas (long minimo-horas))
      horas-reais            (assoc :horas-reais (long horas-reais)))))

(defn- antecedencia->wire [a]
  (when a {:minimo-horas (long (:minimo-horas a)) :horas-reais (long (:horas-reais a)) :cumprida (boolean (:cumprida a))}))

(defn regra->wire
  "Regra efetiva de dominio (`logic/regra-da-pauta`) -> RegraPautaOut (validada)."
  [{:keys [quem-publica antecedencia-minima-horas configurada atualizado-em]}]
  (validado wire/RegraPautaOut
            (cond-> {:quem-publica quem-publica
                     :antecedencia-minima-horas (some-> antecedencia-minima-horas long)
                     :configurada (boolean configurada)}
              atualizado-em (assoc :atualizado-em (->str atualizado-em)))
            "regra da pauta viola o contrato RegraPautaOut (bug de servidor)"))

(defn- versao->wire [v]
  (cond-> {:versao (:numero-versao v)
           :tipo-versao (:tipo-versao v)
           :publicada-em (->str (:publicado-em v))
           :itens (count (:snapshot v))}
    (:justificativa v)      (assoc :justificativa (:justificativa v))
    (:publicada-a-titulo v) (assoc :a-titulo (:publicada-a-titulo v))
    (:publicada-por-nome v) (assoc :publicada-por-nome (:publicada-por-nome v))))

(defn publicacao->wire
  "A tela de publicar (dominio do controller) -> PublicacaoPautaOut (validada)."
  [{:keys [sessao-id regra pode-publicar motivo republicacao itens-na-pauta ultima versoes alterada avisos
           avisos-indisponiveis antecedencia]} resumos]
  (validado wire/PublicacaoPautaOut
            (cond-> {:sessao-id (->str sessao-id)
                     :regra (regra->wire regra)
                     :pode-publicar (boolean pode-publicar)
                     :republicacao (boolean republicacao)
                     :itens-na-pauta (long itens-na-pauta)
                     :versoes (mapv versao->wire versoes)
                     :alterada-desde-a-publicacao (boolean alterada)
                     :avisos (mapv (partial aviso->wire resumos) avisos)
                     :avisos-indisponiveis (boolean avisos-indisponiveis)}
              motivo       (assoc :motivo motivo)
              ultima       (assoc :ultima (versao->wire ultima))
              antecedencia (assoc :antecedencia (antecedencia->wire antecedencia)))
            "publicacao da pauta viola o contrato PublicacaoPautaOut (bug de servidor)"))

(defn publicada->wire
  "O recibo do ato (a versao gravada + os avisos + a antecedencia) -> PautaPublicadaOut (validado)."
  [{:keys [sessao-id versao antecedencia]} resumos]
  (let [aviso-ant (some #(when (= "antecedencia-nao-cumprida" (:tipo %)) %) (:avisos versao))]
    (validado wire/PautaPublicadaOut
              (cond-> {:sessao-id (->str sessao-id)
                       :versao (:numero-versao versao)
                       :tipo-versao (:tipo-versao versao)
                       :publicada-em (->str (:publicado-em versao))
                       :itens (count (:snapshot versao))
                       :avisos (mapv (partial aviso->wire resumos) (:avisos versao))}
                aviso-ant    (assoc :aviso "antecedencia-nao-cumprida")
                antecedencia (assoc :antecedencia (antecedencia->wire antecedencia)))
              "recibo de publicacao da pauta viola o contrato PautaPublicadaOut (bug de servidor)")))

;; ---- o portal ----

(defn- sessao-publica->wire [s]
  (cond-> {:sessao-id (->str (:id s))
           :tipo-sessao (:tipo-sessao s)
           :numero-sequencial (:numero-sequencial s)
           :estado (:estado s)}
    (:agendada-para s) (assoc :agendada-para (->str (:agendada-para s)))
    (:aberta-em s)     (assoc :aberta-em (->str (:aberta-em s)))
    (:versao s)        (assoc :pauta-oficial {:versao (:versao s) :publicada-em (->str (:publicada-em s))
                                              :itens (long (or (:itens s) 0))})))

(defn pautas-publicas->wire
  "As linhas de `db/pauta/pautas-publicas` -> PautasPublicasOut (validada)."
  [linhas]
  (validado wire/PautasPublicasOut {:sessoes (mapv sessao-publica->wire linhas)}
            "lista publica de pautas viola o contrato PautasPublicasOut (bug de servidor)"))

(defn- item-oficial->wire [resumos it]
  (let [pid (:proposicao-id it)
        resumo (some-> pid str parse-uuid (->> (get resumos)))]
    (cond-> {:id (->str (:id it)) :fase (:fase it) :tipo-item (:tipo-item it) :ordem (:ordem it)}
      pid                   (assoc :proposicao-id (->str pid))
      resumo                (assoc :proposicao (resumo->wire resumo))
      (:texto-descricao it) (assoc :texto-descricao (:texto-descricao it)))))

(defn pauta-oficial->wire
  "{:sessao :vigente :versoes} (dominio) -> PautaOficialOut (validada). Com a vigente, a sessao da lista ganha o resumo
  da pauta oficial."
  [{:keys [sessao vigente versoes]} resumos]
  (let [cab (cond-> sessao
              vigente (assoc :versao (:numero-versao vigente) :publicada-em (:publicado-em vigente)
                             :itens (count (:snapshot vigente))))]
    (validado wire/PautaOficialOut
              (cond-> {:sessao (sessao-publica->wire cab)
                       :versoes (mapv (fn [v] (cond-> {:versao (:numero-versao v) :tipo-versao (:tipo-versao v)
                                                       :publicada-em (->str (:publicado-em v))}
                                                (:justificativa v) (assoc :justificativa (:justificativa v))))
                                      versoes)}
                vigente (assoc :vigente (cond-> {:versao (:numero-versao vigente)
                                                 :tipo-versao (:tipo-versao vigente)
                                                 :publicada-em (->str (:publicado-em vigente))
                                                 :itens (mapv (partial item-oficial->wire resumos) (:snapshot vigente))}
                                          (:justificativa vigente) (assoc :justificativa (:justificativa vigente)))))
              "pauta oficial viola o contrato PautaOficialOut (bug de servidor)")))
