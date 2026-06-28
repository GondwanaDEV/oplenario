-- F3.7: modulo legislativo, eixo G (§22.4) — votacao. Tres tabelas:
-- (1) votacoes: o ATO de votar (objeto POLIMORFICO, modalidade, quorum como ENUM, estado, resultado).
-- (2) votos: voto NOMINAL atribuido (vereador_id, created_by) — APPEND-ONLY (correcao = nova votacao).
-- (3) votos_secretos: voto SECRETO — tabela SEPARADA p/ preservar o sigilo NO SCHEMA: SEM vereador_id e
--     SEM created_by (deliberado). Tambem append-only.
-- Quorum: enum (maioria_simples|absoluta|qualificada_2_3|qualificada_3_5); a VERIFICACAO e' aritmetica
-- EXATA (inteira, sem float — a armadilha do quorum) em legislativo.logic, gravada no encerramento.
-- Correcao de voto NUNCA e' UPDATE silencioso: anula-se a votacao e abre-se outra (votacao_corrige_id).
-- NAO-particionadas (cardinalidade moderada como pareceres/emendas; revisitavel por observabilidade).

-- ============================ VOTACOES (o ato) ============================
CREATE TABLE IF NOT EXISTS legislativo.votacoes (
  ente_id     uuid NOT NULL,
  id          uuid NOT NULL DEFAULT gen_random_uuid(),
  -- objeto POLIMORFICO (disc.2): votacao sobre proposicao|emenda|parecer|requerimento|redacao_final
  objeto_tipo text NOT NULL CHECK (objeto_tipo IN
    ('proposicao', 'emenda', 'parecer', 'requerimento', 'redacao_final')),
  objeto_id   uuid NOT NULL,
  modalidade  text NOT NULL CHECK (modalidade IN ('nominal', 'simbolica', 'secreta')),
  quorum_tipo text NOT NULL CHECK (quorum_tipo IN
    ('maioria_simples', 'maioria_absoluta', 'maioria_qualificada_2_3', 'maioria_qualificada_3_5')),
  estado      text NOT NULL DEFAULT 'aberta' CHECK (estado IN ('aberta', 'encerrada', 'anulada')),
  -- preenchidos no ENCERRAMENTO (snapshot do escrutinio + base usada na verificacao)
  resultado   text CHECK (resultado IS NULL OR resultado IN ('aprovada', 'rejeitada')),
  total_sim       integer,
  total_nao       integer,
  total_abstencao integer,
  base_membros    integer,                              -- composicao da Casa usada no quorum (snapshot)
  -- correcao = nova votacao inteira: aponta a votacao corrigida (a anterior vira 'anulada'). Ref p/ TRAS
  -- (a corrigida ja existe), entao FK same-tenant e' segura e barra apontar votacao de outro ente (DB-MAJOR).
  votacao_corrige_id uuid,
  sessao_id   uuid,                                     -- forward-ref p/ a sessao plenaria (F4); sem FK
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
  -- a votacao corrigida e' do MESMO ente (FK same-tenant; anti cross-tenant na coluna de correcao)
  FOREIGN KEY (ente_id, votacao_corrige_id) REFERENCES legislativo.votacoes (ente_id, id),
  -- coerencia do encerramento: 'encerrada' exige resultado (a prova do escrutinio)
  CONSTRAINT votacao_encerrada_requer_resultado
    CHECK (estado <> 'encerrada' OR resultado IS NOT NULL)
);
--;;
CREATE INDEX IF NOT EXISTS idx_votacoes_objeto ON legislativo.votacoes (ente_id, objeto_tipo, objeto_id);
--;;
-- lado-filho da FK de correcao; parcial (a maioria das votacoes nao corrige nada). "FK always indexed".
CREATE INDEX IF NOT EXISTS idx_votacoes_corrige_id
  ON legislativo.votacoes (ente_id, votacao_corrige_id) WHERE votacao_corrige_id IS NOT NULL;
--;;
CREATE INDEX IF NOT EXISTS idx_votacoes_estado ON legislativo.votacoes (ente_id, estado);
--;;
CREATE INDEX IF NOT EXISTS idx_votacoes_staging ON legislativo.votacoes (ente_id, lote_id) WHERE efetivado_em IS NULL;
--;;
ALTER TABLE legislativo.votacoes ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE legislativo.votacoes FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON legislativo.votacoes;
--;;
CREATE POLICY tenant_isolation ON legislativo.votacoes
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid
         AND (efetivado_em IS NOT NULL OR lote_id = NULLIF(current_setting('app.ver_lote', true), '')::uuid))
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
GRANT SELECT, INSERT, UPDATE ON legislativo.votacoes TO oplenario_app;
--;;
-- imutabilidade (b) por estado terminal: encerrada/anulada travam (exceto correcao auditada).
CREATE TRIGGER trg_votacoes_imut_estado
  BEFORE UPDATE ON legislativo.votacoes
  FOR EACH ROW EXECUTE FUNCTION shared.imut_trava_estado_terminal('encerrada', 'anulada');
--;;
-- ============================ VOTOS (nominal, atribuido, append-only) ============================
CREATE TABLE IF NOT EXISTS legislativo.votos (
  ente_id     uuid NOT NULL,
  id          uuid NOT NULL DEFAULT gen_random_uuid(),
  votacao_id  uuid NOT NULL,
  vereador_id uuid NOT NULL,                            -- guard ref (nominal: o voto e' atribuido)
  voto        text NOT NULL CHECK (voto IN ('sim', 'nao', 'abstencao')),
  origem       text NOT NULL DEFAULT 'nativa',
  origem_importado_em timestamptz,
  lote_id      uuid,
  efetivado_em timestamptz,
  created_by   uuid,
  registrado_em timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (ente_id, id),
  -- um voto por vereador por votacao (sem voto duplo; correcao = nova votacao inteira)
  UNIQUE (ente_id, votacao_id, vereador_id),
  FOREIGN KEY (ente_id, votacao_id) REFERENCES legislativo.votacoes (ente_id, id)
);
--;;
-- (sem idx_votos_votacao dedicado: a UNIQUE (ente_id,votacao_id,vereador_id) ja' indexa o prefixo
--  (ente_id,votacao_id) — serve a FK e a busca por votacao. Review F3.7 DB-MENOR.)
CREATE INDEX IF NOT EXISTS idx_votos_staging ON legislativo.votos (ente_id, lote_id) WHERE efetivado_em IS NULL;
--;;
ALTER TABLE legislativo.votos ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE legislativo.votos FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON legislativo.votos;
--;;
CREATE POLICY tenant_isolation ON legislativo.votos
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid
         AND (efetivado_em IS NOT NULL OR lote_id = NULLIF(current_setting('app.ver_lote', true), '')::uuid))
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
GRANT SELECT, INSERT ON legislativo.votos TO oplenario_app;
--;;
CREATE TRIGGER trg_votos_append_only
  BEFORE UPDATE OR DELETE ON legislativo.votos
  FOR EACH ROW EXECUTE FUNCTION shared.imut_append_only();
--;;
-- ============================ VOTOS SECRETOS (anonimo — sigilo no schema) ============================
-- SEM vereador_id e SEM created_by (deliberado, §22.4 eixo G): o sigilo e' estrutural, nao so de aplicacao.
-- Quem VOTOU (presenca/escrutinio) e' rastreado a parte na sessao (F4) — a prevencao de voto-duplo no
-- secreto e' procedimental (cedula), NAO ha como (nem se deve) ligar voto->votante aqui. [carry F4]
CREATE TABLE IF NOT EXISTS legislativo.votos_secretos (
  ente_id     uuid NOT NULL,
  id          uuid NOT NULL DEFAULT gen_random_uuid(),
  votacao_id  uuid NOT NULL,
  voto        text NOT NULL CHECK (voto IN ('sim', 'nao', 'abstencao')),
  origem       text NOT NULL DEFAULT 'nativa',
  origem_importado_em timestamptz,
  lote_id      uuid,
  efetivado_em timestamptz,
  registrado_em timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (ente_id, id),
  FOREIGN KEY (ente_id, votacao_id) REFERENCES legislativo.votacoes (ente_id, id)
);
--;;
CREATE INDEX IF NOT EXISTS idx_votos_secretos_votacao ON legislativo.votos_secretos (ente_id, votacao_id);
--;;
CREATE INDEX IF NOT EXISTS idx_votos_secretos_staging ON legislativo.votos_secretos (ente_id, lote_id) WHERE efetivado_em IS NULL;
--;;
ALTER TABLE legislativo.votos_secretos ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE legislativo.votos_secretos FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON legislativo.votos_secretos;
--;;
CREATE POLICY tenant_isolation ON legislativo.votos_secretos
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid
         AND (efetivado_em IS NOT NULL OR lote_id = NULLIF(current_setting('app.ver_lote', true), '')::uuid))
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
GRANT SELECT, INSERT ON legislativo.votos_secretos TO oplenario_app;
--;;
CREATE TRIGGER trg_votos_secretos_append_only
  BEFORE UPDATE OR DELETE ON legislativo.votos_secretos
  FOR EACH ROW EXECUTE FUNCTION shared.imut_append_only();
