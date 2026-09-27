-- Fatia 2c do pedido do stakeholder: o requerimento COLETIVO (feature 3.17 — coautoria/subscricao, G11 da
-- revisao de completude, v1.38). Desenho: produto/design-system/o-plenario/telas/autoria-apoiamento.html —
-- "coautoria e' ato voluntario: cada coautor confirma a subscricao com a propria assinatura. Ate' confirmar,
-- aparece como pendente e NAO CONSTA no protocolo". Por isso a subscricao acontece ANTES do numero:
--
-- (1) `requerimento_proposta` — o requerimento redigido pelo autor e ainda NAO protocolado, esperando as
--     subscricoes. O TEXTO fica CONGELADO na proposta: e' exatamente esses bytes que cada coautor assina e que
--     o autor protocola (a mesma assinatura cobre o mesmo conteudo). Ao protocolar, `proposicao_id` aponta a
--     proposicao numerada e a proposta fecha (estado 'protocolada'). Sem proposta nao ha' subscricao — o
--     requerimento individual (fatia 2a) continua indo direto ao protocolo.
--
-- (2) `subscricao_requerimento` — um convite por coautor. 'pendente' -> 'confirmada' (com assinatura destacada
--     sobre o texto da proposta, mesmas colunas do requerimento/parecer; STUB-ICP-v0 ate' a assinatura real,
--     §22.5) | 'recusada' | 'nao_consta' (ainda pendente quando o autor protocolou). Estado FINAL e' imutavel
--     (trigger): quem subscreveu nao 'dessubscreve' em silencio, e o recibo assinado nao muda.
--
-- O coautor e' o VEREADOR (cadastro, `vereador_id` — forward-ref sem FK cross-schema, §22.10), nao a
-- identidade: o convite e' para o parlamentar, que confirma com o login dele (resolvido pelo host). O nome vai
-- junto (snapshot do momento do convite), como `autor_texto` da proposicao.
CREATE TABLE IF NOT EXISTS legislativo.requerimento_proposta (
  ente_id             uuid NOT NULL,
  id                  uuid NOT NULL,
  autor_vereador_id   uuid NOT NULL,
  autor_identidade_id uuid NOT NULL,
  autor_nome          text NOT NULL,
  modelo_id           uuid NOT NULL,
  tipo_requerimento   text NOT NULL,
  ementa              text NOT NULL,
  texto               text NOT NULL,
  estado              text NOT NULL DEFAULT 'aguardando_subscricoes'
                      CHECK (estado IN ('aguardando_subscricoes', 'protocolada')),
  proposicao_id       uuid,
  criada_em           timestamptz NOT NULL DEFAULT now(),
  protocolada_em      timestamptz,
  PRIMARY KEY (ente_id, id),
  FOREIGN KEY (ente_id, modelo_id) REFERENCES legislativo.documento_modelo (ente_id, id),
  FOREIGN KEY (ente_id, proposicao_id) REFERENCES legislativo.proposicoes (ente_id, id),
  CONSTRAINT proposta_protocolada_coerente CHECK (
    (estado = 'protocolada') = (proposicao_id IS NOT NULL AND protocolada_em IS NOT NULL))
);
--;;
CREATE INDEX IF NOT EXISTS idx_requerimento_proposta_autor
  ON legislativo.requerimento_proposta (ente_id, autor_vereador_id, estado);
--;;
CREATE UNIQUE INDEX IF NOT EXISTS uq_requerimento_proposta_proposicao
  ON legislativo.requerimento_proposta (ente_id, proposicao_id) WHERE proposicao_id IS NOT NULL;
--;;
CREATE TABLE IF NOT EXISTS legislativo.subscricao_requerimento (
  ente_id              uuid NOT NULL,
  id                   uuid NOT NULL,
  proposta_id          uuid NOT NULL,
  vereador_id          uuid NOT NULL,
  vereador_nome        text NOT NULL,
  estado               text NOT NULL DEFAULT 'pendente'
                       CHECK (estado IN ('pendente', 'confirmada', 'recusada', 'nao_consta')),
  convidada_em         timestamptz NOT NULL DEFAULT now(),
  respondida_em        timestamptz,
  assinado_por         uuid,              -- identidade de quem assinou (o login do coautor)
  assinatura_algoritmo text,
  assinatura_b64       text,
  PRIMARY KEY (ente_id, id),
  UNIQUE (ente_id, proposta_id, vereador_id),
  FOREIGN KEY (ente_id, proposta_id) REFERENCES legislativo.requerimento_proposta (ente_id, id),
  -- assinatura existe SE E SO' SE a subscricao foi confirmada; resposta tem instante se nao esta' pendente
  CONSTRAINT subscricao_assinatura_coerente CHECK (
    (estado = 'confirmada') = (assinado_por IS NOT NULL AND assinatura_algoritmo IS NOT NULL
                               AND assinatura_b64 IS NOT NULL)),
  CONSTRAINT subscricao_resposta_coerente CHECK ((estado = 'pendente') = (respondida_em IS NULL))
);
--;;
CREATE INDEX IF NOT EXISTS idx_subscricao_requerimento_vereador
  ON legislativo.subscricao_requerimento (ente_id, vereador_id, estado);
--;;
-- estado final e' imutavel: so' 'pendente' muda (para um dos tres finais), e nada se apaga
CREATE OR REPLACE FUNCTION legislativo.subscricao_final_imutavel() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'subscricao nao se apaga (id=%)', OLD.id USING ERRCODE = 'check_violation';
  END IF;
  IF OLD.estado <> 'pendente' THEN
    RAISE EXCEPTION 'subscricao ja respondida e imutavel (id=%, estado=%)', OLD.id, OLD.estado
      USING ERRCODE = 'check_violation';
  END IF;
  IF NEW.proposta_id IS DISTINCT FROM OLD.proposta_id OR NEW.vereador_id IS DISTINCT FROM OLD.vereador_id
     OR NEW.vereador_nome IS DISTINCT FROM OLD.vereador_nome OR NEW.convidada_em IS DISTINCT FROM OLD.convidada_em THEN
    RAISE EXCEPTION 'o convite de subscricao nao muda de destinatario (id=%)', OLD.id USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END;
$$;
--;;
DROP TRIGGER IF EXISTS trg_subscricao_final_imutavel ON legislativo.subscricao_requerimento;
--;;
CREATE TRIGGER trg_subscricao_final_imutavel
  BEFORE UPDATE OR DELETE ON legislativo.subscricao_requerimento
  FOR EACH ROW EXECUTE FUNCTION legislativo.subscricao_final_imutavel();
--;;
-- a proposta so' anda de 'aguardando' para 'protocolada', uma vez, e o texto que foi assinado nao muda
CREATE OR REPLACE FUNCTION legislativo.requerimento_proposta_imutavel() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'proposta de requerimento nao se apaga (id=%)', OLD.id USING ERRCODE = 'check_violation';
  END IF;
  IF OLD.estado <> 'aguardando_subscricoes'
     OR NEW.texto IS DISTINCT FROM OLD.texto OR NEW.ementa IS DISTINCT FROM OLD.ementa
     OR NEW.autor_vereador_id IS DISTINCT FROM OLD.autor_vereador_id
     OR NEW.autor_identidade_id IS DISTINCT FROM OLD.autor_identidade_id
     OR NEW.modelo_id IS DISTINCT FROM OLD.modelo_id
     OR NEW.tipo_requerimento IS DISTINCT FROM OLD.tipo_requerimento THEN
    RAISE EXCEPTION 'proposta de requerimento: so muda de aguardando para protocolada (id=%)', OLD.id
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END;
$$;
--;;
DROP TRIGGER IF EXISTS trg_requerimento_proposta_imutavel ON legislativo.requerimento_proposta;
--;;
CREATE TRIGGER trg_requerimento_proposta_imutavel
  BEFORE UPDATE OR DELETE ON legislativo.requerimento_proposta
  FOR EACH ROW EXECUTE FUNCTION legislativo.requerimento_proposta_imutavel();
--;;
ALTER TABLE legislativo.requerimento_proposta ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE legislativo.requerimento_proposta FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON legislativo.requerimento_proposta;
--;;
CREATE POLICY tenant_isolation ON legislativo.requerimento_proposta
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid)
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
ALTER TABLE legislativo.subscricao_requerimento ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE legislativo.subscricao_requerimento FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON legislativo.subscricao_requerimento;
--;;
CREATE POLICY tenant_isolation ON legislativo.subscricao_requerimento
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid)
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
GRANT SELECT, INSERT, UPDATE ON legislativo.requerimento_proposta TO oplenario_app;
--;;
GRANT SELECT, INSERT, UPDATE ON legislativo.subscricao_requerimento TO oplenario_app;
