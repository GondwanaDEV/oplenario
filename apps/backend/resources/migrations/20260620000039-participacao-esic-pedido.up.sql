-- F6 Slice 1: modulo PARTICIPACAO — e-SIC AMPLO (feature 6.1, LAI 12.527/2011). O pedido de acesso a
-- informacao (`pedido_esic`) + o RELOGIO do prazo legal (`prazo_ativo`). Decisao de arquitetura Arch B
-- (F6): `participacao` e' DONO do proprio prazo, adotando a FORMA polimorfica de compliance/prazo_dominio_ativo
-- DENTRO do schema participacao — sem JOIN cross-schema nem import cross-modulo (§22.10); o "anel do prazo"
-- do cidadao le barato (1 leitura single-row) e o recibo LAI e' instantaneo. e-SIC "amplo" = QUALQUER info
-- publica: `descricao` e' texto livre, SEM FK de tema (restringir o objeto do SIC violaria a LAI).
-- Convencao tenant-table MODERNA (migs 0026+): ente_id primeiro, so timestamptz, transversais + staging
-- CHECK, FORCE RLS, GRANT sem DELETE (Inv.10). O schema participacao ja existe (mig 0001).

-- 1a migration do modulo participacao: o role de runtime precisa de USAGE no schema (padrao legislativo 0013 / sessoes 0026).
GRANT USAGE ON SCHEMA participacao TO oplenario_app;
--;;
-- ---------- pedido_esic: state-machine CAS (protocolado -> em_analise -> respondido|indeferido) ----------
CREATE TABLE IF NOT EXISTS participacao.pedido_esic (
  ente_id    uuid NOT NULL,
  id         uuid NOT NULL DEFAULT gen_random_uuid(),
  ano        integer NOT NULL,                          -- ano do protocolo (numeracao reinicia por ano)
  sequencial bigint  NOT NULL,                          -- gapless por (ente, ano) — escopo 'pedido_esic:<ano>'
  protocolo  text    NOT NULL,                          -- numero humano derivado de (ano, sequencial): 'ESIC-<ano>-NNNNNN'
  assunto    text    NOT NULL CHECK (length(trim(assunto)) > 0 AND length(assunto) <= 500),
  -- e-SIC AMPLO: texto livre = qualquer informacao publica do orgao. SEM FK de tema (restringir o objeto do
  -- SIC por tema violaria a LAI). solicitante = forward-ref a identidade (uuid, SEM FK cross-schema, §22.10).
  -- Os tetos (500/20000) sao DEFESA-EM-PROFUNDIDADE anti-abuso (nao restricao semantica do SIC): fecham o
  -- caminho de staging/importacao em lote (lote_id), que NAO passa pelo cap de corpo HTTP da borda. Espelhados
  -- no wire/in (:max) — a borda rejeita 400 antes do banco.
  descricao  text    NOT NULL CHECK (length(trim(descricao)) > 0 AND length(descricao) <= 20000),
  solicitante_identidade_id uuid NOT NULL,
  estado     text    NOT NULL DEFAULT 'protocolado' CHECK (estado IN
    ('protocolado', 'em_analise', 'respondido', 'indeferido')),
  recibo_em  timestamptz NOT NULL,                      -- MARCO DE INICIO DO RELOGIO (instante do recibo LAI)
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
  UNIQUE (ente_id, protocolo),                          -- protocolo = chave publica de acompanhamento
  CONSTRAINT pedido_esic_staging_valido CHECK (lote_id IS NOT NULL OR efetivado_em IS NOT NULL)
);
--;;
-- "meus pedidos" (read-model por solicitante autenticado, cross-ano). Escopo de tenant no prefixo.
CREATE INDEX IF NOT EXISTS idx_pedido_esic_solicitante
  ON participacao.pedido_esic (ente_id, solicitante_identidade_id, criado_em DESC);
--;;
CREATE INDEX IF NOT EXISTS idx_pedido_esic_staging
  ON participacao.pedido_esic (ente_id, lote_id) WHERE efetivado_em IS NULL;
--;;
ALTER TABLE participacao.pedido_esic ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE participacao.pedido_esic FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON participacao.pedido_esic;
--;;
CREATE POLICY tenant_isolation ON participacao.pedido_esic
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid
         AND (efetivado_em IS NOT NULL OR lote_id = NULLIF(current_setting('app.ver_lote', true), '')::uuid))
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
GRANT SELECT, INSERT, UPDATE ON participacao.pedido_esic TO oplenario_app;
--;;
-- state-machine: respondido/indeferido sao terminais — a linha congela (defesa-em-profundidade alem do CAS do servico).
CREATE TRIGGER trg_pedido_esic_trava_terminal
  BEFORE UPDATE ON participacao.pedido_esic
  FOR EACH ROW EXECUTE FUNCTION shared.imut_trava_estado_terminal('respondido', 'indeferido');
--;;
-- ---------- prazo_ativo: forma disc.6 (polimorfico), state-machine CAS. SEM trigger de imutabilidade:
--            o ciclo (pendente -> cumprida|vencida|dispensada|cancelada) E' a maquina (como o compliance). ----------
CREATE TABLE IF NOT EXISTS participacao.prazo_ativo (
  ente_id     uuid NOT NULL,
  id          uuid NOT NULL DEFAULT gen_random_uuid(),
  objeto_tipo text NOT NULL CHECK (objeto_tipo IN
    ('pedido_esic', 'recurso_esic', 'solicitacao_titular')),   -- polimorfico (recurso/titular = fatias seguintes)
  objeto_id   uuid NOT NULL,                            -- forward-ref ao agregado sob prazo (mesmo schema)
  vence_em    date NOT NULL,                            -- relogio da instancia (monitoramento = read-derivation sobre isto)
  estado      text NOT NULL DEFAULT 'pendente' CHECK (estado IN
    ('pendente', 'cumprida', 'vencida', 'dispensada', 'cancelada')),
  base_dias   integer,                                  -- prazo-base aplicado (LAI 20; proveniencia do calculo)
  prorrogado_ate date,                                  -- +10 LAI (carry F6.2 prorrogacao; nulo em V1 Slice 1)
  prazo_fonte_ref text,                                 -- citacao legal (ex.: 'LAI art. 11 §1º')
  cumprida_em timestamptz,                              -- carimbo do cumprimento (quando o agregado respondeu)
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
  -- idempotencia + o "anel": no maximo 1 prazo por objeto; esta UNIQUE (ente, objeto_tipo, objeto_id) e' o
  -- indice que serve a leitura single-row do anel (1 probe/heap-fetch por page-load do cidadao — barato).
  UNIQUE (ente_id, objeto_tipo, objeto_id),
  CONSTRAINT prazo_ativo_staging_valido CHECK (lote_id IS NOT NULL OR efetivado_em IS NOT NULL)
);
--;;
-- sweep do vencimento (F6.3): varre prazos ABERTOS por ente/vencimento. Index PARCIAL: os encerrados
-- (cumprida|dispensada|cancelada) crescem sem limite mas nunca sao relidos no caminho quente do sweep.
CREATE INDEX IF NOT EXISTS idx_prazo_ativo_sweep
  ON participacao.prazo_ativo (ente_id, vence_em)
  WHERE estado IN ('pendente', 'vencida');
--;;
-- (o "anel" do prazo NAO tem indice covering proprio: a UNIQUE (ente, objeto_tipo, objeto_id) acima ja
--  serve o read single-row barato. Um INCLUDE (vence_em, estado, ...) NAO daria index-only aos leitores
--  reais — buscar-do-objeto/prazo-do-objeto projetam a linha inteira, incl. base_dias fora do INCLUDE —
--  seria so custo de escrita de um segundo indice quase-identico a UNIQUE, sem ganho de leitura. Arch B.)
CREATE INDEX IF NOT EXISTS idx_prazo_ativo_staging
  ON participacao.prazo_ativo (ente_id, lote_id) WHERE efetivado_em IS NULL;
--;;
ALTER TABLE participacao.prazo_ativo ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE participacao.prazo_ativo FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON participacao.prazo_ativo;
--;;
CREATE POLICY tenant_isolation ON participacao.prazo_ativo
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid
         AND (efetivado_em IS NOT NULL OR lote_id = NULLIF(current_setting('app.ver_lote', true), '')::uuid))
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
GRANT SELECT, INSERT, UPDATE ON participacao.prazo_ativo TO oplenario_app;
