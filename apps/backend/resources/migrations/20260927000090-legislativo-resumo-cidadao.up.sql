-- Faixa A / A.8 da Track IA — o RESUMO CIDADÃO de uma proposição (§22.3.4: o rascunho vive na IA; o publicado vive no
-- core e vai para o portal). Duas tabelas, as duas append-only:
--
-- (1) `legislativo.resumo_rascunho` — o PONTEIRO do rascunho que a IA redigiu (mesmo molde de sessoes.ata_rascunho):
--     'pronto' (ResumoCidadaoPronto: o id do rascunho NA IA, a versao do texto que ele resume, modelo, prompt e os sinais
--     da Camada de Confiança) ou 'falhou' (ResumoFalhou, categoria do §22.3.5). A IA redige sozinha a cada versao nova
--     do texto; o rascunho atual de uma proposicao e' a linha mais recente dela.
--
-- (2) `legislativo.resumo_cidadao` — as VERSOES PUBLICADAS pela secretaria. Publicar de novo = versao nova; a anterior
--     fica guardada. `texto_base_sha256` diz qual versao do texto da proposicao o resumo descreve (o portal e a
--     secretaria sabem quando ele ficou para tras). Com `rascunho_id`, a versao partiu da IA e carrega modelo e prompt
--     vindos do ponteiro, nunca do cliente.
CREATE TABLE IF NOT EXISTS legislativo.resumo_rascunho (
  ente_id           uuid NOT NULL,
  id                uuid NOT NULL DEFAULT gen_random_uuid(),
  proposicao_id     uuid NOT NULL,
  situacao          text NOT NULL CHECK (situacao IN ('pronto', 'falhou')),
  rascunho_id       uuid,                        -- o id do rascunho NA IA (pronto)
  texto_base_sha256 text,
  modelo_llm_id     text,
  prompt_versao     text,
  incerteza         text CHECK (incerteza IS NULL OR incerteza IN ('normal', 'revisar_com_atencao')),
  n_citacoes            integer,
  n_citacoes_conferidas integer,
  n_paragrafos_sem_fonte integer,
  categoria_erro    text,
  detalhe_erro      text,
  retentavel        boolean,
  ocorrido_em       timestamptz NOT NULL,
  registrado_em     timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (ente_id, id),
  FOREIGN KEY (ente_id, proposicao_id) REFERENCES legislativo.proposicoes (ente_id, id),
  CONSTRAINT resumo_pronto_completo CHECK (
    situacao <> 'pronto' OR (rascunho_id IS NOT NULL AND texto_base_sha256 IS NOT NULL AND modelo_llm_id IS NOT NULL
                             AND prompt_versao IS NOT NULL AND incerteza IS NOT NULL)),
  CONSTRAINT resumo_falha_categorizada CHECK (situacao <> 'falhou' OR categoria_erro IS NOT NULL)
);
--;;
CREATE UNIQUE INDEX IF NOT EXISTS uq_resumo_rascunho_ia ON legislativo.resumo_rascunho (ente_id, rascunho_id)
  WHERE rascunho_id IS NOT NULL;
--;;
CREATE INDEX IF NOT EXISTS idx_resumo_rascunho_prop ON legislativo.resumo_rascunho (ente_id, proposicao_id, ocorrido_em);
--;;
CREATE TABLE IF NOT EXISTS legislativo.resumo_cidadao (
  ente_id           uuid NOT NULL,
  id                uuid NOT NULL DEFAULT gen_random_uuid(),
  proposicao_id     uuid NOT NULL,
  versao            integer NOT NULL CHECK (versao > 0),
  texto             text NOT NULL CHECK (length(btrim(texto)) > 0 AND length(texto) <= 4000),
  conteudo_sha256   text NOT NULL,
  texto_base_sha256 text NOT NULL,
  origem_redacao    text NOT NULL CHECK (origem_redacao IN ('gerada_automaticamente', 'redigida_pela_casa')),
  rascunho_id       uuid,
  modelo_llm_id     text,
  prompt_versao     text,
  publicado_por     uuid NOT NULL,
  publicado_em      timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (ente_id, id),
  UNIQUE (ente_id, proposicao_id, versao),
  FOREIGN KEY (ente_id, proposicao_id) REFERENCES legislativo.proposicoes (ente_id, id),
  CONSTRAINT resumo_gerado_tem_proveniencia CHECK (
    origem_redacao <> 'gerada_automaticamente'
    OR (rascunho_id IS NOT NULL AND modelo_llm_id IS NOT NULL AND prompt_versao IS NOT NULL))
);
--;;
ALTER TABLE legislativo.resumo_rascunho ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE legislativo.resumo_rascunho FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON legislativo.resumo_rascunho;
--;;
CREATE POLICY tenant_isolation ON legislativo.resumo_rascunho
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid)
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
ALTER TABLE legislativo.resumo_cidadao ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE legislativo.resumo_cidadao FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON legislativo.resumo_cidadao;
--;;
CREATE POLICY tenant_isolation ON legislativo.resumo_cidadao
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid)
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
GRANT SELECT, INSERT ON legislativo.resumo_rascunho TO oplenario_app;
--;;
GRANT SELECT, INSERT ON legislativo.resumo_cidadao TO oplenario_app;
--;;
CREATE TRIGGER trg_resumo_rascunho_append_only
  BEFORE UPDATE OR DELETE ON legislativo.resumo_rascunho
  FOR EACH ROW EXECUTE FUNCTION shared.imut_append_only();
--;;
CREATE TRIGGER trg_resumo_cidadao_append_only
  BEFORE UPDATE OR DELETE ON legislativo.resumo_cidadao
  FOR EACH ROW EXECUTE FUNCTION shared.imut_append_only();
