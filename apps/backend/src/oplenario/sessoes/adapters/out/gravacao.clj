(ns oplenario.sessoes.adapters.out.gravacao
  "Gate de SAIDA `models -> wire/out` da gravacao (§22.10 adapters/out, ADR-0001 §3). Projeta o recibo de
  ingestao e o read-model de segmentos p/ a borda, FILTRANDO o que nao deve vazar: a chave interna do store
  (`container-bruto-uri`), `ente-id`, e (no read-model de segmentos JA' vinculados) `audio-hash`/
  `lock-version`. Validado contra o contrato wire/out (drift de campo = bug de servidor -> 500, nunca
  resposta malformada que envenena o codegen do front).

  `recibo-ingestao->wire` EXPOE `lock-version` deliberadamente (ledger de prontidao Fase 8 achado #2):
  `POST .../gravacao/:seg-id/vincular` o exige no corpo, e um segmento AINDA NAO vinculado nunca aparece em
  `GET .../gravacao` (que so' lista os JA' vinculados) — este recibo e' a UNICA fonte do token de CAS, sem
  a qual vincular e' impossivel de montar so' pela API."
  (:require [malli.core :as m]
            [malli.error :as me]
            [oplenario.sessoes.wire.out :as wire]))

(set! *warn-on-reflection* true)

(defn- ->str [x] (some-> x str))

(defn- validado [schema out msg]
  (when-not (m/validate schema out)
    (throw (ex-info msg {:campos (keys (me/humanize (m/explain schema out)))})))
  out)

(defn recibo-ingestao->wire
  "Recibo de dominio {:id uuid :audio-hash string :lock-version int} -> GravacaoReciboOut (resposta 201). NAO
  inclui a chave do store. `lock-version` viaja (excecao consciente, ver docstring do ns) — sem ele o cliente
  nao tem como montar `POST .../gravacao/:seg-id/vincular`."
  [{:keys [id audio-hash lock-version]}]
  (validado wire/GravacaoReciboOut {:id (->str id) :audio-hash audio-hash :lock-version lock-version}
            "recibo de ingestao viola o contrato GravacaoReciboOut (bug de servidor)"))

(defn recibo-vinculo->wire
  "Recibo de dominio {:id uuid :sessao-id uuid} -> VinculoGravacaoOut (resposta 201 do vinculo Opcao A)."
  [{:keys [id sessao-id]}]
  (validado wire/VinculoGravacaoOut {:id (->str id) :sessao-id (->str sessao-id)}
            "recibo de vinculo viola o contrato VinculoGravacaoOut (bug de servidor)"))

(defn- segmento->wire [s]
  {:id              (->str (:id s))
   :sessao-id       (->str (:sessao-id s))
   :iniciou-em      (->str (:iniciou-em s))
   :encerrou-em     (->str (:encerrou-em s))
   :motivo-inicio   (:motivo-inicio s)
   :motivo-fim      (:motivo-fim s)
   :fonte-ingestao  (:fonte-ingestao s)
   :acesso-restrito (boolean (:acesso-restrito s))
   :audio-disponivel (some? (:audio-uri s))})

(defn segmentos->wire
  "{:sessao-id uuid :segmentos [seg...]} -> SegmentosOut (validado). Filtra os internos por segmento."
  [sessao-id segmentos]
  (validado wire/SegmentosOut
            {:sessao-id (->str sessao-id) :segmentos (mapv segmento->wire segmentos)}
            "read-model de gravacao viola o contrato SegmentosOut (bug de servidor)"))

(defn- sugestao->wire [s]
  (when s
    {:sessao-id         (->str (:id s))
     :tipo-sessao       (:tipo-sessao s)
     :numero-sequencial (:numero-sequencial s)
     :estado            (:estado s)
     :inicio            (->str (or (:aberta-em s) (:agendada-para s)))}))

(defn pendentes->wire
  "[segmento+:sugestao ...] -> GravacoesPendentesOut (validado). Faixa A / A.2."
  [segmentos]
  (validado wire/GravacoesPendentesOut
            {:segmentos (mapv (fn [s] {:id              (->str (:id s))
                                       :iniciou-em      (->str (:iniciou-em s))
                                       :encerrou-em     (->str (:encerrou-em s))
                                       :fonte-ingestao  (:fonte-ingestao s)
                                       :acesso-restrito (boolean (:acesso-restrito s))
                                       :audio-hash      (:audio-hash s)
                                       :lock-version    (:lock-version s)
                                       :sugestao        (sugestao->wire (:sugestao s))})
                              segmentos)}
            "gravacoes pendentes violam o contrato GravacoesPendentesOut (bug de servidor)"))

;; ---------- Faixa A / A.3: transcricao ----------

(defn- ->double [x] (some-> x double))

(defn- ponteiro->wire [p]
  {:id (->str (:id p)) :segmento-id (->str (:segmento-id p)) :situacao (:situacao p)
   :transcricao-id (->str (:transcricao-id p)) :versao (:versao p) :idioma (:idioma p)
   :duracao-s (->double (:duracao-s p)) :n-trechos (:n-trechos p)
   :cobertura-atribuida (->double (:cobertura-atribuida p)) :modelo-asr (:modelo-asr p)
   :modelo-diarizacao (:modelo-diarizacao p) :categoria-erro (:categoria-erro p) :detalhe-erro (:detalhe-erro p)
   :retentavel (:retentavel p) :ocorrido-em (->str (:ocorrido-em p))})

(defn transcricoes->wire [{:keys [sessao-id itens]}]
  (validado wire/TranscricoesOut {:sessao-id (->str sessao-id) :itens (mapv ponteiro->wire itens)}
            "transcricoes violam o contrato TranscricoesOut (bug de servidor)"))

(defn transcricao-conteudo->wire
  "O que a IA devolveu + o ponteiro do core -> TranscricaoConteudoOut. So' os campos de exibicao passam (o grupo de
  voz da diarizacao e' interno da IA)."
  [t]
  (validado wire/TranscricaoConteudoOut
            {:ponteiro (ponteiro->wire (:ponteiro t))
             :trechos  (mapv (fn [x] {:inicio (->double (:inicio x)) :fim (->double (:fim x)) :texto (str (:texto x))
                                      :orador-id (:orador-id x) :orador-nome (:orador-nome x)})
                             (:trechos t))}
            "transcricao viola o contrato TranscricaoConteudoOut (bug de servidor)"))
