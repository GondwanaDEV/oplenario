-- F4.4a: votacao-na-sessao (§22.6 eixo B). A votacao do legislativo (eixo G / F3.7) ja' e' session-aware
-- (sessao_id forward-ref existe). Falta o CONTEXTO TEMPORAL na pauta: pauta_item_id. Decisao §22.6:
--   "Votacao aponta para proposicao via (objeto_tipo,objeto_id) polimorfico de §22.4 eixo G, com
--    pauta_item_id OPCIONAL como contexto temporal — votacao e' sobre a MATERIA, nao sobre o item da
--    pauta; materia pode ser votada em duas sessoes (1a e 2a discussao), duas votacoes com pauta_item_id
--    diferentes mas mesma proposicao_id."
-- Forward-ref a sessoes.pauta_item: SEM FK (§22.10 proibe FK cross-schema) — exatamente como sessao_id.
-- Reusa toda a mecanica de votacao (abrir!/encerrar!/quorum exato) — disciplina 5, nada de tabela nova.
ALTER TABLE legislativo.votacoes ADD COLUMN IF NOT EXISTS pauta_item_id uuid;
--;;
-- coerencia (review F4.4a DB-MENOR): um item de pauta pertence a uma sessao, logo votar sobre um item
-- implica estar numa sessao. pauta_item_id sem sessao_id e' incoerente -> o DB trava (belt-and-suspenders;
-- o caminho inverso, sessao sem item, e' valido = votacao avulsa na sessao).
ALTER TABLE legislativo.votacoes ADD CONSTRAINT votacao_pauta_item_requer_sessao
  CHECK (pauta_item_id IS NULL OR sessao_id IS NOT NULL);
--;;
-- lado-filho do forward-ref; parcial (votacao fora de sessao nao tem item). Serve a navegacao
-- "votacoes deste item de pauta" no painel da sessao (F4 real-time / leitura do read-model).
CREATE INDEX IF NOT EXISTS idx_votacoes_pauta_item
  ON legislativo.votacoes (ente_id, pauta_item_id) WHERE pauta_item_id IS NOT NULL;
