# Spec — Track FE · Onda B · Slice 1 · Proposições (lista)

> **Fatia FE** do `docs/13-plano-track-fe.md` (Onda B — "o fluxo diário do servidor", primeira vertical).
> Primeiro passo do **Marco MFE-2** ("o servidor trabalha o dia": protocolar→tramitar→ficha→parecer navegável).
> Fonte de design: `produto/design-system/o-plenario/telas/proposicoes.html` (verbatim como referência visual).
> Backend: **borda nova** — hoje `legislativo/diplomat/http/in.clj` só expõe as rotas de votação ao vivo (F4);
> não há nenhum endpoint de listagem/CRUD de proposição. Esta fatia abre a **primeira vertical de leitura**
> do módulo `legislativo` para o servidor.
>
> **Data:** 2026-07-05 · **Branch:** a criar (`fe-7-proposicoes-lista`, off `main`) · **Escopo aprovado:** só leitura.

---

## 1. Objetivo e escopo

Dar ao servidor uma lista real, filtrável, buscável e paginada de proposições do seu ente — o "cavalo de
batalha" do trabalho diário (achar, filtrar, abrir uma matéria). **Esta fatia é só leitura**: os fluxos de
escrita da tela-fonte (protocolar, arquivar, distribuir a comissão, exportar) pertencem a fatias seguintes
já reservadas pelo plano (`editor-proposicao` = Slice 2) ou foram descartados desta tela por não terem dono
ainda. Mesma disciplina de "em-breve honesto" já usada no Dashboard da Mesa (A1) e no Portal do Cidadão (A2):
nunca simular uma ação que não existe.

**No escopo (Slice 1):**
1. **Nova borda HTTP de leitura em `legislativo`** — `GET /legislativo/proposicoes`, com filtro por texto
   (número/ementa/autor), espécie, situação, autor e ano; ordenação; paginação offset/limit.
2. **Nova página interna `(interno)/proposicoes`** — tabela filtrável/ordenável/paginada, chip de situação +
   mini-azulejo de tramitação por linha (reaproveita o view-model já existente da A2), estado vazio.
3. **Primeira navegação real do App Shell interno** — hoje só existe "Painéis da Mesa"; esta fatia adiciona
   um nav de 2 itens no `TopoInterno` (Painéis da Mesa ⇄ Proposições).

**Fora do escopo (Slice 1) — deferido, com estado desabilitado/`EmBreve` honesto onde a tela-fonte os mostra:**
- **"Nova proposição"** (header) — leva ao editor, que é a Slice 2. Botão desabilitado com indicação "em breve".
- **"Exportar"** (header) e **"Exportar seleção"** (barra de ações em massa) — nenhuma rota de exportação existe.
- **Barra de ações em massa** (Arquivar / Distribuir a comissão) — são mutações de tramitação sem dono nesta
  fatia; a barra de seleção **não é renderizada** nesta fatia (diferente de deixar 3 botões mortos visíveis).
- **Ação "abrir ficha"** por linha — `ficha-materia` (interna) é uma fatia posterior à do editor; o ícone fica
  desabilitado com tooltip "Ficha em breve".
- **Busca inteligente (IA)** — Track IA é satélite (`docs/13` §8). A tela-fonte já modela o estado degradado
  (`ia-off`, "Busca inteligente indisponível... mostrando correspondências exatas") — esse é o **único estado
  real** até a Track IA existir; a busca desta fatia é sempre correspondência exata (sem ranqueamento).

## 2. Backend — nova borda de leitura em `legislativo`

Silhueta ADR-0001, mesmo padrão da vertical de votação (`legislativo/diplomat/http/in.clj`) já em `main`.

| Camada | Arquivo | Responsabilidade |
|---|---|---|
| `wire/in` | `wire/in/proposicao.clj` | `ListarProposicoesParams` (Malli): `busca?`, `tipo?`, `estado?`, `autor-id?`, `ano?`, `pagina` (default 1), `tamanho` (default 20, máx 100), `ordenar-por` (allowlist: `atualizado_em`\|`sequencial`\|`ano`), `ordenar-dir` (`asc`\|`desc`, default `desc`) |
| `wire/out` | `wire/out/proposicao.clj` | `ProposicaoResumoOut` (item de linha: id, tipo, ano, sequencial, urn-lex, ementa, autor-tipo, autor-texto, estado, atualizado-em) + `ListaProposicoesOut` (`{:itens [...] :total :pagina :tamanho-pagina}`) |
| `adapters/in` | `adapters/in/proposicao.clj` | `query-params->dominio`: coage strings de querystring → tipos (int, enum), aplica defaults, **rejeita fail-closed** `tamanho` fora de `[1,100]` e `ordenar-por` fora do allowlist (nunca interpola coluna arbitrária no SQL) |
| `adapters/out` | `adapters/out/proposicao.clj` | `linhas->wire`: projeta só os campos de lista (nunca `atributos_especificos` cru) |
| `controllers.clj` | (existente, novo fn) | `listar-proposicoes` — leitura tenant-wide, **mesmo contrato de authz de `relatores-pendentes`/`tramitacao-board`**: sem policy fina adicional, só o gate grosso da rota |
| `db/proposicao.clj` | (existente, novo fn) | `listar` — `WHERE` dinâmico sempre com `ente_id`; `ILIKE` em `ementa`/`urn_lex` quando `busca` presente (sem índice novo — volume por-tenant limitado; índice trigram fica de carry se a fatia expuser lentidão real); `COUNT(*) OVER()` para o total sem 2ª query; `ORDER BY` a partir do allowlist já validado na borda |
| `diplomat/http/in.clj` | (existente) | `GET /legislativo/proposicoes`, papel `"secretario"` — **mesmo papel usado por toda leitura interna hoje** (`/paineis/mesa`, `/paineis/tramitacao`, `/paineis/pendencias`); não introduz papel novo |
| `rotas.clj` (host) | (existente) | registra o novo fragmento de rotas do `legislativo` (a vertical de votação já é injetada; soma-se a este mesmo `repo-legislativo`) |

Nenhuma migration nova — `legislativo.proposicoes` já existe, já indexada por `(ente_id, estado)`. Nenhuma
tabela de leitura paralela: a lista consulta a fonte da verdade diretamente (decisão explícita — ver §5).

## 3. Frontend

```
src/app/(interno)/proposicoes/page.tsx     — página da lista
src/app/(interno)/proposicoes/*.css        — porte verbatim de .tabela/.filtros/.chip/.paginacao/.vazio da tela-fonte
src/app/(interno)/topo.tsx                 — ganha nav de 2 itens (Painéis da Mesa | Proposições)
src/lib/use-proposicoes.ts                 — hook: fetch autenticado, debounce da busca-texto, refetch em filtro/página/ordenação
src/lib/proposicoes-vista.ts               — view-model puro: wire → o que a tabela mostra (linha por linha)
src/lib/charts/azulejo-mini.tsx            — variante compacta do AzulejoFaixa (mesmos EstagioTramitacao/derivarTramitacao da A2)
```

- **Reaproveita integralmente** `derivarTramitacao`/`EstagioTramitacao`/`descreverFaixa` (`src/lib/tramitacao-vista.ts`,
  já construído na A2 exatamente para este `estado` livre/template-driven) para o chip de situação e o mini-azulejo.
  Nenhum vocabulário novo de estado é inventado aqui.
- **`AzulejoMini`** é um componente novo, mas de baixo risco: mesma entrada (`EstagioTramitacao[]`) do
  `AzulejoFaixa` já testado, só troca o SVG por uma versão compacta (blocos pequenos, sem rótulo textual por
  estágio — o `role="img"`/`aria-label` via `descreverFaixa` já cobre a acessibilidade).
- **Busca/filtros são estado de componente nesta fatia** (sem querystring/URL state — YAGNI; compartilhar link
  filtrado vira carry se um servidor pedir).
- Página cliente (`"use client"`), autenticada pelo `AuthProvider` já injetado pelo layout `(interno)`.

## 4. Contrato de dados (resumo)

**Request:** `GET /legislativo/proposicoes?busca=hortas&tipo=projeto_lei&estado=em_comissoes&ano=2026&pagina=1&tamanho=20&ordenar-por=atualizado_em&ordenar-dir=desc`

**Response 200:**
```json
{
  "itens": [
    {"id": "...", "tipo": "projeto_lei", "ano": 2026, "sequencial": 42, "urn-lex": "...",
     "ementa": "Cria o Programa Municipal de Hortas Comunitárias", "autor-tipo": "vereador",
     "autor-texto": "Helena Matos", "estado": "em_comissoes", "atualizado-em": "2026-05-21T..."}
  ],
  "total": 1284, "pagina": 1, "tamanho-pagina": 20
}
```

**Erros:**
- `tamanho` fora de `[1,100]` ou `ordenar-por` fora do allowlist → `400 {:tipo :validacao/invalido}`.
- `pagina` além do total de páginas → `200` com `itens: []` (nunca 400 — o front mostra o estado vazio já
  desenhado na tela-fonte).
- Ator sem papel `secretario` → `403` (interceptor `exige-papel`, antes do handler).

## 5. Decisões e porquês

- **Query nova direto em `legislativo.proposicoes`, não reaproveita `paineis.tramitacao`.** Existe um
  read-model quase idêntico (`paineis.tramitacao`, projetado por eventos, alimenta `/paineis/tramitacao`),
  mas ele pertence ao módulo `paineis` (dashboards agregados), não ao `legislativo` (dono da proposição).
  Esta tela é o **trabalho diário do servidor**, não um dashboard — usar a projeção assíncrona inverteria a
  posse do módulo (§22.10) e introduziria lag de projeção na tela primária de trabalho. Decisão confirmada
  com Daouda em brainstorm (05/07/2026).
- **Papel `secretario`, não um papel novo.** Todas as leituras internas hoje (`/paineis/mesa`,
  `/paineis/tramitacao`, `/paineis/pendencias`, `/paineis/sli/sessoes`) usam o mesmo gate grosso
  `exige-papel "secretario"`. Seguir o precedente em vez de inventar `"servidor"` — viés de consistência
  disciplinar (CLAUDE.md §4).
- **Mini-azulejo incluído, não deferido.** O custo marginal é baixo porque o view-model (`derivarTramitacao`)
  já existe e já é testado; só falta o componente visual compacto.

## 6. Testes

- **Backend TDD red→green:**
  - `adapters/in` — coerção de querystring, defaults, rejeição fail-closed de `tamanho`/`ordenar-por` inválidos.
  - `db` (integração) — filtros combinados, paginação, ordenação, isolamento por `ente_id` (RLS), busca `ILIKE`
    case-insensitive, `pagina` além do total devolve lista vazia.
  - `wire/http/in` (integração) — rota registrada, papel `secretario` exigido (403 sem o papel), 400 em params
    inválidos, 200 com contrato `ListaProposicoesOut`.
- **Frontend:**
  - `proposicoes-vista` (view-model puro) — reusa os mesmos fixtures de estado conhecido/desconhecido de
    `tramitacao-vista.test.ts`.
  - `use-proposicoes` (mock fetch) — debounce da busca, refetch em mudança de filtro/página, degradação em
    erro de rede.
  - `azulejo-mini` — espelha os testes de `azulejo-faixa.test.tsx` (situação concluído/ativo/pendente).
  - Render da página: estados loading/vazio/erro/dados populados.
- **Review `ecc` antes do merge:** clojure + database (atenção especial ao `WHERE`/`ORDER BY` dinâmico —
  nunca interpolar coluna vinda do usuário sem allowlist) + react + security — mesmo gate das fatias anteriores.

## 7. Carries (não bloqueiam esta fatia)

- Índice `pg_trgm` para `busca` — só se o volume real de um tenant expuser lentidão perceptível.
- URL state dos filtros (compartilhar link filtrado).
- Promoção de `.tabela`/`.filtros`/`.chip`/`.azulejo-mini` ao `chassi.css` — gatilho de 2º uso
  (`PADROES-DE-COMPOSICAO.md`); esta é a 1ª vez que o FE usa o arquétipo de lista/tabela, então os estilos
  ficam page-scoped por ora.
