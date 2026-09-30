-- ADR-0018 (fatia 1): SUSPENDER, REATIVAR e INICIAR O ENCERRAMENTO de uma Casa pelo console do operador.
-- Supratenant como o resto do registro (sem ente_id de tenant, sem RLS): so' o role oplenario_operacao enxerga.
--
-- O registro ganha a RESTRICAO vigente (o motivo e desde quando) e a suspensao AGENDADA (aprovada, esperando a
-- sessao plenaria em curso encerrar — Eixo 2: nunca derrubar um plenario no meio da votacao).
ALTER TABLE admin_sistema.ente
  ADD COLUMN IF NOT EXISTS motivo_restricao   text,
  ADD COLUMN IF NOT EXISTS restrita_desde     timestamptz,
  ADD COLUMN IF NOT EXISTS suspensao_agendada uuid;
--;;
ALTER TABLE admin_sistema.ente DROP CONSTRAINT IF EXISTS ente_motivo_restricao_valido;
--;;
-- Eixo 1a: os quatro motivos fechados (sem "outro") + o encerramento em curso (Eixo 4.1). O motivo so' existe numa
-- Casa suspensa.
ALTER TABLE admin_sistema.ente ADD CONSTRAINT ente_motivo_restricao_valido
  CHECK (motivo_restricao IS NULL
         OR (estado = 'suspenso'
             AND motivo_restricao IN ('inadimplencia', 'pedido_da_casa', 'ordem_judicial', 'incidente_de_seguranca',
                                      'encerramento_em_curso')));
--;;
-- O PEDIDO de suspensao/encerramento e a decisao dele (two-person rule, Eixo 1b). Um operador pede, OUTRO aprova —
-- o banco recusa a mesma pessoa nas duas pontas. O incidente de seguranca suspende na hora e tem 24 h para a 2a
-- aprovacao (`confirmar_ate`); sem ela, o pedido expira e a Casa volta a ativa.
CREATE TABLE IF NOT EXISTS admin_sistema.pedido_restricao (
  id                    uuid PRIMARY KEY,
  ente_id               uuid NOT NULL REFERENCES admin_sistema.ente (ente_id),
  acao                  text NOT NULL,
  motivo                text NOT NULL,
  justificativa         text NOT NULL,
  estado                text NOT NULL DEFAULT 'aguardando',
  pedido_por            uuid NOT NULL REFERENCES admin_sistema.operador (id),
  pedido_em             timestamptz NOT NULL DEFAULT now(),
  confirmar_ate         timestamptz,
  decidido_por          uuid REFERENCES admin_sistema.operador (id),
  decidido_em           timestamptz,
  decisao_justificativa text,
  efetivado_em          timestamptz,
  CONSTRAINT pedido_restricao_acao CHECK (acao IN ('suspender', 'encerrar')),
  CONSTRAINT pedido_restricao_motivo CHECK (
    (acao = 'suspender' AND motivo IN ('inadimplencia', 'pedido_da_casa', 'ordem_judicial', 'incidente_de_seguranca'))
    OR (acao = 'encerrar' AND motivo IN ('pedido_da_casa', 'fim_de_contrato'))),
  CONSTRAINT pedido_restricao_estado CHECK (estado IN ('aguardando', 'aprovado', 'recusado', 'expirado', 'retirado')),
  CONSTRAINT pedido_restricao_justificativa CHECK (length(btrim(justificativa)) >= 10),
  CONSTRAINT pedido_restricao_duas_pessoas CHECK (decidido_por IS NULL OR decidido_por <> pedido_por),
  CONSTRAINT pedido_restricao_decisao CHECK ((estado IN ('aprovado', 'recusado')) = (decidido_por IS NOT NULL)),
  CONSTRAINT pedido_restricao_prazo_so_do_incidente
    CHECK ((confirmar_ate IS NOT NULL) = (motivo = 'incidente_de_seguranca'))
);
--;;
-- Um pedido aberto por Casa: a fila "aguardando 2o operador" nunca tem dois pedidos concorrentes para a mesma Casa.
CREATE UNIQUE INDEX IF NOT EXISTS pedido_restricao_um_aberto_por_casa
  ON admin_sistema.pedido_restricao (ente_id) WHERE estado = 'aguardando';
--;;
CREATE INDEX IF NOT EXISTS pedido_restricao_por_ente ON admin_sistema.pedido_restricao (ente_id, pedido_em DESC);
--;;
GRANT SELECT, INSERT, UPDATE ON admin_sistema.pedido_restricao TO oplenario_operacao;
