# 19 · Camada de atenção por ator — decomposição de features (estilo produto/13)

> **Trilha de produto/UX — definição funcional, pré-design.** Companheiro de
> [`13-decomposicao-features-v1.md`](./13-decomposicao-features-v1.md) (catálogo das 113 features) e
> [`16-revisao-completude-rodada2.md`](./16-revisao-completude-rodada2.md) (auditoria que isolou a "analítica
> por unidade de leitura" como a zona mais fina). Define a **camada de atenção por ator** — *quando cada ator
> entra na plataforma, está claro (a) como estão as coisas e (b) o que exige sua atenção AGORA* — **como
> features, ANTES do design** (o método: funcionalidade primeiro, design depois).
>
> **Status (22/06/2026):** modelo **CONFIRMADO pelo Daouda Traore** — E1/E2/E3 + as duas decisões (a)/(b) abaixo.
> Produzido por workflow (terreno → modelo → decomposição → crítica adversarial); **5 correções da crítica já
> dobradas**. Este doc **não grava o SSOT** — é trilha de produto; reabre §16.11 **pontualmente** (sob *Confirma?*)
> só quando for consolidar no doc-mestre.
>
> **Convenção:** `[FATO]` (verificado no repo) · `[INF]` (inferência) · `[REC]` (recomendação) · `[GAP]` (conteúdo
> não decidido — não inventar). **Severidade:** 🔴 alta · 🟠 média · ⚪ baixa. **§15:** `entra_v1` ·
> `fast_follow` · `diferir` · `decisao_fundacao`. **Famílias:** **S** = situational ("como estão as coisas") ·
> **T** = triage ("o que exige você agora") · **M** = motor (a fundação compartilhada).

---

## 0. Veredito — o que é (e o que não é)

A camada de atenção **não é módulo novo, nem N dashboards, nem verdade durável.** É uma **re-articulação de
leitura** sobre substrato já fechado (§16.11 read-model no módulo `oplenario.paineis`; motor de prazo §22.7.7;
máquina de estados §22.4 eixo C; audit log §16.1). **Quase nada recria** — os read-models 11.1–11.4 / 11.6 / 7.5 /
5.5–5.6 são **consumidos e recortados por ator**, não reconstruídos.

A unidade conceitual única é o **SINAL DE ATENÇÃO**: um read-model derivado, projetado em `paineis`, com 5
atributos canônicos. Cada home de ator é uma **vista filtrada + priorizada** desse sinal; o sinal alimenta **duas
famílias de leitura** sobre o mesmo read-model — exatamente o padrão já provado em `paineis-mesa.html` (vitrine de
cima para a Mesa, prova embaixo para o jurídico).

> **A novidade real** que a camada introduz é a **unificação** — "entrada por ator, com situational + triage lado a
> lado" — que hoje aparece **dispersa por feature**, nunca como um padrão de entrada único. O resto é recorte do que
> já existe.

---

## 1. As decisões confirmadas (o modelo)

| Eixo | Decisão | Justificativa |
|---|---|---|
| **E1 — modelo** | **Sinal de atenção ÚNICO** (não N dashboards) | Disciplina 5 (motor compartilhado) + §22.10 "projeção não tem verdade própria". Adicionar nova home de ator vira *um filtro*, não um épico. `paineis-mesa.html` já prova "um read-model, leituras diferentes". |
| **E2 — origem** | **Híbrido** = os 3 planos do mesmo evento (GAP 4, §22.10) | Já é a arquitetura cravada: outbox/relay (transporte) + SSE efêmero `tempo_real` (quem olha agora → situational) + notificação durável (quem não olha → triage). Única verdade durável = o **ledger de entrega** (UNIQUE+CAS, redelivery no-op). |
| **E3 — atores V1** | **Servidor + Mesa + jurídico + vereador**; comissão/admin/operador/cidadão = fast-follow | Régua §15: read-model sem novo modelo de dado "não é o escopo que a §15 teme". ICP manda os fortes (servidor=POC, Mesa+jurídico=compra, vereador=Aposta 2). Cidadão por design é pontual, não cockpit. |
| **(a) Engajamento C19** | **Número bruto entra_v1**; narrativa/tendência = fast_follow | Fonte contraditória (prosa `produto/16` diz V1, tabelas canônicas L146/L221 dizem fast_follow, r4 não ratificou) + ovo-e-galinha (narrativa de engajamento só existe após meses de portal). O número bruto destrava o KPI do comprador sem inflar a V1. |
| **(b) Triage do operador** | **Núcleo "tenant em risco de perder janela" entra_v1**; resto = fast_follow | É a materialização vivida da tese-manchete (SLA de janela = confiança operacional, Aposta 3). Só esse núcleo sobe; a triage supratenant ampla cresce demand-pulled no beachhead NE. |

**5 correções da crítica adversarial dobradas neste doc:**
1. **Priorização 2D** — o sinal carrega **gravidade ⟂ prazo** (§3). Sinal grave sem prazo materializado (incidente LGPD, remessa rejeitada, prazo ANPD `[GAP]`) não cai mais num balde "sem_prazo" indistinto.
2. **"Lido/visto" pertence à notificação (11.6)**, não ao sinal — o antigo "estado lido do sinal" foi **absorvido por 11.6** (mantém a fronteira sinal≠notificação).
3. **Consent-gate num ponto só** — aplicado no **port de entrega** (ATN-08), não replicado como feature autônoma nem como atributo repetido (era o anti-padrão que o próprio E1 condena).
4. **A Mesa também recebe o sinal de FALHA** (remessa rejeitada/incidente) — antes só servidor/jurídico viam; o presidente é o responsável político (ATN-MESA-13).
5. **Não-co-exibição + dedup multi-destinatário** — o anel da remessa **colapsa** quando o item já está na fila triage da mesma home; um sinal de falha roteado a servidor+jurídico é **um sinal multi-destinatário** (sem duplo push).

---

## 2. As 4 fronteiras (a regra anti-duplicação)

| Conceito | Definição | Distinção load-bearing |
|---|---|---|
| **SINAL DE ATENÇÃO** | Read-model derivado e priorizado: `ator-alvo + gravidade/prazo + UMA ação + deep-link + origem`. | É a camada que **consome** os três abaixo e os recorta por ator. **Aponta uma ação.** |
| **NOTIFICAÇÃO** (11.6) | Evento que **chegou**: feed cronológico (vista sininho/inbox = projeção dropável) + ledger de **entrega** durável idempotente. | É **insumo** da triage, passiva/cronológica. Pode ser puramente informativa. "Lido" mora aqui. A **entrega** é a única verdade durável. Consent-gate: cidadão sim (§22.5); servidor/vereador na função não. |
| **PENDÊNCIA** (11.2) | Tarefa **atribuída** a um ator (assinar, parecer, despachar) com UMA ação e (quando há) prazo. | **Subconjunto** do sinal (o sinal-com-tarefa-atribuída). Toda pendência é sinal; nem todo sinal é pendência. |
| **NÚMERO-DE-READ-MODEL** (11.1–11.4) | Estado agregado/contado: placar 11·1·0, pipeline por estágio, % de presença, anel da remessa. | É a família **situational**, descritiva, **não acionável**. O sinal é a leitura acionável *sobre* o número. Não é BI (report-builder/benchmarking/ranking = V2, guardrail §16.11). |

---

## 3. O SINAL DE ATENÇÃO — schema canônico (a unidade)

| Atributo | O que é | Fonte |
|---|---|---|
| **ator-alvo** | papel/principal que deve ver (`servidor`, `relator:X`, `mesa`, `juridico`, `vereador:X`, `cidadao:consent`, `admin_ente`, `operador`) | resolução RBAC + funções de relação (§22.5 eixo C) |
| **gravidade ⟂ prazo** | **duas dimensões ortogonais** *(correção 1)*: **prazo** = `vence_hoje`/`prazo_legal`/`esta_semana`/`sem_prazo` (do `prazo_dominio_ativo`, §22.7.7) **+ gravidade** = `crítico`/`alto`/`médio`/`baixo`. Um sinal `sem_prazo` mas `crítico` (incidente LGPD, remessa rejeitada) sobe na fila por gravidade. | motor de prazo + classe do evento de origem |
| **ação primária** | **UMA** ação acionável (assinar, dar parecer, revisar ata-IA, abrir remessa, despachar à CCJ, publicar) | tipo do evento de origem → ação canônica |
| **deep-link** | a tela de execução (ata-revisao, remessa/motor, tramitação, ficha-materia) | rota conhecida do front |
| **origem/evento** | o evento de domínio que o gerou, emitido pelo módulo dono via outbox (§22.9 E3) — **nunca fabricado na projeção** | producer do contexto dono |

> **Ciclo de vida:** o sinal é **evento de domínio** na origem, **query derivada** no consumo, e só vira **verdade
> durável** no caso da **entrega externa** (ledger UNIQUE+CAS). Esvazia-se por **evento de conclusão** (parecer dado,
> remessa aceita) — não por marcação manual de UI.

---

## 4. Features — Motor de sinais de atenção (a fundação compartilhada)

| ID | Feature | Fam. | O que faz | Reuso / §22 | §15 | Sev |
|---|---|---|---|---|---|---|
| **ATN-01** | Schema canônico do sinal | M | A unidade única `{ator-alvo, gravidade/prazo, ação, deep-link, origem}` como read-model em `paineis`, dropável/re-projetável | materializa o que 11.1–11.4/11.6 pressupõem informalmente | entra_v1 | 🔴 |
| **ATN-02** | Emissão pelo módulo dono (outbox) | M | O módulo dono publica o evento; o consumer de `paineis` projeta o sinal — evento na origem, query no consumo | outbox/relay + kernel `eventos` (§22.9 E3) | entra_v1 | 🔴 |
| **ATN-03** | Roteamento ator→sinal | M | Mapeia evento+contexto → ator-alvo canônico (relator:X, mesa, juridico…) — é o que torna "nova home" um filtro | RBAC (1.4) + funções de relação + DSL de autorização (1.5) | entra_v1 | 🔴 |
| **ATN-04** | **Priorização 2D: gravidade ⟂ prazo** *(corr. 1)* | M | Ordena a fila por prazo **e** gravidade; sinal grave sem prazo não fica enterrado | `prazo_dominio_ativo` (§22.7.7) + classe do evento | entra_v1 | 🔴 |
| **ATN-05** | Ação primária + deep-link (+ não-co-exibição) | M | UMA ação por sinal + ponteiro à tela; **regra de não-co-exibição** (anel colapsa quando o item já está na fila, *corr. 5*) | telas-alvo já existem | entra_v1 | 🟠 |
| **ATN-06** | Esvaziamento por evento de conclusão | M | Quando o evento que cumpre a obrigação chega, o consumer encerra o sinal (não é "marcar feito" manual) | máquina de estados §22.4 C + ciclo §22.7.7 | entra_v1 | 🟠 |
| **ATN-08** | Ledger de entrega durável + **consent no port** *(corr. 3)* | M | Registra a entrega externa (UNIQUE+CAS, redelivery no-op); **o consent-gate por ator é aplicado aqui, ponto único** | ledger 11.6 / flag 8 / §22.5 | entra_v1 | 🔴 |
| **ATN-09** | Dedup/coalescência **multi-destinatário** *(corr. 5)* | M | Um sinal por (obrigação) com N destinatários — não dois sinais divergentes nem duplo push para servidor+jurídico | idempotência re-stamp S3 (§22.7.7) + redelivery no-op | entra_v1 | 🟠 |
| **ATN-10** | Fan-out ao vivo (SSE) | M | Atualiza filas/placares abertos ao vivo (plano efêmero "quem olha agora") | módulo `tempo_real` + backplane SSE Valkey (§22.6 G) | entra_v1 | 🟠 |
| **ATN-12** | Sweep de avaliação periódica | M | Re-deriva severidade pela passagem do tempo (`esta_semana`→`vence_hoje`) e re-emite ao cruzar limiar | modelo evento+sweep+sob-demanda (§22.7.7) | entra_v1 | 🟠 |
| **ATN-13** | Rebuild / re-projeção do event log | M | Reconstrói toda a projeção dos eventos, preservando só o ledger de entrega (verdade durável) | "read-model dropável" (§22.10) | entra_v1 | ⚪ |
| **ATN-14** | Sinal supratenant — **núcleo "tenant em risco de janela"** *(decisão b)* | M | Mesmo conceito de sinal na esfera do operador; **só o núcleo "vai estourar janela TCE/sessão" entra na V1**; resto demand-pulled | esfera `admin_sistema` disjunta (§22.10); observ. cross-tenant (12.6) | entra_v1 (núcleo) / fast_follow (resto) | 🟠 |
| **ATN-15** | Frase-síntese narrativa (storytelling por IA) | S | Síntese em linguagem natural sobre os números 11.1–11.4 ("storytelling, não tabela") | porta de IA §22.3 + **camada de confiança §16.8** | **diferir** `[GAP]` | ⚪ |
| **ATN-16** | Horas-poupadas / cockpit de produtividade (C20) | S | Leitura de "tempo economizado" (ata-IA gerada vs. revisada, docs, tramitações sem erro) | substrato de eventos existe; **a métrica "hora poupada" é `[GAP]` no PRD** | **decisao_fundacao** | 🟠 |
| ~~ATN-07~~ | ~~Lido/visto do sinal~~ → **absorvido por 11.6** *(corr. 2)* | — | "Lido" é categoria de notificação, não do sinal | — | absorvido | — |
| ~~ATN-11~~ | ~~Consent-gate como feature~~ → **dobrado em ATN-08** *(corr. 3)* | — | Política única (§22.5), um ponto de aplicação no port | — | absorvido | — |

---

## 5. Features — Home do SERVIDOR (decide a POC)

| ID | Feature | Fam. | O que faz | §15 | Sev |
|---|---|---|---|---|---|
| **ATN-S01** | Vista "home do servidor" | M | Recorte do sinal por ator-alvo=servidor/relator, situational no topo + triage embaixo | entra_v1 | 🔴 |
| **ATN-S02** | Pendências priorizadas (fila de ação) | T | Tarefas atribuídas em seções por urgência, UMA ação por item — **matador da POC** | entra_v1 | 🔴 |
| **ATN-S03** | "O que vence" (prazos regimentais/tramitação) | T | Prazos em janela/vencidos com anel honesto; recorte de 11.1 | entra_v1 | 🔴 |
| **ATN-S04** | Remessa ao TCE rejeitada / em risco (R-REM) | T | Sinal de **FALHA** (não countdown verde) com diagnóstico + deep-link ao reenvio guiado | entra_v1 | 🔴 |
| **ATN-S05** | Expediente recebido sem despacho (C58) | T | Documentos recebidos pendentes de autuar/despachar; deep-link ao workstream do recebido *(prazo por C59 = fast_follow)* | entra_v1 | 🔴 |
| **ATN-S06** | Itens adiados a retornar à pauta (C31) | T | Matérias adiadas cujo ciclo de retorno está pendente — para não cair no esquecimento | entra_v1 | 🔴 |
| **ATN-S07** | Convocações sem ciência registrada (C33/N1) | T | Estado de ciência por destinatário; convocação que conta prazo da LOM *(depende de N2, §10)* | entra_v1 | 🔴 |
| **ATN-S08** | Pauta da próxima sessão montando | S | Narrativa de prontidão (o que entrou, o que falta) *(pool determinístico = C30 fast_follow)* | entra_v1 | 🟠 |
| **ATN-S09** | Feed de notificações (insumo) | S | Entrada para o sininho/inbox cronológico — insumo passivo, distinto da fila | entra_v1 | ⚪ |
| **ATN-S10** | Atos sem ICP / numeração pendente (C25) | T | Fila de exceções de conformidade processual do servidor | fast_follow | 🟠 |
| **ATN-S11** | Horas-poupadas / produtividade (C20) | S | Bloqueado pela definição de "hora poupada" (`[GAP]` PRD) | decisao_fundacao | 🟠 |

---

## 6. Features — Home da MESA / PRESIDENTE (decide a compra)

| ID | Feature | Fam. | O que faz | §15 | Sev |
|---|---|---|---|---|---|
| **ATN-MESA-01** | Vista "home da Mesa" | M | Recorte do sinal por ator-alvo=mesa, costura situational + triage | entra_v1 | 🔴 |
| **ATN-MESA-02** | Ilha-placar de saúde TCE (11·1·0) | S | "A Casa está em dia com o TCE-CE" — vitrine de cima / prova de baixo | entra_v1 | 🔴 |
| **ATN-MESA-03** | Anel da próxima remessa + ciclo (azulejo) | S | Próxima entrega obrigatória e seu estado; "Aceita" pendente até a entrega externa | entra_v1 | 🔴 |
| **ATN-MESA-04** | Faixa "o que a Casa entregou" (biênio) | S | 4 números institucionais com referência honesta (sem donut/KPI-card) | entra_v1 | 🟠 |
| **ATN-MESA-05** | Engajamento cidadão — **número bruto** *(decisão a)* | S | Contagem bruta de participação na V1; **narrativa/tendência/picos = fast_follow** | entra_v1 (nº) / fast_follow (narrativa) | 🟠 |
| **ATN-MESA-06** | "O que vence esta semana" (triage) | T | Trilho priorizado de obrigações/prazos, UMA ação por item | entra_v1 | 🔴 |
| **ATN-MESA-07** | Despachos pendentes da Presidência | T | Fila do que aguarda decisão da Mesa (despachar, designar relator) | entra_v1 | 🔴 |
| **ATN-MESA-08** | Prontidão da próxima sessão | S | Hemiciclo de ciência + pool "prontas, fora da pauta" (estático até C30) | entra_v1 | 🟠 |
| **ATN-MESA-09** | Barra de comando da Mesa (superfície de ação) | T | Praticar atos institucionais (convocar, publicar) no contexto; vira histórico assinado | entra_v1 | 🟠 |
| **ATN-MESA-13** | **Sinal de FALHA na home da Mesa** *(corr. 4)* | T | A Mesa recebe o sinal de remessa rejeitada/incidente que antes só servidor/jurídico viam | entra_v1 | 🔴 |
| **ATN-MESA-10** | Frase-síntese da Casa por IA | S | "A Casa está em dia; engajamento subiu 18%; resta 1 remessa" — depende de §16.8 | diferir `[GAP]` | 🟠 |
| **ATN-MESA-11** | Recorte longitudinal do biênio (C23) | S | Série institucional ao longo do mandato (hoje só compara uma linha) | fast_follow | ⚪ |
| **ATN-MESA-12** | Comparativo institucional (C24) | S | Comissões/períodos lado a lado — pressiona o guardrail anti-ranking | diferir | ⚪ |

---

## 7. Features — Home do JURÍDICO (avalia o risco)

| ID | Feature | Fam. | O que faz | §15 | Sev |
|---|---|---|---|---|---|
| **ATN-JUR-01** | Vista "home do jurídico" (gated por papel) | M | Recorte do sinal pela ótica "prova de baixo"; mesmo read-model de `paineis-mesa` | entra_v1 | 🔴 |
| **ATN-JUR-02** | Placar de conformidade lido como prova | S | A ilha-placar como evidência de conformidade para pregão/auditoria | entra_v1 | 🔴 |
| **ATN-JUR-03** | Anel da próxima remessa (janela de envio) | S | Countdown da obrigação por competência (janela saudável) | entra_v1 | 🔴 |
| **ATN-JUR-04** | Remessa **REJEITADA** / vencida (R-REM) | T | Sinal de gravidade máxima por FALHA + ação de diagnóstico/reenvio | entra_v1 | 🔴 |
| **ATN-JUR-05** | Incidente de dados LGPD aberto (NF-R1) | T | Sinal de `incidente_dados` não-encerrado com prazo + ação *(o sinal entra_v1 **co-classificado** com a entidade NF-R1; prazo ANPD = `[GAP]`)* | entra_v1 | 🔴 |
| **ATN-JUR-06** | Grants de suporte ATIVOS sobre o ente (NF-R3) | S | Cartão de risco "operador X tem acesso, escopo, prazo" + revisar/revogar | entra_v1 | 🟠 |
| **ATN-JUR-07** | Atalho do sinal à trilha de auditoria | S | Deep-link de cada sinal à prova append-only (selo encadeado) | entra_v1 | 🟠 |
| **ATN-JUR-08** | Painel de exceções de conformidade (C25) | T | Atos sem ICP, prazos regimentais estourados, numeração pendente — **queries derivadas novas** | fast_follow | 🟠 |
| **ATN-JUR-09** | Minhas relatorias/pareceres pendentes (C17) | T | Recorte de 11.2 por ação atribuída ao papel jurídico | fast_follow | 🟠 |
| **ATN-JUR-10** | Frase-síntese de risco por IA | S | Síntese narrativa do estado de conformidade — depende de §16.8 | diferir `[GAP]` | ⚪ |

---

## 8. Features — Home do VEREADOR (Aposta 2, PWA)

| ID | Feature | Fam. | O que faz | §15 | Sev |
|---|---|---|---|---|---|
| **ATN-V01** | Hero fora-de-sessão ("o que pede você agora") | T | Item de maior urgência no topo quando não há sessão ao vivo (hoje o hero só existe para votação) | entra_v1 | 🟠 |
| **ATN-V02** | Minhas matérias em tramitação | S | Proposições de autoria/coautoria por estágio e local; recorte de 11.3/11.8 | entra_v1 | 🟠 |
| **ATN-V03** | Como votei (histórico pessoal) | S | Reusa 7.5 (vereador-estatisticas) — já existe | entra_v1 | ⚪ |
| **ATN-V04** | Convocação + confirmar ciência (C33) | T | Ciência ativa em 1 toque → `CienciaRegistrada` + valida prazo da LOM *(depende de N2)* | entra_v1 | 🔴 |
| **ATN-V05** | Próxima sessão e pauta | S | Data/itens da próxima sessão, destacando o que toca o vereador | entra_v1 | ⚪ |
| **ATN-V06** | Pareceres a entregar (fila por prazo) | T | Fila acionável dos pareceres do relator, ação "abrir meu parecer" | entra_v1 | 🔴 |
| **ATN-V10** | Pendências de assinatura (2 toques) | T | Fila do que aguarda assinatura + mecânica 7.3 (já existe) | entra_v1 | 🟠 |
| **ATN-V12** | Inbox/sininho pessoal | S | Feed cronológico "o que mudou no que é seu" (insumo, projeção dropável) | entra_v1 | ⚪ |
| **ATN-V07** | Minhas relatorias como carteira (C17) | S | Carteira matéria×prazo×status, distinta da fila por urgência | fast_follow | 🟠 |
| **ATN-V08** | Inscrição de oradores + pedido de vista (C83) | T | A porta do vereador (hoje só há o lado-Mesa) | fast_follow | 🟠 |
| **ATN-V09** | Subscrição / coautoria pela home (3.17) | T | Fila de convites + assinar em 2 toques (7.3 já existe) | fast_follow | ⚪ |
| **ATN-V13** | Narrativa longitudinal do mandato (C23) | S | Série da atividade ao longo do biênio | fast_follow | ⚪ |
| **ATN-V11** | Estado do voto sob rede instável (CONC-1) | M | Exibe pendente/enviado/confirmado; a idempotência write-side é **deferida à materialização** (R-CONC) | decisao_fundacao | 🔴 |
| **ATN-V14** | Frase-síntese por IA da home | S | "Você tem 2 pareceres e a sessão de quinta exige sua ciência" — depende de §16.8 | diferir `[GAP]` | ⚪ |

---

## 9. Features — Homes fast-follow (comissão · admin · operador · cidadão)

| ID | Feature | Ator | O que faz | §15 |
|---|---|---|---|---|
| **ATN-FF-01** | Cockpit da comissão / relator (C16) | comissão | A unidade-de-leitura intermediária que a §16 omitiu (matérias distribuídas, pareceres com prazo, saúde da comissão); reusa arquétipo cockpit | fast_follow |
| **ATN-FF-02** | Cockpit administrativo do ente (C06) | admin_ente | Fila "o que precisa da minha atenção" (convites/resets, usuários sem MFA, config incompleta, certificados); **o lado-ente do grant (NF-R3) é entra_v1** | fast_follow *(núcleo NF-R3 entra_v1)* |
| **ATN-FF-03** | Triage supratenant do operador | operador | Fila cross-tenant; **só o núcleo "tenant em risco de janela" entra_v1** *(decisão b)*; resto demand-pulled | fast_follow *(núcleo entra_v1)* |
| **ATN-FF-04** | Acompanhamento pontual do cidadão (C73/C71/C79) | cidadão | Agenda, matérias que sigo, status dos meus pedidos; **o recibo C79/N5 é entra_v1** (prova jurídica, R-NOT); o resto é pontual | fast_follow *(núcleo C79 entra_v1)* |

---

## 10. Resumo §15 + dependências de fundação

**Contagem (≈67 features):** ~**45 `entra_v1`** (a maioria recorte sobre read-models já fechados) · ~**14 `fast_follow`** ·
**4 `diferir`** (toda a narrativa-IA — depende de §16.8) · **3 `decisao_fundacao`** (horas-poupadas C20, estado-do-voto
CONC-1, e o sinal supratenant na parte ampla).

**[FATO] A camada de atenção não é autossuficiente — seus `entra_v1` dependem de 3 das 5 decisões de fundação da
`produto/18`:**

| Item da camada | Depende de (fundação) |
|---|---|
| Ciência de convocação (ATN-S07/V04, C33/N1) | **N2 — provedor de e-mail transacional in-region** (decisão de fundação) |
| Toda a narrativa-IA (ATN-15/MESA-10/JUR-10/V14, C22) | **Camada de confiança §16.8 madura** (rótulo + revisão humana + reportar erro) |
| Estado do voto PWA (ATN-V11, CONC-1) | **Materialização da idempotência write-side** (R-CONC, deferida ao chat de stack) |
| Sinal de incidente LGPD (ATN-JUR-05, NF-R1) | A **entidade `incidente_dados`** + máquina de estados deve entrar **junto** (co-classificada entra_v1) |
| Horas-poupadas (ATN-S11/ATN-16, C20) | **Definir operacionalmente "hora poupada"** (`[GAP]` no PRD) |

> [INF] O sinal **aponta para um vazio** se a fundação-dona não entrar junto. A ordem é: cravar as decisões de
> fundação (`produto/18 §3`) → materializar as entidades-dono → então a camada de atenção as recorta.

---

## 11. Fronteiras com o que já existe (reuso) + o que NÃO é (guardrails)

**Reusa massivamente (não recria):** read-models 11.1–11.4 (números situational) · 11.2 minhas-pendencias (a família
triage do servidor) · 11.6 notificações + ledger durável (o canal push + o "lido") · 7.5 vereador-estatísticas · 5.5/5.6
portal cidadão · motor de prazo §22.7.7 · máquina de estados §22.4 C · audit log §16.1 · módulo `tempo_real` (SSE) · o
arquétipo **cockpit** já provado em `paineis-mesa.html` (serve comissão/admin/jurídico).

**NÃO é (guardrails):** ❌ módulo novo (é read-model em `paineis`) · ❌ tabela-mestra paralela (projeção dropável,
salvo o ledger de entrega) · ❌ JOIN cross-schema (o sinal é projetado pelo consumer, §22.10) · ❌ cockpit de execução
ao vivo (isso é 4.17 Mesa de condução, operação ≠ atenção de entrada) · ❌ **BI / report-builder** (exportação custom,
benchmarking cross-câmara, ranking por vereador = V2, §16.11) · ❌ um módulo `notificacoes` próprio (só graduar quando
entrar push/multicanal/digest agendado, V1.5/V2).

---

## 12. Próximo passo

Com o modelo confirmado e a decomposição fechada, o **design segue daqui** (telas que materializam estas features,
reusando o design-system e o arquétipo cockpit). Antes de desenhar, dois movimentos de consolidação **sob *Confirma?***:

1. **Reabrir §16.11 pontualmente** no doc-mestre para **nomear** o sinal de atenção como o conceito que unifica
   11.1–11.6, e registrar os recortes que a §16 omitiu (C16 comissão, C19 engajamento, C25 exceções) como leituras de
   1ª classe — não estrutura nova, re-articulação.
2. **Cravar as 3 fundações** de que os `entra_v1` da camada dependem (N2 e-mail, §16.8 confiança, CONC-1 voto) na
   passada de decisões de fundação (`produto/18 §3`).

**Nada disto reabre arquitetura** — é design fino sobre substrato decidido. O catálogo de design da camada de atenção
sai das ~45 features `entra_v1` acima, com as homes dos 4 atores decisores como primeira onda.
