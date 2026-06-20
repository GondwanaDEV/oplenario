# 02 · Concorrência

Skill: `ecc:competitive-platform-analysis`. Reusa §13 (UX fraca confirmada), §21 (lista de players),
§14 (riscos de consolidação). **Convenção:** `[FATO]` / `[INF]` / `[REC]` · 🔎 = fonte ao vivo pendente.

---

## 1. Executive summary

O documento-mestre **nomeia** os concorrentes e fez **uma** verificação real (UX fraca em três
especialistas legislativos — §13), mas **não há teardown competitivo**: nada sobre features reais,
preço, termos de contrato, presença no Nordeste, ou win/loss. A tese inteira de diferenciação
("incumbentes têm UX datada, portal de fachada, IA superficial, migração dolorosa" — §5) está
apoiada em **um ponto de evidência**. Este arquivo organiza o que se sabe, marca o que é asserção,
e define exatamente o que o `ecc:competitive-platform-analysis` precisa sourcing.

## 2. Mapa de players (do §21) — a maioria das células é LACUNA

### Especialistas legislativos puros (concorrentes diretos da V1)
| Player | Produto (realidade, não marketing) | Foco geográfico | Preço/contrato | Força | Fraqueza | Fonte |
|---|---|---|---|---|---|---|
| Legisoft | 🔎 lacuna | 🔎 | 🔎 | 🔎 | `[FATO]` UX fraca (verificado §13) | §13 |
| Legiflow | 🔎 lacuna | 🔎 | 🔎 | 🔎 | `[FATO]` UX fraca (verificado §13) | §13 |
| Nuvem Legislativa | 🔎 lacuna | 🔎 | 🔎 | 🔎 | `[FATO]` UX fraca (verificado §13) | §13 |
| Legislarr | 🔎 lacuna | 🔎 | 🔎 | 🔎 | 🔎 | §21 |
| aLegislativo | 🔎 lacuna | 🔎 | 🔎 | 🔎 | 🔎 | §21 |
| **Softcam** | 🔎 lacuna | `[INF]` forte no **CE** (§13) | 🔎 | `[INF]` incumbência cearense | 🔎 | §13 |

### Generalistas de gestão pública (concorrência indireta / ameaça de bundle)
| Player | Papel competitivo | Fonte |
|---|---|---|
| IPM, Betha, Fiorilli, Elotech | ~~`[INF]` enfiam o legislativo como adjacência~~ **DERRUBADO (rodada 2, `11`):** `[FATO]` **nenhum tem módulo legislativo** (sessão/votação). Betha não tem produto legislativo; IPM só publica atos administrativos; TOTVS/Fiorilli/Elotech = 100% back-office. **Não embutem o legislativo** → ameaça legislativa **baixa**. | §2, §21, `11` |

## 3. Key findings (rodada 1 `09` + **rodada 2 `11`**)

- `[FATO]` **O concorrente real é o SAPL grátis, não os generalistas.** ~1.200 câmaras rodam SAPL
  (Interlegis/Senado, hospedado de graça, **já com painel/votação**) — número **estagnado há ~10 anos
  e de confiança rebaixada** (pode estar contaminado pela cifra do Portal Modelo; ver `11`A). **Os ERPs
  generalistas ignoram o legislativo** → hipótese "a ameaça são os generalistas" **REVERTIDA e agora
  confirmada como falsa**.
- 🔴 `[FATO]` **DUAS correções da rodada 1 (rodada 2, `11`C):** (a) **"Legisoft é da Betha/Nuvem
  Tecnologia" é FALSO** — Legisoft é da **Virtualiza Tecnologia**, especialista independente; (b)
  **"ninguém tem IA" é FALSO** — há IA legislativa **em produção**: **Govsys/Legiflow (LegIA)** (treinada
  em 2M+ proposições), **Città/Eprocleg**, **LegisFácil**, + **MaraIA** de Fortaleza. → **"ter IA" já
  não diferencia**; defensibilidade = **profundidade** de IA, não existência.
- `[FATO]` **Ranking de ameaça revisado:** **(1) SAPL grátis** (incumbente estrutural, roda no próprio
  beachhead); **(2) especialistas modernos com IA — Govsys/Legiflow à frente**, depois Legisoft/Virtualiza,
  VotoAqui, Softcam, Città, LegisFácil; **(3) hardware/painel** (Visual, Imply, Escal — legado, baixo p/
  modelo software-only); **(4) ERPs generalistas — BAIXO**.
- ⚠️ **Inconsistência a resolver:** §13 marcou Legiflow como "UX fraca", mas a rodada 2 achou
  **Govsys/Legiflow como o especialista moderno líder em IA**. A asserção de UX de §13 (ponto único,
  do fundador) **conflita** com o achado web → as células de UX precisam de **verificação visual** (vídeos/
  demos), pendente em Q4. Não usar "UX fraca dos especialistas" no pitch até checar player a player.
- `[FATO]` **Intel de beachhead (T1):** Fortaleza **construiu o CMFor 360 in-house com IA própria
  (MaraIA)** — `[FATO]` **construção interna da Câmara, não fornecedor à venda** — terceiriza só o
  painel (Pregão 05/2025, impugnado pela Visual Sistemas), **e roda SAPL público**. **Nenhum especialista
  moderno tem cliente no CE.** Risco adjacente: precedente **AuditaFor** — a CMFor **cede sistemas próprios
  de graça** a câmaras vizinhas (Maracanaú 2023) → competição **não-comercial** no beachhead. **Reforça não
  liderar por Fortaleza.**
- `[FATO]` **Duas brechas de posicionamento desocupadas (munição p/ `04`):** **SLA de janela de sessão**
  (ninguém promete uptime no horário da plenária; o SAPL joga a disponibilidade para a Casa) e **compliance
  TCE-como-dado no legislativo** (só existe no mundo contábil de prefeitura, não no legislativo).
- `[FATO]` **Fornecedores reais no beachhead (CE) — rodada 3 `11`F.2:** contratos de câmara no PNCP
  revelam **INTGEST** (2 pregões de plataforma SaaS+mobile, R$ 134–142k), **I SISTEMAS** (full-suite
  R$ 475k), **AC2B Tecnologia** (SaaS + ETP com IA, R$ 22,8k), **SYNTERIS, ITRANSPARENCIA, S&S
  Informática, CLIPES**. São os concorrentes que **efetivamente ganham licitação de câmara no CE** — alvo
  primário do teardown de Q4 (features/UX/IA player a player ainda pendente).
- `[FATO]` **Censo de incumbência-SAPL (rodada 3 `11`F.1):** CE tem só **50 instâncias SAPL** (menor entre
  os grandes do NE) → menos grátis-instalado para deslocar no beachhead; mas a incumbência **paga** no CE é
  ativa (os fornecedores acima). Ameaça no CE = especialistas privados + CMFor caseiro, **não** o SAPL.
- `[FATO]` **Risco de consolidação** (§14 R1) inalterado: janela de tempo é ativo estratégico.

## 4. Implications

- **[REC]** O teardown precisa responder primeiro a **uma** pergunta de tese, não a 50 features:
  **"que % das câmaras-alvo compra legislativo apartado vs. embutido no ERP?"** Se a resposta for
  "quase todas embutem", a Rota D recalibra (vira disputa contra generalistas, não especialistas).
- **[REC]** Validar **player a player** as outras 3 fraquezas de §5 antes de usá-las no pitch —
  hoje são alegações, e alegação não verificada vira passivo numa POC.
- **[REC]** Mapear **presença real no Nordeste** de cada player (não presença nacional) — é o que
  define quem encontramos no beachhead.

## 5. Risks & caveats

- Sites de fornecedor B2G são marketing puro; **a realidade do produto sai de demos, editais e
  usuários**, não do site. Não tratar copy de fornecedor como fato.
- Preço B2G muitas vezes só aparece em **contratos publicados** (transparência), não em tabela.
- "Incumbente forte" é regional: ganhar de Softcam em Fortaleza ≠ ganhar dele em Recife (§13).

## 6. Recommendation

Rodar `ecc:competitive-platform-analysis` com escopo **deliberadamente estreito**: (1) responder a
pergunta de tese apartado-vs-embutido; (2) presença Nordeste por player; (3) preço/termos a partir
de contratos publicados de 5–8 câmaras; (4) verificar as 3 fraquezas não confirmadas de §5. Saída
preenche as células 🔎 desta tabela e dá veredito sobre **T1 (beachhead)**.

## 7. Sources

- Interno: §2, §5, §13, §14, §21 (asserções e um ponto verificado de UX).
- **Pendente (fonte primária):** demos/trials dos players; editais e contratos publicados em
  portais de transparência de câmaras do Nordeste; registros de licitação nos TCEs estaduais.
