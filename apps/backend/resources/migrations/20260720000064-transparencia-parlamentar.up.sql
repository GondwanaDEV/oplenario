-- Onda E fatia 2: as duas projecoes que o PERFIL PUBLICO do vereador precisa. Read-model puro (§22.10):
-- alimentado SO' por evento (`voto.registrado`, `presenca.registrada`), sem FK/JOIN cross-schema; a verdade
-- continua em legislativo/sessoes.

-- ---------- voto_parlamentar: COMO O VEREADOR VOTOU, so' em votacao ABERTA/NOMINAL. ----------
-- SIGILO: `voto.registrado` e' uniao discriminada por :modalidade e o ramo 'secreta' e' um mapa :closed que
-- NEM ADMITE :vereador-id/:voto (legislativo/events/votacao.clj). Ou seja, esta tabela nao PODE receber voto
-- secreto — a garantia e' de SCHEMA DE EVENTO, nao de um `if` no consumer.
CREATE TABLE IF NOT EXISTS transparencia.voto_parlamentar (
  ente_id       uuid NOT NULL,
  votacao_id    uuid NOT NULL,                    -- ref por VALOR (sem FK cross-schema)
  vereador_id   uuid NOT NULL,
  proposicao_id uuid,                             -- a materia votada (link do portal); nullable p/ robustez
  voto          text NOT NULL,                    -- sim|nao|abstencao (vocabulario de legislativo/logic)
  ocorrido_em   timestamptz NOT NULL,             -- instante no DOMINIO (do evento), nao o de projecao
  projetado_em  timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (ente_id, votacao_id, vereador_id)  -- um voto por (votacao, vereador): ON CONFLICT DO NOTHING
);
--;;
-- hot-path "como votou" do perfil: por vereador, mais recentes primeiro.
CREATE INDEX IF NOT EXISTS idx_voto_parlamentar_vereador
  ON transparencia.voto_parlamentar (ente_id, vereador_id, ocorrido_em DESC);
--;;
ALTER TABLE transparencia.voto_parlamentar ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE transparencia.voto_parlamentar FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON transparencia.voto_parlamentar;
--;;
CREATE POLICY tenant_isolation ON transparencia.voto_parlamentar
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid)
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
GRANT SELECT, INSERT ON transparencia.voto_parlamentar TO oplenario_app;
--;;
-- ---------- presenca_parlamentar: registro OFICIAL de presenca por (sessao, vereador). ----------
-- O evento e' um LOG de entrada/saida (`tipo`); aqui guarda-se o ESTADO ATUAL por sessao (UPSERT), que e' o
-- que a vista publica precisa. Denominador do "% de presenca" sai da PROPRIA tabela (COUNT DISTINCT sessao_id
-- do ente) — sem projetar sessoes, e honesto: so' entram sessoes que TIVERAM chamada.
CREATE TABLE IF NOT EXISTS transparencia.presenca_parlamentar (
  ente_id       uuid NOT NULL,
  sessao_id     uuid NOT NULL,
  vereador_id   uuid NOT NULL,
  tipo          text NOT NULL,                    -- presente|ausente|... (vocabulario de sessoes)
  modalidade    text NOT NULL,
  ocorrido_em   timestamptz NOT NULL,
  projetado_em  timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (ente_id, sessao_id, vereador_id)
);
--;;
CREATE INDEX IF NOT EXISTS idx_presenca_parlamentar_vereador
  ON transparencia.presenca_parlamentar (ente_id, vereador_id);
--;;
ALTER TABLE transparencia.presenca_parlamentar ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE transparencia.presenca_parlamentar FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON transparencia.presenca_parlamentar;
--;;
CREATE POLICY tenant_isolation ON transparencia.presenca_parlamentar
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid)
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
GRANT SELECT, INSERT, UPDATE ON transparencia.presenca_parlamentar TO oplenario_app;
