-- ADR-0020 (Eixo 1) — SETOR e' cadastro da Casa: o endereco interno (Secretaria, Juridico, Protocolo…) para onde se
-- manda um comunicado. Setor NAO da permissao — papel e' quem pode fazer, setor e' onde a pessoa esta'. O `admin_ente`
-- cria os setores em /administracao e diz quem e' de cada um; uma pessoa pode estar em mais de um.
--
-- (1) `cadastros.setor` — nome unico por Casa SEM diferenca de caixa ("Jurídico" e "jurídico" sao o mesmo setor).
--     Desativar (ativo=false) tira o setor do formulario de envio; os comunicados antigos continuam apontando para ele
--     (o comunicado guarda o NOME como era no envio, `comunicacao.destino.alvo_nome`). Nunca se apaga (Inv.10): sem
--     GRANT de DELETE. So' o nome e o `ativo` mudam.
--
-- (2) `cadastros.setor_membro` — a LOTACAO, que muda livremente: trocar a lotacao inteira e' apagar e inserir, numa tx
--     (por isso DELETE, so' aqui). A historia de quem esteve em qual setor fica na trilha de auditoria (ADR-0017: cada
--     PUT de membros e' um registro com o setor e quem mudou), e — o que importa — o comunicado CONGELA a lista de
--     destinatarios no envio (Eixo 2), entao mudar a lotacao nunca muda quem deveria ter lido um comunicado antigo.
--     `identidade_id` e' GUARD ref ao modulo identidade (sem FK cross-schema, §22.10): o host confere, no ato, que e'
--     uma pessoa com vinculo ativo nesta Casa.
--
-- Sem colunas de staging (lote_id/efetivado_em): setor nao e' alvo de importacao de legado — nasce na tela.
CREATE TABLE IF NOT EXISTS cadastros.setor (
  ente_id    uuid        NOT NULL,
  id         uuid        NOT NULL DEFAULT gen_random_uuid(),
  nome       text        NOT NULL CHECK (btrim(nome) <> '' AND char_length(nome) <= 120),
  ativo      boolean     NOT NULL DEFAULT true,
  criado_em  timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (ente_id, id)
);
--;;
CREATE UNIQUE INDEX IF NOT EXISTS uq_setor_nome_por_casa ON cadastros.setor (ente_id, lower(btrim(nome)));
--;;
CREATE TABLE IF NOT EXISTS cadastros.setor_membro (
  ente_id        uuid        NOT NULL,
  setor_id       uuid        NOT NULL,
  identidade_id  uuid        NOT NULL,                 -- GUARD ref (identidade), sem FK cross-schema
  desde          timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (ente_id, setor_id, identidade_id),
  FOREIGN KEY (ente_id, setor_id) REFERENCES cadastros.setor (ente_id, id)
);
--;;
-- "em quais setores esta' esta pessoa" (a tela de administracao e o filtro de pessoas ativas)
CREATE INDEX IF NOT EXISTS idx_setor_membro_identidade ON cadastros.setor_membro (ente_id, identidade_id);
--;;
ALTER TABLE cadastros.setor ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE cadastros.setor FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON cadastros.setor;
--;;
CREATE POLICY tenant_isolation ON cadastros.setor
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid)
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
GRANT SELECT, INSERT, UPDATE (nome, ativo) ON cadastros.setor TO oplenario_app;
--;;
ALTER TABLE cadastros.setor_membro ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE cadastros.setor_membro FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON cadastros.setor_membro;
--;;
CREATE POLICY tenant_isolation ON cadastros.setor_membro
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid)
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
GRANT SELECT, INSERT, DELETE ON cadastros.setor_membro TO oplenario_app;
