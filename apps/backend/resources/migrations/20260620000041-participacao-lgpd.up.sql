-- F6 Slice 4: modulo PARTICIPACAO — PORTAL DO TITULAR LGPD (feature 5.10, Lei 13.709/2018 arts. 18/23/41).
-- A SOLICITACAO do titular (`solicitacao_titular`) + a RESPOSTA (`resposta_titular`, append-only) + o contato
-- publico do ENCARREGADO/DPO (`encarregado`). O prazo LGPD e' um CONTADOR SEPARADO do e-SIC: a solicitacao
-- materializa a 3a especie do prazo_ativo polimorfico (objeto_tipo='solicitacao_titular') com seu proprio
-- vence_em (dias-titular, [GAP] de conteudo — a LGPD nao cravou o numero; ver participacao/logic). NAO reusa
-- o relogio de 20 dias da LAI.
-- Convencao tenant-table MODERNA (migs 0026+): ente_id primeiro, so timestamptz, transversais + staging CHECK,
-- FORCE RLS, GRANT sem DELETE (Inv.10). NAO e' a 1a migration do modulo (a 0039 ja fez GRANT USAGE) -> sem
-- GRANT/REVOKE USAGE do schema. FK COMPOSTA same-tenant same-SCHEMA (§22.10 so proibe FK cross-SCHEMA).

-- ---------- solicitacao_titular: state-machine CAS (protocolada -> em_analise -> respondida|indeferida) ----------
-- Os 5 DIREITOS do titular (LGPD art. 18): acessar / corrigir / eliminar / com_quem_compartilhado /
-- revogar_consentimento. Todos registrados UNIFORMEMENTE — o cumprimento AUTOMATIZADO de revogar_consentimento
-- via o modulo identidade e' CARRY (guard futuro do host sobre repo-identidade; NAO se fia cross-modulo
-- especulativo agora — o DPO processa manual em V1). detalhe = texto livre OPCIONAL do titular (ex.: qual dado
-- corrigir); CHECK so quando NOT NULL.
CREATE TABLE IF NOT EXISTS participacao.solicitacao_titular (
  ente_id    uuid NOT NULL,
  id         uuid NOT NULL DEFAULT gen_random_uuid(),
  ano        integer NOT NULL,                          -- ano do protocolo (numeracao reinicia por ano)
  sequencial bigint  NOT NULL,                          -- gapless por (ente, ano) — escopo 'solicitacao_titular:<ano>'
  protocolo  text    NOT NULL,                          -- numero humano derivado de (ano, sequencial): 'LGPD-<ano>-NNNNNN'
  tipo       text    NOT NULL CHECK (tipo IN
    ('acessar', 'corrigir', 'eliminar', 'com_quem_compartilhado', 'revogar_consentimento')),  -- os 5 direitos (art. 18)
  -- titular = forward-ref a identidade (uuid, SEM FK cross-schema, §22.10). detalhe OPCIONAL (nullable): texto
  -- livre; o teto 5000 e' defesa-em-profundidade anti-abuso (fecha o caminho de staging/lote que nao passa pelo
  -- cap de corpo HTTP da borda). Espelhado no wire/in (:max). CHECK so quando NOT NULL.
  titular_identidade_id uuid NOT NULL,
  detalhe    text    CHECK (detalhe IS NULL OR (length(trim(detalhe)) > 0 AND length(detalhe) <= 5000)),
  estado     text    NOT NULL DEFAULT 'protocolada' CHECK (estado IN
    ('protocolada', 'em_analise', 'respondida', 'indeferida')),
  recibo_em  timestamptz NOT NULL,                      -- MARCO DE INICIO DO RELOGIO LGPD (contador SEPARADO do e-SIC)
  -- transversais (§22.4.3 disc.1)
  origem     text NOT NULL DEFAULT 'nativa',
  origem_ref text,
  origem_importado_em timestamptz,
  lote_id    uuid,
  efetivado_em timestamptz,
  created_by uuid,
  criado_em    timestamptz NOT NULL DEFAULT now(),
  atualizado_em timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (ente_id, id),
  UNIQUE (ente_id, ano, sequencial),                    -- gapless por ano (kernel/sequencial)
  UNIQUE (ente_id, protocolo),                          -- protocolo = chave publica/humana
  CONSTRAINT solicitacao_titular_staging_valido CHECK (lote_id IS NOT NULL OR efetivado_em IS NOT NULL)
);
--;;
CREATE INDEX IF NOT EXISTS idx_solicitacao_titular_staging
  ON participacao.solicitacao_titular (ente_id, lote_id) WHERE efetivado_em IS NULL;
--;;
ALTER TABLE participacao.solicitacao_titular ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE participacao.solicitacao_titular FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON participacao.solicitacao_titular;
--;;
CREATE POLICY tenant_isolation ON participacao.solicitacao_titular
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid
         AND (efetivado_em IS NOT NULL OR lote_id = NULLIF(current_setting('app.ver_lote', true), '')::uuid))
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
GRANT SELECT, INSERT, UPDATE ON participacao.solicitacao_titular TO oplenario_app;
--;;
-- state-machine: respondida/indeferida sao terminais — a linha congela (defesa-em-profundidade alem do CAS do servico).
CREATE TRIGGER trg_solicitacao_titular_trava_terminal
  BEFORE UPDATE ON participacao.solicitacao_titular
  FOR EACH ROW EXECUTE FUNCTION shared.imut_trava_estado_terminal('respondida', 'indeferida');
--;;
-- ---------- resposta_titular: APPEND-ONLY (Inv.10). O ato de responder uma solicitacao do titular. ----------
CREATE TABLE IF NOT EXISTS participacao.resposta_titular (
  ente_id       uuid NOT NULL,
  id            uuid NOT NULL DEFAULT gen_random_uuid(),
  solicitacao_id uuid NOT NULL,                         -- a solicitacao respondida (FK composta same-tenant abaixo)
  -- corpo da resposta: texto livre. Teto 50000 = defesa-em-profundidade anti-abuso (espelhado no wire/in).
  corpo         text NOT NULL CHECK (length(trim(corpo)) > 0 AND length(corpo) <= 50000),
  respondido_por uuid NOT NULL,                         -- o SERVIDOR/Encarregado que respondeu (injetado do ator, nunca do corpo)
  respondida_em timestamptz NOT NULL,                   -- instante da resposta
  -- transversais de append-only PURO: sem updated_*/created_by (nunca muta; respondido_por = autor)
  origem        text NOT NULL DEFAULT 'nativa',
  origem_ref    text,
  origem_importado_em timestamptz,
  lote_id       uuid,
  efetivado_em  timestamptz,
  criado_em     timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (ente_id, id),
  -- FK COMPOSTA same-tenant (ente_id ancora o par): a resposta cita uma solicitacao do MESMO ente (anti
  -- confused-deputy no nivel do banco). Same-schema -> FK permitida (§22.10 so proibe cross-SCHEMA).
  FOREIGN KEY (ente_id, solicitacao_id) REFERENCES participacao.solicitacao_titular (ente_id, id),
  CONSTRAINT resposta_titular_staging_valido CHECK (lote_id IS NOT NULL OR efetivado_em IS NOT NULL)
);
--;;
-- FK sempre indexada (convencao do projeto; cobre "respostas de uma solicitacao").
CREATE INDEX IF NOT EXISTS idx_resposta_titular_solicitacao
  ON participacao.resposta_titular (ente_id, solicitacao_id);
--;;
CREATE INDEX IF NOT EXISTS idx_resposta_titular_staging
  ON participacao.resposta_titular (ente_id, lote_id) WHERE efetivado_em IS NULL;
--;;
ALTER TABLE participacao.resposta_titular ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE participacao.resposta_titular FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON participacao.resposta_titular;
--;;
CREATE POLICY tenant_isolation ON participacao.resposta_titular
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid
         AND (efetivado_em IS NOT NULL OR lote_id = NULLIF(current_setting('app.ver_lote', true), '')::uuid))
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
-- append-only PURO (nivel a): SELECT/INSERT; sem UPDATE/DELETE (Inv.10 — resposta registrada nunca muda).
GRANT SELECT, INSERT ON participacao.resposta_titular TO oplenario_app;
--;;
CREATE TRIGGER trg_resposta_titular_append_only
  BEFORE UPDATE OR DELETE ON participacao.resposta_titular
  FOR EACH ROW EXECUTE FUNCTION shared.imut_append_only();
--;;
-- ---------- encarregado: CONFIG-like (contato PUBLICO do DPO — exigencia legal LGPD art. 41). UM por ente. ----------
-- MUTAVEL (nao append-only): a Casa troca de Encarregado; o contato publico atualiza. UNIQUE(ente_id) garante 1
-- linha por ente -> upsert por ente_id (INSERT ... ON CONFLICT (ente_id) DO UPDATE). [GAP] DE PLACEMENT: o
-- Encarregado poderia viver em `cadastros`/config-do-ente (e' um cargo do orgao, nao um artefato de participacao);
-- fica em participacao por ora porque a UNICA superficie que o consome hoje e' o Portal do Titular (a rota publica
-- do contato do DPO). Mover p/ cadastros e' refino futuro se outro modulo passar a precisar do dado.
CREATE TABLE IF NOT EXISTS participacao.encarregado (
  ente_id    uuid NOT NULL,
  id         uuid NOT NULL DEFAULT gen_random_uuid(),
  nome       text NOT NULL CHECK (length(trim(nome)) > 0 AND length(nome) <= 200),
  rotulo     text NOT NULL CHECK (length(trim(rotulo)) > 0 AND length(rotulo) <= 200),  -- ex.: 'Encarregado de Dados (DPO)'
  email      text NOT NULL CHECK (length(trim(email)) > 0 AND length(email) <= 320),    -- contato publico (art. 41 §1º)
  atualizado_por uuid NOT NULL,                         -- o SERVIDOR que definiu/atualizou o contato (injetado do ator)
  -- transversais (§22.4.3 disc.1)
  origem     text NOT NULL DEFAULT 'nativa',
  origem_ref text,
  origem_importado_em timestamptz,
  lote_id    uuid,
  efetivado_em timestamptz,
  created_by uuid,
  criado_em    timestamptz NOT NULL DEFAULT now(),
  atualizado_em timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (ente_id, id),
  -- UM Encarregado por ente: alvo do upsert ON CONFLICT (ente_id).
  UNIQUE (ente_id),
  CONSTRAINT encarregado_staging_valido CHECK (lote_id IS NOT NULL OR efetivado_em IS NOT NULL)
);
--;;
-- Indice de staging companheiro (convencao do projeto: TODA tabela tenant com staging CHECK tem o parcial
-- (ente_id, lote_id) WHERE efetivado_em IS NULL, p/ a leitura do lote sob `app.ver_lote` cair em index scan e
-- nao Seq Scan). encarregado carrega o aparato de staging (ver a clausula app.ver_lote na RLS abaixo), entao
-- segue a convencao — sem excecao (mesma posicao das outras duas tabelas desta migration).
CREATE INDEX IF NOT EXISTS idx_encarregado_staging
  ON participacao.encarregado (ente_id, lote_id) WHERE efetivado_em IS NULL;
--;;
ALTER TABLE participacao.encarregado ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE participacao.encarregado FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON participacao.encarregado;
--;;
CREATE POLICY tenant_isolation ON participacao.encarregado
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid
         AND (efetivado_em IS NOT NULL OR lote_id = NULLIF(current_setting('app.ver_lote', true), '')::uuid))
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
-- MUTAVEL (config, nao Inv.10): SELECT,INSERT,UPDATE. Nunca DELETE (a Casa nao apaga o contato, so troca).
GRANT SELECT, INSERT, UPDATE ON participacao.encarregado TO oplenario_app;
