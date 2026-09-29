-- ADR-0017 — a TRILHA DE AUDITORIA da Casa (§16.1, §22.5 Eixos E e G, Invariante 10). Duas tabelas por Casa (RLS):
--
-- (1) `auditoria.registro` — um registro por ato: escrita (permitida ou falha), negacao por politica, entrada
--     (login) e leitura sensivel (a propria trilha). Quem, o que (acao do catalogo), sobre o que, decisao, quando e
--     de onde. NUNCA conteudo: ids, rotulos e NOMES de campos. Cada registro sela o anterior DA MESMA CASA:
--     selo = sha256(selo_anterior | campos canonicos). O IP fica FORA do selo: e' o unico campo que muda — vira
--     NULL depois de 6 meses (Marco Civil art. 15), e o trigger so' deixa essa mudanca passar. Particionada por mes
--     (ocorrido_em) desde o inicio; `auditoria.garantir_particoes` cria os meses seguintes (SECURITY DEFINER: o
--     role da aplicacao nao cria tabela).
--
-- (2) `auditoria.selo_diario` — a cabeca da corrente de cada Casa ao fim de cada dia com registro. E' o que o
--     portal publica e o que a Operacao ancora na corrente DELA (outra esfera): reescrever a trilha passa a exigir
--     reescrever tambem o que ja' saiu.
CREATE SCHEMA IF NOT EXISTS auditoria;
--;;
GRANT USAGE ON SCHEMA auditoria TO oplenario_app;
--;;
CREATE TABLE IF NOT EXISTS auditoria.registro (
  ente_id        uuid        NOT NULL,
  seq            bigint      NOT NULL CHECK (seq > 0),       -- posicao na corrente da Casa (1..n, sem buraco)
  id             uuid        NOT NULL DEFAULT gen_random_uuid(),
  ocorrido_em    timestamptz NOT NULL,
  ator_tipo      text        NOT NULL CHECK (ator_tipo IN ('pessoa', 'cidadao', 'agente')),
  identidade_id  uuid,                                         -- NULL = agente institucional (sem pessoa)
  papeis         text[]      NOT NULL DEFAULT '{}',
  via_agente     text,                                         -- "Fulano, via agente X" (§22.11)
  acao           text        NOT NULL,                         -- o route-name (vocabulario do catalogo, ADR-0009)
  classe         text        NOT NULL CHECK (classe IN ('escrita', 'negacao', 'entrada', 'leitura_sensivel')),
  recurso_tipo   text,
  recurso_id     text,
  rotulo         text,                                         -- "PL 118/2026" — nunca texto de materia
  campos         text[]      NOT NULL DEFAULT '{}',            -- NOMES dos campos alterados (o antes/depois e' da versao)
  decisao        text        NOT NULL CHECK (decisao IN ('permitido', 'negado', 'falhou')),
  status_http    integer,
  canal          text        NOT NULL,
  ip             inet,                                         -- fora do selo; NULL depois de 6 meses
  detalhe        jsonb       NOT NULL DEFAULT '{}',
  selo_anterior  text        NOT NULL,
  selo           text        NOT NULL,
  PRIMARY KEY (ente_id, seq, ocorrido_em)
) PARTITION BY RANGE (ocorrido_em);
--;;
CREATE TABLE IF NOT EXISTS auditoria.registro_fora_de_mes PARTITION OF auditoria.registro DEFAULT;
--;;
CREATE INDEX IF NOT EXISTS idx_registro_cabeca ON auditoria.registro (ente_id, seq DESC);
--;;
CREATE INDEX IF NOT EXISTS idx_registro_periodo ON auditoria.registro (ente_id, ocorrido_em DESC);
--;;
-- o mes de `instante` e os `meses_a_frente` seguintes. Idempotente. Falha num mes (ex.: a particao DEFAULT ja' tem
-- linha daquele mes) nao derruba os outros: o registro cai na DEFAULT e segue selado — nada se perde.
CREATE OR REPLACE FUNCTION auditoria.garantir_particoes(instante timestamptz, meses_a_frente integer)
RETURNS integer LANGUAGE plpgsql SECURITY DEFINER SET search_path = auditoria, pg_temp AS $$
DECLARE
  inicio date := date_trunc('month', instante AT TIME ZONE 'UTC')::date;
  mes    date;
  nome   text;
  criadas integer := 0;
BEGIN
  FOR i IN 0..GREATEST(meses_a_frente, 0) LOOP
    mes  := (inicio + make_interval(months => i))::date;
    nome := 'registro_' || to_char(mes, 'YYYY_MM');
    IF to_regclass('auditoria.' || nome) IS NULL THEN
      BEGIN
        EXECUTE format('CREATE TABLE auditoria.%I PARTITION OF auditoria.registro FOR VALUES FROM (%L) TO (%L)',
                       nome,
                       make_timestamptz(extract(year FROM mes)::int, extract(month FROM mes)::int, 1, 0, 0, 0, 'UTC'),
                       make_timestamptz(extract(year FROM (mes + interval '1 month'))::int,
                                        extract(month FROM (mes + interval '1 month'))::int, 1, 0, 0, 0, 'UTC'));
        criadas := criadas + 1;
      EXCEPTION WHEN others THEN
        RAISE WARNING 'auditoria: particao % nao criada: %', nome, SQLERRM;
      END;
    END IF;
  END LOOP;
  RETURN criadas;
END;
$$;
--;;
REVOKE ALL ON FUNCTION auditoria.garantir_particoes(timestamptz, integer) FROM PUBLIC;
--;;
GRANT EXECUTE ON FUNCTION auditoria.garantir_particoes(timestamptz, integer) TO oplenario_app;
--;;
SELECT auditoria.garantir_particoes(now(), 3);
--;;
-- append-only, com UMA excecao: o IP pode virar NULL (e nada mais muda). DELETE nunca.
CREATE OR REPLACE FUNCTION auditoria.imut_registro() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'UPDATE' AND NEW.ip IS NULL
     AND (to_jsonb(NEW) - 'ip') = (to_jsonb(OLD) - 'ip') THEN
    RETURN NEW;
  END IF;
  RAISE EXCEPTION 'imutabilidade (a) append-only: % proibido em % (so'' o IP pode ser anulado)', TG_OP, TG_TABLE_NAME
    USING ERRCODE = 'check_violation';
END;
$$;
--;;
CREATE TRIGGER trg_registro_append_only
  BEFORE UPDATE OR DELETE ON auditoria.registro
  FOR EACH ROW EXECUTE FUNCTION auditoria.imut_registro();
--;;
ALTER TABLE auditoria.registro ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE auditoria.registro FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON auditoria.registro;
--;;
CREATE POLICY tenant_isolation ON auditoria.registro
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid)
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
GRANT SELECT, INSERT ON auditoria.registro TO oplenario_app;
--;;
GRANT UPDATE (ip) ON auditoria.registro TO oplenario_app;
--;;
CREATE TABLE IF NOT EXISTS auditoria.selo_diario (
  ente_id        uuid        NOT NULL,
  dia            date        NOT NULL,                         -- o dia civil da Casa (America/Fortaleza)
  seq            bigint      NOT NULL,                         -- a cabeca da corrente ao fim do dia
  selo           text        NOT NULL,
  registrado_em  timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (ente_id, dia)
);
--;;
CREATE TRIGGER trg_selo_diario_append_only
  BEFORE UPDATE OR DELETE ON auditoria.selo_diario
  FOR EACH ROW EXECUTE FUNCTION shared.imut_append_only();
--;;
ALTER TABLE auditoria.selo_diario ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE auditoria.selo_diario FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON auditoria.selo_diario;
--;;
CREATE POLICY tenant_isolation ON auditoria.selo_diario
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid)
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
GRANT SELECT, INSERT ON auditoria.selo_diario TO oplenario_app;
