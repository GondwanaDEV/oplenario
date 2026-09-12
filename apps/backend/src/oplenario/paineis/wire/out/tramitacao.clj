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

(def TotalPorEstadoOut
  "Item do par lista/total do board (fatia 'truncamento-familia'): quantas proposicoes o tenant tem NAQUELE
  estado, sem o corte por-estado da lista (ver docstring de TramitacaoBoardOut)."
  [:map {:closed true}
   [:estado :string]
   [:total :int]])

(def TramitacaoBoardOut
  "O board de tramitacao (resposta de GET /paineis/tramitacao): as proposicoes do tenant, mais estagnadas
  primeiro dentro do agrupamento por estado (a ordenacao ja' vem do Repo).

  TRUNCAMENTO: `itens` corta no teto POR ESTADO (50, `components/repositorio`, defesa-em-profundidade em
  `db/tramitacao/listar-board`) — NAO 'todas as proposicoes' (a docstring antiga deste ns mentia isso; era
  o defeito que esta fatia fecha). Como o corte e' por GRUPO (nao um teto global sobre a lista inteira),
  um `itens-total` escalar seria uma mentira NOVA: nenhum numero real corresponderia a ele (nao ha 'o
  total' quando o que existe e' 'o total de cada estado'). `totais-por-estado` publica o par certo no
  nivel certo: para cada estado com ao menos 1 item no tenant, {:estado :total}, SEM o teto (mesmo
  racional de `transparencia/wire/out/parlamentar` e `compliance/wire/out/painel` — o par lista+total e'
  obrigatorio, nao opcional). `:limite` em si NAO sai neste contrato — o teto e' server-side por decisao
  de seguranca, e nenhum wire/out do repo publica teto ao cliente."
  [:map {:closed true}
   [:itens [:sequential ItemBoardOut]]
   [:totais-por-estado [:sequential TotalPorEstadoOut]]])
