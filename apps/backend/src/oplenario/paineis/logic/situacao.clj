(ns oplenario.paineis.logic.situacao
  "Logica PURA de derivacao do rotulo de negocio da situacao de uma sessao (§16.11 / Inv.9) a partir do
  `estado_atual` cru da maquina de sessao (`sessoes`, F4). FONTE UNICA compartilhada pelos DOIS read-models
  de paineis que a expoem — o SLI de janela de sessao (adapters/out/sli_sessao, GET /paineis/sli/sessoes) e o
  dashboard da Mesa (adapters/out/mesa, GET /paineis/mesa). Extraida DE PROPOSITO p/ eliminar a divergencia
  silenciosa de dois `case` paralelos (review clojure MEDIUM): a lint (arquitetura-test) so' proibe adapters/
  chamar adapters/, NAO adapters/ chamar logic/ do MESMO modulo — e modulos de projecao PODEM ter camada
  logic/ (precedente: transparencia.logic.notificacao). Um `logic/` puro sem IO respeita a silhueta enxuta.")

(set! *warn-on-reflection* true)

(defn derivar
  "Rotulo de leitura de negocio do SLI, DERIVADO puramente do estado atual da maquina de sessao: o painel
  fala 'em curso / suspensa / realizada / nao realizada / agendada', nao os estados internos. 'arquivada' e'
  pos-encerrada -> conta como realizada. Estado desconhecido passa cru (defensivo — a fonte pode ganhar
  estados novos; o FE trata como enum ABERTO)."
  [estado-atual]
  (case estado-atual
    "aberta"        "em_curso"
    "suspensa"      "suspensa"
    ("encerrada" "arquivada") "realizada"
    "nao_realizada" "nao_realizada"
    "agendada"      "agendada"
    estado-atual))

(def ^:private ordem-canonica
  "Ordem de exibicao estavel das situacoes no rollup (o dashboard le' 'o que acontece agora' primeiro).
  Situacao desconhecida (futura) cai no fim, desempatada por nome (str/compare)."
  ["em_curso" "suspensa" "agendada" "realizada" "nao_realizada"])

(defn ordenar
  "Ordena uma seq de situacoes pela `ordem-canonica` (desconhecidas ao fim, alfabeticas entre si) — torna a
  ordem do `:por-situacao` do dashboard DETERMINISTICA (review clojure MINOR: um `reduce` num mapa depende da
  ordem de insercao, fragil se o vocabulario crescer)."
  [situacoes]
  (let [posicao (into {} (map-indexed (fn [i s] [s i]) ordem-canonica))
        n (count ordem-canonica)]
    ;; keyfn -> [rank nome]; o comparador default de vetor ordena por rank (int) e depois nome (string).
    (sort-by (fn [s] [(get posicao s n) s]) situacoes)))
