-- Fatia 2b do pedido do stakeholder: "toda movimentacao do documento assinada por quem recebe" (o recibo de
-- carga do processo em papel). Duas pecas:
--
-- (1) `template_estado.exige_recebimento` + `recebedor` — o RITO DA CASA diz em quais estados a materia chega
--     como CARGA a ser aceita (Inv. 4: dado, nao `if estado = ...`). `recebedor` e' expressao da MESMA DSL de
--     `template_transicao.autorizacao` (mig 0079; disciplina 5 — nenhum motor novo): quem pode receber naquele
--     estado. NULL = so' o gate da rota. Default `false`: todo rito ja' cadastrado segue identico.
--
-- (2) `recebimento_tramitacao` — o RECIBO: uma linha por movimentacao recebida, amarrada a linha do historico
--     que a produziu (`transicao_id`), com quem recebeu, quando, e a assinatura destacada sobre o conteudo
--     canonico do recibo (mesmas 2 colunas de assinatura do parecer/requerimento; STUB-ICP-v0 ate' a assinatura
--     real, §22.5). APPEND-ONLY PURO (Inv. 10), como `ciencia_vereador` (mig 0055): o recibo nunca muda nem some.
--     UNIQUE por movimentacao = receber duas vezes e' conflito, nunca duplica. Enquanto a ultima movimentacao
--     para um estado que exige recebimento nao tem recibo, a engine (`db/tramitacao/transicionar!`) recusa
--     tirar a materia dali (`:recebimento-pendente`).
ALTER TABLE legislativo.template_estado
  ADD COLUMN IF NOT EXISTS exige_recebimento boolean NOT NULL DEFAULT false;
--;;
ALTER TABLE legislativo.template_estado ADD COLUMN IF NOT EXISTS recebedor text;
--;;
COMMENT ON COLUMN legislativo.template_estado.recebedor IS
  'Expressao booleana da DSL do motor (vocabulario ator/recurso, igual a template_transicao.autorizacao): quem pode assinar o recebimento de materia que chega a este estado. NULL = so o gate da rota. So tem efeito com exige_recebimento.';
--;;
CREATE TABLE IF NOT EXISTS legislativo.recebimento_tramitacao (
  ente_id       uuid NOT NULL,
  id            uuid NOT NULL DEFAULT gen_random_uuid(),
  transicao_id  uuid NOT NULL,              -- a linha de proposicao_transicao_historico que esta sendo recebida
  proposicao_id uuid NOT NULL,
  estado        text NOT NULL,              -- o estado em que a materia foi recebida (para_estado da transicao)
  recebido_por  uuid NOT NULL,              -- identidade (forward-ref, sem FK cross-schema, §22.10)
  recebido_em   timestamptz NOT NULL DEFAULT now(),
  assinatura_algoritmo text NOT NULL,
  assinatura_b64       text NOT NULL,
  PRIMARY KEY (ente_id, id),
  UNIQUE (ente_id, transicao_id),
  FOREIGN KEY (ente_id, transicao_id) REFERENCES legislativo.proposicao_transicao_historico (ente_id, id),
  FOREIGN KEY (ente_id, proposicao_id) REFERENCES legislativo.proposicoes (ente_id, id)
);
--;;
CREATE INDEX IF NOT EXISTS idx_recebimento_tramitacao_proposicao
  ON legislativo.recebimento_tramitacao (ente_id, proposicao_id);
--;;
ALTER TABLE legislativo.recebimento_tramitacao ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE legislativo.recebimento_tramitacao FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON legislativo.recebimento_tramitacao;
--;;
CREATE POLICY tenant_isolation ON legislativo.recebimento_tramitacao
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid)
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
GRANT SELECT, INSERT ON legislativo.recebimento_tramitacao TO oplenario_app;
--;;
CREATE TRIGGER trg_recebimento_tramitacao_append_only
  BEFORE UPDATE OR DELETE ON legislativo.recebimento_tramitacao
  FOR EACH ROW EXECUTE FUNCTION shared.imut_append_only();
