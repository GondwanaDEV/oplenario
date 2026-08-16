-- Etapa 5 fatia 4 (chamada-etapa5-folha, brief etapa5-folha-brief.md D2/D3): O CONGELAMENTO da folha de
-- presenca — a linha que ancora as DUAS representacoes do MESMO artefato (HTML canonico + PDF), imutavel e
-- versionada.
--
-- MOLDE = legislativo.artefato_publicacao (mig 0046), NAO compliance.remessa_gerada (D2): a remessa carrega
-- UPDATE + coluna `estado` porque tem CICLO DE SUBMISSAO a terceiro (rascunho->validada->submetida->
-- aceita/rejeitada, o TCE responde). A folha nao se submete a ninguem — ela so' existe (foi congelada) ou
-- nao. Por isso, como o artefato de publicacao: append-only PURO (GRANT so' SELECT+INSERT, trigger
-- shared.imut_append_only), SEM coluna `estado`, e SEM `lote_id`/`efetivado_em` (e' artefato POS-FATO — uma
-- sessao so' fecha depois de encerrada/nao_realizada/arquivada, e nao existe fluxo de import de acervo legado
-- de folhas de presenca na V1, ao contrario de `chamada_conduzida`/`presenca_evento`, que carregam staging
-- porque o EVENTO em si pode ter origem de importacao).
--
-- D3 — UMA linha por versao, DOIS trios NOT NULL (html_*/pdf_*): nenhuma das tres tabelas de artefato do
-- repo (artefato_publicacao, remessa_gerada) tem mais de um trio hash/content_type/objeto_store_ref, e
-- tabela filha (uma linha por representacao) nao tem precedente aqui. Uma linha da' de graca a garantia "os
-- dois hashes sao do MESMO congelamento", que duas linhas exigiriam reconciliar por FK/timestamp.
--
-- D7 (brief) — a VERSAO e' PROPOSTA pela aplicacao (le' o MAX, renderiza com o numero previsto, insere
-- EXPLICITO), nao computada pelo proprio INSERT (`INSERT ... SELECT MAX+1`, o molde de artefato_publicacao/
-- remessa_gerada): a folha IMPRIME a propria versao no papel, entao o numero tem de ser conhecido ANTES da
-- renderizacao — circular se o INSERT o decidisse depois. O UNIQUE abaixo segue sendo o que garante a
-- corretude sob corrida; so' muda QUEM propoe o numero (aplicacao, nao o SELECT). Ver
-- `sessoes/controllers.clj` (`gerar-folha!`) para o retry.
--
-- D4 — NENHUM evento de dominio: o molde da remessa (`compliance/events/remessa.clj`) e' so' comentario, sem
-- consumidor — o projeto proibe abrir uma segunda ferida evento-sem-consumidor (a primeira e'
-- `gravacao.segmento-captado`). A linha do banco e' a ANCORA (INSERT antes do blob, mesma disciplina de
-- artefato_publicacao/remessa_gerada — re-derivavel byte a byte a partir desta linha, porque a renderizacao
-- e' deterministica e todos os seus insumos moram aqui: versao, gerada_em, e o dado da sessao, imutavel
-- depois de fechada).

CREATE TABLE IF NOT EXISTS sessoes.folha_sessao (
  id                     uuid PRIMARY KEY,
  ente_id                uuid NOT NULL,
  sessao_id              uuid NOT NULL,
  versao                 integer NOT NULL,             -- PROPOSTA pela aplicacao (D7), nao MAX+1 do INSERT
  spec_versao            text NOT NULL,                -- versao do renderizador (`gerador-folha/spec-versao`)
  html_hash              text NOT NULL,                -- sha256:... do HTML canonico (Fatia 2)
  html_content_type      text NOT NULL,
  html_objeto_store_ref  text NOT NULL,                -- ponteiro no objeto_store; o binario nao mora no banco
  pdf_hash               text NOT NULL,                -- sha256:... do PDF (Fatia 3)
  pdf_content_type       text NOT NULL,
  pdf_objeto_store_ref   text NOT NULL,
  gerada_por             uuid NOT NULL,                -- o ator que pediu o congelamento (chave da D9 dedup)
  gerada_em              timestamptz NOT NULL,          -- INSTANTE DE DOMINIO: o que o HTML/PDF imprimem
  criado_em              timestamptz NOT NULL DEFAULT now(),  -- AUDIT (quando o sistema soube) != gerada_em
  -- re-congelamento deliberado = NOVA versao (D9: duplo-clique dentro da janela de 30s NAO chega aqui — a
  -- aplicacao devolve a versao existente antes de renderizar). O prefixo (ente_id, sessao_id) serve as duas
  -- leituras: "folhas desta sessao" e "a ULTIMA versao desta sessao" (MAX).
  UNIQUE (ente_id, sessao_id, versao),
  -- integridade referencial INTRA-schema (§22.10): a folha aponta uma sessao REAL do MESMO tenant. FK checks
  -- bypassam RLS (regra do PG), seguro sob FORCE RLS de ambas as tabelas.
  CONSTRAINT folha_sessao_sessao_fk FOREIGN KEY (ente_id, sessao_id)
    REFERENCES sessoes.sessao (ente_id, id)
);
--;;
-- D9: o insumo da deduplicacao — "a folha mais recente DESTE ator NESTA sessao", mesmo padrao de
-- idx_chamada_conduzida_sessao (mig 0072).
CREATE INDEX IF NOT EXISTS idx_folha_sessao_ator
  ON sessoes.folha_sessao (ente_id, sessao_id, gerada_por, gerada_em);
--;;
ALTER TABLE sessoes.folha_sessao ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE sessoes.folha_sessao FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON sessoes.folha_sessao;
--;;
CREATE POLICY tenant_isolation ON sessoes.folha_sessao
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid)
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
-- APPEND-ONLY IMUTAVEL (D2/D3): so' SELECT + INSERT — a folha congelada nunca muta; re-congelamento = nova versao.
GRANT SELECT, INSERT ON sessoes.folha_sessao TO oplenario_app;
--;;
CREATE TRIGGER trg_folha_sessao_append_only
  BEFORE UPDATE OR DELETE ON sessoes.folha_sessao
  FOR EACH ROW EXECUTE FUNCTION shared.imut_append_only();
