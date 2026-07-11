-- Onda C1 (§11.2/§11.3): ciencia_vereador — o vereador "da ciencia" a um evento do proprio legislativo
-- (V1: so' parecer PUBLICADO sobre proposicao de sua autoria). APPEND-ONLY PURO (nivel a, Inv.10): a
-- prova "voce foi notificada, com data e hora" nunca muda nem some. Sem origem/origem_ref/lote_id/
-- efetivado_em (mesmo padrao ENXUTO de legislativo.parecer_transicao_historico, mig 0019) — nao ha
-- cenario de importacao de legado p/ um ato de reconhecimento do proprio usuario na V1.
CREATE TABLE IF NOT EXISTS legislativo.ciencia_vereador (
  ente_id     uuid NOT NULL,
  id          uuid NOT NULL DEFAULT gen_random_uuid(),
  vereador_id uuid NOT NULL,                 -- forward-ref a cadastros.vereador (sem FK cross-schema, §22.10)
  evento_ref  uuid NOT NULL,                 -- id do evento reconhecido (V1: pareceres.id) — sem FK (polimorfico por tipo)
  tipo        text NOT NULL,                 -- vocabulario aberto (so' 'parecer_publicado' na V1)
  ciente_em   timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (ente_id, id),
  -- idempotencia: no maximo 1 ciencia por (vereador, evento) — acusar 2x nao duplica nem reescreve.
  UNIQUE (ente_id, vereador_id, evento_ref)
);
--;;
-- hot-path "minhas ciencias pendentes" (anti-join em db/meu_painel.clj/ciencias-pendentes).
CREATE INDEX IF NOT EXISTS idx_ciencia_vereador_vereador ON legislativo.ciencia_vereador (ente_id, vereador_id);
--;;
ALTER TABLE legislativo.ciencia_vereador ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE legislativo.ciencia_vereador FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON legislativo.ciencia_vereador;
--;;
CREATE POLICY tenant_isolation ON legislativo.ciencia_vereador
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid)
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
-- append-only puro (Inv.10): sem UPDATE/DELETE — o registro de ciencia nunca se altera nem se apaga.
GRANT SELECT, INSERT ON legislativo.ciencia_vereador TO oplenario_app;
--;;
CREATE TRIGGER trg_ciencia_vereador_append_only
  BEFORE UPDATE OR DELETE ON legislativo.ciencia_vereador
  FOR EACH ROW EXECUTE FUNCTION shared.imut_append_only();
