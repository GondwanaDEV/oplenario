# Onda C · Slice C2 — Pauta/convocação · Design

> Ref.: `docs/13-plano-track-fe.md` §11.3 (C2). Continuação da Onda C depois de C1 (home do vereador,
> merged `c911f4e`). Ferramenta do **secretário/servidor legislativo** (não do vereador) — sequenciada em
> Onda C só porque abastece com dados reais de sessão o C3 (cockpit de votação do vereador).

## Goal

Entregar `pauta-convocacao` como rota web interna: montar a pauta (Expediente + Ordem do Dia) de uma sessão
**já agendada** e visualizar o artefato de convocação derivado — sobre backend **inteiramente pronto**, sem
fan-out novo.

## Arquitetura

Composição pura no cliente sobre rotas já registradas em dois módulos (`paineis` e `sessoes`):

1. `GET /paineis/sli/sessoes` (papel `secretario`) — lista sessões do tenant; filtro client-side por
   `situacao === "agendada"`; auto-seleciona a mais próxima por `agendada-para`; permite trocar se houver mais
   de uma.
2. `GET /sessoes/:id` — detalhe pleno da sessão escolhida (`tipo-sessao`, `agendada-para`).
3. `GET /sessoes/:id/pauta` — itens atuais, agrupados por `fase` (expediente/ordem_do_dia).
4. `POST /sessoes/:id/pauta/itens` · `PATCH .../itens/:item-id` · `DELETE .../itens/:item-id` — adicionar,
   reordenar (setas ↑↓, CAS via `lock-version`), remover.
5. `GET /proposicoes` (já consumido na Onda B) — fonte de "prontas, fora da pauta": diff client-side entre a
   lista de proposições e os `proposicao-id` já referenciados por itens ativos da pauta.

Nenhuma rota nova, nenhuma migration nova. O trabalho é 100% frontend: view-models puros (seleção da sessão-
alvo, agrupamento por fase, diff pauta-vs-disponíveis) + a página.

## Escopo IN

- Rota `(interno)/pauta-convocacao`, guardada por papel `secretario` (mesmo padrão de auth-guard do shell
  interno já usado em `proposicoes`/`tramitacao`/`parecer`).
- Seleção da sessão-alvo (agendada, auto-escolhida pela mais próxima; troca manual se houver mais de uma).
- **Visualização** da pauta: grupos **Expediente** e **Ordem do Dia**, itens em ordem — **read-only** (ver
  correção abaixo).
- Rail "Prontas, fora da pauta": proposições do tenant que ainda não estão em nenhum item ativo da pauta
  (diff client-side, sem query nova) — mostrado como **lista informativa**, sem ação de adicionar (a ação
  exige escrita, ver correção abaixo).
- Painel de **convocação** como artefato ilha-papel, **client-composed**: tipo de sessão + data/hora (lidos
  da sessão) + contagem de itens por grupo. Nota de antecedência regimental como texto estático rotulado
  `[Regimento]` — sem cravar prazo real.

### Correção pós-investigação técnica (11/07, antes da implementação)

`GET /sessoes/:id/pauta` **não expõe `lock-version`** por design (`wire/out.clj` — "NAO expoe internos...
lock-version"), mas `PATCH`/`DELETE .../pauta/itens/:item-id` **exigem** `lock-version` no corpo para o CAS
otimista (`adapters/in/pauta.clj`). Sem o valor atual, o cliente não tem como montar um `PATCH`/`DELETE`
correto — a única saída seria adivinhar (ex. sempre `0`), o que quebra silenciosamente após a primeira
edição real. **Builder (adicionar/reordenar/remover item) sai do escopo desta fatia** e vira **read puro**
(a leitura literal de "Read puro, sem fan-out" em `docs/13` §11.3, mais estrita do que a leitura inicial
deste spec). O fix (`lock-version` em `PautaItemOut`) é backend de uma linha, mas é fan-out — não entra
aqui sem decisão explícita.

## Escopo OUT (registrado — deixado de fora nesta fatia, não descartado)

| Item da tela-fonte (`pauta-convocacao.html`) | Por que ficou fora | Reabre quando |
|---|---|---|
| **Criar/editar sessão** (form de data/hora/local, seletor Ordinária/Extraordinária/Solene editável) | A tela-fonte trata como form editável, mas `POST /sessoes` (agendar) já existe como fluxo próprio e o modelo `Sessao` carrega campos que o form simplificado não cobre (`delibera`, `transmite-publica`, `gera-ata-regimental`, `permite-voto-secreto`, `permite-modalidade-remota`, `modalidade`). Construir esse form nesta fatia seria puxar uma feature de admin de sessão não pedida por cliente. | Fatia própria, se um cliente pedir agendamento de sessão pela UI (hoje via seed/API). |
| **Campo "local" na convocação** | Não modelado em `Sessao` nem em nenhuma outra tabela hoje. Omitido — não inventado. | Quando o backend ganhar o campo (não há sinal de demanda hoje). |
| **Roster de ciência da convocação** (quem recebeu/confirmou/deu ciência do edital) | Sem tabela hoje. A `legislativo.ciencia_vereador` (C1) é ciência de **parecer publicado**, semânticamente distinta — não serve pra convocação sem um novo modelo (`sessoes.ciencia_convocacao` ou similar) + evento + fan-out de leitura. Fan-out especulativo, contra a regra-mãe do projeto. | Fatia própria (provável C2b ou junto com C3), se a Mesa precisar rastrear entrega/confirmação de convocação. |
| **Antecedência regimental calculada** (prazo real por tipo de sessão) | Conteúdo regulatório = `[GAP]` (mesma disciplina do resto do projeto — regimento interno varia por câmara e não foi inserido no motor/DSL ainda). | Quando o conteúdo regimental for cravado (mesmo gate do §22.7.9/§22.4). |
| **Drag-and-drop de reordenação** | A tela-fonte usa handles de arrastar; setas ↑↓ seriam o substituto acessível, mas ver linha abaixo — nem chega a entrar, pois reordenar em si saiu do escopo. | Junto com o builder, ver linha abaixo. |
| **Builder — adicionar/reordenar/remover item da pauta** | Achado técnico durante a investigação: `GET /sessoes/:id/pauta` não expõe `lock-version`, mas `PATCH`/`DELETE .../itens/:item-id` exigem `lock-version` pra CAS. Sem o valor atual, o cliente não monta um `PATCH`/`DELETE` correto. C2 vira **read puro** (pauta + convocação em modo visualização); o rail "prontas, fora da pauta" também perde a ação de adicionar (é a mesma escrita). | Fatia própria, depois que `PautaItemOut` ganhar `lock-version` (fix backend de uma linha, mas é fan-out — fora desta fatia sem decisão explícita). |

## Frontend — estrutura de arquivos (esperada)

- `src/app/(interno)/pauta-convocacao/page.tsx` — a página (seleção de sessão + builder + rail + convocação).
- `src/lib/pauta-convocacao-vista.ts` (+ `.test.ts`) — view-models puros: sessão-alvo (mais próxima agendada),
  agrupamento por fase, diff pauta-vs-disponíveis, contagens da convocação.
- `src/lib/use-sli-sessoes.ts` — hook de fetch de `/paineis/sli/sessoes`.
- `src/lib/use-pauta.ts` — hook de fetch/mutação de `/sessoes/:id/pauta` (add/reorder/remove).
- `src/lib/contrato-sessoes.gen.ts` / `contrato-paineis.gen.ts` (estender) — tipos novos consumidos.

## Verificação

`vitest` (view-models puros) + `tsc`/`eslint`/`next build` limpos; paridade visual lado-a-lado com
`pauta-convocacao.html` **recortada ao escopo IN** acima, nos 2 temas (`GUIDELINES-CHECKLIST.md`, contraste em
pixel composto); revisão `ecc` **react-reviewer + security-reviewer**; branch `fe-15-pauta-convocacao`.

## Decisões assumidas (lean; reversíveis)

1. Sessão-alvo auto-selecionada pela mais próxima `situacao === "agendada"`; sem sessão agendada → estado
   vazio honesto (não fabrica dado).
2. `POST /sessoes` (criar sessão) segue existindo e utilizável via API/seed — só não ganha UI nesta fatia.
3. Convocação é uma **leitura derivada**, não uma entidade persistida — nada de tabela/artefato novo aqui
   (diferente do artefato de publicação legislativo `§22.7.8`/F6c, que é intencionalmente persistido).
