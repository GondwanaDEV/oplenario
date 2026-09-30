-- ADR-0019 fatia 1 — o PARECER JURIDICO da Casa: o pedido (sobre uma materia ou uma consulta avulsa da Presidencia) e o
-- parecer do advogado da Casa. Opinativo sempre: nada aqui move a materia no rito. Duas tabelas por Casa (RLS):
--
-- (1) `legislativo.pedido_parecer_juridico` — quem pediu, sobre o que, ate' quando. `proposicao_id` NULL = consulta
--     avulsa (decoro, contas, admissibilidade de CPI): o Presidente consulta o juridico sem matéria. A secretaria
--     pede em nome da Presidencia (`em_nome_de`) ou o relator da comissao pede sobre a materia que relata.
--
-- (2) `legislativo.parecer_juridico` — o texto (relatorio, fundamentacao, conclusao), da versao rascunho ate' a
--     assinatura. ASSINADO E' IMUTAVEL (trigger): corrigir e' emitir um parecer novo que SUBSTITUI o anterior
--     (`substitui_id`), e os dois ficam na ficha. A assinatura grava, no ato, o nome, a OAB e a qualificacao de quem
--     assinou (efetivo, comissionado ou contratado) — o parecer diz a que titulo foi assinado — e o numero/ano
--     sequencial da Casa. O texto de maquina (a nota tecnica do agente) nunca vira parecer sem uma pessoa assinar.
CREATE TABLE IF NOT EXISTS legislativo.pedido_parecer_juridico (
  ente_id        uuid NOT NULL,
  id             uuid NOT NULL DEFAULT gen_random_uuid(),
  proposicao_id  uuid,                                         -- NULL = consulta avulsa
  assunto        text NOT NULL CHECK (length(btrim(assunto)) BETWEEN 5 AND 300),
  prazo          date,                                         -- opcional: nao ha' relogio legal, e' combinado
  origem         text NOT NULL CHECK (origem IN ('secretaria', 'relator')),
  pedido_por     uuid NOT NULL,                                -- identidade de quem registrou o pedido
  em_nome_de     text CHECK (em_nome_de IS NULL OR length(btrim(em_nome_de)) BETWEEN 1 AND 80),
  estado         text NOT NULL DEFAULT 'pendente' CHECK (estado IN ('pendente', 'atendido', 'cancelado')),
  cancelado_por  uuid,
  cancelado_em   timestamptz,
  criado_em      timestamptz NOT NULL DEFAULT now(),
  atualizado_em  timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (ente_id, id),
  FOREIGN KEY (ente_id, proposicao_id) REFERENCES legislativo.proposicoes (ente_id, id),
  CONSTRAINT pedido_cancelamento_completo CHECK (
    (estado = 'cancelado') = (cancelado_por IS NOT NULL AND cancelado_em IS NOT NULL))
);
--;;
CREATE INDEX IF NOT EXISTS idx_pedido_parecer_juridico_fila
  ON legislativo.pedido_parecer_juridico (ente_id, estado, criado_em);
--;;
CREATE INDEX IF NOT EXISTS idx_pedido_parecer_juridico_materia
  ON legislativo.pedido_parecer_juridico (ente_id, proposicao_id) WHERE proposicao_id IS NOT NULL;
--;;
ALTER TABLE legislativo.pedido_parecer_juridico ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE legislativo.pedido_parecer_juridico FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON legislativo.pedido_parecer_juridico;
--;;
CREATE POLICY tenant_isolation ON legislativo.pedido_parecer_juridico
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid)
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
GRANT SELECT, INSERT, UPDATE (estado, cancelado_por, cancelado_em, atualizado_em)
  ON legislativo.pedido_parecer_juridico TO oplenario_app;
--;;
CREATE TABLE IF NOT EXISTS legislativo.parecer_juridico (
  ente_id                 uuid NOT NULL,
  id                      uuid NOT NULL DEFAULT gen_random_uuid(),
  pedido_id               uuid NOT NULL,
  proposicao_id           uuid,                                -- do pedido (a ficha e o portal leem por materia)
  estado                  text NOT NULL DEFAULT 'rascunho' CHECK (estado IN ('rascunho', 'assinado')),
  relatorio               text NOT NULL DEFAULT '' CHECK (length(relatorio) <= 30000),
  fundamentacao           text NOT NULL DEFAULT '' CHECK (length(fundamentacao) <= 60000),
  conclusao               text CHECK (conclusao IS NULL OR
                                      conclusao IN ('favoravel', 'contrario', 'com_ressalvas', 'orientacao')),
  substitui_id            uuid,                                -- o parecer assinado que este corrige
  autor_id                uuid NOT NULL,                       -- quem abriu o rascunho (identidade)
  numero                  integer,                             -- sequencial da Casa no ano, so' quando assinado
  ano                     integer,
  assinado_por            uuid,
  assinado_em             timestamptz,
  assinatura_nome         text,
  assinatura_oab          text,
  assinatura_qualificacao text CHECK (assinatura_qualificacao IS NULL OR
                                      assinatura_qualificacao IN ('efetivo', 'comissionado', 'contratado')),
  criado_em               timestamptz NOT NULL DEFAULT now(),
  atualizado_em           timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (ente_id, id),
  UNIQUE (ente_id, ano, numero),
  FOREIGN KEY (ente_id, pedido_id) REFERENCES legislativo.pedido_parecer_juridico (ente_id, id),
  FOREIGN KEY (ente_id, substitui_id) REFERENCES legislativo.parecer_juridico (ente_id, id),
  CONSTRAINT parecer_juridico_assinatura_completa CHECK (
    (estado = 'assinado') = (assinado_por IS NOT NULL AND assinado_em IS NOT NULL AND numero IS NOT NULL
                             AND ano IS NOT NULL AND assinatura_nome IS NOT NULL AND assinatura_oab IS NOT NULL
                             AND assinatura_qualificacao IS NOT NULL AND conclusao IS NOT NULL
                             AND btrim(relatorio) <> '' AND btrim(fundamentacao) <> ''))
);
--;;
-- um rascunho por pedido, e um unico parecer (rascunho ou assinado) que substitui cada assinado
CREATE UNIQUE INDEX IF NOT EXISTS uq_parecer_juridico_rascunho
  ON legislativo.parecer_juridico (ente_id, pedido_id) WHERE estado = 'rascunho';
--;;
CREATE UNIQUE INDEX IF NOT EXISTS uq_parecer_juridico_substitui
  ON legislativo.parecer_juridico (ente_id, substitui_id) WHERE substitui_id IS NOT NULL;
--;;
CREATE INDEX IF NOT EXISTS idx_parecer_juridico_pedido ON legislativo.parecer_juridico (ente_id, pedido_id, criado_em);
--;;
CREATE INDEX IF NOT EXISTS idx_parecer_juridico_materia
  ON legislativo.parecer_juridico (ente_id, proposicao_id, estado) WHERE proposicao_id IS NOT NULL;
--;;
-- assinado e' imutavel: nem editar nem apagar (corrigir = parecer novo que substitui)
CREATE OR REPLACE FUNCTION legislativo.imut_parecer_juridico() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.estado = 'assinado' THEN
    RAISE EXCEPTION 'imutabilidade: parecer juridico assinado nao muda (% proibido); emita um parecer novo que o substitua', TG_OP
      USING ERRCODE = 'check_violation';
  END IF;
  IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
  RETURN NEW;
END;
$$;
--;;
CREATE TRIGGER trg_parecer_juridico_imutavel
  BEFORE UPDATE OR DELETE ON legislativo.parecer_juridico
  FOR EACH ROW EXECUTE FUNCTION legislativo.imut_parecer_juridico();
--;;
ALTER TABLE legislativo.parecer_juridico ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE legislativo.parecer_juridico FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON legislativo.parecer_juridico;
--;;
CREATE POLICY tenant_isolation ON legislativo.parecer_juridico
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid)
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
GRANT SELECT, INSERT, UPDATE ON legislativo.parecer_juridico TO oplenario_app;
