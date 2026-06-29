-- F4.5b: modulo SESSOES, eixo F do §22.6 — TRIBUNA (camada de EXECUCAO). Duas tabelas:
--   fala_executada        : a fala efetivamente ocorrida (SEPARADA da inscricao — intencao != execucao). Carrega
--                           o INTERVALO [iniciou_em, encerrou_em] (tempo = coordenada de 1a classe; o intervalo
--                           ancora a pre-atribuicao de diarizacao da §22.3.4: o cluster que cobre o intervalo e'
--                           do orador). `inscricao_id` nullable (sessao solene reusa a fala com campos relaxados).
--                           Apartes vinculam-se a fala-mae via `fala_pai_id` (auto-FK same-schema). O estado de
--                           PROCESSAMENTO e' EMERGENTE — SEM coluna de status (disc.5); o que existe sao os marcos
--                           e o tempo computado AO ENCERRAR (nao uma coluna ticando).
--   fala_cronometro_evento: eventos APPEND-ONLY do cronometro (iniciada|encerrada|pausada|retomada|
--                           aparte_concedido|tempo_adicional_concedido). O cronometro e' PROJECAO sobre estes
--                           eventos — `fala_executada.tempo_efetivamente_usado_segundos` e' a projecao materializada
--                           ao encerrar (= elapsed - pausas), nao um snapshot vivo.
-- `orador_id`/`proposicao_ref_id` sao forward-ref (uuid, sem FK cross-schema, §22.10). NAO-particionadas.
CREATE TABLE IF NOT EXISTS sessoes.fala_executada (
  ente_id    uuid NOT NULL,
  id         uuid NOT NULL DEFAULT gen_random_uuid(),
  sessao_id  uuid NOT NULL,
  inscricao_id uuid,                                      -- nullable: sessao solene / fala sem inscricao previa
  orador_id  uuid NOT NULL,                               -- forward-ref a cadastros.vereador (sem FK, §22.10)
  tipo_fala  text NOT NULL CHECK (tipo_fala IN
    ('principal', 'aparte', 'pela_ordem', 'questao_de_ordem', 'explicacao_pessoal', 'comunicado')),
  fala_pai_id uuid,                                       -- aparte -> fala-mae (auto-FK same-schema)
  -- FASE como atributo: a tribuna e' subordinada a fase da pauta (§22.6 eixo F).
  fase       text NOT NULL CHECK (fase IN
    ('expediente', 'grande_expediente', 'ordem_do_dia', 'explicacoes_pessoais', 'tribuna_livre_cidadao')),
  proposicao_ref_id uuid,                                 -- vinculo OPCIONAL a materia (forward-ref, §22.10)
  iniciou_em  timestamptz NOT NULL,                       -- marco de inicio (tempo = coordenada de 1a classe)
  encerrou_em timestamptz,                                -- marco de fim (nullable enquanto em curso)
  tempo_efetivamente_usado_segundos integer,             -- PROJECAO computada AO ENCERRAR (elapsed - pausas)
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
  FOREIGN KEY (ente_id, sessao_id)    REFERENCES sessoes.sessao (ente_id, id),
  FOREIGN KEY (ente_id, inscricao_id) REFERENCES sessoes.inscricao_oradores (ente_id, id),  -- nullable: MATCH SIMPLE
  FOREIGN KEY (ente_id, fala_pai_id)  REFERENCES sessoes.fala_executada (ente_id, id),       -- auto-FK aparte->mae
  -- aparte <=> tem fala_pai_id (bicondicional): so aparte tem mae; demais tipos nao tem.
  CONSTRAINT fala_aparte_tem_pai CHECK (
    (tipo_fala = 'aparte'  AND fala_pai_id IS NOT NULL)
    OR (tipo_fala <> 'aparte' AND fala_pai_id IS NULL)),
  -- encerrou <=> tempo computado (bicondicional, simetrico ao par da gravacao F4.4b): o tempo so existe quando
  -- a fala encerrou; encerrar SEM computar o tempo (ou vice-versa) e' incoerente.
  CONSTRAINT fala_encerrou_tem_tempo CHECK (
    (encerrou_em IS NULL     AND tempo_efetivamente_usado_segundos IS NULL)
    OR (encerrou_em IS NOT NULL AND tempo_efetivamente_usado_segundos IS NOT NULL)),
  CONSTRAINT fala_marcos_ordem CHECK (encerrou_em IS NULL OR encerrou_em >= iniciou_em),
  -- defesa-em-profundidade: o tempo computado (elapsed - pausas) e' clampado em logic, mas o CHECK barra
  -- negativo vindo de bug do servico (espelha cronometro_segundos_coerente > 0).
  CONSTRAINT fala_tempo_nao_negativo CHECK (
    tempo_efetivamente_usado_segundos IS NULL OR tempo_efetivamente_usado_segundos >= 0),
  CONSTRAINT fala_staging_valido CHECK (lote_id IS NOT NULL OR efetivado_em IS NOT NULL)
);
--;;
-- read-model do painel: falas da sessao em ordem cronologica (e ancora do intervalo p/ diarizacao).
CREATE INDEX IF NOT EXISTS idx_fala_executada_sessao
  ON sessoes.fala_executada (ente_id, sessao_id, iniciou_em);
--;;
-- reconstrucao "fala principal com apartes": apartes por fala-mae.
CREATE INDEX IF NOT EXISTS idx_fala_executada_pai
  ON sessoes.fala_executada (ente_id, fala_pai_id) WHERE fala_pai_id IS NOT NULL;
--;;
-- suporte ao FK inscricao_id (DELETE maintenance) + read-model "fala desta inscricao". Parcial: solene (NULL)
-- nao tem FK a manter.
CREATE INDEX IF NOT EXISTS idx_fala_executada_inscricao
  ON sessoes.fala_executada (ente_id, inscricao_id) WHERE inscricao_id IS NOT NULL;
--;;
-- read-model historico cross-session "falas do vereador X" (estatisticas do vereador, TCE). orador_id e'
-- forward-ref (sem FK), mas o indice evita seqscan quando o volume de sessoes crescer.
CREATE INDEX IF NOT EXISTS idx_fala_executada_orador
  ON sessoes.fala_executada (ente_id, orador_id, iniciou_em DESC);
--;;
CREATE INDEX IF NOT EXISTS idx_fala_executada_staging
  ON sessoes.fala_executada (ente_id, lote_id) WHERE efetivado_em IS NULL;
--;;
ALTER TABLE sessoes.fala_executada ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE sessoes.fala_executada FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON sessoes.fala_executada;
--;;
CREATE POLICY tenant_isolation ON sessoes.fala_executada
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid
         AND (efetivado_em IS NOT NULL OR lote_id = NULLIF(current_setting('app.ver_lote', true), '')::uuid))
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
GRANT SELECT, INSERT, UPDATE ON sessoes.fala_executada TO oplenario_app;

-- ============================ fala_cronometro_evento (append-only) ============================
--;;
CREATE TABLE IF NOT EXISTS sessoes.fala_cronometro_evento (
  ente_id    uuid NOT NULL,
  id         uuid NOT NULL DEFAULT gen_random_uuid(),
  fala_id    uuid NOT NULL,
  tipo       text NOT NULL CHECK (tipo IN
    ('iniciada', 'encerrada', 'pausada', 'retomada', 'aparte_concedido', 'tempo_adicional_concedido')),
  ocorrido_em timestamptz NOT NULL,                       -- INSTANTE DE DOMINIO (quando ocorreu) != registrado_em
  segundos_adicionais integer,                            -- so p/ tempo_adicional_concedido (estende o LIMITE)
  origem    text NOT NULL DEFAULT 'nativa',
  origem_ref text,
  origem_importado_em timestamptz,
  lote_id   uuid,
  efetivado_em timestamptz,
  created_by uuid,
  registrado_em timestamptz NOT NULL DEFAULT now(),       -- AUDIT (quando o sistema soube) != ocorrido_em
  PRIMARY KEY (ente_id, id),
  FOREIGN KEY (ente_id, fala_id) REFERENCES sessoes.fala_executada (ente_id, id),
  -- coerencia tipo<->segundos (espelha logic/validar-evento-cronometro): so tempo_adicional carrega segundos>0.
  CONSTRAINT cronometro_segundos_coerente CHECK (
    (tipo = 'tempo_adicional_concedido' AND segundos_adicionais IS NOT NULL AND segundos_adicionais > 0)
    OR (tipo <> 'tempo_adicional_concedido' AND segundos_adicionais IS NULL))
  -- SEM _staging_valido CHECK: append-only puro (o INSERT live crava efetivado_em=now()), padrao presenca_evento.
);
--;;
-- cronometro = projecao sobre os eventos da fala em ordem cronologica.
CREATE INDEX IF NOT EXISTS idx_cronometro_fala
  ON sessoes.fala_cronometro_evento (ente_id, fala_id, ocorrido_em);
--;;
CREATE INDEX IF NOT EXISTS idx_cronometro_staging
  ON sessoes.fala_cronometro_evento (ente_id, lote_id) WHERE efetivado_em IS NULL;
--;;
ALTER TABLE sessoes.fala_cronometro_evento ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE sessoes.fala_cronometro_evento FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON sessoes.fala_cronometro_evento;
--;;
CREATE POLICY tenant_isolation ON sessoes.fala_cronometro_evento
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid
         AND (efetivado_em IS NOT NULL OR lote_id = NULLIF(current_setting('app.ver_lote', true), '')::uuid))
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
GRANT SELECT, INSERT ON sessoes.fala_cronometro_evento TO oplenario_app;
--;;
CREATE TRIGGER trg_fala_cronometro_evento_append_only
  BEFORE UPDATE OR DELETE ON sessoes.fala_cronometro_evento
  FOR EACH ROW EXECUTE FUNCTION shared.imut_append_only();
