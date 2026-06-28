-- F3.6a: modulo legislativo, eixo F (§22.4) — parecer_comissao, NUCLEO. Entidade PROPRIA com state
-- machine PROPRIA governada pelo MESMO motor do eixo C (decisao (b) do workflow de reuso do motor): o
-- parecer reusa as MESMAS tabelas de template (template_tramitacao/estado/transicao — subject-agnosticas,
-- sem FK p/ proposicoes) e o MESMO avaliador (motor/guarda-dsl, disciplina 5), com estado e historico
-- PROPRIOS. NAO toca o engine do eixo C (so adiciona o discriminador de sujeito, aditivo).
--
-- DISCRIMINADOR DE SUJEITO (template_tramitacao.sujeito): as tabelas de template eram subject-agnosticas
-- (nada distinguia template-de-proposicao de template-de-parecer) — risco silencioso de um parecer ser
-- governado por um template de proposicao. Coluna aditiva, default 'proposicao' (preserva os templates do
-- eixo C), validada no criar! do parecer. Cresce por adicao (3o sujeito = eleicao da Mesa, §22.5).
ALTER TABLE legislativo.template_tramitacao
  ADD COLUMN IF NOT EXISTS sujeito text NOT NULL DEFAULT 'proposicao'
  CONSTRAINT template_tramitacao_sujeito_ck CHECK (sujeito IN ('proposicao', 'parecer'));
--;;
-- ============================ PARECER (entidade principal) ============================
-- NAO hash-particionada: cardinalidade moderada (como emendas/apensacao — alguns pareceres por objeto);
-- RLS basta; FK same-tenant p/ as tabelas particionadas exige so incluir ente_id. Revisitavel por obs.
CREATE TABLE IF NOT EXISTS legislativo.pareceres (
  ente_id     uuid NOT NULL,
  id          uuid NOT NULL DEFAULT gen_random_uuid(),
  -- ref polimorfica (disc.2): parecer sobre proposicao OU emenda. SEM FK declarativa (integridade em
  -- camadas: CHECK do tipo + indice por objeto_tipo + prova de existencia no criar! + testes dos 2 tipos).
  objeto_tipo text NOT NULL CHECK (objeto_tipo IN ('proposicao', 'emenda')),
  objeto_id   uuid NOT NULL,
  comissao_id uuid NOT NULL,                          -- guard ref (sem FK cross-schema p/ cadastros)
  relator_id  uuid,                                   -- designado em transicao posterior (NULL ate la)
  -- voto do relator na entity principal. SEM CHECK enum: vocabulario regimental ABERTO (§22.4.4 defere ao
  -- especialista: favoravel|contrario|favoravel_com_emendas|pela_constitucionalidade|etc).
  voto_relator text,
  -- estado TEMPLATE-DRIVEN (sem enum CHECK, como proposicoes.estado): derivado de template.estado_inicial
  -- no criar!; a maquina e' o motor (eixo C reusado). Os 4 terminais do trigger sao o piso fixo do eixo F.
  estado      text NOT NULL,
  template_id uuid NOT NULL,                          -- o template (de sujeito 'parecer') que rege o ciclo
  texto_vigente_versao_id uuid,                       -- ponteiro p/ parecer_texto_versao (F3.6b); NULL ate la
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
  -- o template e' do MESMO ente (FK same-tenant; o discriminador de sujeito e' validado no service)
  FOREIGN KEY (ente_id, template_id) REFERENCES legislativo.template_tramitacao (ente_id, id)
);
--;;
-- disc.2: indice composto COMECANDO por objeto_tipo (lookup de pareceres por proposicao/emenda; hot-path
-- do agregador da mae em F3.6c — pareceres.todos_concluidos etc.).
CREATE INDEX IF NOT EXISTS idx_pareceres_objeto ON legislativo.pareceres (ente_id, objeto_tipo, objeto_id);
--;;
-- enforcamento da FK template_id (convencao "FK always indexed", review F3.5 DB-M1 / F3.6a DB-MAJOR).
CREATE INDEX IF NOT EXISTS idx_pareceres_template_id ON legislativo.pareceres (ente_id, template_id);
--;;
-- hot-path "minhas pautas como relator" (app do vereador, §22.4); parcial (relator designado depois).
CREATE INDEX IF NOT EXISTS idx_pareceres_relator ON legislativo.pareceres (ente_id, relator_id) WHERE relator_id IS NOT NULL;
--;;
CREATE INDEX IF NOT EXISTS idx_pareceres_estado ON legislativo.pareceres (ente_id, estado);
--;;
CREATE INDEX IF NOT EXISTS idx_pareceres_staging ON legislativo.pareceres (ente_id, lote_id) WHERE efetivado_em IS NULL;
--;;
ALTER TABLE legislativo.pareceres ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE legislativo.pareceres FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON legislativo.pareceres;
--;;
CREATE POLICY tenant_isolation ON legislativo.pareceres
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid
         AND (efetivado_em IS NOT NULL OR lote_id = NULLIF(current_setting('app.ver_lote', true), '')::uuid))
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
-- sem DELETE (Inv.10): parecer nao se apaga — rejeitado/prejudicado/retirado sao estados explicitos.
GRANT SELECT, INSERT, UPDATE ON legislativo.pareceres TO oplenario_app;
--;;
-- imutabilidade (b) por estado terminal: os 4 terminais do eixo F (os 4 desfechos cravados em §22.4:34 —
-- aprovado/rejeitado/prejudicado/prazo_vencido) sao PISO FIXO no banco. ARMADILHA CONHECIDA (herdada de
-- proposicoes): o trigger le TG_ARGV literais, NAO template_estado.terminal — um template que marque
-- terminal=true num estado fora desses 4 para de oferecer saida no motor, mas o banco nao trava o UPDATE.
-- Documentado; o piso cobre o vocabulario cravado. ('prazo_vencido' e' terminal alcancavel; a materializacao
-- AUTOMATICA do vencimento de prazo e' [DIFERIDO F5] — overlap com proposicao_prazo_ativo/§22.7.7.)
CREATE TRIGGER trg_pareceres_imut_estado
  BEFORE UPDATE ON legislativo.pareceres
  FOR EACH ROW EXECUTE FUNCTION shared.imut_trava_estado_terminal('aprovado', 'rejeitado', 'prejudicado', 'prazo_vencido');
--;;
-- ============================ HISTORICO de transicao do parecer (APPEND-ONLY) ============================
-- NAO particionada (coerente com pareceres, baixa cardinalidade) — diferente de proposicao_transicao_historico,
-- cujo pai (proposicoes) e' particionado. RLS + indice composto bastam; revisitavel por observabilidade.
CREATE TABLE IF NOT EXISTS legislativo.parecer_transicao_historico (
  ente_id      uuid NOT NULL,
  id           uuid NOT NULL DEFAULT gen_random_uuid(),
  parecer_id   uuid NOT NULL,
  template_id  uuid NOT NULL,
  de_estado    text NOT NULL,
  para_estado  text NOT NULL,
  gatilho      text NOT NULL,
  contexto     jsonb,
  ator_id      uuid,
  lote_id      uuid,
  efetivado_em timestamptz,
  ocorrido_em  timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (ente_id, id),
  FOREIGN KEY (ente_id, parecer_id) REFERENCES legislativo.pareceres (ente_id, id)
);
--;;
CREATE INDEX IF NOT EXISTS idx_parecer_transicao_hist_parecer
  ON legislativo.parecer_transicao_historico (ente_id, parecer_id, ocorrido_em);
--;;
ALTER TABLE legislativo.parecer_transicao_historico ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE legislativo.parecer_transicao_historico FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON legislativo.parecer_transicao_historico;
--;;
CREATE POLICY tenant_isolation ON legislativo.parecer_transicao_historico
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid
         AND (efetivado_em IS NOT NULL OR lote_id = NULLIF(current_setting('app.ver_lote', true), '')::uuid))
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
-- append-only PURO (nivel a): a prova duravel da transicao nunca muda nem some (Inv.10).
GRANT SELECT, INSERT ON legislativo.parecer_transicao_historico TO oplenario_app;
--;;
CREATE TRIGGER trg_parecer_transicao_hist_append_only
  BEFORE UPDATE OR DELETE ON legislativo.parecer_transicao_historico
  FOR EACH ROW EXECUTE FUNCTION shared.imut_append_only();
