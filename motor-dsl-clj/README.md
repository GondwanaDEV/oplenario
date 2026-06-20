# motor-dsl-clj — Motor de regras de compliance (Clojure)

Port **Clojure** do motor de regras de compliance (§22.7), o materializador do Invariante 4
("regras de compliance são dados, não código"). **Backend = Clojure** (decisão do Emilio,
20/06/2026) — isto resolve a parte de *backend* do stack que estava deferido em §22.4.4.

> **Por que Clojure para este subsistema:** é um motor de **DSL/regras**. Sendo um Lisp,
> homoicônico, a regra/AST/catálogo são **dados Clojure nativos** — "rule-as-data" deixa de ser
> uma camada construída e passa a ser a linguagem (Invariante 4 + Disciplina 5, motor declarativo
> único). Imutabilidade por default casa com a auditoria **append-only** (Invariante 10), e os
> `ratio` nativos dão a **aritmética exata** da armadilha do quórum sem `Fraction` de biblioteca.

## Rodar

Pré-requisitos: **Java 17+** e **Clojure CLI** (`brew install clojure/tools/clojure`).

```bash
clojure -M:test    # suíte de aceitação — 12 testes / 63 asserções (sai != 0 se falhar)
clojure -M:demo    # demonstração do loop de runtime
```

## Estrutura (namespaces espelham o protótipo Python `../motor-dsl/`)

| ns | papel | Python equivalente |
|---|---|---|
| `oplenario.motor.tipos` | sistema de tipos A2 (tipo = mapa de dados) | `tipos.py` |
| `oplenario.motor.nucleo` | lexer + parser + AST + loader do envelope | `nucleo.py` |
| `oplenario.motor.catalogo` | registry B3 (enums, registros, builtins, relações) | `catalogo.py` |
| `oplenario.motor.verificador` | type-checker do **save time** (Eixo A dec. 2) | `verificador.py` |
| `oplenario.motor.runtime` | avaliador + loop materializa→avalia→monitora→audita | `runtime.py` |
| `oplenario.motor.templates` | os 4 templates do Eixo C + negativos (verbatim docs/05) | `templates.py` |
| `oplenario.motor.motor-test` | suíte de aceitação | `test_motor.py` |

## Relação com `../motor-dsl/` (Python)

O Python foi o **protótipo de validação** (§7) que cravou a forma A2 e o loop de runtime. Este port
Clojure é a **semente do motor de produção** na linguagem de backend escolhida; o Python permanece
como **referência validada** (paridade de comportamento: mesmos 4 templates, mesmos negativos, mesmo
loop §22.7.7).

## Decisões de port (deliberadas)

- **Sintaxe-superfície mantida string** (a DSL `exige: votos_favoraveis(...) >= ...`), não trocada por
  EDN — para preservar a **forma A2 validada** (templates verbatim de docs/05). Agora que o backend é
  Clojure, **EDN-como-superfície** (data-as-code puro) é uma evolução natural a considerar, mas é
  mudança de *forma*, não de port — fica registrada como opção, não feita aqui.
- **Estado mutável do motor num `atom`** (read-modify-write single-thread). Em produção, isto vira a
  fronteira de persistência (tabelas `prazo_dominio_ativo` + `compliance_avaliacao`, §22.7.7) — não
  decidida aqui.
- **`[GAP]` segue `[GAP]`:** valores regulatórios reais (datas de prazo, feriados, layout de remessa)
  são fixtures ilustrativos; o conteúdo do TCE-CE **não foi inventado**.

## Endurecimento — rodada 1 (feito)

Auditoria pelo `ecc:clojure-reviewer` → correções (12 testes / 63 asserções, lint clj-kondo limpo):

- **Curto-circuito em `e`/`ou`** no avaliador — uma regra VÁLIDA com ramo morto que falharia
  (`falso e prazo_vigente(...)`) não explode mais em runtime (protege o ciclo de auditoria, Inv. 10).
- **Aritmética temporal real:** builtin `dias(n) → Duracao`; `Data/Instante ± Duracao → mesmo temporal`,
  `(mesmo temporal) - (mesmo temporal) → Duracao`, `Duracao ± Duracao → Duracao` no type-checker E no avaliador.
- **Ordem temporal estrita:** `Instante < Data` deixou de tipar (antes misturava) — só compara o MESMO tipo temporal.
- **Save-time mais rígido:** `:lit` com tipo desconhecido lança em vez de propagar `nil`; chave vazia no
  envelope (`: valor`) é erro de sintaxe; bloco `prazo:` vazio é malformado (não vira "contínua" silenciosa).
- **Higiene:** cache de parse memoizado no sweep; remoção de binding morto (`env`).

## Próximos passos (quando pedir)

- **Fronteira de persistência** (`atom` → protocolo de store + transação no S3) — **deferida ao chat de
  stack** por disciplina (§22.4.4): não materializar a forma do repositório antes daquela decisão.
- **`Instante` com classe JVM própria** (`java.time.Instant` + fuso) — hoje `Instante` e `Data` são ambos
  `LocalDate` em runtime, protegidos pela ordem temporal estrita no type-checker; a separação real depende
  de decisão de fuso (§22.6).
- **Geração do artefato de remessa** — eixo bloqueado em conteúdo regulatório real do TCE-CE (`[GAP]`).
