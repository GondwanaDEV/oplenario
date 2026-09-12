(ns oplenario.paineis.wire.out.notificacao
  "Representacao EXTERNA de SAIDA da INBOX (§22.10 wire/out, ADR-0001, Onda E fatia 1) — o contrato que
  `adapters/out` produz e o codegen exporta p/ o TS. Tudo JSON-serializavel (uuid/Instant viram string).
  `categoria`/`objeto-tipo` ficam :string (nao enum fechado): a inbox e' PROJECAO de um evento cujo
  vocabulario e' validado na FONTE (o produtor), nao duplicado aqui — mesmo racional de wire/out/pendencia.

  O `destinatario` NAO faz parte do contrato: a rota e' sempre 'as minhas', resolvida do ator; devolve-lo
  seria vazar um identificador que o cliente nao precisa e nao pode usar para nada.")

(def NotificacaoOut
  [:map {:closed true}
   [:id :string]
   [:categoria :string]
   [:assunto :string]
   [:corpo :string]
   [:objeto-tipo :string]
   [:objeto-id :string]
   [:criado-em :string]
   ;; null = nao lida (o estado de leitura E' a ausencia do carimbo)
   [:lida-em [:maybe :string]]])

(def MinhasNotificacoesOut
  "Resposta de GET /meu/notificacoes: a lista (teto 50, mais recentes primeiro) + a contagem TOTAL de nao
  lidas (NAO limitada pelo teto) + `notificacoes-total` (fatia 'truncamento-familia' sitio b): o total REAL
  do ator, lidas+nao-lidas — o par irmao de VERDADE de `notificacoes` (MESMO WHERE de
  `listar-do-destinatario`). `nao-lidas` sozinho NAO cobre o corte: um ator com 200 lidas + 5 nao lidas
  bate `nao-lidas`=5 (todas dentro do teto) e a tela concluiria, errado, que nada foi cortado — as 155
  lidas que caem fora do teto de 50 precisam de `notificacoes-total` para nao sumir em silencio. O teto
  NUNCA e' publicado, so' o par (lista cortada, total real)."
  [:map {:closed true}
   [:notificacoes [:sequential NotificacaoOut]]
   [:nao-lidas :int]
   [:notificacoes-total :int]])

(def MarcarLidaOut
  "Resposta de POST /meu/notificacoes/:id/lida (Task 7): o recibo idempotente."
  [:map {:closed true}
   [:id :string]
   [:lida-em :string]])
