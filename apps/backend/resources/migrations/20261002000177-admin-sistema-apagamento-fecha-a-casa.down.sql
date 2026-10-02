CREATE OR REPLACE FUNCTION admin_sistema.apagar_dados_da_casa(p_ente uuid, p_pedido uuid)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
DECLARE
  v_super     boolean;
  v_srr       text;
  v_lock      text;
  v_r         record;
  v_n         bigint;
  v_res       jsonb := '{}'::jsonb;
  v_forcadas  text[] := '{}';
  v_triggers  text[] := '{}';
  v_alvos     text;
  v_pessoas   uuid[] := '{}';
  v_ids       uuid[];
  v_orfas     uuid[];
  v_existe    text;
  i           integer;
BEGIN
  -- serializa dois apagamentos da mesma Casa e trava o registro enquanto se confere
  PERFORM 1 FROM admin_sistema.ente WHERE ente_id = p_ente FOR UPDATE;
  PERFORM admin_sistema.conferir_apagamento(p_ente, p_pedido);

  SELECT r.rolsuper INTO v_super FROM pg_roles r WHERE r.rolname = current_user;
  v_srr  := current_setting('session_replication_role');
  v_lock := current_setting('lock_timeout');

  IF v_super THEN
    PERFORM set_config('session_replication_role', 'replica', true);
  ELSE
    PERFORM set_config('lock_timeout', '15s', true);
    SELECT string_agg(format('%I.%I', c.relnamespace::regnamespace::text, c.relname), ', ')
      INTO v_alvos
      FROM admin_sistema.inventario_da_casa() inv
      JOIN pg_class c ON c.oid = format('%I.%I', inv.esquema, inv.tabela)::regclass
     WHERE c.relforcerowsecurity
        OR EXISTS (SELECT 1 FROM pg_trigger tg
                    WHERE tg.tgrelid = c.oid AND NOT tg.tgisinternal AND tg.tgparentid = 0 AND tg.tgenabled <> 'D');
    IF v_alvos IS NOT NULL THEN
      EXECUTE 'LOCK TABLE ' || v_alvos || ' IN ACCESS EXCLUSIVE MODE';
    END IF;
    FOR v_r IN
      SELECT inv.esquema, inv.tabela, c.oid, c.relforcerowsecurity AS forcada
        FROM admin_sistema.inventario_da_casa() inv
        JOIN pg_class c ON c.oid = format('%I.%I', inv.esquema, inv.tabela)::regclass
    LOOP
      IF v_r.forcada THEN
        EXECUTE format('ALTER TABLE %I.%I NO FORCE ROW LEVEL SECURITY', v_r.esquema, v_r.tabela);
        v_forcadas := v_forcadas || format('%I.%I', v_r.esquema, v_r.tabela);
      END IF;
      FOR v_n, v_existe IN
        SELECT 0, tg.tgname::text FROM pg_trigger tg
         WHERE tg.tgrelid = v_r.oid AND NOT tg.tgisinternal AND tg.tgparentid = 0 AND tg.tgenabled = 'O'
      LOOP
        EXECUTE format('ALTER TABLE %I.%I DISABLE TRIGGER %I', v_r.esquema, v_r.tabela, v_existe);
        v_triggers := v_triggers || format('ALTER TABLE %I.%I ENABLE TRIGGER %I', v_r.esquema, v_r.tabela, v_existe);
      END LOOP;
    END LOOP;
  END IF;

  -- as pessoas que esta Casa referencia (por FK para identidade.identidade), ANTES de apagar: depois do apagamento,
  -- a identidade que nao tem mais nenhuma referencia em nenhuma Casa sai tambem (LGPD: sem Casa, sem finalidade)
  IF to_regclass('identidade.identidade') IS NOT NULL THEN
    FOR v_r IN
      SELECT inv.esquema, inv.tabela, a.attname::text AS coluna
        FROM admin_sistema.inventario_da_casa() inv
        JOIN pg_constraint con ON con.conrelid = format('%I.%I', inv.esquema, inv.tabela)::regclass
                              AND con.contype = 'f' AND con.conparentid = 0
                              AND con.confrelid = 'identidade.identidade'::regclass
        JOIN pg_attribute a ON a.attrelid = con.conrelid AND a.attnum = con.conkey[1]
       WHERE cardinality(con.conkey) = 1
    LOOP
      EXECUTE format('SELECT array_agg(DISTINCT t.%I) FROM %I.%I AS t WHERE t.%I IS NOT NULL AND (%s)',
                     v_r.coluna, v_r.esquema, v_r.tabela, v_r.coluna,
                     (SELECT inv.predicado FROM admin_sistema.inventario_da_casa() inv
                       WHERE inv.esquema = v_r.esquema AND inv.tabela = v_r.tabela))
        INTO v_ids USING p_ente;
      v_pessoas := v_pessoas || coalesce(v_ids, '{}');
    END LOOP;
  END IF;

  FOR v_r IN SELECT * FROM admin_sistema.inventario_da_casa() ORDER BY nivel, esquema, tabela LOOP
    EXECUTE format('DELETE FROM %I.%I AS t WHERE %s', v_r.esquema, v_r.tabela, v_r.predicado) USING p_ente;
    GET DIAGNOSTICS v_n = ROW_COUNT;
    v_res := v_res || jsonb_build_object(v_r.esquema || '.' || v_r.tabela, v_n);
  END LOOP;

  IF cardinality(v_pessoas) > 0 THEN
    -- orfa = nenhuma tabela (de nenhuma Casa) ainda aponta para ela pela FK, fora as filhas supratenant dela mesma
    -- (o vinculo com o gov.br, `identidade_externa`), que saem junto
    v_orfas := v_pessoas;
    FOR v_r IN
      SELECT con.conrelid::regclass::text AS tabela, a.attname::text AS coluna
        FROM pg_constraint con
        JOIN pg_attribute a ON a.attrelid = con.conrelid AND a.attnum = con.conkey[1]
       WHERE con.contype = 'f' AND con.conparentid = 0 AND cardinality(con.conkey) = 1
         AND con.confrelid = 'identidade.identidade'::regclass
         AND EXISTS (SELECT 1 FROM admin_sistema.inventario_da_casa() inv
                      WHERE format('%I.%I', inv.esquema, inv.tabela)::regclass = con.conrelid)
    LOOP
      EXECUTE format('SELECT array_agg(p) FROM unnest($1) AS p WHERE NOT EXISTS (SELECT 1 FROM %s t WHERE t.%I = p)',
                     v_r.tabela, v_r.coluna)
        INTO v_orfas USING v_orfas;
      v_orfas := coalesce(v_orfas, '{}');
    END LOOP;
    v_n := 0;
    IF cardinality(v_orfas) > 0 THEN
      FOR v_r IN
        SELECT con.conrelid::regclass::text AS tabela, a.attname::text AS coluna
          FROM pg_constraint con
          JOIN pg_attribute a ON a.attrelid = con.conrelid AND a.attnum = con.conkey[1]
         WHERE con.contype = 'f' AND con.conparentid = 0 AND cardinality(con.conkey) = 1
           AND con.confrelid = 'identidade.identidade'::regclass
           AND NOT EXISTS (SELECT 1 FROM admin_sistema.inventario_da_casa() inv
                            WHERE format('%I.%I', inv.esquema, inv.tabela)::regclass = con.conrelid)
      LOOP
        EXECUTE format('DELETE FROM %s t WHERE t.%I = ANY ($1)', v_r.tabela, v_r.coluna) USING v_orfas;
        GET DIAGNOSTICS i = ROW_COUNT;
        v_res := v_res || jsonb_build_object(v_r.tabela, coalesce((v_res ->> v_r.tabela)::bigint, 0) + i);
      END LOOP;
      DELETE FROM identidade.identidade WHERE id = ANY (v_orfas);
      GET DIAGNOSTICS v_n = ROW_COUNT;
    END IF;
    v_res := v_res || jsonb_build_object('identidade.identidade', v_n);
  END IF;

  IF v_super THEN
    PERFORM set_config('session_replication_role', v_srr, true);
  ELSE
    FOR i IN 1 .. coalesce(cardinality(v_triggers), 0) LOOP
      EXECUTE v_triggers[i];
    END LOOP;
    FOR i IN 1 .. coalesce(cardinality(v_forcadas), 0) LOOP
      EXECUTE 'ALTER TABLE ' || v_forcadas[i] || ' FORCE ROW LEVEL SECURITY';
    END LOOP;
    PERFORM set_config('lock_timeout', v_lock, true);
  END IF;
  RETURN v_res;
END;
$$;
--;;
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
ALTER TABLE admin_sistema.ente DROP CONSTRAINT IF EXISTS ente_encerrada_sem_execucao;
--;;
ALTER TABLE admin_sistema.ente
  DROP COLUMN IF EXISTS apagamento_em_execucao_desde,
  DROP COLUMN IF EXISTS apagamento_iniciado_em;
