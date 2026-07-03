-- ordem por FK: resposta_titular (FK -> solicitacao_titular) antes de solicitacao_titular; encarregado independente.
DROP TABLE IF EXISTS participacao.resposta_titular;
--;;
DROP TABLE IF EXISTS participacao.solicitacao_titular;
--;;
DROP TABLE IF EXISTS participacao.encarregado;
-- NAO ha REVOKE USAGE: a 0039 (1a migration do modulo) e' quem carrega o GRANT/REVOKE USAGE do schema.
