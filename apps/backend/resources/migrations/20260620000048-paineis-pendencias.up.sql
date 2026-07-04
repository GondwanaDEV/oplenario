-- F7 Slice 1: modulo PAINEIS — "o que vence" / PENDENCIAS (§16.11, entregavel de docs/11 F7). Read-model
-- CROSS-MODULO agregando os relogios poli-morficos de `participacao` (e-SIC/recurso/LGPD/ouvidoria) numa
-- UNICA lista "o que vence" para o servidor/Mesa — hoje cada relogio so' e' visivel dentro do proprio
-- balcao de participacao; nada agrega os 4 numa vista so'. Projecao (§22.10): um consumer assina os
-- eventos de protocolo/fechamento/vencimento/prorrogacao de participacao e PROJETA aqui — sem import
-- cross-modulo nem JOIN cross-schema. O DONO da verdade do relogio continua sendo `participacao`
-- (pedido_esic/recurso_esic/solicitacao_titular/manifestacao_ouvidoria + prazo_ativo); aqui so' se
-- materializa a VISTA agregada para o servidor.
--
-- Sem colunas de staging (lote_id/efetivado_em) nem particao hash: PROJECAO, nao verdade de dominio
-- (mesmo racional de transparencia mig 0044/0045/0047) — nao ha caminho de importacao de legado de uma
-- projecao. RLS por ente_id obrigatoria (Inv.1): o consumer seta o GUC app.ente_id antes de projetar; a
-- leitura (rota interna, servidor) abre com-tenant* (RLS isola).
--
-- NAO e' a 1a migration do schema paineis (review clojure CRÍTICO): o schema nasce na mig 0001; a mig 0004
-- ja cria `paineis.notificacao_entrega`; e o `GRANT USAGE ON SCHEMA paineis TO oplenario_app` ja foi
-- concedido pela mig 0009 (retrofit de tenancy) — GRANT/REVOKE ON SCHEMA nao e' contado por referencia, um
-- REVOKE aqui desfaria a mig 0009 e quebraria `notificacao_entrega`. Por isso esta migration NAO regrant a
-- USAGE (idempotente seria inofensivo, mas o down simetrico teria que revoga-la — errado) nem terá REVOKE no
-- down.

-- ---------- pendencia: UM relogio poli-morfico (objeto_tipo/objeto_id) por (protocolado, fechamento,
--            vencimento, prorrogacao) de participacao. Chave natural = (ente_id, objeto_tipo, objeto_id):
--            o consumer faz INSERT no protocolo, UPDATE no fechamento/vencimento/prorrogacao. ----------
CREATE TABLE IF NOT EXISTS paineis.pendencia (
  ente_id      uuid NOT NULL,
  objeto_tipo  text NOT NULL CHECK (objeto_tipo IN
    ('pedido_esic', 'recurso_esic', 'solicitacao_titular', 'manifestacao_ouvidoria')),  -- espelha participacao.prazo_ativo
  objeto_id    uuid NOT NULL,                          -- chave natural (ref participacao por VALOR, sem FK §22.10)
  protocolo    text NOT NULL,                          -- numero humano de exibicao (ex.: 'ESIC-2026-000123')
  vence_em     date NOT NULL,                          -- vencimento CORRENTE (prorrogacao atualiza; sweep nao move)
  estado       text NOT NULL DEFAULT 'pendente' CHECK (estado IN ('pendente', 'vencido', 'concluido')),
  projetado_em  timestamptz NOT NULL DEFAULT now(),    -- 1a projecao (INSERT do protocolo)
  atualizado_em timestamptz NOT NULL DEFAULT now(),    -- ultima projecao (UPDATE de estado/vence_em)
  PRIMARY KEY (ente_id, objeto_tipo, objeto_id)
);
--;;
-- "o que vence" (§16.11): so' os itens ABERTOS (pendente|vencido), mais urgente primeiro. Index PARCIAL: o
-- concluido cresce sem limite mas nunca e' relido no caminho quente do painel.
CREATE INDEX IF NOT EXISTS idx_pendencia_o_que_vence
  ON paineis.pendencia (ente_id, vence_em)
  WHERE estado IN ('pendente', 'vencido');
--;;
ALTER TABLE paineis.pendencia ENABLE ROW LEVEL SECURITY;
--;;
-- FORCE: nem o dono (se nao-superuser) bypassa — o consumer projeta como oplenario_pool (NOBYPASSRLS) com o GUC setado.
ALTER TABLE paineis.pendencia FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON paineis.pendencia;
--;;
-- Projecao NAO tem staging (sem clausula app.ver_lote): so' o tenant do GUC ve/escreve. NULLIF(...,'') fail-closed.
CREATE POLICY tenant_isolation ON paineis.pendencia
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid)
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
-- UPSERT do consumer: INSERT (protocolo) + UPDATE (estado/vence_em). Sem DELETE (a projecao dropa por TRUNCATE em re-projecao, DDL).
GRANT SELECT, INSERT, UPDATE ON paineis.pendencia TO oplenario_app;
