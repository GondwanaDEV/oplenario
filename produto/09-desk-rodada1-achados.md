# 09 · Varredura DESK — Rodada 1 (19/06/2026) · Achados sourced

Log de evidências da 1ª varredura (Q3 + Q1, com bônus de Q2). Três pesquisas independentes.
**Convenção:** `[FATO]` = verificável em fonte/URL · `[INF]` = inferência · datas e fontes ao fim.
Os temas `01`/`02`/`06`/`08` foram atualizados a partir daqui.

---

## A. Q3 — Teto de dispensa de licitação (destrava pricing)

- `[FATO]` **R$ 65.492,11** — teto vigente **desde 1/1/2026** para "outros serviços e compras"
  (Lei 14.133/2021, **art. 75, II**), fixado pelo **Decreto nº 12.807/2025**. Atualização anual por
  IPCA-E (art. 182). Histórico: R$ 50k (lei) → 59.906,02 (2024) → 62.021,48 (2025) → **65.492,11 (2026)**.
  *Confirmado por 4 fontes convergentes (gov.br/compras Comunicado 47/25, TCE-PE, effecti, eLicitação);
  Planalto fora do ar na pesquisa — **validar o carimbo literal do decreto** quando voltar.*
- `[INF alta]` **SaaS/software = serviço (inciso II)**, não engenharia (inciso I, cujo teto é
  R$ 130.984,20). Logo o teto aplicável é **R$ 65.492,11**.
- `[FATO]` **Câmara municipal NÃO tem o dobro** do art. 75 §2º (só consórcios públicos e
  autarquias/fundações qualificadas como agência executiva). Teto efetivo = R$ 65.492,11.
- `[FATO]` **Vedação a fracionamento (§1º):** o limite soma, no exercício financeiro, despesas de
  "mesma natureza/ramo". Não se pode picotar contrato para caber.
- `[INF]` Limite aferido **por exercício financeiro** → um contrato SaaS anual cabe na dispensa se o
  **valor anual ≤ R$ 65.492,11** (≈ **R$ 5.457/mês**). Renovar consome o teto do exercício seguinte.
- `[INF]` **Risco de TCE:** se a câmara já gasta com outro serviço de TI de "mesma natureza", o
  tribunal pode somar e estourar o teto. Variável por tribunal (sensível no TCE-CE, beachhead).
- **Decisão derivada:** indexar pricing a **"% do teto vigente"**, não a número absoluto (sobe todo
  1º/jan por IPCA-E).

## B. Q1 + Q2 — Editais/contratos reais (apartado + ACV + modalidade)

- `[FATO, 4/5]` **Quando há legislativo de verdade (tramitação + painel de votação), a compra é
  APARTADA** — contrato próprio da Câmara, objeto exclusivamente legislativo. Amostra (n=5 de
  software legislativo): Ipueiras-CE, Caravelas-BA, Campos Sales-CE apartados; São Luís Gonzaga-MA é
  **ERP administrativo sem legislativo**. → **valida o wedge da Rota D.** O "embutido" do mercado é o
  ERP administrativo, e ele **não** carrega o legislativo.
- `[FATO+INF]` **ACV (câmara pequena/média NE, núcleo legislativo SaaS apartado) ≈ R$ 40–100 mil/ano.**
  Ipueiras R$ 95,6k/ano; Caravelas R$ 62k/ano (dispensa); Ribas/MS R$ 41k (comparativo). **Ano-1 pode
  ~dobrar** com implantação/migração (Ipueiras: ~R$ 217k no TR vs R$ 95,6k/ano recorrente).
- `[FATO]` **Modalidade por porte:** câmara pequena → **DISPENSA** (art. 75 II, **prazo de proposta de
  3 dias úteis** — ciclo curtíssimo, decisão na Mesa); capital → **PREGÃO** (longo, PoC, impugnações).
  **VotoAqui** anuncia venda explícita por dispensa art. 75 II.
- `[FATO]` **Intel de beachhead (Fortaleza):** a Câmara de Fortaleza **construiu o "CMFor 360"
  in-house, com IA própria ("MaraIA")**, e terceiriza **só o painel de votação** via Pregão 05/2025
  (impugnado pela **Visual Sistemas Eletrônicos**). **E roda um SAPL público** (sapl.fortaleza.ce.leg.br).
  → o beachhead já tem solução caseira no núcleo legislativo. Reforça **T1: não liderar por Fortaleza.**
- **Lacunas honestas:** sem valores de software legislativo confirmados em **PI, RN, PB, PE, AL, SE**
  (dados insuficientes, não inventados); fornecedor vencedor raramente capturável; viés para câmaras
  mais organizadas (PDFs escaneados/CAPTCHA não-raspáveis). **Maior ROI seguinte: raspar o PNCP por API.**

## C. Q1 (delta) — SAPL grátis + bundle generalista (redefine o SAM)

- `[FATO]` **~1.200 câmaras/assembleias usam SAPL** (Interlegis/Senado), **gratuito e hospedado de
  graça** pelo Senado, **já com painel eletrônico e votação** (v3.1). ≈ **20–22% do universo** de
  ~5.570 câmaras, **enviesado para as pequenas** → penetração provavelmente maior na base da pirâmide.
- `[FATO]` Pré-requisito do "grátis + zero-TI": ACT com o Senado + DNS hospedado no Interlegis.
  Auto-hospedar exige stack técnico (Django/Solr/Docker) — inviável p/ câmara pequena sem TI.
- `[INF]` **SAM-pago não é 5.570: é ~4.370 câmaras** (descontando ~1.200 no SAPL). A compressão do
  mercado vem do **preço-zero (SAPL)**, não do bundle administrativo.
- `[FATO]` **4 dos 5 ERPs generalistas ignoram o legislativo** (Fiorilli, Elotech, TOTVS = só
  administrativo; IPM/Atende.Net = no máx. protocolo genérico, não confirmado). **Só a Betha** tem
  produto nomeado ("Legisoft"), e pela fonte é **tramitação documental — sem plenário/votação
  confirmados**. → **reverte a hipótese** de que "os generalistas são a ameaça": **não são.**
- `[INF]` **Onde o SAPL grátis dói (vetor de troca p/ pago):** UX datada, suporte sem SLA, **sem IA**,
  painel/app básicos, **sem compliance TCE-como-dado**. Mas p/ câmara cujo objetivo é "estar legal e
  não pagar", **o SAPL basta** — o pago precisa vender dor **além de tramitação** (janela de sessão,
  risco TCE, engajamento cidadão, IA), senão perde para o zero.

## D. Implicações que isto força (cross-tema)

1. **Pricing (`06`):** plano padrão da câmara pequena ancorado **logo abaixo de R$ 65.492,11/ano**
   (dispensa, ciclo de dias). Indexar a % do teto.
2. **Mercado (`01`):** ACV ancorado em R$ 40–100k [FATO]; **SAM recalculado descontando SAPL**; a
   variável-pivô deixou de ser "taxa de apartado" (resolvida: é apartado) e passou a ser
   **penetração do SAPL por porte/região + willingness-to-pay**.
3. **Concorrência (`02`):** ranking de ameaça reordenado → **(1) SAPL grátis; (2) especialistas
   (Softcam/Legisoft/Nuvem/VotoAqui/Visual); (3) ERPs generalistas (baixo)**. + intel de Fortaleza.
4. **Posicionamento (`04`):** a mensagem **não pode ser "tramitação digital"** (SAPL faz de graça);
   tem que ser **dor além do mínimo legal** — IA, SLA de sessão, TCE, engajamento.
5. **GTM (`07`):** dispensa de 3 dias é o canal de velocidade; beachhead recalibrado p/ fora do CE.

## E. Fontes (acesso 19/06/2026)

**Q3:** gov.br/compras Comunicado 47/25 · TCE-PE limites 2026 · Planalto Decreto 12.807/2025 (validar) ·
TCU licitações/dispensa · effecti · eLicitação · Zênite (fracionamento).
**Editais:** Ipueiras-CE (camaraipueiras.ce.gov.br) · Caravelas-BA (itubera.ba.gov.br EDITAL-005-2025) ·
Campos Sales-CE (cmcampossales.ce.gov.br) · Fortaleza-CE Pregão 05/2025 (api.cmfor.ce.gov.br) +
O Povo 15/05/2025 (CMFor 360/MaraIA) · sapl.fortaleza.ce.leg.br · São Luís Gonzaga-MA · Ribas/MS
(ribasdoriopardo.ms.leg.br) · PNCP (pncp.gov.br/app/editais).
**SAPL/generalistas:** Interlegis SAPL e Produtos (senado.leg.br/interlegis) · DataSenado Panorama ·
GitHub interlegis/sapl · Betha (Legisoft) · Fiorilli · Elotech · TOTVS RM.

**Não confirmado (não inventar):** carimbo literal do Decreto 12.807 no Planalto; contagem oficial de
instâncias SAPL ativas por porte/região; votação/plenário no Betha Legisoft; módulo legislativo no
IPM/Atende.Net; ACV em PI/RN/PB/PE/AL/SE.
