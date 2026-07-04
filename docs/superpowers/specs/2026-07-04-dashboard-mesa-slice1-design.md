# Spec — Track FE Slice 1: Dashboard da Mesa (Onda A1)

> Fatia concreta de `docs/13-plano-track-fe.md` §9. Estabelece o **App Shell interno**, as **primitivas de
> chart honestos** e a **decisão do codegen Malli→TS** que as fatias seguintes (Onda A2 em diante) reusam.
> Tela-fonte (design aprovado): `produto/design-system/o-plenario/telas/paineis-mesa.html`.

## 1. Contexto e decisão-chave

O `GET /paineis/mesa` (F7, já em `main`) compõe hoje: o card `compliance-tce` (opaco, do módulo `compliance`,
com placar de 5 fases + obrigações em aberto + remessas recentes) e três rollups **só de contagem**
(`tramitacao`, `pendencias`, `sessoes`) produzidos pelo próprio `paineis`. O campo `lacunas` já documenta
honestamente o que falta: `["presenca_agregada", "engajamento_cidadao"]`.

Comparado à tela-fonte aprovada, quatro blocos do mockup **não têm nenhuma rota que os alimente hoje**:
"o que a Casa entregou" (faixa de orgulho), "o que só a Mesa despacha" (fila de ações internas), a "lente
jurídico" (incidentes LGPD/grants) e o card "Próxima sessão" (quórum-de-ciência + checklist de prontidão).

Investigação de domínio (backend) mostrou que esses gaps têm custo MUITO desigual:

| Facet | Custo | O que existe hoje |
|---|---|---|
| Presença agregada (% presença média) | **Barato** — query nova sobre dado existente | `presenca_evento` por sessão + `membros-da-casa` (denominador) já resolvíveis; falta só agregação cross-sessão |
| % e-SIC/LAI no prazo | **Barato** — query nova, já era intenção documentada | `participacao/relacoes.clj` já diz "diferido, será read-model do próprio participacao" |
| Fila "designar relator" | **Barato** — entidade já existe por completo | `parecer.relator_id`, evento `RelatorDesignado`, `designar-relator!` já implementados |
| Ciência de convocação (quórum-de-ciência) | **Caro** — não existe NADA; precisa nova tabela+evento+regra de negócio (quem confirma, como) | zero ocorrências no código |
| Assinatura do autógrafo | **Caro** — a entidade existe, mas a assinatura ICP-Brasil é `[GAP]` documentado, track cripto/NFR à parte | `legislativo.autografo.enviado_em` existe; sem coluna de assinatura |
| Incidente LGPD + grant de acesso de suporte | **Caro** — não existe absolutamente nada (nenhuma tabela); seria um domínio de segurança inteiro novo | nada em `compliance`/`identidade`/`cadastros` |

**Decisão (confirmada com o Daouda):** construir agora os 3 baratos (entram no `MesaOut` desta fatia); os 3
caros ficam com o **layout idêntico ao design aprovado**, em estado honesto "indisponível/em breve" — carry
documentado para um eixo de design próprio (não inventamos tabela/regra de negócio nova dentro de uma fatia
de FE). Fidelidade visual ao design aprovado é mandatória em tudo o que TEM dado; o que não tem dado não some
da tela, mas também não fabrica número.

## 2. Arquitetura

### 2.1 Backend — 3 novos read-models compostos por inversão de dependência

Mesmo padrão já provado do `compliance-tce` em `paineis.mesa-handler`/`adapters/out/mesa.clj`: cada módulo
dono expõe sua própria função pura (model → wire), o host injeta como fn no `paineis`, que a chama com
`catch Throwable` → sentinel de degradação por card. `paineis` nunca importa os módulos donos.

- **`sessoes`**: nova função `presenca-agregada` — agrega `presenca_evento` cross-sessão, com janela = **a
  sessão legislativa corrente** (`sessao-legislativa-id`, o mesmo agrupamento natural já usado em
  `SessaoOut`; evita inventar uma janela de tempo arbitrária), usando `cadastros.relacoes/membros-da-casa`
  como denominador. Novo `wire/out` próprio (ex. `sessoes.wire.out.presenca-resumo/PresencaResumoOut`).
- **`participacao`**: nova função de cumprimento de prazo e-SIC/LAI — histórico cumprido-no-prazo vs.
  vencido (usa o mesmo substrato de `prazo_ativo` já existente, agregado). Novo `wire/out` próprio.
- **`legislativo`**: nova query "proposições em comissão sem relator designado" (join simples sobre
  `parecer`/proposição em estado `em_comissao`). Novo `wire/out` próprio.
- **`paineis`**: `MesaOut` ganha 3 chaves novas — `presenca-resumo`, `esic-cumprimento`,
  `relatores-pendentes` — cada uma um mapa opaco embutido verbatim (mesmo racional documentado em
  `wire/out/mesa.clj` para `compliance-tce`: `paineis` não redeclara a forma de outro módulo). `lacunas`
  passa a listar só o que genuinamente falta: `["ciencia_convocacao", "assinatura_autografo",
  "incidente_grant_lgpd"]`.
- Cada uma das 3 novas fns é chamada com o mesmo `catch Throwable` → sentinel `{:indisponivel true}` por
  card, preservando a disciplina de degradação já estabelecida.

### 2.2 Frontend — App Shell interno (novo)

Primeira vez que existe layout/navegação compartilhado autenticado no FE (hoje só existe a página avulsa do
plenário).

- `AuthContext`/`AuthProvider` novo: extrai o padrão de token dev (`?token=`/`NEXT_PUBLIC_DEV_TOKEN`, guard
  de produção que já existe em `sessoes/[id]/plenario/page.tsx`) para um contexto compartilhado, reusável
  por qualquer página interna.
- Layout raiz autenticado (`src/app/(interno)/layout.tsx` ou equivalente): barra `.topo` (marca + área-tag +
  sino de notificações + tema-btn + data-pill + quem-mesa) reusando `chassi.css`/`tokens.css` verbatim —
  igual ao padrão já usado na página do plenário.
- **Sem nav lateral com links para páginas que ainda não existem** — só a barra institucional. Itens de
  navegação entram organicamente conforme as próximas fatias (A2 em diante) nascem; não inventamos chrome
  para página inexistente.
- `/sessoes/[id]/plenario` **fica fora do shell por agora** — migrá-la para dentro do App Shell é um carry
  explícito para uma fatia futura, não desta (evita mexer em código já testado e mergeado sem necessidade).

### 2.3 Composição de dado (Fork 1 — decidido)

O dashboard continua **1 request** (`GET /paineis/mesa`); as 3 chaves novas chegam no mesmo JSON. Nenhuma
rota HTTP nova exposta ao FE além da que já existe.

### 2.4 Contrato TS (Fork 2 — decidido)

Os contratos **novos** desta fatia (o `MesaOut` estendido + os 3 `wire/out` novos de sessões/participação/
legislativo) são gerados via `oplenario.codegen.malli-ts` (já existe, F1.5) — nunca driftam do Malli que
valida a resposta no servidor. O `contrato.ts` hand-rolled existente (sessão/pauta/eventos/votação)
**não é tocado** nesta fatia — migração incremental, conforme a própria recomendação do `docs/13`.

## 3. Componentes (FE)

- `page.tsx` (rota `/paineis/mesa`): busca `MesaOut` (1 request, autenticado via `AuthContext`), deriva o
  view-model e renderiza.
- **View-model puro `mesa-vista.ts`** (mesmo padrão de `placar-vista.ts`): traduz `MesaOut` → o que cada
  seção mostra, incluindo a lógica dos 3 estados por seção (`disponivel | indisponivel | em-breve`).
  Testável sem DOM/rede.
- Componentes finos por bloco (sem lógica própria, só mapeamento do view-model):
  - `SaudeInstitucional` — hero: placar de 5 fases (`compliance-tce.resumo`) + lista real de obrigações em
    aberto (`compliance-tce.em-aberto`, ordenada por `vence-em`) + anel de prazo da próxima remessa (se
    houver uma em `remessas-recentes` com estado em curso).
  - `OQueVence` — une `compliance-tce.em-aberto` + itens de `/paineis/pendencias` (chamada adicional, já
    prevista na decisão "enriquecida"), ordenado por `vence-em`.
  - `PipelineLegislativo` — board por estágio com itens reais de `/paineis/tramitacao` (ementa, urn-lex,
    estado, "parado há X dias" derivado de `transicionou-em` no FE).
  - `DespachosDaMesa` — fila: item real "designar relator" (de `relatores-pendentes`, o único dos 3 baratos
    que é item-level) + os demais itens do mockup original (despachar distribuição, assinar autógrafo,
    revisar ata) em estado `em-breve` honesto — nenhum foi investigado/decidido nesta rodada além do
    relator; ficam fora do escopo desta fatia por igual, sem exceção.
  - `OrgulhoInstitucional` — presença agregada (`presenca-resumo`) + % e-SIC no prazo
    (`esic-cumprimento`) + total de proposições em tramitação (`tramitacao.total`); "sessões transmitidas
    ao vivo" fica `em-breve` (não investigado nesta rodada — não há sinal confirmado de tracking de
    transmissão real vs. `transmite-publica`).
  - `ProximaSessaoRail` — o que dá para montar sem ciência-de-convocação: tipo/data de uma sessão
    `agendada` (via `/paineis/sli/sessoes`, já decidido "enriquecida"); quórum-de-ciência e checklist de
    prontidão ficam `em-breve`.
  - `LenteJuridico` — estado `em-breve` apenas (sem incidente/grant nenhum implementado).
- **Primitivas de chart hand-rolled em SVG** (mesmo padrão do `Hemiciclo` já existente em
  `sessoes/[id]/plenario`): `AnelPrazo` (countdown circular), `BarraSegmentada` (distribuição por estágio),
  `TabuleiroEstagios` (board de 5 colunas). Sem lib nova, sem donut/KPI-card inventado (disciplina do skill
  `dataviz`).

## 4. Fluxo de dado

`GET /paineis/mesa` (auth: papel `secretario`, dev-token) + `GET /paineis/tramitacao` + `GET
/paineis/pendencias` + `GET /paineis/sli/sessoes` (as 3 chamadas "enriquecidas", em paralelo) → `mesa-
vista.ts` combina tudo num objeto de view por seção → componentes renderizam. Nenhum SSE (read-only,
confirma o plano). Erro de auth/rede na chamada principal (`/paineis/mesa`) = tela de erro de página inteira
(mesmo padrão da página do plenário); falha isolada de uma das 3 chamadas enriquecidas = a seção
correspondente cai para a versão "só contagem" dessa seção (ex.: `/paineis/tramitacao` falha → pipeline
mostra só a barra segmentada de `tramitacao.por-estado`, sem itens).

## 5. Erros / estados vazios

- Falha de card individual composto no `MesaOut` (qualquer um dos 4 read-models opacos): sentinel
  `{indisponivel:true}` → texto "indisponível no momento" naquele bloco só (backend já garante isso).
- Falha de uma das 3 chamadas enriquecidas (`/paineis/tramitacao`, `/paineis/pendencias`,
  `/paineis/sli/sessoes`): degrada para a versão "só contagem" daquela seção (dado já presente no
  `MesaOut` principal), não derruba a página.
- Seções sem rota nenhuma (ciência-convocação, assinatura-autógrafo, incidente/grant-LGPD, sessões-ao-vivo-
  contador): layout/CSS idênticos ao design aprovado, conteúdo = estado "em breve" honesto — visualmente
  distinto de uma falha (não é erro, é lacuna conhecida e documentada).
- Token de dev ausente/inválido ou papel errado: mesmo guard já usado na página do plenário (throw após
  hooks, bloqueia entrada via querystring em produção).

## 6. Testes

- **Backend**: TDD red→green por read-model novo (3 módulos: sessões, participação, legislativo) + teste de
  composição em `paineis` (rollup com card indisponível simulado, mesmo padrão do teste existente de
  `compliance-tce` degradado). Reviews `ecc` clojure-reviewer + database-reviewer + security-reviewer por
  fatia backend.
- **Frontend**: `mesa-vista.ts` testado puro (vitest) cobrindo os 3 estados por seção
  (`disponivel|indisponivel|em-breve`) e a combinação das 4 chamadas (incl. falha parcial). Componentes
  finos sem lógica própria (só mapeamento). `tsc` + `eslint` + `next build` limpos.
- **Paridade visual**: lado a lado com `paineis-mesa.html` nos 2 temas + `GUIDELINES-CHECKLIST.md` (gate
  obrigatório, mesma disciplina de toda fatia anterior — contraste AA medido em pixel composto).
- **Codegen**: contratos novos gerados via `oplenario.codegen.malli-ts`; `contrato.ts` existente não é
  tocado nesta fatia.
- **Containers locais**: `docker compose up` (postgres/valkey/minio já sobem hoje; `app` precisa reiniciar)
  para rodar a suíte de integração e o e2e visual real — mesmo fluxo de sempre (`apps/backend/`, seed via
  `demo/seed_demo.clj`).
- **E2E vivo**: screenshot/Playwright dos 2 temas contra a API real (Postgres via docker, dev-token),
  confirmando que os estados `disponivel`/`indisponivel`/`em-breve` renderizam como esperado.

## 7. Escopo explicitamente fora desta fatia (carries)

- Migração de `/sessoes/[id]/plenario` para dentro do App Shell.
- Ciência de convocação (nova tabela+evento+regra de negócio — precisa de eixo de design próprio: quem
  confirma, como, precisa de auth?).
- Assinatura ICP-Brasil do autógrafo (já é `[GAP]` documentado, track cripto/NFR).
- Incidente LGPD + grant de acesso de suporte (domínio de segurança inteiro novo; hoje só existe como
  *design* em `console-operador-tenant`, nunca como backend).
- Nav lateral / navegação entre múltiplas páginas internas (não há para onde apontar ainda).
- Ações da barra de comando do mockup (Convocar sessão / Publicar pauta / Exportar relatório) — a fatia é
  **read-only**, confirmado pelo plano; essas viram fatias de escrita futuras se e quando fizerem sentido.

## 8. Verificação de setup

Modelo/effort ativo nesta sessão: Sonnet 5, xhigh. Consistente com a tier "mecânico" de `docs/11`
(trabalho de composição/porte, não é keystone de arquitetura) — nenhum ajuste necessário antes de seguir.
