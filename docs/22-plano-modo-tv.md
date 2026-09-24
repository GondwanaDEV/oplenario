# 22 — Plano: Modo TV da sessão (painel público do plenário)

> Pedido de cliente (Didi, 23/09/2026), repassado por Daouda. Plano aprovado em 23/09/2026 com as três
> recomendações aceitas (ver "Decisões"). Branch: `feat/modo-tv`.

## O pedido

"TV de chamada" para o público presente no plenário: um botão que abre um **modo TV em tela cheia** (não o
navegador com barra), no estilo painel de telejornal — relógio, rodapé com o que está acontecendo, e o miolo
mudando com a evolução da sessão: número da sessão, pauta do dia e, na votação, quantos vereadores podem
votar, quantos votaram, o total de votos e se foi aprovado ou não.

## O que já existe (não reconstruir)

| Pedido | Onde já está |
|---|---|
| Estado ao vivo da sessão | `usePlenario` (SSE `GET /sessoes/:id/plenario`, reducer `plenario-reducer.ts`) |
| Quórum (presentes de membros da Casa) | `vistaDoQuorum` / `GET /sessoes/:id/quorum` |
| Votação: Sim/Não/Abst., faltam, resultado | `placar` + `derivarPlacar` (sigilo §22.6 da secreta já tratado) |
| Nome do vereador (voto, tribuna) | `GET /sessoes/:id/composicao` → `estado.composicao` / `identidadeDe` |
| Matéria em votação (sigla, nº, ementa) | `placar.proposicao` (hidratação `comVotacao`) + `tituloObjetoVotacao` |
| Tribuna + cronômetro | `oradorAtual`, `marcosCronometro`, `lib/cronometro` |
| Pauta | `usePauta` — **mas o item de proposição só tem `proposicaoId`** (ver Fatia 1) |
| Arquétipo visual "display/projeção" | `produto/design-system/o-plenario/telas/telao-votacao.html` (escuro, legível a 15 m) — cobre só a votação |

O painel `/sessoes/[id]/plenario` já mostra esses dados, mas é página comum (topo, toggle de tema, layout de
cabine) — não é TV. A TV é uma **nova superfície** sobre os mesmos dados.

## Decisões (aprovadas)

1. **Quem abre a TV: o operador logado** (Mesa/secretaria). O botão abre a TV numa janela nova, que herda a
   sessão (cookie same-origin). O SSE já autoriza qualquer ator autenticado do tenant em sessão não-secreta
   (`pode-assistir-plenario?`). **Não** se abre SSE anônimo — isso esbarra no carry de segurança MAJOR-1
   (sem teto de conexões SSE, `tempo_real/diplomat/sse.clj`) e seria frente própria.
2. **Sem partido nesta versão.** `ComposicaoMembroOut` só traz `nomeParlamentar`/`cargoMesa`; o mockup do
   telão mostra partido, mas não se alarga o contrato por isso agora. Sem dado falso: some o campo.
3. **Sessão secreta:** a TV mostra "Sessão reservada" (o servidor já recusa a conexão com 403) em vez de erro.

## Fatias

### Fatia 0 — Design: `telas/modo-tv.html`

Estende o arquétipo "display" do telão (sempre escuro — exceção dual-theme já justificada para projeção).
Canvas de referência 1920×1080, escala por `clamp()`/`vw` de 720p a 4K. Moldura fixa + miolo por fase:

- **Moldura:** barra superior (brasão, nome da Casa, "Sessão Ordinária nº 15", selo AO VIVO/SUSPENSA/
  ENCERRADA, **relógio HH:MM grande** e tempo de sessão); **rodapé tipo telejornal** com letreiro rolando.
- **Miolo por fase** (derivado do estado, nunca escolhido à mão):
  1. **Abertura / aguardando** — número da sessão, data, pauta do dia.
  2. **Em curso** — pauta (item em votação destacado) + orador na tribuna com cronômetro + quórum.
  3. **Votação aberta** — matéria (sigla/nº/ementa), presentes (podem votar), votaram, faltam, placar
     Sim/Não/Abst.; na nominal, nomes de quem votou; na secreta, só o contador.
  4. **Resultado** — "APROVADA"/"REJEITADA" em tela inteira com o placar final, ~8 s, depois volta.
  5. **Suspensa / encerrada / reservada.**

Gate: `GUIDELINES-CHECKLIST.md` (AA medido em pixel composto; voto por ícone+cor+palavra, nunca só cor;
`prefers-reduced-motion` congela o letreiro e o pulso). Consultores do CLAUDE.md §4 (`frontend-design` +
`ui-ux-pro-max`). Uma tela, um commit.

### Fatia 1 — Backend: pauta com o resumo da proposição

Hoje `PautaItemOut` de `tipo-item = proposicao` só traz `proposicao-id` — na TV a pauta viraria uma lista de
"Proposição · matéria vinculada". Adicionar ao item o campo opcional `proposicao`
(`{tipo, ano, sequencial, ementa}`, o mesmo molde de `ProposicaoResumoObjetoVotacaoOut`).

- `sessoes` não importa `legislativo` (§22.10): o **host** (`rotas.clj`) injeta
  `resumir-proposicoes-fn` `(fn [ente-id ids] -> {id resumo})` — **em lote** (uma tx para a pauta inteira,
  mesmo racional de `resolver-comissoes`), irmão de `roster-da-casa-fn`.
- `pauta-handler` resolve os ids da pauta e passa o mapa ao `adapters-out/pauta->wire`; item sem resumo
  (proposição de outro tenant/inexistente) sai sem o campo — nunca inventado.
- Codegen: `gerar_sessoes` regenera `PautaItemOut` (+ `contrato-sessoes.gen.ts`).
- Testes: unit do adapters/out; http-in com Repo fake (item com/sem resumo); integração do lote no Repo
  de legislativo.

### Fatia 2 — View-models puros (testados, sem React)

- `lib/tv-vista.ts` — `faseDaTv(estado, pauta, agora)` → `abertura | em-curso | votacao | resultado |
  suspensa | encerrada`, e as vistas de cada fase (reusa `derivarPlacar`, `vistaDoQuorum`,
  `tituloObjetoVotacao`, `identidadeDe`, `formatarNumeroProposicao`).
- `lib/tv-letreiro.ts` — frases do rodapé derivadas do **estado atual** (não de um histórico: o canal
  replaya desde `id: 1` e uma página recarregada não pode narrar o passado como se fosse agora):
  "Quórum: 18 de 21 presentes", "Na tribuna: Ver. Fulana — 03:12", "Em votação: PL 7/2026 — faltam 3",
  "Última votação: PL 5/2026 aprovada por 14 × 2", "A seguir na pauta: …".
- Splash de resultado: só dispara na **transição observada ao vivo** (`encerrada: false → true` depois da
  hidratação), nunca no replay de uma votação já encerrada ao abrir a TV.

### Fatia 3 — A rota `/sessoes/[id]/tv`

- Mesmo `AuthProvider` + `usePlenario(id, token, { comQuorum, comVotacao })` + `usePauta` do painel.
- Overlay inicial "Clique para entrar em tela cheia" (a Fullscreen API exige gesto do usuário na própria
  janela); depois `requestFullscreen()`, cursor escondido após inatividade, **Screen Wake Lock** (a TV não
  apaga) com re-aquisição no `visibilitychange`.
- Reconexão já vem do `usePlenario`; selo "Reconectando…" no topo em vez de congelar números.
- CSS próprio (`tv.css`), sem `.topo`/toggle de tema.

### Fatia 4 — Botão "Modo TV"

No cockpit da Mesa (`/sessoes/[id]/conduzir`) e no painel (`/sessoes/[id]/plenario`): abre
`/sessoes/[id]/tv` em janela nova (`window.open`, `noopener`). Fluxo do operador: arrastar a janela para a
TV (HDMI em tela estendida) e clicar uma vez.

### Fatia 5 — Verificação

- vitest dos view-models + componentes (fases, sigilo da secreta, splash só em transição, sem-nome →
  rótulo neutro, reduced-motion).
- Playwright: screenshot 1920×1080 de cada fase com SSE simulado (harness no scratchpad com o CSS real).
- Gate completo: `tsc`, `eslint`, `vitest`, CI com Postgres real.
- Ao vivo na demo (sessões `…0210/0211/0212` do seed) após deploy.

## Fora de escopo (deliberado)

- TV pública sem login (depende do carry MAJOR-1 de SSE).
- Partido do vereador na composição.
- Escolher o monitor automaticamente (Window Management API, só Chromium) — o operador arrasta a janela.
- Personalização por Casa (logo próprio, cores): a TV usa o brasão/nome já existentes.
