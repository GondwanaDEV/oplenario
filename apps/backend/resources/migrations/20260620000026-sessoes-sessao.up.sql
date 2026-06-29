-- F4.1: modulo SESSOES, eixo A do §22.6 — entidade SESSAO (o encontro plenario). Fundacao do HERO (M4).
-- Hierarquia temporal: legislatura -> sessao_legislativa (ambas em `cadastros`, F1) -> sessao (aqui). Como
-- §22.10 proibe FK cross-schema, `sessao_legislativa_id` e' uuid FORWARD-REF (sem FK; integridade via
-- resolver/servico, mesmo padrao de autor_id/sessao_id). Tipo como ENUM nominal + CAPABILITIES desacopladas
-- (atributos da sessao; defaults derivados do tipo, override individual auditado). Estados grandes na sessao
-- (agendada|aberta|suspensa|encerrada|nao_realizada|arquivada) — a FASE (expediente/ordem do dia) e' atributo
-- da pauta corrente (eixo B), nao estado da sessao. Modalidade independente do tipo. Numeracao canonica por
-- (ente, sessao_legislativa, tipo) resetando por sessao legislativa (escopo 'sessao:<leg>:<tipo>' gapless).
-- NAO-particionada (cardinalidade moderada).

-- 1a migration do modulo sessoes: o role de runtime precisa de USAGE no schema (mesmo padrao de legislativo 0013).
GRANT USAGE ON SCHEMA sessoes TO oplenario_app;
--;;
CREATE TABLE IF NOT EXISTS sessoes.sessao (
  ente_id      uuid NOT NULL,
  id           uuid NOT NULL DEFAULT gen_random_uuid(),
  sessao_legislativa_id uuid NOT NULL,                    -- forward-ref a cadastros.sessao_legislativa (sem FK)
  tipo_sessao  text NOT NULL CHECK (tipo_sessao IN
    ('ordinaria', 'extraordinaria', 'solene', 'secreta', 'especial')),
  numero_sequencial integer NOT NULL,                     -- canonico por (ente, sessao_legislativa, tipo)
  estado       text NOT NULL DEFAULT 'agendada' CHECK (estado IN
    ('agendada', 'aberta', 'suspensa', 'encerrada', 'nao_realizada', 'arquivada')),
  modalidade   text NOT NULL DEFAULT 'presencial' CHECK (modalidade IN ('presencial', 'remota', 'hibrida')),
  -- CAPABILITIES (§22.6 eixo A): comportamento desacoplado do nome do tipo; default derivado, override auditado.
  delibera                 boolean NOT NULL,
  transmite_publica        boolean NOT NULL,
  gera_ata_regimental      boolean NOT NULL,
  permite_voto_secreto     boolean NOT NULL,
  permite_modalidade_remota boolean NOT NULL,
  -- marcos temporais (tempo e' coordenada de 1a classe, §22.6.3 disc.1)
  agendada_para  timestamptz,                             -- quando esta marcada
  aberta_em      timestamptz,                             -- abertura efetiva
  encerrada_em   timestamptz,                             -- encerramento (ou nao-realizacao)
  motivo_nao_realizada text,                              -- preenchido em 'nao_realizada'
  -- transversais (§22.4.3 disc.1); estado muta -> updated_*/lock_version
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
  -- numeracao canonica unica por (ente, sessao_legislativa, tipo) — resetando por sessao legislativa
  UNIQUE (ente_id, sessao_legislativa_id, tipo_sessao, numero_sequencial),
  -- coerencia: 'nao_realizada' carrega o motivo (nao-vazio); o motivo so existe nela (ou arquivada herdada)
  CONSTRAINT sessao_nao_realizada_tem_motivo
    CHECK (estado <> 'nao_realizada' OR motivo_nao_realizada IS NOT NULL),
  CONSTRAINT sessao_motivo_so_em_nao_realizada
    CHECK (motivo_nao_realizada IS NULL OR estado IN ('nao_realizada', 'arquivada')),
  CONSTRAINT sessao_motivo_nao_vazio
    CHECK (motivo_nao_realizada IS NULL OR length(trim(motivo_nao_realizada)) > 0),
  -- coerencia dos MARCOS TEMPORAIS com o estado (tempo = dado de 1a classe; padrao do votacoes; review F4.1 DB-MAJOR1).
  -- aberta_em so existe pos-abertura; obrigatorio nos estados pos-abertura (exceto 'arquivada', que colapsa os
  -- dois historicos: encerrada->arquivada TEM aberta_em, nao_realizada->arquivada NAO tem).
  CONSTRAINT sessao_aberta_em_exige_estado
    CHECK (aberta_em IS NULL OR estado IN ('aberta', 'suspensa', 'encerrada', 'arquivada')),
  CONSTRAINT sessao_aberta_em_obrigatoria
    CHECK (estado NOT IN ('aberta', 'suspensa', 'encerrada') OR aberta_em IS NOT NULL),
  CONSTRAINT sessao_encerrada_em_exige_estado
    CHECK (encerrada_em IS NULL OR estado IN ('encerrada', 'nao_realizada', 'arquivada')),
  CONSTRAINT sessao_encerrada_em_obrigatoria
    CHECK (estado NOT IN ('encerrada', 'nao_realizada') OR encerrada_em IS NOT NULL),
  CONSTRAINT sessao_marcos_ordem
    CHECK (aberta_em IS NULL OR encerrada_em IS NULL OR encerrada_em >= aberta_em),
  CONSTRAINT sessao_staging_valido CHECK (lote_id IS NOT NULL OR efetivado_em IS NOT NULL)
);
--;;
CREATE INDEX IF NOT EXISTS idx_sessao_legislativa ON sessoes.sessao (ente_id, sessao_legislativa_id);
--;;
CREATE INDEX IF NOT EXISTS idx_sessao_estado ON sessoes.sessao (ente_id, estado);
--;;
-- hot-path do M4 ("a sessao acontece"): proximas sessoes / pauta do dia filtram por agendada_para. Parcial
-- nos estados ativos (encerradas/arquivadas saem do scan; estado tem baixa seletividade). Review F4.1 DB-MAJOR2.
CREATE INDEX IF NOT EXISTS idx_sessao_agendada_para
  ON sessoes.sessao (ente_id, agendada_para) WHERE estado IN ('agendada', 'aberta');
--;;
CREATE INDEX IF NOT EXISTS idx_sessao_staging ON sessoes.sessao (ente_id, lote_id) WHERE efetivado_em IS NULL;
--;;
ALTER TABLE sessoes.sessao ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE sessoes.sessao FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON sessoes.sessao;
--;;
CREATE POLICY tenant_isolation ON sessoes.sessao
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid
         AND (efetivado_em IS NOT NULL OR lote_id = NULLIF(current_setting('app.ver_lote', true), '')::uuid))
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
GRANT SELECT, INSERT, UPDATE ON sessoes.sessao TO oplenario_app;
--;;
-- defesa-em-profundidade: 'arquivada' e' o terminal final — uma vez arquivada, a linha congela (alem dos
-- guards de transicao no servico). encerrada/nao_realizada ainda transicionam p/ arquivada (nao sao args).
CREATE TRIGGER trg_sessao_imut_arquivada
  BEFORE UPDATE ON sessoes.sessao
  FOR EACH ROW EXECUTE FUNCTION shared.imut_trava_estado_terminal('arquivada');
