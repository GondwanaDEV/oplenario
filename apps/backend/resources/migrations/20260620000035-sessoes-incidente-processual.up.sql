-- §16.13 / §22.6: modulo SESSOES — INCIDENTES PROCESSUAIS da sessao. A revisao de completude (§16.13) achou que
-- a sessao modelava os OUTPUTS (telao/votacao/quorum/presenca/tribuna) mas nao todos os INCIDENTES de conducao
-- ao vivo. Dois ja' tem casa: `questao de ordem` = `decisao_mesa` (decisao do presidente, mig 0034) e `retirada
-- de pauta` = soft-remove do `pauta_item` (mig 0027). Faltam os DEMAIS: pedido de vista, verificacao de votacao,
-- urgencia, votacao em bloco. `incidente_processual` os captura como ATO REGIMENTAL APPEND-ONLY (suscitado +
-- deliberado num so registro, p/ a ata), espelhando `decisao_mesa`. Os EFEITOS profundos (pedido de vista
-- SUSPENDE a deliberacao + abre prazo; urgencia muda o regime de tramitacao; verificacao reabre a votacao) sao
-- carry F5/legislativo (escopo diferido por default, regua §15) — aqui mora o REGISTRO institucional do ato.
-- `objeto`/`requerente` sao forward-ref OPCIONAIS (uuid, sem FK cross-schema p/ legislativo/cadastros, §22.10).
-- NAO-particionada (cardinalidade baixa por sessao).
CREATE TABLE IF NOT EXISTS sessoes.incidente_processual (
  ente_id    uuid NOT NULL,
  id         uuid NOT NULL DEFAULT gen_random_uuid(),
  sessao_id  uuid NOT NULL,
  -- os incidentes que NAO tem casa propria (questao de ordem -> decisao_mesa; retirada de pauta -> pauta_item).
  tipo       text NOT NULL CHECK (tipo IN
    ('pedido_vista', 'verificacao_votacao', 'urgencia', 'votacao_em_bloco')),
  -- disposicao do incidente, deliberada na mesma hora (V1 atomico: suscitado+resolvido num registro). O ciclo
  -- "suscitado agora, resolvido depois" seria uma state machine -> diferido (disciplina, como o resto do F4).
  resultado  text NOT NULL CHECK (resultado IN
    ('deferido', 'indeferido', 'prejudicado', 'retirado')),
  descricao  text NOT NULL CHECK (length(trim(descricao)) > 0),     -- o que foi suscitado (vai p/ a ata)
  -- materia que o incidente atinge — ref polimorfica forward-ref (sem FK cross-schema, §22.10). OPCIONAL: votacao
  -- em bloco nao tem objeto unico. Coerencia: ambos ou nenhum (CHECK abaixo). `objeto_tipo` BOUNDED por enum (nao
  -- texto livre, review database MENOR-2 + security BAIXO-2): sem o CHECK um typo ('proposicoes') entraria mudo e
  -- furaria o read-model por materia + os consumidores SSE. Aberto a 1 linha por tipo novo (config-ish).
  objeto_tipo text CHECK (objeto_tipo IN ('proposicao', 'votacao', 'emenda')),
  objeto_id   uuid,
  requerente_id uuid,                                   -- quem suscitou (vereador, forward-ref); pode ser ato da Mesa -> nulo
  -- fundamentacao/texto da deliberacao (opcional); se presente, nao-vazia (campo de peso, ~ decisao_mesa).
  deliberacao text CHECK (deliberacao IS NULL OR length(trim(deliberacao)) > 0),
  ocorrido_em timestamptz NOT NULL,                     -- INSTANTE DE DOMINIO (quando o incidente ocorreu)
  origem    text NOT NULL DEFAULT 'nativa',
  origem_ref text,
  origem_importado_em timestamptz,
  lote_id   uuid,
  efetivado_em timestamptz,
  created_by uuid,
  registrado_em timestamptz NOT NULL DEFAULT now(),     -- AUDIT (quando o sistema soube) != ocorrido_em
  PRIMARY KEY (ente_id, id),
  FOREIGN KEY (ente_id, sessao_id) REFERENCES sessoes.sessao (ente_id, id),  -- same-schema same-tenant
  -- coerencia do objeto polimorfico: ambos preenchidos ou ambos nulos (nunca tipo sem id, nem id sem tipo).
  CONSTRAINT incidente_objeto_coerente CHECK ((objeto_tipo IS NULL) = (objeto_id IS NULL))
  -- SEM _staging_valido CHECK: append-only puro (trigger barra UPDATE/DELETE), padrao decisao_mesa/presenca_evento.
);
--;;
-- read-model "incidentes desta sessao" (composicao da ata + painel da mesa de conducao) em ordem cronologica.
CREATE INDEX IF NOT EXISTS idx_incidente_processual_sessao
  ON sessoes.incidente_processual (ente_id, sessao_id, ocorrido_em);
--;;
-- "incidentes referentes a esta materia" (read-model cross-session da proposicao). Parcial: so quando ha objeto.
CREATE INDEX IF NOT EXISTS idx_incidente_processual_objeto
  ON sessoes.incidente_processual (ente_id, objeto_tipo, objeto_id) WHERE objeto_id IS NOT NULL;
--;;
-- "incidentes suscitados pelo vereador X" (read-model cross-session: estatisticas, ata) — espelha o
-- idx_decisao_mesa_presidente. Parcial: requerente_id nulo quando o incidente e' ato da Mesa (review database MENOR-1).
CREATE INDEX IF NOT EXISTS idx_incidente_processual_requerente
  ON sessoes.incidente_processual (ente_id, requerente_id, ocorrido_em DESC) WHERE requerente_id IS NOT NULL;
--;;
CREATE INDEX IF NOT EXISTS idx_incidente_processual_staging
  ON sessoes.incidente_processual (ente_id, lote_id) WHERE efetivado_em IS NULL;
--;;
ALTER TABLE sessoes.incidente_processual ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE sessoes.incidente_processual FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON sessoes.incidente_processual;
--;;
CREATE POLICY tenant_isolation ON sessoes.incidente_processual
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid
         AND (efetivado_em IS NOT NULL OR lote_id = NULLIF(current_setting('app.ver_lote', true), '')::uuid))
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
GRANT SELECT, INSERT ON sessoes.incidente_processual TO oplenario_app;
--;;
CREATE TRIGGER trg_incidente_processual_append_only
  BEFORE UPDATE OR DELETE ON sessoes.incidente_processual
  FOR EACH ROW EXECUTE FUNCTION shared.imut_append_only();
