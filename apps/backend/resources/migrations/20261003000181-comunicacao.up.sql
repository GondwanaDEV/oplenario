-- ADR-0020 — o modulo COMUNICACAO (schema proprio, §22.10): os comunicados internos da Casa, escritos por uma pessoa,
-- com a lista de quem deveria le-los e a prova de que leram. Cinco tabelas, todas por Casa (RLS FORCE) e todas
-- APPEND-ONLY: o comunicado vale como registro de que a Casa COMUNICOU, e a ciencia como registro de que a pessoa
-- RECONHECEU (documento-mestre v1.42). Nenhum dos dois e' assinatura ICP-Brasil.
--
-- (1) `comunicado` — o protocolo (Eixo 5): numero proprio por Casa e ano, `COM-AAAA-NNNNNN` (o mesmo desenho dos
--     protocolos da participacao: `shared.sequencial` gapless, escopo `comunicado:<ano>`). IMUTAVEL depois de enviado,
--     garantido pelo banco (trigger + sem GRANT de UPDATE/DELETE). Corrigir = enviar outro que SUBSTITUI este
--     (`substitui_id`, uma vez so': quem substitui e' unico, a corrente segue pelo substituto). O nome de quem enviou
--     fica como era no envio. `ciencia_ate` (fatia 3) e' o DIA (civil, America/Fortaleza) ate' quando a ciencia e'
--     esperada; vencido e' calculado na leitura (sem agendador). `objeto_*` (fatia 2) e' o link opcional para uma
--     sessao, uma proposicao ou um protocolo — ref POLIMORFICA por valor, sem FK (outro modulo, §22.10).
-- (2) `destino` — COMO foi enderecado (a pessoa X, o setor Y, a comissao Z, todos os setores), com o nome do alvo como
--     era no envio. Ordem preservada (quem chega por dois caminhos fica com o primeiro).
-- (3) `destinatario` — a LISTA CONGELADA (Eixo 2): as pessoas que o enderecamento tinha NAQUELE instante. Quem entra no
--     setor depois nao recebe o antigo; quem sai continua devendo a ciencia do que recebeu.
-- (4) `marca` — recebido / lido / ciente (Eixo 4): a PRIMEIRA ocorrencia de cada uma, nunca sobrescrita (INSERT ...
--     ON CONFLICT DO NOTHING). So' destinatario tem marca (FK para a lista). Gravar `lido` grava `recebido` se faltar;
--     `ciente` grava os dois (a aplicacao faz; o CHECK abaixo garante que `ciente` so' existe em comunicado que pede).
-- (5) `anexo` (fatia 2) — o arquivo vai ao object storage sob `comunicados/<ente>/<comunicado>/<id>` (a convencao
--     `<pasta>/<ente>/` que a exportacao e o apagamento da Casa descobrem sozinhos, ADR-0018); aqui fica o nome, o tipo,
--     o tamanho e o sha256. Ate' 5 por comunicado, de ate' 10 MB, so' nos 10 minutos depois do envio (a aplicacao
--     confere a janela e serializa os envios com uma trava consultiva; o tamanho tambem e' CHECK).
CREATE SCHEMA IF NOT EXISTS comunicacao;
--;;
GRANT USAGE ON SCHEMA comunicacao TO oplenario_app;
--;;
CREATE TABLE IF NOT EXISTS comunicacao.comunicado (
  ente_id                  uuid        NOT NULL,
  id                       uuid        NOT NULL DEFAULT gen_random_uuid(),
  ano                      integer     NOT NULL CHECK (ano BETWEEN 2000 AND 9999),
  numero                   integer     NOT NULL CHECK (numero > 0),
  protocolo                text        NOT NULL CHECK (protocolo ~ '^COM-[0-9]{4}-[0-9]{6,}$'),
  remetente_identidade_id  uuid        NOT NULL,          -- GUARD ref (identidade), sem FK cross-schema
  remetente_nome           text        NOT NULL,
  assunto                  text        NOT NULL CHECK (btrim(assunto) <> '' AND char_length(assunto) <= 200),
  corpo                    text        NOT NULL CHECK (btrim(corpo) <> '' AND char_length(corpo) <= 20000),
  exige_ciencia            boolean     NOT NULL DEFAULT false,
  ciencia_ate              date,
  substitui_id             uuid,
  objeto_tipo              text        CHECK (objeto_tipo IN ('sessao', 'proposicao', 'protocolo')),
  objeto_id                uuid,
  enviado_em               timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (ente_id, id),
  UNIQUE (ente_id, protocolo),
  UNIQUE (ente_id, ano, numero),
  FOREIGN KEY (ente_id, substitui_id) REFERENCES comunicacao.comunicado (ente_id, id),
  CONSTRAINT comunicado_prazo_exige_ciencia CHECK (ciencia_ate IS NULL OR exige_ciencia),
  CONSTRAINT comunicado_objeto_coerente CHECK ((objeto_tipo IS NULL) = (objeto_id IS NULL)),
  CONSTRAINT comunicado_nao_substitui_a_si CHECK (substitui_id IS NULL OR substitui_id <> id)
);
--;;
-- um comunicado e' substituido UMA vez (o "substituido por COM-…" e' um so'; a correcao da correcao substitui o substituto)
CREATE UNIQUE INDEX IF NOT EXISTS uq_comunicado_substituido_uma_vez
  ON comunicacao.comunicado (ente_id, substitui_id) WHERE substitui_id IS NOT NULL;
--;;
-- "os que eu enviei" e "os da Casa", mais recentes primeiro
CREATE INDEX IF NOT EXISTS idx_comunicado_remetente
  ON comunicacao.comunicado (ente_id, remetente_identidade_id, enviado_em DESC);
--;;
CREATE INDEX IF NOT EXISTS idx_comunicado_enviado_em ON comunicacao.comunicado (ente_id, enviado_em DESC);
--;;
CREATE TABLE IF NOT EXISTS comunicacao.destino (
  ente_id        uuid    NOT NULL,
  comunicado_id  uuid    NOT NULL,
  ordem          integer NOT NULL CHECK (ordem >= 0),
  tipo           text    NOT NULL CHECK (tipo IN ('pessoa', 'vereador', 'setor', 'comissao', 'todos')),
  alvo_id        uuid,
  alvo_nome      text    NOT NULL,
  PRIMARY KEY (ente_id, comunicado_id, ordem),
  FOREIGN KEY (ente_id, comunicado_id) REFERENCES comunicacao.comunicado (ente_id, id),
  CONSTRAINT destino_alvo_coerente CHECK ((tipo = 'todos') = (alvo_id IS NULL))
);
--;;
CREATE TABLE IF NOT EXISTS comunicacao.destinatario (
  ente_id        uuid NOT NULL,
  comunicado_id  uuid NOT NULL,
  identidade_id  uuid NOT NULL,                          -- GUARD ref (identidade)
  nome           text NOT NULL,
  via            text NOT NULL,                          -- "direto", "setor Jurídico", "Comissão de Finanças"…
  PRIMARY KEY (ente_id, comunicado_id, identidade_id),
  FOREIGN KEY (ente_id, comunicado_id) REFERENCES comunicacao.comunicado (ente_id, id)
);
--;;
-- a CAIXA da pessoa: os comunicados em que ela e' destinataria
CREATE INDEX IF NOT EXISTS idx_destinatario_pessoa ON comunicacao.destinatario (ente_id, identidade_id);
--;;
CREATE TABLE IF NOT EXISTS comunicacao.marca (
  ente_id        uuid        NOT NULL,
  comunicado_id  uuid        NOT NULL,
  identidade_id  uuid        NOT NULL,
  tipo           text        NOT NULL CHECK (tipo IN ('recebido', 'lido', 'ciente')),
  em             timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (ente_id, comunicado_id, identidade_id, tipo),
  FOREIGN KEY (ente_id, comunicado_id, identidade_id)
    REFERENCES comunicacao.destinatario (ente_id, comunicado_id, identidade_id)
);
--;;
CREATE INDEX IF NOT EXISTS idx_marca_pessoa ON comunicacao.marca (ente_id, identidade_id, tipo);
--;;
-- `ciente` so' existe em comunicado que pede ciencia (Eixo 4): a prova nao nasce onde ninguem a pediu. Trigger (e nao
-- FK/CHECK) porque o dado esta' em outra tabela; roda como o dono so' para LER o comunicado (a RLS do chamador vale).
CREATE OR REPLACE FUNCTION comunicacao.ciente_exige_ciencia() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.tipo = 'ciente' AND NOT EXISTS (
       SELECT 1 FROM comunicacao.comunicado c
        WHERE c.ente_id = NEW.ente_id AND c.id = NEW.comunicado_id AND c.exige_ciencia) THEN
    RAISE EXCEPTION 'comunicacao.marca: ciente em comunicado que nao pede ciencia' USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END;
$$;
--;;
CREATE TRIGGER trg_marca_ciente_exige_ciencia
  BEFORE INSERT ON comunicacao.marca
  FOR EACH ROW EXECUTE FUNCTION comunicacao.ciente_exige_ciencia();
--;;
CREATE TABLE IF NOT EXISTS comunicacao.anexo (
  ente_id        uuid        NOT NULL,
  id             uuid        NOT NULL DEFAULT gen_random_uuid(),
  comunicado_id  uuid        NOT NULL,
  nome           text        NOT NULL CHECK (btrim(nome) <> '' AND char_length(nome) <= 200),
  tipo_midia     text        NOT NULL CHECK (char_length(tipo_midia) <= 200),
  bytes          bigint      NOT NULL CHECK (bytes > 0 AND bytes <= 10485760),
  sha256         text        NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
  chave_objeto   text        NOT NULL,
  enviado_em     timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (ente_id, id),
  FOREIGN KEY (ente_id, comunicado_id) REFERENCES comunicacao.comunicado (ente_id, id),
  -- a chave segue a convencao `<pasta>/<ente>/` — e' o que o apagamento da Casa (ADR-0018) encontra sozinho
  CONSTRAINT anexo_chave_da_convencao
    CHECK (chave_objeto = 'comunicados/' || ente_id || '/' || comunicado_id || '/' || id)
);
--;;
CREATE INDEX IF NOT EXISTS idx_anexo_comunicado ON comunicacao.anexo (ente_id, comunicado_id);
--;;
-- append-only nas cinco: UPDATE/DELETE recusados pelo banco (alem de nao haver GRANT), mesmo para quem tiver o GRANT
DO $$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['comunicado', 'destino', 'destinatario', 'marca', 'anexo']
  LOOP
    EXECUTE format('CREATE TRIGGER trg_%s_append_only BEFORE UPDATE OR DELETE ON comunicacao.%I
                      FOR EACH ROW EXECUTE FUNCTION shared.imut_append_only()', t, t);
    EXECUTE format('ALTER TABLE comunicacao.%I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE comunicacao.%I FORCE ROW LEVEL SECURITY', t);
    EXECUTE format('DROP POLICY IF EXISTS tenant_isolation ON comunicacao.%I', t);
    EXECUTE format(
      'CREATE POLICY tenant_isolation ON comunicacao.%I
         USING (ente_id = NULLIF(current_setting(''app.ente_id'', true), '''')::uuid)
         WITH CHECK (ente_id = NULLIF(current_setting(''app.ente_id'', true), '''')::uuid)', t);
    EXECUTE format('GRANT SELECT, INSERT ON comunicacao.%I TO oplenario_app', t);
  END LOOP;
END $$;
