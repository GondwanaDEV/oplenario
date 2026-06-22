# 18 · Revisão de completude — Rodada 4 (convergência): R-DR e a lista estável de gates de go-live

> **Série de revisão de completude.** r1 (`produto/14`) e r2 (`produto/16`) auditaram features e classes ortogonais; r3 (`produto/17`) fechou seis classes gateantes (R-MIG, R-REM, R-NF, R-NOT, R-CONC, R-IA) e levantou em §4 **seis classes residuais NÃO-VERIFICADAS**. Esta é a **r4 cirúrgica**: verifica a fundo **uma só** dessas residuais — **R-DR** (a candidata a 7º gate) — e **converge** a lista de gates num artefato estável que entra na materialização. Convenções: `[FATO]` = lido line-by-line no SSOT (citado); `[INF]` = inferência; `[REC]` = recomendação. Régua §15 (4 perguntas) e escopo **instituição** valem em tudo. Não se inventa conteúdo regulatório.

---

## 0. Propósito

Fechar **R-DR** — "backup/restore/DR como **processo provado**, não capacidade de infra" — com verificação line-by-line e o desacordo das três lentes adversariais resolvido por julgamento; e, com isso, **estabilizar a lista de gates de go-live** que sai da auditoria de completude e entra na fase de decisão/materialização. r4 é **cirúrgica por design**: as outras cinco residuais da r3 §4 (R-EXP-PROB, R-RET-EXEC, R-OBS-NEG, R-ONB, R-API-PUB) **não** são re-auditadas — viram backlog rastreado. O sinal de saída desta rodada não é "mais uma classe a sondar", é **"a auditoria de gates está fechada; o próximo passo é decidir/materializar, não auditar"**.

---

## 1. Veredito R-DR

### 1.1 O que é

A pergunta-âncora de R-DR (r3 §4, `produto/17:221`): qual o **RTO/RPO committed por classe de dado**, e existe **prova recorrente** de que um restore funciona — ensaio de failover do Postgres, teste de PITR datado, e um **pacote de evidência de DR** mostrável ao jurídico do edital? Quem é notificado quando o **RPO real diverge** do prometido? A distinção que a tese frisa, e que a verificação sustenta: **cifrar o backup não prova que ele restaura**; um **número de uptime não prova durabilidade do acervo**. O bem em jogo é a base material da Aposta 3 (confiança operacional): a Casa **não pode perder** o acervo legislativo permanente e imutável (atas, leis, votações nominais — Inv.10).

### 1.2 O que o repo JÁ garante (citado, line-by-line)

`[FATO]` A **capacidade de infra existe e está decidida com qualidade** — o substrato não é o buraco:

- **PITR + failover + backup** são primitivas reais via operador: "Postgres via operador `CloudNativePG` (failover automático, **PITR + backup pro MinIO**, réplicas, upgrade pelo operador — é o que torna o Postgres self-managed operacionalmente viável)" (`arquitetura/22-9-stack.md:45`).
- **DR multi-AZ "de fábrica"** é razão explícita de deployar em cloud região-BR na V1: "single-site on-prem é **ponto único de falha, inaceitável sob o SLA de janela de sessão §16.10**" (`22-9-stack.md:11`).
- A **coordenação dos três planos de dado** está pensada: "backup/restore é **tripé (core + IA + storage), DR coordena os três**" (`22-3-contrato-core-ia.md:69`). É disciplina de coordenação, **não** processo provado.
- A **durabilidade lógica** do registro legal está cravada **por construção**: atos legislativos são append-only/imutáveis com retenção **Permanente** (Inv.10, `documento-mestre-camaras.md:496`; retenção "Permanente" por classe em `22-5-auth.md:118,123,126`). Isto garante que o sistema **nunca muta/deleta logicamente** o ato — propriedade do **modelo de domínio**, não da mídia.

`[INF]` Conclusão do substrato: a **capacidade** de restaurar existe. O que falta é número e prova — não mecanismo. A própria r3 lista PITR/failover como **COBERTO no nível de ingrediente** (`produto/15:82` "ingredientes existem").

### 1.3 Os três baldes

| Balde | Estado | Evidência |
|---|---|---|
| **(A) Capacidade de infra existe** | ✅ **Decidido, com qualidade** | CloudNativePG PITR+failover+backup→MinIO (`22-9:45`); multi-AZ (`22-9:11`); tripé core+IA+storage (`22-3:69`); imutabilidade lógica/retenção permanente (Inv.10, `22-5-auth.md:118,123,126`) |
| **(B) Números committed faltam** | ❌ **Ausente** | NF8 RTO/RPO "ingredientes existem sem objetivo" (`produto/15:82`); NF7 uptime "SLA só qualitativo, número AUSENTE" (`produto/15:81`); §16.10 "SLA de uptime específico para janelas de sessão" = qualitativo sem número (`doc-mestre:323`); únicos % do repo vivem num **mock de UI sem disclaimer** (`status.html:75–79`, verificado: "99,98% / 99,95% / 99,90% / 99,99% / 99,97% / 90 dias") |
| **(C) Processo e prova faltam** | ❌ **Ausente** | NF15 "restore testado + runbook de DR + ensaio de failover — reconhecido como **custo de SRE** (`22-9:49`), **sem disciplina definida**" (`produto/15:96`); a única menção ao drill/PITR no SSOT inteiro o trata como **custo de time** ("ops de stateful self-managed sob SLA exige trabalho de SRE (drill de failover, teste de PITR, upgrade de major)... Trade consciente", `22-9:49`) — frase de **dimensionamento de §18**, não cadência/teste datado/pacote de evidência/runbook; **zero** menção a "pacote de evidência de DR", "teste de PITR datado", "ensaio de failover executado", "alerta de divergência de RPO" |

`[FATO]` A distinção central **resiste**: nenhum item da r3 cobre o balde C. **NF-R5** cobre cripto/segredos (cifrar o backup); **NF-R4** cobre alvos de uptime/mock. **Nenhum** cobre a **durabilidade física do registro legal permanente sob falha de mídia/região** — e a própria r3 isola isso: "NF8 (RTO/RPO) e NF15... permanecem **abertos**... **não absorvidos** por NF-R4/NF-R5" (`produto/17:209`). R-DR é **classe própria**, não duplicata de uma classe já fechada.

### 1.4 Veredito reconciliado das 3 lentes

As três lentes — **cobertura real**, **cético adversarial (default não-gateia-ano2)** e **edital/procurement B2G** — **convergem no veredito de gate: `nucleo_minimo_gateia`**. Nenhuma sustenta "gateia a classe inteira"; nenhuma sustenta "não gateia nada". O cético, partindo do default mais hostil ao gate, é **forçado pela evidência** a um upgrade parcial e cirúrgico. O acordo é robusto justamente por vir de três pontos de partida diferentes.

O desacordo é em **dois pontos finos**, que resolvo por julgamento:

**(a) O RTO/RPO committed por classe gateia, ou é ano-2?** A lente de cobertura puxa o número committed **para dentro** do gate. As lentes cético e edital o empurram **para ano-2**, deixando in-gate apenas um **RTO/RPO informal/acordado** (não numérico-por-classe-formal). **Resolvo com a lente de edital**, que é a que mede o gate contra a realidade do comprador: o web-search de termos de referência reais mostra que **pregão de câmara pequena/média pede linguagem genérica** — "backup automático", "plano de contingência", "alta disponibilidade", "continuidade", LGPD — e que **RTO/RPO numérico formal por classe é vocabulário de contrato grande / ISO 27001 / planos DR de órgão grande** (Campinas, INTO), exatamente a família que **§16.10 já difere para ano-2** ("ISO 27001 = ano 2, SOC 2 = ano 2-3", `doc-mestre:325`). Pôr RTO/RPO numérico-por-classe-formal no gate da 1ª câmara **superdimensiona** — é confundir o checklist de go-live com o programa de DR de maturidade. **O que entra no gate é um RTO/RPO informal acordado** (faixa de horas para o acervo crítico, periodicidade e retenção de backup escritas), suficiente para a proposta técnica e para o jurídico do edital avaliarem algo concreto.

**(b) É decisão de FUNDAÇÃO?** Cobertura e edital dizem **sim** (par de NF-R5/N2); o cético diz **não** ("o mecanismo está cravado, só verifica algo decidido"). **Resolvo: sim, com um corte preciso.** O cético está certo que o **mecanismo** (CloudNativePG, multi-AZ) não é decisão de fundação — está cravado. Mas a **disciplina** — *qual* periodicidade/retenção de backup se compromete por escrito, *qual* o protocolo do restore-test, *qual* a faixa de RTO/RPO acordada — **precisa existir antes** de o SRE materializar a infra e antes de gravar o acervo da 1ª câmara, porque **o pacote de evidência depende dela** e porque o número de retenção governa o sizing. Isso é decisão de fundação no mesmo sentido operacional que NF-R5 (cripto/cofre) e N2 (provedor de e-mail in-region): cravada **antes de codar dependentes**, mesmo que não reabra arquitetura. A r3 já a antecipava como "**quinta decisão de fundação SRE candidata**" (`produto/17:211`). **`e_decisao_fundacao = SIM`** (no sentido de gate-de-fundação SRE), com a ressalva honesta do cético de que **não reabre arquitetura** — é fundação de processo, não de estrutura.

### 1.5 Decisão §15 + severidade

`[REC]` **R-DR CONFIRMA-SE** como classe real e não-coberta — **mas a hipótese provisória da r3 §4 está superdimensionada** e fica **corrigida**: o gate **não** é "RTO/RPO committed por classe + prova recorrente" (isso é ano-2). O gate é o **núcleo mínimo** abaixo. Pela **régua §15**: o núcleo mínimo **passa as 4 perguntas** — o acervo legal permanente da 1ª câmara é cliente validado e o dano (perda irreversível do acervo) é catastrófico e específico; **não** é diferimento consciente (`produto/15` não o protege sob §15 — é ponto cego, não escolha). A **classe inteira** (programa recorrente, alerta de divergência de RPO, DR de 2º site) **falha §15 hoje** = diferível para ano-2.

- **Núcleo mínimo:** **Severidade ALTA · `entra_v1` · gate de go-live.** Sobe ao mesmo tier de R-MIG/R-REM/R-NF/R-NOT.
- **Resto da classe:** **Severidade MÉDIA · fast-follow / hardening M4 · ano-2** (par de ISO 27001).
- **Mock de uptime (`status.html:75–79`):** **Severidade BAIXA mas obrigatória** — desinforma um cliente gov com número falso sem disclaimer; **barato de corrigir, gateante por honestidade**.

**É decisão de FUNDAÇÃO?** **SIM** — fundação **de processo SRE** (não de arquitetura). Cravar antes de materializar, par de NF-R5/N2.

### 1.6 O que precisaria minimamente (o núcleo que destrava)

1. **RTO/RPO informal acordado + compromisso escrito de backup** para a classe crítica (acervo legal permanente, Inv.10): faixa de RTO/RPO em horas + periodicidade e retenção de backup, na proposta técnica/contrato — alinhado ao CloudNativePG/PITR/MinIO já decididos. **Não** o número formal-por-classe (ano-2).
2. **UMA prova de restore DATADA antes do go-live da 1ª câmara:** um ensaio de failover + teste de PITR **executado e registrado**, produzindo um **pacote de evidência mínimo** (descrição do esquema backup/DR multi-AZ + data/resultado do restore-test) mostrável ao jurídico do edital. Para **uma** câmara operada por nós, isto é **procedimento operacional**, não tooling — paralelo a R-MIG-4 (checklist de handoff) e R-NF-R5 (decisão de fundação). **Restore-test único, não disciplina recorrente.**
3. **Corrigir/disclaimerar o mock de `status.html:75–79`** (NF-R4) antes de expor número de uptime falso a cliente gov.

**O que NÃO gateia (fast-follow / hardening M4):** cadência recorrente de drills, **alerta automático de divergência RPO-real-vs-prometido**, DR de 2º site/região, runbook DR formal completo, RTO/RPO numérico-committed-por-classe, error-budget, sizing de storage (NF12). `[INF]` Risco residual honesto (lente de edital): **se o edital concreto da 1ª câmara exigir RTO/RPO numérico já na proposta técnica**, o gate sobe — mas isso é incerto e deve ser **conferido contra o edital real** (régua §15: não pré-construir).

---

## 2. Lista de gates de go-live CONVERGIDA (estável)

`[FATO]/[REC]` Consolida as seis classes da r3 (`produto/17:194–199`) + R-DR resolvido. **Esta é a entrega-chave da r4: a lista estável que entra na materialização.** R-DR entra **dentro**, como núcleo-mínimo (não a classe inteira).

| # | Classe | Gateia V1? | Núcleo mínimo que destrava | Resto (fast-follow / ano-2) |
|---|---|---|---|---|
| **R-MIG** Migração de legado operada | **SIM** | Decisão de staging (R-MIG-5, fundação) + relatório de migração assinável (R-MIG-3) + checklist de handoff (R-MIG-4) | Tooling de migração polido; auto-serviço |
| **R-REM** Esteira de exceção da remessa TCE | **SIM** | Estados de remessa rejeitada/reenvio + modelo + eventos; fechar `[GAP]` de escalonamento `22-7:204` (matriz severidade→destinatário, REM-8) | Layout físico do SIM (segue `[GAP]`); outros TCEs |
| **R-NF** Controlador LGPD (superfícies de domínio) | **SIM** | Máquina de estados de incidente/ANPD (NF-R1) + aba ROPA (NF-R2) + tela do grant lado-ente (NF-R3); **NF-R5 cripto/segredos = fundação**; corrigir mock de uptime (NF-R4) | Gate a11y CI (NF-R6); FinOps por ente |
| **R-NOT** Notificação comprovável | **SIM** | Prova de ENTREGA + ciência ativa p/ convocação que conta prazo (N1) + recibo cidadão no ato (N5) + detecção de falha de canal (N3-mín); **N2 e-mail in-region = fundação** | Multicanal amplo; retry sofisticado |
| **R-CONC** Concorrência/carga | **SIM** | Idempotência write-side do voto PWA + reconciliação offline (CONC-1) + estado de empate/recontagem (CONC-2) | Trava de pauta (CONC-3); teste de carga (CONC-5) |
| **R-DR** Backup/restore/DR como **processo provado** | **SIM (núcleo)** | **RTO/RPO informal acordado + compromisso escrito de backup** (classe crítica) + **UMA prova de restore datada** com pacote de evidência mínimo + corrigir mock de `status.html`; **= fundação SRE** | RTO/RPO numérico-por-classe formal; drills recorrentes; **alerta de divergência de RPO**; DR de 2º site; runbook DR completo (par de ISO 27001, ano-2) |
| **R-IA** Operação contínua da IA | **NÃO** (exceto R-IA-1) | Embarcar R-IA-1 (estado degradado nas telas HERO — design barato) com o catálogo | R-IA-2 (failover vendor→vendor, patch `22-3`/`22-9`); R-IA-3/4 (FinOps/arquitetura) |

**Líquido: 6 das 7 classes gateiam.** A conta provisória da r3 ("6 de 7 *se* R-DR confirmar", `produto/17:199`) **fecha confirmada** — R-DR gateia, mas **só pelo seu núcleo mínimo**. R-IA segue a única fast-follow integral (seu R-IA-1 embarca com as telas). **A auditoria de gates está, com isto, FECHADA e ESTÁVEL.**

---

## 3. As decisões de FUNDAÇÃO a cravar antes de materializar

`[REC]` Resolver **antes** de desenhar/codar dependentes (mesma disciplina de `produto/17:211`). São cinco:

1. **Staging / homologação de migração (R-MIG-5).** Tensiona NF16 (só dev `kind` + prod Talos, sem intermediário, `produto/15:97`) + RLS/particionamento; governa a forma do diff/aprovação da migração. **Decisão de fundação.**
2. **Cripto / segredos / mTLS / cofre (NF-R5, `produto/15` NF1-3).** Pré-condição de qualquer deploy gov; fica **fora do design** (infra-pura) mas é gate de fundação. **Cravar antes de qualquer deploy.**
3. **Provedor de e-mail transacional in-region/soberano (N2).** Procurement-safe; base da entrega comprovável (R-NOT). **Escolha de arquitetura antes de codar.**
4. **Política de failover de vendor de IA (R-IA-2).** Patch de `arquitetura/22-3` + `22-9` Eixo 10 — hoje só há fallback de modelo + config-swap em deploy-time; runtime vendor→vendor precisa de decisão. **Reabre arquitetura pontualmente.**
5. **RTO/RPO informal acordado + protocolo de prova de restore (R-DR — NOVA, confirmada nesta r4).** Periodicidade/retenção de backup committed + protocolo do restore-test datado + forma do pacote de evidência. Fundação **de processo SRE** (não reabre arquitetura — o mecanismo CloudNativePG está cravado). **Par de NF-R5/N2.**

`[INF]` Ordem recomendada: cravar as cinco decisões de fundação **primeiro**, depois materializar a superfície gateante das seis classes (R-MIG…R-DR-núcleo), deixando R-IA (exceto R-IA-1) e todo o fast-follow para a onda imediatamente pós-go-live.

---

## 4. Backlog rastreado (não mais auditado em rodada ampla)

`[REC]` As cinco residuais da r3 §4 que **não** foram re-auditadas na r4 (decisão de escopo: r4 é cirúrgica em R-DR). Ficam **rastreadas, não re-sondadas em rodada ampla** — cada uma se promove individualmente **se e quando** um requisito de cliente validado a puxar (régua §15).

| Classe | 1 linha | Gate latente na escala vs. correção barata |
|---|---|---|
| **R-EXP-PROB** Exportação probatória sob demanda (MP/Judiciário/auditoria in-loco TCE) | Export forense por escopo (matéria/sessão/intervalo) com hash/cadeia de selo + atestado de integridade — distinto do dump de não-lock-in de `exportar-dados.html` | **Gate latente:** requisição do MP/TCE é evento previsível no 1º ano; substrato existe (selo encadeado 1.6, Inv.10), falta superfície. **Fast-follow** disciplinado |
| **R-RET-EXEC** Execução de retenção/expurgo (não só política) | A política de retenção por classe existe (`22-5-auth.md:116–126`); falta o **executor** que de fato expurga no prazo (áudio bruto G30, logs LAI/LGPD) | **Correção barata na origem, gate latente na escala:** sem executor, retenção é promessa. Materializar com o módulo de áudio/observabilidade |
| **R-OBS-NEG** Observabilidade de negócio (SLI de negócio operável) | Inv.9 crava SLI de negócio como 1ª classe ("a sessão de quarta funciona?", `doc-mestre:494`); falta a **superfície operável** (dashboard/alerta de janela de sessão) | **Gate latente na escala:** com 1 câmara é vigilância manual; com dezenas, precisa de superfície. **Fast-follow** |
| **R-ONB** Onboarding de nova câmara (processo repetível) | Migração operada (R-MIG) cobre o legado; falta o **processo repetível** de provisionar tenant + cadastros + jurisdição (`jurisdicao_camara`) + grant inicial | **Correção barata cedo, gate de escala depois:** com 1 câmara é manual; vira gargalo na expansão NE→N/CO. Tooling de operador |
| **R-API-PUB** API pública / dados abertos como contrato | `dados-abertos.html` desenhada; falta o **contrato de API** (versionamento, rate-limit, SLA de disponibilidade do endpoint público) | **Gate latente na escala:** integração de terceiros (imprensa, civic-tech) é demanda pós-tração. **Hardening M4** |

**Nota de CONVERGÊNCIA.** Com R-DR resolvido e estas cinco rastreadas como backlog, **a auditoria de gates de go-live está FECHADA**: a lista da §2 é estável (6 de 7 classes gateiam) e as decisões de fundação da §3 são cinco. **O próximo macro-passo não é auditar mais uma rodada — é DECIDIR as cinco decisões de fundação e MATERIALIZAR a superfície gateante.** Qualquer nova classe só reabre auditoria se um edital/cliente concreto a forçar (régua §15) — caso em que se sonda **aquela** classe, pontualmente, não uma r5 ampla.