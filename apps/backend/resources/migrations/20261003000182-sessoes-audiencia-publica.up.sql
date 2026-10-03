-- ADR-0021 Parte A — a AUDIENCIA PUBLICA como tipo de sessao (§22.6 eixo A: o tipo e' nome, o comportamento e'
-- capability). Tres mudancas:
--
-- (1) `sessoes.sessao`: o tipo `audiencia_publica` e DUAS capabilities novas. `exige_quorum` sai de `delibera` no
--     backfill (toda sessao que delibera exigia quorum; a solene e a especial nao); `aceita_inscricao_cidadao` nasce
--     false (so' a audiencia aceita). O CHECK de `tipo_sessao` da mig 0026 nao tem nome explicito: e' achado no
--     catalogo pela definicao e recriado com nome. O backfill roda como DONO, sem a RLS forcada (a coluna e' de toda
--     Casa) e sob correcao auditada (a sessao `arquivada` e' imutavel pelo trigger da mig 0026 — a coluna nova e' dado
--     derivado do proprio `delibera`, nao muda o registro).
-- (2) `sessoes.audiencia` (1:1 com a sessao): a comissao que promove (ref a cadastros por uuid, sem FK — §22.10), o
--     tema, o local, a proposicao em debate (ref a legislativo, sem FK), a FINALIDADE (`metas_fiscais` exige a
--     referencia do quadrimestre `AAAA-Q1|Q2|Q3` — LRF art. 9 §4; as outras nao levam referencia), o tempo unico de
--     fala de cada cidadao e se as inscricoes estao abertas.
-- (3) `sessoes.inscricao_cidadao`: quem pediu para falar. O protocolo `AUD-AAAA-NNNNNN` sai do contador gapless da
--     Casa (`shared.sequencial`, escopo `inscricao_audiencia:<ano>`, mesmo desenho dos protocolos da participacao). A
--     ORDEM e' a de inscricao (max+1 por sessao, serializada pela trava da linha da audiencia). Pelo portal
--     (`portal_govbr`) a identidade e' obrigatoria e o nome vem dela; a Mesa inscreve no dia quem esta' presente
--     (`presencial_secretaria`, sem identidade). Um cidadao por audiencia enquanto nao desistiu; no maximo UMA fala
--     em curso por sessao. Estados terminais (falou, ausente, desistiu) congelam a linha (trigger da mig 0012).
DO $$
DECLARE c record;
BEGIN
  FOR c IN SELECT conname FROM pg_constraint
            WHERE conrelid = 'sessoes.sessao'::regclass AND contype = 'c'
              AND pg_get_constraintdef(oid) LIKE '%tipo_sessao%'
  LOOP
    EXECUTE format('ALTER TABLE sessoes.sessao DROP CONSTRAINT %I', c.conname);
  END LOOP;
END $$;
--;;
ALTER TABLE sessoes.sessao ADD CONSTRAINT sessao_tipo_sessao_check CHECK (tipo_sessao IN
  ('ordinaria', 'extraordinaria', 'solene', 'secreta', 'especial', 'audiencia_publica'));
--;;
ALTER TABLE sessoes.sessao ADD COLUMN IF NOT EXISTS exige_quorum boolean;
--;;
ALTER TABLE sessoes.sessao ADD COLUMN IF NOT EXISTS aceita_inscricao_cidadao boolean NOT NULL DEFAULT false;
--;;
ALTER TABLE sessoes.sessao NO FORCE ROW LEVEL SECURITY;
--;;
DO $$
BEGIN
  PERFORM set_config('app.correcao_auditada', 'ADR-0021 mig 182: exige_quorum = delibera (backfill)', true);
  UPDATE sessoes.sessao SET exige_quorum = delibera WHERE exige_quorum IS NULL;
END $$;
--;;
ALTER TABLE sessoes.sessao FORCE ROW LEVEL SECURITY;
--;;
ALTER TABLE sessoes.sessao ALTER COLUMN exige_quorum SET NOT NULL;
--;;
-- a audiencia nao delibera nem exige quorum; so' ela aceita inscricao de cidadao (defesa: o tipo e a capability nao
-- se contradizem nem por escrita fora da aplicacao)
ALTER TABLE sessoes.sessao ADD CONSTRAINT sessao_audiencia_nao_delibera
  CHECK (tipo_sessao <> 'audiencia_publica' OR (NOT delibera AND NOT exige_quorum));
--;;
CREATE TABLE IF NOT EXISTS sessoes.audiencia (
  ente_id             uuid        NOT NULL,
  sessao_id           uuid        NOT NULL,
  comissao_id         uuid        NOT NULL,                 -- forward-ref a cadastros.comissao (sem FK, §22.10)
  tema                text        NOT NULL CHECK (btrim(tema) <> '' AND char_length(tema) <= 200),
  local               text        CHECK (local IS NULL OR (btrim(local) <> '' AND char_length(local) <= 200)),
  proposicao_id       uuid,                                 -- forward-ref a legislativo.proposicoes (sem FK)
  finalidade          text        NOT NULL CHECK (finalidade IN ('tematica', 'metas_fiscais', 'ldo', 'loa', 'ppa')),
  referencia          text        CHECK (referencia IS NULL OR referencia ~ '^[0-9]{4}-Q[1-3]$'),
  tempo_fala_segundos integer     NOT NULL DEFAULT 300 CHECK (tempo_fala_segundos BETWEEN 60 AND 1800),
  inscricoes_abertas  boolean     NOT NULL DEFAULT true,
  created_by          uuid,
  updated_by          uuid,
  criado_em           timestamptz NOT NULL DEFAULT now(),
  atualizado_em       timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (ente_id, sessao_id),
  FOREIGN KEY (ente_id, sessao_id) REFERENCES sessoes.sessao (ente_id, id),
  -- a referencia do quadrimestre e' da audiencia de metas fiscais, e so' dela
  CONSTRAINT audiencia_referencia_so_em_metas_fiscais CHECK ((finalidade = 'metas_fiscais') = (referencia IS NOT NULL))
);
--;;
-- a prova do quadrimestre (motor, fatia 3) procura a audiencia de metas fiscais pela referencia
CREATE INDEX IF NOT EXISTS idx_audiencia_finalidade ON sessoes.audiencia (ente_id, finalidade, referencia);
--;;
ALTER TABLE sessoes.audiencia ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE sessoes.audiencia FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON sessoes.audiencia;
--;;
CREATE POLICY tenant_isolation ON sessoes.audiencia
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid)
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
GRANT SELECT, INSERT, UPDATE (tempo_fala_segundos, inscricoes_abertas, local, updated_by, atualizado_em)
  ON sessoes.audiencia TO oplenario_app;
--;;
CREATE TABLE IF NOT EXISTS sessoes.inscricao_cidadao (
  ente_id              uuid        NOT NULL,
  id                   uuid        NOT NULL DEFAULT gen_random_uuid(),
  sessao_id            uuid        NOT NULL,
  ano                  integer     NOT NULL CHECK (ano BETWEEN 2000 AND 9999),
  numero               integer     NOT NULL CHECK (numero > 0),
  protocolo            text        NOT NULL CHECK (protocolo ~ '^AUD-[0-9]{4}-[0-9]{6,}$'),
  origem               text        NOT NULL CHECK (origem IN ('portal_govbr', 'presencial_secretaria')),
  identidade_id        uuid,                                -- GUARD ref (identidade), sem FK cross-schema
  nome                 text        NOT NULL CHECK (btrim(nome) <> '' AND char_length(nome) <= 200),
  fala_como            text        NOT NULL CHECK (fala_como IN ('individual', 'entidade', 'conselho_movimento')),
  entidade             text        CHECK (entidade IS NULL OR (btrim(entidade) <> '' AND char_length(entidade) <= 200)),
  tema                 text        NOT NULL CHECK (btrim(tema) <> '' AND char_length(tema) <= 200),
  ordem                integer     NOT NULL CHECK (ordem > 0),
  estado               text        NOT NULL DEFAULT 'inscrita'
                                   CHECK (estado IN ('inscrita', 'falando', 'falou', 'ausente', 'desistiu')),
  chamada_em           timestamptz,
  encerrada_em         timestamptz,
  tempo_usado_segundos integer     CHECK (tempo_usado_segundos IS NULL OR tempo_usado_segundos BETWEEN 0 AND 86400),
  registrada_por       uuid,                                -- quem inscreveu na Mesa (presencial); nulo pelo portal
  criado_em            timestamptz NOT NULL DEFAULT now(),
  atualizado_em        timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (ente_id, id),
  UNIQUE (ente_id, protocolo),
  UNIQUE (ente_id, ano, numero),
  UNIQUE (ente_id, sessao_id, ordem),
  FOREIGN KEY (ente_id, sessao_id) REFERENCES sessoes.audiencia (ente_id, sessao_id),
  -- pelo portal, a identidade (o nome veio dela); na Mesa, sem identidade
  CONSTRAINT inscricao_identidade_coerente CHECK ((origem = 'portal_govbr') = (identidade_id IS NOT NULL)),
  -- quem fala por entidade, conselho ou movimento diz qual; o individual nao
  CONSTRAINT inscricao_entidade_coerente CHECK ((fala_como = 'individual') = (entidade IS NULL)),
  -- os marcos da fala acompanham o estado
  CONSTRAINT inscricao_chamada_coerente CHECK ((estado IN ('falando', 'falou')) = (chamada_em IS NOT NULL)),
  CONSTRAINT inscricao_encerramento_coerente
    CHECK ((estado = 'falou') = (encerrada_em IS NOT NULL AND tempo_usado_segundos IS NOT NULL))
);
--;;
-- um cidadao por audiencia enquanto nao desistiu (desistiu, pode se inscrever de novo: ganha o fim da fila)
CREATE UNIQUE INDEX IF NOT EXISTS uq_inscricao_cidadao_uma_por_audiencia
  ON sessoes.inscricao_cidadao (ente_id, sessao_id, identidade_id)
  WHERE identidade_id IS NOT NULL AND estado <> 'desistiu';
--;;
-- no maximo uma fala de cidadao em curso por sessao
CREATE UNIQUE INDEX IF NOT EXISTS uq_inscricao_cidadao_uma_falando
  ON sessoes.inscricao_cidadao (ente_id, sessao_id) WHERE estado = 'falando';
--;;
-- "minhas inscricoes" (a area da cidada)
CREATE INDEX IF NOT EXISTS idx_inscricao_cidadao_identidade
  ON sessoes.inscricao_cidadao (ente_id, identidade_id) WHERE identidade_id IS NOT NULL;
--;;
ALTER TABLE sessoes.inscricao_cidadao ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE sessoes.inscricao_cidadao FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON sessoes.inscricao_cidadao;
--;;
CREATE POLICY tenant_isolation ON sessoes.inscricao_cidadao
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid)
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
GRANT SELECT, INSERT, UPDATE (estado, chamada_em, encerrada_em, tempo_usado_segundos, atualizado_em)
  ON sessoes.inscricao_cidadao TO oplenario_app;
--;;
CREATE TRIGGER trg_inscricao_cidadao_imut_terminal
  BEFORE UPDATE ON sessoes.inscricao_cidadao
  FOR EACH ROW EXECUTE FUNCTION shared.imut_trava_estado_terminal('falou', 'ausente', 'desistiu');
