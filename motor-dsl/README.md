# motor-dsl — avaliador executável da DSL de compliance (protótipo)

**Primeira implementação de fato** do motor de regras de compliance (§22.7), o ponto onde
o trabalho deixa de ser conversa de design e vira código que roda (CLAUDE.md §7). Não é
produção — é o **avaliador mínimo** que valida *end-to-end* a forma fechada nos eixos A, C, B
e no Eixo de runtime, antes de qualquer commitment de stack.

> **Status:** protótipo de validação. Zero dependências (Python 3.8+ stdlib). A linguagem é
> instrumental (throwaway de validação), **não** decisão de stack — esse chat segue deferido
> (§22.4.4). Valores regulatórios são *fixtures* ilustrativos; o `[GAP]` de conteúdo de
> §22.7.5 **continua GAP** — aqui só se exercita a *forma*, que independe do valor.

## Como rodar

```bash
cd motor-dsl
python3 test_motor.py   # suite de aceitação (39 checagens) — sai != 0 se algo falha
python3 demo.py         # walkthrough narrado dos 3 momentos
```

## O que ele prova (e contra qual decisão canônica)

| Prova | Decisão validada |
|---|---|
| Os **4 templates do Eixo C** (T1–T4) passam pelo parser + type-checker | forma **A2** expressa carga real (§22.7.5 veredito) |
| **T4 (quórum)**: o núcleo de expressão tipa, mas o envelope de compliance é rejeitado | **S4** — quórum é *guard de plenário*, não obrigação-com-prazo |
| **5 regras mal-tipadas** (N1–N5) são rejeitadas no *save*, com erro pertinente | **Eixo A dec. 2** — regra mal-tipada não vira `vigente` |
| `arredonda_cima(fracao(2,3)·N)` é **exato** (5, não 4 em N ímpar) | aritmética racional, não float (a armadilha do quórum, §22.7.5) |
| Regra **com prazo** materializa `prazo_dominio_ativo`; **contínua** (sem prazo) só audita | **dois sabores de obrigação** (§22.7.7, S1) |
| `aplica_quando=falso` → veredito `inaplicavel`, sem obrigação | gatilho condicional (T2: ≤10k hab dispensado) |
| Ofício Circular desliza o prazo → obrigação **aberta** é re-carimbada; **cumprida** não move | **re-stamp S3** ↔ Eixo B (§22.7.7) |
| `compliance_avaliacao` só cresce (sem update/delete) carimbando regra+catálogo | **append-only = prova de compliance** (Invariante 10) |

## Mapa dos arquivos

| Arquivo | Papel | Eixo |
|---|---|---|
| `tipos.py` | sistema de tipos (primitivos + compostos) | A (dec. 4/5) |
| `catalogo.py` | registry: builtins, funções de relação, enums, tipos, versão | B3 |
| `nucleo.py` | lexer + AST + parser de expressão + loader do envelope | A (dec. 1) |
| `verificador.py` | type-checker do *save time* | A (dec. 2) |
| `runtime.py` | avaliador + loop materializa→avalia→monitora→audita | runtime (§22.7.7) |
| `templates.py` | os 4 templates do Eixo C (verbatim) + negativos | C |
| `test_motor.py` | suite de aceitação | — |
| `demo.py` | walkthrough narrado | — |

## Loop de runtime modelado (§22.7.7)

```
evento/sweep/sob_demanda
   └─ avalia aplica_quando ──falso──▶ audita (inaplicavel), fim
        │ verdadeiro
        ▼
      avalia exige ─▶ conforme | nao_conforme
        │
        ├─ tem prazo?  SIM ─▶ materializa/atualiza prazo_dominio_ativo (relógio)
        │                       └─ transição enum: pendente→cumprida|vencida
        │              NÃO ─▶ (contínua) não materializa
        ▼
      audita: append em compliance_avaliacao (carimba regra + registry_versao_ref)
```

`monitorar()` é **derivação de leitura** sobre `vence_em` (`a_vencer`/`vencida` não são
estados persistidos — §22.7.7). O relógio é **injetado** (determinístico, como instante em
§22.6); não há `Date.now()`.

## Simplificações conscientes (não viram premissa)

- **Literais de enum globalmente únicos** no catálogo (`resolucao`, `emenda_lom`) para
  resolver o domínio sem qualificação. O catálogo real qualificaria
  (`TipoAtoLegislativo.resolucao`). Anotado em `catalogo.py`.
- **Persistência é em memória** (dicts/listas). As tabelas (`prazo_dominio_ativo`,
  `compliance_avaliacao`, `template_compliance`…) são *espelhadas* como dataclasses; DDL real
  e tipos concretos ficam para o chat de stack (§22.4.4).
- **Infra de avaliação** (worker/fila/cron do sweep) **não** está aqui — só o modelo lógico
  (evento/sweep/sob_demanda), como decidido em §22.7.7.
- `proximo_dia_util` = primeiro dia útil **estritamente após** a data; feriados são fixture.

## O que isto NÃO é

Não é o gerador de **artefato de remessa** ao TCE (gera o *arquivo* — outro eixo) nem a
**expansão a outros TCEs** (conteúdo, Invariante 4). É o *motor que rastreia a obrigação e
prova a conformidade* — a fronteira deixada explícita em §22.7.7 e no Eixo B (§0).
