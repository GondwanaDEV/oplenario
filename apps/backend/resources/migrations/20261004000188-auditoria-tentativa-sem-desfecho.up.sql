-- ADR-0017 (adendo de 04/10/2026) — a TENTATIVA: toda escrita grava na corrente, ANTES do handler e em transacao
-- propria, que o ato vai comecar (`decisao = 'iniciado'`); o desfecho, gravado depois, aponta a tentativa em
-- `detalhe.tentativa` (o seq dela — o `detalhe` ja' entra no selo, entao o formato do selo NAO muda e os registros
-- antigos seguem conferindo). Tentativa sem desfecho = o ato pode ter acontecido sem registro: fica visivel.
--
-- (1) `decisao` passa a aceitar 'iniciado'. O CHECK da tabela particionada e' herdado: trocar no pai troca em TODAS
--     as particoes que existem, e as que `auditoria.garantir_particoes` criar nascem com ele. O nome do CHECK e'
--     achado pelo catalogo (e' o que o Postgres gerou): errar o nome deixaria o CHECK antigo recusando a tentativa.
-- (2) indice parcial do apontamento desfecho -> tentativa (so' as linhas de desfecho que apontam uma). Criado no
--     pai, vale para as particoes que existem e para as futuras.
-- Append-only: so' DDL, nenhuma linha muda.
DO $$
DECLARE c record;
BEGIN
  FOR c IN SELECT conname FROM pg_constraint
            WHERE conrelid = 'auditoria.registro'::regclass AND contype = 'c'
              AND pg_get_constraintdef(oid) LIKE '%decisao%'
  LOOP
    EXECUTE format('ALTER TABLE auditoria.registro DROP CONSTRAINT %I', c.conname);
  END LOOP;
END;
$$;
--;;
ALTER TABLE auditoria.registro ADD CONSTRAINT registro_decisao_check
  CHECK (decisao IN ('permitido', 'negado', 'falhou', 'iniciado'));
--;;
CREATE INDEX IF NOT EXISTS idx_registro_tentativa
  ON auditoria.registro (ente_id, ((detalhe->>'tentativa')::bigint))
  WHERE (detalhe->>'tentativa') IS NOT NULL;
