-- F4.5c: modulo SESSOES, eixo F do §22.6 — TRIBUNA: DECISAO DA MESA. Registro da decisao do presidente sobre
-- questao de ordem — ato regimental com efeito juridico que VAI PARA A ATA. Entidade APARTADA (disciplina
-- §22.4.3: "atos auditados tem registro proprio"), nao um tipo de fala. APPEND-ONLY puro: a decisao e' tomada
-- uma vez; corrigir = nova decisao (nunca UPDATE), padrao presenca_evento/votos. `fala_id` opcional (a questao
-- pode ser decidida sem uma fala_executada registrada). `presidente_id` e' forward-ref (uuid, sem FK, §22.10).
-- NAO-particionada (cardinalidade baixa por sessao).
CREATE TABLE IF NOT EXISTS sessoes.decisao_mesa (
  ente_id    uuid NOT NULL,
  id         uuid NOT NULL DEFAULT gen_random_uuid(),
  sessao_id  uuid NOT NULL,
  fala_id    uuid,                                        -- a fala de questao_de_ordem que motivou (opcional)
  presidente_id uuid NOT NULL,                            -- quem decidiu (forward-ref, sem FK, §22.10)
  questao    text NOT NULL CHECK (length(trim(questao)) > 0),
  decisao    text NOT NULL CHECK (length(trim(decisao)) > 0),
  -- base regimental/legal (opcional); se presente, nao-vazia (campo de peso juridico, ~ questao/decisao).
  fundamentacao text CHECK (fundamentacao IS NULL OR length(trim(fundamentacao)) > 0),
  decidido_em timestamptz NOT NULL,                       -- INSTANTE DE DOMINIO (quando o presidente decidiu)
  origem    text NOT NULL DEFAULT 'nativa',
  origem_ref text,
  origem_importado_em timestamptz,
  lote_id   uuid,
  efetivado_em timestamptz,
  created_by uuid,
  registrado_em timestamptz NOT NULL DEFAULT now(),       -- AUDIT (quando o sistema soube) != decidido_em
  PRIMARY KEY (ente_id, id),
  FOREIGN KEY (ente_id, sessao_id) REFERENCES sessoes.sessao (ente_id, id),
  FOREIGN KEY (ente_id, fala_id)   REFERENCES sessoes.fala_executada (ente_id, id)  -- nullable: MATCH SIMPLE
  -- SEM _staging_valido CHECK: append-only puro (o INSERT live crava efetivado_em=now()), padrao presenca_evento.
);
--;;
-- read-model "decisoes da mesa desta sessao" (composicao da ata) em ordem cronologica.
CREATE INDEX IF NOT EXISTS idx_decisao_mesa_sessao
  ON sessoes.decisao_mesa (ente_id, sessao_id, decidido_em);
--;;
-- suporte ao FK fala_id (DELETE maintenance) + "decisoes referentes a esta fala". Parcial: fala_id NULL nao tem FK.
CREATE INDEX IF NOT EXISTS idx_decisao_mesa_fala
  ON sessoes.decisao_mesa (ente_id, fala_id) WHERE fala_id IS NOT NULL;
--;;
-- read-model historico cross-session "decisoes do presidente X" (estatisticas, TCE). presidente_id e' forward-ref
-- (sem FK), mas o indice evita seqscan ao crescer o volume de sessoes (espelha idx_fala_executada_orador).
CREATE INDEX IF NOT EXISTS idx_decisao_mesa_presidente
  ON sessoes.decisao_mesa (ente_id, presidente_id, decidido_em DESC);
--;;
CREATE INDEX IF NOT EXISTS idx_decisao_mesa_staging
  ON sessoes.decisao_mesa (ente_id, lote_id) WHERE efetivado_em IS NULL;
--;;
ALTER TABLE sessoes.decisao_mesa ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE sessoes.decisao_mesa FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON sessoes.decisao_mesa;
--;;
CREATE POLICY tenant_isolation ON sessoes.decisao_mesa
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid
         AND (efetivado_em IS NOT NULL OR lote_id = NULLIF(current_setting('app.ver_lote', true), '')::uuid))
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
GRANT SELECT, INSERT ON sessoes.decisao_mesa TO oplenario_app;
--;;
CREATE TRIGGER trg_decisao_mesa_append_only
  BEFORE UPDATE OR DELETE ON sessoes.decisao_mesa
  FOR EACH ROW EXECUTE FUNCTION shared.imut_append_only();
