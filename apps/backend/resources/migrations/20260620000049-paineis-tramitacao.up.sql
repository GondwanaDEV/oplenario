-- F7 Slice 2: modulo PAINEIS — TRAMITACAO BOARD (§16.11, 2o entregavel de docs/11 F7, "read-model nao-
-- kanban"). Read-model INTERNO (servidor) da tramitacao — projeta os MESMOS eventos de `legislativo` que
-- `transparencia.materia` ja projeta (`proposicao.protocolada`/`proposicao.transicionou`). Review architect
-- (F7 Slice 2): a 2a projecao (em vez de estender transparencia.materia com um read interno) e' JUSTIFICADA
-- pela fronteira de CONTEXTO — `transparencia` e' o modulo do PORTAL PUBLICO ("conteudo publico por
-- natureza", mig 0044); dar-lhe um read-path interno sem-filtro (`secretario`) inverteria essa identidade e
-- tornaria o filtro estados-excluidos um risco de vazamento por-query (um filtro esquecido expoe estado
-- administrativo ao cidadao). Tabelas fisicamente separadas mantem o portal seguro-por-construcao e deixam
-- o board acumular campos SO-internos (hoje `transicionou_em`; amanha responsavel/prioridade/SLA) sem
-- churn de DDL no schema publico — NAO e' a coluna que justifica a 2a tabela (ver nota da coluna abaixo),
-- e' a fronteira publico/interno. Mesma projecao; dono da verdade continua `legislativo`; aqui so' a VISTA
-- interna.
--
-- Sem colunas de staging/particao hash (PROJECAO, nao verdade de dominio — mesmo racional de
-- transparencia/paineis.pendencia). RLS por ente_id obrigatoria (Inv.1). USAGE do schema `paineis` ja
-- concedido (mig 0009); esta NAO e' a 1a migration do modulo.

-- ---------- tramitacao: UM item do board por proposicao. Chave natural = (ente_id, proposicao_id): o
--            consumer faz INSERT no protocolo, UPDATE na transicao. ----------
CREATE TABLE IF NOT EXISTS paineis.tramitacao (
  ente_id       uuid NOT NULL,
  proposicao_id uuid NOT NULL,                          -- chave natural (ref legislativo por VALOR, sem FK §22.10)
  tipo          text NOT NULL,                           -- especie legislativa (pl, plc, resolucao, ...)
  ano           integer NOT NULL,
  sequencial    bigint  NOT NULL,
  urn_lex       text    NOT NULL,
  ementa        text    NOT NULL,
  autor_tipo    text,                                    -- vereador|comissao|mesa|executivo|... (nullable)
  autor_texto   text,                                    -- nome de exibicao do autor (nullable)
  estado        text    NOT NULL,                        -- estado ATUAL da tramitacao
  projetado_em    timestamptz NOT NULL DEFAULT now(),    -- 1a projecao (INSERT do snapshot)
  transicionou_em timestamptz NOT NULL DEFAULT now(),    -- instante da ULTIMA transicao (staleness do board)
  PRIMARY KEY (ente_id, proposicao_id)
);
--;;
-- board agrupado por estado, mais estagnado primeiro DENTRO do grupo (`transicionou_em` ASC = a materia
-- parada ha mais tempo naquele estado sobe ao topo — o sinal de "precisa de atencao" do board).
CREATE INDEX IF NOT EXISTS idx_tramitacao_board
  ON paineis.tramitacao (ente_id, estado, transicionou_em);
--;;
ALTER TABLE paineis.tramitacao ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE paineis.tramitacao FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON paineis.tramitacao;
--;;
CREATE POLICY tenant_isolation ON paineis.tramitacao
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid)
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
-- UPSERT do consumer: INSERT (snapshot) + UPDATE (estado+transicionou_em). Sem DELETE (Inv.10 / re-projecao e' DDL).
GRANT SELECT, INSERT, UPDATE ON paineis.tramitacao TO oplenario_app;
