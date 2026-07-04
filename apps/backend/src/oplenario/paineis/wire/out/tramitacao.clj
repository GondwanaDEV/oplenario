(ns oplenario.paineis.wire.out.tramitacao
  "Representacao EXTERNA de SAIDA do board de tramitacao (§22.10 wire/out, ADR-0001, F7 Slice 2) — contrato
  que o `adapters/out` produz. Tudo JSON-serializavel (uuid/Instant viram string). `estado`/`tipo`/
  `autor-tipo` ficam :string (nao enum fechado): esta e' uma PROJECAO pura de `legislativo` (mesmo racional
  de transparencia/wire/out/materia e paineis/wire/out/pendencia) — o vocabulario e' validado na FONTE.")

(def ItemBoardOut
  [:map {:closed true}
   [:proposicao-id :string]
   [:tipo :string]
   [:ano :int]
   [:sequencial :int]
   [:urn-lex :string]
   [:ementa :string]
   [:autor-tipo {:optional true} [:maybe :string]]
   [:autor-texto {:optional true} [:maybe :string]]
   [:estado :string]
   [:transicionou-em :string]])

(def TramitacaoBoardOut
  "O board de tramitacao (resposta de GET /paineis/tramitacao): TODAS as proposicoes, mais estagnadas
  primeiro dentro do agrupamento por estado (a ordenacao ja' vem do Repo)."
  [:map {:closed true}
   [:itens [:sequential ItemBoardOut]]])
