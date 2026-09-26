-- Fatia 1 do pedido do stakeholder (tempo de tribuna com campainha): o TEMPO-LIMITE da fala. §22.6 eixo F ja'
-- decidia "tempos regimentais por fase e por camara como configuracao, nao engessados", mas nada o
-- materializava: o cronometro so' sabia o tempo DECORRIDO, e a TV nao tinha como dizer "tempo esgotado".
--
-- Duas pecas:
--
-- (1) `fala_executada.tempo_concedido_segundos` — o limite FOTOGRAFADO na fala ao inicia-la. E' o tempo que a
--     Mesa concedeu A ESTA fala (o regimental da Casa, ou o que a Mesa informou ao chamar o orador). Fotografado,
--     nao lido da configuracao a cada tick: mudar a tabela de tempos no meio da sessao nao pode encurtar a fala
--     de quem ja' esta na tribuna (tempo = coordenada de 1a classe, §22.6 disc.1). NULL = sem limite (sessao
--     solene, Casa sem tempo configurado, falas anteriores a esta migration) — o cronometro so' conta, como
--     antes. O tempo EXTRA concedido continua sendo o marco `tempo_adicional_concedido` (append-only, mig 0033):
--     limite efetivo = concedido + soma dos adicionais, computado no cliente a partir dos marcos (§22.6 eixo G).
--
-- (2) `tempo_regimental` — a CONFIGURACAO por Casa: quantos segundos cada tipo de fala tem, por fase. `fase`
--     NULL = vale para qualquer fase (o regimento costuma dar "aparte: 1 minuto" sem distinguir fase); a linha
--     com fase explicita vence a generica. Resolvida no servidor ao iniciar a fala quando a Mesa nao informa um
--     tempo. NAO e' regra do motor: o motor (§22.7, envelope de guard) responde "esta acao e' valida agora?", e
--     o tempo esgotado nao bloqueia nada — a Casa toca a campainha e o PRESIDENTE decide (+1 min ou encerrar).
--     E' um parametro regimental, dado de tenant, no mesmo schema de quem o consome.
ALTER TABLE sessoes.fala_executada
  ADD COLUMN IF NOT EXISTS tempo_concedido_segundos integer;
--;;
ALTER TABLE sessoes.fala_executada
  DROP CONSTRAINT IF EXISTS fala_tempo_concedido_positivo;
--;;
ALTER TABLE sessoes.fala_executada
  ADD CONSTRAINT fala_tempo_concedido_positivo CHECK (
    tempo_concedido_segundos IS NULL OR tempo_concedido_segundos > 0);
--;;
CREATE TABLE IF NOT EXISTS sessoes.tempo_regimental (
  ente_id   uuid NOT NULL,
  id        uuid NOT NULL DEFAULT gen_random_uuid(),
  fase      text CHECK (fase IS NULL OR fase IN
    ('expediente', 'grande_expediente', 'ordem_do_dia', 'explicacoes_pessoais', 'tribuna_livre_cidadao')),
  tipo_fala text NOT NULL CHECK (tipo_fala IN
    ('principal', 'aparte', 'pela_ordem', 'questao_de_ordem', 'explicacao_pessoal', 'comunicado')),
  segundos  integer NOT NULL CHECK (segundos > 0),
  referencia_normativa text,                               -- ex.: 'RI art. 98 §2' (de onde o numero vem)
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
  CONSTRAINT tempo_regimental_staging_valido CHECK (lote_id IS NOT NULL OR efetivado_em IS NOT NULL)
);
--;;
-- UMA linha por (Casa, fase, tipo). Indice com COALESCE: fase NULL (a linha generica) e' valor legitimo, e no
-- Postgres NULL != NULL faria a UNIQUE inline aceitar duas genericas para o mesmo tipo. Sentinela '' nunca
-- colide com fase real (o CHECK acima fecha o vocabulario).
CREATE UNIQUE INDEX IF NOT EXISTS idx_tempo_regimental_unico
  ON sessoes.tempo_regimental (ente_id, COALESCE(fase, ''), tipo_fala);
--;;
ALTER TABLE sessoes.tempo_regimental ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE sessoes.tempo_regimental FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON sessoes.tempo_regimental;
--;;
CREATE POLICY tenant_isolation ON sessoes.tempo_regimental
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid
         AND (efetivado_em IS NOT NULL OR lote_id = NULLIF(current_setting('app.ver_lote', true), '')::uuid))
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
GRANT SELECT, INSERT, UPDATE, DELETE ON sessoes.tempo_regimental TO oplenario_app;
