-- F3.4: modulo legislativo, eixo D (§22.4) — emendas. ENTIDADE PROPRIA (decisao do eixo D: descartado
-- "emenda como tipo de proposicao"). Numeracao LOCAL dentro da proposicao-mae (UNIQUE ente_id,mae,numero).
-- Ciclo de vida em ENUM SIMPLES na propria coluna `estado` (NAO o motor de templates do eixo C): o ciclo
-- da emenda e' universal entre camaras, entao nao e' DADO configuravel por tenant. Imutabilidade nivel b
-- (§22.4.3): mutavel durante a tramitacao, TRAVADA em estado terminal (helper compartilhado
-- shared.imut_trava_estado_terminal). Texto com a MESMA estrategia hibrida inline/URI do eixo B, mas SEM
-- versionamento (uma versao do texto na propria linha). `versao_texto_resultante_id` fecha o ciclo
-- bidirecional com proposicao_texto_versao quando a emenda aprovada gera o rascunho de consolidacao.
--
-- PARTICIONAMENTO: emendas NAO sao hash-particionadas (ao contrario de proposicoes/texto_versao). Razao =
-- a mesma do `cadastros` (decisao F1.1): cardinalidade limitada (dezenas por proposicao; isolamento por
-- RLS basta), e o FK same-tenant p/ as tabelas particionadas exige so incluir ente_id na referencia.
-- Revisitavel se a observabilidade mostrar volume que justifique particao.

CREATE TABLE IF NOT EXISTS legislativo.emendas (
  ente_id           uuid    NOT NULL,
  id                uuid    NOT NULL DEFAULT gen_random_uuid(),
  proposicao_mae_id uuid    NOT NULL,
  numero_local      integer NOT NULL,                      -- ordinal LOCAL dentro da proposicao-mae (1,2,3…)
  -- ---- classificacao (eixo D; vocabularios em legislativo.logic) ----
  tipo_emenda        text NOT NULL CHECK (tipo_emenda IN
    ('modificativa','supressiva','aditiva','substitutiva_total','substitutiva_parcial','aglutinativa','redacao')),
  momento_apresentacao text NOT NULL CHECK (momento_apresentacao IN ('no_prazo','plenario','redacao_final')),
  escopo_textual     text,                                 -- descritivo: a que parte da mae a emenda se aplica
  -- ---- autoria (guard ref; externo usa autor_texto) ----
  autor_tipo text CHECK (autor_tipo IS NULL OR autor_tipo IN
             ('vereador','mesa','comissao','executivo','cidadao')),
  autor_id   uuid,
  autor_texto text,
  -- ---- ciclo de vida (enum simples; UNICA coluna de estado mutavel ate o terminal) ----
  estado     text NOT NULL DEFAULT 'apresentada' CHECK (estado IN
    ('apresentada','admitida','aprovada','rejeitada','prejudicada','retirada')),
  -- ---- conteudo: hibrido inline/URI (XOR, <=32KB inline), MESMA estrategia do eixo B, SEM versionamento ----
  formato      text NOT NULL DEFAULT 'markdown',
  texto_inline text,
  conteudo_uri text,
  hash_conteudo text,
  -- ---- ciclo bidirecional com o texto-mae (preenchido na aprovacao; aponta o rascunho de consolidacao) ----
  versao_texto_resultante_id uuid,
  -- ---- transversais (§22.4.3 disc.1) ----
  origem    text NOT NULL DEFAULT 'nativa',
  origem_ref text,
  origem_importado_em timestamptz,
  lote_id   uuid,
  efetivado_em timestamptz,
  lock_version integer NOT NULL DEFAULT 0,
  created_by uuid,
  updated_by uuid,
  criado_em    timestamptz NOT NULL DEFAULT now(),
  atualizado_em timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (ente_id, id),
  -- numeracao LOCAL unica por proposicao-mae (barra corrida de numero_local)
  UNIQUE (ente_id, proposicao_mae_id, numero_local),
  -- integridade same-tenant declarativa: a mae existe no MESMO ente (FK inclui ente_id, lado da PK particionada)
  FOREIGN KEY (ente_id, proposicao_mae_id) REFERENCES legislativo.proposicoes (ente_id, id),
  -- o resultante (quando preenchido) e' uma versao de texto do MESMO ente
  FOREIGN KEY (ente_id, versao_texto_resultante_id) REFERENCES legislativo.proposicao_texto_versao (ente_id, id),
  CONSTRAINT emenda_conteudo_xor  CHECK ((texto_inline IS NOT NULL) <> (conteudo_uri IS NOT NULL)),
  CONSTRAINT emenda_inline_32kb   CHECK (texto_inline IS NULL OR octet_length(texto_inline) <= 32768),
  -- ciclo bidirecional coerente (review F3.4 DB-C1): emenda 'aprovada' SEM o rascunho resultante deixaria
  -- o vinculo permanentemente roto (a linha trava no terminal). aprovar! seta os dois na MESMA UPDATE.
  CONSTRAINT emenda_aprovada_requer_versao
    CHECK (estado <> 'aprovada' OR versao_texto_resultante_id IS NOT NULL)
);
--;;
-- staging das nao-efetivadas (import de legado, fundacao #2) — mesmo padrao dos demais.
CREATE INDEX IF NOT EXISTS idx_emendas_staging ON legislativo.emendas (ente_id, lote_id) WHERE efetivado_em IS NULL;
--;;
-- lado-filho da FK do resultante; parcial (anulavel ate a aprovacao).
CREATE INDEX IF NOT EXISTS idx_emendas_resultante
  ON legislativo.emendas (ente_id, versao_texto_resultante_id) WHERE versao_texto_resultante_id IS NOT NULL;
--;;
ALTER TABLE legislativo.emendas ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE legislativo.emendas FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON legislativo.emendas;
--;;
CREATE POLICY tenant_isolation ON legislativo.emendas
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid
         AND (efetivado_em IS NOT NULL
              OR lote_id = NULLIF(current_setting('app.ver_lote', true), '')::uuid))
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
-- sem DELETE (Inv.10): emenda nao se apaga — estados 'retirada'/'rejeitada'/'prejudicada' sao explicitos.
GRANT SELECT, INSERT, UPDATE ON legislativo.emendas TO oplenario_app;
--;;
-- imutabilidade (b) por estado terminal (§22.4.3): UPDATE travado quando OLD.estado e' terminal, exceto
-- sob correcao auditada (app.correcao_auditada). Reusa o helper compartilhado parametrizado (mig 0012).
CREATE TRIGGER trg_emendas_imut_estado
  BEFORE UPDATE ON legislativo.emendas
  FOR EACH ROW EXECUTE FUNCTION shared.imut_trava_estado_terminal('aprovada', 'rejeitada', 'prejudicada', 'retirada');
