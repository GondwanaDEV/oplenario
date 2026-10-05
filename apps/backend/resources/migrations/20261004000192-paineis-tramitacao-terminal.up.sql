-- paineis.tramitacao ganha `terminal`: o estado ATUAL da materia encerra o processo, segundo o RITO da Casa
-- (`legislativo.template_estado.terminal`). O manchete "Proposicoes em tramitacao" do dashboard da Mesa somava
-- TODOS os estados (aprovadas e arquivadas inclusive); `estado` e' texto livre por Casa (nunca se filtra por
-- nome), entao o fato tem de vir do rito: o evento `proposicao.transicionou` passou a carregar `para-terminal`
-- e o consumer grava aqui. DEFAULT false = "nao afirma fim de rito" (linha nova no protocolo, evento antigo).
ALTER TABLE paineis.tramitacao ADD COLUMN IF NOT EXISTS terminal boolean NOT NULL DEFAULT false;
--;;
COMMENT ON COLUMN paineis.tramitacao.terminal IS
$c$O estado atual encerra o processo segundo o rito da Casa (legislativo.template_estado.terminal), carimbado pelo evento proposicao.transicionou (para-terminal). false = em tramitacao OU rito nao declara o estado. Linhas anteriores a esta migration foram reconstruidas UMA vez pelo backfill abaixo.$c$;
--;;
-- BACKFILL (uma vez): materias ja projetadas e hoje em estado que o rito declara terminal. Mesmo desenho da
-- mig 0067: RLS e' FORCE nas tres tabelas, entao o papel de migration (que pode nao ser superuser) leria/
-- atualizaria ZERO linha em silencio; desliga FORCE so' durante este statement e religa logo depois. Leitura
-- cross-schema e' DDL de migration (one-shot), nao codigo de modulo — a regra 22.10 vale para o runtime, onde
-- o consumer so' projeta o que o evento carrega.
ALTER TABLE paineis.tramitacao NO FORCE ROW LEVEL SECURITY;
--;;
ALTER TABLE legislativo.proposicoes NO FORCE ROW LEVEL SECURITY;
--;;
ALTER TABLE legislativo.template_estado NO FORCE ROW LEVEL SECURITY;
--;;
UPDATE paineis.tramitacao t
   SET terminal = true
  FROM legislativo.proposicoes p
  JOIN legislativo.template_estado te
    ON te.ente_id = p.ente_id AND te.template_id = p.template_id AND te.chave = p.estado
 WHERE te.terminal
   AND p.ente_id = t.ente_id AND p.id = t.proposicao_id
   AND p.estado = t.estado;
--;;
ALTER TABLE legislativo.template_estado FORCE ROW LEVEL SECURITY;
--;;
ALTER TABLE legislativo.proposicoes FORCE ROW LEVEL SECURITY;
--;;
ALTER TABLE paineis.tramitacao FORCE ROW LEVEL SECURITY;
