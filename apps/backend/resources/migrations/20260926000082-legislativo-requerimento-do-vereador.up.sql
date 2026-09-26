-- Fatia 2a do pedido do stakeholder: o VEREADOR redige o requerimento pelo proprio login, com o texto
-- formatado a partir de um modelo da Casa, e o assina. Duas pecas:
--
-- (1) Tipo de modelo `requerimento_proposicao` em `documento_modelo`. O requerimento e' PROPOSICAO (numerado,
--     tramita, vai a Plenario), nao documento administrativo do Expediente — mas o TEMPLATE e' o mesmo
--     conceito (corpo com placeholders {{campo}}, config da Casa editada na aba "Modelos"). Reusar a tabela em
--     vez de criar outra mantem um so' lugar onde a Casa cuida dos seus modelos. O Expediente nao GERA
--     documento a partir deste tipo (o controller recusa): so' a borda /meu do vereador o usa. A tabela
--     `documento` NAO ganha o tipo — nenhum documento administrativo nasce dele.
--
-- (2) Assinatura sobre a VERSAO DE TEXTO da proposicao — mesmo padrao de parecer_texto_versao (mig 0057) e
--     artefato_publicacao (mig 0046): assinatura DESTACADA (algoritmo + b64) + quem + quando. NULLABLE: as
--     versoes protocoladas pela Mesa (e as anteriores a esta migration) nao sao assinadas pelo autor. Gravada
--     no INSERT da versao, uma unica vez, junto do conteudo que ela assina. Enquanto a ICP real nao entra, o
--     algoritmo e' 'STUB-ICP-v0' (legislativo/components/assinador_icp.clj) — o selo diz que nao e' ICP.
ALTER TABLE legislativo.documento_modelo DROP CONSTRAINT IF EXISTS documento_modelo_tipo_documento_check;
--;;
ALTER TABLE legislativo.documento_modelo ADD CONSTRAINT documento_modelo_tipo_documento_check CHECK (
  tipo_documento IN ('oficio', 'certidao', 'requerimento_administrativo', 'convite', 'mala_direta', 'outro',
                     'requerimento_proposicao'));
--;;
ALTER TABLE legislativo.proposicao_texto_versao
  ADD COLUMN IF NOT EXISTS assinatura_algoritmo text,
  ADD COLUMN IF NOT EXISTS assinatura_b64 text,
  ADD COLUMN IF NOT EXISTS assinado_por uuid,
  ADD COLUMN IF NOT EXISTS assinado_em timestamptz;
--;;
-- os quatro andam juntos: assinatura sem autor/instante (ou o inverso) e' dado incoerente
ALTER TABLE legislativo.proposicao_texto_versao DROP CONSTRAINT IF EXISTS texto_versao_assinatura_completa;
--;;
ALTER TABLE legislativo.proposicao_texto_versao ADD CONSTRAINT texto_versao_assinatura_completa CHECK (
  (assinatura_algoritmo IS NULL AND assinatura_b64 IS NULL AND assinado_por IS NULL AND assinado_em IS NULL)
  OR (assinatura_algoritmo IS NOT NULL AND assinatura_b64 IS NOT NULL AND assinado_por IS NOT NULL
      AND assinado_em IS NOT NULL));
--;;
-- a assinatura, uma vez gravada, e' imutavel como o conteudo que ela assina (a trigger da mig 0015 so'
-- protege o conteudo; esta protege o selo — fecha o [GAP] que a mig 0057 registrou para o parecer).
CREATE OR REPLACE FUNCTION legislativo.texto_versao_assinatura_imutavel() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.assinatura_algoritmo IS NOT NULL AND (
       NEW.assinatura_algoritmo IS DISTINCT FROM OLD.assinatura_algoritmo
       OR NEW.assinatura_b64    IS DISTINCT FROM OLD.assinatura_b64
       OR NEW.assinado_por      IS DISTINCT FROM OLD.assinado_por
       OR NEW.assinado_em       IS DISTINCT FROM OLD.assinado_em) THEN
    RAISE EXCEPTION 'assinatura da versao de texto e imutavel (versao=%/%)',
      OLD.proposicao_id, OLD.numero_versao USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END;
$$;
--;;
DROP TRIGGER IF EXISTS trg_texto_versao_assinatura_imutavel ON legislativo.proposicao_texto_versao;
--;;
CREATE TRIGGER trg_texto_versao_assinatura_imutavel
  BEFORE UPDATE ON legislativo.proposicao_texto_versao
  FOR EACH ROW EXECUTE FUNCTION legislativo.texto_versao_assinatura_imutavel();
