-- Catalogo estatico do motor de regras (§22.7.6 Eixo B) — a "forma" do motor: definicao da regra,
-- binding por tenant, registry/versao e calendarios. O RUNTIME (obrigacao/avaliacao/remessa, §22.7.7-8)
-- mora no schema 'compliance' (migration …0005). Aqui mora o que o motor LE para avaliar.
--
-- Por que schema 'motor' e nao 'compliance' (reconciliacao vs docs/06, escrito antes da §22.10):
--  - B1 (definicao) + B2 (binding) CO-LOCALIZAM por OBRIGACAO, nao conveniencia: compliance_regra_tenant
--    tem FK REAL versao_fixada_id -> template_compliance(id) (integridade de catalogo) E a resolucao
--    "regras aplicaveis a um ente" junta as duas. §22.10 proibe FK/JOIN cross-schema -> mesmo schema.
--  - B3 (registry_versao) + B4 (prazo/calendario) sao infra do catalogo/builtins do motor (lidas pelos
--    builtins prazo_vigente/proximo_dia_util; registry_versao dirige a re-validacao). Dono = o motor.
-- §22.10 l.1265: "catalogo versionado no motor". Logo as 5 tabelas vivem no schema 'motor'. (docs/06 dizia
-- "Modulo: compliance" PRE-§22.10; a reconciliacao no doc-mestre §22.7.6 segue sob "Confirma?".)
CREATE SCHEMA IF NOT EXISTS motor;

-- ===========================================================================
-- B1 (§22.7.6): template_compliance — a DEFINICAO da regra. Tabela de DOMINIO, SEM ente_id
-- (regra federal/tce_estadual e lei uniforme central; copia-la por ~1.500 entes seria insustentavel — S2).
-- Versionada por COPIA INTEGRAL (§22.4 eixo C): mudar a regra = nova versao, a anterior vira 'superada'.
-- ===========================================================================
CREATE TABLE IF NOT EXISTS motor.template_compliance (
  id                     uuid PRIMARY KEY,
  chave_template         text NOT NULL,                                  -- id logico estavel (ex.: "remessa_mensal_sim"), constante entre versoes
  versao                 integer NOT NULL,                               -- inteiro crescente por chave_template
  template_pai_id        uuid REFERENCES motor.template_compliance(id),  -- proveniencia da copia (mesmo schema -> FK ok); NAO governanca ativa
  dominio                text NOT NULL,                                  -- camada S2: federal|tce_estadual|regimento_tenant
  chave_dominio          text,                                           -- escopo de compartilhamento: NULL p/ federal; cod. TCE/UF p/ tce_estadual
  descricao              text NOT NULL,
  severidade             text NOT NULL,                                  -- bloqueante|aviso (enum em codigo; valor gerado pelo editor interno)
  referencia_normativa   text NOT NULL,
  fonte_yaml             text NOT NULL,                                  -- o envelope como escrito — auditavel/diffavel ("regra e dado", Inv. 4)
  forma_compilada        jsonb NOT NULL,                                 -- AST normalizado/tipado pos type-check — o que o runtime avalia
  assinatura_parametros  jsonb NOT NULL,                                 -- parametros formais tipados (ex.: {"despesa":"AtoDespesa"})
  registry_versao_ref    text NOT NULL,                                  -- versao do registry contra a qual a regra foi tipada (dec.2) -> dirige re-validacao
  estado_versao          text NOT NULL,                                  -- vigente|superada|arquivada (ponteiro de vigencia, §22.4.3 disc.3)
  -- transversais (§22.4.3 disc.1) — SEM ente_id, SEM deleted_at
  criado_em              timestamptz NOT NULL DEFAULT now(),
  criado_por             uuid,
  atualizado_em          timestamptz NOT NULL DEFAULT now(),
  atualizado_por         uuid,
  lock_version           integer NOT NULL DEFAULT 0,
  origem                 text NOT NULL DEFAULT 'nativo',                 -- nativo|migracao|importacao_legado
  origem_ref             text,
  origem_importado_em    timestamptz,
  UNIQUE (chave_template, versao)
);
-- resolucao "quais regras deste regime": index por (dominio, chave_dominio). NAO comeca por ente_id
-- — e tabela de DOMINIO (excecao consciente a §22.2, justificada por S2 — §22.7.6 disciplina derivada).
CREATE INDEX IF NOT EXISTS idx_template_compliance_dominio
  ON motor.template_compliance (dominio, chave_dominio)
  WHERE estado_versao = 'vigente';

-- ===========================================================================
-- B2 (§22.7.6): compliance_regra_tenant — o BINDING. Tabela de TENANT, COM ente_id.
-- Resolucao POR ESCOPO (nao linha-por-tenant): federal/tce_estadual aplicam por jurisdicao (UF->TCE);
-- binding so materializa parametro do tenant, opt-out auditado, ou pin de versao (raro). regimento_tenant
-- EXIGE binding p/ ativar (3a camada S2). ente_id cruza por guard p/ admin_sistema.ente (sem FK cross-schema).
-- UNICA tabela TENANT no schema 'motor' (as outras 4 sao dominio): RLS + particao hash(ente_id) = politica
-- global de tenancy (deferida, como na …0005); isolamento por filtro de query + guard ate la (§22.10 l.1276).
-- ===========================================================================
CREATE TABLE IF NOT EXISTS motor.compliance_regra_tenant (
  id                  uuid PRIMARY KEY,
  ente_id             uuid NOT NULL,                                      -- guard cross-schema -> admin_sistema.ente (§22.10); sem FK
  template_chave      text NOT NULL,                                      -- chave_template logica (nao a versao)
  versao_fixada_id    uuid REFERENCES motor.template_compliance(id),      -- NULL = segue a vigente; preenchido = pin auditado (mesmo schema -> FK ok)
  ativa               boolean NOT NULL DEFAULT true,                      -- opt-out justificado
  motivo_desativacao  text,                                              -- obrigatorio quando ativa=false (invariante de INPUT do editor -> CHECK)
  parametros_tenant   jsonb NOT NULL DEFAULT '{}',                        -- valores de parametro_tenant (ex.: {"prazo_publicacao_ato_dias": 5})
  criado_em           timestamptz NOT NULL DEFAULT now(),
  criado_por          uuid,
  atualizado_em       timestamptz NOT NULL DEFAULT now(),
  atualizado_por      uuid,
  lock_version        integer NOT NULL DEFAULT 0,
  origem              text NOT NULL DEFAULT 'nativo',
  origem_ref          text,
  origem_importado_em timestamptz,
  -- CHECK mantido (diferente dos enums adiados na 0005): aqui o valor e INPUT do editor interno, nao
  -- gerado pelo motor — opt-out sem motivo e dado corrompido; docs/06 §4 especifica a invariante.
  CONSTRAINT motivo_quando_inativa CHECK (ativa OR motivo_desativacao IS NOT NULL),
  UNIQUE (ente_id, template_chave)                                       -- index composto comeca por ente_id (§22.2)
);
-- sweep de re-validacao INVERSO: dado um template (versao superada / bump de registry), achar os bindings
-- ativos que precisam re-validar (parcial em 'ativa' — opt-out inativo nao re-valida).
CREATE INDEX IF NOT EXISTS idx_compliance_regra_tenant_template
  ON motor.compliance_regra_tenant (template_chave)
  WHERE ativa;

-- ===========================================================================
-- B4 (§22.7.6): prazo_dominio_vigente — REFERENCIA regulatoria de prazo (NAO obrigacao de runtime;
-- o _ativo de runtime mora em compliance, §22.7.7). Override por Oficio Circular (S3 "prazo deslizante"):
-- append-only + flag 'vigente'. Lido pelo builtin prazo_vigente(dominio, tipo, competencia). DOMINIO, sem ente_id.
-- ===========================================================================
CREATE TABLE IF NOT EXISTS motor.prazo_dominio_vigente (
  id             uuid PRIMARY KEY,
  dominio        text NOT NULL,                                          -- federal|tce_estadual
  chave_dominio  text,                                                   -- jurisdicao (ex.: 'TCE-CE')
  tipo_prazo     text NOT NULL,                                          -- ex.: 'SIM_mensal', 'PCS_anual'
  chave_periodo  text NOT NULL,                                          -- competencia/exercicio (ex.: '2026-05')
  data_limite    date NOT NULL,                                          -- o prazo
  fonte          text NOT NULL,                                          -- norma-base OU circular que deslizou (ex.: 'IN 04/2019', 'OC 16/2026')
  vigente        boolean NOT NULL,                                       -- a circular mais recente vence; so UMA vigente por (dominio,chave_dominio,tipo,periodo)
  criado_em      timestamptz NOT NULL DEFAULT now(),
  criado_por     uuid,
  origem         text NOT NULL DEFAULT 'nativo'
);
-- UNIQUE via INDICE com COALESCE: chave_dominio e NULL p/ federal, e no Postgres NULL != NULL faria a
-- constraint inline DEIXAR PASSAR duplicatas federais. Sentinela '' nunca colide com jurisdicao real.
-- (a) cada fonte e uma linha (append-only do deslize por Oficio Circular):
CREATE UNIQUE INDEX IF NOT EXISTS idx_prazo_dominio_vigente_fonte
  ON motor.prazo_dominio_vigente (dominio, COALESCE(chave_dominio, ''), tipo_prazo, chave_periodo, fonte);
-- (b) exatamente UMA vigente por (dominio,chave_dominio,tipo_prazo,chave_periodo) — invariante do "deslizante":
CREATE UNIQUE INDEX IF NOT EXISTS idx_prazo_dominio_vigente_unico
  ON motor.prazo_dominio_vigente (dominio, COALESCE(chave_dominio, ''), tipo_prazo, chave_periodo)
  WHERE vigente;

-- ===========================================================================
-- B4 (§22.7.6): calendario_feriado — feriados nacional + municipal, lidos por proximo_dia_util/soma_dias_uteis.
-- DOMINIO, sem ente_id. municipio_id cruza por guard p/ cadastros.municipios (sem FK cross-schema). Moveis ja resolvidas na carga.
-- ===========================================================================
CREATE TABLE IF NOT EXISTS motor.calendario_feriado (
  id            uuid PRIMARY KEY,
  jurisdicao    text NOT NULL,                                           -- nacional|municipal
  municipio_id  uuid,                                                    -- NULL p/ nacional; guard cross-schema p/ cadastros.municipios (municipal)
  data          date NOT NULL,                                           -- data resolvida no ano (moveis ja calculadas)
  descricao     text NOT NULL,
  origem        text NOT NULL DEFAULT 'nativo',
  origem_ref    text,
  criado_em     timestamptz NOT NULL DEFAULT now()
);
-- UNIQUE via INDICE com COALESCE: municipio_id e NULL p/ feriado nacional, e NULL != NULL deixaria passar
-- dois nacionais na mesma data. Sentinela nil-uuid nunca colide com municipio real. Serve TAMBEM de index
-- de lookup (proximo_dia_util/soma_dias_uteis) -> dispensa um index separado redundante.
CREATE UNIQUE INDEX IF NOT EXISTS idx_calendario_feriado_unico
  ON motor.calendario_feriado (jurisdicao, COALESCE(municipio_id, '00000000-0000-0000-0000-000000000000'::uuid), data);

-- ===========================================================================
-- B3 (§22.7.6): registry_catalogo_versao — log de versao do catalogo (tipos/enums/registros/builtins/
-- funcoes de relacao = declarados EM CODIGO, nao tabela). Da referente ao registry_versao_ref de B1 e
-- dirige o passe de re-validacao no deploy quando uma assinatura muda. DOMINIO, sem ente_id.
-- ===========================================================================
CREATE TABLE IF NOT EXISTS motor.registry_catalogo_versao (
  id          uuid PRIMARY KEY,
  versao      text NOT NULL,                                             -- a chave carimbada (ex.: "registry-v1@2026-06-20" = oplenario.motor.catalogo/CATALOGO-VERSAO)
  hash        text,                                                      -- hash/changeset do conteudo do catalogo (integridade da re-validacao)
  descricao   text,
  criado_em   timestamptz NOT NULL DEFAULT now(),
  UNIQUE (versao)
);

-- [GAP]: o CONTEUDO de calendario_feriado e prazo_dominio_vigente (datas/prazos reais das INs do TCE-CE,
-- feriados municipais) e conteudo regulatorio real — populado com o especialista em regimento, NAO inventado aqui.
-- A FORMA das tabelas nao depende do valor (§22.7.6).
