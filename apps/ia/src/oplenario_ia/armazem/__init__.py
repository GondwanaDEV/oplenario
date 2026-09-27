"""O armazenamento próprio do satélite (§22.3.4: a transcrição vive na IA; ADR-0008): o cursor do feed, a fila de
trabalhos (idempotente pela chave do evento) e as transcrições. Porta com dois adaptadores: memória (testes) e
Postgres (schema `ia`, papel próprio em produção)."""
