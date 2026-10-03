(ns oplenario.sessoes.adapters.out.audiencia
  "Gate de SAIDA `models -> wire/out` da AUDIENCIA PUBLICA (ADR-0021 Parte A). Chamado SO pelo diplomat/. Projeta
  CAMPO A CAMPO e valida contra o contrato fechado — drift = bug de servidor (500), nunca resposta malformada. A
  defesa anti-vazamento mora aqui: o portal nunca leva `identidade-id`, protocolo nem nome de quem nao falou."
  (:require [malli.core :as m]
            [malli.error :as me]
            [oplenario.sessoes.wire.out :as wire]))

(set! *warn-on-reflection* true)

(defn- ->str [x] (some-> x str))

(defn- validado [schema out rotulo]
  (when-not (m/validate schema out)
    (throw (ex-info (str rotulo " viola o contrato (bug de servidor)")
                    {:campos (keys (me/humanize (m/explain schema out)))})))
  out)

(defn- sem-nils
  "Tira as chaves OPCIONAIS ausentes (nil) — o contrato as declara opcionais."
  [m ks]
  (reduce (fn [acc k] (if (nil? (get acc k)) (dissoc acc k) acc)) m ks))

(defn- inscricao* [i]
  (sem-nils {:id (->str (:id i)) :protocolo (:protocolo i) :ordem (:ordem i) :nome (:nome i)
             :fala-como (:fala-como i) :entidade (:entidade i) :tema (:tema i) :origem (:origem i)
             :estado (:estado i) :chamada-em (->str (:chamada-em i)) :encerrada-em (->str (:encerrada-em i))
             :tempo-usado-segundos (:tempo-usado-segundos i)}
            [:entidade :chamada-em :encerrada-em :tempo-usado-segundos]))

(defn inscricao->wire [i] (validado wire/InscricaoOut (inscricao* i) "inscricao"))

(defn- proposicao* [p]
  (when p {:id (->str (:id p)) :rotulo (:rotulo p) :ementa (:ementa p)}))

(defn audiencia->wire
  "{:sessao :audiencia :inscricoes :comissao-nome :proposicao} -> AudienciaOut."
  [{:keys [sessao audiencia inscricoes comissao-nome proposicao]}]
  (validado wire/AudienciaOut
            (sem-nils {:sessao-id (->str (:id sessao)) :numero (:numero-sequencial sessao) :estado (:estado sessao)
                       :agendada-para (->str (:agendada-para sessao)) :modalidade (:modalidade sessao)
                       :comissao {:id (->str (:comissao-id audiencia)) :nome comissao-nome}
                       :tema (:tema audiencia) :local (:local audiencia) :proposicao (proposicao* proposicao)
                       :finalidade (:finalidade audiencia) :referencia (:referencia audiencia)
                       :tempo-fala-segundos (:tempo-fala-segundos audiencia)
                       :inscricoes-abertas (boolean (:inscricoes-abertas audiencia))
                       :inscricoes (mapv inscricao* inscricoes)}
                      [:local :proposicao :referencia])
            "audiencia"))

(defn- resumo* [r]
  (sem-nils {:sessao-id (->str (:sessao-id r)) :tema (:tema r) :comissao-nome (:comissao-nome r)
             :agendada-para (->str (:agendada-para r)) :estado (:estado r) :local (:local r)
             :finalidade (:finalidade r)}
            [:local]))

(defn audiencias-publicas->wire [{:keys [proximas realizadas]}]
  (validado wire/AudienciasPublicasOut {:proximas (mapv resumo* proximas) :realizadas (mapv resumo* realizadas)}
            "lista publica de audiencias"))

(defn audiencia-publica->wire
  "A pagina publica: so' o que o portal pode mostrar (nada de protocolo, identidade, nem quem nao falou)."
  [{:keys [sessao audiencia comissao-nome proposicao inscricoes-abertas inscritos ata-publicada falaram]}]
  (validado wire/AudienciaPublicaOut
            (sem-nils (merge (resumo* {:sessao-id (:id sessao) :tema (:tema audiencia) :comissao-nome comissao-nome
                                       :agendada-para (:agendada-para sessao) :estado (:estado sessao)
                                       :local (:local audiencia) :finalidade (:finalidade audiencia)})
                             {:modalidade (:modalidade sessao) :proposicao (proposicao* proposicao)
                              :referencia (:referencia audiencia)
                              :tempo-fala-segundos (:tempo-fala-segundos audiencia)
                              :inscricoes-abertas (boolean inscricoes-abertas) :inscritos inscritos
                              :ata-publicada (boolean ata-publicada)
                              :falaram (mapv (fn [i] (sem-nils {:nome (:nome i) :fala-como (:fala-como i)
                                                                :entidade (:entidade i)}
                                                               [:entidade]))
                                             falaram)})
                      [:proposicao :referencia])
            "audiencia publica"))

(defn recibo-portal->wire [i]
  (validado wire/InscricaoPortalReciboOut
            {:protocolo (:protocolo i) :recibo-em (->str (:criado-em i)) :ordem (:ordem i)}
            "recibo da inscricao"))

(defn- minha* [l]
  {:id (->str (:id l)) :protocolo (:protocolo l) :sessao-id (->str (:sessao-id l)) :tema (:tema-audiencia l)
   :comissao-nome (:comissao-nome l) :agendada-para (->str (:agendada-para l)) :ordem (:ordem l)
   :estado (:estado l) :recibo-em (->str (:criado-em l))})

(defn minha-inscricao->wire [l] (validado wire/MinhaInscricaoOut (minha* l) "inscricao da cidada"))

(defn minhas-inscricoes->wire [linhas]
  (validado wire/MinhasInscricoesOut {:inscricoes (mapv minha* linhas)} "inscricoes da cidada"))
