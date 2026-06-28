DROP TABLE IF EXISTS shared.sequencial;
--;;
-- dropar a particionada leva junto particoes + policy + grants + indices.
DROP TABLE IF EXISTS shared.tenancy_prova;
--;;
-- DROP OWNED BY revoga TODOS os privilegios/objetos do role -> robusto mesmo depois de F1 conceder
-- grants noutros schemas (senao DROP ROLE falharia com "dependent privileges").
DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'oplenario_app') THEN
    EXECUTE 'DROP OWNED BY oplenario_app';
  END IF;
END $$;
--;;
DROP ROLE IF EXISTS oplenario_app;
