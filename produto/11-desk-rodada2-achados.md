# 11 · Varredura DESK — Rodada 2 (19/06/2026) · Achados sourced

Log de evidências da 2ª varredura: **Q10** (penetração do SAPL), **Q2** (distribuição de ACV via
raspagem do PNCP), **Q11** (Betha/IPM + ranking de ameaça). Três pesquisas independentes. Par de `09`
(rodada 1). **Convenção:** `[FATO]` = verificável em fonte/URL · `[INF]` = inferência · `[GAP]` =
dado insuficiente, não inventado. Fontes ao fim. Os temas `01`/`02`/`04`/`06`/`08` foram atualizados
a partir daqui.

> **Síntese de uma linha:** a rodada 2 **espreme a diferenciação por três lados** — não dá para
> vencer no **preço** (SAPL grátis, e o vetor de troca corre *pago→grátis*), nem em **"temos IA"**
> (já é paridade: 4+ concorrentes com IA em produção), nem mirando a **base barata** (mediana real de
> ACV ~R$ 24–26k, bem abaixo do teto de dispensa). O que sobra, e sobra **limpo**, é profundidade de
> IA + **SLA de janela de sessão** + **compliance TCE-como-dado** + confiança operacional — vendidos
> a um segmento que **já paga** por sistema legislativo de verdade.

---

## A. Q10 — Penetração do SAPL (nova variável-pivô do SAM)

- `[FATO]` **"~1.200 câmaras usam SAPL" é cifra estagnada há ~10 anos** (aparece igual de 2016 a
  jun/2026) → redonda, provavelmente **conservadora/desatualizada** e **possivelmente contaminada
  pela cifra do Portal Modelo** (produto institucional distinto do SAPL). Mistura "tem instância" com
  "usa em plenário". **Decisão: manter como melhor estimativa (~21% nominal, viés p/ pequenas), mas
  rebaixar a confiança.**
- `[FATO]` **O corte por porte ou por UF/região NÃO existe em fonte pública.** O painel DataSenado
  "Panorama do Legislativo Municipal" cobre as 5.568 câmaras mas **não registra qual software cada
  uma usa**. → o corte que destrava o SAM-NE **tem que ser produzido por nós** (enumeração própria de
  `sapl.*.leg.br` via Certificate Transparency × população IBGE). Item novo de validação.
- `[FATO]` **Funil ACT → uso:** ~**4.300 casas** já têm Acordo de Cooperação Técnica com o Senado
  (2020), mas só ~1.200 usam SAPL. A fricção real está no **software**, não no convênio. `[INF]` O
  SAM-pago não é "5.570 − 1.200": o universo que poderia trocar é maior, porque ter ACT ≠ usar SAPL.
- 🔴 `[FATO]` **O vetor de migração corre A FAVOR do grátis.** Câmara de **Dourados/MS** (jun/2026)
  **largou o sistema pago "Lamper" (R$ 38 mil/mês ≈ R$ 456k/ano) e adotou o SAPL grátis**, esperando
  ~30% de economia. **Nenhum** caso inverso (SAPL→pago por UX/IA/suporte) foi encontrado. → **teto de
  willingness-to-pay**: no eixo preço, o piso é zero e a câmara corta exatamente isso quando aperta.
- `[FATO]` Mesmo o "grátis" gera custo: há câmara que **contratou empresa terceira só para operar o
  SAPL** (Contrato 13/2023, Ilha Comprida-SP). `[INF]` Fresta para um pago com suporte/SLA embutido —
  o concorrente real não é "grátis nominal", é **"custo total do SAPL operado + painel pago à parte"**.
- `[INF]` **Contraditório:** penetração de *uso ativo em plenário* pode ser **bem abaixo de 20%**
  (instâncias-zumbi, número herdado do Portal Modelo); mas a contagem por `.leg.br` **subestima** o
  universo SAPL-software (auto-hospedadas em `.gov.br` não aparecem). Líquido: número frágil nos dois
  sentidos.

## B. Q2 — Distribuição de ACV (raspagem do PNCP)

- `[FATO]` **Mediana de ACV ~R$ 24–26k/ano** para software legislativo/gestão de câmara pequena, faixa
  **R$ 10,5k–60,6k** (+1 outlier de implantação R$ 130k). **100% por contratação direta** (11 dispensa
  / 1 inexigibilidade); **13 de 15 cabem no teto de dispensa** (R$ 65.492). **Pregão não apareceu** na
  amostra. (n=12 PNCP + 3 âncoras revalidados da rodada 1 = n=15.)
- 🔴 `[FATO/INF]` **A âncora de ~R$ 65k é TETO/aspiração, não mediana.** A faixa R$ 40–100k da rodada 1
  capturou o **topo** (contratos com painel de votação + migração + suporte: Caravelas R$ 62k, Ipueiras
  R$ 95,6k). A **base** do mercado é mais barata — muitos contratos de R$ 12–26k só de "locação de
  sistema de gestão legislativa web". **O mercado é bimodal:** base barata (portal/diário/tramitação
  simples) vs. topo rico (sessão+painel+migração).
- `[FATO]` **Padrão por região:** núcleo concentrado em **BA (6) e RS (3)**, depois MA/GO/TO —
  confirma "Nordeste + interior Sul, municípios pequenos, compra por dispensa".
- `[INF]` **Há espaço de preço para cima quando o valor é percebido:** Ribas-MS trocou de fornecedor e
  "economizou R$ 170k" → o incumbente cobrava **muito mais** que a mediana. O delta entre mediana
  barata (~R$ 24k) e teto (~R$ 65k) é a **janela de upsell** que IA + painel + SLA justificam.
- `[GAP]` **Amostra-piloto, não censo:** só **2 modalidades × 1 janela de 10 dias (mar/2024)**;
  **pregão e todo 2025 não foram raspados** (corte de custo). **Normalização plurianual→anual é
  premissa, não medição** (o endpoint `publicacao` não traz vigência) — se houver contratos de 24–60
  meses, o R$/ano real seria **menor** ainda. Fornecedor vencedor não capturado (só no endpoint
  `contratos`). Próximo passo barato: 1 janela de pregão + 1 de 2025 + cruzar 4–5 `numeroControlePNCP`
  com `contratos` para confirmar vigência e fornecedor.

## C. Q11 — Concorrência (Betha/IPM + ranking) — duas premissas da rodada 1 derrubadas

- 🔴 `[FATO]` **"Legisoft é da Betha/Nuvem Tecnologia" é FALSO.** Legisoft é da **Virtualiza
  Tecnologia**, especialista independente (rodapé do próprio site). Corrigir o nome onde aparecer.
- 🔴 `[FATO]` **"Ninguém tem IA" é FALSO.** Há IA legislativa **em produção real**: **Govsys/Legiflow
  (LegIA)** treinada em 2M+ proposições (Gramado, S. Leopoldo, Araguaína); **Città/Eprocleg** (resumos
  + redação assistida, Viamão/RS); **LegisFácil** (SaaS R$ 117–357/mês); e a **MaraIA** de Fortaleza.
  → **"ter IA" deixou de ser diferencial.** A defensibilidade tem que ser **profundidade** (IA acoplada
  ao motor de tramitação, busca semântica), não a existência da feature.
- `[FATO]` **Generalistas = ameaça BAIXA: CONFIRMADO.** **Betha não tem** produto legislativo (nem o
  "Legisoft", que nunca foi dela); **IPM/Atende.Net** só publica atos administrativos (nível protocolo);
  **TOTVS/Fiorilli/Elotech** = 100% back-office (Elotech OXY é "administrativo e RH", dito pela própria
  Câmara de Pontal-PR). A reversão da rodada 1 se sustenta. Ressalva: ausência de Betha legislativo é
  **inferência forte por convergência**, não documento único (site JS-rendered bloqueou fetch).
- `[FATO]` **Ranking de ameaça revisado:** **(1) SAPL grátis** (incumbente estrutural, roda no próprio
  beachhead); **(2) especialistas modernos com IA — Govsys/Legiflow à frente**, depois Legisoft/
  Virtualiza, VotoAqui, Softcam, Città, LegisFácil; **(3) fornecedores de hardware/painel** (Visual,
  Imply, Escal — legado, baixa ameaça ao modelo software-only); **(4) ERPs generalistas — BAIXO**.
- `[FATO]` **Duas brechas de posicionamento limpas e desocupadas:**
  - **SLA de janela de sessão** — **ninguém** promete uptime garantido no horário da plenária; o SAPL
    explicitamente joga a disponibilidade para a Casa. A dor já aparece em linguagem de edital
    ("horário crítico" de manutenção) → o comprador reconhece, mas ninguém vende. **Diferencial livre.**
  - **Compliance TCE-como-dado no legislativo** — existe automação TCE no mundo *contábil* de prefeitura
    (CidadES/TCE-ES), mas **nenhum software legislativo** aplica regras do TCE à tramitação. Ressalva:
    risco de **ruído de categoria** (comprador confunde com a automação contábil) — o pitch tem que separar.
- `[FATO]` **Beachhead (reforça T1):** **nenhum especialista moderno tem cliente no Ceará** → o wedge
  NE se mantém. Mas a **MaraIA/CMFor360 de Fortaleza é construção INTERNA da Câmara, não fornecedor à
  venda** (O Povo 15/05/2025, cita o servidor responsável). Dois riscos adjacentes: (a) a CMFor tem TI
  interna forte e pode **resistir a comprar de fora**; (b) precedente **AuditaFor** — a CMFor **cede
  sistemas próprios de graça** a câmaras vizinhas (Maracanaú 2023) → vetor de competição **não-comercial**
  dentro do beachhead. Mais um motivo para **não liderar por Fortaleza**.

## D. Síntese cross-tema (o que isto força)

1. **Pricing (`06`) — a maior mudança.** Trabalhar com **dois ACVs**: **mediana conservadora ~R$ 24–30k**
   (base) e **alvo "produto completo" ~R$ 55–64k** (logo abaixo do teto de dispensa, justificado por
   IA+painel+SLA+migração). O delta é a tese de upsell. **Não cravar o pacote padrão em R$ 65k** como se
   fosse a mediana — seria 2× o mercado-base.
2. **ICP (`03`) — tensão nova a resolver.** Se a base barata (~R$ 24k) provavelmente fica no SAPL ou em
   portais de R$ 12k, **o ICP não é "qualquer câmara de 10k–100k"** — é a câmara que **já demonstra
   willingness-to-pay** por sistema legislativo real (painel/migração, R$ 40k+). Segmento **menor e mais
   rico** que o ICP atual de `03`. Isto **tensiona** o ICP "base da pirâmide". → marcar como T6.
3. **Posicionamento (`04`).** Heróis ficam: **profundidade de IA + SLA de sessão + TCE-como-dado +
   confiança operacional**. **Sair** de "temos IA" (paridade) e de "tramitação digital" (SAPL faz grátis).
4. **Concorrência (`02`).** Corrigir Legisoft=Virtualiza; adicionar Govsys/Legiflow como ameaça #1 entre
   especialistas; criar tier de hardware; registrar vetor não-comercial (AuditaFor) no beachhead.
5. **Mercado (`01`/SAM).** ACV mediano real puxa o SAM para baixo se medido pela base; mas o segmento-alvo
   (que paga R$ 40k+) é o numerador certo. Penetração do SAPL por porte/região = **dado a produzir**, não
   a consumir. SAM continua **faixa**, não medição, até a enumeração CT + janela PNCP completa.

## E. Fontes (acesso 19/06/2026)

**Q10 (SAPL):** Interlegis/SAPL (senado.leg.br/interlegis/produtos/sapl) · SAPL-R "4,3 mil casas com ACT"
(senado.leg.br/noticias 22/04/2020) · DataSenado Panorama (senado.leg.br/institucional/datasenado/panorama)
· **Dourados troca Lamper R$38k/mês por SAPL** (capitalnews.com.br …/441583; progresso.com.br 02/06/2026) ·
Ilha Comprida Contrato 13/2023 (sapl.ilhacomprida.sp.leg.br/docadm) · GitHub interlegis/sapl · crt.sh
(método de enumeração, dump truncado — não representativo).
**Q2 (PNCP):** API `pncp.gov.br/api/consulta/v1/contratacoes/publicacao` (modalidades 8=dispensa,
9=inexigibilidade; janela 20240301–20240310, completas; 6=pregão e 2025 NÃO raspados) · endpoint
`/contratos` (fornecedor+vigência, não raspado em volume) · validação: Ipueiras-CE (mabus.com.br),
Caravelas-BA, Ribas-MS (ribasdoriopardo.ms.leg.br/noticias "economia R$170k").
**Q11 (concorrência):** Legisoft/Virtualiza (legisoft.com.br) · Govsys/Legiflow/LegIA (govsys.com.br,
legiflow.com.br, legia.app, capimgrosso.legiflow.com.br, gramado.rs.leg.br) · Fortaleza MaraIA/CMFor360
(opovo.com.br 15/05/2025; camaramaracanau.ce.gov.br/convenios — AuditaFor) · VotoAqui (votoaqui.com.br) ·
Softcam (softcam.com.br; camaramatao.sp.gov.br edital) · Città (eprocleg.com.br; camaraviamao.rs.gov.br) ·
LegisFácil (legisfacil.app) · Betha (betha.com.br/solucoes) · IPM (ipm.com.br) · Elotech OXY
(pontaldoparana.pr.leg.br) · TCE-ES CidadES (tcees.tc.br).

**Não confirmado (não inventar) [pós-rodada 3]:** UX visual (datada/moderna) dos especialistas;
venda por dispensa dos especialistas (exceto Softcam=pregão, VotoAqui=planos); ACV fora do CE com
vigência real (a amostra de contrato-assinado da rodada 3 é 100% CE — ver F.2); instâncias SAPL
auto-hospedadas em `.gov.br` (invisíveis ao Certificate Transparency).

---

## F. Rodada 3 — completions (Q10-completo + Q2-completo) · 19–20/06/2026

### F.1 · Q10-completo — **censo de penetração do SAPL por porte e região** (a parte que ninguém publica)

- `[FATO]` **Censo de 1.560 instâncias municipais `sapl.<mun>.<uf>.leg.br`** (Certificate Transparency,
  **união de duas janelas CT** — recentes + vigentes; 1.555/1.560 cruzadas com população IBGE 2025).
  **Supera o "~1.200"** — não por crescimento, mas porque a cifra antiga era ela própria um piso sem
  corte. O piso de 115 da rodada 2 era artefato de janela única truncada. **Método agora é censo, não piso.**
- `[FATO]` **Nordeste = 511 instâncias = 28,5% do universo NE (1.794 câmaras).** No agregado, NE (32,8%
  do total nacional de instâncias) **não difere muito da média**. O que importa é a curva por porte:
- 🔴 `[FATO]` **A penetração do SAPL CRESCE com o porte** (SAPL ÷ universo de câmaras IBGE):

  | Faixa (hab) | Penetração NE | Penetração Brasil |
  |---|---|---|
  | <10k | **25,2%** | 21,3% |
  | 10–20k | 23,0% | 26,9% |
  | 20–50k | 30,3% | 33,7% |
  | 50–100k | **50,0%** | 45,1% |
  | >100k | **53,6%** | 44,4% |

- 🔴 `[INF]` **Inversão estratégica:** a **base da pirâmide está MENOS saturada** — ~75% das câmaras NE
  **<20k hab NÃO estão em SAPL** (~1.190 câmaras abertas, o maior pool absoluto). O **topo está mais de
  50% em SAPL** → lá o jogo é **migração/deslocamento**, não greenfield. Quem dimensionou o SAM assumindo
  "SAPL tomou ~20% uniforme" **subestimou o alvo nas pequenas e superestimou nas grandes**.
- `[FATO]` **Beachhead:** **CE tem só 50 instâncias SAPL (3,2% do total), a menor entre os grandes
  estados do NE** (atrás de PE 91, PI 81, RN 74, PB 72, BA 72). Menos SAPL instalado para deslocar em CE
  — favorável ao beachhead **em incumbência-SAPL** (mas ortogonal ao problema do CMFor360/Softcam, `11`C).
- `[GAP]` 1.560 é **piso brando**: auto-hospedadas em `.gov.br` não emitem cert `.leg.br` e não aparecem.
  "75% aberto" assume ausência-de-host = ausência-de-SAPL (válido salvo autohospedadas invisíveis). SP
  ficou subcontado (rate-limit numa das janelas) — não é NE, baixo impacto.

### F.2 · Q2-completo — **ACV corrigido: o homologado subestimava o contrato assinado**

- 🔴 `[FATO]` **A mediana ~R$ 24–26k da rodada 2 era erro de medição.** Cruzando com o endpoint
  `/contratos`, o valor **homologado** da licitação **subestima sistematicamente o contrato assinado**:
  Iguatu/CE homologou R$ 32k → contrato real **R$ 142.500**; Aracati/CE teve dispensa headline de R$ 22,8k
  **+ contrato-plataforma separado de R$ 475.000**. Mediana ancorada em **contrato assinado ≈ R$ 76–100k/ano**.
- `[FATO]` **Premissa "12 meses" CONFIRMADA:** vigências observadas `[8,9 · 9,7 · 9,7 · 12 · 12 · 12 · 12 · 12]`
  meses; os <12m terminam em 31/dez (exercício orçamentário anual, não plurianual). **Zero contratos de
  24–60 meses.** A normalização anual estava correta.
- `[FATO]` **Pregão existe para software legislativo e é mais caro:** mediana pregão **~R$ 138k/ano** vs
  dispensa **~R$ 75k/ano**. Mas **pregão é raro** (2 de 329 pregões de CE em 15 dias = 0,6%) — **câmara
  compra legislativo majoritariamente por dispensa.**
- 🔴 `[FATO]` **Teto de WTP muito acima de R$ 65k:** a **suite completa** real de Aracati (SaaS + migração
  + treino + suporte + manutenção evolutiva + cloud — *exatamente o escopo do O Plenário, migração inclusa*)
  custou **R$ 475.000/ano**. O "~R$ 55–64k" não é teto de disposição a pagar — é o **teto da modalidade
  dispensa** (Lei 14.133). Por isso tantos deals batem exatamente em R$ 59–62k: o teto de dispensa é **ímã
  de preço**. **Vender acima de ~R$ 60k/ano obriga o cliente a pregão** (quebra de packaging no go-to-market).
- `[FATO]` **O grátis não é grátis:** Cascavel/CE paga **R$ 61,6k/ano só para *manter* o SAPL + e-Democracia
  + LEG.BR** do Interlegis → custo de manutenção do stack gratuito ≈ um SaaS pago. **Argumento comercial.**
- `[FATO]` **Concorrentes novos (CE):** INTGEST (2 pregões), I SISTEMAS, AC2B, SYNTERIS, ITRANSPARENCIA,
  S&S Informática, CLIPES.
- ⚠️ `[GAP/CONFOUND]` **A amostra de contrato-assinado da rodada 3 é 100% Ceará (n=8).** O salto R$24k→R$76k
  mistura **dois efeitos**: (1) gap homologado→assinado (real, confirmado em 2 casos) e (2) **CE pode pagar
  mais que BA/RS** (rodada 2). Não atribuir tudo ao método. **Robusto apesar do confound:** assinado>homologado;
  câmara de dispensa precifica até o teto (~R$60k); teto de WTP >> R$65k; pregão raro.

### F.3 · Síntese rodada 3 — a **matriz porte × saturação × ACV** (decide T6)

`[INF]` Cruzando F.1 e F.2, o mercado-alvo se separa em três zonas — e isto **dá dado real à T6**:

| Zona (hab) | Saturação SAPL (NE) | ACV observado | Jogo | Tensão |
|---|---|---|---|---|
| **Base <20k** | baixa (~23–25%) → **greenfield** | dispensa, **~R$ 60k** (teto) | volume, ticket no teto de dispensa | **maior pull p/ o grátis** (zero-TI; caso Dourados) |
| **Meio 20–50k** | média (~30%) | dispensa **~R$ 60–76k** | **sweet spot:** orçamento + pouca incumbência SAPL | menos atrito dos dois lados |
| **Topo 50k+** | alta (~50–54%) → **migração** | pregão **R$ 100–475k** | ticket alto, full-suite | **gravidade do grátis** mesmo no topo (Dourados ~250k foi pago→SAPL) |

🔴 **Leitura para T6:** o segmento "que já paga R$ 40k+" (T6) **é o topo — mas o topo é o mais saturado de
SAPL e o que demonstrou fuga pago→grátis.** A base é greenfield e barata mas grávida do zero. **O candidato
mais limpo é o MEIO (20–50k hab):** orçamento de dispensa no teto (~R$60k, ciclo de 3 dias), SAPL ainda em
~30% (pouca migração forçada), longe da gravidade-zero das menores. **Recomendação de ICP:** centrar o wedge
em **20–50k hab do NE fora do CE**, com a oferta-completa (pregão R$100k+) reservada para upsell/contas grandes.
