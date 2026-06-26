-- modulo SUPRATENANT (3a categoria, §22.10): schema sem ente_id — e a raiz da tenancy.
-- Sem RLS / sem particao hash(ente_id): nao ha ente_id aqui; este schema EMITE o ente_id.
CREATE SCHEMA IF NOT EXISTS admin_sistema;

-- registry de entes (esqueleto): a tabela que emite o ente_id consumido por todos os modulos de dominio.
-- ente_id e PK supratenant; nenhum FK cross-schema aponta pra ca (cruza por guard de servico, §22.10).
--;;
CREATE TABLE IF NOT EXISTS admin_sistema.ente (
  ente_id      uuid PRIMARY KEY,
  nome         text NOT NULL,
  uf           text NOT NULL,
  estado       text NOT NULL DEFAULT 'provisionar',  -- provisionar|ativo|suspenso|encerrado
  criado_em    timestamptz NOT NULL DEFAULT now(),
  atualizado_em timestamptz NOT NULL DEFAULT now()
);
