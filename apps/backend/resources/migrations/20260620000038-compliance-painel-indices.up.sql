-- F5.5a (review database MENOR-1/MENOR-2): indices p/ os reads do painel "a Casa esta em dia" (§16.11),
-- chamados a cada carga do painel. Seguros/no-op no cardinality atual (dezenas-centenas/tenant); previnem
-- regressao conforme os estados FECHADOS (cumprida/dispensada/cancelada) e as re-emissoes de remessa crescem.

-- MENOR-1: resumo-por-estado faz COUNT(*) ... GROUP BY estado p/ o ente. Sem este indice, o GROUP BY
-- heap-fetcha toda linha do tenant p/ obter `estado` (o UNIQUE nao carrega estado; o parcial do sweep so
-- cobre pendente/vencida). Index scan + aggregate sem heap-fetch por linha.
CREATE INDEX IF NOT EXISTS idx_prazo_dominio_ativo_estado
  ON compliance.prazo_dominio_ativo (ente_id, estado);
--;;
-- MENOR-2: listar-recentes ordena por criado_em DESC, id DESC com LIMIT. Sem este indice, Postgres ordena
-- todas as linhas do ente antes do LIMIT. Com ele: Index Scan Backward emite os primeiros LIMIT em ordem.
CREATE INDEX IF NOT EXISTS idx_remessa_gerada_recentes
  ON compliance.remessa_gerada (ente_id, criado_em DESC, id DESC);
