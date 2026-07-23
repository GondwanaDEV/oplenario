-- Simetrico: devolve a coluna ao estado SEM comentario no catalogo (que e' o que a 0064 deixou — ela nunca
-- emitiu COMMENT; a prosa ficticia vivia so' no .sql).
COMMENT ON COLUMN transparencia.presenca_parlamentar.tipo IS NULL;
