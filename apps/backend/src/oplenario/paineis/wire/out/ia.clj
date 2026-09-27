(ns oplenario.paineis.wire.out.ia
  "Contrato de SAIDA do painel da IA da Casa (B.9, docs/25 Eixo 8.4, ADR-0014) — GET /paineis/ia, do `admin_ente`.
  Consumo x orcamento, o estado da cota, o que cada capacidade custou e o que as pessoas fizeram com o resultado. So'
  contagens e valores (8.5): nenhum texto de execucao. Valores em dinheiro como texto decimal (sem float). Malli
  fechado; fonte do tipo TS gerado (oplenario.codegen.gerar-paineis).")

(def OrcamentoIAOut
  [:map {:closed true}
   [:mensal :string]
   [:teto-duro :string]
   [:moeda :string]
   [:definido-em :string]])

(def CapacidadeIAOut
  [:map {:closed true}
   [:operacao :string]
   [:execucoes :int]
   [:indisponiveis :int]
   [:custo :string]
   [:aprovados :int]
   [:editados :int]
   [:descartados :int]
   [:erros-reportados :int]])

(def DesfechosNotasOut
  [:map {:closed true} [:pendentes :int] [:aproveitadas :int] [:descartadas :int]])

(def DesfechosPropostasOut
  [:map {:closed true} [:aguardando :int] [:confirmadas :int] [:recusadas :int] [:expiradas :int]])

(def PainelIAOut
  "`consumo-disponivel` false = a IA nao respondeu agora: o orcamento e os desfechos (do core) aparecem; gasto e estado
  nao (R-IA-1: a tela diz isso, nao inventa)."
  [:map {:closed true}
   [:mes :string]
   [:orcamento [:maybe OrcamentoIAOut]]
   [:consumo-disponivel :boolean]
   [:estado [:maybe [:enum "sem_orcamento" "normal" "aviso" "segundo_plano_pausado" "esgotada"]]]
   [:gasto [:maybe :string]]
   [:moeda [:maybe :string]]
   [:parcial :boolean]
   [:execucoes :int]
   [:por-capacidade [:vector CapacidadeIAOut]]
   [:notas-tecnicas DesfechosNotasOut]
   [:propostas DesfechosPropostasOut]])
