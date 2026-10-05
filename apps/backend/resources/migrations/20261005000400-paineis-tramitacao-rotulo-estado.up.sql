-- Quadro de tramitacao e painel da Mesa: o NOME que o rito da Casa da' a etapa atual (`template_estado.nome`), como
-- a lista interna (LEFT JOIN na leitura) e a ficha (faixa pelo rito) ja' dizem. `paineis` e' read-model por EVENTO
-- (nunca le' `legislativo` em runtime, ADR-0001 §6), entao a coluna e' PROJETADA do rotulo que
-- `proposicao.protocolada` (`estado-nome`) e `proposicao.transicionou` (`para-nome`) ja' carregam (mig 0202).
--
-- AS TRES PERGUNTAS DA MIGRATION.
--   1. Muda dado existente? So' a coluna NOVA, numa tabela de PROJECAO: nenhuma coluna existente e' reescrita, nenhum
--      registro historico (pauta, voto, ata, trilha, protocolo) e' tocado. NULL = o rito nao declara o estado (ou a
--      projecao esta' atrasada): a tela cai no rotulo fixo.
--   2. Pode falhar conforme o dado ou o relogio? NAO. Sem data fixa, sem indice, sem NOT NULL; UNIQUE (ente_id,
--      template_id, chave) de template_estado garante que o JOIN nao multiplica linha. Reentrante.
--   3. Enxerga todas as Casas? Sim: BACKFILL (uma vez), mesmo desenho das migs 0202/0211 — leitura cross-schema so'
--      em DDL de migration, FORCE RLS desligado e religado na mesma transacao, so' materia efetivada. So' preenche
--      quando o estado do quadro e' o estado ATUAL da materia (projecao atrasada nao ganha o nome de outra etapa).
ALTER TABLE paineis.tramitacao ADD COLUMN IF NOT EXISTS rotulo_estado text;
--;;
COMMENT ON COLUMN paineis.tramitacao.rotulo_estado IS
$c$O nome que o rito da Casa da' ao estado atual (template_estado.nome), projetado de proposicao.protocolada (estado-nome) e proposicao.transicionou (para-nome). NULL = o rito nao declara o estado: a tela usa o rotulo fixo.$c$;
--;;
ALTER TABLE paineis.tramitacao NO FORCE ROW LEVEL SECURITY;
--;;
ALTER TABLE legislativo.proposicoes NO FORCE ROW LEVEL SECURITY;
--;;
ALTER TABLE legislativo.template_estado NO FORCE ROW LEVEL SECURITY;
--;;
UPDATE paineis.tramitacao t
   SET rotulo_estado = te.nome
  FROM legislativo.proposicoes p
  JOIN legislativo.template_estado te
    ON te.ente_id = p.ente_id AND te.template_id = p.template_id AND te.chave = p.estado
 WHERE t.ente_id = p.ente_id AND t.proposicao_id = p.id AND t.estado = p.estado
   AND p.efetivado_em IS NOT NULL
   AND t.rotulo_estado IS DISTINCT FROM te.nome;
--;;
ALTER TABLE legislativo.template_estado FORCE ROW LEVEL SECURITY;
--;;
ALTER TABLE legislativo.proposicoes FORCE ROW LEVEL SECURITY;
--;;
ALTER TABLE paineis.tramitacao FORCE ROW LEVEL SECURITY;
