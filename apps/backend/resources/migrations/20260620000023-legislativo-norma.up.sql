-- F3.8b: modulo legislativo, pos-aprovacao — NORMA (§22.4; doc-mestre L247, feature 3.15). A lei/ato
-- PROMULGADO: o desfecho da fronteira "da proposicao a' publicacao" (§15). Nasce de um desfecho PROMULGAVEL
-- da tramitacao executiva (sancionado | sancao_tacita | veto_derrubado — logic/promulgavel?). Artefato legal
-- (classe 3 de GAP 5: dominio dono, imutavel, NAO relatorio).
--
-- NUMERACAO CANONICA gapless por (ente, tipo_norma, ano) via kernel/sequencial (escopo 'norma:tipo:ano').
-- URN-de-NORMA (LexML) nasce AQUI (logic/urn-norma) e e' imutavel — a coordenada publica interoperavel da lei.
-- TEXTO promulgado = versao 'promulgacao' do eixo B (forward-ref texto_versao_id, sem FK — mesma razao do
-- autografo: proposicao_texto_versao e' particionada).
--
-- IMUTABILIDADE nivel (c) — MUTACAO PARCIAL (§22.4.3 disc.4): o conteudo legal (tipo/numero/ano/urn/texto/
-- ementa/proveniencia) CONGELA na promulgacao; so a PUBLICACAO muta a linha, UMA VEZ (promulgada -> publicada,
-- com publicado_em/veiculo). Trigger local `legislativo.norma_imut_parcial` (o helper compartilhado so cobre
-- a/b) — espelha o `apensacao_imut_parcial` (mig 0018).
--
-- NAO-particionada (cardinalidade moderada; FK same-tenant inclui ente_id).

CREATE TABLE IF NOT EXISTS legislativo.norma (
  ente_id      uuid NOT NULL,
  id           uuid NOT NULL DEFAULT gen_random_uuid(),
  proposicao_id uuid NOT NULL,                           -- a proposicao que virou norma (origem)
  autografo_id  uuid NOT NULL,                           -- o autografo cuja tramitacao foi promulgavel (lineage)
  tipo_norma   text NOT NULL CHECK (tipo_norma IN
    ('lei', 'lei_complementar', 'resolucao', 'decreto_legislativo', 'emenda_lom')),
  numero       integer NOT NULL,                         -- numero canonico gapless por (ente, tipo_norma, ano)
  ano          integer NOT NULL,
  urn          text NOT NULL,                            -- URN-de-norma LexML (nasce na promulgacao, imutavel)
  ementa       text NOT NULL,
  texto_versao_id uuid,                                  -- versao 'promulgacao' (eixo B); forward-ref, sem FK
  estado       text NOT NULL DEFAULT 'promulgada' CHECK (estado IN ('promulgada', 'publicada')),
  promulgado_em   timestamptz NOT NULL DEFAULT now(),
  promulgado_por  uuid,                                  -- forward-ref (quem promulgou: Presidente/Prefeito)
  -- ---- publicacao (preenchida uma vez, promulgada -> publicada) ----
  publicado_em      timestamptz,
  veiculo_publicacao text,                               -- onde foi publicada (DOM/mural/feed)
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
  -- uma norma por proposicao; a UNIQUE ja' indexa (ente_id, proposicao_id) — serve a FK e a busca.
  UNIQUE (ente_id, proposicao_id),
  -- numero canonico unico por (ente, tipo, ano) — espelha o escopo gapless 'norma:tipo:ano'
  UNIQUE (ente_id, tipo_norma, ano, numero),
  -- a URN-de-norma e' a coordenada publica unica
  UNIQUE (ente_id, urn),
  -- um autografo gera no maximo uma norma (a UNIQUE indexa o lado-filho da FK do autografo)
  UNIQUE (ente_id, autografo_id),
  FOREIGN KEY (ente_id, proposicao_id) REFERENCES legislativo.proposicoes (ente_id, id),
  FOREIGN KEY (ente_id, autografo_id)  REFERENCES legislativo.autografo (ente_id, id),
  -- coerencia BICONDICIONAL da publicacao: os campos de publicacao sao TOTALMENTE determinados pelo estado.
  -- promulgada => ambos NULL (o trigger deixa publicado_em/veiculo mutaveis, entao sem este lado um UPDATE
  -- preencheria a prova sem transicionar o estado = norma 'promulgada' com publicado_em — review F3.8b DB-MAJOR).
  -- publicada => ambos preenchidos (a prova da publicacao).
  CONSTRAINT norma_publicacao_coerente CHECK (
    (estado = 'promulgada' AND publicado_em IS NULL     AND veiculo_publicacao IS NULL)
    OR
    (estado = 'publicada'  AND publicado_em IS NOT NULL AND veiculo_publicacao IS NOT NULL)),
  -- norma efetivada carrega seu texto promulgado (artefato legal nao-vazio; mesma regra do autografo)
  CONSTRAINT norma_efetivada_tem_texto CHECK (efetivado_em IS NULL OR texto_versao_id IS NOT NULL)
);
--;;
CREATE INDEX IF NOT EXISTS idx_norma_estado ON legislativo.norma (ente_id, estado);
--;;
CREATE INDEX IF NOT EXISTS idx_norma_staging ON legislativo.norma (ente_id, lote_id) WHERE efetivado_em IS NULL;
--;;
ALTER TABLE legislativo.norma ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE legislativo.norma FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON legislativo.norma;
--;;
CREATE POLICY tenant_isolation ON legislativo.norma
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid
         AND (efetivado_em IS NOT NULL OR lote_id = NULLIF(current_setting('app.ver_lote', true), '')::uuid))
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
-- UPDATE permitido (so a publicacao, via trigger abaixo); sem DELETE (Inv.10).
GRANT SELECT, INSERT, UPDATE ON legislativo.norma TO oplenario_app;
--;;
-- imutabilidade (c) MUTACAO PARCIAL: o conteudo legal congela na promulgacao; so a publicacao muta a linha,
-- uma vez. Regra: (1) ja' publicada -> linha CONGELA (nenhum UPDATE); (2) efetivado_em e' one-way (anti
-- soft-delete via RLS, Inv.10); (3) so estado/publicado_em/veiculo_publicacao (+ bookkeeping) mudam — todo o
-- resto e' fato fixo. Excecao geral: correcao auditada (GUC app.correcao_auditada). Espelha mig 0018.
CREATE OR REPLACE FUNCTION legislativo.norma_imut_parcial() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  correcao text := NULLIF(current_setting('app.correcao_auditada', true), '');
BEGIN
  IF correcao IS NOT NULL THEN
    RETURN NEW;  -- fluxo de correcao auditada pode tudo (a auditoria registra o porque)
  END IF;
  -- (1) norma ja' publicada e' congelada
  IF OLD.estado = 'publicada' THEN
    RAISE EXCEPTION 'imutabilidade (c) norma: % ja publicada e imutavel (use correcao auditada)',
      OLD.id USING ERRCODE = 'check_violation';
  END IF;
  -- (2) efetivado_em nao regride a NULL (anti soft-delete via RLS, Inv.10); NULL->timestamp e' OK
  IF OLD.efetivado_em IS NOT NULL AND NEW.efetivado_em IS NULL THEN
    RAISE EXCEPTION 'imutabilidade (c) norma: efetivado_em nao volta a NULL (sem soft-delete via RLS)'
      USING ERRCODE = 'check_violation';
  END IF;
  -- (3) so estado/publicado_em/veiculo_publicacao mudam; o resto e' conteudo legal congelado
  IF NEW.ente_id            IS DISTINCT FROM OLD.ente_id
     OR NEW.id              IS DISTINCT FROM OLD.id
     OR NEW.proposicao_id   IS DISTINCT FROM OLD.proposicao_id
     OR NEW.autografo_id    IS DISTINCT FROM OLD.autografo_id
     OR NEW.tipo_norma      IS DISTINCT FROM OLD.tipo_norma
     OR NEW.numero          IS DISTINCT FROM OLD.numero
     OR NEW.ano             IS DISTINCT FROM OLD.ano
     OR NEW.urn             IS DISTINCT FROM OLD.urn
     OR NEW.ementa          IS DISTINCT FROM OLD.ementa
     OR NEW.texto_versao_id IS DISTINCT FROM OLD.texto_versao_id
     OR NEW.promulgado_em   IS DISTINCT FROM OLD.promulgado_em
     OR NEW.promulgado_por  IS DISTINCT FROM OLD.promulgado_por
     OR NEW.origem               IS DISTINCT FROM OLD.origem
     OR NEW.origem_ref           IS DISTINCT FROM OLD.origem_ref
     OR NEW.origem_importado_em  IS DISTINCT FROM OLD.origem_importado_em
     OR NEW.created_by           IS DISTINCT FROM OLD.created_by
     OR NEW.criado_em            IS DISTINCT FROM OLD.criado_em
  THEN
    RAISE EXCEPTION 'imutabilidade (c) norma: so estado/publicado_em/veiculo_publicacao sao mutaveis (publicacao)'
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END;
$$;
--;;
CREATE TRIGGER trg_norma_imut_parcial
  BEFORE UPDATE ON legislativo.norma
  FOR EACH ROW EXECUTE FUNCTION legislativo.norma_imut_parcial();
