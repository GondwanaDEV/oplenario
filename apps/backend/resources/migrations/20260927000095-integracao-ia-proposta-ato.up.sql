-- Faixa B / B.6 da Track IA (docs/25 Eixo 4.2 B, ADR-0012) — a PROPOSTA DE ATO: o agente prepara, a pessoa confirma
-- na tela. Guarda a entrada EXATA (ja' validada) da ferramenta do catalogo, o que a pessoa vai ver (titulo e texto),
-- o ritual, as leituras de terceiro da execucao (Eixo 4.5) e o desfecho. So' a pessoa da proposta confirma.
CREATE TABLE IF NOT EXISTS integracao_ia.proposta_ato (
  ente_id         uuid NOT NULL,
  id              uuid NOT NULL DEFAULT gen_random_uuid(),
  execucao_id     uuid NOT NULL,
  identidade_id   uuid NOT NULL,                     -- a pessoa: a unica que confirma (agente institucional nao propoe)
  agente          text NOT NULL,
  ferramenta      text NOT NULL,
  entrada         jsonb NOT NULL,
  titulo          text NOT NULL,
  texto           text NOT NULL,
  ritual          text NOT NULL CHECK (ritual IN ('confirmar', 'assinatura')),
  contaminada_por jsonb NOT NULL DEFAULT '[]'::jsonb,
  estado          text NOT NULL DEFAULT 'aguardando'
                  CHECK (estado IN ('aguardando', 'executando', 'confirmada', 'recusada', 'expirada')),
  criada_em       timestamptz NOT NULL DEFAULT now(),
  expira_em       timestamptz NOT NULL,
  decidida_em     timestamptz,
  resultado       jsonb,
  erro            text,
  PRIMARY KEY (ente_id, id)
);
--;;
CREATE INDEX IF NOT EXISTS idx_proposta_ato_pessoa ON integracao_ia.proposta_ato (ente_id, identidade_id, estado, criada_em);
--;;
CREATE INDEX IF NOT EXISTS idx_proposta_ato_execucao ON integracao_ia.proposta_ato (ente_id, execucao_id);
--;;
ALTER TABLE integracao_ia.proposta_ato ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE integracao_ia.proposta_ato FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON integracao_ia.proposta_ato;
--;;
CREATE POLICY tenant_isolation ON integracao_ia.proposta_ato
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid)
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
GRANT SELECT, INSERT, UPDATE (estado, decidida_em, resultado, erro) ON integracao_ia.proposta_ato TO oplenario_app;
--;;
-- As leituras de conteudo de TERCEIRO de uma execucao (Eixo 4.5): a proposta criada depois as leva ao confirmador.
CREATE TABLE IF NOT EXISTS integracao_ia.leitura_de_terceiro (
  ente_id     uuid NOT NULL,
  id          uuid NOT NULL DEFAULT gen_random_uuid(),
  execucao_id uuid NOT NULL,
  ferramenta  text NOT NULL,
  origem      text NOT NULL,
  referencia  text NOT NULL,
  lida_em     timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (ente_id, id)
);
--;;
CREATE INDEX IF NOT EXISTS idx_leitura_de_terceiro_execucao ON integracao_ia.leitura_de_terceiro (ente_id, execucao_id, lida_em);
--;;
ALTER TABLE integracao_ia.leitura_de_terceiro ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE integracao_ia.leitura_de_terceiro FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON integracao_ia.leitura_de_terceiro;
--;;
CREATE POLICY tenant_isolation ON integracao_ia.leitura_de_terceiro
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid)
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
GRANT SELECT, INSERT ON integracao_ia.leitura_de_terceiro TO oplenario_app;
--;;
CREATE TRIGGER trg_leitura_de_terceiro_append_only
  BEFORE UPDATE OR DELETE ON integracao_ia.leitura_de_terceiro
  FOR EACH ROW EXECUTE FUNCTION shared.imut_append_only();
--;;
-- o audit das chamadas de agente ganha o desfecho 'proposta' (ato pedido por agente vira proposta, nao execucao)
ALTER TABLE integracao_ia.chamada_agente DROP CONSTRAINT IF EXISTS chamada_agente_desfecho_check;
--;;
ALTER TABLE integracao_ia.chamada_agente ADD CONSTRAINT chamada_agente_desfecho_check
  CHECK (desfecho IN ('ok', 'nao_encontrado', 'negado', 'invalido', 'erro', 'proposta'));
