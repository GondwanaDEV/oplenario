-- ADR-0018 (fatia 2): ENCERRAR uma Casa — exportacao completa entregue, confirmacao de recebimento, janela de guarda
-- de 90 dias, APAGAMENTO aprovado por dois operadores e o estado final `encerrado`. Supratenant como o resto do
-- registro: sobrevive ao apagamento dos dados da Casa e e' a prova de que entregamos e de que apagamos (Eixo 4.6).
--
-- (1) A EXPORTACAO (9.6). Gerada pelo operador (no encerramento) ou pelo admin_ente (portabilidade, a qualquer
-- momento); o arquivo vai ao object storage e o registro guarda o hash e o manifesto. A Casa CONFIRMA o recebimento
-- (o admin_ente, na tela; ou o operador registra o oficio). O hash da exportacao confirmada fica aqui para sempre.
CREATE TABLE IF NOT EXISTS admin_sistema.exportacao_casa (
  id                   uuid PRIMARY KEY,
  ente_id              uuid NOT NULL REFERENCES admin_sistema.ente (ente_id),
  solicitada_por_tipo  text NOT NULL,
  solicitada_por       uuid NOT NULL,
  solicitada_em        timestamptz NOT NULL DEFAULT now(),
  estado               text NOT NULL DEFAULT 'gerando',
  chave_objeto         text,
  sha256               text,
  bytes                bigint,
  manifesto            jsonb,
  concluida_em         timestamptz,
  erro                 text,
  confirmada_em        timestamptz,
  confirmada_por_tipo  text,
  confirmada_por       uuid,
  confirmacao_texto    text,
  CONSTRAINT exportacao_casa_solicitante CHECK (solicitada_por_tipo IN ('operador', 'admin_ente')),
  CONSTRAINT exportacao_casa_estado CHECK (estado IN ('gerando', 'pronta', 'falhou')),
  CONSTRAINT exportacao_casa_pronta CHECK (
    (estado = 'pronta') = (chave_objeto IS NOT NULL AND sha256 IS NOT NULL AND concluida_em IS NOT NULL)),
  CONSTRAINT exportacao_casa_sha256 CHECK (sha256 IS NULL OR sha256 ~ '^[0-9a-f]{64}$'),
  -- so' se confirma o recebimento de uma exportacao PRONTA; quem confirma e' a Casa (admin_ente) ou o operador
  -- registrando o oficio da Casa, com o texto do oficio
  CONSTRAINT exportacao_casa_confirmacao CHECK (
    (confirmada_em IS NULL AND confirmada_por_tipo IS NULL AND confirmada_por IS NULL)
    OR (estado = 'pronta' AND confirmada_em IS NOT NULL AND confirmada_por IS NOT NULL
        AND confirmada_por_tipo IN ('admin_ente', 'oficio'))),
  CONSTRAINT exportacao_casa_oficio CHECK (
    confirmada_por_tipo IS DISTINCT FROM 'oficio' OR length(btrim(coalesce(confirmacao_texto, ''))) >= 10)
);
--;;
-- uma geracao em andamento por Casa
CREATE UNIQUE INDEX IF NOT EXISTS exportacao_casa_uma_gerando
  ON admin_sistema.exportacao_casa (ente_id) WHERE estado = 'gerando';
--;;
CREATE INDEX IF NOT EXISTS exportacao_casa_por_ente ON admin_sistema.exportacao_casa (ente_id, solicitada_em DESC);
--;;
GRANT SELECT, INSERT, UPDATE ON admin_sistema.exportacao_casa TO oplenario_operacao;
--;;
-- (2) O PEDIDO DE APAGAMENTO (Eixo 4.5): a mesma two-person rule do pedido de suspensao/encerramento, com a acao
-- `apagar` e o motivo unico `fim_da_guarda`. A funcao de apagamento (migration seguinte) confere no proprio banco que
-- o pedido foi aprovado por outro operador e que a guarda de 90 dias desde a confirmacao ja' passou.
ALTER TABLE admin_sistema.pedido_restricao DROP CONSTRAINT IF EXISTS pedido_restricao_acao;
--;;
ALTER TABLE admin_sistema.pedido_restricao
  ADD CONSTRAINT pedido_restricao_acao CHECK (acao IN ('suspender', 'encerrar', 'apagar'));
--;;
ALTER TABLE admin_sistema.pedido_restricao DROP CONSTRAINT IF EXISTS pedido_restricao_motivo;
--;;
ALTER TABLE admin_sistema.pedido_restricao ADD CONSTRAINT pedido_restricao_motivo CHECK (
  (acao = 'suspender' AND motivo IN ('inadimplencia', 'pedido_da_casa', 'ordem_judicial', 'incidente_de_seguranca'))
  OR (acao = 'encerrar' AND motivo IN ('pedido_da_casa', 'fim_de_contrato'))
  OR (acao = 'apagar' AND motivo = 'fim_da_guarda'));
--;;
-- (3) A Casa ENCERRADA: quando, para onde foi o acervo (o portal mostra "esta Camara nao usa mais O Plenario; os
-- documentos estao em ..."), e o RESUMO do apagamento (tabelas, totais, objetos, hash da exportacao entregue).
ALTER TABLE admin_sistema.ente
  ADD COLUMN IF NOT EXISTS encerrada_em       timestamptz,
  ADD COLUMN IF NOT EXISTS destino_acervo_url text,
  ADD COLUMN IF NOT EXISTS apagamento         jsonb;
--;;
ALTER TABLE admin_sistema.ente DROP CONSTRAINT IF EXISTS ente_destino_acervo_url;
--;;
ALTER TABLE admin_sistema.ente ADD CONSTRAINT ente_destino_acervo_url
  CHECK (destino_acervo_url IS NULL OR destino_acervo_url ~ '^https://[^\s]+$');
