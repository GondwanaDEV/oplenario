-- F3.1: modulo legislativo (§22.4 eixos A+H). Schema 'legislativo'. A proposicao e' o coracao do
-- produto. STI HIBRIDO (eixo A): tronco comum tipado + atributos quentes por tipo em colunas opcionais
-- + JSONB sidecar (atributos_especificos) so p/ o genuinamente heterogeneo (PDL e subtipos hoje).
-- Gate eixo H (ADR-0002): PK (ente_id,id) com id uuid logico; numeracao canonica gapless
-- (ente_id,tipo,ano,sequencial) UNIQUE; urn_lex computada no protocolo e IMUTAVEL; imutabilidade
-- pos-publicacao por trigger nivel (b). VOLUMOSA -> hash-particao por ente_id (a ferramenta de escala
-- que o cadastros adiou explicitamente p/ ca). uf/municipio p/ a URN sao FATO do ente resolvido
-- UPSTREAM (nao JOIN cross-schema, §22.10) — o schema legislativo nao referencia cadastros.

GRANT USAGE ON SCHEMA legislativo TO oplenario_app;
--;;
CREATE TABLE IF NOT EXISTS legislativo.proposicoes (
  ente_id  uuid    NOT NULL,
  id       uuid    NOT NULL DEFAULT gen_random_uuid(),   -- app gera via kernel/ids; DEFAULT = rede
  -- ---- numeracao canonica (eixo H) ----
  tipo       text    NOT NULL,                            -- especie (vocabulario em legislativo.logic/tipos)
  ano        integer NOT NULL,
  sequencial bigint  NOT NULL,                            -- gapless (kernel/sequencial, escopo 'tipo:ano')
  urn_lex    text    NOT NULL,                            -- coordenada LexML, computada no protocolo, imutavel
  -- ---- tronco comum (eixo A) ----
  ementa     text    NOT NULL,
  autor_tipo text CHECK (autor_tipo IS NULL OR autor_tipo IN
             ('vereador','mesa','comissao','executivo','cidadao')),  -- cidadao = iniciativa popular

  autor_id   uuid,                                        -- guard ref (sem FK cross-schema); externo usa autor_texto
  autor_texto text,
  estado     text    NOT NULL DEFAULT 'protocolada',      -- coarse; a maquina fina e' a tramitacao (F3.3)
  -- ---- atributos quentes por tipo (eixo A — colunas tipadas opcionais) ----
  objeto_indicacao   text,                                -- indicacao
  destinatario_id    uuid,                                -- indicacao/requerimento (guard ref)
  destinatario_texto text,
  tipo_requerimento  text,                                -- requerimento
  categoria_mocao    text,                                -- mocao
  -- ---- JSONB sidecar (so o heterogeneo: PDL e subtipos) ----
  atributos_especificos jsonb,
  -- ---- texto vigente (FK preenchida na F3.2; aponta a versao vigente) ----
  texto_vigente_versao_id uuid,
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
  -- numeracao oficial unica por ente; URN unica por ente (citacao cruzada)
  UNIQUE (ente_id, tipo, ano, sequencial),
  UNIQUE (ente_id, urn_lex),
  -- CHECK por tipo (eixo A): especie conhecida + atributo quente obrigatorio quando o tipo exige
  CONSTRAINT proposicao_tipo_conhecido CHECK (tipo IN (
    'projeto_lei','projeto_lei_complementar','projeto_resolucao','projeto_decreto_legislativo',
    'proposta_emenda_lom','indicacao','requerimento','mocao')),
  CONSTRAINT proposicao_indicacao_tem_objeto   CHECK (tipo <> 'indicacao'    OR objeto_indicacao  IS NOT NULL),
  CONSTRAINT proposicao_requerimento_tem_tipo  CHECK (tipo <> 'requerimento' OR tipo_requerimento IS NOT NULL),
  CONSTRAINT proposicao_mocao_tem_categoria    CHECK (tipo <> 'mocao'        OR categoria_mocao   IS NOT NULL)
) PARTITION BY HASH (ente_id);
--;;
-- VOLUMOSA: 8 particoes hash(ente_id) (a numeracao/RLS independem do modulo; aumentar e' op futura).
CREATE TABLE IF NOT EXISTS legislativo.proposicoes_p0 PARTITION OF legislativo.proposicoes FOR VALUES WITH (MODULUS 8, REMAINDER 0);
--;;
CREATE TABLE IF NOT EXISTS legislativo.proposicoes_p1 PARTITION OF legislativo.proposicoes FOR VALUES WITH (MODULUS 8, REMAINDER 1);
--;;
CREATE TABLE IF NOT EXISTS legislativo.proposicoes_p2 PARTITION OF legislativo.proposicoes FOR VALUES WITH (MODULUS 8, REMAINDER 2);
--;;
CREATE TABLE IF NOT EXISTS legislativo.proposicoes_p3 PARTITION OF legislativo.proposicoes FOR VALUES WITH (MODULUS 8, REMAINDER 3);
--;;
CREATE TABLE IF NOT EXISTS legislativo.proposicoes_p4 PARTITION OF legislativo.proposicoes FOR VALUES WITH (MODULUS 8, REMAINDER 4);
--;;
CREATE TABLE IF NOT EXISTS legislativo.proposicoes_p5 PARTITION OF legislativo.proposicoes FOR VALUES WITH (MODULUS 8, REMAINDER 5);
--;;
CREATE TABLE IF NOT EXISTS legislativo.proposicoes_p6 PARTITION OF legislativo.proposicoes FOR VALUES WITH (MODULUS 8, REMAINDER 6);
--;;
CREATE TABLE IF NOT EXISTS legislativo.proposicoes_p7 PARTITION OF legislativo.proposicoes FOR VALUES WITH (MODULUS 8, REMAINDER 7);
--;;
-- indice de staging (visao app.ver_lote das nao-efetivadas) — mesmo padrao da exemplar/cadastros.
CREATE INDEX IF NOT EXISTS idx_proposicoes_staging ON legislativo.proposicoes (ente_id, lote_id) WHERE efetivado_em IS NULL;
--;;
-- hot-path: listar/filtrar por estado dentro do ente (tramitacao board, "o que vence").
CREATE INDEX IF NOT EXISTS idx_proposicoes_estado ON legislativo.proposicoes (ente_id, estado);
--;;
-- lado-filho da FK do texto vigente (F3.2 fara JOIN proposicao_texto_versao); parcial (anulavel).
CREATE INDEX IF NOT EXISTS idx_proposicoes_texto_versao ON legislativo.proposicoes (ente_id, texto_vigente_versao_id) WHERE texto_vigente_versao_id IS NOT NULL;
--;;
-- hot-path "minhas proposicoes" (app do vereador): WHERE ente_id AND autor_id. Parcial (anulavel).
CREATE INDEX IF NOT EXISTS idx_proposicoes_autor ON legislativo.proposicoes (ente_id, autor_id) WHERE autor_id IS NOT NULL;
--;;
ALTER TABLE legislativo.proposicoes ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE legislativo.proposicoes FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON legislativo.proposicoes;
--;;
CREATE POLICY tenant_isolation ON legislativo.proposicoes
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid
         AND (efetivado_em IS NOT NULL
              OR lote_id = NULLIF(current_setting('app.ver_lote', true), '')::uuid))
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
-- sem DELETE (Inv.10): proposicao nao se apaga — arquiva-se (estado).
GRANT SELECT, INSERT, UPDATE ON legislativo.proposicoes TO oplenario_app;
--;;
-- imutabilidade (a) da IDENTIDADE CANONICA (ADR-0002 §4a): tipo/ano/sequencial/urn_lex/ente_id sao
-- congelados no protocolo e NUNCA mudam (mesmo durante tramitacao) — coluna-especifica (nivel c), por
-- isso funcao propria do modulo, nao o helper generico. Sem isto, um bug de UPDATE corromperia a
-- numeracao oficial / a citacao cruzada (incidente juridico).
CREATE OR REPLACE FUNCTION legislativo.proposicao_identidade_imutavel() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.tipo       IS DISTINCT FROM OLD.tipo
     OR NEW.ano        IS DISTINCT FROM OLD.ano
     OR NEW.sequencial IS DISTINCT FROM OLD.sequencial
     OR NEW.urn_lex    IS DISTINCT FROM OLD.urn_lex
     OR NEW.ente_id    IS DISTINCT FROM OLD.ente_id THEN
    RAISE EXCEPTION 'identidade canonica (tipo/ano/sequencial/urn_lex/ente_id) e imutavel apos o protocolo'
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END;
$$;
--;;
CREATE TRIGGER trg_proposicoes_imut_identidade
  BEFORE UPDATE ON legislativo.proposicoes
  FOR EACH ROW EXECUTE FUNCTION legislativo.proposicao_identidade_imutavel();
--;;
-- imutabilidade (b) pos-publicacao (ADR-0002 §4b): UPDATE travado em estado terminal, exceto correcao
-- auditada. Trigger no PARENT particionado (PG cascateia p/ as particoes). Terminais = publicada|arquivada.
CREATE TRIGGER trg_proposicoes_imut_estado
  BEFORE UPDATE ON legislativo.proposicoes
  FOR EACH ROW EXECUTE FUNCTION shared.imut_trava_estado_terminal('publicada', 'arquivada');
