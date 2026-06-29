-- F4.5a: modulo SESSOES, eixo F do §22.6 — TRIBUNA (camada de INTENCAO). `inscricao_oradores` registra a
-- INTENCAO de falar; a EXECUCAO (`fala_executada`) e' entidade SEPARADA (F4.5b) — intencao != execucao: uma
-- inscricao pode terminar em `desistencia` SEM nunca virar fala. `origem_inscricao` discrimina os 4 caminhos
-- (app do vereador, secretaria, pedido intra-sessao, automatica por autoria). Subordinada a FASE da pauta (mesmo
-- enum de `pauta_item.fase`) — a tribuna nao e' ortogonal a pauta. Vinculo OPCIONAL a uma materia
-- (`proposicao_ref_id`, forward-ref §22.10). `vereador_id` tambem e' forward-ref a cadastros (sem FK cross-schema).
-- State machine pequena inscrita -> desistencia (terminal); MUTAVEL (situacao/ordem) -> lock_version + CAS, e o
-- terminal-lock congela a desistencia (igual justificativa_ausencia do eixo C). NAO-particionada (cardinalidade
-- moderada por sessao).
CREATE TABLE IF NOT EXISTS sessoes.inscricao_oradores (
  ente_id    uuid NOT NULL,
  id         uuid NOT NULL DEFAULT gen_random_uuid(),
  sessao_id  uuid NOT NULL,
  vereador_id uuid NOT NULL,                              -- forward-ref a cadastros.vereador (sem FK, §22.10)
  origem_inscricao text NOT NULL CHECK (origem_inscricao IN
    ('pre_sessao_app', 'pre_sessao_secretaria', 'intra_sessao_pedido', 'automatica_por_autoria')),
  -- FASE como atributo (mesmo enum de pauta_item): a tribuna e' subordinada a fase da pauta (§22.6 eixo F).
  -- NOTA: aqui 'tribuna_livre_cidadao' = um VEREADOR optando por falar nessa fase (esta tabela e' vereador-keyed,
  -- os 4 origem_inscricao sao fluxos de vereador). Orador CIDADAO (sem vereador_id) NAO entra aqui — a inscricao
  -- de cidadao na tribuna livre e' fora de escopo da V1; quando entrar, sera entidade propria (nao reusa esta).
  fase       text NOT NULL CHECK (fase IN
    ('expediente', 'grande_expediente', 'ordem_do_dia', 'explicacoes_pessoais', 'tribuna_livre_cidadao')),
  proposicao_ref_id uuid,                                 -- vinculo OPCIONAL a materia (forward-ref, §22.10)
  -- estado da intencao (nome `estado` p/ reusar shared.imut_trava_estado_terminal, como justificativa_ausencia).
  estado     text NOT NULL DEFAULT 'inscrita' CHECK (estado IN ('inscrita', 'desistencia')),
  ordem      integer NOT NULL,                            -- fila = max+1 por (sessao, fase); sort hint, nao identidade
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
  FOREIGN KEY (ente_id, sessao_id) REFERENCES sessoes.sessao (ente_id, id),  -- same-schema same-tenant
  CONSTRAINT inscricao_staging_valido CHECK (lote_id IS NOT NULL OR efetivado_em IS NOT NULL)
);
--;;
-- read-model "fila de oradores": inscricoes ATIVAS da sessao em ordem por fase. PARCIAL (WHERE estado='inscrita',
-- espelha idx_pauta_item_pauta WHERE ativo): a fila so mostra quem ainda vai falar; desistencias historicas saem
-- do indice (evita bloat acumulado ao longo dos anos + filtro em runtime no hot-path).
CREATE INDEX IF NOT EXISTS idx_inscricao_oradores_fila
  ON sessoes.inscricao_oradores (ente_id, sessao_id, fase, ordem) WHERE estado = 'inscrita';
--;;
CREATE INDEX IF NOT EXISTS idx_inscricao_oradores_staging
  ON sessoes.inscricao_oradores (ente_id, lote_id) WHERE efetivado_em IS NULL;
--;;
ALTER TABLE sessoes.inscricao_oradores ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE sessoes.inscricao_oradores FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON sessoes.inscricao_oradores;
--;;
CREATE POLICY tenant_isolation ON sessoes.inscricao_oradores
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid
         AND (efetivado_em IS NOT NULL OR lote_id = NULLIF(current_setting('app.ver_lote', true), '')::uuid))
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
GRANT SELECT, INSERT, UPDATE ON sessoes.inscricao_oradores TO oplenario_app;
--;;
-- terminal (b): a desistencia congela a inscricao; reabertura so sob correcao auditada (GUC app.correcao_auditada).
CREATE TRIGGER trg_inscricao_oradores_terminal
  BEFORE UPDATE ON sessoes.inscricao_oradores
  FOR EACH ROW EXECUTE FUNCTION shared.imut_trava_estado_terminal('desistencia');
