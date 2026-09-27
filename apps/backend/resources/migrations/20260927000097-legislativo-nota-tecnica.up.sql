-- Faixa B / B.8 da Track IA (docs/25 Eixo 7.7, ADR-0013) — a NOTA TECNICA de conferencia: o agente institucional da
-- Casa le a proposicao protocolada e os dispositivos da LOM/RI que se aplicam e deixa um RASCUNHO com citacoes na fila
-- da secretaria. Nunca uma decisao: a secretaria aproveita (com ou sem edicao) ou descarta.
--
-- O rascunho mora no CORE (docs/25 5.3: rascunho tem valor institucional) e chega pela ferramenta `rascunho` do
-- catalogo (`registrar_nota_tecnica`), gravada com a execucao do agente. Uma conferencia por proposicao e agente:
-- repetir a execucao (o satelite tenta de novo) nao duplica a fila. O texto do agente nunca muda; o que a
-- secretaria aproveitou fica em `texto_final`, com quem e quando.
CREATE TABLE IF NOT EXISTS legislativo.nota_tecnica (
  ente_id              uuid NOT NULL,
  id                   uuid NOT NULL DEFAULT gen_random_uuid(),
  proposicao_id        uuid NOT NULL,
  agente               text NOT NULL,
  execucao_id          uuid NOT NULL,
  texto                text NOT NULL CHECK (length(btrim(texto)) > 0 AND length(texto) <= 20000),
  citacoes             jsonb NOT NULL DEFAULT '[]'::jsonb,
  paragrafos_sem_fonte jsonb NOT NULL DEFAULT '[]'::jsonb,
  incerteza            text NOT NULL CHECK (incerteza IN ('normal', 'revisar_com_atencao')),
  motivos_incerteza    jsonb NOT NULL DEFAULT '[]'::jsonb,
  modelo_llm_id        text NOT NULL,
  estado               text NOT NULL DEFAULT 'pendente' CHECK (estado IN ('pendente', 'aproveitada', 'descartada')),
  texto_final          text CHECK (texto_final IS NULL OR length(texto_final) <= 20000),
  decidida_por         uuid,
  decidida_em          timestamptz,
  criada_em            timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (ente_id, id),
  UNIQUE (ente_id, proposicao_id, agente),
  FOREIGN KEY (ente_id, proposicao_id) REFERENCES legislativo.proposicoes (ente_id, id),
  CONSTRAINT nota_decisao_completa CHECK (
    (estado = 'pendente') = (decidida_por IS NULL AND decidida_em IS NULL)),
  CONSTRAINT nota_aproveitada_tem_texto CHECK ((estado = 'aproveitada') = (texto_final IS NOT NULL))
);
--;;
CREATE INDEX IF NOT EXISTS idx_nota_tecnica_fila ON legislativo.nota_tecnica (ente_id, estado, criada_em);
--;;
ALTER TABLE legislativo.nota_tecnica ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE legislativo.nota_tecnica FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON legislativo.nota_tecnica;
--;;
CREATE POLICY tenant_isolation ON legislativo.nota_tecnica
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid)
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
GRANT SELECT, INSERT, UPDATE (estado, texto_final, decidida_por, decidida_em) ON legislativo.nota_tecnica TO oplenario_app;
