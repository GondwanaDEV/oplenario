-- F3.0 (gate eixo H, ADR-0002 §4): o PADRAO de imutabilidade como helper KERNEL-reusavel (uma funcao
-- SQL parametrizavel, NAO copiada por tabela). Vive no schema `shared` (infra transversal, como
-- shared.outbox/sequencial). As tabelas de dominio legal anexam o trigger; a 1a usuaria e' a F3.1
-- (legislativo.proposicoes). Tres niveis da taxonomia (§22.4.3 disc.4):
--   (a) append-only puro      -> shared.imut_append_only()          : nenhum UPDATE/DELETE jamais.
--   (b) trava por estado term. -> shared.imut_trava_estado_terminal(): UPDATE bloqueado em row terminal,
--       EXCETO sob a flag de correcao auditada (GUC app.correcao_auditada). Estados terminais = TG_ARGV.
--   (c) mutacao parcial        -> caso a caso na propria migracao (F3.6 apensacao), nao helper generico.

-- (a) append-only puro: qualquer UPDATE/DELETE lanca. (versoes de texto, votos, historico, audit.)
CREATE OR REPLACE FUNCTION shared.imut_append_only() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'imutabilidade (a) append-only: % proibido em %', TG_OP, TG_TABLE_NAME
    USING ERRCODE = 'check_violation';
END;
$$;
--;;
-- (b) trava por estado terminal: so bloqueia quando a row JA ESTAVA terminal (OLD.estado), entao a
-- transicao p/ terminal (nao-terminal -> terminal) passa; mexer numa row JA terminal nao. A excecao e'
-- o fluxo de correcao auditada: a tx seta app.correcao_auditada (= id/justificativa da correcao) no GUC.
-- Estados terminais sao argumentos do trigger (TG_ARGV) -> mesma funcao serve proposicoes, votacoes, etc.
CREATE OR REPLACE FUNCTION shared.imut_trava_estado_terminal() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  era_terminal boolean;
  correcao     text    := NULLIF(current_setting('app.correcao_auditada', true), '');
BEGIN
  -- fail-LOUD: trigger sem estados terminais (TG_ARGV vazio) desativaria a protecao em silencio
  -- (OLD.estado = ANY ('{}') -> NULL -> nunca bloqueia). Para registro legal, isso e' inaceitavel.
  IF TG_NARGS = 0 THEN
    RAISE EXCEPTION 'imut_trava_estado_terminal: nenhum estado terminal passado ao trigger (TG_ARGV vazio)'
      USING ERRCODE = 'invalid_parameter_value';
  END IF;
  era_terminal := OLD.estado = ANY (TG_ARGV);
  IF era_terminal AND correcao IS NULL THEN
    RAISE EXCEPTION 'imutabilidade (b): % em estado terminal ''%'' so muda sob correcao auditada (app.correcao_auditada)',
      TG_TABLE_NAME, OLD.estado USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END;
$$;
