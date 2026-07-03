-- F6c Slice 1: modulo TRANSPARENCIA — PORTAL PUBLICO (feature 16.5). Read-model do que a Casa PRODUZ,
-- servido white-label ao cidadao SEM cadastro. Este modulo e' um BOUNDED CONTEXT de dominio (§22.2), mas a
-- superficie do portal e' PROJECAO: um consumer do bus (mesmo mecanismo de tempo_real, §22.6 eixo G) assina
-- os eventos de legislativo (`proposicao.protocolada`, `proposicao.transicionou`, `norma.publicada`) e os
-- PROJETA nestas tabelas de leitura — SEM import cross-modulo nem JOIN cross-schema (§22.10). O DONO da
-- verdade da publicacao continua sendo `legislativo` (a norma tem estado 'publicada' + publicado_em); aqui
-- so' se materializa a VISTA publica.
--
-- POR QUE NAO HA colunas de STAGING (lote_id/efetivado_em) NEM particao hash(ente_id): estas tabelas sao
-- PROJECAO, nao verdade de dominio (§22.10: "read-model dropavel" EM PRINCIPIO), NAO ha caminho de
-- importacao de legado de uma projecao (a fundacao #2/staging so se aplica a dado de dominio migrado).
-- CARRY (review architect HIGH-2): "re-projetavel do event log" ainda NAO tem ferramenta — o outbox marca
-- `processed_at` e nao e' log replayavel entre consumidores (um evento ja drenado nao e' reentregue a um
-- consumer registrado depois); truncar estas tabelas hoje perderia o read-model ate' surgir um backfill via
-- HTTP do legislativo ou um mecanismo de redrive do outbox. Nao bloqueia esta fatia (nenhum truncamento
-- planejado), mas a premissa "e' so' dropar e reprojetar" NAO e' executavel ainda — registrar antes de
-- confiar nela em produção. RLS por ente_id continua obrigatoria (Inv.1 + isolamento cross-tenant): o
-- consumer seta o GUC app.ente_id (do ente-id do evento) antes de projetar; a leitura publica abre
-- com-tenant* (RLS isola). O schema transparencia ja existe (mig 0001).

-- 1a migration do modulo transparencia: o role de runtime precisa de USAGE no schema (padrao participacao 0039).
GRANT USAGE ON SCHEMA transparencia TO oplenario_app;
--;;
-- ---------- materia: read-model de UMA proposicao no portal. Chave natural = (ente_id, proposicao_id): o
--            consumer faz UPSERT (INSERT do snapshot em `proposicao.protocolada`; UPDATE do `estado` em
--            `proposicao.transicionou`). Conteudo PUBLICO por natureza (proposicao e' ato legislativo). ----------
CREATE TABLE IF NOT EXISTS transparencia.materia (
  ente_id       uuid NOT NULL,
  proposicao_id uuid NOT NULL,                           -- chave natural (ref legislativo por VALOR, sem FK §22.10)
  tipo          text NOT NULL,                           -- especie legislativa (pl, plc, resolucao, ...) — string do evento
  ano           integer NOT NULL,
  sequencial    bigint  NOT NULL,                        -- numero gapless por (tipo, ano) — origem legislativo
  urn_lex       text    NOT NULL,                        -- coordenada publica LexML (ADR-0002)
  ementa        text    NOT NULL,
  autor_tipo    text,                                    -- vereador|comissao|mesa|executivo|... (nullable)
  autor_texto   text,                                    -- nome de exibicao do autor (nullable; sem autor_id interno)
  estado        text    NOT NULL,                        -- estado ATUAL da tramitacao (atualizado a cada transicao)
  projetado_em  timestamptz NOT NULL DEFAULT now(),      -- 1a projecao (INSERT do snapshot)
  atualizado_em timestamptz NOT NULL DEFAULT now(),      -- ultima projecao (UPDATE de estado)
  PRIMARY KEY (ente_id, proposicao_id)
);
--;;
-- "proposicoes em tramitacao" (review db MEDIUM: db/materia/listar-em-tramitacao SEMPRE filtra so' por
-- ente_id + ORDER BY ano/sequencial nesta fatia — controllers/listar-materias chama com estados-excluidos
-- VAZIO; um indice liderado por `estado` nao serve nem esse caso nem o futuro NOT IN, que nao e' igualdade).
CREATE INDEX IF NOT EXISTS idx_materia_ano_sequencial
  ON transparencia.materia (ente_id, ano DESC, sequencial DESC);
--;;
-- "por especie/ano" (navegacao do acervo, feature AINDA nao implementada nesta fatia — indice aspiracional,
-- nenhuma query hoje filtra por `tipo`).
CREATE INDEX IF NOT EXISTS idx_materia_tipo_ano
  ON transparencia.materia (ente_id, tipo, ano DESC, sequencial DESC);
--;;
ALTER TABLE transparencia.materia ENABLE ROW LEVEL SECURITY;
--;;
-- FORCE: nem o dono (se nao-superuser) bypassa — o consumer projeta como oplenario_pool (NOBYPASSRLS) com o GUC setado.
ALTER TABLE transparencia.materia FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON transparencia.materia;
--;;
-- Projecao NAO tem staging (sem clausula app.ver_lote): so o tenant do GUC ve/escreve. NULLIF(...,'') fail-closed.
CREATE POLICY tenant_isolation ON transparencia.materia
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid)
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
-- UPSERT do consumer: INSERT (snapshot) + UPDATE (estado). Sem DELETE (a projecao dropa por TRUNCATE em re-projecao, DDL).
GRANT SELECT, INSERT, UPDATE ON transparencia.materia TO oplenario_app;
--;;
-- ---------- norma: read-model da NORMA PUBLICADA (legislacao publicada as-enacted — NAO "consolidada" no
--            sentido juridico, ver db/norma.clj; feature 16.5). Chave natural = (ente_id, norma_id); INSERT
--            em `norma.publicada`. publicado_em/veiculo = a PROVA legal da publicacao (dona: legislativo;
--            aqui, VISTA). ----------
CREATE TABLE IF NOT EXISTS transparencia.norma (
  ente_id       uuid NOT NULL,
  norma_id      uuid NOT NULL,                           -- chave natural (ref legislativo por VALOR, sem FK §22.10)
  proposicao_id uuid NOT NULL,                           -- a materia de origem (link do portal)
  tipo_norma    text NOT NULL,                           -- lei|lei_complementar|resolucao|decreto_legislativo|emenda_lom
  numero        integer NOT NULL,
  ano           integer NOT NULL,
  urn           text    NOT NULL,                         -- URN-de-norma LexML (ADR-0002), imutavel
  ementa        text    NOT NULL,
  publicado_em  timestamptz NOT NULL,                     -- instante da publicacao (eficacia)
  veiculo_publicacao text NOT NULL,                       -- veiculo de publicacao (prova; espelha legislativo.norma)
  projetado_em  timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (ente_id, norma_id)
);
--;;
-- "ficha da materia" (review db HIGH: db/norma/buscar-por-proposicao filtra por proposicao_id em TODA
-- ficha publica de materia — a rota mais visitada do portal; sem este indice era seq scan). UNIQUE dobra
-- como defesa-em-profundidade da regra de dominio "uma norma por proposicao" (o ON CONFLICT de inserir!
-- e' por norma_id, nao por proposicao_id — este indice barra uma 2a norma para a MESMA materia).
CREATE UNIQUE INDEX IF NOT EXISTS idx_norma_proposicao
  ON transparencia.norma (ente_id, proposicao_id);
--;;
-- "legislacao publicada" (acervo por publicacao mais recente). Escopo tenant no prefixo.
CREATE INDEX IF NOT EXISTS idx_norma_publicado
  ON transparencia.norma (ente_id, publicado_em DESC);
--;;
-- "por tipo/numero/ano" (busca de uma norma especifica: 'Lei 123/2026').
CREATE INDEX IF NOT EXISTS idx_norma_tipo_numero
  ON transparencia.norma (ente_id, tipo_norma, ano DESC, numero DESC);
--;;
ALTER TABLE transparencia.norma ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE transparencia.norma FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON transparencia.norma;
--;;
CREATE POLICY tenant_isolation ON transparencia.norma
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid)
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
-- INSERT (idempotente por PK: norma publica uma vez). SELECT p/ a leitura. Sem UPDATE/DELETE (norma-vista imutavel).
GRANT SELECT, INSERT ON transparencia.norma TO oplenario_app;
