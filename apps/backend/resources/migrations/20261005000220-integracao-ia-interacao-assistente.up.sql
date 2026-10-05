-- ADR-0024 — o HISTORICO AUDITAVEL da Clara, a assistente da Casa (B.3). Uma linha por pergunta: o que a pessoa
-- perguntou, o desfecho (resposta ou indisponivel), a resposta como a tela recebeu, os passos, as propostas e o
-- modelo. `conteudo_sha256` e' o SHA-256 do registro canonico; a trilha da Casa (ADR-0017, sem conteudo) ancora esse
-- hash na entrada do POST /agente/perguntas. Append-only, por tenant (RLS): entra sozinha no inventario da Casa
-- (ADR-0018), exportada no encerramento e apagada no apagamento. Prazo de guarda = [GAP] juridico.
CREATE TABLE IF NOT EXISTS integracao_ia.interacao_assistente (
  ente_id         uuid NOT NULL,
  id              uuid NOT NULL,
  conversa_id     uuid NOT NULL,
  execucao_id     uuid NOT NULL,                     -- a credencial delegada: liga as chamadas de ferramenta
  identidade_id   uuid NOT NULL,                     -- quem perguntou
  agente          text NOT NULL,
  publico         text NOT NULL,
  pergunta        text NOT NULL CHECK (length(pergunta) BETWEEN 1 AND 2000),
  desfecho        text NOT NULL CHECK (desfecho IN ('resposta', 'indisponivel')),
  resposta        jsonb,                             -- texto, citacoes, paragrafos-sem-fonte, incerteza, contaminado
  passos          jsonb NOT NULL DEFAULT '[]'::jsonb,
  propostas       jsonb NOT NULL DEFAULT '[]'::jsonb,
  modelo          text,
  execucao_ia     text,                              -- o id da execucao no satelite (Camada de Confianca, reportar erro)
  conteudo_sha256 text NOT NULL CHECK (conteudo_sha256 ~ '^[0-9a-f]{64}$'),
  ocorrido_em     timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (ente_id, id),
  CONSTRAINT interacao_assistente_resposta_do_desfecho
    CHECK ((desfecho = 'resposta') = (resposta IS NOT NULL))
);
--;;
CREATE INDEX IF NOT EXISTS idx_interacao_assistente_pessoa
  ON integracao_ia.interacao_assistente (ente_id, identidade_id, ocorrido_em DESC);
--;;
CREATE INDEX IF NOT EXISTS idx_interacao_assistente_conversa
  ON integracao_ia.interacao_assistente (ente_id, conversa_id, ocorrido_em);
--;;
ALTER TABLE integracao_ia.interacao_assistente ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE integracao_ia.interacao_assistente FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON integracao_ia.interacao_assistente;
--;;
CREATE POLICY tenant_isolation ON integracao_ia.interacao_assistente
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid)
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
GRANT SELECT, INSERT ON integracao_ia.interacao_assistente TO oplenario_app;
--;;
CREATE TRIGGER trg_interacao_assistente_append_only
  BEFORE UPDATE OR DELETE ON integracao_ia.interacao_assistente
  FOR EACH ROW EXECUTE FUNCTION shared.imut_append_only();
--;;
COMMENT ON TABLE integracao_ia.interacao_assistente IS
  'Historico da Clara, a assistente da Casa (ADR-0024): uma linha por pergunta, append-only.';
--;;
-- ADR-0024 item 2: toda chamada de agente (inclusive de LEITURA) entra no audit com o SHA-256 do JSON canonico da
-- saida — prova o que a IA viu sem guardar o conteudo. NULL nas linhas anteriores e nas chamadas sem saida.
ALTER TABLE integracao_ia.chamada_agente ADD COLUMN IF NOT EXISTS resultado_sha256 text
  CHECK (resultado_sha256 IS NULL OR resultado_sha256 ~ '^[0-9a-f]{64}$');
