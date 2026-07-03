-- ordem inversa da FK: resposta_esic (FK -> recurso_esic) antes de recurso_esic.
DROP TABLE IF EXISTS participacao.resposta_esic;
--;;
DROP TABLE IF EXISTS participacao.recurso_esic;
-- NAO ha REVOKE USAGE: a 0039 (1a migration do modulo) e' quem carrega o GRANT/REVOKE USAGE do schema.
