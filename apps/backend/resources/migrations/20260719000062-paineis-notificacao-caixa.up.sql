-- Onda E fatia 1: modulo PAINEIS — INBOX interna (`notificacao_caixa`). SEGUNDA projecao do MESMO evento
-- `notificacao.requisitada`, com tabela PROPRIA (decisao D4 da spec): `notificacao_entrega` (mig 0004+0050)
-- e' um ledger de TENTATIVA DE ENTREGA POR CANAL (pendente->enviada|falha); um inbox e' a MENSAGEM + o
-- ESTADO DE LEITURA do destinatario. Enfiar `lida_em` naquele ledger misturaria os dois, e 'pendente'/
-- 'enviada' nao significam nada para in-app. Nenhuma tabela existente e' alterada; nenhum JOIN entre elas.
--
-- SEM PII: `destinatario_identidade_id` e' o UUID de identidade (handle pseudonimo, NAO e-mail/nome/CPF);
-- assunto/corpo derivam de dado PUBLICO (a norma publicada e' ato publico por natureza).
--
-- Inv.10: `lida_em` e' ESTADO ATUAL MUTAVEL, nao historico. Aceito e registrado (spec §4.4) — se um dia
-- houver exigencia formal de trilha de leitura, o remedio pattern-consistent e' um ledger COMPANHEIRO
-- append-only (espelhando participacao.moderacao_comentario), nunca tornar esta tabela append-only.
--
-- Sem colunas de staging (lote_id/efetivado_em) nem particao hash: e' PROJECAO, nao verdade de dominio
-- (mesmo racional das migs 0044/0048/0049) — nao ha importacao de legado de uma projecao.
--
-- NAO regrant `USAGE ON SCHEMA paineis` (mesma nota da mig 0048): a concessao pertence a mig 0009 e
-- schema-level GRANT nao e' contado por referencia — um REVOKE no down desfaria a 0009.
CREATE TABLE IF NOT EXISTS paineis.notificacao_caixa (
  id            uuid NOT NULL DEFAULT gen_random_uuid(),
  ente_id       uuid NOT NULL,
  -- a quem a mensagem e' endereçada (UUID de identidade; ref por VALOR, sem FK cross-schema §22.10)
  destinatario_identidade_id uuid NOT NULL,
  -- classe da MENSAGEM, nao estado de entrega (decisao D5): 'norma_publicada' nesta fatia;
  -- 'falha'/'prazo'/'sessao'/'sistema' quando um segundo produtor chegar.
  categoria     text NOT NULL,
  assunto       text NOT NULL,
  corpo         text NOT NULL,
  -- destino do clique — ref polimorfica OPACA (mesma convencao de paineis.pendencia/notificacao_entrega)
  objeto_tipo   text NOT NULL,
  objeto_id     uuid NOT NULL,
  -- chave DETERMINISTICA vinda do payload: um redrive/backfill do mesmo evento e' no-op (ON CONFLICT)
  idempotency_key text NOT NULL,
  criado_em     timestamptz NOT NULL DEFAULT now(),
  -- NULL = nao lida. Sem DEFAULT: a ausencia E' o estado inicial.
  lida_em       timestamptz,
  PRIMARY KEY (ente_id, id),
  UNIQUE (ente_id, idempotency_key)
);
--;;
-- o acesso da borda de leitura: "as minhas notificacoes, mais recentes primeiro" (GET /meu/notificacoes).
CREATE INDEX IF NOT EXISTS idx_notificacao_caixa_destinatario
  ON paineis.notificacao_caixa (ente_id, destinatario_identidade_id, criado_em DESC);
--;;
-- a CONTAGEM de nao lidas (parcial: o lido cresce sem limite mas sai do indice quente).
CREATE INDEX IF NOT EXISTS idx_notificacao_caixa_nao_lidas
  ON paineis.notificacao_caixa (ente_id, destinatario_identidade_id)
  WHERE lida_em IS NULL;
--;;
ALTER TABLE paineis.notificacao_caixa ENABLE ROW LEVEL SECURITY;
--;;
-- FORCE: nem o dono (se nao-superuser) bypassa — o consumer projeta como oplenario_app com o GUC setado.
ALTER TABLE paineis.notificacao_caixa FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON paineis.notificacao_caixa;
--;;
-- Projecao NAO tem staging (sem clausula app.ver_lote). NULLIF(...,'') = fail-closed sem GUC.
CREATE POLICY tenant_isolation ON paineis.notificacao_caixa
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid)
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
-- INSERT (projecao) + UPDATE (marcar lida). Sem DELETE (Inv.10; re-projecao seria TRUNCATE via DDL).
GRANT SELECT, INSERT, UPDATE ON paineis.notificacao_caixa TO oplenario_app;
