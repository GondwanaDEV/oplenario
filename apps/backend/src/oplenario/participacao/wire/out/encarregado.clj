(ns oplenario.participacao.wire.out.encarregado
  "Representacao EXTERNA de SAIDA do ENCARREGADO/DPO (§22.10 wire/out, ADR-0001) — o contrato da rota PUBLICA
  sem-auth GET /portal/casa/:ente/encarregado. O contato do DPO e' LEGALMENTE PUBLICO (LGPD art. 41 §1º): a Casa
  DEVE divulgar. O view carrega SO o contato publico {nome, rotulo, email} — NENHUM interno (id, ente-id,
  atualizado-por, timestamps). A defesa anti-vazamento mora no adapters/out (a rota nao tem ator).")

(def EncarregadoPublicoOut
  "Contato PUBLICO do Encarregado/DPO. So o minimo que a LGPD torna publico (nome, rotulo, email de contato).
  Sem ids internos, sem quem atualizou, sem tenant.
  ATENCAO (superficie ANONIMA): `nome`/`rotulo`/`email` sao texto livre gravado por um servidor e servido a
  QUALQUER visitante nao-autenticado (GET /portal/casa/:ente/encarregado) — alcance MAIOR que o `detalhe` da
  solicitacao (que so volta ao proprio titular). O backend NAO sanitiza (o `email` segue [GAP]: so nao-vazio +
  teto, sem validacao semantica); o consumidor DEVE escapar antes de renderizar como HTML (anti stored-XSS)."
  [:map {:closed true}
   [:nome :string]
   [:rotulo :string]
   [:email :string]])
