-- F3.3a: modulo legislativo, eixo C (§22.4) — tramitacao por MOTOR DECLARATIVO (descartados: estados
-- hardcoded e BPMN). O regimento e' DADO (Inv.4): template_tramitacao + template_estado +
-- template_transicao (config TENANT por camara). A transicao avalia um GUARD (expressao DSL booleana) com
-- o MESMO avaliador do motor (disciplina 5, via motor/api/guarda-dsl) e registra em
-- proposicao_transicao_historico (APPEND-ONLY). Versionamento de template por COPIA INTEGRAL
-- (template_pai_id = proveniencia, nao governanca). proposicao_prazo_ativo + action-handlers = F3.3b.

-- ============================ TEMPLATES (config tenant; baixa cardinalidade -> sem particao) ============================
CREATE TABLE IF NOT EXISTS legislativo.template_tramitacao (
  ente_id        uuid NOT NULL,
  id             uuid NOT NULL DEFAULT gen_random_uuid(),
  chave          text NOT NULL,                      -- ex.: 'rito_ordinario'
  versao         integer NOT NULL DEFAULT 1,
  nome           text NOT NULL,
  estado_inicial text NOT NULL,                      -- chave do template_estado inicial
  ativo          boolean NOT NULL DEFAULT true,
  template_pai_id uuid,                              -- proveniencia da copia (nao FK: pode ser de versao podada)
  lote_id        uuid,
  efetivado_em   timestamptz,
  criado_em      timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (ente_id, id),
  UNIQUE (ente_id, chave, versao)
);
--;;
CREATE TABLE IF NOT EXISTS legislativo.template_estado (
  ente_id     uuid NOT NULL,
  id          uuid NOT NULL DEFAULT gen_random_uuid(),
  template_id uuid NOT NULL,
  chave       text NOT NULL,                         -- ex.: 'protocolada', 'em_comissoes', 'arquivada'
  nome        text NOT NULL,
  terminal    boolean NOT NULL DEFAULT false,
  ordem       integer NOT NULL DEFAULT 0,
  lote_id     uuid,
  efetivado_em timestamptz,
  criado_em   timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (ente_id, id),
  UNIQUE (ente_id, template_id, chave),
  FOREIGN KEY (ente_id, template_id) REFERENCES legislativo.template_tramitacao (ente_id, id)
);
--;;
CREATE TABLE IF NOT EXISTS legislativo.template_transicao (
  ente_id     uuid NOT NULL,
  id          uuid NOT NULL DEFAULT gen_random_uuid(),
  template_id uuid NOT NULL,
  de_estado   text NOT NULL,                         -- chave do estado de origem
  para_estado text NOT NULL,                         -- chave do estado destino
  gatilho     text NOT NULL,                         -- ex.: 'despachar', 'parecer_aprovado'
  guarda      text,                                  -- expressao DSL booleana (NULL = sempre permite)
  acao        text,                                  -- identificador de handler em codigo (F3.3b); NULL = so muda estado
  ordem       integer NOT NULL DEFAULT 0,
  lote_id     uuid,
  efetivado_em timestamptz,
  criado_em   timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (ente_id, id),
  FOREIGN KEY (ente_id, template_id) REFERENCES legislativo.template_tramitacao (ente_id, id)
);
--;;
CREATE INDEX IF NOT EXISTS idx_template_estado_template ON legislativo.template_estado (ente_id, template_id);
--;;
-- hot-path da transicao: achar a(s) transicao(oes) por (template, de_estado, gatilho).
CREATE INDEX IF NOT EXISTS idx_template_transicao_origem ON legislativo.template_transicao (ente_id, template_id, de_estado, gatilho);
--;;
-- RLS + staging + grants uniformes (mesmo padrao das tabelas tenant do cadastros).
DO $$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['template_tramitacao','template_estado','template_transicao']
  LOOP
    EXECUTE format('ALTER TABLE legislativo.%I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE legislativo.%I FORCE ROW LEVEL SECURITY', t);
    EXECUTE format('DROP POLICY IF EXISTS tenant_isolation ON legislativo.%I', t);
    EXECUTE format(
      'CREATE POLICY tenant_isolation ON legislativo.%I
         USING (ente_id = NULLIF(current_setting(''app.ente_id'', true), '''')::uuid
                AND (efetivado_em IS NOT NULL OR lote_id = NULLIF(current_setting(''app.ver_lote'', true), '''')::uuid))
         WITH CHECK (ente_id = NULLIF(current_setting(''app.ente_id'', true), '''')::uuid)', t);
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_%s_staging ON legislativo.%I (ente_id, lote_id) WHERE efetivado_em IS NULL', t, t);
    EXECUTE format('GRANT SELECT, INSERT, UPDATE ON legislativo.%I TO oplenario_app', t);
  END LOOP;
END $$;
--;;
-- ============================ HISTORICO de transicao (APPEND-ONLY, volumoso -> particionado) ============================
CREATE TABLE IF NOT EXISTS legislativo.proposicao_transicao_historico (
  ente_id      uuid NOT NULL,
  id           uuid NOT NULL DEFAULT gen_random_uuid(),
  proposicao_id uuid NOT NULL,
  template_id  uuid NOT NULL,                        -- a versao de template que regeu a transicao
  de_estado    text NOT NULL,
  para_estado  text NOT NULL,
  gatilho      text NOT NULL,
  contexto     jsonb,                                -- payload do gatilho (quem disparou, refs, etc.)
  ator_id      uuid,                                 -- identidade que disparou (guard ref)
  lote_id      uuid,
  efetivado_em timestamptz,
  ocorrido_em  timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (ente_id, id),
  FOREIGN KEY (ente_id, proposicao_id) REFERENCES legislativo.proposicoes (ente_id, id)
) PARTITION BY HASH (ente_id);
--;;
CREATE TABLE IF NOT EXISTS legislativo.proposicao_transicao_historico_p0 PARTITION OF legislativo.proposicao_transicao_historico FOR VALUES WITH (MODULUS 8, REMAINDER 0);
--;;
CREATE TABLE IF NOT EXISTS legislativo.proposicao_transicao_historico_p1 PARTITION OF legislativo.proposicao_transicao_historico FOR VALUES WITH (MODULUS 8, REMAINDER 1);
--;;
CREATE TABLE IF NOT EXISTS legislativo.proposicao_transicao_historico_p2 PARTITION OF legislativo.proposicao_transicao_historico FOR VALUES WITH (MODULUS 8, REMAINDER 2);
--;;
CREATE TABLE IF NOT EXISTS legislativo.proposicao_transicao_historico_p3 PARTITION OF legislativo.proposicao_transicao_historico FOR VALUES WITH (MODULUS 8, REMAINDER 3);
--;;
CREATE TABLE IF NOT EXISTS legislativo.proposicao_transicao_historico_p4 PARTITION OF legislativo.proposicao_transicao_historico FOR VALUES WITH (MODULUS 8, REMAINDER 4);
--;;
CREATE TABLE IF NOT EXISTS legislativo.proposicao_transicao_historico_p5 PARTITION OF legislativo.proposicao_transicao_historico FOR VALUES WITH (MODULUS 8, REMAINDER 5);
--;;
CREATE TABLE IF NOT EXISTS legislativo.proposicao_transicao_historico_p6 PARTITION OF legislativo.proposicao_transicao_historico FOR VALUES WITH (MODULUS 8, REMAINDER 6);
--;;
CREATE TABLE IF NOT EXISTS legislativo.proposicao_transicao_historico_p7 PARTITION OF legislativo.proposicao_transicao_historico FOR VALUES WITH (MODULUS 8, REMAINDER 7);
--;;
CREATE INDEX IF NOT EXISTS idx_transicao_hist_proposicao ON legislativo.proposicao_transicao_historico (ente_id, proposicao_id, ocorrido_em);
--;;
ALTER TABLE legislativo.proposicao_transicao_historico ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE legislativo.proposicao_transicao_historico FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON legislativo.proposicao_transicao_historico;
--;;
CREATE POLICY tenant_isolation ON legislativo.proposicao_transicao_historico
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid
         AND (efetivado_em IS NOT NULL OR lote_id = NULLIF(current_setting('app.ver_lote', true), '')::uuid))
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
-- append-only PURO (nivel a): historico de transicao nunca muda nem some (Inv.10) -> sem UPDATE/DELETE.
GRANT SELECT, INSERT ON legislativo.proposicao_transicao_historico TO oplenario_app;
--;;
CREATE TRIGGER trg_transicao_hist_append_only
  BEFORE UPDATE OR DELETE ON legislativo.proposicao_transicao_historico
  FOR EACH ROW EXECUTE FUNCTION shared.imut_append_only();
