-- F4.4b: modulo SESSOES, eixo D do §22.6 — gravacao (audio/video). `gravacao_segmento` e' a unidade TECNICA
-- do arquivo de gravacao (NAO a unidade regimental da sessao): uma sessao tipica gera 1 segmento, mas N sao
-- possiveis (falha tecnica + reinicio do OBS; a camara dividir manualmente). Alinhamento entre arquivo e
-- fatos regimentais e' por INSTANTE, nao por chave hierarquica forte (§22.6.3 disc.2). Container bruto (MP4/
-- MKV) e' objeto OPACO do core; extracao de audio p/ transcricao = responsabilidade da Plataforma de IA.
-- Video = cidadao de 2a classe (video_uri nullable, nao processado na V1). Estado de PROCESSAMENTO e'
-- EMERGENTE (derivado de vinculos/eventos), SEM coluna mutavel de status (§22.6.3 disc.5).
-- Vinculacao arquivo<->sessao = servidor via UI pos-upload (Opcao A): sessao_id nasce nullable e e' setado
-- uma vez. MESMO schema -> FK PERMITIDA (§22.10 so proibe FK cross-schema).
-- NAO-particionada (cardinalidade baixa: ~1 por sessao).
CREATE TABLE IF NOT EXISTS sessoes.gravacao_segmento (
  ente_id  uuid NOT NULL,
  id       uuid NOT NULL DEFAULT gen_random_uuid(),
  -- vinculo regimental (Opcao A): nullable ate o servidor vincular. FK same-schema; com sessao_id NULL a FK
  -- (MATCH SIMPLE) nao e' checada -> segmento nao-vinculado e' permitido.
  sessao_id uuid,
  -- unidade TECNICA: instantes do arquivo (tempo = coordenada de 1a classe, §22.6.3 disc.1)
  iniciou_em  timestamptz NOT NULL,
  encerrou_em timestamptz,
  motivo_inicio text NOT NULL CHECK (motivo_inicio IN
    ('inicio_sessao', 'reinicio_pos_falha', 'divisao_manual')),
  motivo_fim    text CHECK (motivo_fim IS NULL OR motivo_fim IN
    ('fim_sessao', 'falha_tecnica', 'divisao_manual')),
  -- URIs no objeto_store. container_bruto = o que foi captado (opaco, sempre presente). audio/video uri
  -- nullable (audio extraido pela IA; video nao processado na V1).
  container_bruto_uri text NOT NULL,
  audio_uri text,
  video_uri text,
  audio_hash text,                                        -- sha256 do conteudo (integridade/dedup na ingestao)
  fonte_ingestao text NOT NULL CHECK (fonte_ingestao IN
    ('gravacao_local_pos_sessao', 'rtmp_duplicado_ao_vivo', 'youtube_api_fallback', 'importacao_legado')),
  acesso_restrito boolean NOT NULL DEFAULT false,         -- sessao secreta -> restrita (guard no servico, nao na RLS)
  -- transversais (§22.4.3 disc.1); sessao_id muta (vinculacao) -> updated_*/lock_version
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
  -- same-schema FK (§22.10 ok): a sessao vinculada e' do MESMO ente
  FOREIGN KEY (ente_id, sessao_id) REFERENCES sessoes.sessao (ente_id, id),
  -- coerencia bicondicional (review F4.4b DB-MENOR; simetrico ao par do sessoes.sessao): encerrou <=> tem
  -- motivo de fim. E os marcos sao ordenados.
  CONSTRAINT gravacao_encerrou_tem_motivo CHECK (encerrou_em IS NULL OR motivo_fim IS NOT NULL),
  CONSTRAINT gravacao_motivo_fim_exige_encerramento CHECK (motivo_fim IS NULL OR encerrou_em IS NOT NULL),
  CONSTRAINT gravacao_marcos_ordem CHECK (encerrou_em IS NULL OR encerrou_em >= iniciou_em),
  CONSTRAINT gravacao_staging_valido CHECK (lote_id IS NOT NULL OR efetivado_em IS NOT NULL)
);
--;;
-- read-model do painel da sessao ("gravacoes desta sessao"); parcial (segmentos nao-vinculados saem do scan).
CREATE INDEX IF NOT EXISTS idx_gravacao_sessao
  ON sessoes.gravacao_segmento (ente_id, sessao_id, iniciou_em) WHERE sessao_id IS NOT NULL;
--;;
CREATE INDEX IF NOT EXISTS idx_gravacao_staging
  ON sessoes.gravacao_segmento (ente_id, lote_id) WHERE efetivado_em IS NULL;
--;;
ALTER TABLE sessoes.gravacao_segmento ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE sessoes.gravacao_segmento FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON sessoes.gravacao_segmento;
--;;
CREATE POLICY tenant_isolation ON sessoes.gravacao_segmento
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid
         AND (efetivado_em IS NOT NULL OR lote_id = NULLIF(current_setting('app.ver_lote', true), '')::uuid))
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
GRANT SELECT, INSERT, UPDATE ON sessoes.gravacao_segmento TO oplenario_app;
