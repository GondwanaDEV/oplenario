-- F4.3a: modulo SESSOES, eixo C do §22.6 — PRESENCA e quorum (camada de fatos). Duas tabelas:
--   presenca_evento       : eventos APPEND-ONLY (entrada|saida|retorno|mudanca_modalidade) x modalidade
--                           (plenario|remoto) x fonte de captura. A presenca CORRENTE nunca e' materializada
--                           em snapshot: e' DERIVADA do ULTIMO evento por vereador ate um instante (§22.6 eixo C).
--                           `ocorrido_em` e' INSTANTE DE DOMINIO (tempo = coordenada de 1a classe, princ.1) —
--                           distinto de `registrado_em` (quando o sistema soube). Descartadas: presenca binaria
--                           por sessao; intervalos explicitos (risco de nao-fechamento).
--   justificativa_ausencia: ato administrativo APARTADO (juizo posterior != fato observado), state machine
--                           pequena pendente -> aprovada|indeferida (terminal). NAO e' tipo de evento de presenca.
-- `vereador_id` e' forward-ref (uuid, sem FK cross-schema p/ cadastros.vereador, §22.10).
-- NOTA DE NOMENCLATURA: o §22.6 chama o enum de captura de "origem"; o codebase ja reserva `origem`/`origem_ref`/
-- `origem_importado_em` p/ a proveniencia de import (convencao §22.4.3). P/ nao sobrecarregar, a fonte de captura
-- vira coluna `fonte` (consistente com `fonte_ingestao` do eixo D). V1 = Nivel 1 (sem integracao de
-- videoconferencia): presenca remota e' marcada manualmente pela secretaria; enum `fonte` SEM 'videoconferencia'.

-- ============================ presenca_evento (append-only) ============================
CREATE TABLE IF NOT EXISTS sessoes.presenca_evento (
  ente_id    uuid NOT NULL,
  id         uuid NOT NULL DEFAULT gen_random_uuid(),
  sessao_id  uuid NOT NULL,
  vereador_id uuid NOT NULL,                              -- forward-ref a cadastros.vereador (sem FK, §22.10)
  tipo       text NOT NULL CHECK (tipo IN ('entrada', 'saida', 'retorno', 'mudanca_modalidade')),
  modalidade text NOT NULL CHECK (modalidade IN ('plenario', 'remoto')),
  -- fonte de captura. Precedencia em conflito de MESMO instante (resolvida na consulta, nao no schema):
  -- manual_secretaria > painel_eletronico > inferida_*. As inferencias (voto/tribuna sem check-in) viram
  -- EVENTO concreto — evita codigo de fallback fragil (§22.6 eixo C).
  fonte      text NOT NULL CHECK (fonte IN
    ('painel_eletronico', 'manual_secretaria', 'inferida_por_voto', 'inferida_por_tribuna')),
  -- precedencia derivada da fonte, MATERIALIZADA (generated stored) p/ entrar no indice do hot-path: o
  -- desempate de mesmo instante (manual>painel>inferida) vira coluna ordenavel -> a derivacao DISTINCT ON
  -- e' IndexScan+Unique sem Sort, e o tiebreak e' deterministico no plano. ESPELHA logic/precedencia-fonte
  -- (a fonte unica app-side); um teste cruzado (presenca_db_test) ancora as duas codificacoes.
  fonte_precedencia integer NOT NULL GENERATED ALWAYS AS (
    CASE fonte WHEN 'manual_secretaria' THEN 3 WHEN 'painel_eletronico' THEN 2 ELSE 1 END) STORED,
  ocorrido_em timestamptz NOT NULL,                       -- INSTANTE DE DOMINIO (quando ocorreu) — parametro das funcoes de relacao
  origem    text NOT NULL DEFAULT 'nativa',
  origem_ref text,
  origem_importado_em timestamptz,
  lote_id   uuid,
  efetivado_em timestamptz,
  created_by uuid,
  registrado_em timestamptz NOT NULL DEFAULT now(),       -- AUDIT (quando o sistema soube) != ocorrido_em
  PRIMARY KEY (ente_id, id),
  FOREIGN KEY (ente_id, sessao_id) REFERENCES sessoes.sessao (ente_id, id)  -- same-schema same-tenant
  -- SEM _staging_valido CHECK: append-only puro (trigger barra UPDATE/DELETE) nao faz efetivacao em 2 fases
  -- (mesmo padrao de legislativo.votos / sessoes.pauta_alteracao). O INSERT live crava efetivado_em=now().
);
--;;
-- hot-path: "ultimo evento por vereador ate <instante>" (DISTINCT ON (vereador) ORDER BY ocorrido_em DESC,
-- fonte_precedencia DESC, id DESC) — serve esta-presente-em? e os agregadores de quorum. As direcoes do indice
-- casam exatamente o ORDER BY (vereador ASC + resto DESC) -> IndexScan+Unique sem Sort. INCLUDE (tipo,
-- modalidade) cobre a projecao da derivacao -> os agregadores leem o indice sem ir ao heap.
CREATE INDEX IF NOT EXISTS idx_presenca_evento_corrente
  ON sessoes.presenca_evento (ente_id, sessao_id, vereador_id, ocorrido_em DESC, fonte_precedencia DESC, id DESC)
  INCLUDE (tipo, modalidade);
--;;
CREATE INDEX IF NOT EXISTS idx_presenca_evento_staging
  ON sessoes.presenca_evento (ente_id, lote_id) WHERE efetivado_em IS NULL;
--;;
ALTER TABLE sessoes.presenca_evento ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE sessoes.presenca_evento FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON sessoes.presenca_evento;
--;;
CREATE POLICY tenant_isolation ON sessoes.presenca_evento
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid
         AND (efetivado_em IS NOT NULL OR lote_id = NULLIF(current_setting('app.ver_lote', true), '')::uuid))
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
GRANT SELECT, INSERT ON sessoes.presenca_evento TO oplenario_app;
--;;
CREATE TRIGGER trg_presenca_evento_append_only
  BEFORE UPDATE OR DELETE ON sessoes.presenca_evento
  FOR EACH ROW EXECUTE FUNCTION shared.imut_append_only();

-- ============================ justificativa_ausencia (ato apartado, state machine) ============================
--;;
CREATE TABLE IF NOT EXISTS sessoes.justificativa_ausencia (
  ente_id    uuid NOT NULL,
  id         uuid NOT NULL DEFAULT gen_random_uuid(),
  sessao_id  uuid NOT NULL,
  vereador_id uuid NOT NULL,                              -- forward-ref (sem FK, §22.10)
  estado     text NOT NULL DEFAULT 'pendente' CHECK (estado IN ('pendente', 'aprovada', 'indeferida')),
  motivo     text NOT NULL CHECK (length(trim(motivo)) > 0),
  decidido_por uuid,
  decidido_em  timestamptz,
  origem    text NOT NULL DEFAULT 'nativa',
  origem_ref text,
  origem_importado_em timestamptz,
  lote_id   uuid,
  efetivado_em timestamptz,
  lock_version integer NOT NULL DEFAULT 0,
  created_by uuid,
  updated_by uuid,
  criado_em    timestamptz NOT NULL DEFAULT now(),
  atualizado_em timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (ente_id, id),
  UNIQUE (ente_id, sessao_id, vereador_id),               -- uma justificativa por vereador por sessao
  FOREIGN KEY (ente_id, sessao_id) REFERENCES sessoes.sessao (ente_id, id),
  -- estado decidido (nao-pendente) exige decisor + instante da decisao; pendente proibe ambos.
  CONSTRAINT justificativa_decisao_coerente CHECK (
    (estado = 'pendente'  AND decidido_por IS NULL     AND decidido_em IS NULL)
    OR (estado <> 'pendente' AND decidido_por IS NOT NULL AND decidido_em IS NOT NULL)),
  CONSTRAINT justificativa_staging_valido CHECK (lote_id IS NOT NULL OR efetivado_em IS NOT NULL)
);
--;;
CREATE INDEX IF NOT EXISTS idx_justificativa_ausencia_sessao
  ON sessoes.justificativa_ausencia (ente_id, sessao_id);
--;;
CREATE INDEX IF NOT EXISTS idx_justificativa_ausencia_staging
  ON sessoes.justificativa_ausencia (ente_id, lote_id) WHERE efetivado_em IS NULL;
--;;
ALTER TABLE sessoes.justificativa_ausencia ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE sessoes.justificativa_ausencia FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON sessoes.justificativa_ausencia;
--;;
CREATE POLICY tenant_isolation ON sessoes.justificativa_ausencia
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid
         AND (efetivado_em IS NOT NULL OR lote_id = NULLIF(current_setting('app.ver_lote', true), '')::uuid))
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
GRANT SELECT, INSERT, UPDATE ON sessoes.justificativa_ausencia TO oplenario_app;
--;;
-- terminal (b): aprovada|indeferida congelam; reabertura so sob correcao auditada (GUC app.correcao_auditada).
CREATE TRIGGER trg_justificativa_ausencia_terminal
  BEFORE UPDATE ON sessoes.justificativa_ausencia
  FOR EACH ROW EXECUTE FUNCTION shared.imut_trava_estado_terminal('aprovada', 'indeferida');
