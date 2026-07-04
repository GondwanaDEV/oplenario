-- Reverte a mig 0052 (F7 E3, carry). Remove so' a coluna adicionada; a tabela e as politicas RLS sao da 0051.
ALTER TABLE paineis.sli_sessao DROP COLUMN IF EXISTS agendada_para;
