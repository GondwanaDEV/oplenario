(ns oplenario.legislativo.adapters.out.proposicao
  "Gate de SAIDA `models -> wire/out` da leitura de proposicoes (§22.10 adapters/out, ADR-0001, Onda B Slice
  1). Projeta cada linha (kebab, uuid/instant) para ProposicaoResumoOut — nunca vaza
  atributos_especificos/texto_vigente_versao_id/lock_version/created_by/updated_by/ente_id. Validado contra
  o contrato (drift de campo = bug de servidor -> 500, nunca resposta malformada que envenena o codegen)."
  (:require [malli.core :as m]
            [malli.error :as me]
            [oplenario.legislativo.wire.out.proposicao :as wire]))

(set! *warn-on-reflection* true)

(defn- ->str [x] (some-> x str))

(defn- validado [schema out tipo]
  (when-not (m/validate schema out)
    (throw (ex-info (str "projecao de " tipo " viola o contrato wire/out (bug de servidor)")
                    {:campos (keys (me/humanize (m/explain schema out)))})))
  out)

(defn- resumo->wire [linha]
  (validado wire/ProposicaoResumoOut
            {:id (->str (:id linha)) :tipo (:tipo linha) :ano (:ano linha) :sequencial (:sequencial linha)
             :urn-lex (:urn-lex linha) :ementa (:ementa linha) :autor-tipo (:autor-tipo linha)
             :autor-texto (:autor-texto linha) :estado (:estado linha)
             :atualizado-em (->str (:atualizado-em linha))}
            "item de proposicao"))

(defn listar->wire
  "{:itens [...] :total :pagina :tamanho-pagina} (dominio) -> ListaProposicoesOut."
  [{:keys [itens total pagina tamanho-pagina]}]
  (validado wire/ListaProposicoesOut
            {:itens (mapv resumo->wire itens) :total total :pagina pagina :tamanho-pagina tamanho-pagina}
            "lista de proposicoes"))

(defn detalhe->wire
  "Proposicao (dominio, kebab) + texto vigente inline opcional (string ou nil) -> ProposicaoDetalheOut."
  [linha texto]
  (validado wire/ProposicaoDetalheOut
            {:id (->str (:id linha)) :tipo (:tipo linha) :ano (:ano linha) :sequencial (:sequencial linha)
             :urn-lex (:urn-lex linha) :ementa (:ementa linha) :autor-tipo (:autor-tipo linha)
             :autor-id (->str (:autor-id linha)) :autor-texto (:autor-texto linha)
             :objeto-indicacao (:objeto-indicacao linha) :destinatario-id (->str (:destinatario-id linha))
             :destinatario-texto (:destinatario-texto linha) :tipo-requerimento (:tipo-requerimento linha)
             :categoria-mocao (:categoria-mocao linha) :estado (:estado linha)
             :lock-version (:lock-version linha) :atualizado-em (->str (:atualizado-em linha)) :texto texto}
            "detalhe de proposicao"))
