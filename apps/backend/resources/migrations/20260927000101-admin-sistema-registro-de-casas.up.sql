-- ADR-0016 (12.1): o REGISTRO DE CASAS ganha o que o console precisa para provisionar e acompanhar a Casa — o perfil
-- publico que ele entrega ao cadastros, o 1o administrador convidado e as datas do handoff. O ciclo
-- provisionar -> ativo acontece quando o 1o administrador entra (evento identidade.vinculo.primeiro_acesso).
ALTER TABLE admin_sistema.ente
  ADD COLUMN IF NOT EXISTS nome_curto                   text,
  ADD COLUMN IF NOT EXISTS municipio_ibge               text,
  ADD COLUMN IF NOT EXISTS municipio_nome               text,
  ADD COLUMN IF NOT EXISTS provisionada_por             uuid REFERENCES admin_sistema.operador (id),
  ADD COLUMN IF NOT EXISTS primeiro_admin_identidade_id uuid,
  ADD COLUMN IF NOT EXISTS primeiro_admin_email         text,
  ADD COLUMN IF NOT EXISTS convite_enviado_em           timestamptz,
  ADD COLUMN IF NOT EXISTS ativada_em                   timestamptz;
--;;
ALTER TABLE admin_sistema.ente DROP CONSTRAINT IF EXISTS ente_estado_valido;
--;;
ALTER TABLE admin_sistema.ente ADD CONSTRAINT ente_estado_valido
  CHECK (estado IN ('provisionar', 'ativo', 'suspenso', 'encerrado'));
--;;
-- O relay (que roda o consumidor do admin_sistema) ativa a Casa: mesmas permissoes do console sobre o registro.
GRANT USAGE ON SCHEMA admin_sistema TO oplenario_relay;
--;;
GRANT SELECT, UPDATE ON admin_sistema.ente TO oplenario_relay;
--;;
GRANT SELECT, INSERT ON admin_sistema.atuacao TO oplenario_relay;
--;;
GRANT USAGE ON SEQUENCE admin_sistema.atuacao_seq_seq TO oplenario_relay;
--;;
-- O operador cadastra o municipio de referencia da Casa que provisiona (a tabela nao e' semeada por migration).
GRANT USAGE ON SCHEMA cadastros TO oplenario_operacao;
--;;
GRANT SELECT, INSERT ON cadastros.municipios TO oplenario_operacao;
--;;
-- O 1o acesso de cada vinculo (so' a primeira vez): e' o que diz ao registro que a Casa passou as maos dela.
ALTER TABLE identidade.vinculo ADD COLUMN IF NOT EXISTS primeiro_acesso_em timestamptz;
