-- Faixa B / B.2 da Track IA (docs/25 Eixo 3.5, ADR-0010) — o AUDIT das chamadas de ferramenta feitas por agente que
-- escrevem (classes 'rascunho' e 'ato'): pessoa + agente + execucao + ferramenta + classe + desfecho, sempre. Leitura
-- segue a regra das telas; o registro completo de cada execucao fica no log de inferencia do satelite (§22.3.4).
-- Append-only, por tenant (RLS). Tentativa negada tambem entra: e' o rastro que importa quando algo tenta demais.
CREATE TABLE IF NOT EXISTS integracao_ia.chamada_agente (
  ente_id       uuid NOT NULL,
  id            uuid NOT NULL DEFAULT gen_random_uuid(),
  execucao_id   uuid NOT NULL,
  identidade_id uuid,                              -- NULL = agente institucional
  agente        text NOT NULL,
  ferramenta    text NOT NULL,
  classe        text NOT NULL CHECK (classe IN ('leitura', 'rascunho', 'ato')),
  desfecho      text NOT NULL CHECK (desfecho IN ('ok', 'nao_encontrado', 'negado', 'invalido', 'erro')),
  ocorrido_em   timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (ente_id, id)
);
--;;
CREATE INDEX IF NOT EXISTS idx_chamada_agente_execucao ON integracao_ia.chamada_agente (ente_id, execucao_id, ocorrido_em);
--;;
ALTER TABLE integracao_ia.chamada_agente ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE integracao_ia.chamada_agente FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON integracao_ia.chamada_agente;
--;;
CREATE POLICY tenant_isolation ON integracao_ia.chamada_agente
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid)
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
GRANT SELECT, INSERT ON integracao_ia.chamada_agente TO oplenario_app;
--;;
CREATE TRIGGER trg_chamada_agente_append_only
  BEFORE UPDATE OR DELETE ON integracao_ia.chamada_agente
  FOR EACH ROW EXECUTE FUNCTION shared.imut_append_only();
