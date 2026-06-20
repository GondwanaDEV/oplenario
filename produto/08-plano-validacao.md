# 08 · Plano de Validação

Skill: `ecc:market-research` (+ `deep-research`). Agrega **todas** as lacunas 🔎 dos temas `01`–`07`
e do §13. **A discovery de produto não está "completa" até as P0 fecharem.** Define a varredura
sourced única combinada no fork (B).

---

## 1. Lista-mestra de perguntas abertas (priorizada)

| # | Pergunta | Alimenta | Método | Prioridade |
|---|---|---|---|---|
| Q1 | apartado vs. embutido | 01,02,03 | editais (rodada 1) | ✅ **Resolvida**: é apartado [FATO 4/5]; ameaça real = SAPL grátis, não generalistas (`09`) |
| Q2 | ACV real por porte | 01,06 | PNCP API | ✅ **RESOLVIDA (`11`F.2):** homologado subestima assinado; dispensa-tier ~R$ 55–62k, plataforma R$ 100–142k, full-suite R$ 475k; vigência 12m confirmada. *Confound: amostra-assinada 100% CE.* |
| Q3 | Teto de dispensa (Lei 14.133) | 01,06,07 | decreto | ✅ **R$ 65.492,11** (Decreto 12.807/2025) (`09`) |
| Q10 | **Penetração do SAPL por porte/região** (variável-pivô do SAM) | 01,06 | enumeração CT × IBGE | ✅ **RESOLVIDA (`11`F.1):** censo 1.560 instâncias; NE 28,5%; **penetração cresce com porte** (<20k ~23% → >100k ~54%); base greenfield |
| Q11 | Betha "Legisoft"/IPM têm plenário/votação? | 02 | sites + demos | ✅ **Não** (`11`C): Legisoft é da **Virtualiza** (não Betha); generalistas sem legislativo; **IA já é paridade** |
| Q4 | Profundidade competitiva: features, presença NE, 3 fraquezas não verificadas de §5 | 02 | demos/trials + editais | P1 |
| Q5 | Validação de personas / gatilho de compra / quem decide | 03,04 | entrevistas (campo) | ✅ **RESOLVIDA (`12`A):** decisor = servidor inicia→presidente assina→jurídico veta; **gatilho TCE-dominante** (inbound baixo) |
| Q6 | Recalibragem de beachhead (T1): CE vs. MA/PI/RN/PB | 07 | combina Q1+Q4 por geografia | P1 |
| Q7 | Willingness-to-pay, normas de contrato/reajuste, quem escreve o edital | 06,07 | entrevistas + contratos | ✅ **RESOLVIDA (`12`B):** WTP R$ 50–70k (médias), >R$ 80k força pregão; edital copiado de vizinha→entregar modelo TR; timing 1º sem. |
| Q8 | Método de medição de "tempo economizado" | 05 | design de produto + baseline cliente | P2 |
| Q9 | Difusão de "gravação como registro oficial" (§13) | 04,05 | regimentos/TCE | P2 |

## 2. Dois trilhos de pesquisa

- **Trilho DESK (ECC pode rodar agora):** Q1, Q2, Q3, Q4, Q9 — leis, editais, portais de
  transparência, sites/demos. É a **varredura sourced única** do fork (B). Ferramentas:
  `ecc:competitive-platform-analysis`, `ecc:market-research`, `deep-research`, busca web.
- **Trilho PRIMÁRIO (humano-liderado, ECC prepara):** Q5, Q7, Q8 — entrevistas com 5–10 câmaras
  (§13). ✅ **Roteiro entregue em `10-roteiros-entrevista.md`** (triagem ICP + script por persona +
  bloco Q7 willingness-to-pay/edital + consolidação). Execução (agendar/entrevistar) é do Emilio/time.

## 3. Critério de "discovery completa"

`[REC]` A trilha de produto fecha quando: **(a)** Q1–Q3 (P0) respondidas com fonte → TAM/SAM/SOM e
pricing deixam de ser faixa; **(b)** T1 (beachhead) resolvido com dado (Q4/Q6); **(c)** personas
validadas o suficiente para cravar mensagem (Q5). Q8/Q9 (P2) podem seguir como itens vivos sem
travar o lançamento.

## 4. Próximo passo concreto

**Rodadas 1 (Q1,Q3), 2 (Q10,Q2,Q11), 3 (completions) e o Trilho PRIMÁRIO (Q5,Q7) concluídos** — ver
`09`, `11`, `12`. ✅ **A DISCOVERY DE PRODUTO ESTÁ COMPLETA** pelo critério §3 (a+b+c atendidos): TAM/
SAM/SOM e pricing deixaram de ser faixa, T1 (beachhead) resolvido, personas validadas em campo. **ICP
cravado:** câmara de **20–50k hab do NE fora do CE com gatilho TCE ativo**.

**Itens vivos que NÃO travam o lançamento** (saem da pesquisa, entram em produto/arquitetura):
1. **T7 (novo, `05`/`12`C) — decisão de produto:** a **ata-por-IA** é o herói de compra mas está como
   não-meta no PRD. Emilio decide se entra na V1. **Mais importante que qualquer desk restante.**
2. **T5 (cross-track):** "TCE-CE coberto na V1" depende da DSL do motor de compliance (§22.7). Arquitetura.
3. **Q8/Q9 (P2):** método fino de "tempo economizado" (âncora ~200h/ano já posta, `12`D) e difusão da
   gravação-como-registro — itens vivos, não bloqueadores.
4. **Desks opcionais (ROI baixo):** ACV-assinado fora do CE (confound `11`F.2); teardown player-a-player (Q4).

**A trilha de discovery de produto/comercial encerra aqui.** O próximo movimento é **decisão de produto
(T7) + retomada da arquitetura (Eixo C)**, não mais pesquisa de mercado.
