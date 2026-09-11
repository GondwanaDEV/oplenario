-- Fatia 1 da BORDA DE TRAMITACAO: o elo MATERIA <-> TEMPLATE.
--
-- A engine do eixo C (legislativo/db/tramitacao/transicionar!) sempre exigiu `template_id` como
-- argumento, e `legislativo.proposicoes` nunca teve onde guardar o seu — a semente da demo carregava o
-- `tid` numa variavel local. Por isso a borda HTTP da tramitacao nunca existiu: nao havia de onde tirar
-- o template. Esta coluna e' esse elo. O precedente e' `legislativo.pareceres` (mig 0019), que ja' nasce
-- com `template_id` + FK same-tenant e deriva o estado inicial do template.
--
-- NULLABLE (ao contrario de pareceres, onde a coluna e' NOT NULL) — e o motivo e' historico, nao
-- preferencia:
--   (a) ja' existem proposicoes protocoladas neste acervo, e elas nasceram ANTES desta coluna;
--   (b) nao ha' rito conhecido para atribuir template retroativamente: escolher um rito por uma materia
--       ja' em curso e' decisao regimental da Casa, nao de migration. Backfill inventado aqui seria
--       fabricar historia processual.
-- Materia sem template simplesmente NAO TRAMITA — que e' exatamente o comportamento de hoje (nenhuma
-- proposicao tramita, porque nenhuma tem rito). A diferenca e' que agora isso e' EXPLICITO no schema em
-- vez de implicito na ausencia de uma coluna. Sem backfill, sem DEFAULT.
ALTER TABLE legislativo.proposicoes
  ADD COLUMN IF NOT EXISTS template_id uuid;
--;;
-- FK same-tenant (o par (ente_id, template_id), espelhando pareceres): o rito e' do MESMO ente. Um
-- template do vizinho nao casa, mesmo que o uuid seja conhecido — nao e' so' a RLS que segura.
-- MATCH SIMPLE (default): com `template_id` NULL a constraint NAO e' checada, que e' o que permite a
-- coluna nascer nullable sobre um acervo existente sem validar nada retroativo.
-- Sem `ADD CONSTRAINT IF NOT EXISTS` no Postgres -> guarda por catalogo (a migration e' idempotente por
-- migratus, mas o resto do schema segue a convencao defensiva `IF NOT EXISTS`).
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'proposicoes_template_fk') THEN
    ALTER TABLE legislativo.proposicoes
      ADD CONSTRAINT proposicoes_template_fk
      FOREIGN KEY (ente_id, template_id) REFERENCES legislativo.template_tramitacao (ente_id, id);
  END IF;
END $$;
--;;
-- convencao "FK always indexed" (review F3.5 DB-M1 / F3.6a DB-MAJOR). PARCIAL, como
-- idx_pareceres_relator: durante toda a transicao a maioria das linhas tem `template_id` NULL, e linha
-- com NULL nunca e' alvo do lookup por FK.
CREATE INDEX IF NOT EXISTS idx_proposicoes_template_id
  ON legislativo.proposicoes (ente_id, template_id) WHERE template_id IS NOT NULL;
