-- ADR-0021 Parte B — o JULGAMENTO DAS CONTAS. Quatro tabelas no schema do legislativo (a prestacao e' materia da Casa:
-- o PDL que ela protocola tramita como qualquer outro, e a votacao e' a do proprio modulo).
--
-- (1) `prestacao_contas` — uma por (Casa, tipo, exercicio). `governo_prefeito` (CF art. 31 §2: o parecer previo do
--     TCE so' deixa de prevalecer por 2/3 dos membros) carrega o parecer previo, o PDL (`proposicao_id`, protocolado
--     na MESMA tx do registro), a notificacao do responsavel e o resultado. `gestao_camara` (contas da Mesa) e' so'
--     ACOMPANHAMENTO: processo, situacao no TCE e documentos — sem PDL, sem votacao (B6).
--     O ESTADO NAO E' COLUNA: e' derivado na leitura (logica pura) de notificado_em / prazo_defesa_ate /
--     defesa_juntada_em / resultado. Os dois prazos sao CONGELADOS no ato que os abre (`prazo_julgamento_ate` no
--     registro, `prazo_defesa_ate` na notificacao): mudar o parametro da Casa depois nao mexe em prazo ja' correndo.
--     O resultado so' e' gravado pelo encerramento da votacao do PDL, na mesma tx (nunca por rota propria), uma vez.
-- (2) `prestacao_contas_documento` — INSERT-ONLY: o PDF do parecer, o relatorio, a notificacao, a defesa... O arquivo
--     vai ao object storage sob `contas/<ente>/<prestacao>/<doc>` (a convencao `<pasta>/<ente>/` que a exportacao e o
--     apagamento da Casa descobrem sozinhos, ADR-0018) ANTES da linha; aqui fica o nome, o tipo, o tamanho e o sha256.
-- (3) `parametro_contas` — uma linha por Casa; sem linha vale o padrao (defesa 15 dias, julgamento 60), `[GAP]` por LOM.
-- (4) `regra_votacao_materia` — a regra de votacao por CLASSE de materia, como DADO (Inv. 4): uma guarda DSL avaliada
--     pelo MESMO motor ao abrir a votacao. Vale para todas as Casas (CF art. 31 §2 e' federal): sem `ente_id`, sem RLS,
--     so' leitura para o app. Semeada aqui.
CREATE TABLE IF NOT EXISTS legislativo.prestacao_contas (
  ente_id               uuid        NOT NULL,
  id                    uuid        NOT NULL DEFAULT gen_random_uuid(),
  tipo                  text        NOT NULL CHECK (tipo IN ('governo_prefeito', 'gestao_camara')),
  exercicio             integer     NOT NULL CHECK (exercicio BETWEEN 1990 AND 9999),
  responsavel           text        NOT NULL CHECK (btrim(responsavel) <> '' AND char_length(responsavel) <= 200),
  recebida_em           date        NOT NULL,
  processo_tce          text        CHECK (processo_tce IS NULL OR (btrim(processo_tce) <> '' AND char_length(processo_tce) <= 80)),
  parecer_previo        text        CHECK (parecer_previo IN ('favoravel', 'favoravel_com_ressalvas', 'desfavoravel')),
  proposicao_id         uuid,
  notificado_em         date,
  notificacao_meio      text        CHECK (notificacao_meio IS NULL OR (btrim(notificacao_meio) <> '' AND char_length(notificacao_meio) <= 200)),
  prazo_defesa_ate      date,
  defesa_juntada_em     timestamptz,
  prazo_julgamento_ate  date,
  resultado             text        CHECK (resultado IN ('parecer_mantido', 'parecer_rejeitado')),
  votacao_id            uuid,
  julgada_em            timestamptz,
  situacao_tce          text        CHECK (situacao_tce IS NULL OR char_length(situacao_tce) <= 500),
  criado_por            uuid,
  criado_em             timestamptz NOT NULL DEFAULT now(),
  atualizado_por        uuid,
  atualizado_em         timestamptz NOT NULL DEFAULT now(),
  lock_version          integer     NOT NULL DEFAULT 0,
  PRIMARY KEY (ente_id, id),
  UNIQUE (ente_id, tipo, exercicio),
  FOREIGN KEY (ente_id, proposicao_id) REFERENCES legislativo.proposicoes (ente_id, id),
  FOREIGN KEY (ente_id, votacao_id) REFERENCES legislativo.votacoes (ente_id, id),
  -- governo: parecer, PDL e prazo de julgamento desde o registro
  CONSTRAINT prestacao_governo_completa CHECK (
    tipo <> 'governo_prefeito'
    OR (parecer_previo IS NOT NULL AND proposicao_id IS NOT NULL AND prazo_julgamento_ate IS NOT NULL)),
  -- Mesa: so' acompanhamento — nada do rito de julgamento
  CONSTRAINT prestacao_mesa_so_acompanha CHECK (
    tipo <> 'gestao_camara'
    OR (proposicao_id IS NULL AND notificado_em IS NULL AND defesa_juntada_em IS NULL AND resultado IS NULL
        AND votacao_id IS NULL AND prazo_julgamento_ate IS NULL)),
  CONSTRAINT prestacao_notificacao_coerente CHECK (
    (notificado_em IS NULL) = (prazo_defesa_ate IS NULL) AND (notificado_em IS NULL) = (notificacao_meio IS NULL)),
  CONSTRAINT prestacao_julgamento_coerente CHECK ((resultado IS NULL) = (julgada_em IS NULL)),
  CONSTRAINT prestacao_votacao_so_com_resultado CHECK (votacao_id IS NULL OR resultado IS NOT NULL)
);
--;;
-- o PDL e' de UMA prestacao (o encerramento da votacao acha a prestacao pela proposicao)
CREATE UNIQUE INDEX IF NOT EXISTS uq_prestacao_contas_proposicao
  ON legislativo.prestacao_contas (ente_id, proposicao_id) WHERE proposicao_id IS NOT NULL;
--;;
CREATE TABLE IF NOT EXISTS legislativo.prestacao_contas_documento (
  ente_id        uuid        NOT NULL,
  id             uuid        NOT NULL DEFAULT gen_random_uuid(),
  prestacao_id   uuid        NOT NULL,
  tipo           text        NOT NULL CHECK (tipo IN ('parecer_previo', 'relatorio_tce', 'notificacao', 'defesa',
                                                     'decisao_tce', 'outro')),
  nome           text        NOT NULL CHECK (btrim(nome) <> '' AND char_length(nome) <= 200),
  tipo_midia     text        NOT NULL CHECK (char_length(tipo_midia) <= 200),
  tamanho_bytes  bigint      NOT NULL CHECK (tamanho_bytes > 0 AND tamanho_bytes <= 10485760),
  sha256         text        NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
  chave_objeto   text        NOT NULL,
  criado_por     uuid,
  criado_em      timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (ente_id, id),
  FOREIGN KEY (ente_id, prestacao_id) REFERENCES legislativo.prestacao_contas (ente_id, id),
  -- a convencao `<pasta>/<ente>/` — e' o que a exportacao e o apagamento da Casa (ADR-0018) encontram sozinhos
  CONSTRAINT prestacao_documento_chave_da_convencao
    CHECK (chave_objeto = 'contas/' || ente_id || '/' || prestacao_id || '/' || id)
);
--;;
CREATE INDEX IF NOT EXISTS idx_prestacao_documento ON legislativo.prestacao_contas_documento (ente_id, prestacao_id, criado_em);
--;;
CREATE TRIGGER trg_prestacao_contas_documento_append_only
  BEFORE UPDATE OR DELETE ON legislativo.prestacao_contas_documento
  FOR EACH ROW EXECUTE FUNCTION shared.imut_append_only();
--;;
CREATE TABLE IF NOT EXISTS legislativo.parametro_contas (
  ente_id                uuid        NOT NULL,
  prazo_defesa_dias      integer     NOT NULL DEFAULT 15 CHECK (prazo_defesa_dias BETWEEN 1 AND 120),
  prazo_julgamento_dias  integer     NOT NULL DEFAULT 60 CHECK (prazo_julgamento_dias BETWEEN 1 AND 365),
  atualizado_por         uuid,
  atualizado_em          timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (ente_id)
);
--;;
-- RLS nas tres tabelas da Casa (o mesmo desenho de `parametro_parecer_juridico`, mig 131)
DO $$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['prestacao_contas', 'prestacao_contas_documento', 'parametro_contas']
  LOOP
    EXECUTE format('ALTER TABLE legislativo.%I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE legislativo.%I FORCE ROW LEVEL SECURITY', t);
    EXECUTE format('DROP POLICY IF EXISTS tenant_isolation ON legislativo.%I', t);
    EXECUTE format(
      'CREATE POLICY tenant_isolation ON legislativo.%I
         USING (ente_id = NULLIF(current_setting(''app.ente_id'', true), '''')::uuid)
         WITH CHECK (ente_id = NULLIF(current_setting(''app.ente_id'', true), '''')::uuid)', t);
  END LOOP;
END $$;
--;;
GRANT SELECT, INSERT, UPDATE (processo_tce, situacao_tce, notificado_em, notificacao_meio, prazo_defesa_ate,
                              defesa_juntada_em, resultado, votacao_id, julgada_em, atualizado_por, atualizado_em,
                              lock_version)
  ON legislativo.prestacao_contas TO oplenario_app;
--;;
GRANT SELECT, INSERT ON legislativo.prestacao_contas_documento TO oplenario_app;
--;;
GRANT SELECT, INSERT, UPDATE (prazo_defesa_dias, prazo_julgamento_dias, atualizado_por, atualizado_em)
  ON legislativo.parametro_contas TO oplenario_app;
--;;
CREATE TABLE IF NOT EXISTS legislativo.regra_votacao_materia (
  chave       text NOT NULL PRIMARY KEY CHECK (chave ~ '^[a-z][a-z0-9_]{0,62}$'),
  guarda      text NOT NULL CHECK (btrim(guarda) <> ''),
  referencia  text NOT NULL CHECK (btrim(referencia) <> '')
);
--;;
GRANT SELECT ON legislativo.regra_votacao_materia TO oplenario_app;
--;;
-- CF art. 31 §2: o parecer previo do TCE so' deixa de prevalecer por decisao de 2/3 dos MEMBROS da Camara. A pergunta
-- votada e' "Rejeitar o parecer previo?" (sim = rejeitar): quorum qualificado de 2/3 sobre a composicao, em votacao
-- NOMINAL (cada vereador responde pelo voto que derruba ou mantem o parecer).
INSERT INTO legislativo.regra_votacao_materia (chave, guarda, referencia)
VALUES ('contas_prefeito',
        'votacao.quorum_tipo == "maioria_qualificada_2_3" e votacao.modalidade == "nominal"',
        'CF art. 31 §2')
ON CONFLICT (chave) DO NOTHING;
