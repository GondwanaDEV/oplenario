(ns oplenario.tempo-real.canais
  "Nomes de canal + roteamento PURO evento->canais (§22.6 eixo G). `sessao/{id}/plenario` = painel ao vivo (a
  authz POR CONEXAO — participante da sessao — e a por-evento via policy.check sao avaliadas na ABERTURA da
  conexao no endpoint SSE, G3). Os canais `publico/sessao/{id}` e `vereador/{id}/dashboard` + sua authz/capability
  aterrissam com o endpoint (G3); aqui mora so o roteamento estrutural por tipo de evento.

  >>> CHECKLIST DE AUTORIZACAO OBRIGATORIO PARA O ENDPOINT SSE (G3) — review de seguranca G2 <<<
  A CanalStore e' tenant-cega por design (a mensagem carrega `:ente-id`, mas isso e' defesa-em-profundidade,
  NAO a barreira). O endpoint, na ABERTURA da conexao (nao por evento), DEVE:
   1. POSSE DE TENANT (obrigatorio): consultar sessoes.sessao e exigir sessao.ente_id == ente do ator. O nome
      do canal (uuid) NAO e' barreira — existir um canal != poder le-lo.
   2. SESSAO SECRETA (obrigatorio): se transmite_publica=false, RECUSAR a subscricao (403) — nao filtrar por
      evento. Vale tambem p/ o replay (ler-desde) na reconexao.
   3. CAPABILITY (obrigatorio): policy.check (§22.5) do ator antes de devolver qualquer evento.
   4. CANAL PUBLICO (quando existir): retirar campos internos da projecao — `:fonte` de presenca.registrada e
      `:ator-id` de sessao.transicionou — antes de emitir ao portal cidadao.")

(defn canal-plenario [sessao-id] (str "sessao/" sessao-id "/plenario"))

(def tipos-plenario
  "FONTE UNICA dos tipos de evento que viram mensagem do painel ao vivo (`consumer/tipos-consumidos`
  deriva DAQUI — evita drift entre roteamento e registro no bus). Cobre a TRIADE do hemiciclo: conducao da
  sessao + presenca/quorum, tribuna (fala/inscricao) e o PLACAR DE VOTACAO (votacao.aberta/voto.registrado/
  votacao.encerrada — emitidos pelo legislativo, §22.6 eixo G). `gravacao.segmento-captado` NAO entra (e'
  fronteira core->IA, nao SSE) — roteia p/ []. SIGILO §22.6: a projecao (projecao.clj) e' o ultimo portao
  do voto secreto — o `voto.registrado` secreto vira so contador, nunca identidade."
  #{"sessao.transicionou" "presenca.registrada" "fala.iniciada" "fala.encerrada" "fala.cronometro"
    "inscricao.registrada" "inscricao.desistida" "incidente.registrado"
    "votacao.aberta" "voto.registrado" "votacao.encerrada"})

(def tipo-lacuna
  "Tipo SINTETICO do painel ao vivo — nunca um evento de dominio, nunca passa pelo outbox/relay. O backplane
  da CanalStore (`tempo-real/components`, impl Valkey) o INJETA dentro do proprio `ler-desde` quando uma
  entrada do stream nao valida na leitura (corrupcao/escrita externa nao confiavel —
  `mensagem-valida?`). Frente 'truncamento-familia', sitio (d): antes, essa entrada era DESCARTADA
  (`keep` devolvendo nil) e o cursor do cliente avancava por cima do buraco como se o replay estivesse
  integro — sem sinal nenhum, nem no servidor nem no cliente. Agora vira esta mensagem, que ocupa a MESMA
  seq da entrada corrompida (o cursor avanca SABENDO do buraco, nao por cima dele).

  NAO entra em `tipos-plenario`: aquele set e' so' para roteamento de evento de DOMINIO outbox->canal
  (`consumer/tipos-consumidos` registra 1 handler de bus POR tipo dali); registrar um consumidor de bus
  para um tipo que o outbox nunca emite seria, na melhor das hipoteses, um registro morto. Este tipo entra
  so' em `tipos-emitidos-ao-cliente`, a fonte do enum que `wire/out/evento-sse` aceita na SAIDA."
  "tempo-real.lacuna")

(def tipos-emitidos-ao-cliente
  "Uniao de `tipos-plenario` (eventos de dominio roteados) + `tipo-lacuna` (sinal sintetico do backplane) —
  a fonte unica do enum de `:tipo` que `wire/out/evento-sse` valida na SAIDA. `tipos-plenario` sozinho
  seguiria descrevendo so' roteamento de dominio; este set e' o que de fato PODE chegar ao cliente pelo
  canal plenario."
  (conj tipos-plenario tipo-lacuna))

(defn rotas-do-evento
  "Canais que um evento de dominio alimenta. Por ora so o canal plenario da sessao (painel ao vivo); devolve []
  p/ eventos nao-SSE. O `sessao-id` sai do payload (string ISO/uuid serializada — basta concatenar no nome)."
  [{:keys [tipo payload]}]
  (if (contains? tipos-plenario tipo)
    [(canal-plenario (:sessao-id payload))]
    []))
