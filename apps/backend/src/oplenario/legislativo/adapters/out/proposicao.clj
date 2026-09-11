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
  "Proposicao (dominio, kebab) + texto vigente inline opcional (string ou nil) -> ProposicaoDetalheOut.
  `:aprovada` (T3-A/Fatia 2) chega JA COMPUTADO em `linha` (o Repo o traz na MESMA tx da leitura, via
  votacao/aprovada-em-votacao?) — este adapter so' repassa, nunca decide o fato aqui."
  [linha texto]
  (validado wire/ProposicaoDetalheOut
            {:id (->str (:id linha)) :tipo (:tipo linha) :ano (:ano linha) :sequencial (:sequencial linha)
             :urn-lex (:urn-lex linha) :ementa (:ementa linha) :autor-tipo (:autor-tipo linha)
             :autor-id (->str (:autor-id linha)) :autor-texto (:autor-texto linha)
             :objeto-indicacao (:objeto-indicacao linha) :destinatario-id (->str (:destinatario-id linha))
             :destinatario-texto (:destinatario-texto linha) :tipo-requerimento (:tipo-requerimento linha)
             :categoria-mocao (:categoria-mocao linha) :estado (:estado linha) :aprovada (:aprovada linha)
             :lock-version (:lock-version linha) :atualizado-em (->str (:atualizado-em linha)) :texto texto}
            "detalhe de proposicao"))

(defn recibo-transicao->wire
  "Resultado de Repo/transicionar! (`{:transicionou? true :de :para :ocorrido-em ...}`) + o id da materia e
  o gatilho DISPARADO -> TramitacaoReciboOut. So' e' chamado no ramo `:transicionou? true` — o ramo `false`
  nao e' recibo nenhum (nao houve ato a comprovar), e' recusa de dominio traduzida pelo diplomat.

  O `gatilho` chega por ARGUMENTO e nao do mapa da engine de proposito: o resultado positivo de
  `transicionar!` nao carrega `:gatilho` (so' o negativo carrega). Ler dali daria `nil` no recibo, e o
  contrato :closed transformaria isso num 500 de projecao — barulhento, mas pelo motivo errado."
  [proposicao-id gatilho r]
  (validado wire/TramitacaoReciboOut
            {:proposicao-id (->str proposicao-id) :de (:de r) :para (:para r)
             :gatilho gatilho :ocorrido-em (->str (:ocorrido-em r))}
            "recibo de tramitacao"))

(defn- historico-item->wire [l]
  {:de-estado (:de-estado l) :para-estado (:para-estado l) :gatilho (:gatilho l)
   :ocorrido-em (->str (:ocorrido-em l))})

(defn tramitacao->wire
  "Model de `controllers/buscar-tramitacao` -> TramitacaoOut (Fatia 3).

  PROJECAO COM RECORTE, nao repasse: o model do historico carrega `:contexto`, `:ator-id`, `:template-id`
  e `:id` da linha — nenhum deles atravessa (ver a docstring de `wire/out/TramitacaoHistoricoItemOut`). Um
  `select-keys` sobre a linha inteira passaria a vazar sozinho no dia em que a `db/` ganhasse uma coluna;
  montar campo a campo faz o contrato ser a lista de campos, e nao a forma da tabela.

  `:historico-truncado` e `:pode-ser-recusado` chegam ja' decididos do controller/logic — o adapters/out
  nunca infere nem preenche default para eles: sao AFIRMACOES sobre o que a resposta e', e um default aqui
  (ex.: `(boolean nil)` -> false) transformaria 'nao foi calculado' em 'nao ha' mais nada', que e' a
  mentira exata que o campo existe p/ impedir. O `:closed` + `validado` reprovam (500) se faltarem."
  [m]
  (validado wire/TramitacaoOut
            {:proposicao-id (->str (:proposicao-id m))
             :estado-atual (:estado-atual m)
             :template-id (->str (:template-id m))
             :estado-terminal (:estado-terminal m)
             :historico (mapv historico-item->wire (:historico m))
             :historico-truncado (:historico-truncado m)
             :gatilhos-possiveis (mapv #(select-keys % [:gatilho :destinos-possiveis :pode-ser-recusado])
                                       (:gatilhos-possiveis m))
             :nota (:nota m)}
            "tramitacao de proposicao"))
