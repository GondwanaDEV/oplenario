-- F6c Slice 4b: transparencia — PROJECAO do ARTEFATO DE PUBLICACAO OFICIAL ("DO-lite", feature 16.5). Read-model
-- do artefato legal que `legislativo` GERA (mig 0046, verdade de dominio) e EMITE via `artefato.publicacao.gerado`.
-- Aqui so' se materializa o PONTEIRO + proveniencia publica p/ a rota publica de download servir o binario SEM
-- consultar o legislativo (§22.10: sem import/JOIN cross-modulo; o consumer projeta do evento). O DONO da verdade
-- (o binario no objeto_store + a linha imutavel em legislativo.artefato_publicacao) segue no legislativo.
--
-- PROJECAO (nao verdade de dominio): sem staging (lote_id/efetivado_em) nem particao hash(ente_id) — mesmo
-- racional de transparencia.materia/norma (mig 0044). CARRY architect HIGH-2 herdado: "re-projetavel do event
-- log" ainda NAO tem ferramenta (o outbox marca processed_at, nao e' log replayavel) — truncar hoje perderia o
-- read-model; nao bloqueia (nenhum truncamento planejado), mas registrar antes de confiar em prod.
--
-- INSERT-ONLY IDEMPOTENTE: `ON CONFLICT (ente_id, artefato_id) DO NOTHING` — o artefato e' imutavel (uma vez
-- gerado, nunca muda); um redrive do relay COMPARTILHADO com idempotency-key nova nao pode lancar PK-violation
-- (envenenaria o bus de TODOS os modulos — mesmo cinto de db/norma/inserir!). Sem UPDATE/DELETE.
--
-- ANCORA-ANTES-DO-BLOB (herdado de legislativo.artefato_publicacao, mig 0046): a linha (aqui e la') pode existir
-- SEM o binario resolvivel se o objeto_store falhar APOS o commit do INSERT no legislativo. NAO ha coluna de
-- estado que sinalize isso. A rota de download (Slice 4b) DEVE tratar `objeto_store/obter -> nil` como ALERTA
-- (500 + log; NUNCA "documento oficial" confiavel nem 404 silencioso), e o reconciliador F7 inclui a tabela de
-- legislativo (a fonte da verdade), nao esta projecao.

-- ---------- artefato_publicacao: read-model de UM artefato de uma norma. Chave natural = (ente_id, artefato_id);
--            INSERT em `artefato.publicacao.gerado`. `assinado` = (some? assinado_por) no legislativo (stub=false
--            enquanto ICP real/ator assinante nao fiados, F1.4-carry). Sem assinatura_b64 (proveniencia interna
--            do legislativo — o portal so' precisa saber SE ha, o algoritmo, e o hash de integridade). ----------
CREATE TABLE IF NOT EXISTS transparencia.artefato_publicacao (
  ente_id              uuid NOT NULL,
  norma_id             uuid NOT NULL,                       -- a norma cujo ato oficial este artefato materializa (ref por VALOR, sem FK §22.10)
  artefato_id          uuid NOT NULL,                       -- id do artefato no legislativo (chave natural da projecao)
  versao               integer NOT NULL,                    -- re-geracao = nova versao (a mais recente e' a exibida)
  objeto_store_ref     text NOT NULL,                       -- ponteiro p/ o binario no objeto_store (a rota le' dele)
  content_type         text NOT NULL,                       -- tipo do binario (Content-Type da resposta de download)
  hash                 text NOT NULL,                       -- sha256:... do binario (integridade; exibivel)
  assinatura_algoritmo text NOT NULL,                       -- 'STUB-ICP-v0' ([GAP] real ICP-Brasil, §22.5 eixo F)
  assinado             boolean NOT NULL,                    -- (some? assinado_por) no legislativo — false enquanto stub
  criado_em            timestamptz NOT NULL,                -- criado_em do artefato no legislativo (Instant do ISO do evento)
  projetado_em         timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (ente_id, artefato_id)
);
--;;
-- "artefato mais recente da norma" (a rota de download resolve o ponteiro por norma_id, maior versao). Prefixo
-- tenant; versao DESC serve o ORDER BY ... LIMIT 1 sem sort adicional. UNIQUE (ente_id, norma_id, versao):
-- defesa-em-profundidade (a fonte legislativo.artefato_publicacao ja' garante via UNIQUE + MAX+1 atomico; a
-- projecao espelha o invariante — mesmo racional do idx UNIQUE de defesa da irma transparencia.norma, mig 0044)
-- para que "o artefato oficial mais recente" NUNCA seja ambiguo (dois artefatos distintos com a mesma versao).
-- O UNIQUE INDEX (nao um CONSTRAINT) permite manter o `versao DESC` que serve o ORDER BY sem custo extra. A
-- projecao absorve QUALQUER conflito de chave unica com `ON CONFLICT DO NOTHING` SEM alvo (db/inserir!) — nunca
-- lanca no relay COMPARTILHADO (uma re-entrega por artefato_id no-op pela PK; um par (norma,versao) duplicado
-- com artefato_id novo — so' possivel sob corrupcao da fonte — no-op por ESTE indice, sem envenenar o bus).
CREATE UNIQUE INDEX IF NOT EXISTS idx_artefato_pub_norma_versao
  ON transparencia.artefato_publicacao (ente_id, norma_id, versao DESC);
--;;
ALTER TABLE transparencia.artefato_publicacao ENABLE ROW LEVEL SECURITY;
--;;
-- FORCE: nem o dono (se nao-superuser) bypassa — o consumer projeta como oplenario_pool (NOBYPASSRLS) com o GUC setado.
ALTER TABLE transparencia.artefato_publicacao FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON transparencia.artefato_publicacao;
--;;
-- Projecao NAO tem staging: so o tenant do GUC ve/escreve. NULLIF(...,'') fail-closed.
CREATE POLICY tenant_isolation ON transparencia.artefato_publicacao
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid)
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
-- INSERT (idempotente por PK: ON CONFLICT DO NOTHING) + SELECT (leitura publica). Sem UPDATE/DELETE (artefato-vista imutavel).
GRANT SELECT, INSERT ON transparencia.artefato_publicacao TO oplenario_app;
