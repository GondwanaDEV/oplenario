# CLAUDE.md — O Plenário · Discovery de Arquitetura

> **Nome do produto: O Plenário** — tagline de trabalho *"Onde a câmara acontece."*
> (provisório até confirmar domínio e marca — ver `docs/04-nome-e-marca.md`).

> **Este arquivo é lido automaticamente pelo Claude Code ao abrir a pasta.**
> Ele orienta como continuar o trabalho. Leitura obrigatória antes de qualquer resposta.
> A referência canônica de decisões é o `documento-mestre-camaras.md`. Em qualquer
> conflito entre o que se lembra de um chat antigo e o documento-mestre, **o documento prevalece.**

---

## 1. O que é este projeto

**O Plenário** é uma plataforma **SaaS de gestão pública para câmaras municipais brasileiras**,
tratando a câmara como **instituição** — nunca sistemas de gabinete de vereador (escopo de
gabinete está excluído desde a origem e não se reabre).

- **Beachhead:** Fortaleza / Nordeste (incumbentes do Sul/Sudeste têm baixa penetração — wedge geográfico real).
- **Estratégia (Rota D):** entrar com módulo legislativo como *wedge*, expandir para suite administrativa completa, eventualmente prefeituras. Alvo de IPO/exit R$ 1B+ em 8-12 anos.
- **Fundador/CTO:** Daouda Traore — decisor técnico único, 10+ anos de engenharia. Comunicação direta, intolerante a complexidade desnecessária e framing rebuscado.

**Três apostas de produto da V1:** (1) IA como copiloto legislativo; (2) experiência de
produto moderna para três públicos (servidor, vereador, cidadão); (3) confiança operacional
como diferencial comercial (migração como feature, SLA de janela de sessão, compliance TCE automático).

**Três públicos decisores em licitação**, cada um com porta de entrada própria: servidor
(avalia na POC — ganha com IA e UX), presidente da Mesa (aprova politicamente — ganha com
engajamento cidadão), jurídico/administrativo (avalia risco — ganha com confiança operacional).

---

## 2. Em que fase estamos

**Implementação.** As três trilhas de discovery estão **fechadas** e viraram fundo de consulta,
não frente de trabalho:

- **Arquitetura (§22) — COMPLETA.** Todas as subseções fechadas; o doc-mestre está na v1.39, com a
  §22 densa extraída para `arquitetura/` (7 arquivos, um por subseção). **Nenhum eixo de design de
  arquitetura aberto.** O granular que resta a reconciliar (mecânica fina do registry, formas
  descartadas no Eixo A de §22.7) vive em §22.7.4 e não bloqueia nada.
- **Produto/comercial — COMPLETA** (`produto/`, 113 features / 12 módulos).
- **Design system — COMPLETO** (`produto/design-system/`, 47 telas, 10 arquétipos provados, os 3
  públicos decisores cobertos). O que resta ali é **profundidade dentro de telas já cobertas**
  (`INVENTARIO §4`), não ausência — só puxar se um cliente pedir.

**O plano de execução da engenharia (`docs/11-plano-execucao-engenharia.md`) também acabou:
as 8 fases F0–F7 estão mergeadas em `main`** — F0 plataforma base · F1 cadastros+identidade ·
F2 resolvedor de fatos (o KEYSTONE) · F3 legislativo (8 eixos) · F4 sessões+tempo real (HERO) ·
F5 compliance/remessa · F6 transparência/participação · F7 painéis/observabilidade. 13 módulos,
61 migrations. **A track de frontend (`docs/13-plano-track-fe.md`) fechou as Ondas A–D**, com os
marcos MFE-1 a MFE-4 cumpridos.

**Marcos de valor demonstrável:** M1 (a Casa existe), M2 (compliance vivo), M3 (coração
legislativo), M5 (a porta da rua) e M6 (remessa ao TCE) **fechados**. **M4 (a sessão acontece) é o
único parcial** — sessão ao vivo, quórum, presença, tribuna e placar funcionam e estão portados; o
que falta é a ata-IA, que depende da Track IA (ver §3).

O detalhe rolling de cada fase vive nas memórias de sessão (`oplenario-f0-execucao` … `oplenario-f7-execucao`,
`oplenario-fe-execucao`, `oplenario-proxima-sessao`), não neste arquivo.

---

## 3. ⚠️ Estado do cursor + primeira ação

**Estado (19/07/2026):** o caminho crítico do plano de engenharia está cumprido. As frentes
abertas, em ordem de importância:

**1. Track IA (satélite §22.3) — não iniciada, e é o maior bloco restante.** Zero código. O lado
core do contrato já cumpre a sua metade: `sessoes/events/gravacao.clj` emite
`gravacao.segmento-captado` e `gravacao.segmento-vinculado` com payload validado — não há consumidor
do outro lado. **Trava a Aposta 1 inteira** (copiloto legislativo, ata-IA, resumo cidadão em
linguagem simples, busca semântica) e é o que impede M4 de fechar. Atenção: `prototipos/governanca-ia/`
é o arco de escolha de vendor de LLM, **não** é o satélite.

**2. Dois IdPs abertos.** O broker **gov.br** (cidadão) não tem uma linha — bloqueia os fluxos de
escrita autenticados do cidadão (a consulta pública não exige login, então M5 não está bloqueado).
O IdP do operador (`admin_sistema`) é um **stub de 3 linhas** — bloqueia o console supratenant.

**3. Onda E da track FE — MEDIDA em 10/09/2026, e a cauda NÃO é trabalho mecânico.** A descrição
anterior deste item ("~13 telas com design pronto e zero rota Next… trabalho mecânico, o design já foi
pago") estava **errada**, e errada de um jeito caro: mandaria 12 agentes portar telas que não têm API
para chamar. A medição (14 telas × medir + refutar; ledger `docs/16`, seção Onda E) devolveu:

| Veredito | Telas |
|---|---|
| **BLOQUEADO — o domínio não existe no backend** (8) | `transparencia-fiscal` · `console-operador` · `console-operador-tenant` · `livro-atas` · `audiencia-publica` · `julgamento-contas` · `trilha-auditoria` · `observabilidade-ia` |
| **PARCIAL** (4) | `dados-abertos` · `calendario` ✅ · `vereador-estatisticas` · `notificacoes` ✅ |
| **PORTÁVEL** (1) | `status` ✅ — e só porque o design é texto fixo, sem binding |
| **JÁ FEITA** (1) | `perfil-vereador-publico` (a lista anterior a dava como pendente) |

✅ = **entregue** na branch `onda-e-cauda`. As 3 fatias entregáveis foram feitas: `/status` (pública),
`/calendario` (interno, sessões + prazos de compliance) e o incremento de `/notificacoes`.

**O que sobra não é FE adiado, é domínio ausente** — e três dessas dependem de decisão, não de código:
- `transparencia-fiscal` — o **documento-mestre §289/§404 veta** produzir o dado fiscal: isso é do sistema
  contábil, e entra só a camada de publicação, por consumo. É `[GAP]` de conector externo (qual sistema,
  qual protocolo), da mesma família do layout SIM do TCE-CE. **Não é backlog de engenharia.**
- `console-operador` (+tenant) — `admin_sistema/diplomat/http/in.clj` tem **3 linhas e zero rotas**, e o
  IdP do operador é o stub do item 2 acima.
- `observabilidade-ia` — Track IA, item 1 acima.

E `vereador-estatisticas` esbarra no `proposicoes.estado` morto (4 dos 5 buckets).

**4. A verificação independente começou — CI destravado na infra, 3 testes `demo.*` faltam.**
*(atualizado 15/09/2026, tarde)* O repositório **tem remote** (`github.com/GondwanaDEV/oplenario`) e
`main` está publicada. O `.github/workflows/ci.yml` **executou** e o buraco de verificação começou a
fechar, em duas etapas de infra:
- **MinIO:** o namespace `minio/*` **sumiu do Docker Hub** (API de tags do Hub → 404 "object not found";
  `library/postgres` → 200). Não era rate-limit nem `:latest` faltando — a imagem não está mais lá.
  Consertado: `docker-compose.yml` puxa de `quay.io/minio/minio` (mesmo registro do keycloak).
- **Valkey:** o CI não subia o `valkey`; o teste do backplane (conecta em `redis://localhost:6379`)
  dava `Connection refused`. Consertado: `ci.yml` sobe `valkey` + readiness.
Com isso o CI passou de "morre em 15s sem puxar imagem" para **rodar a suíte inteira (2352 testes)**.
**Resta 1 frente para o verde total:** 3 erros pré-existentes em `oplenario.demo.*_test`
(`participacao_test`, `sessoes_test`) — dependem de `acervo/semear!` ter rodado antes no MESMO banco
(estado compartilhado que a suíte não garante, e que outro teste chega a apagar). Conserto correto =
tornar esses testes auto-suficientes / isolar a suíte, **não** afrouxar asserção. Detalhe e procedência
em `docs/16`, seção "Progressão do CI". (Os textos anteriores — "sem remote / nunca executou" e depois
"fixar tag + docker login" — estão superados por este.)

**Dívida técnica conhecida (não bloqueia):** assinatura ICP-Brasil ainda é `STUB-ICP-v0`; registro de
passkey depende de secure context (carry de ambiente); PWA cerimonial e app Flutter parqueados atrás
de gatilho de cliente validado; migratus deixa lock `-1` preso em crash de migration (limpar manual).

**`[GAP]` que dependem de informação externa, não de engenharia:** layout físico do arquivo SIM do
TCE-CE; prazos corridos vs. úteis (LAI 20+10, LGPD, ouvidoria 13.460); rito de sanção/veto por LOM;
layout do Diário Oficial; admissibilidade de emenda de plenário; e os outros 26 TCEs (forma validada
só contra o CE, rollout demand-pulled).

**Se voltar a fazer design:** o protocolo por tela segue valendo — linkar `produto/design-system/o-plenario/sistema/`,
usar arquétipo já provado, reusar receitas de `PADROES-DE-COMPOSICAO.md`, passar pelo
`GUIDELINES-CHECKLIST.md` como gate (esp. §5.1: branco-sobre-telha → `--telha-fundo`; fill de gráfico
escuro no escuro → clarear; âmbar → `--aviso-texto`), **medir AA em pixel composto, um tema por
chamada com flush**, e commitar uma tela por commit. Servidor: `python3 -m http.server 8755` na raiz;
galeria em `componentes.html`. **Fronteira do descarte:** só `produto/design-system/` é design — o
resto de `produto/` (01–14) é o input do processo e fica intacto.

---

## 4. Como trabalhamos (protocolo)

**Detalhe completo em `docs/01-metodologia.md`.** Resumo operacional:

- **Ferramentas fixas deste projeto (decisão do Daouda Traore, 20/06/2026):** todo trabalho de
  **discovery e engenharia** (pesquisa de mercado, code review, build, etc.) usa o plugin
  **`ecc`** (suas skills/subagents); o **design de UI/UX** usa **dois consultores complementares** (decisão Daouda Traore, 21/06/2026 — substitui o
  arranjo só-UI/UX-Pro-Max): **(a) `frontend-design`** (skill oficial Anthropic do repo `anthropics/skills`, via o
  plugin `example-skills`) como **diretor de arte + copy** — direção visual ousada/não-templated e microcopy
  (erro/empty/voz, crítico p/ e-SIC e LGPD), nas telas que decidem a compra; **(b) `ui-ux-pro-max`** como
  **biblioteca + UX + charts** — catálogo de paleta/fonte, 99 UX guidelines, 25 tipos de chart e consistência de
  sistema através das 113 features / 3 públicos. Os **artefatos seguem autorados à mão em `produto/design-system/`**
  (fonte de verdade). *(O `frontend-design` antigo de `claude-plugins-official` foi desabilitado p/ evitar colisão
  de nome; ambos os consultores ficam habilitados.)* **Usar sempre que houver trabalho dessas naturezas** — não é
  preferência pontual, é o trilho do projeto.
- **Português em toda sessão técnica.**
- **Um tópico macro por sessão**, fechado e consolidado no documento-mestre antes de seguir.
- **Eixo por eixo:** abrir opções por eixo → debater tradeoffs explicitamente → chegar a
  decisão confirmada → consolidar. **Não se relitiga item já fechado.**
- **Confirmação explícita antes de prosseguir:** protocolo "Confirmo" / "Confirma?". Daouda Traore
  intervém com correções cirúrgicas e espera incorporação imediata.
- **Bump de versão vs. patch:** correções dentro de uma sessão podem ser patch de mesma
  versão ou bump, conforme a natureza da mudança.
- **Viés forte por consistência disciplinar:** estender padrões existentes (DSL
  compartilhada, taxonomia de eventos, mecânica de registries) em vez de introduzir conceitos novos.
- **Escopo diferido por default:** itens sem requisito de cliente validado são parqueados,
  não pré-construídos. A régua das 4 perguntas (§15 do documento-mestre) é o filtro permanente.

---

## 5. Invariantes que NÃO podem driftar

Os 10 invariantes da §22.1 são lei estrutural de 5 anos. Para o trabalho de §22.7, dois
pesam mais:

- **Invariante 4 — regras de compliance são dados, não código.** TCE-CE na V1 é
  *configuração*, não branch de código. A DSL precisa permitir expressar todos os requisitos
  do TCE-CE como dado na V1, **sem refactor estrutural** para adicionar outros estados depois.
- **Disciplina 5 de §22.4.3 e §22.5.3 — motor declarativo compartilhado.** A DSL e a mecânica
  de avaliação são as **mesmas** entre tramitação (§22.4 eixo C), autorização (§22.5 eixo B),
  regras de plenário (§22.6 — quórum, regras de votação por matéria, tempos de tribuna) e
  agora compliance. **Não construir DSLs distintas.** Partir sempre do que já está fechado.

Princípio comercial que justifica rigor técnico aqui: **uma regra de compliance falhando em
runtime e fazendo um cliente perder janela de envio ao TCE é incidente inaceitável** — é o
que justifica type-checking estático no momento de salvar a regra (decisão do Eixo A).

---

## 6. Mapa da pasta

| Arquivo | Para quê |
|---|---|
| `apps/` | **Monorepo — os 3 componentes de código** (decisão Daouda Traore, 27/06/2026): `apps/backend/` (Clojure, o monólito modular — internamente em `STRUCTURE.md`), `apps/frontend/` (Next.js — porta o design-system), `apps/mobile/` (Flutter — diferido, PWA-first na V1). **Rodar/testar o backend é de dentro de `apps/backend/`** (ver memória `oplenario-rodar-local`). |
| `e2e/` | **Harness de browser-e2e (Playwright) do Portal do Cidadão** — projeto Node isolado (`package.json` próprio, só `@playwright/test`), fora de `apps/` porque não é um componente deployável: atravessa a stack inteira (frontend+backend+DB) já de pé. Comando canônico `./e2e/rodar.sh` (semeia via `seed_demo.clj` em container efêmero + roda o Playwright no container oficial). Nunca muta o mount vivo de `apps/frontend`. |
| `prototipos/` | **Referência histórica (não é produto)** — `motor-dsl/` (protótipo do avaliador da DSL) e `governanca-ia/` (arco da porta de IA). Superseded por `apps/backend/`; mantidos para consulta. Não buildados pelo CI. |
| `documento-mestre-camaras.md` | **Single source of truth.** Decisões consolidadas. Em conflito, prevalece. **A versão vive no cabeçalho + §24, nunca no nome do arquivo** (evita trocar referências a cada bump). |
| `arquitetura/` | **Parte do SSOT** — as subseções densas da §22, extraídas (v1.39), uma por subseção: `22-3-contrato-core-ia` · `22-4-dados-legislativo` · `22-5-auth` · `22-6-sessao-plenaria` · `22-7-motor-compliance` · `22-9-stack` · `22-10-monolito`. Cada arquivo abre com cabeçalho de SSOT; **versão do conjunto governada pelo §24 do doc-mestre**, nunca por arquivo. |
| `docs/adr/` | **ADRs** — decisões de arquitetura navegáveis (formato curto). **[ADR-0001](docs/adr/0001-estrutura-de-pastas-e-silhueta-de-modulo.md) = estrutura de pastas + silhueta de módulo** (autoridade da forma do `apps/backend/`: `wire/in`·`wire/out`, sem `port/`, recursos via Component, sem ORM). **Enforçada por máquina** — `estrutura_lint_test` (CI falha se `port/`/`schema/` voltarem) + `arquitetura_test` (import-lint §22.10). Toda decisão estrutural nova entra como ADR. |
| `docs/11-plano-execucao-engenharia.md` | **O plano de engenharia** — as 8 fases F0–F7, os marcos M1–M6 e os tracks paralelos (FE, IA). **F0–F7 estão todas mergeadas**; o que resta aberto está em §3 acima. Brief de origem em `docs/10`. |
| `docs/13-plano-track-fe.md` | **O plano da track de frontend** — Ondas A–E, marcos MFE-1..4. **A–D fechadas; a Onda E (cauda) não foi iniciada.** |
| `produto/` | Trilha de produto/comercial **completa** (01–14): JTBD, decomposição das 113 features / 12 módulos, completude. **`produto/design-system/`** = o design system (47 telas, `LINGUAGEM-VISUAL.md`, `PADROES-DE-COMPOSICAO.md`, `GUIDELINES-CHECKLIST.md`). |
| `docs/00-estado-e-roadmap.md` | **Histórico da fase de design** — estado do cursor e roadmap de §22.7. ⚠️ Não reflete a engenharia: lido isolado, dá a impressão de que o projeto ainda está em discovery de arquitetura. O estado vivo é o §3 deste arquivo. |
| `docs/01-metodologia.md` | Método de trabalho (eixo a eixo, confirmação, versionamento, escopo). |
| `docs/02-eixo-A-fechado-rascunho.md`, `docs/03-proxima-sessao-eixo-C.md` | Rascunhos de origem dos eixos de §22.7 — **consolidados no doc-mestre**; valor só arqueológico. |
| `docs/04-nome-e-marca.md` | Decisão de nome (**O Plenário**), tagline, e os checks pendentes (domínio + INPI). |
| `README.md` | Orientação geral da pasta (visão humana). |

---

## 7. Como rodar e verificar

- **Subir a stack:** `cd apps/backend && docker compose up -d --build` (migrate → app; postgres,
  valkey, minio; keycloak e mailpit atrás de `--profile auth`). Portas via `.env` — neste dev:
  frontend :3000, backend :8888, Postgres :5544, MinIO :9100. Detalhe na memória `oplenario-rodar-local`.
- **Mandato Docker (sem exceção):** nunca rodar `node`/`npx`/`clj` direto no host — tudo em container.
  Testes do backend rodam em container efêmero de Clojure; os do frontend, dentro de `oplenario-frontend-1`.
- **Nunca mutar um mount vivo.** O compose monta `../frontend:/app` para o `next dev`; um container
  efêmero que escreva ali derruba o frontend (já aconteceu). Monte só o necessário, prefira `:ro`,
  mantenha deps/caches em volume de container. Memória: `oplenario-e2e-container-guardrails`.
- **Browser-e2e:** `./e2e/rodar.sh` da raiz — semeia dado real via `seed_demo.clj` em containers
  efêmeros, espera a projeção do relay e roda o Playwright contra a stack de pé.
- **Uma branch por frente,** mergeada em `main` e fechada; a frente seguinte abre branch nova.
- **TDD por fatia** (red → green) e **revisão `ecc` antes do merge** — é o trilho que sustentou F0–F7.
