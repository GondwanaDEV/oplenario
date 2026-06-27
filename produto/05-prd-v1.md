# 05 · PRD da V1 (formalização)

Skill: `ecc:prp-prd` / `ecc:plan-prd`. Reusa §5–§18 (apostas, escopo dos 10 módulos, ordem, régua).
**Este arquivo NÃO re-deriva escopo** — o §16 já é forte. Ele dá **forma de PRD** e adiciona o que
falta: problema, metas/não-metas, **métricas de sucesso** e dependências de risco. Detalhe de escopo
permanece em §16 (referência, não cópia). **Convenção:** `[FATO]` / `[INF]` / `[REC]` · 🔎.

---

## 1. Problema

Câmaras pequenas/médias operam o fluxo legislativo (proposição → tramitação → sessão → publicação)
em sistemas datados ou fragmentados: servidor perde horas em trabalho braçal, cidadão não tem
transparência real, e há risco de incidente regulatório (TCE). Os incumbentes ou têm UX fraca
(especialistas) ou enfiam o legislativo como adjacência de um ERP (generalistas).

## 2. Metas e não-metas

**Metas da V1:** entregar os **10 módulos** de §16 em 4 meses (§18), materializando as 3 apostas
(IA copiloto · UX 3 públicos · confiança operacional). 🔴 **Meta-herói (decisão Daouda Traore, 20/06/2026):**
a **ata automática a partir do áudio** entra na V1 como feature-âncora de entrada — é o nº 1 motivo de
compra do campo (`12`C) e a arma que vence o SAPL grátis. **Passa a régua de §15** (requisito de cliente
validado em campo, não escopo especulativo).

**Não-metas (explícitas):** gabinete de vereador (§16.7 — fora para sempre na tese atual); suite
administrativa (Rota D, anos 2+); plataforma aberta/APIs públicas (§5 — V2); conectores automáticos de
migração (§16.9 — satélite). *(A "geração automática de ata" saiu das não-metas — virou meta-herói, ver §2.)*

✅ **T7 RESOLVIDA (decisão Daouda Traore, 20/06/2026): ata-por-IA ENTRA na V1.** Cauda que a decisão puxa, a
tratar no design/arquitetura (não anula a decisão — qualifica-a):
- **Dependência de áudio (`12`D):** a transcrição só é confiável com captação boa; áudio ruim de plenário
  (microfonia/eco) degrada a IA. → **a captação tem que fazer parte da oferta** (conecta §22.6) — ata-IA
  **não** é só software. Materializa-se no **Eixo C/arquitetura**, não aqui.
- **Pressão de cronograma (§18):** somar pipeline de áudio→transcrição→sumarização IA ao escopo de 4 meses
  **aperta** o plano; ou o time sobe, ou outro módulo de paridade cede prioridade. Flag para §18, não resolvido.
- **Dois modos de valor (`12`D):** onde o regimento já fez a gravação A/V virar registro oficial, a ata-IA
  é **registro oficial** (barra de confiabilidade/auditoria mais alta); onde não, é **produtividade**.
  **[REC]** V1 mira "produtividade" (barra regulatória menor); "registro oficial" como configuração depois.

## 3. Escopo (referência a §16 — não copiar)

10 módulos: 16.1 Identidade/Auditoria · 16.2 Cadastros · 16.3 Processo Legislativo (coração) ·
16.4 Sessões Plenárias · 16.5 Transparência/Portal · 16.6 Participação Cidadã · 16.7 Experiência
Vereador (app) · 16.8 Camada de Confiança · 16.9 Migração · 16.10 Operação/SLA/Compliance.
**Diferenciação** mora em 16.3 (copiloto, busca), 16.5 (resumo cidadão), 16.7 (app); o resto é
**paridade** (§6). Ordem de construção: §18.

## 4. Métricas de sucesso — LACUNA que o PRD precisa fechar

§4 cita "tempo economizado mensurado, NPS, retenção >95%" mas **sem definição operacional**. Proposta:

| Métrica | Definição candidata `[INF]` | Método 🔎 |
|---|---|---|
| **Time-to-go-live** | dias do contrato à 1ª sessão no ar | ≤ 30 dias (§16.9) — medível direto |
| **Ativação** | 1ª sessão gravada+transcrita **e** 1ª ata anexada/indexada | evento de produto |
| **Tempo economizado/servidor** | horas/semana antes vs. depois | ✅ **âncora de campo (`12`D):** ata ≈ **4h/sessão ≈ ~200h/ano** por câmara — usar como baseline declarado + confirmar por telemetria |
| **NPS servidor / vereador** | survey trimestral | priorizar servidor (fica, §14 R3) |
| **Retenção logo** | % câmaras que renovam | meta >95% (§4) — só medível após 1º ciclo |

**[REC]** Definir o **método de "tempo economizado"** é trabalho de produto + cliente (tema 08); sem
ele, o pitch central ("tempo economizado", §20) não tem como ser provado.

## 5. Dependências e riscos de PRD

- **T5 (risco cross-track):** "TCE-CE totalmente coberto na V1" (§16.10) é compromisso de PRD que
  **depende** do motor de compliance (§22.7), cujas listas granulares da DSL seguem "a transcrever"
  (§22.7.4). **A promessa comercial está à frente da especificação técnica.** Rastrear: ou o Eixo
  C/B fecha a tempo, ou o escopo TCE-CE da V1 recua. **Não é decisão de produto resolver — é de
  flag.**
- **Stack e time indefinidos** (§18, §19): o mês 0 depende de decisões de stack ainda não tomadas;
  4 meses exige ≥8 eng + designer + PM + especialista em regimento. PRD assume isso; não está fechado.
- **Régua de escopo (§15)** é o **controle de mudança** deste PRD: todo "pedacinho" novo passa pelas
  4 perguntas antes de entrar. Mecanismo anti-inflação já existe — usar.
- ⚠️ **Riscos de produto levantados em campo (`12`D), a tratar no design:** (a) **áudio ruim do plenário
  degrada a transcrição por IA** — a captação tem que ser parte da oferta (conecta §22.6), não premissa;
  (b) **internet oscila** — exigir resiliência/modo leve na sessão (reforça SLA-de-sessão, 16.10); (c)
  **migração sem perda + auditoria** é promessa existencial, não feature (16.9) — o trauma de perda de
  dado é objeção profunda; (d) **gravação A/V como registro oficial** (tendência regimental) muda o valor
  da ata-IA de "produtividade" para "registro oficial" conforme o regimento da câmara.

## 6. Recommendation

Tratar este PRD como **formalização aprovada do escopo §16** + **3 itens abertos reais**: (a) definir
método das métricas de sucesso; (b) resolver/rastrear T5 (TCE-CE vs. DSL); (c) confirmar stack/time
(trilha de arquitetura). O escopo em si **não precisa relitígio** — está fechado e disciplinado.
