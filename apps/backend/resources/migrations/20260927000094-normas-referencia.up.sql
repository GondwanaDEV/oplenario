-- Faixa B / B.4 da Track IA (docs/25 Eixo 7, ADR-0011) — o CONHECIMENTO NORMATIVO DE REFERENCIA, por dispositivo.
-- Modulo novo `normas`: a norma (identidade), cada VERSAO importada (o texto como chegou + o estado da conferencia) e os
-- DISPOSITIVOS de cada versao (artigo, paragrafo, inciso, alinea, item), cada um com endereco estavel e rotulo de
-- citacao. Nada vale antes de uma pessoa conferir (Eixo 7.3): a versao nasce 'em_conferencia' e so' vira 'vigente'
-- pela conferencia; a anterior passa a 'substituida'. Os dispositivos sao append-only.
--
-- Camadas (Eixo 7.1, a taxonomia do motor): federal e estadual sao curadoria do produto, SEM ente_id, visiveis a todas
-- as Casas; municipal (LOM, leis) e casa (Regimento Interno, resolucoes) tem o ente_id da Casa que as conferiu.
-- A LOM carrega o municipio_ibge: e' do Municipio, e quando a Prefeitura existir a visibilidade passa a ser por ele.
CREATE SCHEMA IF NOT EXISTS normas;
--;;
GRANT USAGE ON SCHEMA normas TO oplenario_app;
--;;
CREATE TABLE IF NOT EXISTS normas.norma (
  id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  ente_id         uuid,
  camada          text NOT NULL CHECK (camada IN ('federal', 'estadual', 'municipal', 'casa')),
  uf              text,
  municipio_ibge  text,
  especie         text NOT NULL CHECK (especie IN ('constituicao_federal', 'constituicao_estadual', 'lei_organica',
                                                  'regimento_interno', 'lei_complementar', 'lei', 'resolucao',
                                                  'decreto', 'outra')),
  numero          text,
  data            date,
  titulo          text NOT NULL CHECK (length(btrim(titulo)) > 0 AND length(titulo) <= 300),
  criada_por      uuid,
  criada_em       timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT norma_camada_dono CHECK ((camada IN ('federal', 'estadual')) = (ente_id IS NULL)),
  CONSTRAINT norma_estadual_uf CHECK (camada <> 'estadual' OR uf IS NOT NULL),
  CONSTRAINT norma_municipal_municipio CHECK (camada <> 'municipal' OR municipio_ibge IS NOT NULL)
);
--;;
-- uma norma por especie + numero na mesma Casa (uma LOM, um Regimento; leis pelo numero)
CREATE UNIQUE INDEX IF NOT EXISTS uq_norma_identidade
  ON normas.norma (COALESCE(ente_id, '00000000-0000-0000-0000-000000000000'::uuid), COALESCE(uf, ''), especie,
                   COALESCE(numero, ''));
--;;
CREATE TABLE IF NOT EXISTS normas.versao (
  id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  norma_id        uuid NOT NULL REFERENCES normas.norma (id),
  ente_id         uuid,
  estado          text NOT NULL CHECK (estado IN ('em_conferencia', 'vigente', 'substituida', 'descartada')),
  consolidada_ate date,
  fonte           text NOT NULL CHECK (length(btrim(fonte)) > 0),
  texto           text NOT NULL,
  texto_sha256    text NOT NULL,
  n_dispositivos  integer NOT NULL,
  alertas         jsonb NOT NULL DEFAULT '[]'::jsonb,
  enviada_por     uuid,
  enviada_em      timestamptz NOT NULL DEFAULT now(),
  decidida_por    uuid,
  decidida_em     timestamptz,
  CONSTRAINT versao_decidida CHECK (estado = 'em_conferencia' OR decidida_em IS NOT NULL)
);
--;;
CREATE UNIQUE INDEX IF NOT EXISTS uq_versao_vigente ON normas.versao (norma_id) WHERE estado = 'vigente';
--;;
CREATE UNIQUE INDEX IF NOT EXISTS uq_versao_em_conferencia ON normas.versao (norma_id) WHERE estado = 'em_conferencia';
--;;
CREATE TABLE IF NOT EXISTS normas.dispositivo (
  versao_id   uuid NOT NULL REFERENCES normas.versao (id),
  ente_id     uuid,
  endereco    text NOT NULL CHECK (endereco ~ '^[a-z0-9_\-]+$'),
  rotulo      text NOT NULL,
  tipo        text NOT NULL CHECK (tipo IN ('preambulo', 'artigo', 'paragrafo', 'inciso', 'alinea', 'item')),
  pai         text,
  ordem       integer NOT NULL,
  texto       text NOT NULL,
  agrupador   text,
  PRIMARY KEY (versao_id, endereco)
);
--;;
CREATE INDEX IF NOT EXISTS idx_dispositivo_ordem ON normas.dispositivo (versao_id, ordem);
--;;
CREATE TRIGGER trg_dispositivo_append_only
  BEFORE UPDATE OR DELETE ON normas.dispositivo
  FOR EACH ROW EXECUTE FUNCTION shared.imut_append_only();
--;;
ALTER TABLE normas.norma ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE normas.norma FORCE ROW LEVEL SECURITY;
--;;
CREATE POLICY referencia_ou_da_casa ON normas.norma
  USING (ente_id IS NULL OR ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid)
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
ALTER TABLE normas.versao ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE normas.versao FORCE ROW LEVEL SECURITY;
--;;
CREATE POLICY referencia_ou_da_casa ON normas.versao
  USING (ente_id IS NULL OR ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid)
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
ALTER TABLE normas.dispositivo ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE normas.dispositivo FORCE ROW LEVEL SECURITY;
--;;
CREATE POLICY referencia_ou_da_casa ON normas.dispositivo
  USING (ente_id IS NULL OR ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid)
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
GRANT SELECT, INSERT ON normas.norma TO oplenario_app;
--;;
GRANT SELECT, INSERT, UPDATE (estado, decidida_por, decidida_em) ON normas.versao TO oplenario_app;
--;;
GRANT SELECT, INSERT ON normas.dispositivo TO oplenario_app;
