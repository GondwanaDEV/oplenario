-- §22.6 eixo C, Etapa 2d (chamada-etapa2-escritas): o ATO de que a CHAMADA FOI CONDUZIDA. Hoje
-- `presenca_evento` so' grava QUEM APARECEU: uma sessao em que ninguem registrou nada e uma sessao em que a
-- Mesa chamou e a Casa TODA faltou produzem exatamente ZERO linhas — indistinguiveis. Essa ambiguidade
-- contamina a folha da sessao (Etapa 5) e qualquer apuracao de assiduidade (Etapa 6): uma apuracao que
-- confunde "ninguem chamou" com "todos faltaram" acusaria vereador de falta que nao houve.
--
-- `chamada_conduzida` registra o ato em si: QUANDO foi conduzida, QUEM conduziu, e quantos MEMBROS A CASA
-- TINHA naquele instante (o denominador do quorum, CONGELADO no momento do ato — resolvido em Clojure pela
-- MESMA fonte que `GET /sessoes/:id/chamada` usa, `logic/membros-da-casa-do-roster` sobre o roster de
-- `cadastros`, nunca uma conta em SQL a parte).
--
-- RECON (antes de escrever esta migration): a forma recomendada era um TIPO NOVO no CHECK de
-- `sessoes.incidente_processual` (mig 0035, precedente de ampliacao de CHECK = mig 0071). Descartada: essa
-- tabela tem `resultado NOT NULL CHECK IN ('deferido','indeferido','prejudicado','retirado')` — a semantica e'
-- "disposicao DELIBERADA de um incidente suscitado", e a chamada nao tem disposicao nenhuma (nao e' deferida
-- ou indeferida). Encaixar exigiria (a) tornar `resultado` nullable + CHECK de coerencia por tipo, (b) uma
-- coluna `membros_da_casa` NOVA que so' faz sentido p/ este tipo (2a CHECK de coerencia), (c) repropositar
-- `requerente_id` (hoje "quem SUSCITOU o incidente", tipicamente um vereador) para "quem CONDUZIU a chamada"
-- (tipicamente o secretario) — um desvio de significado do MESMO padrao que `decisao_mesa` evita mantendo
-- `presidente_id` separado de `created_by`, e (d) forjar um `descricao` NOT NULL sem conteudo de dominio real
-- (a chamada nao tem "o que foi suscitado" para descrever). Quatro alteracoes de schema numa tabela cuja
-- semantica de `resultado`/`descricao` nao serve este ato = desfigurar, nao estender. Tabela propria, MENOR
-- (uma tabela pequena e' mais barata que remodelar uma existente), e SEM enum/CHECK de vocabulario — por isso
-- este ato nao tem um par logic.clj<->CHECK de "tipo" para testar em paridade (A2 do enunciado da fatia): o
-- unico invariante numerico e' `membros_da_casa >= 0`, validado em `logic/validar-membros-da-casa` E aqui.
--
-- IDEMPOTENCIA (decisao, nao imposta pelo schema): SEM UNIQUE por sessao. A chamada PODE ser reconduzida na
-- mesma sessao — apos uma suspensao, ou para reverificar quorum a pedido da Mesa (mesmo racional de
-- `incidente_processual.tipo = 'verificacao_votacao'`, que tambem pode repetir). Cada linha e' um FATO
-- historico apartado com o SEU proprio denominador congelado: se um suplente foi convocado NO MEIO da sessao,
-- duas chamadas na mesma sessao podem legitimamente ter `membros_da_casa` diferentes, e sobrescrever a
-- primeira apagaria essa historia.
--
-- Mesmo molde de `incidente_processual`/`presenca_evento`: append-only puro (trigger barra UPDATE/DELETE),
-- staging (`origem`/`lote_id`/`efetivado_em`) para consistencia com o resto do schema (sem fluxo de import em
-- V1 — carry, [GAP] se um cliente trouxer acervo legado de listas de presenca).
CREATE TABLE IF NOT EXISTS sessoes.chamada_conduzida (
  ente_id    uuid NOT NULL,
  id         uuid NOT NULL DEFAULT gen_random_uuid(),
  sessao_id  uuid NOT NULL,
  conduzida_por uuid NOT NULL,                          -- quem conduziu (o ator autenticado; nunca do corpo)
  membros_da_casa integer NOT NULL CHECK (membros_da_casa >= 0),  -- o DENOMINADOR do quorum, CONGELADO
  ocorrido_em timestamptz NOT NULL,                     -- INSTANTE DE DOMINIO (quando a chamada foi conduzida)
  origem    text NOT NULL DEFAULT 'nativa',
  origem_ref text,
  origem_importado_em timestamptz,
  lote_id   uuid,
  efetivado_em timestamptz,
  created_by uuid,
  registrado_em timestamptz NOT NULL DEFAULT now(),     -- AUDIT (quando o sistema soube) != ocorrido_em
  PRIMARY KEY (ente_id, id),
  FOREIGN KEY (ente_id, sessao_id) REFERENCES sessoes.sessao (ente_id, id)  -- same-schema same-tenant
  -- SEM UNIQUE (ente_id, sessao_id): a chamada pode ser reconduzida na mesma sessao (decisao acima).
  -- SEM _staging_valido CHECK: append-only puro (trigger barra UPDATE/DELETE), padrao decisao_mesa/presenca_evento.
);
--;;
-- read-model "os atos de chamada desta sessao, em ordem" (embutido em GET /sessoes/:id/chamada).
CREATE INDEX IF NOT EXISTS idx_chamada_conduzida_sessao
  ON sessoes.chamada_conduzida (ente_id, sessao_id, ocorrido_em);
--;;
CREATE INDEX IF NOT EXISTS idx_chamada_conduzida_staging
  ON sessoes.chamada_conduzida (ente_id, lote_id) WHERE efetivado_em IS NULL;
--;;
ALTER TABLE sessoes.chamada_conduzida ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE sessoes.chamada_conduzida FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON sessoes.chamada_conduzida;
--;;
CREATE POLICY tenant_isolation ON sessoes.chamada_conduzida
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid
         AND (efetivado_em IS NOT NULL OR lote_id = NULLIF(current_setting('app.ver_lote', true), '')::uuid))
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
GRANT SELECT, INSERT ON sessoes.chamada_conduzida TO oplenario_app;
--;;
CREATE TRIGGER trg_chamada_conduzida_append_only
  BEFORE UPDATE OR DELETE ON sessoes.chamada_conduzida
  FOR EACH ROW EXECUTE FUNCTION shared.imut_append_only();
