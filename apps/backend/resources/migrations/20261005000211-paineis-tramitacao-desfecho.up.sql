-- Quadro de tramitacao: o DESFECHO da materia depois do plenario (docs/16, retriagem linha 18). Nenhum ato depois do
-- plenario move o `estado` do rito, entao o quadro deixava a materia sancionada e ate' publicada como lei na coluna
-- "Pronta p/ pauta". `paineis.tramitacao` ganha o ultimo ato A PARTIR DO AUTOGRAFO (a votacao sozinha nao decide: em
-- dois turnos, "Aguardando pauta" depois do 1o e' verdade), projetado de `proposicao.desfecho-registrado` e de
-- `norma.publicada`. Mesmo vocabulario de `transparencia.materia.desfecho` (mig 20261005000210).
--
-- BACKFILL (uma vez), mesmo desenho da mig 0192: leitura cross-schema so' em DDL de migration, FORCE RLS desligado e
-- religado na mesma transacao, so' linha efetivada.
ALTER TABLE paineis.tramitacao ADD COLUMN IF NOT EXISTS desfecho text;
--;;
ALTER TABLE paineis.tramitacao ADD COLUMN IF NOT EXISTS desfecho_em timestamptz;
--;;
COMMENT ON COLUMN paineis.tramitacao.desfecho IS
$c$Ultimo ato da materia a partir do autografo (autografo_enviado, sancionado, sancao_tacita, vetado, veto_mantido, veto_derrubado, promulgada, publicada). NULL = nao saiu do plenario; a coluna do quadro vem do estado do rito.$c$;
--;;
ALTER TABLE paineis.tramitacao NO FORCE ROW LEVEL SECURITY;
--;;
ALTER TABLE legislativo.autografo NO FORCE ROW LEVEL SECURITY;
--;;
ALTER TABLE legislativo.tramitacao_executiva NO FORCE ROW LEVEL SECURITY;
--;;
ALTER TABLE legislativo.norma NO FORCE ROW LEVEL SECURITY;
--;;
UPDATE paineis.tramitacao t
   SET desfecho = u.ato, desfecho_em = u.ocorrido_em
  FROM (SELECT DISTINCT ON (ente_id, proposicao_id) ente_id, proposicao_id, ato, ocorrido_em
          FROM (SELECT a.ente_id, a.proposicao_id, 'autografo_enviado' AS ato, a.enviado_em AS ocorrido_em
                  FROM legislativo.autografo a WHERE a.efetivado_em IS NOT NULL
                UNION ALL
                SELECT a.ente_id, a.proposicao_id,
                       CASE WHEN x.estado IN ('sancionado', 'sancao_tacita', 'vetado') THEN x.estado ELSE 'vetado' END,
                       x.respondido_em
                  FROM legislativo.tramitacao_executiva x
                  JOIN legislativo.autografo a ON a.ente_id = x.ente_id AND a.id = x.autografo_id
                 WHERE x.respondido_em IS NOT NULL
                UNION ALL
                SELECT a.ente_id, a.proposicao_id, x.estado, x.apreciado_em
                  FROM legislativo.tramitacao_executiva x
                  JOIN legislativo.autografo a ON a.ente_id = x.ente_id AND a.id = x.autografo_id
                 WHERE x.apreciado_em IS NOT NULL AND x.estado IN ('veto_mantido', 'veto_derrubado')
                UNION ALL
                SELECT n.ente_id, n.proposicao_id, 'promulgada', n.promulgado_em FROM legislativo.norma n
                UNION ALL
                SELECT n.ente_id, n.proposicao_id, 'publicada', n.publicado_em FROM legislativo.norma n
                 WHERE n.publicado_em IS NOT NULL) atos
         ORDER BY ente_id, proposicao_id, ocorrido_em DESC) u
 WHERE t.ente_id = u.ente_id AND t.proposicao_id = u.proposicao_id;
--;;
ALTER TABLE legislativo.norma FORCE ROW LEVEL SECURITY;
--;;
ALTER TABLE legislativo.tramitacao_executiva FORCE ROW LEVEL SECURITY;
--;;
ALTER TABLE legislativo.autografo FORCE ROW LEVEL SECURITY;
--;;
ALTER TABLE paineis.tramitacao FORCE ROW LEVEL SECURITY;
