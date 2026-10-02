-- ADR-0018 (fatia 2, Eixo 4.5): o APAGAMENTO dos dados de uma Casa encerrada, no proprio banco.
--
-- (1) `admin_sistema.inventario_da_casa()` — O INVENTARIO DESCOBERTO (nunca uma lista a mao): quais tabelas guardam
--     linhas de uma Casa e como achar essas linhas. E' a fonte unica da exportacao (9.6) e do apagamento: uma tabela
--     nova de tenant entra sozinha nos dois.
--       - BASE: toda tabela (nao-particao) com a coluna `ente_id`, fora dos schemas supratenant/derivados:
--         `admin_sistema` (o registro, a atuacao, as exportacoes — a prova de que entregamos e apagamos, Eixo 4.6),
--         `ia` (o satelite e' dono do proprio schema; o core NUNCA o toca — ADR-0006/0008), `public` (o
--         schema_migrations) e os de sistema. Predicado: `ente_id = $1`. Tabelas com linhas de REFERENCIA de
--         `ente_id` nulo (ex.: `normas` publica a LOM/CF sem Casa) ficam com as de referencia: `NULL = $1` e' falso.
--       - FILHAS: tabela SEM `ente_id` com FK para uma tabela do inventario pertence a Casa pela FK (recursivo, ate'
--         10 niveis). Predicado: EXISTS no pai pela FK. Tabelas de referencia sem `ente_id` (municipios, tribunais,
--         catalogo do motor) nao tem FK para tabela de tenant e por isso NUNCA entram.
--       - Excecao unica: em `shared.outbox` os eventos `admin_sistema.*` sao da Operacao (supratenant) e ficam — o relay
--         ainda pode precisar entrega-los depois do apagamento.
--     `exporta` = a tabela e' isolada por RLS (ou e' filha de uma que e'): a exportacao le com o role de runtime e a
--     RLS, e so' exporta o que a RLS isola. As tabelas de tenant SEM RLS sao infraestrutura ou segredo (sessoes e
--     credenciais por hash, outbox, inbox, o feed core<->IA) — apagadas, nunca exportadas.
--     `nivel` = a ordem de apagar pela FK (filhas antes dos pais). Ciclo de FK entre tabelas do inventario = excecao
--     (fail-closed: uma migration futura que crie ciclo quebra o teste, nao o apagamento de uma Casa real).
--
-- (2) `admin_sistema.conferir_apagamento(ente, pedido)` — as salvaguardas do Eixo 4.5, conferidas NO BANCO (nao por
--     disciplina do chamador): pedido `apagar` aprovado por OUTRO operador; a Casa suspensa com encerramento em curso
--     (ou ja' encerrada: retomada); exportacao PRONTA e CONFIRMADA ha' pelo menos 90 dias. Recusa com excecao.
--
-- (3) `admin_sistema.apagar_dados_da_casa(ente, pedido)` — SECURITY DEFINER do DONO das tabelas. O app conecta como
--     `oplenario_pool` (NOBYPASSRLS, nao-dono) e ~44 migrations poem triggers de imutabilidade/append-only que recusam
--     DELETE: so' o dono, dentro desta funcao, apaga. Confere (2), apaga TODAS as linhas da Casa no inventario, na ordem
--     da FK, apaga as identidades que so' existiam por esta Casa e devolve {"schema.tabela": linhas}. Idempotente
--     (rodar de novo apaga zero). Nunca toca `admin_sistema` nem tabela de referencia.
--
--     COMO OS TRIGGERS E A RLS SAO CONTORNADOS — so' nesta transacao, e nenhuma outra Casa ve nada diferente:
--       - dono SUPERUSER (o compose e o deploy atual, imagem postgres com POSTGRES_USER): `session_replication_role =
--         replica` LOCAL a esta sessao (desliga os triggers de usuario so' aqui; a RLS o superuser ja' nao sofre). Sem
--         DDL e sem lock de tabela: as outras Casas seguem trabalhando durante o apagamento.
--       - dono NAO-superuser (Postgres gerenciado): `LOCK TABLE ... ACCESS EXCLUSIVE` nas tabelas com FORCE RLS ou
--         trigger, depois `NO FORCE ROW LEVEL SECURITY` + `DISABLE TRIGGER` e, antes de devolver, `FORCE` + `ENABLE` de
--         novo. Tudo transacional e sob o lock: quem esperava o lock ve o catalogo ja' restaurado, e um erro no meio
--         desfaz tudo. Custo: as escritas de todas as Casas nessas tabelas esperam o apagamento (segundos; lock_timeout
--         de 15 s para nao enfileirar a plataforma atras de uma transacao longa — falhou, roda de novo).
--     Chame numa transacao CURTA e propria (o lock e o modo de replicacao valem ate' o fim da transacao do chamador).
CREATE OR REPLACE FUNCTION admin_sistema.inventario_da_casa()
RETURNS TABLE (esquema text, tabela text, nivel integer, exporta boolean, predicado text)
LANGUAGE plpgsql STABLE SET search_path = pg_catalog, pg_temp AS $$
DECLARE
  v_ciclo boolean;
BEGIN
  RETURN QUERY
  WITH RECURSIVE
  tabs AS (
    SELECT c.oid, n.nspname::text COLLATE "default" AS esq, c.relname::text COLLATE "default" AS tab,
           c.relrowsecurity AS rls
      FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE c.relkind IN ('r', 'p') AND NOT c.relispartition
       AND n.nspname NOT IN ('admin_sistema', 'ia', 'public', 'information_schema')
       AND n.nspname NOT LIKE 'pg\_%'),
  com_ente AS (
    SELECT t.* FROM tabs t
     WHERE EXISTS (SELECT 1 FROM pg_attribute a
                    WHERE a.attrelid = t.oid AND a.attname = 'ente_id' AND a.attnum > 0 AND NOT a.attisdropped)),
  fks AS (
    SELECT con.conrelid AS filho, con.confrelid AS pai, con.conkey, con.confkey
      FROM pg_constraint con
     WHERE con.contype = 'f' AND con.conparentid = 0 AND con.conrelid <> con.confrelid),
  membros (oid, esq, tab, exporta, pred, prof) AS (
    SELECT ce.oid, ce.esq, ce.tab, ce.rls,
           CASE WHEN ce.esq = 'shared' AND ce.tab = 'outbox'
                THEN '{a}.ente_id = $1 AND {a}.tipo NOT LIKE ''admin\_sistema.%'''
                ELSE '{a}.ente_id = $1' END COLLATE "C",
           0
      FROM com_ente ce
    UNION ALL
    SELECT t.oid, t.esq, t.tab, m.exporta,
           format('EXISTS (SELECT 1 FROM %I.%I AS p%s WHERE %s AND %s)', m.esq, m.tab, m.prof + 1,
                  (SELECT string_agg(format('p%s.%I = {a}.%I', m.prof + 1, ap.attname, af.attname), ' AND ')
                     FROM unnest(f.conkey, f.confkey) AS k(col_filho, col_pai)
                     JOIN pg_attribute af ON af.attrelid = f.filho AND af.attnum = k.col_filho
                     JOIN pg_attribute ap ON ap.attrelid = f.pai AND ap.attnum = k.col_pai),
                  replace(m.pred, '{a}', 'p' || (m.prof + 1))),
           m.prof + 1
      FROM membros m
      JOIN fks f ON f.pai = m.oid
      JOIN tabs t ON t.oid = f.filho
     WHERE m.prof < 10 AND NOT EXISTS (SELECT 1 FROM com_ente ce WHERE ce.oid = t.oid)),
  inventario AS (
    SELECT m.oid, m.esq, m.tab, bool_and(m.exporta) AS exporta,
           replace(string_agg('(' || m.pred || ')', ' OR ' ORDER BY m.pred), '{a}', 't') AS pred
      FROM membros m GROUP BY m.oid, m.esq, m.tab),
  arestas AS (
    SELECT DISTINCT f.filho, f.pai FROM fks f
     WHERE EXISTS (SELECT 1 FROM inventario i WHERE i.oid = f.filho)
       AND EXISTS (SELECT 1 FROM inventario i WHERE i.oid = f.pai)),
  -- nivel = o caminho mais longo de filhas que apontam para a tabela (a filha sem quem a referencie = 0)
  caminhos (oid, n) AS (
    SELECT i.oid, 0 FROM inventario i
    UNION ALL
    SELECT a.pai, c.n + 1 FROM caminhos c JOIN arestas a ON a.filho = c.oid
     WHERE c.n < 200)
  SELECT i.esq, i.tab, (SELECT max(c.n) FROM caminhos c WHERE c.oid = i.oid)::integer, i.exporta, i.pred
    FROM inventario i
   ORDER BY 3, 1, 2;

  -- um caminho de 200 passos so' existe com ciclo: recusa (o teste de integracao pega antes de qualquer Casa real)
  WITH RECURSIVE
  tabs AS (
    SELECT c.oid FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE c.relkind IN ('r', 'p') AND NOT c.relispartition
       AND n.nspname NOT IN ('admin_sistema', 'ia', 'public', 'information_schema') AND n.nspname NOT LIKE 'pg\_%'
       AND EXISTS (SELECT 1 FROM pg_attribute a
                    WHERE a.attrelid = c.oid AND a.attname = 'ente_id' AND a.attnum > 0 AND NOT a.attisdropped)),
  arestas AS (
    SELECT DISTINCT con.conrelid AS filho, con.confrelid AS pai FROM pg_constraint con
     WHERE con.contype = 'f' AND con.conparentid = 0 AND con.conrelid <> con.confrelid
       AND con.conrelid IN (SELECT oid FROM tabs) AND con.confrelid IN (SELECT oid FROM tabs)),
  caminhos (oid, n) AS (
    SELECT t.oid, 0 FROM tabs t
    UNION ALL
    SELECT a.pai, c.n + 1 FROM caminhos c JOIN arestas a ON a.filho = c.oid WHERE c.n < 200)
  SELECT EXISTS (SELECT 1 FROM caminhos WHERE n >= 200) INTO v_ciclo;
  IF v_ciclo THEN
    RAISE EXCEPTION 'inventario da Casa: ciclo de FK entre tabelas de tenant (ordem de apagar indefinida)';
  END IF;
END;
$$;
--;;
REVOKE ALL ON FUNCTION admin_sistema.inventario_da_casa() FROM PUBLIC;
--;;
GRANT EXECUTE ON FUNCTION admin_sistema.inventario_da_casa() TO oplenario_operacao;
--;;
CREATE OR REPLACE FUNCTION admin_sistema.conferir_apagamento(p_ente uuid, p_pedido uuid)
RETURNS void LANGUAGE plpgsql STABLE SET search_path = pg_catalog, pg_temp AS $$
DECLARE
  v_pedido admin_sistema.pedido_restricao%ROWTYPE;
  v_ente   admin_sistema.ente%ROWTYPE;
BEGIN
  IF p_ente IS NULL OR p_pedido IS NULL THEN
    RAISE EXCEPTION 'apagamento recusado: Casa e pedido sao obrigatorios';
  END IF;
  SELECT * INTO v_pedido FROM admin_sistema.pedido_restricao WHERE id = p_pedido;
  IF NOT FOUND OR v_pedido.ente_id <> p_ente OR v_pedido.acao <> 'apagar' THEN
    RAISE EXCEPTION 'apagamento recusado: nao ha pedido de apagamento % para esta Casa', p_pedido;
  END IF;
  IF v_pedido.estado <> 'aprovado' THEN
    RAISE EXCEPTION 'apagamento recusado: o pedido de apagamento nao foi aprovado (estado %)', v_pedido.estado;
  END IF;
  IF v_pedido.decidido_por IS NULL OR v_pedido.decidido_por = v_pedido.pedido_por THEN
    RAISE EXCEPTION 'apagamento recusado: o pedido precisa ser aprovado por outro operador';
  END IF;
  SELECT * INTO v_ente FROM admin_sistema.ente WHERE ente_id = p_ente;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'apagamento recusado: Casa % nao esta no registro', p_ente;
  END IF;
  IF NOT ((v_ente.estado = 'suspenso' AND v_ente.motivo_restricao = 'encerramento_em_curso')
          OR v_ente.estado = 'encerrado') THEN
    RAISE EXCEPTION 'apagamento recusado: a Casa nao esta com o encerramento em curso (estado %)', v_ente.estado;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM admin_sistema.exportacao_casa x
                  WHERE x.ente_id = p_ente AND x.estado = 'pronta' AND x.confirmada_em IS NOT NULL) THEN
    RAISE EXCEPTION 'apagamento recusado: a Casa nao confirmou o recebimento de uma exportacao completa';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM admin_sistema.exportacao_casa x
                  WHERE x.ente_id = p_ente AND x.estado = 'pronta'
                    AND x.confirmada_em <= now() - interval '90 days') THEN
    RAISE EXCEPTION 'apagamento recusado: a guarda de 90 dias desde a confirmacao do recebimento ainda nao terminou';
  END IF;
END;
$$;
--;;
REVOKE ALL ON FUNCTION admin_sistema.conferir_apagamento(uuid, uuid) FROM PUBLIC;
--;;
GRANT EXECUTE ON FUNCTION admin_sistema.conferir_apagamento(uuid, uuid) TO oplenario_operacao;
--;;
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
REVOKE ALL ON FUNCTION admin_sistema.apagar_dados_da_casa(uuid, uuid) FROM PUBLIC;
--;;
GRANT EXECUTE ON FUNCTION admin_sistema.apagar_dados_da_casa(uuid, uuid) TO oplenario_operacao;
