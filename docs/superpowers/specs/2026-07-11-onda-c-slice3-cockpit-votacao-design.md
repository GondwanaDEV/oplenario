# Onda C — Slice C3: cockpit ao vivo do vereador (voto + presença pelo próprio celular)

**Data:** 2026-07-11
**Fecha:** Marco MFE-3 — "o vereador no bolso" (`docs/13-plano-track-fe.md` §11, item C3)
**Contexto:** Onda C entrega o vereador como web responsiva (não PWA/Flutter, decisão 11/07/2026, §11 do plano). C1 (home fora de sessão) e C2 (pauta/convocação, read puro) já estão merged em `main`. C3 é a última fatia de domínio antes da assinatura em 2 toques (C4).

---

## 1. Objetivo

Durante uma sessão ao vivo, o vereador:
1. **Confirma a própria presença** pelo celular.
2. **Vota Sim/Não/Abstenção** na votação aberta, do próprio celular, e vê o placar oficial em tempo real (mesmo SSE/placar que a Mesa vê).

Segurança é o eixo quente: **voto é ato jurídico**. `vereador-id` nunca pode vir do corpo da requisição — sempre resolvido a partir do ator autenticado, mesmo padrão anti-forja já usado em `/meu/ciencias` (C1).

## 2. Escopo IN / OUT

**IN:**
- `POST /sessoes/:id/presenca/confirmar` — autoatendimento de presença (módulo `sessoes`, papel `vereador`).
- `POST /sessoes/:id/votacoes/:vid/meu-voto` — voto do vereador autenticado (módulo `legislativo`, papel `vereador`).
- Fato novo no registry do motor: `esta_presente_em` (hoje só as agregadoras `presentes_plenario`/`presentes_remoto` estão expostas à DSL).
- Policy fina via `motor/politica-dsl` — **primeira rota de produção** a usar esse caminho (hoje só provado em teste/`motor`).
- Nova fonte de presença `autoatendimento` (migration nova; não editar a migration existente — Migratus não re-roda ids já aplicados).
- FE: rota "Votar" em `(vereador)` (hoje tab `disabled`), reusando `use-plenario`/`placar-vista`; hooks de mutação `use-confirmar-presenca`/`use-meu-voto`; view-model puro do ciclo pendente→enviado→confirmado.

**OUT (por construção, não é lacuna):**
- Votação **modalidade secreta** pelo celular — a policy barra explicitamente (`recurso.modalidade != "secreta"`); voto secreto continua exclusivo da Mesa/terminal físico (reforço estrutural já existente: `votos_secretos` não tem coluna `vereador_id`).
- Web Push, offline shell, manifest/service worker (carry Onda D, §11.5).
- Presença remota "Nível 2" (integração de videoconferência) — fora de escopo desde §22.6.
- Correção/edição de presença pela própria Mesa não muda (rota `/sessoes/:id/presenca` existente continua exclusiva da Mesa).

## 3. Backend

### 3.1 Fato novo no registry (`sessoes/relacoes/presenca.clj`)

- Expor `esta-presente-em?` no mapa `relacoes` do módulo (hoje só os agregadores de quórum estão lá) sob o nome canônico **`esta_presente_em`**, assinatura `(tx, sessao-id, vereador-id, instante) -> Boolean`.
- Registrar a assinatura em `motor/catalogo.clj` (`FUNCOES-RELACAO`) — o assert de costura do boot (`RegistroFatos`) precisa casar nome↔assinatura ou falha fail-closed no start.

### 3.2 Policy fina do `meu-voto`

Expressão DSL (sintaxe confirmada em `motor/nucleo.clj`: `e`/`ou`/`nao`, comparadores `== != > >= < <=`, chamada de função, acesso a campo via `.`):

```
tem_mandato_vigente(ator.identidade, hoje())
e esta_presente_em(recurso.sessao_id, ator.identidade, hoje())
e recurso.estado == "aberta"
e recurso.modalidade != "secreta"
```

- `tem_mandato_vigente` já existe (`cadastros/relacoes/cadastro.clj`), reusado sem alteração.
- `recurso` = a votação carregada (mesmo shape usado por `sessao-autorizada`/`pode-dirigir-votacao?` hoje).
- Falha em qualquer condição → `autz/negar!` → **403 genérico** ("não autorizado a votar"), nunca detalha qual precondição falhou (mesma disciplina de não vazar motivo de autorização usada em outras rotas finas).

### 3.3 `POST /sessoes/:id/presenca/confirmar` (módulo `sessoes`, papel `vereador`)

- Injetar `resolver-vereador` em `sessoes-http/rotas` — mesmo padrão já usado para `legislativo-http/rotas` (closure `resolver-vereador-fn` em `rotas.clj:71`, hoje só passada para `legislativo`).
- Handler resolve `vereador-id` do ator (`resolver-vereador (:ente-id ator) (:identidade-id ator)`); `nil` → 404. **Nunca lê `vereador-id` do corpo.**
- Registra `presenca_evento` tipo `entrada` (ou `retorno` se o último evento foi `saida`), **`fonte "autoatendimento"`**.
- Idempotente por natureza: reconfirmar não corrompe nada — é sempre "o último evento por vereador" que conta.

**Migration nova** (não tocar `20260620000029-sessoes-presenca.up.sql`, já aplicada):
- Adiciona `'autoatendimento'` ao CHECK de `fonte`.
- Recria a coluna gerada `fonte_precedencia` com a nova ordem: `manual_secretaria=4, painel_eletronico=3, autoatendimento=2, ELSE 1` (cobre as duas `inferida_*`). Preserva a ordem relativa hoje existente (manual > painel > inferida) e insere o autoatendimento entre painel e inferida — a Mesa (manual ou painel físico) sempre pode sobrepor um autoatendimento do celular, mas o autoatendimento vale mais que uma simples inferência.

### 3.4 `POST /sessoes/:id/votacoes/:vid/meu-voto` (módulo `legislativo`, papel `vereador`)

- `vereador-id` resolvido do ator (mesmo padrão de `/meu/ciencias`), nunca do corpo.
- Autoriza via `autz/check! ator :votar votacao (motor/politica-dsl {...})` (§3.2) — negativa/exceção → 403.
- Reusa `controllers/registrar-voto` + `repo/registrar-voto!` **já existentes** (mesmo caminho nominal/simbólica que a Mesa usa) — **sem evento novo**; a única diferença frente à rota da Mesa é a origem (autoatendimento) e a authz fina adicional aplicada antes de delegar ao controller já existente.
- Resposta: mesmo formato de recibo que `voto-handler` já retorna (`adapters-out/voto->wire`), 201.

## 4. Frontend

- Ativa a aba **"Votar"** em `(vereador)/layout.tsx` (hoje `disabled`/`href: null`).
- Nova página do cockpit ao vivo reusa **`use-plenario`** (mesmo hook SSE do plenário) + **`placar-vista`** (mesmo reducer/view-model já usado no placar institucional da Mesa) — o vereador vê o placar oficial, não um estado paralelo.
- Hooks de mutação novos, seguindo o padrão de corrida já usado (`vivoRef`/`tokenAtualRef` como em `use-meu-painel`/`use-acusar-ciencia`):
  - `use-confirmar-presenca(token)` → `POST /api/sessoes/:id/presenca/confirmar`.
  - `use-meu-voto(token)` → `POST /api/sessoes/:id/votacoes/:vid/meu-voto`.
- View-model puro `meu-voto-vista.ts`:
  - Deriva se o botão de votar está habilitado: `votação aberta && presença confirmada && modalidade !== "secreta"`.
  - Ciclo **pendente → enviado → confirmado**: "confirmado" quando o SSE ecoa o próprio voto de volta no placar (reconciliação otimista, mesmo padrão já usado no editor/parecer — não um estado local desconectado do placar oficial).
  - Se modalidade é secreta: mostra estado explicativo ("voto secreto: só pelo terminal da Mesa"), não um botão desabilitado sem explicação.
- Erros 403 do backend viram mensagem genérica ("não é possível votar agora") — sem detalhar qual precondição falhou, espelhando a disciplina do backend.

## 5. Testes e verificação

- **Backend:** TDD (controllers de `confirmar-presenca`/`meu-voto` + fato novo no registry + teste de integração do `politica-dsl` composto, no padrão de `marco_m2_test.clj`) + revisão `ecc` **clojure-reviewer + security-reviewer + database-reviewer** (a migration nova, o CHECK, a coluna gerada).
- **Frontend:** vitest nos view-models puros (`meu-voto-vista.ts`) + `tsc`/`eslint`/`next build` limpos + paridade visual com `produto/design-system/o-plenario/telas/vereador-app.html` nos 2 temas (`GUIDELINES-CHECKLIST.md`) + revisão `ecc` **react-reviewer + security-reviewer**.
- **E2E vivo (Playwright):** Mesa abre votação → vereador confirma presença + vota pelo celular → placar da Mesa reflete em tempo real via SSE.

## 6. Fatias de implementação (branch → TDD → review → merge)

1. **Fato + policy:** `esta_presente_em` no registry + catálogo + teste de integração do `politica-dsl` composto (sem rota HTTP ainda).
2. **Backend `confirmar-presenca`:** migration da fonte nova + rota + controller + testes.
3. **Backend `meu-voto`:** rota + controller (reusa `registrar-voto`) + testes com a policy fina de ponta a ponta.
4. **Frontend:** hooks + view-model + página do cockpit + testes vitest + paridade visual.
5. **E2E + revisão de branch inteira** (padrão já usado em C1/C2: revisão por task + revisão final).

## 7. Riscos / decisões que podem mudar durante a implementação

- Ordem exata de precedência entre `autoatendimento` e as duas `inferida_*` pode ser ajustada se o `database-reviewer` apontar um problema concreto no desempate de quórum — a decisão registrada aqui (`autoatendimento=2`, acima de `inferida_*=1`) é a âncora, não uma garantia cega.
- Se `motor/politica-dsl` expuser alguma lacuna de sintaxe ao ser usado pela 1ª vez em produção (ex.: acesso a campo aninhado, tipo `instante`/`hoje()` no contexto de `recurso`), a fatia 1 isolada existe justamente para descobrir isso **antes** de cravar a rota HTTP.
