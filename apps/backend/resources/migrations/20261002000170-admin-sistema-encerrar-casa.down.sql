ALTER TABLE admin_sistema.ente DROP CONSTRAINT IF EXISTS ente_destino_acervo_url;
--;;
ALTER TABLE admin_sistema.ente
  DROP COLUMN IF EXISTS apagamento, DROP COLUMN IF EXISTS destino_acervo_url, DROP COLUMN IF EXISTS encerrada_em;
--;;
ALTER TABLE admin_sistema.pedido_restricao DROP CONSTRAINT IF EXISTS pedido_restricao_motivo;
--;;
ALTER TABLE admin_sistema.pedido_restricao ADD CONSTRAINT pedido_restricao_motivo CHECK (
  (acao = 'suspender' AND motivo IN ('inadimplencia', 'pedido_da_casa', 'ordem_judicial', 'incidente_de_seguranca'))
  OR (acao = 'encerrar' AND motivo IN ('pedido_da_casa', 'fim_de_contrato')));
--;;
ALTER TABLE admin_sistema.pedido_restricao DROP CONSTRAINT IF EXISTS pedido_restricao_acao;
--;;
ALTER TABLE admin_sistema.pedido_restricao
  ADD CONSTRAINT pedido_restricao_acao CHECK (acao IN ('suspender', 'encerrar'));
--;;
DROP TABLE IF EXISTS admin_sistema.exportacao_casa;
