-- F3.5: modulo legislativo, eixo E (§22.4) — apensacao. TABELA DE ASSOCIACAO COM HISTORICO
-- (descartados: coluna direta na proposicao e o conceito de "grupo de tramitacao"). O FATO de
-- apensacao e' a linha: principal + apensada + quando/por-que apensou. Desapensar NAO apaga — e'
-- UPDATE em `desapensada_em` (a linha persiste como fato historico, Inv.10). Mudanca de principal e'
-- DOIS atos auditados (desapensar + apensar), nunca um UPDATE in-place do principal.
--
-- "motivos" (plural na spec) = motivo_apensacao + motivo_desapensacao (um por ato, correlatos).
-- "atos_ref" = ato_apensacao_ref + ato_desapensacao_ref: proveniencia do despacho que ordenou cada
-- ato (forward-ref, SEM FK — a entidade despacho ainda nao existe; mesmo padrao de origem_ref).
--
-- IMUTABILIDADE nivel (c) — MUTACAO PARCIAL (§22.4.3 disc.4): so `desapensada_em` e os campos
-- correlatos da desapensacao (motivo/ato) sao mutaveis, UMA VEZ. Todo o resto e' fato congelado.
-- O helper compartilhado (mig 0012) cobre os niveis (a)/(b); o (c) e' "caso a caso na propria
-- migracao" (dito explicitamente la) — trigger local `legislativo.apensacao_imut_parcial` abaixo.
--
-- PARTICIONAMENTO: NAO hash-particionada (como `cadastros`/`emendas`, decisao F1.1/F3.4): cardinalidade
-- limitada (poucas apensacoes por casa), isolamento por RLS basta, e a FK same-tenant p/ as proposicoes
-- particionadas exige so incluir ente_id na referencia. Revisitavel se a observabilidade pedir.

CREATE TABLE IF NOT EXISTS legislativo.proposicao_apensacao (
  ente_id      uuid NOT NULL,
  id           uuid NOT NULL DEFAULT gen_random_uuid(),
  principal_id uuid NOT NULL,                            -- a proposicao que LIDERA a tramitacao conjunta
  apensada_id  uuid NOT NULL,                            -- a proposicao apensada (tramita junto da principal)
  -- ---- o ato de apensar (preenchido uma vez, na criacao) ----
  apensada_em       timestamptz NOT NULL DEFAULT now(),
  motivo_apensacao  text,
  ato_apensacao_ref uuid,                                -- despacho que ordenou (forward-ref, sem FK)
  -- ---- o ato de desapensar (NULL enquanto ativa; preenchido uma vez, e congela a linha) ----
  desapensada_em        timestamptz,
  motivo_desapensacao   text,
  ato_desapensacao_ref  uuid,
  -- ---- transversais (§22.4.3 disc.1) ----
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
  -- integridade same-tenant declarativa: ambos os lados existem no MESMO ente (FK inclui ente_id,
  -- lado da PK particionada das proposicoes).
  FOREIGN KEY (ente_id, principal_id) REFERENCES legislativo.proposicoes (ente_id, id),
  FOREIGN KEY (ente_id, apensada_id)  REFERENCES legislativo.proposicoes (ente_id, id),
  -- nao se apensa a si mesma
  CONSTRAINT apensacao_nao_reflexiva CHECK (principal_id <> apensada_id),
  -- coerencia temporal: desapensar nunca antes de apensar
  CONSTRAINT apensacao_ordem_temporal CHECK (desapensada_em IS NULL OR desapensada_em >= apensada_em),
  -- campos da desapensacao so existem COM a desapensacao (sem motivo/ato de desapensacao "solto")
  CONSTRAINT apensacao_desapensacao_coerente
    CHECK (desapensada_em IS NOT NULL OR (motivo_desapensacao IS NULL AND ato_desapensacao_ref IS NULL))
);
--;;
-- INVARIANTE central do eixo E: uma proposicao esta apensada a NO MAXIMO UM principal de cada vez.
-- UNIQUE PARCIAL sobre as ATIVAS (desapensada_em IS NULL) — as linhas historicas (desapensadas) nao
-- contam, entao re-apensar apos desapensar e' livre. Indexa tambem a busca de apensacao ativa por apensada.
CREATE UNIQUE INDEX IF NOT EXISTS uq_apensacao_ativa_por_apensada
  ON legislativo.proposicao_apensacao (ente_id, apensada_id) WHERE desapensada_em IS NULL;
--;;
-- traversal da cadeia (CTE recursivo): apensadas ATIVAS por principal.
CREATE INDEX IF NOT EXISTS idx_apensacao_principal_ativa
  ON legislativo.proposicao_apensacao (ente_id, principal_id) WHERE desapensada_em IS NULL;
--;;
-- enforcamento de FK: os indices acima sao PARCIAIS (so ativas) e o Postgres NAO os usa p/ checar FK
-- (a checagem precisa achar QUALQUER linha referenciante, inclusive desapensadas). Indices NAO-parciais
-- p/ as duas FKs (mesmo que UPDATE/DELETE da PK de proposicoes seja raro por Inv.10) — review F3.5 DB-M1.
CREATE INDEX IF NOT EXISTS idx_apensacao_fk_principal
  ON legislativo.proposicao_apensacao (ente_id, principal_id);
--;;
CREATE INDEX IF NOT EXISTS idx_apensacao_fk_apensada
  ON legislativo.proposicao_apensacao (ente_id, apensada_id);
--;;
-- staging das nao-efetivadas (import de legado, fundacao #2) — mesmo padrao dos demais.
CREATE INDEX IF NOT EXISTS idx_apensacao_staging
  ON legislativo.proposicao_apensacao (ente_id, lote_id) WHERE efetivado_em IS NULL;
--;;
ALTER TABLE legislativo.proposicao_apensacao ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE legislativo.proposicao_apensacao FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON legislativo.proposicao_apensacao;
--;;
CREATE POLICY tenant_isolation ON legislativo.proposicao_apensacao
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid
         AND (efetivado_em IS NOT NULL
              OR lote_id = NULLIF(current_setting('app.ver_lote', true), '')::uuid))
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
-- sem DELETE (Inv.10): apensacao nao se apaga — a desapensacao e' um estado explicito (UPDATE).
GRANT SELECT, INSERT, UPDATE ON legislativo.proposicao_apensacao TO oplenario_app;
--;;
-- imutabilidade (c) MUTACAO PARCIAL: caso a caso (mig 0012 deixa o nivel c fora do helper generico).
-- Regra: (1) uma vez desapensada (fato terminal), a linha CONGELA; (2) os unicos campos mutaveis sao
-- desapensada_em + motivo_desapensacao + ato_desapensacao_ref (+ bookkeeping updated_by/atualizado_em/
-- lock_version). Qualquer mudanca em campo FIXO (principal/apensada/apensada_em/motivo_apensacao/
-- ato_apensacao_ref/origem*/origem_importado_em/created_by/criado_em/ente_id/id) e' barrada. `efetivado_em`
-- e' transicao ONE-WAY: NULL->timestamp (efetivacao de lote, fundacao #2) e' permitido, mas timestamp->NULL
-- NAO (volta a NULL esconderia a linha sob a policy RLS = soft-delete sem trilha, violando Inv.10 —
-- review F3.5 DB-M2). Excecao geral: correcao auditada (GUC app.correcao_auditada).
CREATE OR REPLACE FUNCTION legislativo.apensacao_imut_parcial() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  correcao text := NULLIF(current_setting('app.correcao_auditada', true), '');
BEGIN
  IF correcao IS NOT NULL THEN
    RETURN NEW;  -- fluxo de correcao auditada pode tudo (a auditoria registra o porque)
  END IF;
  -- (1) fato ja desapensado e' congelado: nenhum UPDATE
  IF OLD.desapensada_em IS NOT NULL THEN
    RAISE EXCEPTION 'imutabilidade (c) apensacao: fato % ja desapensado e imutavel (use correcao auditada)',
      OLD.id USING ERRCODE = 'check_violation';
  END IF;
  -- (1b) efetivado_em nao regride a NULL (anti soft-delete via RLS, Inv.10); NULL->timestamp e' OK
  IF OLD.efetivado_em IS NOT NULL AND NEW.efetivado_em IS NULL THEN
    RAISE EXCEPTION 'imutabilidade (c) apensacao: efetivado_em nao volta a NULL (sem soft-delete via RLS)'
      USING ERRCODE = 'check_violation';
  END IF;
  -- (2) so desapensada_em e motivos/ato correlatos mudam; o resto e' fato fixo
  IF NEW.ente_id            IS DISTINCT FROM OLD.ente_id
     OR NEW.id              IS DISTINCT FROM OLD.id
     OR NEW.principal_id    IS DISTINCT FROM OLD.principal_id
     OR NEW.apensada_id     IS DISTINCT FROM OLD.apensada_id
     OR NEW.apensada_em     IS DISTINCT FROM OLD.apensada_em
     OR NEW.motivo_apensacao     IS DISTINCT FROM OLD.motivo_apensacao
     OR NEW.ato_apensacao_ref    IS DISTINCT FROM OLD.ato_apensacao_ref
     OR NEW.origem               IS DISTINCT FROM OLD.origem
     OR NEW.origem_ref           IS DISTINCT FROM OLD.origem_ref
     OR NEW.origem_importado_em  IS DISTINCT FROM OLD.origem_importado_em
     OR NEW.created_by           IS DISTINCT FROM OLD.created_by
     OR NEW.criado_em            IS DISTINCT FROM OLD.criado_em
  THEN
    RAISE EXCEPTION 'imutabilidade (c) apensacao: so desapensada_em/motivo_desapensacao/ato_desapensacao_ref sao mutaveis'
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END;
$$;
--;;
CREATE TRIGGER trg_apensacao_imut_parcial
  BEFORE UPDATE ON legislativo.proposicao_apensacao
  FOR EACH ROW EXECUTE FUNCTION legislativo.apensacao_imut_parcial();
