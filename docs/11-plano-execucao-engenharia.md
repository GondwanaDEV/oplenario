# Plano de Execução da Engenharia — O Plenário

> **Tipo:** plano de execução faseado da materialização do produto (handoff para as sessões de implementação).
> **Origem:** brief em `docs/10-proxima-sessao-plano-execucao.md`. **Não implementa nada** — sequencia.
> **Base:** SSOT (`documento-mestre-camaras.md` §1–24 + `arquitetura/`) + auditoria do `backend/` e do design-system feita na sessão de 26/06/2026.

## Contexto

A SSOT está fechada: estratégia (doc-mestre §1–21), os **10 invariantes** (§22.1), tenancy/serviços/ingestão/staging (§22.2), contrato core↔IA (§22.3), modelo de dados legislativo em 8 eixos (§22.4), auth + resolvedor de fatos (§22.5), sessão plenária em 7 eixos (§22.6), **motor de compliance completo** (§22.7), stack provider-neutral (§22.9) e fronteiras do monólito (§22.10). O **design está 100%** (48 telas, fundação `sistema/`). Falta **materializar**.

Estado real do esqueleto (`backend/`, auditado nesta sessão):
- ✅ **Infra 100%**: `deps.edn` (Pedestal·Malli·next.jdbc·HoneySQL·HikariCP·Migratus·Component·Carmine), `docker-compose` (Postgres16·Valkey8·MinIO·Keycloak26), 6 migrations (12 schemas, `shared.outbox`, `admin_sistema.ente`, `paineis.notificacao_entrega`, runtime do compliance, catálogo do motor).
- ✅ **Motor 100%** (`motor/`, 1.016 linhas, 12 testes verdes): lexer·parser·type-checker (`verificar-fonte` real)·avaliador tree-walk·loop de runtime — roda **em atom**.
- ⚠️ **Seams abertos**: `motor/db/` (persistência stub), `motor/avaliar` (resolvedor de fatos **não injetado** — documentado, não exposto), `arquitetura_test.clj` (import-lint **placeholder vazio**), RLS/partição (deferida — mas fundação #2 a tornou **não-deferível**).
- ❌ **Zero código**: `cadastros`, `identidade`, `sessoes`, `transparencia`, `participacao` (só README). `legislativo`/`admin_sistema` = silhueta-template vazia. `paineis`/`tempo_real` = projeção mínima.
- ❌ **Front**: HTML/CSS puro, **sem toolchain** (sem `package.json`/Next/Tailwind).

Objetivo deste plano: **a ordem de construção, o que cada fase entrega, e o que trava o quê** — trilho para as sessões de implementação. Não implementa nada aqui.

> **Nota de execução:** este plano fala em **fases**, não em meses/headcount (isso é §18 + chat de dimensionamento). "Modelo/effort" = o tier sugerido para a sessão de implementação assistida (Opus high/max para carga arquitetural; Sonnet low/medium para mecânico; revisão por agentes `ecc`).

---

## Guardrails que constrangem TODA fase (não driftar)

- **Inv. 1** — `ente_id` em toda tabela tenant; `municipios` ref IBGE; multi-ente desde o dia 1.
- **Inv. 4** — regra de compliance é **dado validado**, não código. Nada de `if tribunal == X`.
- **Inv. 5** — fronteira core↔presentation explícita (API interna Malli desde o dia 1).
- **Inv. 10** — audit log append-only, imutável, de produto.
- **Disciplina 5** (§22.4.3/§22.5.3) — **uma DSL, um avaliador** para tramitação + autorização + plenário + compliance. Não criar DSLs paralelas.
- **§22.10** — silhueta Nubank por módulo; comunicação **só HTTP ou eventos**; **proibido import cross-módulo e JOIN cross-schema**; schema-por-módulo; refs cross-módulo por **guard de serviço**, não FK.
- **§22.9** — **zero vendor cloud**: Postgres vanilla + outbox, Valkey, k8s self-managed, Keycloak, Next self-host, IA atrás de porta vendor-agnóstica.
- **§15** — escopo diferido por default; régua das 4 perguntas é filtro permanente.

---

## Caminho crítico (o backbone que destrava tudo)

```
F0 Plataforma base ──> F1 Cadastros+Identidade ──> F2 RESOLVEDOR+motor/avaliar ──> F3 Legislativo ──> F4 Sessões+tempo_real
   (tenancy/RLS,            (refs, relações,            (KEYSTONE: registry de         (coração: 8 eixos,    (HERO: sessão ao vivo,
    kernel, outbox,          jurisdicao_camara,          fatos in-process; policy.check  protocolo→publicação)  votação, ata-IA)
    import-lint CI)          gate URN/LexML, auth base)   ligado ao mesmo avaliador)
                                                              │
                                                              ├──> F5 Compliance runtime + remessa
                                                              ├──> F6 Transparência + Participação (portal, e-SIC)
                                                              └──> F7 Painéis + notificações + observabilidade

Tracks paralelos:  FE (front: F0-codegen ──> componentes ──> portar telas por fatia)   ·   IA (satélite Python, interface §22.3)
```

**Regra-mestra de bloqueio:** o **resolvedor de fatos (F2, §22.5.3 disc.3/5)** é o nó que destrava motor (compliance), autorização (`policy.check`) e os agregadores de tramitação ao mesmo tempo. Se o contrato de registro/injeção sair errado, F3/F4/F5 quebram juntos. **Validar end-to-end antes de fanout.**

---

## Os seams a fechar (explícito, do brief)

| Seam | Onde está hoje | Fase que fecha |
|---|---|---|
| **Persistência real do motor** (`motor/db/`) | 5 tabelas em migration 6; repositório = stub comentado "deferida §22.4.4" | **F2** |
| **Resolvedor de fatos injetado** (`motor/avaliar`, §22.5.3 disc.5) | seam documentado, não exposto; runtime roda em atom | **F2** (keystone) |
| **Tabelas do `cadastros` incl. `jurisdicao_camara`** (§22.7.9 E1) | 0 código; DDL deferida | **F1** |
| Import-lint §22.10 (matriz) | `arquitetura_test.clj` = placeholder vazio | **F0** |
| RLS + partição `hash(ente_id)` + 3ª dimensão de efetivação | deferida em comentário de migration | **F0** (fundação #2: não mais deferível) |
| `policy.check` mecânica + two-layer authz | kernel/autorizacao stub | F0 (esqueleto) → **F2** (ligado ao avaliador) |
| Outbox **relay + worker** (sweep/cron) | `shared.outbox` existe; relay/worker não | **F0** |
| Estratégia de `sequencial` (numeração canônica, §22.4 eixo H) | deferida ao chat de stack | **F0** (decisão) → F3 (uso) |

---

## Fases (ordenadas)

### F0 — Plataforma base (raiz do caminho crítico)
- **Objetivo:** o substrato que todo módulo herda, nascido correto em tenancy e fronteira.
- **Entregáveis:**
  - **Tenancy materializada**: política RLS + partição `hash(ente_id)` **já com a 3ª dimensão de efetivação de migração** (fundação #2/§22.2); `lote_id`/`efetivado_em` na forma. Disciplinas §22.2 (coluna `ente_id`, índice composto começa por `ente_id`).
  - **Kernel real**: `datasource`/HikariCP; **bus de eventos** (`producer`→`shared.outbox`→`consumer`) com **relay + worker-pool** (a infra de fila do §22.9 eixo 3); `ids` (UUID + decisão de `sequencial`); `tempo` (relógio injetado — já no motor); `policy.check` esqueleto; `objeto_store` (port MinIO).
  - **Import-lint §22.10 no CI**: a matriz real em `arquitetura_test.clj` via clj-kondo (módulo nunca importa namespace de outro; kernel/motor nunca importam módulo). **Teste de vazamento cross-tenant** com 2 entes sintéticos (3 dimensões: cross-tenant, cross-esfera, lote-não-efetivado).
  - **Decisões §22.4.4 que bloqueiam tudo**: estratégia de `sequencial` (SEQUENCE por `(ente_id,tipo,ano)` vs counter); padrão de idempotência de handler; validação de polimórfico `(objeto_tipo,objeto_id)` (CHECK XOR vs guard de serviço); Malli→TS (escolher a lib do codegen).
  - Pipeline Migratus + ambiente dev (`docker-compose` já existe); `Dockerfile` de produção (uberjar).
- **Critério de feito:** CI verde com import-lint + leak test 3-dim falhando build em violação; um evento atravessa outbox→relay→consumer; RLS bloqueia cross-tenant em teste.
- **Dependências:** nenhuma (raiz).
- **Modelo/effort:** **Opus high** (Opus **max** na política de tenancy+efetivação e no contrato do outbox/idempotência — erro aqui é refactor de ano). Revisão: `ecc:database-reviewer` + `ecc:security-reviewer`.

### F1 — Cadastros + Identidade (refs · relações · base de auth)
- **Objetivo:** a "Casa" existe; os fatos de cadastro ficam resolvíveis; ator/papel autenticável.
- **Entregáveis:**
  - **`cadastros`** (silhueta Nubank completa — primeiro módulo de referência): `entes` (Inv.1), `municipios` (seed IBGE), **`jurisdicao_camara`** (domínio, sem `ente_id`, índice `COALESCE` p/ `UNIQUE+NULL`, §22.7.9 E1), `vereadores`/`mandato` (com `vigencia_inicio/fim` p/ consulta temporal, disc.5 §22.5.3), `comissoes`, `mesa_diretora`, `legislatura`/`sessao_legislativa`.
  - **`cadastros/relacoes/`**: `tem_mandato_vigente`, `é_membro_de_comissao`, `é_presidente_da_mesa`, `é_secretario_da_mesa`, `quem_exerce_presidencia`, `populacao(ente)`, `membros_da_casa(ente)`, **`tribunal_competente(ente)`** (§22.7.9 E1) — todas com `instante` default `now()`.
  - **`identidade`**: `identidade` supratenant (CPF, disc.1) ↔ `vinculo` (tenant), `usuario_papel` (RBAC); integração **Keycloak** (realm tenant + IdP **fisicamente separado** p/ admin); **gov.br** p/ cidadão; passkey-primário + TOTP piso. `identidade/relacoes/`: `é_o_próprio`.
- **Critério de feito:** dois entes sintéticos isolados; login servidor (passkey) + cidadão (gov.br) ponta-a-ponta; relações respondem com `instante` histórico; schemas Malli emitem TS (primeiro corte do codegen).
- **Dependências:** F0.
- **Modelo/effort:** **Opus high** (modelo de dados é load-bearing; auth tem armadilhas de segurança). Revisão: `ecc:clojure-reviewer` + `ecc:database-reviewer` + `ecc:security-reviewer`.

### F2 — Resolvedor de fatos + motor em runtime (KEYSTONE) ⚠️
- **Objetivo:** ligar o motor (já pronto) ao mundo real — fatos resolvidos do domínio, persistência real, `policy.check` no mesmo avaliador.
- **Entregáveis:**
  - **`motor/db/` real**: repositório sobre as 5 tabelas estáticas (migration 6 já existe) — `template_compliance`, `compliance_regra_tenant`, `prazo_dominio_vigente`, `calendario_feriado`, `registry_catalogo_versao`.
  - **Registry de fatos / resolvedor (o contrato crítico)**: o mecanismo pelo qual cada módulo registra suas `relacoes/` no catálogo do motor e o avaliador as invoca **in-process, síncrono, por inversão de dependência** (o módulo entrega a `fn`/query no boot via Component; o motor chama por nome — **não** importa o módulo). **Esta é a exceção nomeada à regra "só HTTP/eventos" do §22.10** — reconciliar e documentá-la explicitamente. Schema Malli de entrada/saída por relação; type-check no save-time já valida assinatura.
  - **`motor/avaliar` exposto** (hoje seam): `regras-aplicaveis` db-backed, `prazo-vigente` db-backed, resolvedor injetado.
  - **`policy.check`** (kernel) ligado ao **mesmo** avaliador; **two-layer authz** nos interceptors Pedestal (middleware grosso + domínio fino com recurso carregado); naming `dominio.acao(args, ator)` + lint que exige `policy.check`.
  - **Validação end-to-end**: um template real do TCE-CE (já existem T1–T4) avaliando com `tribunal_competente`/`populacao` resolvidos do `cadastros`; **e** uma policy de auth usando `é_autor_de`/`tem_mandato_vigente`.
- **Critério de feito:** motor sai do atom e avalia contra Postgres; obrigação materializa→avalia→audita com fatos reais; `policy.check` nega por papel, por relação dinâmica e por estado; cobertura de autorização no CI.
- **Dependências:** F0, F1 (relações de cadastros/identidade).
- **Modelo/effort:** **Opus max** no contrato do resolvedor (registro/injeção/reconciliação §22.10) — é o único nó cujo erro propaga para 3 fases. Resto Opus high. Revisão: `ecc:architect` + `ecc:clojure-reviewer` + `ecc:security-reviewer`.

### F3 — Legislativo (o coração: proposição → publicação)
- **Objetivo:** o fluxo legislativo central de ponta a ponta (§15: da proposição à publicação).
- **Entregáveis (8 eixos §22.4):**
  - **Gate de abertura — eixo H (URN/LexML + numeração canônica)**: decidir e cravar **antes** de qualquer DDL (retrofit é refactor estrutural). PK UUID + `(ente_id,tipo,ano,sequencial)` UNIQUE + formato de exibição por ente + estratégia de imutabilidade auditada.
  - Eixo A: `proposicoes` STI híbrido (tronco + colunas quentes + JSONB sidecar só p/ PDL) com CHECK por tipo; espécies incl. Decreto Leg./Resolução/Emenda à LOM (§16.13).
  - Eixo B: `proposicao_texto_versao` append-only (`origem_versao`, inline/URI 32KB, `texto_vigente_versao_id`).
  - Eixo C: **tramitação por motor declarativo** (`template_tramitacao`/`estado`/`transicao`, `proposicao_transicao_historico`) — **reusa o avaliador da F2** + agregadores de pareceres.
  - Eixo D: `emendas` (ciclo enum em código); Eixo E: `proposicao_apensacao` (não-destrutiva); Eixo F: `parecer_comissao` (state machine própria, polimórfico); Eixo G: `votacoes`/`votos`/`votos_secretos` (polimórfico, append-only, quórum no motor).
  - **Pós-aprovação** (§16.3): autógrafo → sanção/veto → promulgação → publicação.
  - **Expediente/Documentos + Protocolo Geral** (§16.13 Tier 1): geração de docs por modelo + numerador único.
- **Critério de feito:** protocolar→tramitar pelo regimento configurável→parecer→emenda→votar→promulgar→publicar, com audit append-only e imutabilidade pós-publicação; eventos de domínio no bus.
- **Dependências:** F2 (avaliador + relações), F1 (entes/vereadores/comissões).
- **Modelo/effort:** **Opus high** (data model + tramitação). DDL mecânica e CRUD-controllers podem ser **Sonnet medium** a partir das DDLs já esboçadas. Revisão: `ecc:clojure-reviewer` + `ecc:database-reviewer`.

### F4 — Sessões + tempo_real (o HERO da demo)
- **Objetivo:** a sessão plenária acontece ao vivo; alimenta a ata-IA.
- **Entregáveis (7 eixos §22.6):** `legislatura→sessao_legislativa→sessao` (tipos + capabilities desacopladas); **pauta** (versão append-only + alterações intra-sessão); **presença/quórum** (eventos append-only, `está_presente_em`, agregadores `presentes_plenario/remoto` à DSL — S4 envelope de guard); **votação** (reusa polimórfico do legislativo + quórum no motor); **tribuna** (inscrição/`fala_executada`/cronômetro por marcos/`decisao_mesa`); **gravação** (`gravacao_segmento`, endpoint de ingestão **agnóstico** §22.3.4 + CLI/watch-folder, `GravacaoSegmentoCaptado`); **`tempo_real`** (projeção SSE sobre o bus, canais plenário/vereador/público, backplane Valkey, replay 5min). Mesa de condução + incidentes processuais (§16.13).
- **Critério de feito:** sessão ao vivo com placar nominal, quórum em tempo real, presença, tribuna; gravação ingerida emite evento; SSE projeta sem emitir no bus; ata-IA revisável quando a Track IA entregar `TranscricaoRevisada`.
- **Dependências:** F3 (proposições/emendas/pareceres p/ votação), F1 (vereadores/comissões/mesa), F2 (motor de votação), Track IA (interface §22.3 p/ transcrição/ata).
- **Modelo/effort:** **Opus high** (acoplamento sessão↔motor↔tempo). SSE/projeção e controllers = **Sonnet medium**. Revisão: `ecc:clojure-reviewer` + `ecc:security-reviewer`.

### F5 — Compliance runtime operacional + remessa
- **Objetivo:** o motor vira produto: monitora prazo do TCE-CE e gera o artefato de envio.
- **Entregáveis:** o módulo `compliance` (já materializado, runtime tables em migration 5) ligado à **persistência real + resolvedor da F2** (sai do atom); loop materializa→avalia→monitora→audita com `sweep` no worker da F0; `gerador_remessa` (descritor de layout = **dado**, dec.2b — forma fechada, **layout SIM = [GAP]**); `remessa_gerada` append-only + `objeto_store`; transporte "download manual" V1; `remessa_enviada` cumpre em `aceita`.
- **Critério de feito:** obrigação do SIM materializa, vence/cumpre conforme calendário; remessa gera artefato (com fixture ilustrativo) e o operador submete; painel "a Casa está em dia" reflete estado real.
- **Dependências:** F2 (keystone), F1 (`tribunal_competente`, calendários), F4 (atos/votações como fonte de fatos).
- **Modelo/effort:** **Opus high**. Revisão: `ecc:clojure-reviewer` + `ecc:database-reviewer`.

### F6 — Transparência + Participação (a porta da rua)
- **Objetivo:** público cidadão + obrigações legais de publicação.
- **Entregáveis:** `transparencia` (publicação de proposições/atas/votações/presenças/legislação consolidada; portal white-label; transparência fiscal do órgão por **consumo**, não produção §17; dados abertos; artefato de publicação oficial); `participacao` (**e-SIC amplo** com prazo via `prazo_dominio_ativo` 20+10 + recurso; ouvidoria 13.460 + Carta de Serviços; **portal do titular LGPD**; comentários + moderação). Resumo cidadão/busca semântica via Track IA.
- **Critério de feito:** portal navegável white-label; pedido e-SIC com timer legal; acompanhamento por cidadão; AA/eMAG.
- **Dependências:** F3 (atos/proposições), F2 (motor de prazo), F1 (cidadão gov.br).
- **Modelo/effort:** **Opus high** no e-SIC/LGPD (risco jurídico); resto **Sonnet medium**. Revisão: `ecc:security-reviewer`.

### F7 — Painéis + notificações + observabilidade
- **Objetivo:** tornar visível o que já capturamos; operar com confiança.
- **Entregáveis:** `paineis` (projeção já esboçada + `notificacao_entrega` migration 4) com consumers ligados aos streams reais — "o que vence", pendências, tramitação board, dashboard da Mesa, busca global não-IA, exportação PDF/CSV; **entrega de notificação** (port e-mail in-region §22.9 fundação #3 + Web Push); observabilidade dos 4 pilares (Inv.7), `ente_id` em todo sinal (Inv.8), SLIs de negócio "sessão de quarta funciona?" (Inv.9); status page; SLA de janela de sessão.
- **Critério de feito:** read-models reconstroem do event log; notificação durável idempotente; alertas filtráveis por `ente_id`; SLI de janela de sessão monitorado.
- **Dependências:** F3/F4/F5 (streams de evento), F0 (outbox/relay).
- **Modelo/effort:** **Sonnet medium** majoritário; **Opus high** na observabilidade/SLI. Revisão: `ecc:code-reviewer`.

---

## Track FE — Front (paralelo, começa após F0)

| Sub-fase | Entregável | Depende de | Effort |
|---|---|---|---|
| **FE0 — codegen + scaffold** | `tokens.css`→TS/Tailwind (85 props: paleta fixa + semânticos por `[data-tema]`); theming = `tema.js`→React Context + `localStorage('oplenario-tema')` + `prefers-color-scheme`; Next.js **self-host** (não Vercel), 3 superfícies (portal SSR/SSG, app interno, painel SSE) | só `sistema/` | **Sonnet low** (Opus high p/ decidir o pipeline Malli→TS) |
| **FE1 — biblioteca de componentes** | `chassi.css`→React, **portar 1:1 antes de inventar**; começar pelos 11 promovidos: `.topo`·`.btn`·`.chip`·`.sinal`·`.azulejo`·`.ilha-palco`·`.ilha-papel`·`.lacre/.trilha`·`.passos`·`.govbr`·`.card`. **`GUIDELINES-CHECKLIST.md` = gate** (§5.1 contraste AA medido nos 2 temas) | FE0 | **Sonnet medium** |
| **FE2+ — portar telas por fatia vertical** | ordem **casada com o backend pronto**: (1) `login`/`entrar-govbr`/cadastros [após F1] → (2) `editor-proposicao`/`proposicoes`/`tramitacao-board`/`ficha-materia` [F3] → (3) `sessao-ao-vivo`/`telao-votacao`/`pauta-convocacao`/`ata-revisao` [F4] → (4) `portal-cidadao`/`portal-materias`/`ouvidoria` [F6] → (5) `vereador-app` PWA [F4/F3] → (6) `console-operador` [admin] | módulo correspondente + Malli→TS daquela fatia | **Sonnet medium** |

PWA-first (§22.9 eixo 9): Next responsivo + Web Push; RN/Expo **diferido**. O cliente tipado de cada tela chega quando o `schema/` do módulo chega (Malli→TS por fatia).

---

## Track IA — Plataforma de IA (satélite Python, paralelo desde cedo)

Satélite **separado** (§22.2): Python-nativo, GPU-aware, fora do container do core. **A engenharia do core só depende da interface §22.3** (eventos de integração + `objeto_store` + endpoint de ingestão). Seu build interno é **plano próprio** (Onda 0: pipeline áudio/diarização, corpus indexado, embeddings, confidence layer). Deve correr em paralelo desde cedo porque destrava os marcos de **demo power** (ata-IA, resumo cidadão, busca semântica, copiloto — Aposta 1) em F4/F6; é o item que **aperta o cronograma** (§16.4). Contrato a honrar: eventos `core→IA` (`GravacaoSegmentoCaptado`, `SessaoEncerrada`…) e `IA→core` (`TranscricaoRevisada`, `AtaRascunhoPronta`…), idempotência + `correlation_id`+`ente_id`, modelo de erro de 6 categorias.

---

## Matriz de risco

| Risco | Sev | Por quê | Mitigação |
|---|---|---|---|
| **Contrato do resolvedor de fatos** (§22.5.3 disc.5) | 🔴 CRÍTICO | erro propaga p/ motor+auth+tramitação juntos; é a exceção ao §22.10 | desenhar + **validar end-to-end em F2** antes de fanout; Opus max; `ecc:architect` |
| **Tenancy/RLS com dimensão de efetivação** (fundação #2) | 🔴 CRÍTICO | não-efetivado visível = incidente ANPD; retrofit = ano | nascer correto em **F0**; leak test 3-dim no CI |
| **URN/LexML + numeração canônica** (§22.4 eixo H) | 🔴 CRÍTICO | retrofit é refactor estrutural | **gate no início de F3**, decidir antes de DDL |
| Estratégia de `sequencial` / idempotência / polimórfico | 🟡 MÉDIO | concorrência, drift silencioso | decidir em F0; CHECK XOR + guard de serviço |
| Timeline da Track IA | 🟡 MÉDIO | Aposta 1 (demo power) depende dela; aperta §18 | correr em paralelo desde cedo; core não-bloqueado (interface §22.3) |
| Lib de codegen Malli→TS não especificada | 🟡 MÉDIO | desincronização schema↔front | escolher em F0/FE0 |
| Tuning SSE (retenção 5min, mobile) | 🟢 BAIXO | UX de reconexão | calibrar em PoC F4 |
| Contraste/AA no front | 🟢 BAIXO | já há gate medido | `GUIDELINES-CHECKLIST.md` §5.1 |

---

## [GAP] que continuam abertos (conteúdo, não forma — não bloqueiam)

- **Layout físico do arquivo SIM** (formato/campos/encoding) — F5 gera com **fixture ilustrativo**; conteúdo real = especialista/TCE.
- **Conteúdo regulatório por tribunal** (regras, prazos, feriados, sistema de remessa) — populado **demand-pulled** (§15); 2º TCE = marco de validação empírica da forma.
- **Protocolo de submissão TCE** (API vs upload) — V1 = download manual.
- **Valores de prazo das INs / calendário de feriados** — `[GAP]` de conteúdo; a forma não depende do valor.
- Rótulo terminal de aceite/rejeição da remessa; rito exato do veto (varia por LOM) — confirmar com especialista.

---

## Marcos de valor demonstrável

| Marco | Após | O que se mostra (a quem) |
|---|---|---|
| **M1 — "a Casa existe"** | F1 | cadastro de vereadores/comissões/mesa, multi-ente isolado, login passkey + gov.br. *(tenancy + identidade)* |
| **M2 — "compliance vivo"** | F2 | o motor avalia uma obrigação real do TCE-CE com fatos resolvidos do cadastro; autorização fina funciona. *(Aposta 3 + Inv.4 — jurídico/administrativo)* |
| **M3 — "o coração legislativo"** | F3 | protocolar→tramitar→parecer→emenda→votar→promulgar→publicar; editor + copiloto (c/ IA). *(servidor)* |
| **M4 — "a sessão acontece"** 🎯 | F4 | sessão ao vivo, votação nominal no telão, quórum/presença/tribuna em tempo real, gravação→transcrição→ata-IA revisável. *(HERO — Apostas 1+2; servidor + vereador)* |
| **M5 — "a porta da rua"** | F6 | portal cidadão white-label, e-SIC amplo, transparência, acompanhamento. *(cidadão + presidente da Mesa)* |
| **M6 — "remessa ao TCE"** | F5 | gera artefato (fixture), operador submete, obrigação cumprida em `aceita`. *(Aposta 3 — fecha a confiança operacional)* |

---

## Verificação (como provar cada fase ponta-a-ponta)

- **Backend:** `clj -M:test` (kaocha) verde; **import-lint + leak test 3-dim + cobertura de authz falham o build** em violação; migrations `migratus migrate` aplicam limpo; subir `docker-compose up` (Postgres·Valkey·MinIO·Keycloak) e exercitar o fluxo da fase via `http_server` real (Postgres real, sem fake-DB). Revisão por agentes `ecc` (`clojure-reviewer`·`database-reviewer`·`security-reviewer`) antes de cada commit de fase.
- **Motor (F2):** sair do atom — a suíte de 12 testes do motor passa a rodar contra Postgres; obrigação materializa→avalia→audita com fatos reais; `policy.check` nega por papel/relação/estado.
- **Front:** `python3 -m http.server 8755` valida as telas-fonte; após FE, paridade visual lado-a-lado com a tela HTML + `GUIDELINES-CHECKLIST.md` rodado nos **2 temas** (contraste em pixel composto); cliente tipado consome a API real da fatia.
- **Marcos:** cada M1–M6 é um roteiro de demo executável contra o ambiente real, não só testes.

---

## Decisões assumidas (não bloqueiam; registradas)

- **Track IA é plano próprio** — aqui entra só como dependência de interface (§22.3); seu build interno (Onda 0) é sessão dedicada. *(default por §22.2: satélite separado.)*
- **Plano em fases, não em meses** — dimensionamento de time/calendário é §18 + chat dedicado.
- **`admin_sistema`** (supratenant: provisionamento, billing, feature flags, grant de suporte, staging de migração) é transversal — materializa-se incrementalmente junto de F0 (provisionar ente) e F5/migração; não é fase própria do caminho crítico.
