-- Portal do cidadao: a faixa "Onde este projeto esta" da ficha publica passa a seguir o RITO da Casa (a ordem e o
-- nome das etapas que a Casa declarou), como a ficha interna ja' faz, em vez de um mapa fixo por nome de estado.
--
-- O QUE MUDA. `transparencia.materia` ganha `rito` (jsonb, nullable): o ULTIMO rito da materia, projetado de
-- `proposicao.protocolada` e `proposicao.transicionou` (o evento passa a carregar a linha do rito ja' calculada por
-- `legislativo.logic.rito`). So' chave, rotulo e terminal de cada etapa: nada de responsavel, comissao ou id interno.
-- A projecao nao le' schema de `legislativo` (ADR-0001 §6); so' o evento.
--
-- AS TRES PERGUNTAS DA MIGRATION.
--   1. Muda dado existente? NAO. Coluna nova NULLABLE numa tabela de PROJECAO, sem DEFAULT e sem UPDATE: nenhuma linha
--      existente e' reescrita. Sem BACKFILL de proposito: materia que ainda nao teve evento novo fica com `rito` NULL e a
--      tela cai no comportamento anterior (mapa fixo). Nenhum registro historico (pauta, voto, ata, trilha, protocolo,
--      anexo, papel) e' tocado.
--   2. Pode falhar conforme o dado ou o relogio? NAO. `ADD COLUMN IF NOT EXISTS` de coluna nullable nao tem predicado,
--      data fixa nem indice; roda igual em tabela vazia ou cheia e e' reentrante.
--   3. Enxerga todas as Casas? Nao precisa: a migration nao le' nem escreve linha alguma (DDL puro), entao o FORCE RLS
--      de `transparencia.materia` fica como esta' (nenhum NO FORCE/FORCE).
ALTER TABLE transparencia.materia ADD COLUMN IF NOT EXISTS rito jsonb;
--;;
COMMENT ON COLUMN transparencia.materia.rito IS
$c$O ultimo rito da materia como a Casa o declara: {ordem-unica, etapas[], atual, anteriores, proximas}, cada etapa so' com chave, rotulo e terminal. NULL = a materia nao teve evento com rito (anterior ao campo, ou rito descartado por malformado): a tela usa o mapa fixo.$c$;
