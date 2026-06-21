-- runtime do motor de compliance (§22.7.7) + artefato de remessa (§22.7.8) — tabelas TENANT do schema 'compliance'.
-- O catalogo (template/regra, dominio SEM ente_id) e o binding por tenant vivem no 'motor' (§22.7.6); aqui mora o que e POR ENTE.
-- RLS / particao hash(ente_id) = politica global de tenancy (deferida, como o ledger de paineis). So ente_id NOT NULL aqui.

-- §22.7.7: obrigacao materializada com prazo (sabor DEADLINE-BOUND). Polimorfica (disc.6: proposicao_prazo_ativo -> prazo_dominio_ativo).
CREATE TABLE IF NOT EXISTS compliance.prazo_dominio_ativo (
  id              uuid PRIMARY KEY,
  ente_id         uuid NOT NULL,
  template_chave  text NOT NULL,                           -- ref ao catalogo do motor (dominio); cruza por guard, nunca FK cross-schema
  objeto_tipo     text NOT NULL,                           -- polimorfico: proposicao|ato_despesa|ato_legislativo|...
  objeto_id       uuid NOT NULL,                           -- o objeto de dominio sob prazo
  vence_em        date NOT NULL,                           -- relogio da instancia (S1: o motor MONITORA prazo, nao so avalia)
  prazo_fonte_ref text,                                    -- proveniencia do prazo de dominio (S3: Oficio Circular desliza)
  estado          text NOT NULL DEFAULT 'pendente',        -- ciclo enum FIXO em codigo: pendente|cumprida|vencida|dispensada|cancelada
  cumprida_em     timestamptz,
  criado_em       timestamptz NOT NULL DEFAULT now(),
  atualizado_em   timestamptz NOT NULL DEFAULT now(),
  UNIQUE (ente_id, template_chave, objeto_tipo, objeto_id) -- idempotencia da materializacao (a chave do loop de runtime)
);
-- sweep do runtime (§22.7.7): varre obrigacoes ABERTAS por ente/prazo. Index PARCIAL nao indexa as ja
-- encerradas (cumprida|dispensada|cancelada), que crescem sem limite mas nunca sao relidas no caminho quente.
CREATE INDEX IF NOT EXISTS idx_prazo_dominio_ativo_sweep
  ON compliance.prazo_dominio_ativo (ente_id, vence_em)
  WHERE estado IN ('pendente', 'vencida');

-- §22.7.7: compliance_avaliacao APPEND-ONLY = a prova de compliance (Invariante 10). Sem UPDATE/DELETE.
-- Sabor CONTINUO e veredito 'inaplicavel' NAO materializam obrigacao -> obrigacao_id NULL.
CREATE TABLE IF NOT EXISTS compliance.compliance_avaliacao (
  id                  uuid PRIMARY KEY,
  ente_id             uuid NOT NULL,
  obrigacao_id        uuid,                                -- ref logica p/ prazo_dominio_ativo.id; NULL p/ continua/inaplicavel
  template_chave      text NOT NULL,
  registry_versao_ref text NOT NULL,                       -- versao do catalogo carimbada na avaliacao (B3)
  veredito            text NOT NULL,                       -- conforme|nao_conforme|inaplicavel
  severidade          text NOT NULL DEFAULT 'aviso',       -- bloqueante|aviso (herdado do template)
  origem_avaliacao    text NOT NULL,                       -- evento|sweep|sob_demanda
  detalhe             text,
  avaliado_em         timestamptz NOT NULL DEFAULT now()   -- quando a avaliacao ocorreu (append-only; sem UPDATE)
);
-- dois acessos de leitura: historico de UMA obrigacao; e auditoria por template (regra continua / painel) numa
-- tabela append-only que cresce sem limite -> Seq Scan sem estes indices.
CREATE INDEX IF NOT EXISTS idx_compliance_avaliacao_obrigacao
  ON compliance.compliance_avaliacao (ente_id, obrigacao_id, avaliado_em);
CREATE INDEX IF NOT EXISTS idx_compliance_avaliacao_ente_template
  ON compliance.compliance_avaliacao (ente_id, template_chave, avaliado_em DESC);

-- §22.7.8: artefato de remessa. O ARTEFATO e imutavel por VERSAO (re-emissao = NOVA versao, nunca muta hash/ref);
-- o ESTADO de submissao evolui via UPDATE no ciclo (rascunho -> ... -> aceita|rejeitada). Binario no objeto_store; aqui so metadados + ponteiro.
-- Costura: remessa_enviada(ente, sistema, competencia) = EXISTS linha estado='aceita'. Rejeicao NAO cumpre a obrigacao
-- (Invariante 10: o registro do artefato/versao nao e apagado nem sobrescrito).
CREATE TABLE IF NOT EXISTS compliance.remessa_gerada (
  id                  uuid PRIMARY KEY,
  ente_id             uuid NOT NULL,
  template_chave      text NOT NULL,                       -- a obrigacao de remessa (ex.: remessa_mensal_sim)
  sistema             text NOT NULL,                       -- sistema de entrega do TCE (ex.: SIM) — chave da costura remessa_enviada
  competencia         text NOT NULL,                       -- "AAAA-MM" (comp_chave)
  versao              integer NOT NULL DEFAULT 1,          -- re-emissao = NOVA versao (nao muta a anterior)
  spec_layout_versao  text NOT NULL,                       -- versao do descritor declarativo de layout (dado, dec. 2b)
  registry_versao_ref text NOT NULL,                       -- versao do registry usado como fonte (B3)
  hash                text NOT NULL,                        -- hash do binario gerado (ex.: SHA-256) p/ integridade
  objeto_store_ref    text NOT NULL,                        -- ponteiro p/ o binario no objeto_store (nao mora no banco)
  estado              text NOT NULL DEFAULT 'rascunho',     -- ciclo enum FIXO: rascunho|validada|submetida|aceita|rejeitada
  submetida_em        timestamptz,
  resposta_em         timestamptz,                          -- quando o TCE respondeu (aceita|rejeitada)
  criado_em           timestamptz NOT NULL DEFAULT now(),
  UNIQUE (ente_id, template_chave, competencia, versao)     -- §22.7.8: re-emissao = nova versao
);
-- costura remessa_enviada: so a linha 'aceita' importa. Index PARCIAL numa tabela que acumula versoes/estados intermediarios.
CREATE INDEX IF NOT EXISTS idx_remessa_gerada_costura
  ON compliance.remessa_gerada (ente_id, sistema, competencia)
  WHERE estado = 'aceita';

-- [GAP]: o layout FISICO do SIM (campos, ordem, formato do arquivo) e conteudo regulatorio real — NAO inventado aqui.
