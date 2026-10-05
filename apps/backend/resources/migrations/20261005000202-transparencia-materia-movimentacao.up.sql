-- Portal do cidadao: "Por onde a materia passou". A ficha publica guardava so' o `estado` ATUAL da materia; a
-- linha do tempo (cada movimentacao, com data e o NOME da etapa no rito da Casa) nao existia em lugar nenhum do
-- portal. `transparencia` e' read-model por EVENTO (nunca le' `legislativo`), entao esta tabela e' PROJETADA de
-- `proposicao.protocolada` (a abertura da linha) e `proposicao.transicionou` (cada movimentacao), que passaram a
-- carregar o rotulo da etapa (`estado-nome` / `para-nome`) e o instante do protocolo (`protocolada-em`).
--
-- O QUE GUARDA. So' o que e' publico: QUANDO e EM QUE ETAPA. Nada de quem despachou, gatilho, contexto, parecer.
--   etapa_chave = a chave do estado (texto livre por Casa) — so' para a idempotencia, nunca vai a tela.
--   etapa       = o rotulo do rito (`template_estado.nome`); NULL = o rito nao declara o estado de destino.
--   inicial     = a linha de abertura (o protocolo). E' ela que prova que o historico e' COMPLETO: sem ela o
--                 historico "comeca no meio" e a tela diz "disponivel a partir de ...".
--
-- IDEMPOTENCIA. Chave natural (ente, materia, instante, etapa): o mesmo fato projetado duas vezes — redrive com
-- idempotency-key nova, ou o evento de uma transicao que o backfill abaixo ja' cobriu — cai no ON CONFLICT.
--
-- ORDEM DELIBERADA (mesmo desenho da mig 0067): criar -> BACKFILLAR -> so' entao ENABLE/FORCE RLS + policy + GRANT.
CREATE TABLE IF NOT EXISTS transparencia.materia_movimentacao (
  ente_id       uuid        NOT NULL,
  proposicao_id uuid        NOT NULL,                    -- ref por VALOR (sem FK cross-schema; a materia vive aqui no schema, vide INSERT..SELECT)
  ocorrido_em   timestamptz NOT NULL,                    -- o instante REAL do ato (protocolo / transicao), nao o da projecao
  etapa_chave   text        NOT NULL,                    -- idempotencia; NUNCA exibida (vocabulario livre por Casa)
  etapa         text,                                    -- rotulo no rito da Casa; NULL = o rito nao declara o estado
  inicial       boolean     NOT NULL DEFAULT false,      -- a abertura (protocolo)
  projetado_em  timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (ente_id, proposicao_id, ocorrido_em, etapa_chave)
);
--;;
COMMENT ON TABLE transparencia.materia_movimentacao IS
$c$Linha do tempo PUBLICA da materia no portal: data e etapa (rotulo do rito da Casa). Projecao append-only de proposicao.protocolada/transicionou. Linhas anteriores ao deploy desta migration foram reconstruidas UMA vez de legislativo.proposicao_transicao_historico (backfill desta migration).$c$;
--;;
-- BACKFILL (uma vez), SO' da projecao. Fonte: o historico append-only de transicoes e a linha da proposicao. A
-- leitura cross-schema e' DDL de migration (one-shot), nao codigo de modulo: a regra 22.10 vale para o runtime.
-- RLS e' FORCE nas tabelas lidas e uma migration nao seta `app.ente_id`: sem desligar o FORCE (e religar logo
-- depois, na mesma transacao do migratus) estes SELECTs leriam ZERO linha em silencio num papel nao-superuser.
-- Nenhum dado existente e' alterado: so' se INSERE na tabela nova. So' materia que o portal JA' mostra
-- (transparencia.materia) ganha historico, e so' linha efetivada (nao a de lote de importacao em staging).
ALTER TABLE transparencia.materia NO FORCE ROW LEVEL SECURITY;
--;;
ALTER TABLE legislativo.proposicoes NO FORCE ROW LEVEL SECURITY;
--;;
ALTER TABLE legislativo.proposicao_transicao_historico NO FORCE ROW LEVEL SECURITY;
--;;
ALTER TABLE legislativo.template_estado NO FORCE ROW LEVEL SECURITY;
--;;
-- cada movimentacao: o instante e o rotulo da etapa de DESTINO (o do rito que a regeu: template_id da propria linha)
INSERT INTO transparencia.materia_movimentacao (ente_id, proposicao_id, ocorrido_em, etapa_chave, etapa, inicial)
SELECT h.ente_id, h.proposicao_id, h.ocorrido_em, h.para_estado, te.nome, false
  FROM legislativo.proposicao_transicao_historico h
  JOIN transparencia.materia m ON m.ente_id = h.ente_id AND m.proposicao_id = h.proposicao_id
  LEFT JOIN legislativo.template_estado te
    ON te.ente_id = h.ente_id AND te.template_id = h.template_id AND te.chave = h.para_estado
 WHERE h.efetivado_em IS NOT NULL
ON CONFLICT DO NOTHING;
--;;
-- a abertura: o protocolo, no estado em que a materia NASCEU (o `de` da 1a transicao; sem transicao, o estado
-- atual ainda e' o inicial). Sem nome no rito, a etapa de abertura chama-se "Protocolada" — e' o ato, e foi o que houve.
INSERT INTO transparencia.materia_movimentacao (ente_id, proposicao_id, ocorrido_em, etapa_chave, etapa, inicial)
SELECT p.ente_id, p.id, p.criado_em, COALESCE(prim.de_estado, p.estado), COALESCE(te.nome, 'Protocolada'), true
  FROM legislativo.proposicoes p
  JOIN transparencia.materia m ON m.ente_id = p.ente_id AND m.proposicao_id = p.id
  LEFT JOIN LATERAL (
    SELECT h.de_estado
      FROM legislativo.proposicao_transicao_historico h
     WHERE h.ente_id = p.ente_id AND h.proposicao_id = p.id AND h.efetivado_em IS NOT NULL
     ORDER BY h.ocorrido_em ASC
     LIMIT 1) prim ON true
  LEFT JOIN legislativo.template_estado te
    ON te.ente_id = p.ente_id AND te.template_id = p.template_id AND te.chave = COALESCE(prim.de_estado, p.estado)
 WHERE p.efetivado_em IS NOT NULL
ON CONFLICT DO NOTHING;
--;;
ALTER TABLE legislativo.template_estado FORCE ROW LEVEL SECURITY;
--;;
ALTER TABLE legislativo.proposicao_transicao_historico FORCE ROW LEVEL SECURITY;
--;;
ALTER TABLE legislativo.proposicoes FORCE ROW LEVEL SECURITY;
--;;
ALTER TABLE transparencia.materia FORCE ROW LEVEL SECURITY;
--;;
ALTER TABLE transparencia.materia_movimentacao ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE transparencia.materia_movimentacao FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON transparencia.materia_movimentacao;
--;;
CREATE POLICY tenant_isolation ON transparencia.materia_movimentacao
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid)
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
-- append-only: o consumer so' INSERE (ON CONFLICT DO NOTHING nao pede UPDATE) e o portal so' le'.
GRANT SELECT, INSERT ON transparencia.materia_movimentacao TO oplenario_app;
