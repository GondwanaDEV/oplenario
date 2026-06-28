-- F1.1: modulo cadastros (§22.2 bounded context; silhueta Nubank §22.10). Schema 'cadastros'.
-- Tres categorias de tabela:
--   (A) REFERENCIA/DOMINIO (sem ente_id): municipios (IBGE), tribunal_de_contas (E2), jurisdicao_camara
--       (E1). Dado geografico-institucional uniforme, nao por tenant — sem RLS, read-only p/ o app.
--   (B) ente (1:1 com admin_sistema.ente, ref por GUARD, nao FK cross-schema): o perfil cadastral da
--       Casa (§22.5.3 disc.7 — branding/contatos/municipio). Provisionado, linha unica por ente_id.
--   (C) TENANT multi-linha (vereador/mandato/comissao/...): ente_id + dimensao de staging (efetivado_em/
--       lote_id, fundacao #2 — sao alvo de import de legado) + RLS no padrao da exemplar shared.tenancy_prova.
-- DECISAO (validar na review): NAO ha particao hash(ente_id) nas tabelas cadastrais — cardinalidade e'
-- limitada (~9-55 vereadores/camara; ~80k linhas mesmo em 1.500 entes). O isolamento vem da RLS (ortogonal
-- a particao); particao e' ferramenta de ESCALA p/ as tabelas volumosas de legislativo/sessoes (F3/F4).
-- FKs intra-schema (cadastros->cadastros) sao permitidas e INCLUEM ente_id (integridade same-tenant
-- declarativa); ref cross-modulo (identidade_id -> modulo identidade) cruza por GUARD, sem FK (§22.10).

GRANT USAGE ON SCHEMA cadastros TO oplenario_app;
--;;
-- ============================ (A) REFERENCIA / DOMINIO ============================
-- IBGE: ~5.570 municipios. populacao alimenta a relacao populacao(ente) (§22.7.5). Seed por carga.
CREATE TABLE IF NOT EXISTS cadastros.municipios (
  codigo_ibge text PRIMARY KEY,                 -- 7 digitos IBGE
  nome        text  NOT NULL,
  uf          char(2) NOT NULL,
  capital     boolean NOT NULL DEFAULT false,
  populacao   integer                            -- ultima estimativa IBGE (NULL ate carregar)
);
--;;
CREATE INDEX IF NOT EXISTS idx_municipios_uf ON cadastros.municipios (uf);
--;;
-- E2 (§22.7.9): os 33 Tribunais de Contas como DADO. tipo distingue TCE estadual x TCM-do-estado x TCM-do-municipio.
CREATE TABLE IF NOT EXISTS cadastros.tribunal_de_contas (
  codigo text PRIMARY KEY,                       -- ex.: TCE-CE, TCM-GO, TCM-SP
  nome   text NOT NULL,
  uf     char(2) NOT NULL,
  tipo   text NOT NULL DEFAULT 'estadual'
         CHECK (tipo IN ('estadual', 'tcm_estado', 'tcm_municipio'))
);
--;;
-- E1 (§22.7.9): resolucao camara->tribunal por (UF, municipio?), precedencia municipio->UF. Dominio, sem ente_id.
-- A relacao tribunal_competente(ente) (F1.2) le isto SEM JOIN cross-schema (reconcilia o "UF JOIN" do Eixo B).
CREATE TABLE IF NOT EXISTS cadastros.jurisdicao_camara (
  id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  uf              char(2) NOT NULL,
  municipio_ibge  text,                          -- NULL = regra default da UF; preenchido = override do municipio
  tribunal_codigo text NOT NULL REFERENCES cadastros.tribunal_de_contas (codigo),
  FOREIGN KEY (municipio_ibge) REFERENCES cadastros.municipios (codigo_ibge)
);
--;;
-- armadilha UNIQUE+NULL (municipio NULL = regra de UF): COALESCE p/ que NULL nao escape do unique.
CREATE UNIQUE INDEX IF NOT EXISTS uq_jurisdicao_uf_municipio
  ON cadastros.jurisdicao_camara (uf, COALESCE(municipio_ibge, '*'));
--;;
GRANT SELECT ON cadastros.municipios, cadastros.tribunal_de_contas, cadastros.jurisdicao_camara TO oplenario_app;
--;;
-- ============================ (B) ente (perfil cadastral, 1:1) ============================
CREATE TABLE IF NOT EXISTS cadastros.ente (
  ente_id        uuid PRIMARY KEY,               -- = admin_sistema.ente.ente_id (ref por guard, sem FK cross-schema)
  municipio_ibge text NOT NULL REFERENCES cadastros.municipios (codigo_ibge),
  nome_oficial   text NOT NULL,                  -- "Camara Municipal de Fortaleza"
  nome_curto     text,
  brasao_ref     text,                           -- ponteiro p/ objeto_store (branding, §22.5.3 disc.7)
  criado_em      timestamptz NOT NULL DEFAULT now(),
  atualizado_em  timestamptz NOT NULL DEFAULT now()
);
--;;
ALTER TABLE cadastros.ente ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE cadastros.ente FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON cadastros.ente;
--;;
-- ente e' 1:1 provisionado (nao alvo de import multi-linha) -> RLS so por ente_id, sem clausula de staging.
CREATE POLICY tenant_isolation ON cadastros.ente
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid)
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
GRANT SELECT, INSERT, UPDATE ON cadastros.ente TO oplenario_app;
--;;
-- ============================ (C) TENANT multi-linha (com staging) ============================
-- legislatura: o ciclo de 4 anos. numero/ano sao ordinais humanos (nao gapless).
CREATE TABLE IF NOT EXISTS cadastros.legislatura (
  ente_id      uuid NOT NULL,
  id           uuid NOT NULL DEFAULT gen_random_uuid(),
  numero       integer NOT NULL,
  ano_inicio   integer NOT NULL,
  ano_fim      integer NOT NULL,
  vigente      boolean NOT NULL DEFAULT false,
  lote_id      uuid,
  efetivado_em timestamptz,
  criado_em    timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (ente_id, id),
  UNIQUE (ente_id, numero)
);
--;;
-- sessao legislativa: a sessao anual (1..4) dentro da legislatura.
CREATE TABLE IF NOT EXISTS cadastros.sessao_legislativa (
  ente_id        uuid NOT NULL,
  id             uuid NOT NULL DEFAULT gen_random_uuid(),
  legislatura_id uuid NOT NULL,
  numero         integer NOT NULL,               -- 1..4
  ano            integer NOT NULL,
  data_inicio    date,
  data_fim       date,
  lote_id        uuid,
  efetivado_em   timestamptz,
  criado_em      timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (ente_id, id),
  FOREIGN KEY (ente_id, legislatura_id) REFERENCES cadastros.legislatura (ente_id, id)
);
--;;
-- vereador: o registro institucional cadastral (a pessoa/identidade vive no modulo identidade, ref por guard).
CREATE TABLE IF NOT EXISTS cadastros.vereador (
  ente_id         uuid NOT NULL,
  id              uuid NOT NULL DEFAULT gen_random_uuid(),
  identidade_id   uuid,                           -- GUARD ref ao modulo identidade (sem FK cross-schema)
  nome            text NOT NULL,
  nome_parlamentar text,
  lote_id         uuid,
  efetivado_em    timestamptz,
  criado_em       timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (ente_id, id)
);
--;;
-- mandato: entidade com ESTADO (§22.5 eixo C). Cascata de papeis temporais via tem_mandato_vigente.
CREATE TABLE IF NOT EXISTS cadastros.mandato (
  ente_id         uuid NOT NULL,
  id              uuid NOT NULL DEFAULT gen_random_uuid(),
  vereador_id     uuid NOT NULL,
  legislatura_id  uuid NOT NULL,
  partido         text,
  estado          text NOT NULL DEFAULT 'vigente'
                  CHECK (estado IN ('vigente','licenciado','cassado','renunciado','falecido','concluido')),
  natureza        text NOT NULL DEFAULT 'titular'
                  CHECK (natureza IN ('titular','suplencia')),
  vigencia_inicio date NOT NULL,
  vigencia_fim    date,                           -- NULL = em aberto
  fim_efetivo     date,                           -- preenchido em cassacao/renuncia/falecimento
  lote_id         uuid,
  efetivado_em    timestamptz,
  criado_em       timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (ente_id, id),
  FOREIGN KEY (ente_id, vereador_id)    REFERENCES cadastros.vereador (ente_id, id),
  FOREIGN KEY (ente_id, legislatura_id) REFERENCES cadastros.legislatura (ente_id, id)
);
--;;
CREATE INDEX IF NOT EXISTS idx_mandato_vereador ON cadastros.mandato (ente_id, vereador_id);
--;;
-- mandato_licenca: licenca com vinculo ao mandato do suplente em exercicio (§22.5 eixo C).
CREATE TABLE IF NOT EXISTS cadastros.mandato_licenca (
  ente_id             uuid NOT NULL,
  id                  uuid NOT NULL DEFAULT gen_random_uuid(),
  mandato_id          uuid NOT NULL,
  mandato_suplente_id uuid,                       -- mandato (natureza='suplencia') que assume na licenca
  inicio              date NOT NULL,
  fim                 date,
  motivo              text,
  lote_id             uuid,
  efetivado_em        timestamptz,
  criado_em           timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (ente_id, id),
  FOREIGN KEY (ente_id, mandato_id)          REFERENCES cadastros.mandato (ente_id, id),
  FOREIGN KEY (ente_id, mandato_suplente_id) REFERENCES cadastros.mandato (ente_id, id)
);
--;;
-- suplencia: ordem de suplentes por (legislatura, partido).
CREATE TABLE IF NOT EXISTS cadastros.suplencia (
  ente_id        uuid NOT NULL,
  id             uuid NOT NULL DEFAULT gen_random_uuid(),
  legislatura_id uuid NOT NULL,
  partido        text NOT NULL,
  vereador_id    uuid NOT NULL,
  ordem          integer NOT NULL,
  lote_id        uuid,
  efetivado_em   timestamptz,
  criado_em      timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (ente_id, id),
  FOREIGN KEY (ente_id, legislatura_id) REFERENCES cadastros.legislatura (ente_id, id),
  FOREIGN KEY (ente_id, vereador_id)    REFERENCES cadastros.vereador (ente_id, id),
  UNIQUE (ente_id, legislatura_id, partido, ordem)
);
--;;
-- comissao: a Mesa Diretora e' tipo='mesa' (comissao especial com cargos nomeados; §22.5 eixo C).
CREATE TABLE IF NOT EXISTS cadastros.comissao (
  ente_id         uuid NOT NULL,
  id              uuid NOT NULL DEFAULT gen_random_uuid(),
  nome            text NOT NULL,
  tipo            text NOT NULL DEFAULT 'permanente'
                  CHECK (tipo IN ('permanente','temporaria','especial','cpi','mesa')),
  legislatura_id  uuid,
  vigencia_inicio date NOT NULL,
  vigencia_fim    date,
  lote_id         uuid,
  efetivado_em    timestamptz,
  criado_em       timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (ente_id, id),
  FOREIGN KEY (ente_id, legislatura_id) REFERENCES cadastros.legislatura (ente_id, id)
);
--;;
-- comissao_cargo: cargos nomeados (presidente/vice/relator/secretario; na Mesa: presidente/1_secretario/...).
CREATE TABLE IF NOT EXISTS cadastros.comissao_cargo (
  ente_id         uuid NOT NULL,
  id              uuid NOT NULL DEFAULT gen_random_uuid(),
  comissao_id     uuid NOT NULL,
  vereador_id     uuid NOT NULL,
  cargo           text NOT NULL,
  vigencia_inicio date NOT NULL,
  vigencia_fim    date,
  lote_id         uuid,
  efetivado_em    timestamptz,
  criado_em       timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (ente_id, id),
  FOREIGN KEY (ente_id, comissao_id) REFERENCES cadastros.comissao (ente_id, id),
  FOREIGN KEY (ente_id, vereador_id) REFERENCES cadastros.vereador (ente_id, id)
);
--;;
CREATE INDEX IF NOT EXISTS idx_comissao_cargo_comissao ON cadastros.comissao_cargo (ente_id, comissao_id);
--;;
-- comissao_membro: membership (tabela de juncao §22.5.3 disc.2).
CREATE TABLE IF NOT EXISTS cadastros.comissao_membro (
  ente_id         uuid NOT NULL,
  id              uuid NOT NULL DEFAULT gen_random_uuid(),
  comissao_id     uuid NOT NULL,
  vereador_id     uuid NOT NULL,
  vigencia_inicio date NOT NULL,
  vigencia_fim    date,
  lote_id         uuid,
  efetivado_em    timestamptz,
  criado_em       timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (ente_id, id),
  FOREIGN KEY (ente_id, comissao_id) REFERENCES cadastros.comissao (ente_id, id),
  FOREIGN KEY (ente_id, vereador_id) REFERENCES cadastros.vereador (ente_id, id)
);
--;;
CREATE INDEX IF NOT EXISTS idx_comissao_membro_comissao ON cadastros.comissao_membro (ente_id, comissao_id);
--;;
-- INTEGRIDADE ANTI-FAIL-OPEN (review F1.2 #1): no maximo UMA Mesa Diretora EFETIVADA ativa por ente num
-- dado periodo. Sem isto, duas linhas tipo='mesa' com vigencia sobreposta fariam mesa-vigente-id (e logo
-- presidente-da-mesa?/quem_exerce_presidencia) escolher a Mesa ERRADA por heuristica -> concessao indevida.
-- Staging (efetivado_em IS NULL) fica de fora ate efetivar. Requer btree_gist p/ o '=' no GiST.
CREATE EXTENSION IF NOT EXISTS btree_gist;
--;;
ALTER TABLE cadastros.comissao ADD CONSTRAINT uq_uma_mesa_ativa
  EXCLUDE USING gist (
    ente_id WITH =,
    daterange(vigencia_inicio, COALESCE(vigencia_fim, 'infinity'::date), '[]') WITH &&
  ) WHERE (tipo = 'mesa' AND efetivado_em IS NOT NULL);
--;;
-- indices do LADO FILHO das FKs (o PG nao os cria sozinho): evitam Seq Scan no check de FK e nos joins
-- filho->pai (review F1.1 M1). Colunas FK anulaveis usam indice PARCIAL (nao indexa NULL).
CREATE INDEX IF NOT EXISTS idx_sessao_leg_legislatura  ON cadastros.sessao_legislativa (ente_id, legislatura_id);
--;;
CREATE INDEX IF NOT EXISTS idx_mandato_legislatura     ON cadastros.mandato (ente_id, legislatura_id);
--;;
CREATE INDEX IF NOT EXISTS idx_mandato_licenca_mandato ON cadastros.mandato_licenca (ente_id, mandato_id);
--;;
CREATE INDEX IF NOT EXISTS idx_mandato_licenca_suplente ON cadastros.mandato_licenca (ente_id, mandato_suplente_id) WHERE mandato_suplente_id IS NOT NULL;
--;;
CREATE INDEX IF NOT EXISTS idx_suplencia_legislatura   ON cadastros.suplencia (ente_id, legislatura_id);
--;;
CREATE INDEX IF NOT EXISTS idx_suplencia_vereador      ON cadastros.suplencia (ente_id, vereador_id);
--;;
CREATE INDEX IF NOT EXISTS idx_comissao_legislatura    ON cadastros.comissao (ente_id, legislatura_id) WHERE legislatura_id IS NOT NULL;
--;;
CREATE INDEX IF NOT EXISTS idx_comissao_cargo_vereador  ON cadastros.comissao_cargo (ente_id, vereador_id);
--;;
CREATE INDEX IF NOT EXISTS idx_comissao_membro_vereador ON cadastros.comissao_membro (ente_id, vereador_id);
--;;
-- hot-path da F2 (quem_exerce_presidencia consulta a Mesa vigente a cada checagem de autorizacao):
-- indice parcial das comissoes tipo='mesa' por vigencia.
CREATE INDEX IF NOT EXISTS idx_comissao_mesa_vigente ON cadastros.comissao (ente_id, vigencia_inicio DESC) WHERE tipo = 'mesa';
--;;
-- RLS + staging + grants uniformes nas tabelas tenant multi-linha (DRY: mesmo padrao da exemplar
-- shared.tenancy_prova — FORCE RLS, tenant_isolation com clausula de staging (efetivado_em/lote_id),
-- indice de staging das nao-efetivadas, grants SELECT/INSERT/UPDATE sem DELETE (Inv.10)).
DO $$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['legislatura','sessao_legislativa','vereador','mandato','mandato_licenca',
                           'suplencia','comissao','comissao_cargo','comissao_membro']
  LOOP
    EXECUTE format('ALTER TABLE cadastros.%I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE cadastros.%I FORCE ROW LEVEL SECURITY', t);
    EXECUTE format('DROP POLICY IF EXISTS tenant_isolation ON cadastros.%I', t);
    EXECUTE format(
      'CREATE POLICY tenant_isolation ON cadastros.%I
         USING (ente_id = NULLIF(current_setting(''app.ente_id'', true), '''')::uuid
                AND (efetivado_em IS NOT NULL
                     OR lote_id = NULLIF(current_setting(''app.ver_lote'', true), '''')::uuid))
         WITH CHECK (ente_id = NULLIF(current_setting(''app.ente_id'', true), '''')::uuid)', t);
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_%s_staging ON cadastros.%I (ente_id, lote_id) WHERE efetivado_em IS NULL', t, t);
    EXECUTE format('GRANT SELECT, INSERT, UPDATE ON cadastros.%I TO oplenario_app', t);
  END LOOP;
END $$;
