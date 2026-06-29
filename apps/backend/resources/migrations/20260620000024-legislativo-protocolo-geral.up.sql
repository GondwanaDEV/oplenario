-- F3.9a: modulo legislativo, EXPEDIENTE — PROTOCOLO GERAL / unico (§16.3; doc-mestre L355, feature 3.23).
-- O "livro do Protocolo Geral": numerador INSTITUCIONAL unico que protocola QUALQUER coisa que entra/sai da
-- Casa — proposicao E documento administrativo (oficio recebido, requerimento de cidadao, processo adm.).
-- Distinto do numero LEGISLATIVO da proposicao (PL 042/2026, gate eixo H): uma proposicao tem os DOIS (seu
-- numero de especie + um numero de protocolo geral). Numeracao gapless por ente/ANO (reinicio anual via
-- escopo 'protocolo_geral:ano' do kernel/sequencial). 3.18 reserva/cancelamento de numero = [carry] (exige
-- numerador reservavel, nao o gapless-on-commit; sem requisito validado ainda).
--
-- Objeto POLIMORFICO (disc.2): (objeto_tipo, objeto_id) aponta o que foi protocolado — SEM FK declarativa
-- (o alvo varia: proposicoes e' particionada, documento vem na F3.9b, e ha protocolos de papel externo sem
-- objeto interno). Integridade em camadas (CHECK do tipo + indice + servico), como votacoes/pareceres.
--
-- APPEND-ONLY PURO: o protocolo e' registro permanente (o livro nao se rasura). Cancelamento de um ato
-- protocolado e' um fato POSTERIOR no proprio dominio do objeto, nao um DELETE/UPDATE da linha de protocolo.
--
-- DECISAO DE MODULO: o Expediente vive no schema `legislativo` (nao em modulo proprio) — o produto agrupa
-- §16.3 (features 3.1–3.23) no mesmo modulo e o Protocolo Geral protocola proposicoes daqui. Candidato a
-- extracao se o Expediente crescer (disc.6). NAO-particionada (cardinalidade moderada; sem FK particionada).

CREATE TABLE IF NOT EXISTS legislativo.protocolo_geral (
  ente_id      uuid NOT NULL,
  id           uuid NOT NULL DEFAULT gen_random_uuid(),
  numero       integer NOT NULL,                          -- numero institucional gapless por ente/ano
  ano          integer NOT NULL,
  -- o que foi protocolado (disc.2, sem FK declarativa)
  objeto_tipo  text NOT NULL CHECK (objeto_tipo IN
    ('proposicao', 'documento', 'oficio_recebido', 'requerimento_cidadao', 'processo_administrativo', 'outro')),
  objeto_id    uuid,                                       -- NULL quando o protocolo precede/descreve papel externo
  sentido      text NOT NULL CHECK (sentido IN ('recebido', 'expedido', 'interno')),
  assunto      text NOT NULL,                              -- descricao do que se protocolou (sempre presente)
  interessado_texto text,                                  -- parte interessada (cidadao/orgao); livre
  interessado_id    uuid,                                  -- forward-ref ao cadastro (sem FK)
  protocolado_em   timestamptz NOT NULL DEFAULT now(),
  protocolado_por  uuid,
  -- transversais (§22.4.3 disc.1) — sem lock_version/estado: registro permanente (append-only puro)
  origem    text NOT NULL DEFAULT 'nativa',
  origem_ref text,
  origem_importado_em timestamptz,
  lote_id   uuid,
  efetivado_em timestamptz,
  created_by uuid,
  criado_em  timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (ente_id, id),
  -- o numero de protocolo e' unico por ente/ano (reinicio anual)
  UNIQUE (ente_id, ano, numero),
  -- assunto descritivo nao-vazio (NOT NULL nao barra '')
  CONSTRAINT protocolo_assunto_nao_vazio CHECK (assunto <> ''),
  -- anti linha-fantasma: como a tabela e' APPEND-ONLY, uma linha com (lote_id NULL AND efetivado_em NULL)
  -- passaria o WITH CHECK mas ficaria invisivel sob o USING e IMPOSSIVEL de remover (review F3.9a DB-MAJOR).
  -- Estados legitimos: (lote,NULL) em staging | (lote,ts) staged+efetivado | (NULL,ts) efetivado direto.
  CONSTRAINT protocolo_staging_valido CHECK (lote_id IS NOT NULL OR efetivado_em IS NOT NULL)
);
--;;
-- busca "qual o protocolo deste objeto?" (disc.2: indice por objeto_tipo). PARCIAL: protocolos de papel
-- externo (objeto_id NULL) nao entram nessa busca e inflariam o indice (review F3.9a DB-MENOR).
CREATE INDEX IF NOT EXISTS idx_protocolo_objeto ON legislativo.protocolo_geral (ente_id, objeto_tipo, objeto_id)
  WHERE objeto_id IS NOT NULL;
--;;
CREATE INDEX IF NOT EXISTS idx_protocolo_staging ON legislativo.protocolo_geral (ente_id, lote_id) WHERE efetivado_em IS NULL;
--;;
ALTER TABLE legislativo.protocolo_geral ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE legislativo.protocolo_geral FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON legislativo.protocolo_geral;
--;;
CREATE POLICY tenant_isolation ON legislativo.protocolo_geral
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid
         AND (efetivado_em IS NOT NULL OR lote_id = NULLIF(current_setting('app.ver_lote', true), '')::uuid))
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
-- append-only puro (o livro do protocolo nao se rasura): nem UPDATE nem DELETE.
GRANT SELECT, INSERT ON legislativo.protocolo_geral TO oplenario_app;
--;;
CREATE TRIGGER trg_protocolo_append_only
  BEFORE UPDATE OR DELETE ON legislativo.protocolo_geral
  FOR EACH ROW EXECUTE FUNCTION shared.imut_append_only();
