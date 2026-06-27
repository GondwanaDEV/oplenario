# Brief — Próxima sessão: reorganização do documento-mestre (extração da §22)

> **Tarefa de uma sessão, fresca e barata.** Mudança *estrutural de documento*, não de decisão.
> Nenhuma decisão de arquitetura/produto é reaberta aqui. Extração **verbatim**.

## Por que (diagnóstico, v1.38)

O `documento-mestre-camaras.md` tem **1435 linhas / 286 KB** — passou do teto de leitura do Read
(>25k tokens numa tacada). A dor é **concentrada**, não espalhada:

| Bloco | ~linhas | % do doc |
|---|---|---|
| §1–§21 (estratégia, escopo, features, régua) | ~459 | 32% — **saudável, narrativa, fica inteira** |
| §22 (North Star Architecture) | ~925 | **64%** — registro de decisões, cresce 1 subseção/sessão |
| └ só a §22.7 (motor de compliance) | 301 | **21% num único item** |
| §23–§24 | ~50 | 4% |

O doc-mestre mistura dois artefatos com ciclos de vida diferentes: a **espinha estratégica**
(lê-se como narrativa) e o **ADR de arquitetura** (§22, denso, por subseção). Separar a §22
resolve a dor e espelha o padrão que já funciona em `produto/` (arquivos numerados, cada um lê
numa tacada) e em `docs/0X-eixo-*-rascunho.md`.

## Princípio que preserva o SSOT

A autoridade do SSOT **não vem de ser um arquivo** — vem de ser consolidado e **versionado num
só lugar**. Mantém-se a propriedade desde que **o changelog (§24) continue a autoridade única de
versão** do conjunto. Os arquivos extraídos são parte do SSOT, não cópias.

## O corte exato

**Doc-mestre MANTÉM inline** (curto, load-bearing, sempre carregado barato):
- §1–§21 (íntegra), §23, §24 (changelog = autoridade de versão).
- §22 abertura + **§22.1 invariantes** (os 10 — "lei estrutural", referenciados o tempo todo) +
  §22.2 alto nível + §22.8 parqueados.
- As subseções extraídas viram **entradas de índice** dentro da §22: 1 linha de resumo + ponteiro
  pro arquivo (ex.: `→ ver arquitetura/22-7-motor-compliance.md`).

**EXTRAIR para `arquitetura/`** (as densas) — *localizar por âncora de header `### 22.x `, não por
linha* (as linhas abaixo são guia da v1.38 e vão driftar assim que a 1ª extração rodar):

| Arquivo novo | Subseção | Linhas-guia (v1.38) |
|---|---|---|
| `arquitetura/22-3-contrato-core-ia.md` | §22.3 contrato core ↔ IA | ~518–614 |
| `arquitetura/22-4-dados-legislativo.md` | §22.4 modelo de dados legislativo | ~615–678 |
| `arquitetura/22-5-auth.md` | §22.5 auth (authn/authz) | ~679–860 |
| `arquitetura/22-6-sessao-plenaria.md` | §22.6 sessão plenária + áudio + real-time | ~861–934 |
| `arquitetura/22-7-motor-compliance.md` | §22.7 motor de compliance (o monstro) | ~935–1235 |
| `arquitetura/22-9-stack.md` | §22.9 stack técnico e infra | ~1242–1327 |
| `arquitetura/22-10-monolito.md` | §22.10 organização do monólito modular | ~1328–1375 |

## Invariantes do corte (não violar)

1. **Verbatim** — mover o texto, **não reescrever** nenhuma decisão. Conteúdo `[GAP]` segue `[GAP]`.
2. **Âncoras `§22.x` preservadas** dentro dos arquivos — as referências cruzadas existentes
   (`§22.5 eixo B`, `§22.10 proíbe cross-schema JOIN`, etc.) seguem válidas como texto. O nome do
   arquivo carrega o número (`22-7-...`) justamente para casar a âncora sem churn.
3. **Cabeçalho de SSOT** no topo de cada arquivo extraído:
   > *Parte do SSOT (documento-mestre). Versão canônica do conjunto em `documento-mestre-camaras.md §24`.
   > Em conflito com memória de chat antigo, este arquivo prevalece.*
4. **§24 = autoridade única de versão** do conjunto (não versionar arquivo por arquivo).

## O que NÃO mexer (deliberado)

- **Nome `documento-mestre-camaras.md`** — fica (referenciado em CLAUDE.md, memória, `.remember`;
  versão vive no header, decisão do Daouda Traore). Renomear = churn por zero ganho.
- **`produto/`** — já está limpo. O `produto/13` (46 KB) é tabela de referência, fora do caminho
  quente do design; split só se virar gargalo.
- **`docs/0X-rascunho`** — drafts de origem já superseded; no máximo um `docs/rascunhos/` depois.
- **§1–§21** — narrativa estratégica, fica inteira no doc-mestre.

## Checklist de execução

- [ ] Ler o doc-mestre em janelas (não cabe numa tacada); localizar cada `### 22.x` por header.
- [ ] Criar `arquitetura/` + os 7 arquivos com header de SSOT + conteúdo verbatim.
- [ ] No doc-mestre: substituir o corpo de §22.3–§22.7, §22.9, §22.10 por entradas de índice
      (resumo de 1 linha + ponteiro). Manter §22.1/§22.2/§22.8 inline.
- [ ] Atualizar o `### 23. Como este documento deve ser usado` para explicar a espinha + `arquitetura/`.
- [ ] **Validar o corte com `ecc:architect`** (a §22 referencia a si mesma — checar que nenhuma
      referência cruzada quebrou e que os seams respeitam a §22.10).
- [ ] **Bump v1.39 no §24**: "Reorganização estrutural — §22 extraída para `arquitetura/`; doc-mestre
      vira espinha estratégica + índice; §24 segue autoridade única de versão. Sem mudança de decisão."
- [ ] Atualizar o mapa da pasta no `CLAUDE.md` (§6) com a pasta `arquitetura/`.
- [ ] **Um commit limpo**: `docs: reorganização — extrai §22 para arquitetura/ (doc-mestre = espinha + índice, v1.39)`.
- [ ] Atualizar o cursor (§3 do CLAUDE.md): reorg feita → **próxima ação = DESIGN** (sessão ao vivo +
      mesa de condução 4.14–4.21; Expediente 3.22–3.23; portal cidadão 6.1/5.10; painéis 16.11).

## Prompt de abertura sugerido (sessão nova)

> "Executa a reorganização do doc-mestre conforme `docs/09-proxima-sessao-reorg-arquitetura.md`:
> extrai a §22 para `arquitetura/`, deixa o doc-mestre como espinha + índice, valida o corte com
> ecc:architect, bump v1.39, um commit. Depois me confirma para abrir o design."
