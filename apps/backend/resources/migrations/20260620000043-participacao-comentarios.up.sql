-- FAST-FOLLOW (03/07/2026) Slice 6: modulo PARTICIPACAO — COMENTARIOS/MODERACAO (feature 6.3).
--
-- Comentario de cidadao numa materia (proposicao). Diferente do e-SIC/LGPD/ouvidoria: NAO tem
-- protocolo/relogio LAI/LGPD/13.460 — nao materializa prazo_ativo, nao usa kernel/sequencial. Autor
-- SEMPRE obrigatorio (SEM variante anonima — diferente da ouvidoria: 6.3 nao e' canal de denuncia sigilosa).
-- `proposicao_id` e' forward-ref ao schema `legislativo` SEM FK (§22.10 proibe FK cross-schema); a
-- existencia NAO e' validada nesta fatia (fora do caminho critico, decisao registrada no plano).
--
-- Tres tabelas: `comentario` (CAS: pendente -> aprovado|rejeitado), `moderacao_comentario` (APPEND-ONLY:
-- a trilha de CADA decisao de moderacao), `denuncia_comentario` (APPEND-ONLY: UNIQUE por (comentario,
-- denunciante) = idempotencia — um cidadao so' denuncia 1x o mesmo comentario).
--
-- Convencao tenant-table MODERNA (migs 0026+). NAO e' a 1a migration do modulo (a 0039 ja fez GRANT USAGE)
-- -> sem GRANT/REVOKE USAGE do schema. FK COMPOSTA same-tenant same-SCHEMA (§22.10 so proibe FK cross-SCHEMA).

-- ---------- (1) comentario: state-machine CAS (pendente -> aprovado|rejeitado) ----------
CREATE TABLE IF NOT EXISTS participacao.comentario (
  ente_id     uuid NOT NULL,
  id          uuid NOT NULL DEFAULT gen_random_uuid(),
  -- forward-ref cross-schema SEM FK (§22.10) — a existencia da proposicao NAO e' validada nesta fatia.
  proposicao_id uuid NOT NULL,
  -- autor SEMPRE obrigatorio (SEM variante anonima, diferente da ouvidoria — feature 6.3 nao e' canal
  -- sigiloso). INJETADO do ator na borda, NUNCA do corpo (anti-forge).
  autor_identidade_id uuid NOT NULL,
  corpo       text    NOT NULL CHECK (length(trim(corpo)) > 0 AND length(corpo) <= 2000),
  estado      text    NOT NULL DEFAULT 'pendente' CHECK (estado IN ('pendente', 'aprovado', 'rejeitado')),
  -- motivo_rejeicao: OBRIGATORIO quando rejeitado (invariante ESTRUTURAL no SQL); QUANDO presente, deve
  -- ser um dos 5 motivos FIXOS (vocabulario de aplicacao — participacao/logic.clj e' a fonte unica; este
  -- CHECK e' defesa-em-profundidade, espelha o padrao de tipo/estado das demais tabelas do modulo).
  motivo_rejeicao text,
  CONSTRAINT comentario_motivo_rejeicao_obrigatorio CHECK (estado != 'rejeitado' OR motivo_rejeicao IS NOT NULL),
  CONSTRAINT comentario_motivo_rejeicao_valido CHECK (motivo_rejeicao IS NULL OR motivo_rejeicao IN
    ('ofensivo', 'spam', 'fora-do-tema', 'conteudo-ilegal', 'dados-pessoais')),
  -- denunciado: flag de indexacao da fila de moderacao (denuncia_comentario, abaixo, e' o REGISTRO de cada
  -- ato). So' e' setada via CAS quando o comentario AINDA esta 'pendente' (db/comentario/marcar-denunciado!) —
  -- denunciar um comentario JA terminal (aprovado/rejeitado) registra o ato em denuncia_comentario mas NAO
  -- tenta mudar esta flag (a fila-moderacao so' lista pendentes; a flag e' irrelevante pos-terminal, e uma
  -- tentativa de UPDATE numa linha terminal seria barrada pelo trigger de estado terminal abaixo).
  denunciado  boolean NOT NULL DEFAULT false,
  -- transversais (§22.4.3 disc.1)
  origem      text NOT NULL DEFAULT 'nativa',
  origem_ref  text,
  origem_importado_em timestamptz,
  lote_id     uuid,
  efetivado_em timestamptz,
  created_by  uuid,
  criado_em     timestamptz NOT NULL DEFAULT now(),
  atualizado_em timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (ente_id, id),
  CONSTRAINT comentario_staging_valido CHECK (lote_id IS NOT NULL OR efetivado_em IS NOT NULL)
);
--;;
-- serve `comentarios-da-materia` (PUBLICA, so' aprovado, cronologico — o thread de uma materia).
CREATE INDEX IF NOT EXISTS idx_comentario_materia_publica
  ON participacao.comentario (ente_id, proposicao_id, criado_em ASC)
  WHERE estado = 'aprovado';
--;;
-- serve `fila-moderacao` (SERVIDOR): denunciados primeiro, depois cronologico — so' pendente e' candidato.
CREATE INDEX IF NOT EXISTS idx_comentario_fila_moderacao
  ON participacao.comentario (ente_id, denunciado DESC, criado_em ASC)
  WHERE estado = 'pendente';
--;;
CREATE INDEX IF NOT EXISTS idx_comentario_staging
  ON participacao.comentario (ente_id, lote_id) WHERE efetivado_em IS NULL;
--;;
ALTER TABLE participacao.comentario ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE participacao.comentario FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON participacao.comentario;
--;;
CREATE POLICY tenant_isolation ON participacao.comentario
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid
         AND (efetivado_em IS NOT NULL OR lote_id = NULLIF(current_setting('app.ver_lote', true), '')::uuid))
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
GRANT SELECT, INSERT, UPDATE ON participacao.comentario TO oplenario_app;
--;;
-- state-machine: aprovado/rejeitado sao terminais — a linha congela (defesa-em-profundidade alem do CAS).
-- NOTA: este trigger e' BEFORE UPDATE (nao "OF estado") — bloqueia QUALQUER UPDATE numa linha JA terminal,
-- nao so' mudanca de estado. E' por isso que `marcar-denunciado!` so' executa o UPDATE sob WHERE
-- estado='pendente': uma linha que a WHERE nao casa NUNCA dispara o trigger (Postgres so' avalia o trigger
-- de linha para linhas efetivamente candidatas ao UPDATE), entao denunciar um comentario terminal nunca
-- colide com esta trava.
CREATE TRIGGER trg_comentario_trava_terminal
  BEFORE UPDATE ON participacao.comentario
  FOR EACH ROW EXECUTE FUNCTION shared.imut_trava_estado_terminal('aprovado', 'rejeitado');
--;;
-- ---------- (2) moderacao_comentario: APPEND-ONLY (Inv.10). A trilha de CADA decisao de moderacao. ----------
CREATE TABLE IF NOT EXISTS participacao.moderacao_comentario (
  ente_id        uuid NOT NULL,
  id             uuid NOT NULL DEFAULT gen_random_uuid(),
  comentario_id  uuid NOT NULL,                         -- o comentario moderado (FK composta same-tenant abaixo)
  acao           text NOT NULL CHECK (acao IN ('aprovado', 'rejeitado')),
  motivo_rejeicao text,
  CONSTRAINT moderacao_comentario_motivo_obrigatorio CHECK (acao != 'rejeitado' OR motivo_rejeicao IS NOT NULL),
  CONSTRAINT moderacao_comentario_motivo_valido CHECK (motivo_rejeicao IS NULL OR motivo_rejeicao IN
    ('ofensivo', 'spam', 'fora-do-tema', 'conteudo-ilegal', 'dados-pessoais')),
  moderado_por   uuid NOT NULL,                         -- o SERVIDOR que moderou (injetado do ator, nunca do corpo)
  moderado_em    timestamptz NOT NULL,                  -- instante da decisao
  -- transversais de append-only PURO: sem updated_*/created_by (nunca muta; moderado_por = autor)
  origem         text NOT NULL DEFAULT 'nativa',
  origem_ref     text,
  origem_importado_em timestamptz,
  lote_id        uuid,
  efetivado_em   timestamptz,
  criado_em      timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (ente_id, id),
  FOREIGN KEY (ente_id, comentario_id) REFERENCES participacao.comentario (ente_id, id),
  CONSTRAINT moderacao_comentario_staging_valido CHECK (lote_id IS NOT NULL OR efetivado_em IS NOT NULL)
);
--;;
-- FK sempre indexada (convencao do projeto; cobre "a trilha de moderacao de um comentario").
CREATE INDEX IF NOT EXISTS idx_moderacao_comentario_comentario
  ON participacao.moderacao_comentario (ente_id, comentario_id, moderado_em DESC);
--;;
CREATE INDEX IF NOT EXISTS idx_moderacao_comentario_staging
  ON participacao.moderacao_comentario (ente_id, lote_id) WHERE efetivado_em IS NULL;
--;;
ALTER TABLE participacao.moderacao_comentario ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE participacao.moderacao_comentario FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON participacao.moderacao_comentario;
--;;
CREATE POLICY tenant_isolation ON participacao.moderacao_comentario
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid
         AND (efetivado_em IS NOT NULL OR lote_id = NULLIF(current_setting('app.ver_lote', true), '')::uuid))
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
-- append-only PURO (nivel a): SELECT/INSERT; sem UPDATE/DELETE (Inv.10 — decisao registrada nunca muda).
GRANT SELECT, INSERT ON participacao.moderacao_comentario TO oplenario_app;
--;;
CREATE TRIGGER trg_moderacao_comentario_append_only
  BEFORE UPDATE OR DELETE ON participacao.moderacao_comentario
  FOR EACH ROW EXECUTE FUNCTION shared.imut_append_only();
--;;
-- ---------- (3) denuncia_comentario: APPEND-ONLY (Inv.10). UNIQUE = IDEMPOTENCIA (1 denuncia/cidadao/comentario). ----------
CREATE TABLE IF NOT EXISTS participacao.denuncia_comentario (
  ente_id        uuid NOT NULL,
  id             uuid NOT NULL DEFAULT gen_random_uuid(),
  comentario_id  uuid NOT NULL,                         -- o comentario denunciado (FK composta same-tenant abaixo)
  -- denunciante SEMPRE obrigatorio (a rota exige auth — denunciar nao e' sem-ator; so' o comentario original
  -- de ouvidoria e' que pode ser anonimo, nao a denuncia de um comentario).
  denunciante_identidade_id uuid NOT NULL,
  motivo         text CHECK (motivo IS NULL OR (length(trim(motivo)) > 0 AND length(motivo) <= 2000)),
  denunciado_em  timestamptz NOT NULL,                  -- instante do ato de denunciar
  -- transversais de append-only PURO: sem updated_*/created_by (nunca muta; denunciante = autor)
  origem         text NOT NULL DEFAULT 'nativa',
  origem_ref     text,
  origem_importado_em timestamptz,
  lote_id        uuid,
  efetivado_em   timestamptz,
  criado_em      timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (ente_id, id),
  -- IDEMPOTENCIA: 1 denuncia por (comentario, denunciante) — a UNIQUE e' o que permite ao Repo tratar a 2a
  -- tentativa como sucesso idempotente (ON CONFLICT DO NOTHING), nao como erro/409 (denunciar de novo nao e'
  -- conflito de negocio). Prefixada por ente_id (convencao do modulo: TODA UNIQUE e' tenant-scoped, mesmo
  -- padrao de UNIQUE(ente_id, ano, sequencial)/UNIQUE(ente_id, protocolo) das demais tabelas).
  UNIQUE (ente_id, comentario_id, denunciante_identidade_id),
  FOREIGN KEY (ente_id, comentario_id) REFERENCES participacao.comentario (ente_id, id),
  CONSTRAINT denuncia_comentario_staging_valido CHECK (lote_id IS NOT NULL OR efetivado_em IS NOT NULL)
);
--;;
-- historico de denuncias de UM comentario (mais recente primeiro) — a UNIQUE acima ja serve o lookup de
-- idempotencia (ente_id, comentario_id, denunciante_identidade_id); este indice cobre a leitura por comentario.
CREATE INDEX IF NOT EXISTS idx_denuncia_comentario_comentario
  ON participacao.denuncia_comentario (ente_id, comentario_id, denunciado_em DESC);
--;;
CREATE INDEX IF NOT EXISTS idx_denuncia_comentario_staging
  ON participacao.denuncia_comentario (ente_id, lote_id) WHERE efetivado_em IS NULL;
--;;
ALTER TABLE participacao.denuncia_comentario ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE participacao.denuncia_comentario FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON participacao.denuncia_comentario;
--;;
CREATE POLICY tenant_isolation ON participacao.denuncia_comentario
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid
         AND (efetivado_em IS NOT NULL OR lote_id = NULLIF(current_setting('app.ver_lote', true), '')::uuid))
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
-- append-only PURO (nivel a): SELECT/INSERT; sem UPDATE/DELETE (Inv.10 — denuncia registrada nunca muda).
GRANT SELECT, INSERT ON participacao.denuncia_comentario TO oplenario_app;
--;;
CREATE TRIGGER trg_denuncia_comentario_append_only
  BEFORE UPDATE OR DELETE ON participacao.denuncia_comentario
  FOR EACH ROW EXECUTE FUNCTION shared.imut_append_only();
