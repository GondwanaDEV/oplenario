# Discovery de Produto/Comercial — O Plenário

> **Trilha nova, separada da arquitetura.** O `documento-mestre-camaras.md` continua sendo
> o SSOT das decisões **já cravadas** (estratégia §1–§21, arquitetura §22). Esta trilha
> **não engorda o documento-mestre** — ela produz artefatos de produto/comercial em
> arquivos pequenos, um tema por arquivo. Quando um tema fechar uma decisão, registra-se
> aqui e referencia-se de volta no documento-mestre por número de seção, sem copiar conteúdo.

## Método desta trilha

- **Reuso crítico:** o material estratégico (§1–§21) é insumo, não verdade final. Reaproveitar,
  reestruturar em forma de produto, e **marcar o que é fato, inferência e aposta**.
- **Padrão de honestidade (skill `ecc:market-research`):** toda afirmação importante precisa de
  fonte; dado velho é sinalizado; o contraditório entra; cada artefato termina numa **decisão**,
  não num resumo.
- **Um tema por arquivo.** Crítica explícita de lacunas e inconsistências em cada um.
- **ECC conduz:** cada tema mapeia para a skill ECC correta (coluna abaixo).

## Mapa de temas → skill ECC → estado

| # | Tema (arquivo) | Skill ECC | O que já existe (reusar) | Estado |
|---|---|---|---|---|
| 01 | Mercado / TAM-SAM-SOM (`01-mercado-tam-sam-som.md`) | `ecc:market-research` + `deep-research` | §4 metas de unidades, §13 premissas não validadas | 🟢 Rodada 3: censo SAPL 1.560 (NE 28,5%, penetração ↑ c/ porte); ACV corrigido (dispensa ~R$60k, full-suite R$475k); SAM ~1.283 NE não-SAPL (`11`F) |
| 02 | Concorrência (`02-concorrencia.md`) | `ecc:competitive-platform-analysis` | §13 (UX fraca confirmada), §21 lista de players, §14 riscos | 🟢 Rodada 2: ranking revisado (Govsys/LegIA #1 especialista); Legisoft=Virtualiza; **IA já é paridade**; generalistas s/ legislativo (`11`) |
| 03 | ICP & personas decisoras (`03-icp-personas.md`) | `ecc:market-research` / `ecc:prp-prd` | §11 três públicos decisores | 🟢 **Validado em campo (`12`):** ICP 20–50k NE; decisor servidor→presidente→jurídico; gatilho TCE |
| 04 | Posicionamento & mensagem (`04-posicionamento.md`) | `ecc:brand-discovery` / `ecc:brand-voice` | §1 nome, §11 "complementamos não substituímos", §20 princípios | 🟢 **Validado (`12`):** pivô respondido (paga por tempo/risco/suporte); ao presidente vender "sair do TCE", não "transparência" |
| 05 | PRD da V1 (`05-prd-v1.md`) | `ecc:prp-prd` / `ecc:plan-prd` | §5–§18 (escopo já bem detalhado) | 🟡 Formalizado; métricas de sucesso = lacuna |
| 06 | Modelo comercial & pricing (`06-modelo-comercial.md`) | `ecc:market-research` (pricing) | §13/§19 tensão pregão↔MRR (aberta) | 🟡 ACV real sourced (bimodal: ~R$24–30k base / ~R$55–64k completo, `11`); tabela final trava em Q7 (WTP primário) |
| 07 | GTM & motion de venda B2G (`07-gtm-pregao.md`) | `ecc:marketing-campaign` | §11 portas de entrada por persona | 🟡 Duas motions + lighthouse; T1 recalibrado |
| 08 | Plano de validação (`08-plano-validacao.md`) | `ecc:market-research` | §13 "investigações pendentes" | 🟡 9 perguntas P0–P2; trilhos desk/primário |
| 10 | Roteiros de entrevista (`10-roteiros-entrevista.md`) | `ecc:market-research` (primária) | §13 entrevistar 5–10 câmaras; `03` personas | 🟢 Roteiro Q5/Q7 pronto p/ execução humana |
| 13 | Decomposição em features da V1 (`13-decomposicao-features-v1.md`) | — (decompõe §16 + §22.10) | §15 régua, §16 11 módulos, §22 âncoras | 🟢 **Atualizado ao doc-mestre v1.34:** 89 features, 12 módulos = 81 tenant-facing/11 (§16) + 8 supratenant/1 (16.12 `admin_sistema`, §22.10). Incorpora stack §22.9 (passkey, PWA-first, IA híbrida), monólito §22.10 e bloco GAP 1–5 (admin sistema/ente, notificações, relatórios). Cobertura §16 verificada |

Legenda: 🔴 a fazer · 🟡 em andamento/parcial · 🟢 consolidado.

**Evidências sourced:** `09` (desk rodada 1 — Q1/Q3) · `11` (desk rodadas 2–3 — Q10/Q2/Q11 + censo SAPL
1.560, ACV corrigido) · **`12` (campo — Q5/Q7 validados: decisor, gatilho TCE, WTP, edital).**
✅ **DISCOVERY DE PRODUTO COMPLETA (20/06/2026).** Itens vivos não-bloqueadores: **T7** (ata-IA na V1? —
produto), **T5** (TCE-CE vs DSL — arquitetura). Próximo movimento = decisão de produto + Eixo C.

## Ordem proposta (e por quê)

1. **01 Mercado + 02 Concorrência primeiro** — toda a tese Rota D depende de premissas de
   mercado que o §13 marca como **não validadas**. Posicionamento, pricing e priorização de PRD
   herdam erro se a base de mercado estiver errada. É também onde o ECC agrega valor real (dado
   sourced, não conversa).
2. **03 ICP/personas + 04 Posicionamento** — dependem de 01/02.
3. **05 PRD** — o escopo (§16) já é forte; vira PRD formal com métricas e histórias.
4. **06 Pricing + 07 GTM** — dependem de ICP + concorrência + entendimento do ciclo de pregão.
5. **08 Validação** — fecha o ciclo: experimentos para derrubar/confirmar as premissas de risco.

## Inconsistências e tensões abertas detectadas na reconstrução (rastrear até resolver)

- **T1 — Beachhead vs. incumbente.** §1/§15 cravam Fortaleza como beachhead; §13 admite que
  Softcam é incumbente cearense forte e que o wedge real talvez seja municípios médios em
  MA/PI/RN/PB. Decisão de beachhead pode estar contra a realidade competitiva. → temas 01/02/03.
- **T2 — Metas antes da base.** §4 crava 40–80 câmaras nos anos 1–2, mas o TAM atendível
  (% que licita legislativo apartado) é explicitamente **não validado** (§13). Meta numérica
  apoiada em premissa não medida. → temas 01/08.
- **T3 — Caixa vs. ciclo de pregão.** Seed R$5–15M + time ≥8 eng/designer/PM/especialista (§18)
  é burn alto; contrato de pregão é 12–60 meses com ciclo de venda lento (§13). Vender 40–80
  câmaras via pregão em 24 meses pode estar descalibrado com o caixa. → temas 06/07.
- **T4 — Compliance: table stakes ou diferencial?** §20 diz "compliance é pré-requisito, não
  diferencial"; §5 lista "compliance automático TCE" como Aposta 3 (diferencial). → temas 04/05.
- **T5 — Promessa comercial vs. arquitetura inacabada.** "TCE-CE totalmente coberto na V1"
  (§16.10) depende da DSL do motor de compliance, cujas listas granulares seguem "a transcrever"
  (§22.7.4). Promessa comercial à frente da especificação. → cross-track (produto ↔ arquitetura).
- **T6 — ICP "base da pirâmide" vs. "câmara que já paga" (nova, rodada 2 `11`D).** O ICP de `03`
  mira câmara de 10k–100k hab (base da pirâmide). Mas a rodada 2 mostra que a base paga mediana
  ~R$ 24k e tende a ficar no **SAPL grátis** — o vetor de troca corre *pago→grátis* (Dourados). O
  segmento com willingness-to-pay real é o que **já paga R$ 40k+** por sistema legislativo (painel/
  migração) — **menor e mais rico**. Decidir se o ICP é volume-barato ou valor-rico. → temas 03/06/08.
  ✅ **RESOLVIDA (`11`F.3 + campo `12`):** ICP = **meio 20–50k hab do NE fora do CE, com gatilho TCE** —
  nem a base grávida-do-grátis, nem o topo SAPL-saturado.
- **T7 — herói de compra fora do escopo da V1 (campo `12`C + `05`).** ✅ **RESOLVIDA (Daouda Traore, 20/06/2026):
  ata-por-IA ENTRA na V1** como feature-âncora. Cauda a tratar na arquitetura: captação de áudio é parte da
  oferta (§22.6), e o pipeline áudio→transcrição→IA aperta o cronograma de §18. Ver `05`§2.
