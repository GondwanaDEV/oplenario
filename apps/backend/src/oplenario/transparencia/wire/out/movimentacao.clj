(ns oplenario.transparencia.wire.out.movimentacao
  "Representacao EXTERNA de SAIDA da linha do tempo da materia no portal (§22.10 wire/out, ADR-0001). SO' o que e'
  publico: QUANDO e EM QUE ETAPA. Nao ha campo de quem despachou, de gatilho, de contexto nem de parecer — o contrato
  e' `:closed`, entao um campo a mais e' bug de servidor (500), nunca vazamento silencioso. Tudo JSON-serializavel
  (Instant vira string ISO).")

(def MovimentacaoOut
  "Uma movimentacao. `etapa` = o nome da etapa no rito da Casa (`nil` = o rito nao declara o estado de destino: a
  tela diz isso, nunca exibe a chave). `abertura` = a primeira linha, o protocolo."
  [:map {:closed true}
   [:ocorrido-em :string]
   [:etapa [:maybe :string]]
   [:abertura :boolean]])

(def MovimentacoesOut
  "Resposta de GET /portal/casa/:ente/materias/:proposicao_id/movimentacoes — da mais recente para a mais antiga.
  `movimentacoes-total` e' o par obrigatorio do teto server-side (o teto em si nunca sai: e' decisao de seguranca).
  `historico-completo` = a abertura (protocolo) esta' no historico; `false` = o historico COMECA NO MEIO (materia
  anterior a esta funcionalidade sem reconstrucao possivel) e `historico-desde` diz a partir de quando ele existe —
  a tela nunca finge completude."
  [:map {:closed true}
   [:movimentacoes [:sequential MovimentacaoOut]]
   [:movimentacoes-total :int]
   [:historico-completo :boolean]
   [:historico-desde [:maybe :string]]])
