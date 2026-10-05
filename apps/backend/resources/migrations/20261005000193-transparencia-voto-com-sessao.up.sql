-- transparencia.voto_parlamentar ganha `sessao_id`: a SESSAO em que o voto nominal foi dado. Sem ela o portal nao
-- sabia se a sessao era publica, e o CSV de dados abertos e o perfil publico do vereador publicavam o voto nominal
-- dado em sessao SECRETA (ou fechada ao publico), porque filtravam so' pela modalidade. A leitura passa a recortar
-- pelas sessoes que o portal pode mostrar (a regra do livro de atas e do portal de votacoes, decidida em `sessoes`
-- e entregue pelo host). O evento `voto.registrado` nominal ja' carrega `sessao-id`; o consumer passa a grava-lo.
-- NULL = "nao se sabe a sessao" e NUNCA aparece no portal (a leitura usa `= ANY(...)`, que nao casa NULL).
ALTER TABLE transparencia.voto_parlamentar ADD COLUMN IF NOT EXISTS sessao_id uuid;
--;;
COMMENT ON COLUMN transparencia.voto_parlamentar.sessao_id IS
$c$Sessao em que o voto nominal foi dado (payload de voto.registrado). O portal so' mostra o voto quando a sessao e' publica e nao secreta. NULL nunca aparece. Linhas anteriores a esta migration foram preenchidas UMA vez pelo backfill abaixo, a partir de legislativo.votacoes.$c$;
--;;
-- BACKFILL (uma vez), mesmo desenho da mig 0192: RLS e' FORCE nas duas tabelas, entao o papel de migration (que
-- pode nao ser superuser) leria/atualizaria ZERO linha em silencio; desliga FORCE so' durante o statement e religa
-- logo depois. Leitura cross-schema e' DDL de migration (one-shot), nao codigo de modulo — a regra 22.10 vale para
-- o runtime, onde o consumer so' projeta o que o evento carrega.
ALTER TABLE transparencia.voto_parlamentar NO FORCE ROW LEVEL SECURITY;
--;;
ALTER TABLE legislativo.votacoes NO FORCE ROW LEVEL SECURITY;
--;;
UPDATE transparencia.voto_parlamentar v
   SET sessao_id = lv.sessao_id
  FROM legislativo.votacoes lv
 WHERE lv.ente_id = v.ente_id AND lv.id = v.votacao_id
   AND v.sessao_id IS NULL;
--;;
ALTER TABLE legislativo.votacoes FORCE ROW LEVEL SECURITY;
--;;
ALTER TABLE transparencia.voto_parlamentar FORCE ROW LEVEL SECURITY;
