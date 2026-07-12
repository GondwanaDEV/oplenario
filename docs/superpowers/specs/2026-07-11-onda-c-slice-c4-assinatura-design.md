# Onda C · Slice C4 — Assinatura eletrônica em 2 toques (feature 7.3)

**Data:** 11/07/2026
**Track:** FE (`docs/13-plano-track-fe.md`, linha 210) — fecha o item C4 da Onda C.
**Tela-fonte:** `produto/design-system/o-plenario/telas/assinatura-2-toques.html`.

## 1. Objetivo

Dar ao vereador-relator um jeito de **assinar digitalmente** um parecer de comissão a partir do
próprio celular, em 2 toques (revisar → confirmar com biometria), produzindo uma assinatura real
(contra o `assinador` stub `'STUB-ICP-v0'`, o mesmo padrão de F6c) e uma trilha de auditoria
queryable (quem assinou, quando, com qual algoritmo). Cripto/biometria real (ICP-Brasil/WebAuthn)
é fast-follow da Onda D — já decidido no plano da track, não reaberto aqui.

## 2. Decisão central: assinar = emitir

O alvo funcional é o **parecer de comissão** (Onda B Slice 5, já mergeado). Hoje
`Repo/emitir-parecer!` promove o rascunho de texto a vigente + registra o voto do relator + tenta
transicionar o estado — tudo numa única transação — mas não produz nenhuma assinatura
criptográfica. O ato de "assinar em 2 toques" **é o próprio ato de emitir**: não há um estado novo,
nem uma tabela de "pendente de assinatura" separada do parecer. Toque 1 = revisar; toque 2 =
confirmar = dispara exatamente o `POST /pareceres/:id/emissao` que já existe, só que agora esse
endpoint também assina.

Consequência: o editor desktop `(interno)/parecer/[id]` (Slice 5, já em produção) **não muda de
fluxo** — continua com o botão único "Emitir parecer" — mas qualquer emissão feita por ele também
sai assinada, porque a assinatura vive no backend, não na UI. Nenhum teste E2E do editor desktop
deveria quebrar (a assinatura é campo novo, aditivo).

## 3. Dados

Migration nova adiciona 4 colunas **nullable** em `legislativo.parecer_texto_versao`:

| coluna | tipo | preenchida quando |
|---|---|---|
| `assinatura_algoritmo` | `text` | a versão foi assinada (hoje sempre `'STUB-ICP-v0'`) |
| `assinatura_b64` | `text` | idem |
| `assinado_por` | `uuid` | idem — identidade do ator que chamou a emissão (forward-ref, sem FK cross-schema, mesmo padrão de `artefato_publicacao.assinado-por`) |
| `assinado_em` | `timestamptz` | idem |

Versões de texto anteriores (já vigentes antes desta fatia, ou nunca promovidas) ficam com os 4
campos `NULL` — nunca assinamos retroativamente.

**Quando a assinatura é gravada:** apenas quando **há um rascunho sendo promovido a vigente nesta
chamada** de `emitir-parecer!`. O caminho normal (salvar rascunho → emitir) sempre passa por aqui.
O caso de borda "reemitir sem rascunho novo, só vigente pré-existente" (usado por retentativas de
transição de estado) **não** produz nova assinatura — evitar reassinar retroativamente uma versão
já commitada, com um novo timestamp que mentiria sobre quando o conteúdo foi de fato assinado.

**O que é assinado:** os bytes UTF-8 do `texto-inline` da versão promovida (é o único formato que o
editor de parecer produz hoje; `conteudo-uri`/objeto_store não é usado por pareceres nesta fatia —
YAGNI, sem necessidade de resolver bytes externos).

## 4. Backend

- `parecer-texto/promover!` (db) passa a aceitar os 4 campos de assinatura e os grava no MESMO
  `UPDATE` que promove rascunho→vigente (uma escrita, não duas).
- `Repo/emitir-parecer!` ganha um `assinador` (port `AssinadorICP`, já existente em
  `legislativo.components.assinador-icp`) nos args. Quando há rascunho a promover: computa
  `(assinador-icp/assinar assinador bytes-do-texto-inline)`, usa `:algoritmo`/`:assinatura-b64`
  resultantes, `assinado-por` = o mesmo `updated-by` (identidade do ator autenticado) que já flui
  hoje para essa função — **nenhum campo novo no corpo da requisição HTTP**.
- O diplomat (`emitir-parecer-handler`, `legislativo/diplomat/http/in.clj`) constrói
  `(assinador-icp/assinador-stub)` inline e passa para o controller — mesmo padrão já usado por
  `gerar-artefato-publicacao!` (porta passada como valor simples pelo caller; sem Component novo
  em `sistema.clj`, sem wiring de sistema).
- Leituras existentes (`buscar-parecer-para-editor`, usado pelo GET do editor tanto no desktop
  quanto na nova página do vereador) passam a devolver os 4 campos de assinatura da versão vigente.
  **Isso é a "trilha de auditoria real" desta fatia**: quem assinou e quando fica queryable e
  exibível via o read-model já existente — sem endpoint dedicado, sem sistema de audit-log
  genérico (feature 1.6 do catálogo de produto continua `[DIF]`, não é reaberta).

## 5. Frontend

- Página nova `apps/frontend/src/app/(vereador)/parecer/[id]/assinar/page.tsx`, porte de
  `assinatura-2-toques.html`: toque 1 mostra o parecer em modo leitura (ilha-papel com
  relatório/análise/voto + sumário "o que você está assinando"), reusando o mesmo GET do editor já
  usado pelo `(interno)/parecer/[id]`; toque 2 abre a sheet de confirmação ("Confirmar com a
  biometria" — **mock local**, sem WebAuthn/gov.br real) e, ao confirmar, dispara o
  `useEmitirParecer` já existente (`POST /pareceres/:id/emissao`).
- `(vereador)/vereador/page.tsx` (home do vereador) ganha a seção "Meus pareceres", populada a
  partir de `meusPareceres.aguardando` — já computado por `meu-painel-vista.ts` (Onda C1) mas hoje
  **não renderizado** (carry documentado explicitamente na fatia C1: "não mostra 'meus pareceres'
  no estado fora-de-sessão"). Cada item linka para a página de assinatura acima. Isso é o que torna
  a página de assinatura alcançável no produto — sem essa seção, a tela nova ficaria sem entrada.
- Se o parecer ainda não tiver rascunho nem vigente (relator ainda não escreveu nada), a página de
  assinatura mostra um estado vazio honesto ("ainda sem texto pronto para assinar") em vez da
  ilha-papel — tratado na própria página, sem precisar de um campo novo na listagem da home.

## 6. Fora de escopo (consistente com o plano da track)

- Biometria/WebAuthn real, certificado ICP-Brasil real (fast-follow Onda D, já decidido).
- Retrofit do editor desktop com o ritual de 2 toques (continua com o botão único).
- Sistema de audit-log genérico (feature 1.6, `[DIF]`).
- Assinatura de qualquer objeto além do parecer de comissão (ex.: emendas, outros pareceres sobre
  objeto-tipo diferente de `proposicao`) — YAGNI, mesmo recorte já usado pelo resto da Onda B
  Slice 5.

## 7. Verificação

- Backend: TDD (Clojure) cobrindo — assinatura gravada quando há promoção de rascunho; assinatura
  ausente quando não há promoção nesta chamada; campos surgindo na leitura do editor; migration
  aplicada limpo sobre dados existentes (colunas nullable, sem backfill). Revisão `ecc`
  clojure-reviewer + database-reviewer + security-reviewer.
- Frontend: `vitest` para o view-model/estado da página de assinatura + para a seção nova da home;
  `tsc`/`eslint`/`next build` limpos; paridade visual com `assinatura-2-toques.html` nos 2 temas
  (`GUIDELINES-CHECKLIST.md`, contraste medido em pixel composto); revisão `ecc` react-reviewer +
  security-reviewer; e2e vivo (fluxo completo: home do vereador → lista "Meus pareceres" → assinar
  → parecer emitido e assinado, contra o stack real).
