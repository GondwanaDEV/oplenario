-- Carry I-5, fatia 5: a COMPANHEIRA intra-schema do denominador de presenca.
--
-- POR QUE ELA EXISTE. Ate' aqui o denominador do numero-card de presenca era
-- `COUNT(DISTINCT sessao_id)` sobre `transparencia.presenca_parlamentar` — uma linha por (sessao, vereador),
-- ou seja ~21x mais linhas do que sessoes numa Casa tipica. Medido (42.000 linhas sinteticas = 2.000 sessoes
-- x 21 vereadores, apos VACUUM ANALYZE, RLS ativa, papel oplenario_app): `Aggregate <- Sort <- Seq Scan`,
-- ~605 buffers, ~12 ms. O custo NAO e' I/O (605 buffers = 4,8 MB) — e' o VOLUME DE LINHAS ORDENADAS pelo
-- agregado DISTINCT. Esta tabela guarda UMA linha por SESSAO, com a DATA CIVIL dela, e e' o que permite a
-- fatia 6 trocar o `COUNT(DISTINCT)` por um `count(*)` servido por range scan em (ente_id, data) — que e'
-- tambem o predicado da JANELA DE EXERCICIO do mandato, o proposito inteiro do carry I-5.
--
-- QUE DADO E' ESTE. `data` = data civil (America/Fortaleza) do PRIMEIRO evento de presenca daquela sessao.
-- NAO e' "a data da sessao": `transparencia` nao projeta sessao nenhuma e JOIN cross-schema com `sessoes` e'
-- proibido (§22.10). Uma sessao que atravessa a meia-noite pode ficar um dia adiante — so' importa na borda
-- exata de posse/fim de mandato (carry escrito na decisao do I-5).
-- E "sessao com chamada" NAO e' "sessao realizada": uma sessao em que ninguem fez check-in nao existe aqui e
-- some dos DOIS lados da fracao. E' deliberado — a alternativa (denominador vindo de `sessoes.sessao`)
-- transformaria uma falha do painel em FALTA para os 21 vereadores.
--
-- ORDEM DELIBERADA DOS STATEMENTS: criar -> BACKFILLAR -> so' entao ENABLE/FORCE RLS + policy + GRANT.
-- Backfillar com a policy ja' ativa e sem o GUC `app.ente_id` setado (migration nao seta tenant) inseriria
-- ZERO linha ou falharia no WITH CHECK.
CREATE TABLE IF NOT EXISTS transparencia.sessao_com_chamada (
  ente_id      uuid NOT NULL,
  sessao_id    uuid NOT NULL,                   -- ref por VALOR (sem FK cross-schema)
  data         date NOT NULL,                   -- data CIVIL do PRIMEIRO evento de presenca da sessao
  projetado_em timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (ente_id, sessao_id)              -- uma linha por sessao: ON CONFLICT ... LEAST no consumer
);
--;;
-- o predicado da fatia 6: janela de exercicio do mandato = OR de intervalos sobre `data`, dentro do ente.
CREATE INDEX IF NOT EXISTS idx_sessao_com_chamada_data
  ON transparencia.sessao_com_chamada (ente_id, data);
--;;
-- BACKFILL — INTRA-SCHEMA (a origem e' a propria `transparencia.presenca_parlamentar`; nao ha leitura
-- cross-schema em SQL nem em Clojure). O NO FORCE e' obrigatorio: `presenca_parlamentar` tem FORCE ROW LEVEL
-- SECURITY e a policy compara `ente_id` com o GUC `app.ente_id`, que uma migration nao seta — com FORCE
-- ligado este SELECT leria ZERO linha SILENCIOSAMENTE num cluster em que o papel de migration nao e'
-- superuser (num cluster em que ele e' superuser o BYPASSRLS ja' resolveria, mas nao se pode depender disso).
ALTER TABLE transparencia.presenca_parlamentar NO FORCE ROW LEVEL SECURITY;
--;;
INSERT INTO transparencia.sessao_com_chamada (ente_id, sessao_id, data)
SELECT ente_id, sessao_id, min(ocorrido_em AT TIME ZONE 'America/Fortaleza')::date
  FROM transparencia.presenca_parlamentar
 GROUP BY ente_id, sessao_id
ON CONFLICT (ente_id, sessao_id) DO NOTHING;
--;;
ALTER TABLE transparencia.presenca_parlamentar FORCE ROW LEVEL SECURITY;
--;;
ALTER TABLE transparencia.sessao_com_chamada ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE transparencia.sessao_com_chamada FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON transparencia.sessao_com_chamada;
--;;
CREATE POLICY tenant_isolation ON transparencia.sessao_com_chamada
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid)
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
-- UPDATE e' OBRIGATORIO, nao zelo: o consumer usa `ON CONFLICT ... DO UPDATE SET data = LEAST(...)` e o
-- Postgres exige o privilegio de UPDATE para a acao do conflito (mesma licao da 0064 na presenca).
GRANT SELECT, INSERT, UPDATE ON transparencia.sessao_com_chamada TO oplenario_app;
