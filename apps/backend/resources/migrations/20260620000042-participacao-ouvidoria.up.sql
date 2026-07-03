-- FAST-FOLLOW (03/07/2026): modulo PARTICIPACAO — GENERALIZACAO de prazo_ativo (prorrogacao) + OUVIDORIA
-- 13.460/2017 (feature 6.3, art. 10: 30 dias, prorrogavel por igual periodo mediante justificativa).
--
-- GENERALIZACAO (compartilhada por pedido_esic/recurso_esic/solicitacao_titular/manifestacao_ouvidoria):
-- a ouvidoria precisa de prorrogacao 30+30 — em vez de codar um mecanismo so-ouvidoria, generaliza-se
-- prazo_ativo (disciplina 5, motor declarativo compartilhado). (1) o CHECK de objeto_tipo ganha a 4a
-- especie; (2) a nova tabela participacao.prorrogacao (append-only) registra CADA prorrogacao (auditoria);
-- a CAS que so permite 1x mora em db/prazo_ativo/prorrogar!. `prorrogado_ate` ja existia na mig 0039 (nunca
-- escrito) — esta migration e' quem passa a escreve-lo. BACKWARD-SAFE: prorrogado_ate e' sempre NULL nos
-- objetos existentes (e-SIC/LGPD nao prorrogam em V1); COALESCE(prorrogado_ate, vence_em) degenera p/
-- vence_em cru, sem mudar comportamento observavel do sweep/leituras ja em producao.
--
-- Convencao tenant-table MODERNA (migs 0026+). NAO e' a 1a migration do modulo (a 0039 ja fez GRANT
-- USAGE) -> sem GRANT/REVOKE USAGE do schema. FK COMPOSTA same-tenant same-SCHEMA (§22.10 so proibe
-- FK cross-SCHEMA).

-- ---------- (1) objeto_tipo de prazo_ativo ganha a 4a especie: manifestacao_ouvidoria ----------
ALTER TABLE participacao.prazo_ativo DROP CONSTRAINT prazo_ativo_objeto_tipo_check;
--;;
ALTER TABLE participacao.prazo_ativo ADD CONSTRAINT prazo_ativo_objeto_tipo_check CHECK (objeto_tipo IN
  ('pedido_esic', 'recurso_esic', 'solicitacao_titular', 'manifestacao_ouvidoria'));
--;;
-- ---------- (1b) coerencia de prorrogado_ate (defesa-em-profundidade — review db/architect): a auditoria
--            (prorrogacao_coerente, abaixo) ja exige para_data > de_data, mas nada travava prazo_ativo em
--            si; um futuro caller de db/prazo_ativo/prorrogar! passando prorrogado_ate <= vence_em faria o
--            vencimento EFETIVO (COALESCE) RETROCEDER silenciosamente. Fecha a invariante na fonte. ----------
ALTER TABLE participacao.prazo_ativo ADD CONSTRAINT prazo_ativo_prorrogado_coerente
  CHECK (prorrogado_ate IS NULL OR prorrogado_ate > vence_em);
--;;
-- ---------- (1c) sweep index: a generalizacao do sweep p/ COALESCE(prorrogado_ate, vence_em) (review db)
--            invalida o idx_prazo_ativo_sweep (mig 0039) como indice util — um btree sobre a coluna CRUA
--            vence_em nao serve nem o filtro nem o ORDER BY de uma expressao. Substitui por um indice de
--            EXPRESSAO sobre o vencimento EFETIVO (mesmo predicado parcial da mig 0039). ----------
DROP INDEX IF EXISTS participacao.idx_prazo_ativo_sweep;
--;;
CREATE INDEX IF NOT EXISTS idx_prazo_ativo_sweep_efetivo
  ON participacao.prazo_ativo (ente_id, (COALESCE(prorrogado_ate, vence_em)))
  WHERE estado IN ('pendente', 'vencida');
--;;
-- ---------- (2) prorrogacao: APPEND-ONLY (Inv.10). O ato de prorrogar um prazo (auditoria; a CAS de
--            db/prazo_ativo/prorrogar! e' quem impede >1 prorrogacao por objeto — esta tabela e' o
--            REGISTRO do ato, polimorfica, servindo qualquer prazo futuro, nao so ouvidoria). ----------
CREATE TABLE IF NOT EXISTS participacao.prorrogacao (
  ente_id        uuid NOT NULL,
  id             uuid NOT NULL DEFAULT gen_random_uuid(),
  objeto_tipo    text NOT NULL CHECK (objeto_tipo IN
    ('pedido_esic', 'recurso_esic', 'solicitacao_titular', 'manifestacao_ouvidoria')),  -- espelha prazo_ativo
  objeto_id      uuid NOT NULL,                         -- forward-ref ao agregado sob prazo (mesmo schema)
  de_data        date NOT NULL,                         -- vence_em ANTES da prorrogacao
  para_data      date NOT NULL,                         -- vence_em DEPOIS (o novo prorrogado_ate)
  justificativa  text NOT NULL CHECK (length(trim(justificativa)) > 0 AND length(justificativa) <= 5000),
  prorrogado_por uuid NOT NULL,                         -- o SERVIDOR que prorrogou (injetado do ator)
  prorrogado_em  timestamptz NOT NULL,                  -- instante do ato de prorrogar
  -- transversais de append-only PURO: sem updated_*/created_by (nunca muta; prorrogado_por = autor)
  origem         text NOT NULL DEFAULT 'nativa',
  origem_ref     text,
  origem_importado_em timestamptz,
  lote_id        uuid,
  efetivado_em   timestamptz,
  criado_em      timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (ente_id, id),
  CONSTRAINT prorrogacao_coerente CHECK (para_data > de_data),  -- prorrogar so' pode EMPURRAR o prazo p/ frente
  CONSTRAINT prorrogacao_staging_valido CHECK (lote_id IS NOT NULL OR efetivado_em IS NOT NULL)
);
--;;
-- "o anel" tambem serve historico de prorrogacoes de um objeto (cross-ano, mais recente primeiro).
CREATE INDEX IF NOT EXISTS idx_prorrogacao_objeto
  ON participacao.prorrogacao (ente_id, objeto_tipo, objeto_id, criado_em DESC);
--;;
CREATE INDEX IF NOT EXISTS idx_prorrogacao_staging
  ON participacao.prorrogacao (ente_id, lote_id) WHERE efetivado_em IS NULL;
--;;
ALTER TABLE participacao.prorrogacao ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE participacao.prorrogacao FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON participacao.prorrogacao;
--;;
CREATE POLICY tenant_isolation ON participacao.prorrogacao
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid
         AND (efetivado_em IS NOT NULL OR lote_id = NULLIF(current_setting('app.ver_lote', true), '')::uuid))
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
-- append-only PURO (nivel a): SELECT/INSERT; sem UPDATE/DELETE (Inv.10 — prorrogacao registrada nunca muda).
GRANT SELECT, INSERT ON participacao.prorrogacao TO oplenario_app;
--;;
CREATE TRIGGER trg_prorrogacao_append_only
  BEFORE UPDATE OR DELETE ON participacao.prorrogacao
  FOR EACH ROW EXECUTE FUNCTION shared.imut_append_only();
--;;
-- ---------- (3) manifestacao_ouvidoria: state-machine CAS (protocolada -> em_analise -> respondida|arquivada) ----------
-- Os 5 TIPOS padrao CGU/Lei 13.460: reclamacao/denuncia/sugestao/elogio/solicitacao. ANONIMA nao e' sem-auth
-- (§22.5: escrita SEMPRE exige ator) — quando anonima=true, manifestante_identidade_id fica NULL na linha
-- (a CHECK abaixo garante que so' fica NULL quando anonima=true; o ator segue existindo p/ anti-abuso, mas
-- NUNCA chega ao banco). descricao = mesmos tetos 500/20000 do e-SIC (defesa-em-profundidade, nao restricao
-- semantica).
CREATE TABLE IF NOT EXISTS participacao.manifestacao_ouvidoria (
  ente_id     uuid NOT NULL,
  id          uuid NOT NULL DEFAULT gen_random_uuid(),
  ano         integer NOT NULL,                          -- ano do protocolo (numeracao reinicia por ano)
  sequencial  bigint  NOT NULL,                          -- gapless por (ente, ano) — escopo 'manifestacao_ouvidoria:<ano>'
  protocolo   text    NOT NULL,                          -- numero humano derivado de (ano, sequencial): 'OUV-<ano>-NNNNNN'
  tipo        text    NOT NULL CHECK (tipo IN
    ('reclamacao', 'denuncia', 'sugestao', 'elogio', 'solicitacao')),
  assunto     text    NOT NULL CHECK (length(trim(assunto)) > 0 AND length(assunto) <= 500),
  descricao   text    NOT NULL CHECK (length(trim(descricao)) > 0 AND length(descricao) <= 20000),
  anonima     boolean NOT NULL DEFAULT false,
  -- manifestante = forward-ref a identidade (uuid, SEM FK cross-schema, §22.10). NULL SOMENTE quando anonima
  -- (decisao de arquitetura: anonima NAO e' sem-auth — a escrita exige ator; so' o CAMPO nao persiste).
  manifestante_identidade_id uuid,
  CONSTRAINT manifestacao_anonima_coerente CHECK (
    (anonima AND manifestante_identidade_id IS NULL)
    OR (NOT anonima AND manifestante_identidade_id IS NOT NULL)),
  estado      text    NOT NULL DEFAULT 'protocolada' CHECK (estado IN
    ('protocolada', 'em_analise', 'respondida', 'arquivada')),
  recibo_em   timestamptz NOT NULL,                      -- MARCO DE INICIO DO RELOGIO (Lei 13.460 art. 10)
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
  UNIQUE (ente_id, ano, sequencial),                    -- gapless por ano (kernel/sequencial)
  UNIQUE (ente_id, protocolo),                          -- protocolo = chave publica de acompanhamento
  CONSTRAINT manifestacao_ouvidoria_staging_valido CHECK (lote_id IS NOT NULL OR efetivado_em IS NOT NULL)
);
--;;
-- 'minhas manifestacoes' (read-model por manifestante autenticado NAO-anonimo, cross-ano). Escopo de
-- tenant no prefixo. Manifestacoes anonimas (manifestante NULL) nunca aparecem aqui (nao ha dono).
CREATE INDEX IF NOT EXISTS idx_manifestacao_ouvidoria_manifestante
  ON participacao.manifestacao_ouvidoria (ente_id, manifestante_identidade_id, criado_em DESC)
  WHERE manifestante_identidade_id IS NOT NULL;
--;;
CREATE INDEX IF NOT EXISTS idx_manifestacao_ouvidoria_staging
  ON participacao.manifestacao_ouvidoria (ente_id, lote_id) WHERE efetivado_em IS NULL;
--;;
ALTER TABLE participacao.manifestacao_ouvidoria ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE participacao.manifestacao_ouvidoria FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON participacao.manifestacao_ouvidoria;
--;;
CREATE POLICY tenant_isolation ON participacao.manifestacao_ouvidoria
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid
         AND (efetivado_em IS NOT NULL OR lote_id = NULLIF(current_setting('app.ver_lote', true), '')::uuid))
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
GRANT SELECT, INSERT, UPDATE ON participacao.manifestacao_ouvidoria TO oplenario_app;
--;;
-- state-machine: respondida/arquivada sao terminais — a linha congela (defesa-em-profundidade alem do CAS).
CREATE TRIGGER trg_manifestacao_ouvidoria_trava_terminal
  BEFORE UPDATE ON participacao.manifestacao_ouvidoria
  FOR EACH ROW EXECUTE FUNCTION shared.imut_trava_estado_terminal('respondida', 'arquivada');
--;;
-- ---------- (4) resposta_ouvidoria: APPEND-ONLY (Inv.10). O ato de responder uma manifestacao. ----------
CREATE TABLE IF NOT EXISTS participacao.resposta_ouvidoria (
  ente_id        uuid NOT NULL,
  id             uuid NOT NULL DEFAULT gen_random_uuid(),
  manifestacao_id uuid NOT NULL,                        -- a manifestacao respondida (FK composta same-tenant abaixo)
  corpo          text NOT NULL CHECK (length(trim(corpo)) > 0 AND length(corpo) <= 50000),
  respondido_por uuid NOT NULL,                         -- o SERVIDOR que respondeu (injetado do ator, nunca do corpo)
  respondida_em  timestamptz NOT NULL,                  -- instante da resposta
  -- transversais de append-only PURO: sem updated_*/created_by (nunca muta; respondido_por = autor)
  origem         text NOT NULL DEFAULT 'nativa',
  origem_ref     text,
  origem_importado_em timestamptz,
  lote_id        uuid,
  efetivado_em   timestamptz,
  criado_em      timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (ente_id, id),
  -- FK COMPOSTA same-tenant (ente_id ancora o par): a resposta cita uma manifestacao do MESMO ente (anti
  -- confused-deputy no nivel do banco). Same-schema -> FK permitida (§22.10 so proibe cross-SCHEMA).
  FOREIGN KEY (ente_id, manifestacao_id) REFERENCES participacao.manifestacao_ouvidoria (ente_id, id),
  CONSTRAINT resposta_ouvidoria_staging_valido CHECK (lote_id IS NOT NULL OR efetivado_em IS NOT NULL)
);
--;;
-- FK sempre indexada (convencao do projeto; cobre "respostas de uma manifestacao").
CREATE INDEX IF NOT EXISTS idx_resposta_ouvidoria_manifestacao
  ON participacao.resposta_ouvidoria (ente_id, manifestacao_id);
--;;
CREATE INDEX IF NOT EXISTS idx_resposta_ouvidoria_staging
  ON participacao.resposta_ouvidoria (ente_id, lote_id) WHERE efetivado_em IS NULL;
--;;
ALTER TABLE participacao.resposta_ouvidoria ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE participacao.resposta_ouvidoria FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON participacao.resposta_ouvidoria;
--;;
CREATE POLICY tenant_isolation ON participacao.resposta_ouvidoria
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid
         AND (efetivado_em IS NOT NULL OR lote_id = NULLIF(current_setting('app.ver_lote', true), '')::uuid))
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
-- append-only PURO (nivel a): SELECT/INSERT; sem UPDATE/DELETE (Inv.10 — resposta registrada nunca muda).
GRANT SELECT, INSERT ON participacao.resposta_ouvidoria TO oplenario_app;
--;;
CREATE TRIGGER trg_resposta_ouvidoria_append_only
  BEFORE UPDATE OR DELETE ON participacao.resposta_ouvidoria
  FOR EACH ROW EXECUTE FUNCTION shared.imut_append_only();
