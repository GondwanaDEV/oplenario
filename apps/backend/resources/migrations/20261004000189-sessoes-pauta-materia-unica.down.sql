-- Desfaz o indice (a migration nao muta dado, entao nao ha' o que restaurar).
DROP INDEX IF EXISTS sessoes.uq_pauta_item_materia_ativa;
