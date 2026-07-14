# Onda D · Slice 3 — Cadastro de Vereadores (read-only master-detail)

> **Design doc.** Track FE (`docs/13`). Estabelece a **primeira borda HTTP do módulo `cadastros`** e porta a
> tela `cadastro-vereadores.html` (arquétipo lista+ficha / master-detail) contra dados reais. **Read-only** —
> as escritas (Novo/Editar/Registrar licença) são fatia seguinte, por decisão explícita (criar vereador toca
> `identidade`/CPF + provisionamento Keycloak, vertical própria com sua própria revisão de segurança).

Data: 2026-07-14 · Branch: `fe-19-cadastro-vereadores`

## 1. Objetivo e escopo

Materializar o cadastro institucional de vereadores como **master-detail somente-leitura**: lista à esquerda
(seleção, busca) + ficha do selecionado à direita (mandato, filiação, comissões). Ao fazê-lo, **nasce a borda
HTTP do `cadastros`** (hoje o módulo tem `db`/`models`/`relacoes`/`repositorio` mas **zero `wire/in`**),
seguindo a silhueta ADR-0001.

**IN:**
- `GET /cadastros/vereadores` (lista) + `GET /cadastros/vereadores/:id` (ficha) — leitura, gate papel
  `secretario`, tenant por RLS.
- Duas queries `db` novas: `listar` (vereador ⋈ mandato vigente ⋈ cargo-na-mesa) e `comissoes-do-vereador`
  (reverse de `membros`).
- Rota FE `(interno)/cadastros/vereadores` no app shell interno + entrada de nav "Vereadores".
- View-model puro + hooks + página master-detail (listbox navegável por teclado, seleção na URL).

**OUT (diferido, honesto na UI — não faked):**
- **Escritas** (Novo vereador / Editar cadastro / Registrar licença) → fatia seguinte.
- **Estatísticas** nº proposições / % presença → cross-módulo (legislativo/sessões); só a **contagem de
  comissões** (real, vinda da ficha) é exibida.
- **Contato institucional** (gabinete, e-mail) → não modelado em lugar nenhum do domínio.
- Filtro por legislatura/estado além da busca textual; paginação (cardinalidade limitada — uma Casa tem
  ~21–55 vereadores).

## 2. Backend — a borda do `cadastros` (silhueta ADR-0001)

Dois endpoints somente-leitura. Gate papel `secretario` (o mesmo de toda leitura interna; a linha real é a
autorização server-side — pode-se apertar para um papel de administração depois sem tocar o FE).

### 2.1 `GET /cadastros/vereadores` — lista (master)

Query `db/vereador/listar` nova: `vereador` LEFT JOIN `mandato` vigente (para `partido`, `estado`) LEFT JOIN
cargo-na-mesa (`comissao_cargo ⋈ comissao WHERE comissao.tipo='mesa'` e vigente). Retorna por vereador:
`id`, `nome`, `nome-parlamentar`, `partido` (nullable), `estado-mandato` (nullable se sem mandato),
`cargo-na-mesa` (nullable).

- **Mandato vigente** = `estado IN ('vigente','licenciado')` e dentro da vigência (mesma disciplina temporal
  de `relacoes/cadastro`). Um vereador sem mandato vigente ainda aparece na lista (nome só), `estado=nil`.
- **Busca**: fora da borda. **Decisão** — a lista é pequena (bounded), então o filtro é **client-side no
  view-model**; a borda devolve a lista completa e não recebe param `busca`. (Server-side ILIKE foi
  considerado e rejeitado por não valer a superfície nesta cardinalidade.)
- Sem paginação (bounded).

`wire/out/vereador.clj` → `VereadorLinhaOut` (schema fechado Malli). Ordenação: por `nome` asc.

### 2.2 `GET /cadastros/vereadores/:id` — ficha (detail)

Agrega numa **única tx** (disciplina de `ficha-completa-da-proposicao`):
- `vereador/buscar`
- **mandato vigente** (de `mandatos-do-vereador`, seleciona o vigente): `legislatura` (numero + ano-início/fim
  via `buscar-legislatura`), `posse` = `vigencia-inicio`, `filiação` = `partido`, `natureza`, `estado`,
  `cargo-na-mesa` (de `comissao_cargo` da Mesa vigente).
- **comissões do vereador** — query `db/comissao/comissoes-do-vereador` nova: `comissao_membro WHERE
  vereador_id = ?` (vigente) `⋈ comissao` (nome, tipo) LEFT JOIN `comissao_cargo` (mesmo vereador+comissão,
  vigente) para o rótulo de cargo (ex. "presidente"). Retorna lista `{nome, tipo, cargo?}`.

404 (`:nao-encontrado`) se o id não existe **ou** é de outro tenant (RLS já isola; o handler devolve 404 uniforme).

`wire/out/vereador.clj` → `VereadorFichaOut` (vereador + mandato-vigente + comissões). Schema fechado.

### 2.3 Silhueta e wiring

`wire/in/vereador.clj` (controllers) · `adapters/in` · `adapters/out` · rotas registradas no host
(`host/rotas` ou equivalente) · Component `RepoCadastros` já existe e é injetado. Codegen
`oplenario.codegen.*` estendido com os 2 tipos novos → `apps/frontend/src/lib/contrato-cadastros.gen.ts`
(novo arquivo de contrato do módulo, espelha `contrato-mesa`/`contrato-portal`).

**Sem migration** — todas as tabelas (`vereador`, `mandato`, `comissao`, `comissao_membro`, `comissao_cargo`)
já existem (migration 0010). Só queries de leitura novas.

## 3. Frontend — `(interno)/cadastros/vereadores`

### 3.1 View-model puro — `cadastro-vereadores-vista.ts`
- **Avatar** determinístico: iniciais (2 primeiras palavras do nome) + cor de uma paleta fixa (as `--jade`/
  `--cobalto`/`--telha`/… da tela-fonte), índice = hash estável do id/nome (`for` sobre charCodes) →
  mesma cor entre renders.
- **Estado → chip**: `vigente`→"Mandato ativo" (chip verde); `licenciado`→"Licença" (chip âmbar, `--aviso-texto`,
  ícone+texto nunca só cor); `cassado`/`renunciado`/`falecido`/`concluido`/nil → rótulo honesto neutro
  (catch-all fail-closed, mesma disciplina de `tramitacao-board-vista`).
- **Busca**: filtro client-side sobre a lista já buscada (nome/nome-parlamentar/partido, case-insensitive).
- **Seleção default**: primeira linha após ordenação, se nenhuma na URL.

### 3.2 Hooks
- `use-vereadores` — GET da lista; espelha `use-mesa`/`use-tramitacao-board` (loading/erro/dados, reset no
  `?token=`/tenant).
- `use-vereador-ficha` — GET da ficha, **fetch-on-select**; reseta estado ao trocar de id; degradação própria
  (erro da ficha não derruba a lista).

### 3.3 Página (master-detail)
- **Master**: `role="listbox"` de linhas `role="option"`, navegável por teclado (setas + Enter, `aria-current`
  na selecionada, `tabIndex` roving — mesmo padrão de abas da ficha-materia). Avatar + nome + `partido · cargo`
  + selo de licença quando aplicável.
- **Detail**: ficha do selecionado — topo (avatar grande, nome, cargo, chip de estado) + faixa de stats
  (comissões=real; proposições/presença=`EmBreve`) + blocos Mandato / Comissões (tags, presidente destacado) /
  Contato institucional (`EmBreve`).
- **Seleção na URL** (`?v=<id>`) — compartilhável e sobrevive a reload; ausência ⇒ primeira linha.
- **Ações** (`Novo vereador`, `Editar cadastro`, `Registrar licença`) → `EmBreve`/desabilitadas (fatia
  seguinte). "Ver proposições" → link para `/proposicoes` se barato; senão `EmBreve`.
- Nav "Vereadores" adicionada ao `(interno)/layout.tsx`.

## 4. Fluxo de dados

1. Página monta → `use-vereadores` busca a lista → render do master (loading/erro/vazio próprios).
2. Sem `?v=` ⇒ view-model seleciona a 1ª linha → `router.replace(?v=<id>)` (não empilha histórico).
3. Seleção (click/teclado) → atualiza `?v=` → `use-vereador-ficha` busca/renderiza a ficha.
4. Busca digitada → filtra o master client-side; se a seleção sair do filtro, mantém a ficha (não force-troca).

## 5. Tratamento de erro / estados de borda
- **Lista**: loading (skeleton/placeholder), erro (mensagem honesta + retry via re-render), **vazio** (nenhum
  vereador seedado → estado vazio explícito, não tela quebrada).
- **Ficha**: loading próprio; 404 → "vereador não encontrado" (não derruba o master); erro de rede → honesto.
- **Degradação por seção** (padrão do projeto): erro da ficha ≠ erro da lista.
- Sigilo/tenant: toda query filtra por RLS; id de outro tenant → 404 uniforme (não vaza existência).

## 6. Testes
- **Backend**: TDD (queries `listar`/`comissoes-do-vereador` + controllers + rotas). Revisão `ecc`
  **clojure + database + security** (isolamento de tenant nas 2 queries novas; 404 uniforme; gate `secretario`).
- **Frontend**: vitest do `cadastro-vereadores-vista.ts` (avatar determinístico, estado→chip catch-all,
  busca, seleção default). `tsc`/`eslint`/`next build` limpos.
- **Paridade visual** lado-a-lado com `cadastro-vereadores.html` nos 2 temas (`GUIDELINES-CHECKLIST.md`,
  contraste em pixel composto — atenção §5.1: chip de licença âmbar → `--aviso-texto`).
- **Verificação ao vivo** (claude-in-chrome, 2 temas) contra o stack real (Postgres + seed demo — já há
  vínculos de vereador no dev DB): lista renderiza vereadores reais, seleção troca a ficha, mandato/comissões
  reais aparecem, `EmBreve` honesto nas seções diferidas.

## 7. Deferrals explícitos (não relitigar nesta fatia)
- Escritas (criar/editar vereador, mandato, licença) — toca `identidade`/CPF (split-privilege) +
  provisionamento Keycloak; vertical própria.
- Estatísticas proposições/presença — cross-módulo (host injeta fato, precedente existe), quando pedido.
- Contato institucional (gabinete/e-mail) — feature de modelagem nova.
- Busca server-side / filtro por estado-legislatura / paginação — client-side basta na cardinalidade atual.

## 8. Riscos / notas
- **Primeira borda do `cadastros`**: fixar a silhueta ADR-0001 corretamente aqui vira o precedente das fatias
  de escrita seguintes — capricho no `wire/out`/adapters/controllers.
- **`mandato vigente`** é derivação temporal (estado + vigência) — reusar a disciplina de `relacoes/cadastro`,
  não reinventar o predicado.
- Um vereador pode ter **múltiplos mandatos** históricos; a ficha mostra o **vigente** (ou o mais recente se
  nenhum vigente — decisão: vigente-ou-nil, sem inventar).
</content>
</invoke>
