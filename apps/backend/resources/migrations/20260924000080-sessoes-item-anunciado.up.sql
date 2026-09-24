-- docs/23 Fatia 4b: o ATO DA MESA de ANUNCIAR um item da pauta — "passamos a apreciar o PL 22/2026". No sistema
-- antigo o operador "colocava a pauta" na TV antes de abrir a votacao; aqui isso nao e' um controle da TV
-- (decisao 4 do docs/23: nada de "mostrar qualquer coisa na TV" manual), e' um fato da sessao: QUANDO a Mesa
-- passou a apreciar QUAL item, e QUEM registrou. A TV, o telao e a ata leem o mesmo fato.
--
-- Tabela PROPRIA, nao um tipo novo em `pauta_alteracao`: aquela tabela registra ALTERACOES da pauta (inclusao,
-- inversao, exclusao, retirada) e alimenta o historico de versoes da pauta publicada; anunciar nao altera a
-- pauta. Encaixar ali faria o log de alteracoes contar anuncio como mudanca de pauta — o mesmo desvio de
-- significado que a mig 0072 recusou ao nao enfiar a chamada em `incidente_processual`.
--
-- O "item em apreciacao" de uma sessao e' o ULTIMO anuncio dela (por `anunciado_em`, desempate por id) — nao
-- ha' estado mutavel "item corrente": cada anuncio e' um FATO historico (append-only, como decisao_mesa/
-- chamada_conduzida), e reanunciar um item depois de outro e' legitimo (a Mesa volta a uma materia adiada).
-- Mesmo molde de `chamada_conduzida`: staging (`origem`/`lote_id`/`efetivado_em`) por consistencia de schema.
CREATE TABLE IF NOT EXISTS sessoes.item_anunciado (
  ente_id    uuid NOT NULL,
  id         uuid NOT NULL DEFAULT gen_random_uuid(),
  sessao_id  uuid NOT NULL,
  pauta_item_id uuid NOT NULL,
  anunciado_em timestamptz NOT NULL,                     -- INSTANTE DE DOMINIO (relogio do servidor na borda)
  origem    text NOT NULL DEFAULT 'nativa',
  origem_ref text,
  origem_importado_em timestamptz,
  lote_id   uuid,
  efetivado_em timestamptz,
  created_by uuid,                                       -- quem registrou (o operador; nunca do corpo)
  registrado_em timestamptz NOT NULL DEFAULT now(),      -- AUDIT (quando o sistema soube) != anunciado_em
  PRIMARY KEY (ente_id, id),
  FOREIGN KEY (ente_id, sessao_id) REFERENCES sessoes.sessao (ente_id, id),         -- same-schema same-tenant
  FOREIGN KEY (ente_id, pauta_item_id) REFERENCES sessoes.pauta_item (ente_id, id)  -- same-schema same-tenant
  -- SEM _staging_valido CHECK: append-only puro (trigger barra UPDATE/DELETE), padrao chamada_conduzida.
);
--;;
-- read-model "o item em apreciacao desta sessao" (o ultimo anuncio) e "os anuncios desta sessao, em ordem".
CREATE INDEX IF NOT EXISTS idx_item_anunciado_sessao
  ON sessoes.item_anunciado (ente_id, sessao_id, anunciado_em);
--;;
CREATE INDEX IF NOT EXISTS idx_item_anunciado_staging
  ON sessoes.item_anunciado (ente_id, lote_id) WHERE efetivado_em IS NULL;
--;;
ALTER TABLE sessoes.item_anunciado ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE sessoes.item_anunciado FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON sessoes.item_anunciado;
--;;
CREATE POLICY tenant_isolation ON sessoes.item_anunciado
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid
         AND (efetivado_em IS NOT NULL OR lote_id = NULLIF(current_setting('app.ver_lote', true), '')::uuid))
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
GRANT SELECT, INSERT ON sessoes.item_anunciado TO oplenario_app;
--;;
CREATE TRIGGER trg_item_anunciado_append_only
  BEFORE UPDATE OR DELETE ON sessoes.item_anunciado
  FOR EACH ROW EXECUTE FUNCTION shared.imut_append_only();
