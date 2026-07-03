-- ordem por FK: denuncia_comentario e moderacao_comentario (FK -> comentario) antes de comentario.
DROP TABLE IF EXISTS participacao.denuncia_comentario;
--;;
DROP TABLE IF EXISTS participacao.moderacao_comentario;
--;;
DROP TABLE IF EXISTS participacao.comentario;
-- NAO ha REVOKE USAGE: a 0039 (1a migration do modulo) e' quem carrega o GRANT/REVOKE USAGE do schema.
