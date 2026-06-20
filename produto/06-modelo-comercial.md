# 06 · Modelo Comercial & Pricing

Skill: `ecc:market-research` (pricing). Reusa §13/§19#7 (tensão pregão↔MRR), `01` (alavanca de
dispensa + banda de ACV), `03` (ciclo político). **Convenção:** `[FATO]` / `[INF]` / `[REC]` · 🔎.

---

## 1. A tensão central (§19 item 7, ainda aberta)

SaaS quer **MRR previsível**; câmara contrata por **pregão plurianual (12–60 meses) com reajuste**
(§13). Os dois modelos brigam. O material **nunca propôs preço** — não há âncora.

## 2. Decisão-âncora proposta: precificar em torno do teto de dispensa

✅ `[FATO]` (rodada 1, `09`): Lei 14.133/2021 art. 75 II — dispensa para serviços abaixo de
**R$ 65.492,11/ano** (2026, Decreto 12.807/2025; câmara **não** tem o dobro do §2º; limite **por
exercício financeiro**; vedado fracionamento). **[REC] Plano padrão da câmara pequena com anual
≤ R$ 65.492,11** (≈ R$ 5.457/mês) → contratação por dispensa, ciclo de **3 dias úteis** (editais
reais), CAC baixo. Câmaras acima do teto → **pregão** com ACV maior. O teto vira **eixo de
packaging** — e convém **indexar o preço a "% do teto"** (sobe todo ano por IPCA-E), não a número fixo.

🔴 **Correção (rodada 3, `11`F.2) — a mediana ~R$ 24–26k da rodada 2 era ERRO de medição.** O valor
*homologado* da licitação **subestima o contrato assinado** (Iguatu R$ 32k homologado → **R$ 142,5k
assinado**; Aracati full-suite **R$ 475k**). Vigência **12 meses confirmada** (zero plurianual). Leitura
corrigida em **três degraus**:

| Tier | Como o mercado compra | ACV observado (CE, `11`F.2) | Motion |
|---|---|---|---|
| **Essencial** | dispensa, precificada **até o teto** (ímã de preço) | **~R$ 55–62k** | dispensa, 3 dias úteis |
| **Plataforma** | sessão+painel+app, contrato assinado | **~R$ 100–142k** | pregão (estoura dispensa) |
| **Full-suite** | + migração + treino + manut. evolutiva + cloud | **até R$ 475k** | pregão |

`[INF]` **Quebra de packaging crítica:** **vender acima de ~R$ 60k/ano OBRIGA o cliente a pregão** (a
dispensa de serviços é limitada por lei). Logo o tier Essencial tem que **caber sob o teto de dispensa**
(velocidade de venda), e a oferta-completa **assume o ciclo de pregão** conscientemente. ⚠️ amostra de
contrato-assinado é **100% CE** (beachhead) — confound geográfico, não tratar como âncora nacional.

## 3. Estrutura de pricing (v0 a validar)

| Componente | Proposta `[INF]` | Racional |
|---|---|---|
| Assinatura anual por câmara | **três tiers (`11`F.2):** Essencial ~R$ 55–62k (≤ teto dispensa, ciclo 3 dias); Plataforma ~R$ 100–142k (pregão); Full-suite até R$ 475k (pregão) | dispensa p/ velocidade; pregão p/ ticket alto |
| Onboarding / migração | **fee de implantação** (go-live 30 dias como serviço pago) | §16.9 migração é feature; cobre custo artesanal do 1º cliente |
| Expansão (NRR) | módulos administrativos futuros como upsell | §4 NRR 110%+ é o motor do R$ 1B+, não a V1 |
| Reajuste | índice anual (IPCA?) embutido em contrato plurianual | 🔎 norma de reajuste em contratos públicos |

## 4. Sanidade de caixa (reconcilia T3)

`[INF]` Com ACV ~R$ 50k e meta de 40–80 câmaras (§4) → **ARR ~R$ 2–4M** ao fim do ano 2. Time de
§18 (≥8 eng + designer + PM + especialista) ≈ **burn de R$ 3–6M/ano** `[INF]`. Logo: seed R$ 5–15M
dá ~1,5–3 anos de pista, e a **ARR não cobre o burn no horizonte da V1** — o negócio depende de
**próxima rodada**, não de break-even. Implicação dura: **ou** sobe o ACV, **ou** o caminho de
dispensa (volume rápido) é essencial, **ou** a meta de unidades baixa. Modelar de verdade no `08`.

## 5. Riscos & lacunas

- ✅ **ACV agora ancorado** em R$ 40–100k/ano [FATO, rodada 1] (era hipótese). Plano ~R$ 65k cabe na
  dispensa **e** no mercado.
- 🔴 **Willingness-to-pay é o gargalo real, não o teto** — e a rodada 2 endureceu isto: `[FATO]` (`11`A)
  **Dourados/MS largou um pago de R$ 38k/mês e foi para o SAPL grátis por ~30% de economia**; **nenhum**
  caso inverso (grátis→pago) foi achado. O vetor de troca corre **a favor do zero**. Na base da pirâmide
  "por que pagar?" é a objeção central; o pricing tem que vender **dor além do mínimo legal** (profundidade
  de IA, SLA de sessão, TCE-como-dado), senão perde para o grátis. **Contra-argumento de venda (`11`F.2):**
  o grátis **não é grátis** — Cascavel/CE paga **R$ 61,6k/ano só para *manter* o SAPL** + e-Democracia +
  LEG.BR. O pitch é "você já gasta ~R$ 60k operando o grátis; pague o mesmo por algo que funciona melhor".
- 🔴 **Refinamento de segmento (rodada 3, `11`F.3) — o ICP mais limpo é o MEIO (20–50k hab).** A matriz
  porte×saturação×ACV mostra: **base <20k** = greenfield mas maior gravidade do grátis (Dourados); **topo
  50k+** = ticket alto (R$ 100–475k) mas >50% já em SAPL (migração forçada) e fuga pago→grátis demonstrada;
  **meio 20–50k** = orçamento de dispensa no teto (~R$ 60k), SAPL ainda ~30%, menos atrito dos dois lados.
  Alimenta **T6** (`03`).
- ✅ **CONFIRMADO em campo (`12`B), pelo lado do comprador:** WTP *"razoável"* ~**R$ 50–70k/ano** (médias),
  *"caro demais"* > R$ 100–150k; e *"acima de R$ 80k já exige pregão, o que alonga"* — corrobora o teto de
  dispensa como **ímã de preço E fronteira de modalidade dos dois lados** (contrato real `11`F.2 + percepção
  do comprador). **Decisão de pricing destravada:** Essencial sob o teto de dispensa; completo assume pregão.
- **Ciclo político (`03`/§14 R3):** contrato precisa sobreviver à troca de Mesa — preferir
  plurianual com cláusula que não dependa do presidente que assinou.
- **Risco de "corrida ao teto":** se todos precificarem sob dispensa, o teto vira teto de receita
  por conta pequena — daí a importância do **upsell de módulos** (NRR) para crescer a conta.

## 6. Recommendation

Adotar **pricing ancorado no teto de dispensa** como hipótese v0 e **modelar caixa (ARR vs. burn)
explicitamente** assim que `08` trouxer ACV real. Sem dado de editais, **não cravar tabela de
preço** — é a decisão mais cara de errar.
