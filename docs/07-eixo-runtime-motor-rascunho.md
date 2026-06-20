# §22.7 — Eixo de Runtime do motor (comportamento temporal + auditoria) · RASCUNHO DE TRABALHO

> **Status: ✅ CONSOLIDADO em §22.7.7 do documento-mestre (v1.12, 20/06/2026).** Artefato de
> trabalho do primeiro dos "+5 eixos", no mesmo papel que `docs/02` (Eixo A), `docs/05` (Eixo C) e
> `docs/06` (Eixo B). Aqui o motor deixa de ser **forma** (schema estático, Eixo B) e passa a ser
> **comportamento**: como uma regra vira **obrigação concreta**, é **avaliada**, tem **prazo
> monitorado** e gera **prova auditável**. As decisões e a DDL viraram texto canônico em **§22.7.7**;
> este rascunho fica como registro de origem.
>
> **Elevado por S1** (§22.7.5): o motor de compliance _monitora prazo_, não só avalia booleano.
> **Pré-requisito cumprido:** schema estático fechado (§22.7.6); a ponte deixada explícita pelo Eixo B
> ("cada definição carrega o que o runtime vai ler — forma compilada, assinatura, severidade, prazo").

---

## 0. Fronteira de escopo (decidida)

**Dentro deste eixo (o comportamento de runtime do motor):**
- **Materialização de obrigações:** regra (definição + binding) + prazo/calendário → instâncias de
  obrigação concretas, com sujeito e (quando aplicável) prazo.
- **Modelo de avaliação:** quando/como o motor avalia a `forma_compilada` contra o estado do ente.
- **Monitoramento de prazo (S1):** o relógio — estados de vencimento, ação no vencimento, e o
  re-stamp quando o prazo regulatório desliza (Ofício Circular, S3).
- **Auditoria/rastreabilidade:** cada avaliação vira registro imutável = **prova de compliance**.

**Fora deste eixo (roteado, não esquecido):**
- **Geração de artefatos de envio ao TCE** (o arquivo da remessa no formato exigido) → **outro +5
  eixo**. Aqui o motor rastreia a **obrigação de enviar**; **não** gera o arquivo. Fronteira limpa.
- **Versionamento de regras** → já fechado no Eixo B (cópia integral; o runtime só lê a `vigente`).
- **Expansão a outros TCEs** → Invariante 4: conteúdo (dado), não estrutura. O runtime é
  **agnóstico de regime** por construção (lê `dominio`/jurisdição da definição). Não se reabre.

**Consequência honesta:** este eixo **funde dois** dos "+5 candidatos" da abertura do motor —
*comportamento temporal* (o principal, S1) + *auditoria/rastreabilidade de avaliação de regra*. Os
outros candidatos (geração de artefatos; expansão a 27 TCEs) seguem separados. Versionamento saiu da
lista (fechou no Eixo B). Não invento que este "é o eixo X de N" — é o **eixo de runtime**, e estes
dois escopos andam juntos porque o relógio e a prova são o mesmo loop de execução.

---

## 1. Decomposição (4 sub-eixos)

| Sub-eixo | Tema | Estado |
|---|---|---|
| **R1** | **Materialização da obrigação** — `prazo_dominio_ativo` (generalização polimórfica de `proposicao_prazo_ativo`, disc. 6); como a regra vira instância; idempotência | 🎯 design (§3) |
| **R2** | **Modelo de avaliação** — gatilho (evento / sweep / sob demanda); modelo lógico decidido, infra deferida (como §22.4 eixo C) | 🎯 design (§4) |
| **R3** | **Monitoramento de prazo (S1)** — ciclo de vencimento; `acao_no_vencimento`; re-stamp no deslize de circular (S3) | 🎯 design (§5) |
| **R4** | **Auditoria da avaliação** — `compliance_avaliacao` append-only = prova de compliance (Invariante 10 + confiança operacional) | 🎯 design (§6) |

---

## 2. O NÓ CENTRAL — a obrigação temporal (S1)

S1 define a semântica do envelope de compliance: **estado asserido que precisa valer até um prazo,
ou continuamente.** Isso parte a obrigação em **dois sabores**, e a distinção governa todo o resto:

- **Obrigação com prazo (deadline-bound):** "enviar a remessa do SIM da competência 2026-05 até D".
  Tem `vence_em`. **Materializa** uma instância em `prazo_dominio_ativo` — há um relógio aberto a
  monitorar. (remessa mensal; publicação de ato em N dias úteis após assinatura.)
- **Obrigação contínua (standing):** "manter transparência em tempo real" (condicional ao porte).
  **Sem prazo** (bloco `prazo` ausente, S1). **Não materializa** instância com relógio — é uma
  asserção avaliada de forma reativa (no evento / sob demanda); a violação vira **registro de
  avaliação** (R4), não obrigação vencida.

**Decisão central:** **`prazo_dominio_ativo` é a tabela das obrigações COM prazo**; obrigações
contínuas **não criam linha de obrigação** — produzem só avaliação (R4). Isso mantém a tabela de
obrigação enxuta (só o que tem relógio a monitorar) e dá um lugar para tudo (a prova existe para os
dois sabores). É a materialização direta de S1.

**Generalização disparada (disc. 6).** O 2º caso de "prazo de domínio" chegou (envio TCE), então a
disciplina 6 manda **generalizar `proposicao_prazo_ativo` → `prazo_dominio_ativo` polimórfico**
agora — não é abstração prematura, é a regra que a própria §22.4.3 deixou agendada. O sujeito da
obrigação vira polimórfico `(objeto_tipo, objeto_id)` (§22.4.3 disc. 2): competência, ato,
proposição, sessão.

**Contraste que fecha o trio de tenancy do motor:** definição = **domínio** (sem `ente_id`, Eixo B);
binding = **tenant** (`ente_id`, Eixo B); **obrigação = tenant** (`ente_id`, este eixo — a obrigação
é sempre de *um* ente). A separação domínio/tenant de §22.7.6 se completa: o runtime é todo de tenant.

---

## 3. R1 — `prazo_dominio_ativo` (a obrigação materializada)

DDL ilustrativa (tipos concretos e geração de id → chat de stack, §22.4.4; a **modelagem** é o que se
decide aqui):

```sql
-- Módulo: compliance (runtime). Tabela de TENANT → COM ente_id (a obrigação é de UM ente).
-- Generalização polimórfica de proposicao_prazo_ativo (§22.4.3 disc. 6 — 2º caso: envio TCE).
CREATE TABLE prazo_dominio_ativo (
  id                     uuid PRIMARY KEY,
  ente_id                uuid NOT NULL REFERENCES entes(id),

  -- de qual regra (versão EXATA — cópia integral) esta obrigação nasceu
  template_compliance_id uuid NOT NULL REFERENCES template_compliance(id),
  template_chave         text NOT NULL,          -- desnormalizado p/ idempotência e consulta

  -- o SUJEITO da obrigação (polimórfico — §22.4.3 disc. 2)
  objeto_tipo            text NOT NULL,           -- 'competencia' | 'ato' | 'proposicao' | 'sessao'
  objeto_id              text NOT NULL,           -- ref ao sujeito (competência '2026-05', ato uuid, ...)

  -- o RELÓGIO (S1)
  vence_em               timestamptz,             -- o prazo (sempre presente em obrigação deadline-bound)
  prazo_fonte_ref        text,                    -- qual prazo_dominio_vigente/circular fixou o vence_em
                                                  -- (auditoria do "prazo deslizante", S3)

  -- CICLO DE VIDA: enum fixo em código (universal entre câmaras/regimes → NÃO é template,
  -- mesmo princípio de emendas §22.4 eixo D). A DSL governa a ASSERÇÃO e o PRAZO, não este ciclo.
  estado                 text NOT NULL,           -- 'pendente' | 'cumprida' | 'vencida' | 'dispensada' | 'cancelada'
  acao_no_vencimento     text NOT NULL,           -- identificador que o motor mapeia (emitir evento, escalar)

  -- resultado corrente (cache da última avaliação; a TRILHA vai em compliance_avaliacao, R4)
  ultima_avaliacao_id    uuid,                    -- aponta a avaliação que justificou o estado atual
  cumprida_em            timestamptz,

  -- transversais + ente_id (§22.4.3 disc. 1 / §22.2)
  created_at timestamptz NOT NULL, created_by uuid, updated_at timestamptz NOT NULL, updated_by uuid,
  lock_version int NOT NULL DEFAULT 0, origem text NOT NULL, origem_ref text, origem_importado_em timestamptz,

  UNIQUE (ente_id, template_chave, objeto_tipo, objeto_id)  -- IDEMPOTÊNCIA da materialização
);
-- índice composto começa por ente_id (§22.2); índice parcial por (estado, vence_em)
-- WHERE estado = 'pendente' p/ o sweep de monitoramento (R3) varrer só o que tem relógio aberto.
```

### Decisões embutidas no R1 (tomadas)

1. **A obrigação aponta a versão EXATA da regra** (`template_compliance_id`), não só a chave lógica.
   Prova de compliance precisa saber *qual texto da regra* gerou *qual obrigação* — se a regra mudar
   (nova versão por cópia integral), obrigações já materializadas mantêm a versão sob a qual nasceram.
2. **Idempotência por `UNIQUE(ente_id, template_chave, objeto_tipo, objeto_id)`.** Materializar
   "remessa competência 2026-05 do ente E" duas vezes (gatilho disparou em duplicidade) **não** cria
   obrigação duplicada. Crítico porque o gatilho é evento (R2) e eventos podem reentregar.
3. **Ciclo de vida é enum fixo, não template** (§22.4 eixo D). `pendente → cumprida | vencida |
   dispensada | cancelada`. `dispensada` = opt-out via binding (`compliance_regra_tenant.ativa=false`)
   ou inaplicabilidade superveniente; `cancelada` = sujeito desapareceu (proposição arquivada). O
   ciclo é universal; só a **asserção** e o **prazo** são DSL.
4. **`acao_no_vencimento` é identificador, não código** (igual `proposicao_prazo_ativo` e as ações da
   tramitação, §22.4 eixo C): o motor mapeia para handler. Na V1 a ação canônica é **emitir evento de
   domínio** (R3) — escalar severidade / notificar são consumidores, não lógica embutida na obrigação.

---

## 4. R2 — modelo de avaliação (lógico decidido, infra deferida)

**Mesma disciplina de §22.4 eixo C** ("processamento — worker varrendo, job scheduling, ou híbrido —
é decisão de infra deferida; schema sobrevive a qualquer escolha"). Aqui se decide o **modelo
lógico**, não a infra.

**Três gatilhos, um motor:**
1. **Dirigido a evento (primário).** Um evento de domínio (Invariante 2 / §22.3) — `AtoAssinado`,
   `SessaoEncerrada`, `CompetenciaFechada`, `ProposicaoTransicionou` — dispara (a) **materialização**
   de obrigações que aquele evento torna devidas e/ou (b) **reavaliação** das obrigações afetadas.
   Reusa o **bus e a taxonomia de eventos que já existem** — não há mecanismo novo.
2. **Sweep agendado (o relógio, R3).** Uma varredura periódica avança o estado de vencimento das
   obrigações `pendente` cujo `vence_em` passou, e materializa obrigações **recorrentes** de calendário
   (remessa mensal) que ainda não nasceram. É o único gatilho temporal; o resto é reativo.
3. **Sob demanda.** A UI ("estou em dia com o TCE?") força uma avaliação pontual — útil para regras
   **contínuas** (sem relógio) e para o painel de confiança operacional. Não materializa; só avalia.

**Decisões (tomadas):**
1. **Evento como caminho primário; sweep só para o que é intrinsecamente temporal** (vencimento,
   recorrência de calendário). Evita polling cego — bate com Invariante 2 (domain events de primeira
   classe) e com a economia de já ter o bus.
2. **Avaliação = executar `forma_compilada` (AST tipado) contra o estado do ente.** O avaliador é o
   **mesmo** dos 4 usos da DSL (disc. 5); compliance não ganha avaliador próprio. As funções de
   relação/builtins vêm do catálogo do registry (B3); a versão é a `registry_versao_ref` carimbada.
3. **Infra (worker dedicado vs. fila persistente vs. cron) → chat de stack.** O modelo lógico
   (evento + sweep + sob demanda) e o schema sobrevivem a qualquer escolha de infra.

---

## 5. R3 — monitoramento de prazo (o coração do S1)

O que distingue o motor de compliance de um simples avaliador booleano: ele **observa o relógio**.

**Ciclo de vencimento de uma obrigação deadline-bound:**
- `pendente` + `vence_em` no futuro → **a vencer**. (O painel de confiança operacional mostra "faltam
  N dias para a remessa X".)
- avaliação dá `conforme` (remessa enviada/estado asserido válido) → `cumprida` (`cumprida_em`
  carimbado; relógio fecha).
- `vence_em` passou e ainda não cumprida → o **sweep** transiciona para `vencida` e dispara
  `acao_no_vencimento` (emite `ObrigacaoComplianceVencida`).

**Decisões (tomadas):**
1. **"A vencer" / "vence em breve" são derivações da consulta, não estados persistidos.** Persistir
   `pendente`/`cumprida`/`vencida`/`dispensada`/`cancelada`; o "faltam N dias" e o limiar de alerta
   (ex.: 3 dias) são **cálculo de leitura** sobre `vence_em` (relógio injetado, builtin de Eixo C),
   não colunas. Mantém a máquina de estados pequena.
2. **Re-stamp no deslize de circular (S3 ↔ Eixo B).** Quando `prazo_dominio_vigente` ganha nova linha
   `vigente` (Ofício Circular deslizou o prazo de uma competência), as obrigações **abertas**
   (`pendente`) daquele `(dominio, tipo, periodo)` têm `vence_em` **re-carimbado**, com o novo
   `prazo_fonte_ref` — e o deslize fica na trilha de auditoria (R4). O relógio **pode** mover; é
   exatamente o "prazo deslizante" load-bearing do Eixo C. Obrigações já `cumprida`/`vencida` **não**
   se mexem (fato consumado).
3. **`acao_no_vencimento` na V1 = emitir evento de domínio, sempre.** Escalonamento (notificar
   servidor, subir no painel, marcar SLA de janela de envio) são **consumidores** do evento, não
   lógica na obrigação. Mantém o motor declarativo e reusa o bus (§22.3). A política concreta de
   escalonamento por tipo de requisito é `[GAP]` de produto/UX — a **forma** (evento no vencimento)
   está decidida.

---

## 6. R4 — auditoria da avaliação (a prova de compliance)

Load-bearing **comercial**: "confiança operacional como diferencial" (CLAUDE.md §1) + **Invariante
10** (audit log como domínio de produto). Cada avaliação — dos **dois** sabores — vira registro
imutável.

```sql
-- Módulo: compliance (runtime). APPEND-ONLY (imutabilidade nível (a), §22.4.3 disc. 4).
-- A "prova de compliance": qual regra (versão), contra qual catálogo, que veredito, quando.
CREATE TABLE compliance_avaliacao (
  id                     uuid PRIMARY KEY,
  ente_id                uuid NOT NULL REFERENCES entes(id),

  -- o que foi avaliado
  obrigacao_id           uuid REFERENCES prazo_dominio_ativo(id),  -- NULL p/ regra CONTÍNUA (sem obrigação)
  template_compliance_id uuid NOT NULL REFERENCES template_compliance(id),  -- versão EXATA avaliada
  registry_versao_ref    text NOT NULL,           -- versão do catálogo (B3) — re-validável

  -- o VEREDITO
  veredito               text NOT NULL,           -- 'conforme' | 'nao_conforme' | 'inaplicavel'
  severidade             text NOT NULL,           -- snapshot no momento (bloqueante | aviso)
  entradas_ref           jsonb,                   -- snapshot/ref das entradas que alimentaram a avaliação
  detalhe                text,                    -- legível ("remessa não enviada; vence em 2d")

  -- QUANDO (evento — não mutável)
  occurred_at            timestamptz NOT NULL,
  origem_avaliacao       text NOT NULL,           -- 'evento' | 'sweep' | 'sob_demanda'
  -- transversais de evento (§22.4.3 disc. 1, exceção append-only): só created_at/by, sem updated_*/lock
  created_at timestamptz NOT NULL, created_by uuid,
  origem text NOT NULL
);
-- append-only: sem updated_*, sem lock_version, sem deleted_at.
-- índice composto começa por ente_id (§22.2); índice (obrigacao_id, occurred_at) p/ a trilha de uma obrigação.
```

**Decisões (tomadas):**
1. **Append-only puro (nível a).** Avaliação é fato histórico; nunca UPDATE/DELETE. É o que dá força
   probatória — "em D, contra a regra versão V e o catálogo C, o ente estava conforme". Se a regra ou
   o catálogo mudarem depois, o registro antigo preserva o contexto sob o qual foi feito.
2. **Carimba regra + catálogo** (`template_compliance_id` + `registry_versao_ref`). Reconstrói
   exatamente *com o que* a avaliação foi feita — fecha o ciclo do save-time type-check do Eixo B
   (que carimba a versão na definição) com o runtime (que carimba a versão na avaliação).
3. **`ultima_avaliacao_id` na obrigação aponta para cá.** A obrigação carrega o **estado corrente**
   (cache leve); a **trilha** vive aqui (append-only). Mesma divisão de `texto_vigente_versao_id` →
   `proposicao_texto_versao` (§22.4 eixo B): ponteiro de corrente + histórico append-only.

**Eventos emitidos pelo runtime** (no bus interno, §22.3 — consumidos por notificação/painel/IA):
`ObrigacaoComplianceMaterializada`, `ObrigacaoComplianceAvaliada`, `ObrigacaoComplianceCumprida`,
`ObrigacaoComplianceVencida`, `ObrigacaoComplianceDispensada`. Promoção a evento de **integração**
com a Plataforma de IA quando feature justificar (mesma régua de §22.4 eixo G).

---

## 7. Estado e próximo passo

**R1–R4 desenhados** (§3–§6). O eixo de runtime cobre o loop de execução do motor de ponta a ponta:
materialização (R1) · avaliação (R2) · monitoramento de prazo (R3) · prova auditável (R4). Tabelas
novas:

| Tabela | Módulo | Tenant? | Papel |
|---|---|---|---|
| `prazo_dominio_ativo` | compliance/runtime | tenant (`ente_id`) | obrigação materializada com relógio (generaliza `proposicao_prazo_ativo`, disc. 6) |
| `compliance_avaliacao` | compliance/runtime | tenant (`ente_id`) | trilha append-only de avaliação = prova de compliance |

**Roteado para fora (intencional):** geração do arquivo de remessa ao TCE → outro +5 eixo;
versionamento de regra → Eixo B (cópia integral); expansão a 27 TCEs → Invariante 4 (conteúdo).

**`[GAP]` (conteúdo, não forma):** política concreta de escalonamento por tipo de requisito (quem é
notificado, quando, com que urgência) — produto/UX + especialista; valores de prazo das INs já são
`[GAP]` do Eixo B. A **forma** (evento no vencimento, ciclo de obrigação, trilha) não depende deles.

**Deferido a stack (§22.4.4):** infra de processamento (worker/fila/cron) do sweep e do consumo de
eventos; geração de id. O modelo lógico (evento + sweep + sob demanda) sobrevive à escolha.

**Checkpoint real (único):** consolidar §22.7 do documento-mestre com o eixo de runtime — sugestão de
bump **v1.12**, nova subseção **§22.7.7**. Atualizar §22.7.4 (roadmap dos +5: runtime ✅, restam
geração de artefatos + expansão), intro de §22.7, §24, e os arquivos de cursor (CLAUDE.md §2/§3,
docs/00).

**Depois:** sobra como candidato de igual valor a primeira **implementação de fato** — o avaliador
executável da DSL (parser+type-checker rodando os 4 templates do Eixo C contra o catálogo), que agora
tem **o loop de runtime desenhado** para validar end-to-end (materializa → avalia → monitora → audita).
