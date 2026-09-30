-- ADR-0019 fatia 2a (Eixo 5) — a nota tecnica da IA vira RASCUNHO do parecer juridico. O advogado clica "Usar como
-- rascunho" na nota: abre-se (ou reaproveita-se) o pedido de parecer, nasce o rascunho com o texto da nota sem as marcas
-- de citacao, e a nota fica `aproveitada` — tudo numa tx. Aqui so' o que o banco precisa saber:
--
-- (1) o pedido ganha a origem 'nota_tecnica' (o advogado abriu o pedido a partir da nota; `pedido_por` = ele);
-- (2) o parecer registra a ORIGEM DO RASCUNHO: `origem_rascunho` + `nota_tecnica_id`. E' informativo: nao muda a
--     imutabilidade nem a assinatura — o parecer continua sendo do advogado que o revisou, assumiu e assinou. O texto da
--     IA nunca vira "parecer" por si. A origem so' e' escrita ao ABRIR o rascunho (o trigger de imutabilidade so' barra
--     UPDATE/DELETE de assinado; um rascunho nao muda de origem).
ALTER TABLE legislativo.pedido_parecer_juridico
  DROP CONSTRAINT IF EXISTS pedido_parecer_juridico_origem_check;
--;;
ALTER TABLE legislativo.pedido_parecer_juridico
  ADD CONSTRAINT pedido_parecer_juridico_origem_check CHECK (origem IN ('secretaria', 'relator', 'nota_tecnica'));
--;;
ALTER TABLE legislativo.parecer_juridico
  ADD COLUMN IF NOT EXISTS origem_rascunho text CHECK (origem_rascunho IS NULL OR origem_rascunho IN ('nota_tecnica'));
--;;
ALTER TABLE legislativo.parecer_juridico
  ADD COLUMN IF NOT EXISTS nota_tecnica_id uuid;
--;;
ALTER TABLE legislativo.parecer_juridico
  ADD CONSTRAINT parecer_juridico_origem_completa CHECK ((origem_rascunho IS NULL) = (nota_tecnica_id IS NULL));
--;;
ALTER TABLE legislativo.parecer_juridico
  ADD CONSTRAINT parecer_juridico_nota_fk FOREIGN KEY (ente_id, nota_tecnica_id)
    REFERENCES legislativo.nota_tecnica (ente_id, id);
--;;
CREATE INDEX IF NOT EXISTS idx_parecer_juridico_nota
  ON legislativo.parecer_juridico (ente_id, nota_tecnica_id) WHERE nota_tecnica_id IS NOT NULL;
