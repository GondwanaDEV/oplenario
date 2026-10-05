-- O catalogo do motor passa a ser LIDO em runtime — e a migration 20260620000009 (F1.0) tinha deixado esse
-- privilegio para "quando a F2 ler de fato". A leitura chegou com o gatilho das obrigacoes (ADR-0021,
-- `gatilho_compliance.clj`) e o grant nao veio junto: com o role de runtime (`oplenario_pool`, nao-dono) o gatilho
-- morria em `permission denied for table template_compliance`. Como ele nunca derruba a leitura do painel nem o
-- ato, a falha era muda: nenhuma obrigacao era avaliada, nenhuma remessa aceita cumpria a competencia. So' apareceu
-- no log do CI (job t3-e2e), que sobe o app como `oplenario_pool`; a suite roda como o dono do banco e nao via.
--
-- PRIVILEGIO MINIMO, por papel (mesmo split do outbox na F1.0 e do `oplenario_id_resolver` na F1.3):
--   - LER o catalogo (dominio `oplenario_app`, de quem o pool herda): as tres tabelas de DOMINIO que a avaliacao le
--     — `template_compliance` (a regra), `prazo_dominio_vigente` (builtin `prazo_vigente`) e `calendario_feriado`
--     (lido em toda avaliacao). Sem ente_id e sem dado de Casa: regra, prazo legal e feriado sao lei publica.
--   - GRAVAR versao de regra: so' o papel `oplenario_motor_catalogo` (NOLOGIN), do qual o pool herda. O gatilho
--     cataloga a regra na conexao CRUA do pool; DENTRO de com-tenant* (SET LOCAL ROLE oplenario_app) nao ha' esse
--     privilegio — a definicao vale para TODAS as Casas, e codigo rodando na sessao de uma Casa nao a grava.
--     So' INSERT: versao de regra e' copia integral (§22.4 eixo C); sem UPDATE e sem DELETE em runtime.
--   - `registry_catalogo_versao` segue sem grant: nada a le em runtime.
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'oplenario_motor_catalogo') THEN
    CREATE ROLE oplenario_motor_catalogo NOLOGIN NOBYPASSRLS;
  END IF;
END $$;
--;;
GRANT oplenario_motor_catalogo TO oplenario_pool;
--;;
GRANT USAGE ON SCHEMA motor TO oplenario_motor_catalogo;
--;;
GRANT INSERT ON motor.template_compliance TO oplenario_motor_catalogo;
--;;
GRANT SELECT ON motor.template_compliance TO oplenario_app;
--;;
GRANT SELECT ON motor.prazo_dominio_vigente TO oplenario_app;
--;;
GRANT SELECT ON motor.calendario_feriado TO oplenario_app;
