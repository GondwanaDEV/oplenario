# 13 · Decomposição em features da V1

> **Trilha de produto/UX — primeiro artefato.** Decompõe o escopo cravado na §16 do
> documento-mestre (agora **11 módulos**) em **features** construíveis. **Não re-deriva escopo** — a
> fronteira é a §16; a régua de mudança é a §15. Onde a §16 enumera capacidades, isto as organiza
> em features; onde infere um recorte não-enumerado, marca `[INF]`.
>
> **Convenção:** `[FATO]` (na §16/§22) · `[INF]` (decomposição minha, derivada) · `[REC]` (recomendação) · 🔎 (a confirmar).

---

## 0. Como ler

**Granularidade:** épico (= módulo §16) → **feature** → sub-capacidades. **Sem** user stories/critério
de aceitação — isso depende do design (ainda a abrir) e do especialista em regimento (§22.4.4). Isto é
**esqueleto de backlog**, não spec de implementação.

**Tags por feature:**

| Tag | Significado |
|---|---|
| `[DIF]` / `[PAR]` | Diferenciação competitiva / paridade de mercado (PRD §3, §6) |
| `[IA]` | Toca a Plataforma de IA (§22.3) |
| `[HERO]` | Feature-âncora de compra (decisão 20/06) |
| `[RM]` | Read-model / projeção sobre dado já capturado (custo baixo) |
| `O0`/`O1`/`O2` | Onda de IA: 0 = infra (pipeline/corpus), 1 = features IA da V1, 2 = camada robusta (V2) |
| `M0–4` | Banda de mês na ordem de construção (§18) |
| `§22.x` | Âncora arquitetural já decidida |

---

## 1. ⚠️ Flags transversais (ler antes do backlog)

1. **✅ Inconsistência cross-track — ata-por-IA RESOLVIDA (doc-mestre v1.13, 20/06/2026).** §16.4 —
   e também §7 (Onda 1), §12 (tabela Status V1), §18 (mês 3-4), §22.4 eixo E, §22.6 — foram
   reconciliados: a ata-IA consta **DENTRO da V1** como feature-âncora (modo "produtividade", revisão
   humana §16.8). **Sem mudança de modelo de dados** — `ata_publicada` já aceitava ambos
   `origem_redacao` (`redigida_externamente`/`gerada_automaticamente`); a reconciliação foi só do
   *gate de capability de produto*. Esta decomposição já refletia a decisão nova.

2. **✅ Revisão de completude + quick wins incorporados (doc-mestre v1.14, 20/06/2026).** A revisão
   da lista (a pedido do Emilio) achou um buraco — **zero analytics/painéis** voltados à instituição,
   apesar de as personas decisoras esperarem métricas (presidente: engajamento; servidor: horas
   poupadas) — e três lacunas de completude. Decisões tomadas: **novo módulo 16.11** (painéis read-model,
   quick wins); **C-1 entra** (fluxo pós-aprovação); **C-2/C-3 entram em versão leve** (artefato de
   publicação oficial + consolidação manual assistida), com o tail caro deferido a V1.5+. Tudo
   documentado em §16 do documento-mestre.

3. **Contagem de features de IA.** São **5 capacidades de IA** na V1 (copiloto, busca semântica, resumo
   cidadão, transcrição/pré-atribuição, ata-IA). A "Camada de Confiança" (16.8) cobre **todas**.

4. **Régua de §15 = controle de mudança.** Toda feature nova passa pelas 4 perguntas. Os quick wins de
   16.11 são **read-model sem novo modelo de dados** — não são o tipo de escopo que a §15 teme.

---

## 2. Backlog por módulo

### 16.1 — Identidade, Perfis e Auditoria · `[PAR]` · M0 · §22.5 / §22.1(inv.10)

| # | Feature | Tags | Notas |
|---|---|---|---|
| 1.1 | Autenticação servidor/vereador (senha + MFA/TOTP) | `[PAR]` `§22.5` | gov.br é separado (1.2); admin interno usa WebAuthn (1.3) |
| 1.2 | SSO gov.br para cidadão | `[PAR]` `§22.5` | porta de entrada do público cidadão |
| 1.3 | WebAuthn obrigatório para admin interno | `[PAR]` `§22.5` | invariante de auth |
| 1.4 | RBAC por perfil (servidor, vereador, presidente, sec. de mesa, cidadão, admin) | `[PAR]` `§22.5` | papéis fixos V1; grupos dinâmicos **fora** |
| 1.5 | Funções de relação + DSL de autorização compartilhada | `[PAR]` `§22.5(eixo B)` | mesmo motor declarativo do compliance/tramitação |
| 1.6 | Trilha de auditoria completa (quem/o quê/quando/de onde) | `[DIF]` `§22.1(inv.10)` | audit log = domínio de produto, não log técnico |
| 1.7 | Sessão + gestão de tokens | `[PAR]` `§22.5` | |

**Fora (guardrail):** SSO AD/LDAP do município, federação outros IdPs, grupos/funções dinâmicas.

---

### 16.2 — Cadastros Estruturais Legislativos · `[PAR]` · M0 · §22.4 / §22.5(mandato)

| # | Feature | Tags | Notas |
|---|---|---|---|
| 2.1 | Vereadores (mandato, filiação, suplência, licença, afastamento) | `[PAR]` `§22.5(mandato c/ cascata)` | mandato = entidade com cascata |
| 2.2 | Mesa Diretora (cargos + rotatividade bienal) | `[PAR]` | |
| 2.3 | Comissões permanentes/temporárias + CPIs | `[PAR]` | "comissões obrigatórias por matéria" 🔎 (especialista em regimento, §22.4.4) |
| 2.4 | Legislaturas e sessões legislativas | `[PAR]` `§22.6` | entidades temporais |
| 2.5 | Blocos / frentes parlamentares | `[PAR]` | |

**Fora (guardrail):** servidores administrativos como entidade (delegado ao RH), fornecedores, cadastros contábeis.

---

### 16.3 — Processo Legislativo (coração) · `[DIF]` · M1–M4 · §22.4

| # | Feature | Tags | Notas |
|---|---|---|---|
| 3.1 | Protocolo de proposições (PL, PLC, PLP, PDL, PR, PRC, indicação, requerimento, moção) | `[PAR]` `§22.4(STI)` | espécies via STI híbrido |
| 3.2 | Emendas de todos os tipos como entidade própria | `[PAR]` `§22.4(eixo E)` | |
| 3.3 | Tramitação configurável pelo regimento (máquina de estados declarativa) | `[DIF]` `§22.4(eixo C/DSL)` | **mesmo motor** do compliance/auth/plenário |
| 3.4 | Versionamento de texto append-only (rascunho→vigente→superada→arquivada) | `[PAR]` `§22.4(eixo B)` | proveniência via `origem_versao` |
| 3.5 | Pareceres de comissão (relator, voto, divergências) | `[PAR]` `§22.4(eixo F)` | governado pelo motor do eixo C |
| 3.6 | Apensação / desapensação (associação histórica) | `[PAR]` `§22.4(eixo G)` | |
| 3.7 | Distribuição a comissões + fluxo de aprovação | `[PAR]` `§22.4` | |
| 3.8 | Controle de prazos de tramitação | `[PAR]` `§22.4(disc.6 prazo de domínio)` | `prazo_dominio_ativo` polimórfico; alimenta o Painel de Prazos (11.1) |
| 3.9 | Arquivamento | `[PAR]` | |
| 3.10 | Assinatura digital ICP-Brasil no fluxo | `[PAR]` `§22.5(eixo F)` | paridade obrigatória |
| 3.11 | **Copiloto de redação de projetos** | `[DIF]` `[IA]` `O1` `§22.3` | Aposta 1; saída = sugestão editável (16.8) |
| 3.12 | **Busca semântica intra-câmara no acervo** | `[DIF]` `[IA]` `O1` `§22.3` | depende da transcrição (4.12) e do corpus indexado (O0) |
| 3.13 | **Autógrafo + envio ao Executivo** (C-1) | `[PAR]` `§22.4(origem_versao=redacao_final)` | texto oficial aprovado; registra envio ao Prefeito |
| 3.14 | **Controle de sanção/veto + apreciação do veto** (C-1) | `[DIF]` `§22.4` `§22.7.5(S4)` | prazo do Executivo (sanção tácita); veto volta à câmara, votação maioria absoluta (reusa 16.4) |
| 3.15 | **Promulgação + numeração canônica da lei + publicação** (C-1) | `[PAR]` `§22.4(origem_versao=promulgacao)` | fecha a fronteira "da proposição à publicação" (§15) |

**Fora (guardrail):** integração c/ processo legislativo federal/estadual (V2), mineração cross-câmara (V1.5), similaridade entre proposições (V2). **Prazos/rito exato do veto a confirmar com especialista de regimento** (variam por LOM).

---

### 16.4 — Sessões Plenárias · `[PAR]`+`[DIF]` · M2–M3 · §22.6 / §22.3.4

| # | Feature | Tags | Notas |
|---|---|---|---|
| 4.1 | Pauta eletrônica (expediente + ordem do dia), versionada | `[PAR]` `§22.6(pauta híbrida)` | |
| 4.2 | Painel eletrônico de votação em telão | `[PAR]` `§22.6` `[SSE]` | real-time via SSE |
| 4.3 | Votação nominal / simbólica / secreta com registro auditável | `[PAR]` `§22.4(votos secretos em tabela separada)` | |
| 4.4 | Controle de quórum em tempo real | `[PAR]` `§22.6` `§22.7.5(S4: quórum = guard)` | regra de plenário no motor (envelope de guard, não compliance) |
| 4.5 | Registro de presença por vereador (append-only) | `[PAR]` `§22.6` | presença = eventos |
| 4.6 | Inscrição de oradores + cronômetro de tribuna | `[PAR]` `§22.6(tempos de tribuna = config no motor)` | |
| 4.7 | Captação/gravação de áudio-vídeo + diarização | `[PAR]` `[IA]` `O0` `§22.3.4` | pipeline da Onda 0 |
| 4.8 | Endpoint de ingestão de áudio agnóstico à fonte | `[PAR]` `§22.3.4` | M3 (porta única OBS/appliance/estúdio) |
| 4.9 | Utilitário CLI / watch-folder genérico (~2 sem) | `[PAR]` `§22.3.4` | fallback p/ qualquer fonte |
| 4.10 | Transmissão ao vivo via YouTube Live (integração) | `[PAR]` | não reimplementar transmissão |
| 4.11 | Anexação de ata redigida externamente (upload, indexação, vínculo, imutabilidade, ICP-Brasil) | `[PAR]` `§22.4.3(disc.4)` `§22.5(eixo F)` | decisão v1.7; só texto extraível |
| 4.12 | Transcrição automática + pré-atribuição de fala (Caminho C) | `[DIF]` `[IA]` `O0/O1` `§22.3` | insumo da busca (3.12); revisão manual opcional |
| 4.13 | **Geração automática de ata pós-sessão por IA** | `[DIF]` `[IA]` `[HERO]` `O1` `§22.6` | modo "produtividade"; revisão humana obrigatória (16.8) |

**Fora (guardrail):** software de captação local proprietário; **Plugin de Captura Sincronizada rico** e adaptadores por fornecedor (satélite); shorts automáticos (V1.5); plataforma própria de transmissão; multi-plataforma simultânea.

---

### 16.5 — Transparência e Portal Público · `[PAR]`+`[DIF]` · M2–M4 · §22.1 / §22.3

| # | Feature | Tags | Notas |
|---|---|---|---|
| 5.1 | Portal público white-label (identidade visual configurável, **não** estrutura) | `[PAR]` | |
| 5.2 | Publicação automática (proposições, atas, votações nominais, presenças, OD, vereadores, comissões, regimento, legislação) | `[PAR]` | alimentado por domain events |
| 5.3 | Acessibilidade eMAG/WCAG AA + responsivo | `[PAR]` | requisito de design (trilha UX) |
| 5.4 | **Resumo em linguagem simples de proposições** | `[DIF]` `[IA]` `O1` `§22.3` | Aposta 1; revisão humana antes de publicar (16.8) |
| 5.5 | Acompanhamento de proposição por cidadão + notificações (e-mail V1; push c/ app) | `[DIF]` | |
| 5.6 | **Timeline pública da tramitação** da proposição (#5) | `[DIF]` `[RM]` | read-model sobre eventos; demo power + transparência |
| 5.7 | **Artefato de publicação oficial** (assinado/numerado/imutável) + feed ao DOM externo (C-2 leve) | `[DIF]` `§22.5(eixo F)` `§22.4.3(disc.4)` | reusa assinatura; DOe-de-registro = V1.5 (ver Fora) |
| 5.8 | **Legislação consolidada: repositório as-enacted + consolidação manual assistida** (C-3) | `[DIF]` `§22.4(eixo B)` | texto vivo versionado pelo servidor; IA-auto e bulk histórico = depois (ver Fora) |

**Fora (guardrail):** portal da transparência geral (despesas/folha/contratos/licitações da câmara) — responsabilidade do sistema administrativo, integramos por consumo; **DOe-de-registro** da câmara (adoção legal + SLA elevado + risco jurídico) → V1.5 se cliente exigir; **consolidação automática por IA** (Onda 2, sobre o copiloto) e **consolidação em massa do acervo histórico** (problema de migração, 16.9).

---

### 16.6 — Participação Cidadã (mínima) · `[PAR]` · M4 · §22.4(events)

| # | Feature | Tags | Notas |
|---|---|---|---|
| 6.1 | e-SIC restrito a proposições e atos legislativos | `[PAR]` | |
| 6.2 | Ouvidoria com roteamento básico por assunto/comissão | `[PAR]` | |
| 6.3 | Comentários públicos em proposições com moderação | `[PAR]` | |

**Fora (guardrail):** consulta pública estruturada (V2), chatbot cidadão (V2.5+), audiência virtual/e-democracia (V2+), ranking de engajamento por vereador (V2).

---

### 16.7 — Experiência para Vereador (Aposta 2) · `[DIF]` · M3–M4 · §22.5 / §22.6

| # | Feature | Tags | Notas |
|---|---|---|---|
| 7.1 | App mobile (iOS + Android) como entrada principal do vereador | `[DIF]` | trilha UX é crítica aqui |
| 7.2 | Dashboard pessoal (próximas sessões, pautas, proposições próprias em tramitação) | `[DIF]` `[SSE]` | |
| 7.3 | Assinatura eletrônica em 2 toques | `[DIF]` `§22.5` | |
| 7.4 | Notificações push | `[PAR]` | consolida na central 11.6 |
| 7.5 | **Estatísticas pessoais do vereador** (minhas proposições por status, presença, pendências) (#7) | `[DIF]` `[RM]` | read-model; extensão de 7.2 |

**Fora (guardrail):** gestão de gabinete (agenda, demandas de bairro, mala direta, CRM eleitoral) — **excluído desde a origem, não é reabertura disfarçada.**

---

### 16.8 — Camada de Confiança (mínima reutilizável) · `[DIF]` · M1–M4 · §22.3 / §22.1

| # | Feature | Tags | Notas |
|---|---|---|---|
| 8.1 | Citação de fontes em todo output de IA | `[DIF]` `[IA]` `§22.3` | |
| 8.2 | Indicação de incerteza (score/sinal propagado como metadata) | `[DIF]` `[IA]` `§22.3.5` | |
| 8.3 | Log auditável de IA | `[DIF]` `[IA]` `§22.1(inv.10)` | |
| 8.4 | Botão "reportar erro" | `[DIF]` `[IA]` | |
| 8.5 | Workflow de revisão humana obrigatório antes de publicar | `[DIF]` `[IA]` | governa ata (4.13), resumo (5.4), texto de projeto (3.11) |

**Fora (guardrail):** versão robusta (sampling de auditoria, métricas de qualidade por câmara, painel de governança de IA) → Onda 2/V2. **Racional:** constrói-se o mínimo que as 5 capacidades de IA exigem.

---

### 16.9 — Migração (Aposta 3) · `[DIF]` · M1+M4 · §22.2 / §22.1(origem)

| # | Feature | Tags | Notas |
|---|---|---|---|
| 9.1 | Endpoints de ingestão de legado explícitos por módulo do core | `[DIF]` `§22.2` | dado legado entra por API interna, não escreve no banco direto |
| 9.2 | Marcador `origem` / `origem_ref` / `origem_importado_em` em toda tabela relevante | `[DIF]` `§22.1(inv.8)` | desde o dia 1 |
| 9.3 | Pipeline de áudio que processa histórico em bulk (além de ao vivo) | `[DIF]` `§22.6` | |
| 9.4 | Migração artesanal da 1ª câmara (humano + scripts ad-hoc sobre 9.1) | `[INF]` | meta 30 dias contrato→go-live |
| 9.5 | 1º conector (provável Softcam, relevância regional) | `[INF]` `M1` | demais conectores M4 |

**Fora (guardrail):** conectores automatizados para concorrentes como produto (satélite separado, sem cliente fechado); migração de dados administrativos (folha/contábil/licitações) — não há o que migrar.

---

### 16.10 — Operação, SLA e Compliance · `[DIF]` · M4 · §22.7 / §22.1(inv.9)

| # | Feature | Tags | Notas |
|---|---|---|---|
| 10.1 | SLA de uptime para janelas de sessão (noites ter/qua/qui) | `[DIF]` `§22.1(inv.9 SLIs negócio)` | |
| 10.2 | Suporte de plantão noturno dedicado a sessões | `[DIF]` | confiança operacional como diferencial comercial |
| 10.3 | Status page pública + monitoramento sintético das rotas críticas | `[PAR]` `§22.1(inv.7)` | |
| 10.4 | Motor de regras de compliance configurável (TCE-CE 100% coberto na V1) | `[DIF]` `§22.7` | **motor-dsl/ é o protótipo validado**; conteúdo TCE-CE = config (Invariante 4) |
| 10.5 | Geração de artefato de remessa ao TCE-CE | `[DIF]` `§22.7(+2 eixo aberto)` 🔎 | ⚠️ **bloqueado em layout regulatório real** — eixo de arquitetura ainda não aberto |

**Fora (guardrail):** TCEs de outros estados (expansão geográfica), ISO 27001 (ano 2), SOC 2 (ano 2–3).

---

### 16.11 — Painéis, Pendências e Notificações (read-model) · `[DIF]` · M3–M4 · §22.1(inv.9) / §22.7.7

**Racional:** as personas decisoras esperam métricas (presidente "ganha com engajamento cidadão";
servidor com "horas poupadas") mas nenhuma feature as entregava; o motor de compliance (16.10)
monitora prazo mas o estado ficava invisível. Esta camada **torna visível o que já capturamos** —
read-model/projeção sobre o substrato event-driven + audit log + motor de prazo. Custo baixo, valor
alto; **11.1 e 11.4 são entrega de aposta/persona já construída-mas-invisível**, não analytics opcional.

| # | Feature | Tags | Notas |
|---|---|---|---|
| 11.1 | **Painel de prazos "o que vence"** (compliance TCE + tramitação) | `[DIF]` `[RM]` `§22.7.7` | o motor já monitora; **entrega visível da aposta 3**; ataca o gatilho nº1 de compra (TCE) |
| 11.2 | **Caixa de pendências / "minhas tarefas hoje"** (assinar, parecer, revisar ata-IA/transcrição) | `[DIF]` `[RM]` | momento-matador da POC do servidor |
| 11.3 | **Painel de tramitação** (funil/kanban "onde está cada proposição") | `[DIF]` `[RM]` `§22.4(eixo C)` | read-model sobre a máquina de estados |
| 11.4 | **Dashboard institucional da Mesa** (proposições por status, sessões, presença, engajamento cidadão) | `[DIF]` `[RM]` | **cumpre a proposta de valor da persona presidente** |
| 11.5 | Busca global simples (não-IA) | `[PAR]` `[RM]` | distinta da semântica (3.12); table-stakes |
| 11.6 | **Central de notificações/alertas unificada** (C-4) | `[DIF]` `[RM]` | consolida push (7.4) + e-mail (5.5): prazo a vencer, parecer pendente, sessão amanhã |
| 11.7 | Exportação PDF/CSV de listas (proposições, presenças, votações) | `[PAR]` `[RM]` | cross-cutting; table-stakes B2G |

**Fora (guardrail):** BI de verdade — report-builder, exportação custom configurável, benchmarking
cross-câmara, ranking de engajamento por vereador (§16.6) — V2 (mesmo substrato, camada robusta).

---

## 3. As 5 capacidades de IA (visão cross-módulo)

A diferenciação de IA não é um módulo — atravessa o produto. Todas passam pela **Camada de Confiança (16.8)**:

| Capacidade | Mora em | Onda | Depende de |
|---|---|---|---|
| Transcrição + pré-atribuição (Caminho C) | 4.12 | O0/O1 | captação 4.7/4.8 |
| Busca semântica intra-câmara | 3.12 | O1 | transcrição + corpus indexado (O0) |
| Copiloto de redação | 3.11 | O1 | corpus legal (O0) |
| Resumo cidadão em linguagem simples | 5.4 | O1 | proposição protocolada |
| **Ata automática por IA** `[HERO]` | 4.13 | O1 | transcrição confiável → captação como oferta |

**Evolução Onda 2:** consolidação de legislação assistida por IA (sobre 5.8 + o copiloto) é o 6º uso
natural, deferido. **[REC]** a trilha de UX deve tratar a **superfície de revisão humana (8.5)** como
tela de primeira classe — encontro da Aposta 1 (IA) com a Aposta 2 (UX), onde o servidor decide a POC.

---

## 4. Sequência de construção (§18) → features

| Banda | Foco | Features-chave |
|---|---|---|
| **M0–1** | Fundação + Onda 0 IA | 1.x, 2.x, 4.7 (pipeline+diarização), corpus legal indexado, modelo de dados legislativo |
| **M1–2** | Coração legislativo | 3.1–3.10, 8.1–8.5 (1ª versão), 9.5 (1º conector) |
| **M2–3** | Sessão + captação + ata anexada | 4.1–4.6, 4.8–4.12, 5.1–5.3, 5.6, 7.1–7.2 (inicial). **Endpoint áudio + CLI funcionais até fim do M3** |
| **M3–4** | IA de valor + cidadão + painéis + hardening | 3.11–3.12, **3.13–3.15 (pós-aprovação)**, 4.13 `[HERO]`, 5.4, **5.7–5.8**, 6.x, **7.5**, 10.1–10.4, **16.11 (11.1–11.7 read-models)**, 9.x, fechamento |

**Nota (§18):** M4 é piso, não teto. Exige **≥8 eng + designer sênior + PM + especialista em
regimento**. A ata-IA (4.13) + o pós-aprovação (3.14 veto) + os painéis somam ao escopo de 4 meses —
**apertam** o plano (PRD §2/§5). Os read-models (16.11) são baratos mas dependem do dado fluindo (tardios).

---

## 5. Cobertura — toda capacidade "Entra" da §16 mapeada (78 features, 11 módulos)

✅ 16.1→F1.1–1.7 · 16.2→F2.1–2.5 · 16.3→F3.1–3.15 · 16.4→F4.1–4.13 · 16.5→F5.1–5.8 ·
16.6→F6.1–6.3 · 16.7→F7.1–7.5 · 16.8→F8.1–8.5 · 16.9→F9.1–9.5 · 16.10→F10.1–10.5 · **16.11→F11.1–11.7**.
**Nenhum item "Entra" da §16 ficou sem feature.** Itens "Não entra" viraram guardrails por módulo.

---

## 6. Dependências e itens abertos (rastrear)

1. ✅ **§16.4 reconciliado** (doc-mestre v1.13) — ata-IA DENTRO da V1; ver Flag 1.
2. ✅ **C-1/C-2/C-3 decididos e documentados** (doc-mestre v1.14) — ver Flag 2 e abaixo.
3. **C-1 (pós-aprovação):** prazos e rito exato do **veto** dependem do **especialista de regimento** (§10);
   variam por LOM. Estrutura é padrão; reusa votação (16.4) + DSL (S4).
4. **C-2 (publicação oficial):** V1 entrega só o artefato leve + feed; **ser o DOe-de-registro = V1.5** —
   🔎 **validar com o beachhead** se "publicação oficial através de nós" é dor de compra (+ jurídico).
5. **C-3 (consolidação):** **quão fundo** consolidar o acervo histórico é decisão de **migração** (16.9);
   IA-auto-consolidação = Onda 2.
6. **T5 (cross-track):** "TCE-CE 100% coberto na V1" (10.4) depende das listas granulares da DSL (§22.7.4)
   e do eixo de remessa (10.5, bloqueado em conteúdo). Promessa comercial à frente da spec.
7. **Métricas de sucesso (PRD §4):** definição operacional de "tempo economizado" segue lacuna.
8. **Especialista em regimento (§22.4.4):** "comissões obrigatórias por matéria" (2.3) é config estática
   ou expressão DSL?
9. **Stack + dimensionamento de time:** M0 depende de decisões de stack não tomadas (§19).
10. **Design (próximo passo da trilha):** features `[DIF]` de UX (16.7 app, 5.x portal, 8.5 revisão,
    **16.11 painéis**) precisam de design antes de virar histórias construíveis.

---

## 7. Próximo passo da trilha de produto/UX

Com o backlog em pé (78 features, 11 módulos), o próximo movimento é **design** das superfícies `[DIF]`:
app do vereador (16.7), portal cidadão (16.5), a **superfície de revisão humana de IA (8.5)** e os
**painéis de 16.11** (onde a confiança operacional e o engajamento ficam visíveis). 🔎 **Decisão
parqueada (Emilio):** ao chegar no design, escolher entre **ECC** e **UI/UX Pro Max – Design
Intelligence** como ferramenta. **Não decidida aqui** — é da fase de design, trago quando lá chegarmos.
