-- F5.3b (review database MAJOR-1): indice de lookup p/ compliance.remessa_gerada. O unico indice nao-PK
-- existente (idx_remessa_gerada_costura) e' PARCIAL (WHERE estado='aceita') e serve so a costura. Os dois
-- caminhos quentes da geracao usam o predicado (ente_id, template_chave, competencia):
--   - db/inserir-versionada! : o SELECT do INSERT...SELECT computa COALESCE(MAX(versao),0)+1 (dentro da tx
--     de insercao — o scan segura o lock);
--   - db/listar              : historico de (re)emissoes, ORDER BY versao ASC.
-- Sem este indice, ambos fazem Seq Scan da particao logica do tenant, piorando linearmente com o acumulo
-- de versoes/competencias. `versao DESC` torna-o de cobertura p/ o MAX (Index Scan Backward pega o 1o) e
-- serve o ORDER BY de `listar` via scan invertido.

CREATE INDEX IF NOT EXISTS idx_remessa_gerada_lookup
  ON compliance.remessa_gerada (ente_id, template_chave, competencia, versao DESC);
