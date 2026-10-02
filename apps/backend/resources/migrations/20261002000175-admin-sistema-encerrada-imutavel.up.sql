-- ADR-0018 (fatia 2), Eixo 5: `encerrado -> *` NUNCA. O backend ja' so' transiciona por UPDATE condicional ao estado de
-- origem; o banco segura o mesmo de novo, para que nenhum caminho (script, console, bug) reabra uma Casa apagada. A Casa
-- que volta e' uma Casa nova, provisionada de novo e importada da exportacao que recebeu.
--
-- O que segue mudando numa Casa encerrada: so' o destino do acervo (`destino_acervo_url`) — a Casa pode informar
-- depois para onde foram os documentos, e o portal passa a apontar para la'. Estado, data e resumo do apagamento ficam.
CREATE OR REPLACE FUNCTION admin_sistema.imut_ente_encerrado() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.estado = 'encerrado' THEN
    IF TG_OP = 'DELETE' THEN
      RAISE EXCEPTION 'Casa encerrada: a linha do registro e'' a prova do encerramento e nao se apaga'
        USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.estado IS DISTINCT FROM OLD.estado
       OR NEW.encerrada_em IS DISTINCT FROM OLD.encerrada_em
       OR NEW.apagamento IS DISTINCT FROM OLD.apagamento
       OR NEW.motivo_restricao IS DISTINCT FROM OLD.motivo_restricao THEN
      RAISE EXCEPTION 'Casa encerrada nao muda de estado (ADR-0018 Eixo 5): a Casa que volta e'' uma Casa nova'
        USING ERRCODE = 'check_violation';
    END IF;
  END IF;
  IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
  RETURN NEW;
END;
$$;
--;;
DROP TRIGGER IF EXISTS trg_ente_encerrado_imutavel ON admin_sistema.ente;
--;;
CREATE TRIGGER trg_ente_encerrado_imutavel
  BEFORE UPDATE OR DELETE ON admin_sistema.ente
  FOR EACH ROW EXECUTE FUNCTION admin_sistema.imut_ente_encerrado();
--;;
-- A data do encerramento existe exatamente quando a Casa esta' encerrada.
ALTER TABLE admin_sistema.ente DROP CONSTRAINT IF EXISTS ente_encerrada_em_coerente;
--;;
ALTER TABLE admin_sistema.ente ADD CONSTRAINT ente_encerrada_em_coerente
  CHECK ((estado = 'encerrado') = (encerrada_em IS NOT NULL));
--;;
-- A CONFIRMACAO de recebimento nao se desfaz (Eixo 4.2): uma vez confirmada, quem confirmou, quando, o texto do oficio e o
-- hash do arquivo que a Casa disse ter recebido ficam como estao. E' deles que a janela de guarda conta.
CREATE OR REPLACE FUNCTION admin_sistema.imut_exportacao_confirmada() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.confirmada_em IS NOT NULL AND (
       NEW.confirmada_em IS DISTINCT FROM OLD.confirmada_em
       OR NEW.confirmada_por IS DISTINCT FROM OLD.confirmada_por
       OR NEW.confirmada_por_tipo IS DISTINCT FROM OLD.confirmada_por_tipo
       OR NEW.confirmacao_texto IS DISTINCT FROM OLD.confirmacao_texto
       OR NEW.sha256 IS DISTINCT FROM OLD.sha256
       OR NEW.estado IS DISTINCT FROM OLD.estado) THEN
    RAISE EXCEPTION 'confirmacao de recebimento nao se desfaz (ADR-0018 Eixo 4.2)'
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END;
$$;
--;;
DROP TRIGGER IF EXISTS trg_exportacao_confirmada_imutavel ON admin_sistema.exportacao_casa;
--;;
CREATE TRIGGER trg_exportacao_confirmada_imutavel
  BEFORE UPDATE ON admin_sistema.exportacao_casa
  FOR EACH ROW EXECUTE FUNCTION admin_sistema.imut_exportacao_confirmada();
