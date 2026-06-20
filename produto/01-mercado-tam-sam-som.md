# 01 · Mercado — TAM / SAM / SOM

Skill: `ecc:market-research`. Reusa §4 (metas), §13 (premissas não validadas), §1 (beachhead).
**Convenção:** `[FATO]` = verificável/público · `[INF]` = inferência nossa · `[REC]` = recomendação ·
🔎 = precisa de pesquisa ao vivo com fonte antes de virar decisão.

---

## 1. Executive summary

O documento-mestre define metas em **unidades de câmara** (§4: 40–80 nos anos 1–2) mas **nunca
dimensionou o mercado em R$** nem validou quantas câmaras são de fato atendíveis pela Rota D.
Este arquivo monta um **TAM/SAM/SOM bottom-up** a partir de fatos públicos (contagem de câmaras)
e deixa explícitas as **duas variáveis-pivô** que hoje não têm fonte: (a) **âncora de preço/ACV**
e (b) **% de câmaras que contratam o legislativo apartado do administrativo**. Sem essas duas, todo
número abaixo é uma faixa, não uma medição. Descoberta de maior alavancagem: o **regime de
dispensa de licitação** (Lei 14.133/2021) pode tornar o ciclo de venda muito mais curto do que o
"pregão de 12–60 meses" assumido no §13 — e interage diretamente com a decisão de preço.

## 2. Key findings

### 2.1 Universo de câmaras (âncora bottom-up)
- `[FATO]` 🔎 Brasil tem **~5.570 municípios** (IBGE); como cada município tem uma câmara, há
  **~5.568 câmaras municipais** (o DF tem Câmara Legislativa distrital; Fernando de Noronha não
  tem câmara). *Verificar contagem corrente no IBGE ao vivo.*
- `[FATO]` 🔎 **Nordeste ≈ 1.794 municípios** (9 estados: AL, BA, CE, MA, PB, PE, PI, RN, SE).
- `[FATO]` 🔎 **Ceará ≈ 184 municípios** → 184 câmaras (o beachhead Fortaleza é **1** delas).
- `[INF]` A esmagadora maioria são **câmaras pequenas** (municípios < 50k hab), que é justamente
  o segmento que o §13 descreve como OBS+YouTube e baixa sofisticação de sistema legislativo.

### 2.2 As duas variáveis-pivô (sem fonte hoje)
- **ACV (preço médio anual por câmara).** ✅ `[FATO]` **CORRIGIDO na rodada 3 (`11`F.2):** a mediana
  "~R$ 24–26k" da rodada 2 era **erro de medição** — usava o valor *homologado*, que subestima o
  **contrato assinado** (Iguatu R$ 32k homologado → **R$ 142,5k assinado**; Aracati full-suite
  **R$ 475k**). Leitura corrigida: **dispensa precifica até o teto (~R$ 60k, ímã de preço); contrato de
  plataforma assinado ~R$ 76–142k; full-suite até R$ 475k**. Vigência **12 meses confirmada** (zero
  plurianual). **Âncoras de trabalho: dispensa-tier ~R$ 55–62k; completo/pregão R$ 100k+.** ⚠️ a
  amostra de contrato-assinado é **100% CE** (beachhead) — CE pode pagar mais que BA/RS; não tratar
  como âncora nacional. Detalhe e confound em `11`F.2.
- **Taxa de "licencia apartado".** ✅ **Resolvida** (rodada 1): quando há legislativo de verdade, a
  compra é **apartada** [FATO, 4/5 editais]. A variável-pivô **mudou**: não é mais "apartado vs.
  embutido" — é a **penetração do SAPL grátis** + a **willingness-to-pay** de quem hoje usa o grátis.
  ✅ **PRODUZIDO na rodada 3 (`11`F.1):** censo próprio de **1.560 instâncias SAPL** (Certificate
  Transparency × IBGE) — **NE = 511 = 28,5% do universo NE**. E o corte que ninguém tinha: **a
  penetração cresce com o porte** (<20k ~23–25% → >100k ~50–54%). 🔴 **A base da pirâmide está MENOS
  saturada** (~75% das câmaras NE <20k **não** estão em SAPL ≈ ~1.190 abertas); o **topo é jogo de
  migração** (>50% em SAPL). Quem assumiu "~20% uniforme" subestimou a base e superestimou o topo.

### 2.3 Alavanca regulatória — dispensa de licitação ✅ `[FATO]` (rodada 1)
- Lei 14.133/2021 art. 75 II: dispensa para serviços abaixo de **R$ 65.492,11/ano** (vigente 2026,
  Decreto 12.807/2025; câmara **não** tem o dobro do §2º). Abaixo disso a câmara contrata **sem
  pregão** — editais reais mostram prazo de proposta de **3 dias úteis** (ver `09`).
- `[INF]` Implicação: **plano anual ≤ R$ 65.492,11** (≈ R$ 5.457/mês) converte pregão de 12–60 meses
  em venda de **dias**. Reenquadra **T3** (caixa vs. pregão). Detalhe de pricing e riscos
  (fracionamento, "objeto de mesma natureza") em `06`/`09`.

## 3. Modelo TAM / SAM / SOM (bottom-up, ACV midpoint R$ 50k — banda entre parênteses)

| Camada | Definição | Câmaras | R$/ano (ACV R$65k `[FATO]`) | Observação |
|---|---|---|---|---|
| **TAM** | Câmaras Brasil **endereçáveis-a-pago** (desc. ~1.200 no SAPL grátis) | ~4.370 | **~R$ 284M** | universo bruto ~5.568 |
| **SAM** | Nordeste (~1.794) − SAPL **511 medido** (`11`F.1) | **~1.283 não-SAPL** | **~R$ 77–115M** (ACV R$60–90k) | concentrado nas pequenas (greenfield); topo é migração |
| **SOM** | Anos 1–2 (meta §4) | 40–80 | **~R$ 2,6M–5,2M ARR** | dispensa encurta ciclo |

> Todos os valores são **estimativas condicionadas** às duas variáveis-pivô. O TAM legislativo-only
> é modesto (centenas de milhões/ano), o que **reforça** a tese Rota D: o R$ 1B+ do §1 **não vem
> do legislativo** — vem da expansão para suite administrativa + prefeituras (§4 anos 4–12). O
> legislativo é wedge, não o prêmio. Isso precisa estar explícito no pitch de investidor.

## 4. Implications (decisões que isto força)

- **[REC] Dimensionar em R$, não só em unidades.** Adotar este modelo como baseline e substituir
  as faixas por números medidos assim que 06 (pricing) e 08 (validação) rodarem.
- **[REC] Tratar "dispensa de licitação" como pilar de GTM**, não detalhe jurídico (tema 07). Pode
  ser o maior destravador de velocidade da V1.
- **[REC] Recalibrar a narrativa de investidor:** o legislativo sozinho não sustenta R$ 1B+; a
  história é "wedge legislativo → NRR via suite → prefeituras". Já está no §4, mas o número de
  TAM legislativo-only torna isso **obrigatório** de dizer, não opcional.

## 5. Risks & caveats

- **TAM inflado por entusiasmo de unidades.** 5.568 câmaras parecem muitas, mas ACV baixo + venda
  pública lenta = receita real modesta no legislativo. Não confundir contagem de logos com receita.
- **SAM pode ser muito menor que 20–40%.** Se a maioria das câmaras compra legislativo **embutido**
  no contrato do ERP administrativo (incumbentes generalistas — §21), o mercado de "apartado"
  encolhe e a Rota D precisa recalibrar (o próprio §13 levanta isso).
- **Beachhead (T1).** Ceará tem só 184 câmaras e um incumbente forte (Softcam). O SOM dos anos 1–2
  provavelmente **não cabe só no Ceará** — exige MA/PI/RN/PB desde cedo. Conflita com "beachhead
  Fortaleza" do §1.
- **Dado de contagem precisa de verificação ao vivo** (IBGE atualiza; números aqui são de memória
  pública, marcados 🔎).

## 6. Recommendation

Adotar este TAM/SAM/SOM como **baseline v0** e priorizar duas pesquisas com fonte para convertê-lo
em medição: **(1)** levantamento de editais/contratos de sistema legislativo nos TCEs do Nordeste
(24 meses) → calibra ACV **e** taxa de apartado de uma vez; **(2)** confirmação do teto de dispensa
vigente (Lei 14.133/2021). Ambas alimentam os temas 06 e 08. Até lá, **nenhuma meta numérica do §4
deve ser tratada como validada.**

## 7. Sources

- IBGE — contagem de municípios (Brasil/Nordeste/CE) — 🔎 *a confirmar ao vivo na próxima rodada.*
- Lei nº 14.133/2021, art. 75 (dispensa de licitação) — 🔎 *confirmar teto vigente reajustado.*
- Interno: documento-mestre §4, §13, §19 — premissas, não fontes externas.
- **Pendente:** editais/contratos publicados nos portais de transparência dos TCEs do Nordeste
  (fonte primária para ACV real e taxa de licenciamento apartado).
