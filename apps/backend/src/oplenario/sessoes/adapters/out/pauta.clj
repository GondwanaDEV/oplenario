(ns oplenario.sessoes.adapters.out.pauta
  "Gate de SAIDA `models -> wire/out` da PAUTA (§22.10 adapters/out, ADR-0001 §3) — chamado SO pelo diplomat/.
  Projeta a pauta viva do dominio (kebab, uuid) p/ a representacao externa (strings, JSON-serializavel) e
  FILTRA o que nao deve vazar: `ente-id`, `pauta-sessao-id`, `lock-version` e `ativo` (internos). A defesa
  anti-vazamento de saida mora aqui. A projecao e' VALIDADA contra `wire/out.PautaOut` (drift de campo =
  bug de servidor -> 500, nunca resposta malformada que envenena o codegen do front)."
  (:require [malli.core :as m]
            [malli.error :as me]
            [oplenario.sessoes.wire.out :as wire]))

(set! *warn-on-reflection* true)

(defn- ->str [x] (some-> x str))

(defn- item->wire
  "Item de dominio (ativo) -> PautaItemOut. Inclui apenas os campos do contrato; honra o `{:optional true}` do
  schema: a chave FK-por-tipo so aparece quando presente (proposicao-id XOR texto-descricao, garantido a
  montante) — em vez de emitir `null` explicito, que o codegen Malli->TS leria como nullable em vez de ausente."
  [it]
  (cond-> {:id        (->str (:id it))
           :fase      (:fase it)
           :tipo-item (:tipo-item it)
           :ordem     (:ordem it)}
    (:proposicao-id it)   (assoc :proposicao-id   (->str (:proposicao-id it)))
    (:texto-descricao it) (assoc :texto-descricao (:texto-descricao it))))

(defn pauta->wire
  "Pauta viva de dominio {:sessao-id :itens [...]} -> PautaOut (validada). itens vazio quando nao ha pauta."
  [{:keys [sessao-id itens]}]
  (let [out {:sessao-id (->str sessao-id)
             :itens     (mapv item->wire itens)}]
    (when-not (m/validate wire/PautaOut out)
      ;; arvore completa de erros (inclui violacao aninhada em :itens[i]); `out` ja e' o projetado sem internos.
      (throw (ex-info "projecao de pauta viola o contrato PautaOut (bug de servidor)"
                      {:explain (me/humanize (m/explain wire/PautaOut out))})))
    out))

(defn- validado [schema out msg]
  (when-not (m/validate schema out)
    (throw (ex-info msg {:campos (keys (me/humanize (m/explain schema out)))})))
  out)

(defn recibo-item-adicionado->wire
  "Recibo de dominio {:id uuid :ordem int} -> PautaItemAdicionadoOut (validado, resposta 201)."
  [{:keys [id ordem]}]
  (validado wire/PautaItemAdicionadoOut {:id (->str id) :ordem ordem}
            "recibo de item adicionado viola o contrato PautaItemAdicionadoOut (bug de servidor)"))

(defn recibo-reordenacao->wire
  "Recibo de dominio {:id uuid :de int :para int} -> PautaItemReordenadoOut (validado, resposta 200)."
  [{:keys [id de para]}]
  (validado wire/PautaItemReordenadoOut {:id (->str id) :de de :para para}
            "recibo de reordenacao viola o contrato PautaItemReordenadoOut (bug de servidor)"))

(defn recibo-remocao->wire
  "Recibo de dominio {:id uuid :ativo false} -> PautaItemRemovidoOut (validado, resposta 200). So o `id`."
  [{:keys [id]}]
  (validado wire/PautaItemRemovidoOut {:id (->str id)}
            "recibo de remocao viola o contrato PautaItemRemovidoOut (bug de servidor)"))
