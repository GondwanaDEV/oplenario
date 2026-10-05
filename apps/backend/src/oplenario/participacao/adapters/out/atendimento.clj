(ns oplenario.participacao.adapters.out.atendimento
  "Gate de SAIDA `models -> wire/out` do BALCAO interno de atendimento (§22.10 adapters/out, ADR-0001) — chamado SO
  pelo diplomat/. Projeta por ALLOWLIST (so' as chaves do contrato: nada de tenant, de id de pessoa, de CPF inteiro;
  na ouvidoria, nada do manifestante) e VALIDA contra wire/out — drift e' bug de servidor (500), nunca vazamento."
  (:require [malli.core :as m]
            [malli.error :as me]
            [oplenario.participacao.wire.out.atendimento :as wire]))

(set! *warn-on-reflection* true)

(defn- ->str [x] (some-> x str))

(defn- validar! [schema out rotulo]
  (when-not (m/validate schema out)
    (throw (ex-info (str "projecao viola o contrato " rotulo " (bug de servidor)")
                    {:erros (me/humanize (m/explain schema out))})))
  out)

(defn- prazo [i]
  {:aberto (boolean (:aberto i)) :recebido-em (->str (:recibo-em i)) :prazo-vigente (->str (:prazo-vigente i))
   :dias-restantes (:dias-restantes i) :prorrogado (boolean (:prorrogado i))})

(defn anexo->wire
  "UM anexo (kebab) -> AnexoOut, por allowlist (nada de chave no object storage, sha256 nem `enviado-por`). Serve o 201 do
  upload e a lista do detalhe."
  [a]
  (validar! wire/AnexoOut
            (cond-> {:id (->str (:id a)) :nome (:nome a) :tipo-midia (:tipo-midia a) :bytes (:bytes a) :origem (:origem a)
                     :enviado-em (->str (:enviado-em a))}
              (:retirado-em a) (assoc :retirado-em (->str (:retirado-em a)))
              (:motivo-da-retirada a) (assoc :motivo-da-retirada (:motivo-da-retirada a))
              (:substituido-por a) (assoc :substituido-por (->str (:substituido-por a))))
            "AnexoOut"))

(defn complemento->wire
  "UM complemento (kebab) -> ComplementoOut, por allowlist (nada de quem escreveu nem do tenant). Serve o 201 da rota."
  [c]
  (validar! wire/ComplementoOut
            {:id (->str (:id c)) :corpo (:corpo c) :complementado-em (->str (:complementado-em c))}
            "ComplementoOut"))

(defn- anexos [xs] (mapv anexo->wire xs))

(defn- pessoa [p] (when p {:nome (:nome p) :cpf-mascarado (:cpf-mascarado p)}))

(defn- item-esic [i]
  (merge {:id (->str (:id i)) :protocolo (:protocolo i) :assunto (:assunto i) :estado (:estado i)
          :recurso-pendente (when-let [r (:recurso-pendente i)]
                              {:id (->str (:id r)) :protocolo (:protocolo r) :recebido-em (->str (:recibo-em r))})}
         (prazo i)))

(defn- item-ouvidoria [i]
  (merge {:id (->str (:id i)) :protocolo (:protocolo i) :tipo (:tipo i) :assunto (:assunto i)
          :identificacao (if (:anonima i) "anonima" "identificada") :estado (:estado i)}
         (prazo i)))

(defn- item-lgpd [i]
  (merge {:id (->str (:id i)) :protocolo (:protocolo i) :tipo (:tipo i) :estado (:estado i)} (prazo i)))

(defn fila->wire
  "A fila da `especie` na `situacao`, item a item."
  [especie situacao itens]
  (let [[f schema rotulo] (case especie
                            :esic      [item-esic wire/FilaEsicOut "FilaEsicOut"]
                            :ouvidoria [item-ouvidoria wire/FilaOuvidoriaOut "FilaOuvidoriaOut"]
                            :lgpd      [item-lgpd wire/FilaLgpdOut "FilaLgpdOut"])]
    (validar! schema {:situacao situacao :itens (mapv f itens)} rotulo)))

(defn- evento [e]
  (cond-> {:tipo (:tipo e) :em (->str (:em e)) :texto (or (:texto e) "") :por (:por e)}
    (:protocolo e) (assoc :protocolo (:protocolo e))
    (:de-data e)   (assoc :de-data (->str (:de-data e)))
    (:para-data e) (assoc :para-data (->str (:para-data e)))))

(defn esic->wire
  [d]
  (validar! wire/DetalheEsicOut
            (merge {:id (->str (:id d)) :protocolo (:protocolo d) :assunto (:assunto d) :descricao (:descricao d)
                    :estado (:estado d)
                    :requerente (pessoa (:requerente d))
                    :recurso (when-let [r (:recurso d)]
                               {:id (->str (:id r)) :protocolo (:protocolo r) :motivo (:motivo r) :estado (:estado r)
                                :recebido-em (->str (:recibo-em r)) :decidido-em (->str (:decidido-em r))
                                :prazo-vigente (->str (:prazo-vigente r)) :dias-restantes (:dias-restantes r)
                                :prorrogado (boolean (:prorrogado r))})
                    :historico (mapv evento (:historico d))
                    :anexos (anexos (:anexos d))
                    :acoes (let [a (:acoes d)]
                             {:pode-responder (boolean (:pode-responder a)) :pode-indeferir (boolean (:pode-indeferir a))
                              :pode-prorrogar (boolean (:pode-prorrogar a)) :pode-anexar (boolean (:pode-anexar a))
                              :pode-complementar (boolean (:pode-complementar a))
                              :recurso-pendente-id (->str (:recurso-pendente-id a))})}
                   (prazo d))
            "DetalheEsicOut"))

(defn ouvidoria->wire
  [d]
  (validar! wire/DetalheOuvidoriaOut
            (merge {:id (->str (:id d)) :protocolo (:protocolo d) :tipo (:tipo d) :assunto (:assunto d)
                    :descricao (:descricao d) :identificacao (:identificacao d) :estado (:estado d)
                    :historico (mapv evento (:historico d))
                    :anexos (anexos (:anexos d))
                    :acoes (let [a (:acoes d)]
                             {:pode-responder (boolean (:pode-responder a)) :pode-arquivar (boolean (:pode-arquivar a))
                              :pode-prorrogar (boolean (:pode-prorrogar a)) :pode-anexar (boolean (:pode-anexar a))
                              :pode-complementar (boolean (:pode-complementar a))})}
                   (prazo d))
            "DetalheOuvidoriaOut"))

(defn lgpd->wire
  [d]
  (validar! wire/DetalheLgpdOut
            (merge {:id (->str (:id d)) :protocolo (:protocolo d) :tipo (:tipo d) :detalhe (:detalhe d)
                    :estado (:estado d) :titular (pessoa (:titular d))
                    :historico (mapv evento (:historico d))
                    :anexos (anexos (:anexos d))
                    :acoes {:pode-responder (boolean (get-in d [:acoes :pode-responder]))
                            :pode-indeferir (boolean (get-in d [:acoes :pode-indeferir]))
                            :pode-anexar (boolean (get-in d [:acoes :pode-anexar]))
                            :pode-complementar (boolean (get-in d [:acoes :pode-complementar]))}}
                   (prazo d))
            "DetalheLgpdOut"))
