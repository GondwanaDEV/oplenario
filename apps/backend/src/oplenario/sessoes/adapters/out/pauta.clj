(ns oplenario.sessoes.adapters.out.pauta
  "Gate de SAIDA `models -> wire/out` da PAUTA (§22.10 adapters/out, ADR-0001 §3) — chamado SO pelo diplomat/.
  Projeta a pauta viva do dominio (kebab, uuid) p/ a representacao externa (strings, JSON-serializavel) e
  FILTRA o que nao deve vazar: `ente-id`, `pauta-sessao-id` e `ativo` (internos). A defesa anti-vazamento de
  saida mora aqui. A projecao e' VALIDADA contra `wire/out.PautaOut` (drift de campo = bug de servidor -> 500,
  nunca resposta malformada que envenena o codegen do front).

  `lock-version` (token de CAS) EXPOE, deliberadamente (ledger de prontidao Fase 8 achado #2): `PATCH`/
  `DELETE /sessoes/:id/pauta/itens/:item-id` o exigem no corpo, e `GET .../pauta` (esta projecao) e' a UNICA
  leitura de onde um cliente aprende o valor corrente de um item — sem ele reordenar/remover e' impossivel
  de montar so' pela API."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]
            [oplenario.sessoes.wire.out :as wire]))

(set! *warn-on-reflection* true)

(defn- ->str [x] (some-> x str))

(defn- resumo->wire
  "O resumo da materia -> ProposicaoResumoPautaOut. `autor-texto` so' entra quando ha' texto (docs/23 Fatia 4a):
  ausente e' 'sem autoria textual', nunca uma string vazia que a TV exibiria como autor."
  [resumo]
  (let [autor (:autor-texto resumo)]
    (cond-> (select-keys resumo [:tipo :ano :sequencial :ementa])
      (and (string? autor) (not (str/blank? autor))) (assoc :autor-texto autor))))

(defn- item->wire
  "Item de dominio (ativo) -> PautaItemOut. Inclui apenas os campos do contrato; honra o `{:optional true}` do
  schema: a chave FK-por-tipo so aparece quando presente (proposicao-id XOR texto-descricao, garantido a
  montante) — em vez de emitir `null` explicito, que o codegen Malli->TS leria como nullable em vez de ausente.
  `resumos` ({proposicao-id -> {:tipo :ano :sequencial :ementa}}) so' acrescenta `:proposicao` quando ha' um
  resumo para aquele id — nunca inventa."
  [resumos it]
  (let [resumo (some->> (:proposicao-id it) (get resumos))]
    (cond-> {:id           (->str (:id it))
             :fase         (:fase it)
             :tipo-item    (:tipo-item it)
             :ordem        (:ordem it)
             :lock-version (:lock-version it)}
      (:proposicao-id it)   (assoc :proposicao-id   (->str (:proposicao-id it)))
      resumo                (assoc :proposicao      (resumo->wire resumo))
      (:texto-descricao it) (assoc :texto-descricao (:texto-descricao it)))))

(defn pauta->wire
  "Pauta viva de dominio {:sessao-id :itens [...]} -> PautaOut (validada). itens vazio quando nao ha pauta.
  `resumos` opcional (Modo TV, docs/22): o resumo das proposicoes da pauta, por id."
  ([pauta] (pauta->wire pauta {}))
  ([{:keys [sessao-id itens em-apreciacao]} resumos]
   (let [out (cond-> {:sessao-id (->str sessao-id)
                      :itens     (mapv (partial item->wire resumos) itens)}
               em-apreciacao (assoc :em-apreciacao {:item-id      (->str (:pauta-item-id em-apreciacao))
                                                    :anunciado-em (->str (:anunciado-em em-apreciacao))}))]
     (when-not (m/validate wire/PautaOut out)
       ;; arvore completa de erros (inclui violacao aninhada em :itens[i]); `out` ja e' o projetado sem internos.
       (throw (ex-info "projecao de pauta viola o contrato PautaOut (bug de servidor)"
                       {:explain (me/humanize (m/explain wire/PautaOut out))})))
     out)))

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

(defn recibo-anuncio->wire
  "Recibo de dominio do anuncio {:id :pauta-item-id :anunciado-em ...} -> ItemAnunciadoOut (validado). So' o
  anuncio: `created-by`/`registrado-em`/`ja-anunciado` nao viajam (o 200 vs 201 ja' diz se era reenvio)."
  [{:keys [id pauta-item-id anunciado-em]}]
  (validado wire/ItemAnunciadoOut {:id (->str id) :item-id (->str pauta-item-id) :anunciado-em (->str anunciado-em)}
            "recibo de anuncio viola o contrato ItemAnunciadoOut (bug de servidor)"))
