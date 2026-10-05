-- Portal do cidadao: o DESFECHO da materia (docs/16, retriagem linhas 18 e 30). Nenhum ato depois do plenario move o
-- `estado` do rito (encerrar a votacao nao transiciona a proposicao), entao a materia aprovada, sancionada e ate'
-- publicada como lei aparecia no portal como "Aguardando pauta", e a linha do tempo publica parava no plenario.
--
-- O QUE MUDA. `transparencia.materia` ganha o ULTIMO ato depois do plenario (`desfecho` + `desfecho_em`), projetado de
-- `proposicao.desfecho-registrado` (aprovada/rejeitada/autografo_enviado/sancionado/sancao_tacita/vetado/veto_mantido/
-- veto_derrubado/promulgada) e de `norma.publicada` (publicada). Cada ato tambem entra na linha do tempo
-- (`materia_movimentacao`), com `etapa_chave` = 'ato:<ato>' (nunca colide com etapa do rito, que e' texto livre).
--
-- BACKFILL (uma vez), mesma disciplina da mig 20261005000202: leitura cross-schema so' em DDL de migration, FORCE RLS
-- desligado e religado na mesma transacao, so' linha efetivada, so' materia que o portal ja' mostra. Os ROTULOS sao os
-- de `transparencia.logic.desfecho/rotulo` (o teste `desfecho_da_materia_test` confere os dois lados).
ALTER TABLE transparencia.materia ADD COLUMN IF NOT EXISTS desfecho text;
--;;
ALTER TABLE transparencia.materia ADD COLUMN IF NOT EXISTS desfecho_em timestamptz;
--;;
COMMENT ON COLUMN transparencia.materia.desfecho IS
$c$Ultimo ato da materia depois do plenario (aprovada, rejeitada, autografo_enviado, sancionado, sancao_tacita, vetado, veto_mantido, veto_derrubado, promulgada, publicada). NULL = ainda nao foi a votos; a situacao vem do estado do rito.$c$;
--;;
ALTER TABLE transparencia.materia NO FORCE ROW LEVEL SECURITY;
--;;
ALTER TABLE transparencia.materia_movimentacao NO FORCE ROW LEVEL SECURITY;
--;;
ALTER TABLE legislativo.votacoes NO FORCE ROW LEVEL SECURITY;
--;;
ALTER TABLE legislativo.autografo NO FORCE ROW LEVEL SECURITY;
--;;
ALTER TABLE legislativo.tramitacao_executiva NO FORCE ROW LEVEL SECURITY;
--;;
ALTER TABLE legislativo.norma NO FORCE ROW LEVEL SECURITY;
--;;
CREATE TEMP TABLE desfecho_backfill ON COMMIT DROP AS
WITH especie(tipo_norma, nome) AS (
  VALUES ('lei', 'Lei'), ('lei_complementar', 'Lei Complementar'), ('resolucao', 'Resolução'),
         ('decreto_legislativo', 'Decreto Legislativo'), ('emenda_lom', 'Emenda à Lei Orgânica')
), atos AS (
  -- a votacao encerrada que carrega a propria materia (proposicao ou redacao final)
  SELECT v.ente_id, v.objeto_id AS proposicao_id, v.atualizado_em AS ocorrido_em, v.resultado AS ato,
         'ato:' || v.resultado || CASE WHEN v.objeto_tipo = 'redacao_final' THEN ':redacao_final' ELSE '' END AS chave,
         CASE WHEN v.objeto_tipo = 'redacao_final'
              THEN CASE v.resultado WHEN 'aprovada' THEN 'Redação final aprovada em plenário'
                                    ELSE 'Redação final rejeitada em plenário' END
              ELSE CASE v.resultado WHEN 'aprovada' THEN 'Aprovada em plenário'
                                    ELSE 'Rejeitada em plenário' END
         END AS etapa
    FROM legislativo.votacoes v
   WHERE v.estado = 'encerrada' AND v.objeto_tipo IN ('proposicao', 'redacao_final') AND v.efetivado_em IS NOT NULL
  UNION ALL
  SELECT a.ente_id, a.proposicao_id, a.enviado_em, 'autografo_enviado', 'ato:autografo_enviado',
         format('Autógrafo nº %s/%s enviado ao Executivo', a.numero, a.ano)
    FROM legislativo.autografo a
   WHERE a.efetivado_em IS NOT NULL
  UNION ALL
  -- a resposta do Executivo; depois da apreciacao `estado` guarda o resultado dela, e a resposta so' pode ter sido o veto
  SELECT a.ente_id, a.proposicao_id, t.respondido_em,
         CASE WHEN t.estado IN ('sancionado', 'sancao_tacita', 'vetado') THEN t.estado ELSE 'vetado' END,
         'ato:' || CASE WHEN t.estado IN ('sancionado', 'sancao_tacita', 'vetado') THEN t.estado ELSE 'vetado' END,
         CASE CASE WHEN t.estado IN ('sancionado', 'sancao_tacita', 'vetado') THEN t.estado ELSE 'vetado' END
              WHEN 'sancionado' THEN 'Sancionada pelo Executivo'
              WHEN 'sancao_tacita' THEN 'Sancionada sem resposta do Executivo no prazo (sanção tácita)'
              ELSE 'Vetada pelo Executivo' END
    FROM legislativo.tramitacao_executiva t
    JOIN legislativo.autografo a ON a.ente_id = t.ente_id AND a.id = t.autografo_id
   WHERE t.respondido_em IS NOT NULL
  UNION ALL
  SELECT a.ente_id, a.proposicao_id, t.apreciado_em, t.estado, 'ato:' || t.estado,
         CASE t.estado WHEN 'veto_mantido' THEN 'Veto mantido pela Câmara' ELSE 'Veto derrubado pela Câmara' END
    FROM legislativo.tramitacao_executiva t
    JOIN legislativo.autografo a ON a.ente_id = t.ente_id AND a.id = t.autografo_id
   WHERE t.apreciado_em IS NOT NULL AND t.estado IN ('veto_mantido', 'veto_derrubado')
  UNION ALL
  SELECT n.ente_id, n.proposicao_id, n.promulgado_em, 'promulgada', 'ato:promulgada',
         format('Promulgação: %s nº %s/%s', COALESCE(e.nome, 'Norma'), n.numero, n.ano)
    FROM legislativo.norma n LEFT JOIN especie e ON e.tipo_norma = n.tipo_norma
  UNION ALL
  SELECT n.ente_id, n.proposicao_id, n.publicado_em, 'publicada', 'ato:publicada',
         format('Publicação: %s nº %s/%s', COALESCE(e.nome, 'Norma'), n.numero, n.ano)
    FROM legislativo.norma n LEFT JOIN especie e ON e.tipo_norma = n.tipo_norma
   WHERE n.publicado_em IS NOT NULL
)
SELECT atos.* FROM atos
  JOIN transparencia.materia m ON m.ente_id = atos.ente_id AND m.proposicao_id = atos.proposicao_id;
--;;
INSERT INTO transparencia.materia_movimentacao (ente_id, proposicao_id, ocorrido_em, etapa_chave, etapa, inicial)
SELECT ente_id, proposicao_id, ocorrido_em, chave, etapa, false FROM desfecho_backfill
ON CONFLICT DO NOTHING;
--;;
UPDATE transparencia.materia m
   SET desfecho = u.ato, desfecho_em = u.ocorrido_em
  FROM (SELECT DISTINCT ON (ente_id, proposicao_id) ente_id, proposicao_id, ato, ocorrido_em
          FROM desfecho_backfill
         ORDER BY ente_id, proposicao_id, ocorrido_em DESC) u
 WHERE m.ente_id = u.ente_id AND m.proposicao_id = u.proposicao_id;
--;;
ALTER TABLE legislativo.norma FORCE ROW LEVEL SECURITY;
--;;
ALTER TABLE legislativo.tramitacao_executiva FORCE ROW LEVEL SECURITY;
--;;
ALTER TABLE legislativo.autografo FORCE ROW LEVEL SECURITY;
--;;
ALTER TABLE legislativo.votacoes FORCE ROW LEVEL SECURITY;
--;;
ALTER TABLE transparencia.materia_movimentacao FORCE ROW LEVEL SECURITY;
--;;
ALTER TABLE transparencia.materia FORCE ROW LEVEL SECURITY;
