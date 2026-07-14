-- Onda D Slice 4: EXCLUDE anti-overlap de mandato VIGENTE efetivado por (ente_id, vereador_id). A leitura da
-- Slice 3 nao podia criar sobreposicao; a escrita desta fatia pode -> fecha o carry (c) da Slice 3. Espelha
-- uq_uma_mesa_ativa (migration 0010): btree_gist ja' instalado; o staging (efetivado_em NULL) fica de fora
-- ate efetivar. O guard app-level do repo devolve 409 amigavel; ESTA constraint e' a rede (last line).
ALTER TABLE cadastros.mandato ADD CONSTRAINT uq_mandato_vigente_sem_overlap
  EXCLUDE USING gist (
    ente_id WITH =,
    vereador_id WITH =,
    daterange(vigencia_inicio, COALESCE(vigencia_fim, 'infinity'::date), '[]') WITH &&
  ) WHERE (estado = 'vigente' AND efetivado_em IS NOT NULL);
