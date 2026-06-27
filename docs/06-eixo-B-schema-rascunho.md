# §22.7 — Eixo B (schema das tabelas de template/regra) · RASCUNHO DE TRABALHO

> **Status: ✅ CONSOLIDADO em §22.7.6 do documento-mestre (v1.11, 20/06/2026).** Artefato de
> trabalho do Eixo B, no mesmo papel que `docs/02` teve para o Eixo A e `docs/05` para o Eixo C.
> Materializou em **schema real** o vocabulário que o Eixo C validou (§22.7.5). As decisões e a
> DDL viraram texto canônico em **§22.7.6**; este rascunho fica como registro de origem.
>
> **Pré-requisito cumprido:** forma A2 validada e vocabulário derivado de carga real (§22.7.5).
> **Próximo:** +5 eixos (comportamento de runtime do motor, elevado por S1) + avaliador executável
> da DSL (primeira implementação de fato).

---

## 0. Fronteira de escopo do Eixo B (decidida)

**Decisão (tomada, não perguntada — fork de escopo resolvível com contexto):** o Eixo B é o
schema **estático** do motor. **Não** inclui as tabelas de *runtime* (instâncias de obrigação,
resultados de avaliação, rastreamento de vencimento de prazo).

**Por quê:** o achado **S1** (§22.7.5) já roteou o *comportamento* do motor — "monitora prazo,
não só avalia booleano" — para um dos "+5 eixos" (comportamento temporal/auditoria). O type-check
no save time e a **forma** das tabelas de template/regra são independentes de *como* o motor
agenda/avalia. Misturar agora reabriria decisão de motor que ainda nem tem eixo próprio. **Enxuto
agora, motor depois.** A única ponte deixada explícita: cada definição de regra carrega o que o
eixo de runtime vai precisar ler (forma compilada, assinatura, severidade, prazo).

---

## 1. Decomposição do Eixo B (4 sub-eixos)

| Sub-eixo | Tema | Estado |
|---|---|---|
| **B1** | `template_compliance` — a **definição** da regra (envelope + expressão + forma compilada + versão) | 🎯 **em design (este arquivo §3)** |
| **B2** | **Binding por tenant + camadas de domínio (S2)** — como uma definição se liga a entes e carrega `parametro_tenant` | ✅ desenhado (§4) |
| **B3** | **Registry de funções de relação + tipos** — o que o type-checker do save time lê (infra compartilhada, não só compliance) | ✅ desenhado (§5) |
| **B4** | **Prazo como dado / calendários (S3)** — `calendario_feriado` + `prazo_dominio_vigente` (override por Ofício Circular) | ✅ desenhado (§6) |

**Ordem proposta:** B1 (fundação que os outros referenciam) → B2 (binding) → B4 (calendários, que
o `prazo` da regra lê) → B3 (registry, que o type-checker lê; é o mais transversal e o que mais
depende de reconciliar a "mecânica fina do registry" ainda aberta em §22.7.4).

---

## 2. O NÓ CENTRAL do Eixo B — definição (domínio) vs. binding (tenant)

O achado **S2** (`dominio` é taxonomia em camadas: `federal` | `tce_estadual` | `regimento_tenant`)
colide com o padrão de §22.4 eixo C ("override por câmara via **cópia integral**"). A colisão e a
resolução:

- **Em §22.4 (tramitação):** um template de workflow é legitimamente **customizável por câmara**;
  override = cópia integral por ente. Faz sentido: cada casa tem seu fluxo.
- **Em compliance:** uma regra `federal` (LC 131, LAI) ou `tce_estadual` (IN do TCE-CE) é **lei
  uniforme**, mantida **central** pelo editor interno. **Não** é customização por câmara. Copiá-la
  por tenant (≈1.500 entes) seria absurdo de manutenção — corrigir uma regra federal viraria UPDATE
  em 1.500 linhas. Só a camada `regimento_tenant` varia por casa, e mesmo aí o que varia é um
  **parâmetro** (prazo de publicação), não a expressão.

**Decisão:** **separar a definição da regra (dado de domínio, compartilhado, SEM `ente_id`) do
binding por tenant (config, COM `ente_id`).** Versão da *definição* segue por **cópia integral**
(§22.4 eixo C honrado); o *binding* aponta para uma versão da definição e carrega só
on/off + parâmetros do tenant.

**É uma divergência consciente de §22.4**, justificada por S2. Honra a disciplina de consistência
(§4 CLAUDE.md) onde ela cabe (cópia integral, campos transversais, imutabilidade) e diverge só onde
o domínio genuinamente difere (escopo de compartilhamento). ⚑ **Decidido e sinalizado** (não
perguntado — resolvível com o contexto do produto). Fica registrado em destaque porque divergir de
um padrão estabelecido, mesmo justificado, é o que a metodologia manda sinalizar; revisável se o
Daouda Traore discordar na revisão da consolidação.

Consequência direta: a tabela de **definição** (B1) é tabela de **domínio** (como `municipios`) e
**não carrega `ente_id`**; a tabela de **binding** (B2) é tabela de **tenant** e carrega `ente_id`
(com índice composto começando por `ente_id`, §22.2). O "onde mora o `ente_id`" se resolve pela
própria separação B1/B2.

---

## 3. B1 — `template_compliance` (a definição da regra)

DDL proposta (Postgres-flavored, ilustrativa — tipos concretos e geração de id ficam p/ chat de
stack, §22.4.4; a **modelagem** é o que se decide aqui):

```sql
-- Módulo: compliance. Tabela de DOMÍNIO (não de tenant) → SEM ente_id.
-- Versionada por cópia integral (§22.4 eixo C). Editor interno na V1.
CREATE TABLE template_compliance (
  id                     uuid PRIMARY KEY,        -- PK interna UUID (§22.4 eixo H)

  -- identidade lógica estável + versão (cópia integral)
  chave_template         text NOT NULL,           -- id estável da regra (ex.: "remessa_mensal_sim"); constante entre versões
  versao                 int  NOT NULL,           -- inteiro crescente por chave_template
  template_pai_id        uuid REFERENCES template_compliance(id),  -- proveniência da cópia, NÃO governança ativa

  -- camada de domínio (S2)
  dominio                text NOT NULL,           -- 'federal' | 'tce_estadual' | 'regimento_tenant'
  chave_dominio          text,                    -- escopo de compartilhamento: NULL p/ federal;
                                                  -- cód. do TCE/UF p/ tce_estadual; NULL p/ regimento_tenant (resolve no binding B2)

  -- envelope de compliance (a regra COMO DADO — §22.7.5)
  descricao              text NOT NULL,
  severidade             text NOT NULL,           -- 'bloqueante' | 'aviso' (referente real: LRF 73-C — §22.7.5)
  referencia_normativa   text NOT NULL,

  -- a expressão (núcleo A2): FONTE auditável + FORMA COMPILADA tipada
  fonte_yaml             text  NOT NULL,          -- o envelope YAML como escrito — reproduzível, auditável, diffável
  forma_compilada        jsonb NOT NULL,          -- AST normalizado/tipado pós type-check — o que o motor de runtime avalia
  assinatura_parametros  jsonb NOT NULL,          -- parâmetros formais tipados (ex.: {"despesa":"AtoDespesa"})

  -- contrato do save-time type-check (Eixo A dec. 2: regra mal-tipada NÃO persiste como ativa)
  registry_versao_ref    text NOT NULL,           -- contra qual versão do registry (B3) a regra foi tipada
                                                  -- → permite re-validar se o registry mudar (mecânica fina do registry, §22.7.4)

  -- lifecycle (qual versão está vigente) — espelha estado_versao de §22.4.3 disc. 3
  estado_versao          text NOT NULL,           -- 'vigente' | 'superada' | 'arquivada'

  -- transversais (§22.4.3 disc. 1) — SEM ente_id, SEM deleted_at
  created_at             timestamptz NOT NULL,
  created_by             uuid,
  updated_at             timestamptz NOT NULL,
  updated_by             uuid,
  lock_version           int NOT NULL DEFAULT 0,
  origem                 text NOT NULL,           -- 'nativo' | 'migracao' | 'importacao_legado'
  origem_ref             text,
  origem_importado_em    timestamptz,

  UNIQUE (chave_template, versao)
);
-- índice por (dominio, chave_dominio) p/ resolver "quais regras deste regime"; NÃO começa por ente_id
-- (não é tabela de tenant — exceção consciente à disciplina §22.2, ver §2).
```

### Decisões embutidas no B1 (todas resolvíveis com contexto — tomadas, sujeitas a correção)

1. **Fonte + forma compilada lado a lado.** `fonte_yaml` (auditável/diffável, honra "regra é dado")
   + `forma_compilada` (jsonb, o AST tipado que o motor avalia). Não se reparseia YAML em runtime;
   não se perde a fonte humana. O threshold inline/URI de §22.4.3 disc. 3 **não se aplica** — regra
   é pequena, cabe inline sempre.
2. **Type-check no save grava prova, não só passa/falha.** `registry_versao_ref` registra contra
   qual versão do registry a regra foi tipada. Se o registry mudar assinatura, dá para detectar
   regras que precisam re-validar — fecha a ponta da "mecânica fina do registry" parqueada em
   §22.7.4 **sem** reabri-la agora. Regra `invalida` não chega a virar `vigente` (dec. 2): o estado
   inválido é rejeitado no editor, não persistido como ativo.
3. **Imutabilidade: conteúdo append-only + ponteiro de vigência** (§22.4.3 disc. 3+4). A expressão
   de uma versão, uma vez `vigente`, **não muta** — mudar a regra = nova versão (cópia integral,
   `versao`+1, `template_pai_id` aponta a anterior, anterior vira `superada`). Mesma mecânica do
   `proposicao_texto_versao`/`texto_vigente_versao_id`.
4. **`chave_dominio` carrega o escopo de compartilhamento (S2).** É o que evita a explosão por
   tenant: `federal` vale para todos; `tce_estadual` + `chave_dominio="TCE-CE"` vale para os entes
   sob aquele TCE; `regimento_tenant` é genérica e só ganha valor no binding (B2).
5. **`numero` de ato NÃO entra aqui** — reclassificação (§22.7.5): é invariante de integridade
   (`UNIQUE(tipo,numero,ano,camara_id)`) no schema legislativo (§22.4), não regra do motor.

### Pontas abertas do B1 (rastrear, não bloqueiam)

- Precedência exata de operadores e gramática literal do núcleo A2 → vêm com B3 (registry/tipos) e
  com a transcrição residual da sessão de origem (§22.7.4: mecânica fina do registry, formas
  descartadas). Não bloqueiam a forma da tabela.
- `estado_versao` inclui `rascunho`? Provável que o **editor interno** tenha rascunho fora desta
  tabela (ou com flag), já que dec. 2 diz que o que persiste como ativo é type-checked. Decidir em
  B2/B3 junto do fluxo de edição.

---

## 4. B2 — binding por tenant + camadas de domínio (desenhado)

Tabela de **tenant** (COM `ente_id`, índice composto começa por `ente_id` — §22.2). Liga um ente a
uma regra de domínio e carrega só o que varia por casa: param do tenant + on/off + (raro) pin de
versão. **Não copia a definição.**

```sql
-- Módulo: compliance. Tabela de TENANT → COM ente_id.
CREATE TABLE compliance_regra_tenant (
  id                  uuid PRIMARY KEY,
  ente_id             uuid NOT NULL REFERENCES entes(id),
  template_chave      text NOT NULL,        -- chave_template lógica (não a versão específica)
  versao_fixada_id    uuid REFERENCES template_compliance(id),  -- NULL = segue a vigente; preenchido = pin auditado (raro)
  ativa               boolean NOT NULL DEFAULT true,            -- opt-out justificado
  motivo_desativacao  text,                  -- CHECK: obrigatório quando ativa = false
  parametros_tenant   jsonb NOT NULL DEFAULT '{}',              -- valores de parametro_tenant (ex.: {"prazo_publicacao_ato_dias": 5})
  -- transversais + ente_id (§22.4.3 disc. 1 / §22.2)
  created_at timestamptz NOT NULL, created_by uuid, updated_at timestamptz NOT NULL, updated_by uuid,
  lock_version int NOT NULL DEFAULT 0, origem text NOT NULL, origem_ref text, origem_importado_em timestamptz,
  UNIQUE (ente_id, template_chave)
);
-- índice composto começa por ente_id (§22.2)
```

### Semântica de resolução (a decisão de fato de B2)

O conjunto de regras aplicáveis a um ente resolve **por escopo**, não por linha-por-tenant:

> `aplicáveis(ente)` = (todas `federal` vigentes) ∪ (todas `tce_estadual` vigentes cuja jurisdição
> casa com a UF do ente) ∪ (as `regimento_tenant` que o ente **ativou** via binding) — **menos** as
> que tenham binding com `ativa = false`.

Decisões embutidas (tomadas):
1. **`federal`/`tce_estadual` aplicam por default ao escopo — sem linha de binding.** Binding só
   existe para: (a) valores de `parametro_tenant`; (b) opt-out auditado (`ativa=false`+motivo);
   (c) pin de versão (raro). Isso mantém Invariante 4 limpo: a regra é dado central; o binding é a
   camada fina de config. Evita 1.500 linhas por regra federal.
2. **`regimento_tenant` exige binding para ativar** — é por-casa por natureza; sem binding, não
   aplica. É aqui que mora a 3ª camada de S2 e o `parametro_tenant`.
3. **Resolução ente→jurisdição via UF** (`entes → municipios.uf`). `chave_dominio` da regra
   `tce_estadual` = código da jurisdição (ex.: `"TCE-CE"`); na V1 a UF resolve direto (CE→TCE-CE,
   pós-fusão TCM-CE 2017). Estados com TCM separado por capital entram depois via tabela de exceção
   de jurisdição — **não** muda a forma. (Invariante 4: outros TCEs = dado.)

## 5. B3 — registry de funções de relação + tipos (desenhado)

**Achado de modelagem:** o registry **não é tabela de tenant** — é **catálogo de infra
compartilhada** pelos 4 usos da DSL (tramitação, autorização, plenário, compliance). Mora no módulo
**core/compartilhado**, não no de compliance (§22.2: schema/prefixo por módulo). Decisões:

1. **Assinaturas declaradas em código, no contexto dono, agregadas num catálogo versionado.** Uma
   função de relação (`populacao(ente)`, `publicado(ato)`…) **tem implementação** no bounded context
   dono (Eixo A dec. 3: ownership distribuído). A **assinatura** (nome, params tipados, retorno) é
   declarada **junto da implementação** — co-locação evita drift — e **publicada** num catálogo
   central que o type-checker lê. **Builtins** (`hoje`, `proximo_dia_util`, `arredonda_cima`,
   `fracao`…) são biblioteca da DSL — também código, no catálogo, distintos das funções de relação
   (§22.7.5). O **catálogo de tipos** (primitivos + compostos: `Competencia`, `Maioria/Limiar`,
   `Conjunto`/enum, registros como `AtoDespesa` com campos) idem: declarado pelos contextos donos.
2. **O catálogo é versionado; a regra carimba a versão.** O `registry_versao_ref` de B1 aponta a
   versão do catálogo contra a qual a regra passou no type-check. Persistência concreta (tabela
   gerada vs. estrutura carregada no boot) é detalhe de stack (§22.4.4); o **modelo** é:
   código declara → catálogo versionado → type-checker lê → regra carimba a versão.
3. **Evolução de assinatura = bump de versão do catálogo + passe de re-validação.** Mudar a
   assinatura de uma função de relação bumpa a versão; um passe re-roda o type-check das regras
   `vigente` contra o novo catálogo e **sinaliza quebras no deploy/migração** — estende a garantia
   do save-time type-check (dec. 2) para a evolução do registry. **É a materialização da "mecânica
   fina do registry" de §22.7.4** (visibilidade entre contextos + versionamento de assinatura),
   sem reabrir a forma A2.

Única tabela candidata aqui: um log pequeno `registry_catalogo_versao` (versão → hash/changeset)
para o `registry_versao_ref` ter referente e dirigir a re-validação. Tabela de domínio, sem `ente_id`.

## 6. B4 — prazo como dado / calendários (desenhado)

Materializa S3 (prazo multi-fonte) + a dependência de calendário de feriados. **Duas** tabelas de
**domínio** (sem `ente_id`), lidas pelos builtins de prazo.

```sql
-- Feriados nacional + municipal. Lido por proximo_dia_util / soma_dias_uteis.
CREATE TABLE calendario_feriado (
  id            uuid PRIMARY KEY,
  jurisdicao    text NOT NULL,                 -- 'nacional' | 'municipal'
  municipio_id  uuid REFERENCES municipios(id),-- NULL p/ nacional; FK p/ municipal (§22.4.1)
  data          date NOT NULL,                 -- data resolvida no ano (móveis já calculadas na carga)
  descricao     text NOT NULL,
  origem text NOT NULL, origem_ref text, created_at timestamptz NOT NULL,
  UNIQUE (jurisdicao, municipio_id, data)
);

-- Prazo regulatório vigente por jurisdição/tipo/período, com override por Ofício Circular (S3).
-- Lido por prazo_vigente(dominio, tipo, chave). APPEND-ONLY + ponteiro de vigência.
CREATE TABLE prazo_dominio_vigente (
  id             uuid PRIMARY KEY,
  dominio        text NOT NULL,                -- 'federal' | 'tce_estadual'
  chave_dominio  text,                         -- jurisdição (ex.: 'TCE-CE')
  tipo_prazo     text NOT NULL,                -- ex.: 'SIM_mensal', 'PCS_anual'
  chave_periodo  text NOT NULL,                -- competência/exercício a que se aplica (ex.: '2026-05')
  data_limite    date NOT NULL,               -- o prazo
  fonte          text NOT NULL,                -- 'IN 04/2019' | 'OC 13/2025' (norma-base OU circular que deslizou)
  vigente        boolean NOT NULL,            -- a circular mais recente vence a base; só uma vigente por (dominio,chave_dominio,tipo_prazo,chave_periodo)
  created_at timestamptz NOT NULL, created_by uuid, origem text NOT NULL,
  UNIQUE (dominio, chave_dominio, tipo_prazo, chave_periodo, fonte)
);
```

Decisões (tomadas):
1. **`prazo_dominio_vigente` é REFERÊNCIA regulatória, NÃO obrigação de runtime.** Crítico p/ a
   fronteira de §0: esta tabela responde *"qual é o prazo do SIM da competência X na jurisdição
   TCE-CE"* (dado, com override por circular). A *obrigação* — *"o ente E deve o SIM da competência
   X, vence em D, status pendente"* — é instância de **runtime** e fica no eixo de comportamento do
   motor (S1), como `prazo_dominio_ativo` (generalização de `proposicao_prazo_ativo`, §22.4.3 disc.
   6). **Nomes propositalmente distintos** (`_vigente` = referência · `_ativo` = obrigação) p/ não
   colar os dois conceitos.
2. **Override por circular = append-only + flag `vigente`.** Cada deslize (Ofício Circular) é uma
   linha nova com sua `fonte`; a mais recente fica `vigente=true`, as anteriores viram histórico
   auditável. Mesma disciplina de imutabilidade de §22.4.3 (append-only + ponteiro de vigência). É
   o que materializa o "prazo deslizante" load-bearing do Eixo C (`docs/05` §6.1).
3. **Feriados móveis** (Carnaval, Corpus Christi) entram como datas **já resolvidas por ano** na
   carga (cálculo a partir da Páscoa é detalhe de populate, não de schema). Conteúdo exato dos
   feriados/prazos é `[GAP]` a popular com o especialista em regimento (§22.7.5) — **forma não
   depende do valor**.

---

## 7. Estado e próximo passo

**B1–B4 desenhados** (§3–§6). O Eixo B (schema estático do motor) está coberto de ponta a ponta:
definição (B1) · binding/tenant (B2) · registry/tipos como catálogo de infra (B3) · calendários de
domínio (B4). Tabelas resultantes:

| Tabela | Módulo | Tenant? | Papel |
|---|---|---|---|
| `template_compliance` | compliance | domínio (sem `ente_id`) | definição versionada da regra |
| `compliance_regra_tenant` | compliance | tenant (`ente_id`) | binding: param + on/off + pin |
| `registry_catalogo_versao` | core/compartilhado | domínio | versão do catálogo p/ re-validação |
| `calendario_feriado` | compliance/ref | domínio | feriados nacional+municipal |
| `prazo_dominio_vigente` | compliance | domínio | prazo regulatório com override por circular |

*(Registry de assinaturas/tipos = catálogo declarado em código, não tabela — B3.)*

**Roteado para fora do Eixo B (intencional):** instâncias de obrigação/avaliação/vencimento
(`prazo_dominio_ativo` de runtime) → eixo de comportamento do motor (S1). Numeração de ato →
schema legislativo §22.4. Índices ITM/PNTP → fora do motor (§22.7.5).

**Checkpoint real (único):** consolidar §22.7 do documento-mestre com o schema do Eixo B —
**sob "Confirma?"** (`docs/01`; SSOT não muda sem aval). Sugestão de bump **v1.11**.

**Depois da consolidação:** o avaliador mínimo executável da DSL (parser+type-checker rodando os 4
templates do Eixo C contra o catálogo) — primeira **implementação de fato** (CLAUDE.md §7), valida
o type-check do save time end-to-end. Decisão revisável de `docs/05` §2.
