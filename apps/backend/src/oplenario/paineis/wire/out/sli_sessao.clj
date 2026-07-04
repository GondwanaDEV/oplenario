(ns oplenario.paineis.wire.out.sli-sessao
  "Representacao EXTERNA de SAIDA do SLI de janela de sessao (§22.10 wire/out, ADR-0001, F7 E3) — contrato que
  o `adapters/out` produz. Tudo JSON-serializavel (uuid/Instant viram string). `estado-atual` fica :string
  (nao enum fechado): e' PROJECAO de `sessoes` (mesmo racional de wire/out/tramitacao) — o vocabulario e'
  validado na FONTE. `situacao` e' um rotulo DERIVADO (pelo adapters/out) do estado-atual — a leitura de
  negocio do SLI (em curso / concluida / nao realizada); `duracao-segundos` = janela fechada (aberta ->
  encerrada), presente so' quando a sessao ja' abriu E encerrou.")

(def SliSessaoOut
  [:map {:closed true}
   [:sessao-id :string]
   [:estado-atual :string]
   ;; situacao: UM dos 5 rotulos conhecidos (em_curso | suspensa | realizada | nao_realizada | agendada) OU o
   ;; estado_atual CRU se `sessoes` introduzir um estado novo (fallback deliberado do adapters/out) — NAO
   ;; tratar como enum fechado no consumidor (o switch do FE precisa de um caso default; review clojure MEDIUM).
   [:situacao :string]
   ;; agendada-para: quando a sessao esta' marcada (do agendamento); o FE deriva o no-show comparando com 'agora'
   ;; (servidor puro, nao carimba 'atrasada' — mesma disciplina de duracao viva). Nil se nao houver data cravada.
   [:agendada-para {:optional true} [:maybe :string]]
   [:aberta-em    {:optional true} [:maybe :string]]
   [:encerrada-em {:optional true} [:maybe :string]]
   [:duracao-segundos {:optional true} [:maybe :int]]])

(def SliSessoesOut
  "O SLI de janela de sessao (resposta de GET /paineis/sli/sessoes): sessoes do tenant, em curso primeiro
  (a ordenacao ja' vem do Repo)."
  [:map {:closed true}
   [:sessoes [:sequential SliSessaoOut]]])
