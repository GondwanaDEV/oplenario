-- F6c Slice 4a: legislativo — ARTEFATO DE PUBLICACAO OFICIAL ("DO-lite", doc-mestre L287, feature 16.5).
-- O artefato LEGAL da publicacao de uma norma: o DOCUMENTO OFICIAL (cabecalho do ato + texto legal integral),
-- assinado (ICP-Brasil, §22.5 eixo F) e IMUTAVEL, publicado no veiculo da Camara.
--
-- FRONTEIRA (§22.10 / carry architect MEDIUM-4 do Slice 1): este e' artefato LEGAL — dono e' o LEGISLATIVO
-- (verdade de dominio; so' ele tem o texto congelado via texto_versao_id), NAO transparencia (que e' projecao
-- sem verdade propria). transparencia EXIBE o artefato (Slice 4b), nao o GERA. "Mesmo padrao do artefato de
-- remessa" (doc-mestre L287) = a MAQUINA de imutabilidade/proveniencia (tabela append-only versionada + hash +
-- binario no objeto_store + porta de assinatura), NAO a DSL de descritor de layout da remessa: aquela existe
-- porque a remessa e' arquivo legivel-por-maquina do TCE (extracao campo-a-campo); um ato de DO e' documento de
-- estrutura FIXA -> renderizador proprio (gerador_publicacao), sem descritor.
--
-- APPEND-ONLY IMUTAVEL (Inv.10, §22.4.3 disc.4): re-geracao = NOVA versao (nunca muta hash/objeto_store_ref/
-- assinatura da anterior). GRANT so' SELECT + INSERT (sem UPDATE/DELETE) — ao contrario de remessa_gerada, que
-- carrega UPDATE por ter ciclo de submissao ao TCE (rascunho->submetida->aceita). O artefato de publicacao NAO
-- tem ciclo: ele so' existe (foi gerado) ou nao.
--
-- ANCORA-ANTES-DO-BLOB (carry F7 reconciliador, mesma disciplina de compliance.remessa_gerada): o Repo INSERE
-- a linha ANTES de gravar o binario no objeto_store (a linha e' a ancora recuperavel; um binario orfao seria
-- pior). Consequencia: se o objeto_store falhar APOS o commit, esta linha existe SEM o blob resolvivel — e,
-- diferente de remessa_gerada, aqui NAO ha coluna de estado que sinalize isso. A EXIBICAO (Slice 4b) DEVE
-- tratar `objeto_store/obter -> nil` como condicao de ALERTA (nunca "documento oficial" confiavel, nem 404
-- silencioso), e o reconciliador F7 (rascunho-sem-blob) deve incluir ESTA tabela explicitamente.
--
-- ASSINATURA (§22.5 eixo F) = [GAP]/infra-deferida nesta fatia: `assinatura_algoritmo` = 'STUB-ICP-v0' marca
-- HONESTAMENTE que a validacao real da cadeia ICP-Brasil (cert valido na cadeia da AC-Raiz + CPF-do-cert ==
-- CPF-da-sessao + carimbo de tempo confiavel) ainda nao esta fiada (carry F1.4). Assinatura DESTACADA (detached,
-- padrao CAdES): a assinatura e' metadado sobre o binario, nao embutida no binario hasheado. `assinado_por` (o
-- ator que assina — gesto humano deliberado, DISTINTO de aprovar/publicar) fica NULL ate' a borda HTTP
-- autenticada + step-up (Slice 4b / F1.4-carry). Layout FISICO do documento (PDF/DO real) = [GAP] (o
-- serializador emite texto ilustrativo DETERMINISTICO — mesma entrada, mesmos bytes, p/ hash estavel).

CREATE TABLE IF NOT EXISTS legislativo.artefato_publicacao (
  id                    uuid PRIMARY KEY,
  ente_id               uuid NOT NULL,
  norma_id              uuid NOT NULL,                    -- a norma publicada cujo ato oficial este artefato materializa
  versao                integer NOT NULL,                 -- re-geracao = NOVA versao (MAX+1 atomico); nunca muta a anterior
  spec_versao           text NOT NULL,                    -- versao do renderizador/layout (ex.: 'do-lite-v0')
  content_type          text NOT NULL,                    -- tipo do binario serializado (ex.: 'text/plain; charset=utf-8')
  hash                  text NOT NULL,                    -- hash do binario (sha256:...) p/ integridade
  objeto_store_ref      text NOT NULL,                    -- ponteiro p/ o binario no objeto_store (o binario nao mora no banco)
  assinatura_algoritmo  text NOT NULL,                    -- 'STUB-ICP-v0' ([GAP] real ICP-Brasil, §22.5 eixo F)
  assinatura_b64        text NOT NULL,                    -- assinatura DESTACADA sobre o binario (base64)
  assinado_por          uuid,                             -- ator que assinou (NULL ate' a borda autenticada — Slice 4b)
  assinado_em           timestamptz NOT NULL DEFAULT now(),  -- carimbo (server-now no stub; [GAP] autoridade de tempo ICP)
  criado_em             timestamptz NOT NULL DEFAULT now(),
  -- re-geracao = nova versao (doc-mestre L287, mesmo padrao da remessa). O prefixo (ente_id, norma_id) da UNIQUE
  -- serve as duas leituras: "artefatos da norma X" e "ultima versao da norma X".
  UNIQUE (ente_id, norma_id, versao),
  -- integridade referencial INTRA-schema (§22.10 proibe cross-SCHEMA JOIN/FK, nao intra): o artefato aponta uma
  -- norma REAL do MESMO tenant. norma.PK = (ente_id, id); a FK inclui ente_id. FK checks BYPASSAM RLS (regra de
  -- integridade referencial do PG), logo seguro sob FORCE RLS de ambas. Defesa-em-profundidade: o guard de
  -- EXISTENCIA+ESTADO ('publicada') e' de aplicacao (Repo/gerar-artefato-publicacao!), a FK garante que nenhum
  -- caminho (script/bug futuro) crie um artefato orfao apontando norma inexistente.
  CONSTRAINT artefato_publicacao_norma_fk FOREIGN KEY (ente_id, norma_id)
    REFERENCES legislativo.norma (ente_id, id)
);
--;;
ALTER TABLE legislativo.artefato_publicacao ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE legislativo.artefato_publicacao FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON legislativo.artefato_publicacao;
--;;
CREATE POLICY tenant_isolation ON legislativo.artefato_publicacao
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid)
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
-- APPEND-ONLY IMUTAVEL: so' SELECT + INSERT (sem UPDATE/DELETE) — o artefato nunca muta; re-geracao = nova versao.
GRANT SELECT, INSERT ON legislativo.artefato_publicacao TO oplenario_app;
