# §22.7 — Eixo de expansão a outros TCEs · RASCUNHO DE TRABALHO

> **Status: ✅ CONSOLIDADO em §22.7.9 do documento-mestre (v1.37, 21/06/2026).** Último eixo de
> §22.7 — **fecha o bloco do motor de compliance**. No mesmo papel que `docs/05` (Eixo C), `docs/06`
> (Eixo B) e `docs/07` (runtime). Este eixo **não reabre a forma** (A/C/B + runtime + §22.7.8 já a
> cravaram); **fecha a estratégia** de absorver os 33 Tribunais de Contas como **dado** (Invariante 4)
> e **crava a única questão estrutural que o Eixo B empurrou pra cá** (resolução de jurisdição c/ TCMs).

---

## 0. A pergunta real do eixo

Não é "como escrever as regras dos outros 26 TCEs" — isso é **conteúdo `[GAP]`** (especialista de
regimento / pesquisa sourced, fora de escopo, como em todo eixo). É: **a arquitetura absorve os 33
tribunais sem refactor estrutural?** Onde é dado puro, onde é exceção-de-código bounded, e qual o
playbook de onboarding repetível.

### Inventário dos tribunais (a confirmar em fonte autoritativa — Atricon/IRB)
São **33 Tribunais de Contas**; a diversidade que importa p/ câmara (= conta municipal):
- **27 de nível estadual:** 26 TCEs + TCDF. Na maioria, o TCE julga conta estadual **e** municipal.
- **3 TCM "dos Municípios"** (órgão estadual, julga **todos** os municípios da UF): **TCM-BA, TCM-GO,
  TCM-PA**. Câmara nessas UFs responde ao TCM, não ao TCE.
- **2 TCM "do Município"** (só a capital): **TCM-SP, TCM-RJ**. Câmara do interior → TCE-SP/RJ; câmara
  da capital → TCM-SP/RJ.
- *(Ceará: TCM-CE extinto/fundido no TCE-CE em 2017 — por isso o beachhead resolve `CE→TCE-CE` direto.)*

Consequência dura: **`tribunal(câmara)` NÃO é `UF→TCE`** — é função de `(UF, município, é-capital)`. O
Eixo B sabia e deixou "entra via tabela de exceção de jurisdição — não muda a forma". O E1 crava isso.

---

## E1 — resolução de jurisdição (`câmara → tribunal`) · DECIDIDO

**Opção escolhida: A — tabela de jurisdição de domínio (default + overrides).**

- **Tabela `jurisdicao_camara`** — **domínio, no módulo `cadastros`** (irmã de `municipios`, **sem
  `ente_id`**; é referência geográfico-institucional, não regra de compliance). Conteúdo: **uma linha
  por UF** dando o tribunal da *câmara* (maioria `UF→TCE-UF`; `BA→TCM-BA`, `GO→TCM-GO`, `PA→TCM-PA`) +
  **override por município** p/ as 2 capitais (`São Paulo→TCM-SP`, `Rio→TCM-RJ`). ~29 linhas.
  **Precedência: município-override → linha-de-UF.**
- **Bridge ao motor = função de relação `tribunal_competente(ente) → Texto`** (o código do tribunal),
  exposta pelo **`cadastros`** (contexto dono, padrão B3) e registrada no catálogo. A B2 (§22.7.6) a
  chama p/ obter o `chave_dominio` das regras de `tribunal_de_contas`.
- **Reconciliação:** isto refina o Eixo B (que dizia "resolução via `entes → municipios.uf` JOIN") p/
  a §22.10 (**sem cross-schema JOIN**) — vira **função de relação**, não JOIN.
- **Armadilha `UNIQUE+NULL`** (`municipio_id` NULL = regra de UF) → índice com `COALESCE`, padrão já
  fixado na migration `…0006`.
- **Invariante 4:** novo tribunal / fusão (como TCM-CE→TCE-CE 2017) = **linha de dado**, zero código.

**Materialização deferida:** a tabela + a função de relação são **forma** (recordadas aqui); a DDL/
implementação vem com a materialização do módulo `cadastros` (não puxar antes da hora).

**Descartadas:** B (binding explícito por ente — ~1.500 linhas tenant, perde "lei uniforme central",
drift); C (`chave_dominio` como expressão na regra — resolução é de cadastro, não de regra).

---

## E2 — taxonomia `dominio`/`chave_dominio` · DECIDIDO

**Opção escolhida: B — renomear a camada `tce_estadual` → `tribunal_de_contas`.**

- `dominio ∈ {federal, tribunal_de_contas, regimento_tenant}`; `chave_dominio` = o código do
  tribunal específico (TCE-CE, TCM-GO, TCM-SP…).
- **A forma/schema não muda** — é valor de enum-texto. O rótulo `tce_estadual` **mentia** nos ~5 casos
  de TCM e re-importava a confusão "qual TCE" que o S2 alertou. `tribunal_de_contas` é o nome honesto
  da camada (regime-tipo); `chave_dominio` faz o resto.
- **Churn aplicado:** `verificador.clj` (enum + msg de erro), `templates.clj` (T1, N1), comentários da
  migration `…0006`. Suíte re-rodada **verde (12 testes / 63 asserções)**. *(Seed `motor-dsl-clj/`
  superseded mantém o nome antigo — backend é canônico.)*
- §22.7.5 S2 e §22.7.6 (v1.11) referem-se à camada pelo nome anterior; a partir da v1.37 o valor é
  `tribunal_de_contas`.

**Descartada:** C (TCE vs TCM como `dominio` distintos — over-granular; a diferença é *qual* tribunal,
trabalho do `chave_dominio`).

---

## E3 — variação × fronteira do Invariante 4 + playbook · DECIDIDO

### Inventário de variação (classificado)
| Dimensão | Onde mora | Dado ou código? |
|---|---|---|
| Quais obrigações existem | `template_compliance` | **dado** ✅ |
| Prazos (c/ override por circular) | `prazo_dominio_vigente` | **dado** ✅ |
| Feriados (nacional+municipal) | `calendario_feriado` | **dado** ✅ |
| Expressão da regra | `fonte_yaml`+`forma_compilada` | **dado** ✅ *(se o vocabulário cobre)* |
| Mapeamento de jurisdição | `jurisdicao_camara` (E1) | **dado** ✅ |
| Cadência/competência | tipo `Competencia` + `prazo` | **dado** ✅ |
| Layout da remessa (campos/ordem) | descritor declarativo (§22.7.8) | **dado** ✅ |
| Encoding do arquivo (XML/CSV/posicional) | `SerializadorRemessa` adapter | **código bounded** ⚠️ |
| Protocolo de submissão | `TransporteRemessa` adapter | **código bounded** ⚠️ (V1 manual) |
| Vocabulário (função de relação nova) | registry B3 | **código aditivo compartilhado** ⚠️ |

### A regra-de-ouro (decisão load-bearing)
As 3 exceções-de-código são keyed por **eixo COMPARTILHADO, nunca por identidade do tribunal**:
- **Encoding:** 1 adapter por *família de formato* (remessa gov-BR é predominantemente XML-tabular +
  alguns CSV/posicional — poucas famílias, muitos tribunais cada). Nunca "1 serializer por TCE".
- **Protocolo:** 1 adapter por *protocolo de transporte* (quando automatizar), compartilhado.
- **Vocabulário:** função de relação nova é **aditiva ao registry compartilhado** (B3), usada por todos
  os 4 usos da DSL — não um branch por tribunal.
> **"Código por tribunal" é o cheiro a resistir.** Nunca `if tribunal == X`.

### Playbook de onboarding de um tribunal-XX (repetível; conteúdo de cada passo = `[GAP]`)
1. Confirmar/inserir a linha em `jurisdicao_camara` (E1) — quase sempre o default de UF cobre.
2. Carregar `calendario_feriado` + `prazo_dominio_vigente`.
3. Escrever as regras em `template_compliance` (DSL) — **type-check no save = rede de segurança**.
4. Se uma regra não tipa por falta de função de relação → **estender o registry** (aditivo); o
   type-check aponta exatamente o que falta.
5. Descritor de layout da remessa + reusar/adicionar `SerializadorRemessa` da família de encoding.
6. (Depois) `TransporteRemessa` se automatizar a submissão; V1 = download manual.

### O que torna isto seguro comercialmente
O **type-check do save-time** transforma "onboarding de TCE novo" de **risco de engenharia** em
**edição de dado verificada** — regra malformada vira erro no editor, não incidente de janela de
envio. É a Aposta 3 (confiança operacional) materializada na expansão.

---

## E4 — fronteira `[GAP]` + rollout escalonado · DECIDIDO

- **Forma↔conteúdo:** a forma (E1–E3) está validada estruturalmente + contra **1 tribunal real**
  (TCE-CE, Eixo C). O **conteúdo** de cada tribunal (regras, prazos, feriados, campos, sistema de
  remessa) é `[GAP]` — não inventado.
- **O que honestamente NÃO está validado:** a forma **não** foi stress-testada contra o conteúdo real
  de um 2º tribunal. "Absorve os 33" é argumentado estruturalmente, não provado além do CE. **O
  type-check é o instrumento que mede isso quando o conteúdo chega.**
- **Rollout demand-pulled** (régua §15; escopo diferido por default): **V1 = só TCE-CE** (§10/§325);
  ordem segue a expansão comercial (NE → N/CO → S/SE), **gatilho = cliente validado** na jurisdição,
  não roadmap. **O 2º tribunal é o marco de validação empírica da forma** (provável TCM-BA ou TCE-NE).
- **Item de de-risk parqueado (pesquisa, não conteúdo):** inventário das *famílias de encoding* dos
  sistemas de remessa dos tribunais de expansão prioritária (NE) — confirma a hipótese "poucos
  adapters" do E3 antes de comprometer o `SerializadorRemessa`. Quando a 2ª expansão se aproximar.

---

## Estado e fechamento

**E1–E4 decididos.** Com este eixo, **§22.7 (motor de regras de compliance) fecha por completo** —
Eixos A, C, B, runtime (§22.7.7), geração de remessa (§22.7.8) e expansão (§22.7.9). A forma do motor
está cravada e validada; o conteúdo regulatório de cada tribunal segue `[GAP]` por design, populado
sob a régua das 4 perguntas conforme a expansão comercial pede. Consolidado em **§22.7.9** (bump v1.37).
