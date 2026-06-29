-- F3.8a: modulo legislativo, pos-aprovacao (§22.4; doc-mestre L247, features 3.13/3.14). DUAS tabelas:
-- (1) autografo: o ARTEFATO LEGAL — texto oficial aprovado enviado ao Executivo. Artefato legal (classe 3
--     de GAP 5: dominio dono, imutavel, NAO relatorio). APPEND-ONLY PURO: gerado uma vez, nunca muda
--     (a assinatura ICP-Brasil e' [GAP], track cripto/NFR). Numerado gapless por ente/ano (kernel/sequencial).
-- (2) tramitacao_executiva: o PROCESSO da resposta do Executivo (sancao/veto) — state machine que EVOLUI
--     (lock_version + trava terminal nivel b). Separada do autografo justamente porque o artefato e' imutavel
--     e a resposta evolui. Apreciacao do veto pela camara reusa a VOTACAO (eixo G, maioria absoluta).
-- Prazo de sancao/veto = DADO aqui (prazo_resposta_em); o PROCESSAMENTO (sancao tacita ao vencer o prazo) e'
-- o worker de prazo_dominio, DIFERIDO p/ F5 (mesmo padrao do proposicao_prazo_ativo deferido em F3.3a).
-- Rito EXATO do veto (prazos, base do quorum, veto parcial -> promulgacao parcial) e' [GAP] regimental
-- (especialista de regimento §22.4.4) — modelamos a forma defensavel, nao o conteudo por LOM.
-- NAO-particionadas (cardinalidade moderada como pareceres/emendas; FK same-tenant inclui ente_id).

-- ====================== AUTOGRAFO (artefato legal, append-only puro) ======================
CREATE TABLE IF NOT EXISTS legislativo.autografo (
  ente_id      uuid NOT NULL,
  id           uuid NOT NULL DEFAULT gen_random_uuid(),
  proposicao_id uuid NOT NULL,                           -- a proposicao aprovada que originou o autografo
  numero       integer NOT NULL,                         -- numero canonico do autografo (gapless por ente/ano)
  ano          integer NOT NULL,
  -- a versao de texto APROVADA (origem_versao='redacao_final', eixo B). Forward-ref (SEM FK declarativa:
  -- proposicao_texto_versao e' hash-particionada e a FK p/ ela exigiria carregar a PK composta; integridade
  -- na camada de servico + teste, mesmo criterio de sessao_id/origem_ref). E' o CONTEUDO do autografo.
  texto_versao_id uuid,
  destinatario_texto text NOT NULL,                      -- ex.: 'Prefeito Municipal de Fortaleza'
  destinatario_id    uuid,                               -- forward-ref ao cadastro do Executivo (sem FK)
  enviado_em   timestamptz NOT NULL DEFAULT now(),       -- envio ao Executivo = ato de geracao (V1)
  prazo_resposta_em timestamptz,                         -- prazo de sancao/veto (DADO; processamento = F5)
  -- transversais (§22.4.3 disc.1) — sem lock_version/estado: o autografo nao muda (append-only puro)
  origem    text NOT NULL DEFAULT 'nativa',
  origem_ref text,
  origem_importado_em timestamptz,
  lote_id   uuid,
  efetivado_em timestamptz,
  created_by uuid,
  criado_em  timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (ente_id, id),
  -- um autografo por proposicao (a aprovacao final gera UM autografo; veto/derrubada nao geram outro).
  -- a UNIQUE ja' indexa (ente_id, proposicao_id) — serve a FK e a busca por proposicao (review F3.8a DB-MAJOR).
  UNIQUE (ente_id, proposicao_id),
  -- numero canonico unico por ente/ano (espelha a numeracao gapless do kernel/sequencial escopo 'autografo:ano')
  UNIQUE (ente_id, ano, numero),
  -- um autografo efetivado tem que carregar seu CONTEUDO (a versao 'redacao_final' aprovada): append-only
  -- impede correcao silenciosa, entao um artefato legal sem texto seria vazio e permanente (review F3.8a DB-MENOR).
  CONSTRAINT autografo_efetivado_tem_texto CHECK (efetivado_em IS NULL OR texto_versao_id IS NOT NULL),
  FOREIGN KEY (ente_id, proposicao_id) REFERENCES legislativo.proposicoes (ente_id, id)
);
--;;
CREATE INDEX IF NOT EXISTS idx_autografo_staging ON legislativo.autografo (ente_id, lote_id) WHERE efetivado_em IS NULL;
--;;
ALTER TABLE legislativo.autografo ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE legislativo.autografo FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON legislativo.autografo;
--;;
CREATE POLICY tenant_isolation ON legislativo.autografo
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid
         AND (efetivado_em IS NOT NULL OR lote_id = NULLIF(current_setting('app.ver_lote', true), '')::uuid))
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
-- append-only puro (artefato legal imutavel): nem UPDATE nem DELETE (correcao = artefato novo via fluxo auditado).
GRANT SELECT, INSERT ON legislativo.autografo TO oplenario_app;
--;;
CREATE TRIGGER trg_autografo_append_only
  BEFORE UPDATE OR DELETE ON legislativo.autografo
  FOR EACH ROW EXECUTE FUNCTION shared.imut_append_only();
--;;
-- ====================== TRAMITACAO_EXECUTIVA (processo sancao/veto, state machine) ======================
CREATE TABLE IF NOT EXISTS legislativo.tramitacao_executiva (
  ente_id      uuid NOT NULL,
  id           uuid NOT NULL DEFAULT gen_random_uuid(),
  autografo_id uuid NOT NULL,                            -- a quem o Executivo responde (1:1 com o autografo)
  -- ciclo: aguardando -> {sancionado | sancao_tacita | vetado}; vetado -> {veto_mantido | veto_derrubado}.
  -- promulgavel (vira norma, F3.8b): sancionado | sancao_tacita | veto_derrubado. arquivada: veto_mantido.
  estado       text NOT NULL DEFAULT 'aguardando' CHECK (estado IN
    ('aguardando', 'sancionado', 'sancao_tacita', 'vetado', 'veto_mantido', 'veto_derrubado')),
  veto_tipo    text CHECK (veto_tipo IS NULL OR veto_tipo IN ('total', 'parcial')),
  veto_razoes  text,
  -- apreciacao do veto pela camara = VOTACAO (eixo G, maioria absoluta). FK same-tenant (votacoes nao-part.).
  veto_votacao_id uuid,
  respondido_em   timestamptz,                           -- quando o Executivo sancionou/vetou
  apreciado_em    timestamptz,                           -- quando a camara apreciou o veto
  -- transversais (§22.4.3 disc.1); estado muta -> updated_*/lock_version
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
  -- uma tramitacao executiva por autografo
  UNIQUE (ente_id, autografo_id),
  FOREIGN KEY (ente_id, autografo_id) REFERENCES legislativo.autografo (ente_id, id),
  FOREIGN KEY (ente_id, veto_votacao_id) REFERENCES legislativo.votacoes (ente_id, id),
  -- coerencia: veto_tipo so existe num estado de veto; TODO estado de veto (inclusive os terminais
  -- veto_mantido/veto_derrubado) exige veto_tipo. Cobrir os 3 estados fecha a janela em que a transicao
  -- 'vetado'->terminal zeraria veto_tipo e o trigger travaria a linha num terminal incoerente (review F3.8a DB-CRITICO).
  CONSTRAINT exec_veto_tipo_coerente
    CHECK (veto_tipo IS NULL OR estado IN ('vetado', 'veto_mantido', 'veto_derrubado')),
  CONSTRAINT exec_veto_requer_tipo
    CHECK (estado NOT IN ('vetado', 'veto_mantido', 'veto_derrubado') OR veto_tipo IS NOT NULL)
);
--;;
-- (sem idx_exec_autografo dedicado: a UNIQUE (ente_id, autografo_id) ja' indexa o prefixo — serve a FK e
--  a busca por autografo. Review F3.8a DB-MAJOR.)
CREATE INDEX IF NOT EXISTS idx_exec_estado ON legislativo.tramitacao_executiva (ente_id, estado);
--;;
-- lado-filho da FK da votacao de apreciacao; parcial (so as vetadas-apreciadas apontam votacao).
CREATE INDEX IF NOT EXISTS idx_exec_veto_votacao
  ON legislativo.tramitacao_executiva (ente_id, veto_votacao_id) WHERE veto_votacao_id IS NOT NULL;
--;;
CREATE INDEX IF NOT EXISTS idx_exec_staging ON legislativo.tramitacao_executiva (ente_id, lote_id) WHERE efetivado_em IS NULL;
--;;
ALTER TABLE legislativo.tramitacao_executiva ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE legislativo.tramitacao_executiva FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON legislativo.tramitacao_executiva;
--;;
CREATE POLICY tenant_isolation ON legislativo.tramitacao_executiva
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid
         AND (efetivado_em IS NOT NULL OR lote_id = NULLIF(current_setting('app.ver_lote', true), '')::uuid))
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
GRANT SELECT, INSERT, UPDATE ON legislativo.tramitacao_executiva TO oplenario_app;
--;;
-- imutabilidade (b) por estado terminal: os 4 desfechos travam (exceto correcao auditada). 'aguardando' e
-- 'vetado' sao intermediarios (a resposta/apreciacao ainda os move).
CREATE TRIGGER trg_exec_imut_estado
  BEFORE UPDATE ON legislativo.tramitacao_executiva
  FOR EACH ROW EXECUTE FUNCTION shared.imut_trava_estado_terminal('sancionado', 'sancao_tacita', 'veto_mantido', 'veto_derrubado');
