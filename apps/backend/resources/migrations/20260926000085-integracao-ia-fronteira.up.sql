-- Faixa A / A.3 da Track IA — a FRONTEIRA core <-> IA (ADR-0008, §22.3.3). Tres pecas:
--
-- (1) `integracao_ia.evento_saida` — o FEED de eventos de integracao core -> IA. Um consumidor do outbox PROMOVE
--     eventos de dominio escolhidos (lista revisada em codigo) a eventos de integracao versionados; o satelite
--     PUXA pelo `seq` (cursor). Supratenant como `shared.outbox`: sem RLS, lido so' pelas rotas de servico
--     `/integracao/ia/*` (segredo core<->satelite). `chave` = idempotencia (promover duas vezes nao duplica).
--
-- (2) `integracao_ia.evento_entrada` — a CAIXA DE ENTRADA IA -> core: um registro por evento aceito, gravado na
--     MESMA tx do efeito (dedup + auditoria do que chegou, §22.3.3). Reenvio com a mesma `chave` nao repete
--     o efeito.
--
-- (3) `sessoes.transcricao_sessao` — o PONTEIRO da transcricao no core (§22.6: "metadata leve"; o texto vive
--     na IA, §22.3.4). Uma linha por conclusao OU falha de uma versao de transcricao de um segmento: situacao,
--     metricas, modelos usados. Append-only (Inv. 10): transcrever de novo = linha nova.
CREATE SCHEMA IF NOT EXISTS integracao_ia;
--;;
GRANT USAGE ON SCHEMA integracao_ia TO oplenario_app;
--;;
CREATE TABLE IF NOT EXISTS integracao_ia.evento_saida (
  seq        bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  ente_id    uuid NOT NULL,
  tipo       text NOT NULL,
  versao     integer NOT NULL CHECK (versao > 0),
  chave      text NOT NULL UNIQUE,
  payload    jsonb NOT NULL,
  criado_em  timestamptz NOT NULL DEFAULT now()
);
--;;
CREATE TRIGGER trg_evento_saida_append_only
  BEFORE UPDATE OR DELETE ON integracao_ia.evento_saida
  FOR EACH ROW EXECUTE FUNCTION shared.imut_append_only();
--;;
CREATE TABLE IF NOT EXISTS integracao_ia.evento_entrada (
  id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  ente_id     uuid NOT NULL,
  tipo        text NOT NULL,
  versao      integer NOT NULL CHECK (versao > 0),
  chave       text NOT NULL UNIQUE,
  correlation_id text,
  payload     jsonb NOT NULL,
  recebido_em timestamptz NOT NULL DEFAULT now()
);
--;;
GRANT SELECT, INSERT ON integracao_ia.evento_entrada TO oplenario_app;
--;;
CREATE TRIGGER trg_evento_entrada_append_only
  BEFORE UPDATE OR DELETE ON integracao_ia.evento_entrada
  FOR EACH ROW EXECUTE FUNCTION shared.imut_append_only();
--;;
CREATE TABLE IF NOT EXISTS sessoes.transcricao_sessao (
  ente_id        uuid NOT NULL,
  id             uuid NOT NULL DEFAULT gen_random_uuid(),
  sessao_id      uuid NOT NULL,
  segmento_id    uuid NOT NULL,
  situacao       text NOT NULL CHECK (situacao IN ('concluida', 'falhou')),
  transcricao_id uuid,                        -- o id da transcricao NA IA (concluida)
  versao         integer,                     -- versao da transcricao na IA (reprocessar = versao nova)
  idioma         text,
  duracao_s      numeric(10, 2),
  n_trechos      integer,
  cobertura_atribuida numeric(5, 4),          -- fracao da fala com orador atribuido pelo Caminho C (0..1)
  modelo_asr     text,
  modelo_diarizacao text,
  categoria_erro text,                        -- falhou: uma das 6 categorias do §22.3.5
  detalhe_erro   text,
  retentavel     boolean,
  ocorrido_em    timestamptz NOT NULL,        -- quando a IA concluiu/falhou
  recebido_em    timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (ente_id, id),
  FOREIGN KEY (ente_id, sessao_id) REFERENCES sessoes.sessao (ente_id, id),
  FOREIGN KEY (ente_id, segmento_id) REFERENCES sessoes.gravacao_segmento (ente_id, id),
  CONSTRAINT transcricao_concluida_completa CHECK (
    situacao <> 'concluida' OR (transcricao_id IS NOT NULL AND versao IS NOT NULL)),
  CONSTRAINT transcricao_falha_categorizada CHECK (situacao <> 'falhou' OR categoria_erro IS NOT NULL)
);
--;;
CREATE INDEX IF NOT EXISTS idx_transcricao_sessao
  ON sessoes.transcricao_sessao (ente_id, sessao_id, ocorrido_em);
--;;
ALTER TABLE sessoes.transcricao_sessao ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE sessoes.transcricao_sessao FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON sessoes.transcricao_sessao;
--;;
CREATE POLICY tenant_isolation ON sessoes.transcricao_sessao
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid)
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
GRANT SELECT, INSERT ON sessoes.transcricao_sessao TO oplenario_app;
--;;
CREATE TRIGGER trg_transcricao_sessao_append_only
  BEFORE UPDATE OR DELETE ON sessoes.transcricao_sessao
  FOR EACH ROW EXECUTE FUNCTION shared.imut_append_only();
