-- Desfaz so' o indice. Os itens duplicados que o `.up.sql` retirou (ativo = false, com linha em `pauta_alteracao`)
-- NAO voltam a ficar ativos: reativar item nao existe no sistema, e a trilha append-only nao se reescreve.
DROP INDEX IF EXISTS sessoes.uq_pauta_item_materia_ativa;
