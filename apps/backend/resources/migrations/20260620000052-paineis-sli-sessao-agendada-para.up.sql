-- F7 E3 (fecha o carry): o SLI de janela de sessao passa a materializar a sessao JA' no agendamento (evento
-- sessao.agendada), nao so' a partir da 1a transicao — fechando a cegueira ao no-show 100% silencioso (a
-- sessao agendada que nunca sequer foi tocada), que e' o headline failure do proprio Inv.9. Para o painel
-- dizer "a sessao de quarta NAO aconteceu", a vista precisa saber PARA QUANDO estava marcada: nova coluna
-- `agendada_para`, carimbada na projecao do agendamento e preservada pelas transicoes seguintes.
--
-- ALTER (nao recria a tabela): mig 0051 ja' foi aplicada; Migratus nao a re-roda. Coluna nullable (nem toda
-- sessao tem data cravada no agendamento). RLS/grants ja' vigentes na tabela (mig 0051) cobrem a coluna nova.
ALTER TABLE paineis.sli_sessao ADD COLUMN IF NOT EXISTS agendada_para timestamptz;
