(ns oplenario.admin-sistema.wire.out.ia
  "Representacao externa (wire) da OBSERVABILIDADE DA IA no console do operador (Onda E, `observabilidade-ia`; §22.8):
  todas as Casas juntas numa janela de horas — volume, latencia p50/p95, o que nao rodou e por que, custo, por
  capacidade e por fornecedor/modelo, e a serie por hora. So' contagens, tempos e valores: nenhum texto de execucao e
  nenhuma Casa identificada (so' QUANTAS usaram). Dinheiro como texto decimal (sem float).

  Escopo honesto: o registro do satelite cobre as execucoes do NUCLEO (modelo de linguagem: ata, resumo, agente,
  conferencia, copiloto); transcricao e embeddings da busca nao passam por ele.")

(def ^:private Agregado
  [[:execucoes :int]
   [:indisponiveis :int]
   [:latencia-p50-ms [:maybe :int]]
   [:latencia-p95-ms [:maybe :int]]
   [:custo :string]
   [:parcial :boolean]])

(def AgregadoIAOut (into [:map {:closed true}] Agregado))

(def PorOperacaoIAOut (into [:map {:closed true} [:operacao :string]] Agregado))

(def PorFornecedorIAOut (into [:map {:closed true} [:vendor :string] [:modelo [:maybe :string]]] Agregado))

(def MotivoIAOut [:map {:closed true} [:motivo :string] [:execucoes :int]])

(def HoraIAOut [:map {:closed true} [:inicio :string] [:execucoes :int] [:indisponiveis :int]])

(def ObservabilidadeIAOut
  "`disponivel` false = o satelite nao respondeu agora (R-IA-1): a tela diz isso e nao inventa numero — os agregados
  saem nulos/vazios."
  [:map {:closed true}
   [:disponivel :boolean]
   [:horas :int]
   [:desde [:maybe :string]]
   [:ate [:maybe :string]]
   [:casas [:maybe :int]]
   [:moeda [:maybe :string]]
   [:total [:maybe AgregadoIAOut]]
   [:por-operacao [:vector PorOperacaoIAOut]]
   [:por-fornecedor [:vector PorFornecedorIAOut]]
   [:motivos-indisponivel [:vector MotivoIAOut]]
   [:por-hora [:vector HoraIAOut]]])
