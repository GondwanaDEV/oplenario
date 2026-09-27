-- Faixa A / A.6b da Track IA — o PONTEIRO do rascunho de ata da IA (§22.3.4: "ata em rascunho" vive na IA, transitória;
-- o core guarda so' a situacao e os metadados, como o ponteiro da transcricao). Uma linha por FATO de uma solicitacao:
-- 'solicitado' (a secretaria pediu; vira o evento de integracao AtaSolicitada), 'pronto' (AtaRascunhoPronta: o id do
-- rascunho na IA, modelo, versao do prompt e os sinais da Camada de Confiança) ou 'falhou' (AtaFalhou, categoria do
-- §22.3.5). Append-only: a situacao atual de uma solicitacao e' a linha mais recente dela. Cada fato acontece uma vez
-- por solicitacao (UNIQUE) — a caixa de entrada ja' deduplica pela chave, o banco garante de novo.
CREATE TABLE IF NOT EXISTS sessoes.ata_rascunho (
  ente_id         uuid NOT NULL,
  id              uuid NOT NULL DEFAULT gen_random_uuid(),
  solicitacao_id  uuid NOT NULL,
  sessao_id       uuid NOT NULL,
  situacao        text NOT NULL CHECK (situacao IN ('solicitado', 'pronto', 'falhou')),
  solicitado_por  uuid,
  rascunho_id     uuid,                        -- o id do rascunho NA IA (pronto)
  modelo_llm_id   text,
  prompt_versao   text,
  incerteza       text CHECK (incerteza IS NULL OR incerteza IN ('normal', 'revisar_com_atencao')),
  n_citacoes            integer,
  n_citacoes_conferidas integer,
  n_paragrafos_sem_fonte integer,
  n_pontos_a_confirmar  integer,
  categoria_erro  text,
  detalhe_erro    text,
  retentavel      boolean,
  ocorrido_em     timestamptz NOT NULL,
  PRIMARY KEY (ente_id, id),
  UNIQUE (ente_id, solicitacao_id, situacao),
  FOREIGN KEY (ente_id, sessao_id) REFERENCES sessoes.sessao (ente_id, id),
  CONSTRAINT rascunho_solicitado_tem_autor CHECK (situacao <> 'solicitado' OR solicitado_por IS NOT NULL),
  CONSTRAINT rascunho_pronto_completo CHECK (
    situacao <> 'pronto' OR (rascunho_id IS NOT NULL AND modelo_llm_id IS NOT NULL AND prompt_versao IS NOT NULL
                             AND incerteza IS NOT NULL)),
  CONSTRAINT rascunho_falha_categorizada CHECK (situacao <> 'falhou' OR categoria_erro IS NOT NULL)
);
--;;
CREATE INDEX IF NOT EXISTS idx_ata_rascunho_sessao ON sessoes.ata_rascunho (ente_id, sessao_id, ocorrido_em);
--;;
ALTER TABLE sessoes.ata_rascunho ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE sessoes.ata_rascunho FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON sessoes.ata_rascunho;
--;;
CREATE POLICY tenant_isolation ON sessoes.ata_rascunho
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid)
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
GRANT SELECT, INSERT ON sessoes.ata_rascunho TO oplenario_app;
--;;
CREATE TRIGGER trg_ata_rascunho_append_only
  BEFORE UPDATE OR DELETE ON sessoes.ata_rascunho
  FOR EACH ROW EXECUTE FUNCTION shared.imut_append_only();
