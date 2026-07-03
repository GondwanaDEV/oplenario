-- F6c Slice 2: modulo TRANSPARENCIA — ACOMPANHAMENTO de proposicao pelo cidadao (§16.5). O cidadao AUTENTICADO
-- "segue" uma materia; depois consulta "minhas materias acompanhadas". Diferente das tabelas do Slice 1
-- (materia/norma = PROJECAO, read-model dropavel): `acompanhamento` e' VERDADE DE DOMINIO (a subscricao do
-- cidadao, o registro do consentimento de ser notificado — §22.5, consent-gated), logo leva a convencao
-- tenant-table completa (staging + RLS com clausula app.ver_lote), como participacao.comentario.
--
-- ESCOPO DESTA FATIA (decisao Daouda 03/07): SO' a subscricao (seguir/deixar de seguir/minhas). O PIPELINE de
-- NOTIFICACAO (fan-out por transicao + entrega de e-mail) fica no F7. Motivo: paineis (dono da entrega) e'
-- stub e o port de e-mail (fundacao #3) nao existe; alem disso o outbox descarta eventos sem consumidor (nao
-- e' log replayavel — carry HIGH-2 do Slice 1), entao emitir `novidade` agora seria um gatilho fantasma. A
-- subscricao e' um vertical completo e demonstravel por si.
--
-- COSTURA PARA O F7 (review architect — a direcao IMPORTA p/ nao vazar PII cross-modulo): o fan-out DEVE
-- morar em TRANSPARENCIA, nao em paineis. transparencia consome `proposicao.transicionou`/`norma.publicada`
-- (ambos carregam proposicao_id), faz JOIN com ESTA tabela (same-schema) e emite UM evento de entrega POR
-- destinatario (o seguidor_identidade_id) que paineis consome. paineis NAO pode ler transparencia.acompanhamento
-- (JOIN cross-schema proibido, §22.10) NEM ganhar um endpoint "liste os seguidores" (exportaria a PII
-- "quem-segue-o-que" pra fora do modulo). O UNIQUE (ente_id, proposicao_id, ...) abaixo ja indexa o prefixo
-- (ente_id, proposicao_id) que a query do fan-out "quem segue X" usa (com filtro residual de estado='ativo';
-- se um dia uma materia MUITO seguida com muito churn pedir, um indice parcial (ente_id,proposicao_id) WHERE
-- estado='ativo' e' o proximo passo — YAGNI agora).
--
-- CONSENTIMENTO (§22.5): o proprio ato de seguir E' o opt-in; o estado 'ativo'/'cancelado' registra a
-- vigencia ATUAL do consentimento. CARRY (review architect — Inv.10): esta linha e' MUTAVEL (UPSERT reativa,
-- soft-cancel), entao guarda SO' o estado corrente — NAO o historico de consentir/retirar (um seguir->cancelar->
-- seguir sobrescreve o carimbo do cancelamento; nao ha como provar QUANDO o cidadao retirou o consentimento
-- atraves de um re-seguir). Isto e' aceitavel p/ um opt-in de notificacao de baixo risco (checa-se estado='ativo'
-- antes de notificar), mas se um TRILHO LGPD FORMAL for exigido, o remedio pattern-consistent NAO e' tornar
-- esta tabela append-only (brigaria com o UPSERT) — e' uma tabela COMPANHEIRA append-only `acompanhamento_evento`
-- (espelhando participacao.moderacao_comentario), a criar quando o requisito surgir. O schema transparencia ja
-- existe (mig 0001); GRANT USAGE ja' foi concedido pela 1a migration do modulo (0044).

-- ---------- acompanhamento: subscricao do cidadao a uma materia. State-machine simples (ativo <-> cancelado)
--            via UPSERT (re-seguir reativa) + soft-cancel. UNIQUE (ente_id, proposicao_id, seguidor) = 1
--            subscricao por (cidadao, materia) = alvo do ON CONFLICT. ----------
CREATE TABLE IF NOT EXISTS transparencia.acompanhamento (
  ente_id       uuid NOT NULL,
  id            uuid NOT NULL DEFAULT gen_random_uuid(),
  -- forward-ref ao schema legislativo SEM FK (§22.10); tambem NAO ha FK a transparencia.materia (mesmo
  -- schema): materia e' PROJECAO dropavel e acompanhamento e' VERDADE — verdade nao deve depender de uma
  -- projecao re-projetavel. A existencia da materia e' checada no controller (guard, nao constraint).
  -- NOTA V2 (review architect): concreto em `proposicao_id` de proposito (§15 — nenhum requisito validado de
  -- seguir comissao/vereador). Se "seguir outros objetos" chegar, generalizar p/ (objeto_tipo, objeto_id)
  -- como protocolo_geral/incidente_processual/prazo_dominio_ativo ja fazem — o JOIN unico com `materia` abaixo
  -- nao sobrevive ao polimorfismo (tipos diferentes vivem em projecoes diferentes), entao seria um refactor.
  proposicao_id uuid NOT NULL,
  -- o cidadao que segue. INJETADO do ator na borda, NUNCA do corpo (anti-forge). Referencia a identidade
  -- (uuid), sem FK cross-schema.
  seguidor_identidade_id uuid NOT NULL,
  estado        text NOT NULL DEFAULT 'ativo' CHECK (estado IN ('ativo', 'cancelado')),
  -- transversais (§22.4.3 disc.1)
  origem        text NOT NULL DEFAULT 'nativa',
  origem_ref    text,
  origem_importado_em timestamptz,
  lote_id       uuid,
  efetivado_em  timestamptz,
  created_by    uuid,
  criado_em     timestamptz NOT NULL DEFAULT now(),
  atualizado_em timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (ente_id, id),
  -- 1 subscricao por (cidadao, materia): alvo do UPSERT de `seguir!` (re-seguir reativa a MESMA linha). Como
  -- lidera por (ente_id, proposicao_id), tambem serve a query FUTURA do fan-out do F7 ("quem segue a materia
  -- X") sem indice adicional.
  UNIQUE (ente_id, proposicao_id, seguidor_identidade_id),
  CONSTRAINT acompanhamento_staging_valido CHECK (lote_id IS NOT NULL OR efetivado_em IS NOT NULL)
);
--;;
-- "minhas materias acompanhadas" (por seguidor autenticado, so' ativas, mais recentes primeiro). Parcial em
-- 'ativo' (a listagem nunca mostra canceladas).
CREATE INDEX IF NOT EXISTS idx_acompanhamento_seguidor
  ON transparencia.acompanhamento (ente_id, seguidor_identidade_id, criado_em DESC)
  WHERE estado = 'ativo';
--;;
CREATE INDEX IF NOT EXISTS idx_acompanhamento_staging
  ON transparencia.acompanhamento (ente_id, lote_id) WHERE efetivado_em IS NULL;
--;;
ALTER TABLE transparencia.acompanhamento ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE transparencia.acompanhamento FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON transparencia.acompanhamento;
--;;
CREATE POLICY tenant_isolation ON transparencia.acompanhamento
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid
         AND (efetivado_em IS NOT NULL OR lote_id = NULLIF(current_setting('app.ver_lote', true), '')::uuid))
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
-- UPSERT (seguir/re-seguir) + UPDATE (soft-cancel). Sem DELETE (Inv.10 — o cancelamento e' de estado, o
-- registro do consentimento/retirada permanece).
GRANT SELECT, INSERT, UPDATE ON transparencia.acompanhamento TO oplenario_app;
