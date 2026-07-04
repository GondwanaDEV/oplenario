-- F7 E3: modulo PAINEIS — SLI DE JANELA DE SESSAO (Invariante 9: "a sessao de quarta funciona?" e' SLI de
-- NEGOCIO de 1a classe, ao lado dos tecnicos; docs/11 F7 "SLI de janela de sessao monitorado"). Read-model
-- INTERNO (servidor/operador) que projeta o ciclo de vida da SESSAO PLENARIA — os eventos `sessao.transicionou`
-- que `sessoes` (F4) EMITE — numa VISTA por sessao com a JANELA (aberta_em -> encerrada_em) + estado atual. Nao
-- e' metrica de infra (Prometheus/Grafana = pilares 2/3 do Inv.7, infra-gated [GAP]); e' a modelagem de dominio
-- da janela de sessao que o Inv.9 exige ("modelagem de janelas de sessao no sistema, nao so' metricas de infra").
--
-- Mesma disciplina das outras projecoes de `paineis` (mig 0048/0049): dono da verdade continua `sessoes` (a
-- maquina de estado, CAS por lock_version); aqui so' a VISTA de observabilidade. Sem import/JOIN cross-modulo
-- (§22.10) — projetado do EVENTO. Sem colunas de staging/particao hash (PROJECAO, nao verdade de dominio). RLS
-- por ente_id obrigatoria (Inv.1 + Inv.8: todo sinal filtravel por ente_id). USAGE do schema `paineis` ja'
-- concedido (mig 0009); esta NAO e' a 1a migration do modulo.

-- ---------- sli_sessao: UMA linha por sessao. Chave natural = (ente_id, sessao_id): o consumer faz UPSERT a
--            cada `sessao.transicionou` (a 1a transicao INSERE; as seguintes atualizam a janela/estado). ----------
CREATE TABLE IF NOT EXISTS paineis.sli_sessao (
  ente_id         uuid NOT NULL,
  sessao_id       uuid NOT NULL,                          -- chave natural (ref sessoes por VALOR, sem FK §22.10)
  estado_atual    text NOT NULL,                          -- ULTIMO estado visto (`para` da transicao mais recente)
  aberta_em       timestamptz,                            -- 1a abertura (transicao -> 'aberta'); NULL ate' abrir
  encerrada_em    timestamptz,                            -- fechamento (transicao -> 'encerrada'|'nao_realizada')
  transicionou_em timestamptz NOT NULL,                   -- instante REAL (:ocorrido-em) da ULTIMA transicao aplicada
  projetado_em    timestamptz NOT NULL DEFAULT now(),     -- 1a projecao (auditoria da vista, nao do dominio)
  PRIMARY KEY (ente_id, sessao_id)
);
--;;
-- Leitura do SLI (`listar-sli-sessoes`): sessoes AINDA ABERTAS primeiro (encerrada_em IS NULL = as que podem
-- estar TRAVADAS, o sinal operacional que o SLI existe p/ pegar), depois as concluidas mais recentes primeiro.
-- O index cobre o escopo por tenant + a ordenacao temporal; o "aberta primeiro" e' um sort secundario barato
-- sobre a lista ja' tenant-restrita (cardinalidade limitada por ente).
CREATE INDEX IF NOT EXISTS idx_sli_sessao_recentes
  ON paineis.sli_sessao (ente_id, transicionou_em DESC);
--;;
ALTER TABLE paineis.sli_sessao ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE paineis.sli_sessao FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON paineis.sli_sessao;
--;;
CREATE POLICY tenant_isolation ON paineis.sli_sessao
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid)
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
-- UPSERT do consumer: INSERT (1a transicao) + UPDATE (transicoes seguintes). Sem DELETE (Inv.10 / re-projecao e' DDL).
GRANT SELECT, INSERT, UPDATE ON paineis.sli_sessao TO oplenario_app;
