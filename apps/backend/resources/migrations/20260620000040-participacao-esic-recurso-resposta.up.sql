-- F6 Slice 2: modulo PARTICIPACAO — e-SIC RESPOSTA + RECURSO (feature 6.1, LAI 12.527/2011). O RECURSO
-- (`recurso_esic`) e' ENTIDADE SEPARADA do pedido: relogio PROPRIO (2a linha em participacao.prazo_ativo,
-- objeto_tipo='recurso_esic'), protocolo/recibo/decisao proprios, e interpor recurso NAO muta o pedido
-- terminal (o pedido segue congelado pelo trg_pedido_esic_trava_terminal). A RESPOSTA (`resposta_esic`) e'
-- APPEND-ONLY (Inv.10): o ato de responder um pedido OU decidir um recurso e' registrado e NUNCA alterado.
-- Convencao tenant-table MODERNA (migs 0026+). FK COMPOSTA same-tenant same-SCHEMA (§22.10 so proibe
-- FK cross-SCHEMA). NAO e' a 1a migration do modulo (a 0039 ja fez GRANT USAGE) -> sem GRANT/REVOKE USAGE.

-- ---------- recurso_esic: state-machine CAS (protocolado -> decidido). Terminal 'decidido' congela a linha. ----------
CREATE TABLE IF NOT EXISTS participacao.recurso_esic (
  ente_id    uuid NOT NULL,
  id         uuid NOT NULL DEFAULT gen_random_uuid(),
  pedido_id  uuid NOT NULL,                             -- o pedido recorrido (ref intra-schema, FK composta abaixo)
  ano        integer NOT NULL,                          -- ano do protocolo do recurso (numeracao reinicia por ano)
  sequencial bigint  NOT NULL,                          -- gapless por (ente, ano) — escopo 'recurso_esic:<ano>'
  protocolo  text    NOT NULL,                          -- numero humano derivado de (ano, sequencial): 'REC-<ano>-NNNNNN'
  instancia  smallint NOT NULL CHECK (instancia >= 1),  -- ordinal da instancia recursal (V1 = 1a; >= 1 defesa-em-profundidade contra import/fatia futura)
  -- motivo do recurso: texto livre. O teto (20000) e' DEFESA-EM-PROFUNDIDADE anti-abuso (fecha o caminho de
  -- staging/importacao em lote, que NAO passa pelo cap de corpo HTTP da borda). Espelhado no wire/in (:max).
  motivo     text    NOT NULL CHECK (length(trim(motivo)) > 0 AND length(motivo) <= 20000),
  estado     text    NOT NULL DEFAULT 'protocolado' CHECK (estado IN ('protocolado', 'decidido')),
  recibo_em  timestamptz NOT NULL,                      -- MARCO DE INICIO DO RELOGIO PROPRIO do recurso (independente do pedido)
  decidido_em timestamptz,                              -- carimbo da decisao (so preenchido no estado terminal)
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
  UNIQUE (ente_id, protocolo),                          -- protocolo = chave publica de acompanhamento do recurso
  -- IDEMPOTENCIA DE NEGOCIO: no maximo 1 recurso por (pedido, instancia). O pedido terminal NAO muta ao
  -- receber recurso, entao a policy do controller (dono + recorrivel) NAO barra o double-click/retry do
  -- cidadao — sem esta UNIQUE, N recursos duplicados, cada um com relogio/obrigacao LAI proprios (falso
  -- alarme no painel §16.11). A 2a interposicao na MESMA instancia vira 23505 -> :conflito/participacao (409)
  -- no Repo (espelha o CAS-no-banco do resto do modulo). Multi-instancia futura usa outro valor -> nao colide.
  UNIQUE (ente_id, pedido_id, instancia),
  -- FK COMPOSTA same-tenant (ente_id ancora o par): garante que o recurso aponta a um pedido do MESMO ente
  -- (anti confused-deputy no nivel do banco). Same-schema -> FK permitida (§22.10 so proibe cross-SCHEMA).
  FOREIGN KEY (ente_id, pedido_id) REFERENCES participacao.pedido_esic (ente_id, id),
  -- coerencia do desfecho: uma linha 'decidido' TEM decidido_em; enquanto 'protocolado' o carimbo fica nulo.
  CONSTRAINT recurso_decidido_coerente CHECK (
    (estado = 'decidido' AND decidido_em IS NOT NULL)
    OR (estado = 'protocolado' AND decidido_em IS NULL)),
  CONSTRAINT recurso_esic_staging_valido CHECK (lote_id IS NOT NULL OR efetivado_em IS NOT NULL)
);
--;;
-- FK pedido_id sempre indexada (convencao do projeto; cobre tambem "recursos de um pedido").
CREATE INDEX IF NOT EXISTS idx_recurso_esic_pedido
  ON participacao.recurso_esic (ente_id, pedido_id);
--;;
CREATE INDEX IF NOT EXISTS idx_recurso_esic_staging
  ON participacao.recurso_esic (ente_id, lote_id) WHERE efetivado_em IS NULL;
--;;
ALTER TABLE participacao.recurso_esic ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE participacao.recurso_esic FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON participacao.recurso_esic;
--;;
CREATE POLICY tenant_isolation ON participacao.recurso_esic
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid
         AND (efetivado_em IS NOT NULL OR lote_id = NULLIF(current_setting('app.ver_lote', true), '')::uuid))
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
GRANT SELECT, INSERT, UPDATE ON participacao.recurso_esic TO oplenario_app;
--;;
-- state-machine: 'decidido' e' terminal — a linha congela (defesa-em-profundidade alem do CAS do servico).
CREATE TRIGGER trg_recurso_esic_trava_terminal
  BEFORE UPDATE ON participacao.recurso_esic
  FOR EACH ROW EXECUTE FUNCTION shared.imut_trava_estado_terminal('decidido');
--;;
-- ---------- resposta_esic: APPEND-ONLY (Inv.10). O ato de responder um pedido OU decidir um recurso. ----------
CREATE TABLE IF NOT EXISTS participacao.resposta_esic (
  ente_id     uuid NOT NULL,
  id          uuid NOT NULL DEFAULT gen_random_uuid(),
  -- EXATAMENTE UM alvo (pedido OU recurso), garantido pelo CHECK num_nonnulls abaixo. Cada FK composta e'
  -- MATCH SIMPLE: quando a coluna do alvo e' NULL a FK NAO e' checada (a outra e' que vale) -> FK condicional.
  pedido_id   uuid,
  recurso_id  uuid,
  -- corpo da resposta/decisao: texto livre. Teto 50000 = defesa-em-profundidade anti-abuso (espelhado no wire/in).
  corpo       text NOT NULL CHECK (length(trim(corpo)) > 0 AND length(corpo) <= 50000),
  respondido_por uuid NOT NULL,                         -- o SERVIDOR que respondeu (injetado do ator, nunca do corpo)
  respondida_em timestamptz NOT NULL,                   -- instante da resposta/decisao
  -- transversais de append-only PURO: sem updated_*/lock_version/created_by (nunca muta; respondido_por = autor)
  origem       text NOT NULL DEFAULT 'nativa',
  origem_ref   text,
  origem_importado_em timestamptz,
  lote_id      uuid,
  efetivado_em timestamptz,
  criado_em    timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (ente_id, id),
  -- alvo exclusivo: 1 e apenas 1 de (pedido_id, recurso_id) nao-nulo.
  CONSTRAINT resposta_alvo_exclusivo CHECK (num_nonnulls(pedido_id, recurso_id) = 1),
  -- FKs COMPOSTAS same-tenant CONDICIONAIS (MATCH SIMPLE ignora a que tem NULL): a resposta cita um pedido
  -- OU um recurso do MESMO ente (§22.10 same-schema OK).
  FOREIGN KEY (ente_id, pedido_id)  REFERENCES participacao.pedido_esic  (ente_id, id),
  FOREIGN KEY (ente_id, recurso_id) REFERENCES participacao.recurso_esic (ente_id, id),
  CONSTRAINT resposta_esic_staging_valido CHECK (lote_id IS NOT NULL OR efetivado_em IS NOT NULL)
);
--;;
-- indices parciais por alvo (cada resposta pertence a exatamente um): "respostas de um pedido" / "de um recurso".
CREATE INDEX IF NOT EXISTS idx_resposta_esic_pedido
  ON participacao.resposta_esic (ente_id, pedido_id) WHERE pedido_id IS NOT NULL;
--;;
CREATE INDEX IF NOT EXISTS idx_resposta_esic_recurso
  ON participacao.resposta_esic (ente_id, recurso_id) WHERE recurso_id IS NOT NULL;
--;;
CREATE INDEX IF NOT EXISTS idx_resposta_esic_staging
  ON participacao.resposta_esic (ente_id, lote_id) WHERE efetivado_em IS NULL;
--;;
ALTER TABLE participacao.resposta_esic ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE participacao.resposta_esic FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON participacao.resposta_esic;
--;;
CREATE POLICY tenant_isolation ON participacao.resposta_esic
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid
         AND (efetivado_em IS NOT NULL OR lote_id = NULLIF(current_setting('app.ver_lote', true), '')::uuid))
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
-- append-only PURO (nivel a): SELECT/INSERT; sem UPDATE/DELETE (Inv.10 — resposta registrada nunca muda).
GRANT SELECT, INSERT ON participacao.resposta_esic TO oplenario_app;
--;;
CREATE TRIGGER trg_resposta_esic_append_only
  BEFORE UPDATE OR DELETE ON participacao.resposta_esic
  FOR EACH ROW EXECUTE FUNCTION shared.imut_append_only();
