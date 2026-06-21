# 13 · Decomposição em features da V1

> **Trilha de produto/UX — primeiro artefato.** Decompõe o escopo cravado na §16 do
> documento-mestre (**11 módulos tenant-facing**) em **features** construíveis, **mais** o módulo
> **supratenant do operador SaaS** (16.12, §22.10 — fora da fronteira §16). **Não re-deriva escopo** —
> a fronteira tenant-facing é a §16; a régua de mudança é a §15. Onde a §16 enumera capacidades, isto
> as organiza em features; onde infere um recorte não-enumerado, marca `[INF]`. O épico do operador
> (16.12) **não deriva da §16** — deriva de §22.10 (GAP 1+3).
>
> **Convenção:** `[FATO]` (na §16/§22) · `[INF]` (decomposição minha, derivada) · `[REC]` (recomendação) · 🔎 (a confirmar).
>
> **Atualizado ao doc-mestre v1.38** (antes refletia v1.14): incorpora o chat de stack (§22.9), o
> monólito modular (§22.10), o **bloco GAP 1–5** (admin do sistema, admin do ente, notificações,
> relatórios) e a **revisão de completude pré-design (v1.38, §16.13 do doc-mestre / `produto/14`)** —
> que adicionou ~24 features (linhas marcadas `v1.38`) e corrigiu a 6.1. Delta no fim das flags (seção 1)
> e no resumo da seção 5.

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
| `[SUPRATENANT]` | Feature **operador-facing**, fora da fronteira tenant da §16, ancorada em §22.10 |
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

5. **✅ Stack V1 cravada (doc-mestre §22.9, v1.16–v1.27, 10/10 eixos).** Decisões que tocam features:
   **passwordless-first via passkey** (§22.5, v1.17 → reescreve 1.1/1.3); **PWA-first, app nativo
   deferido** (Eixo 9, v1.26 → reescreve 7.1, **encolhe** M0–M4 — zero codebase nativo); **IA híbrida**
   (Eixo 10, v1.27) — ASR Whisper-class **self-host on-GPU** + **embeddings open-source self-host +
   `pgvector`** + **LLM de fronteira atrás de porta de inferência vendor-agnóstica** → anota
   3.11/3.12/4.7/4.12/4.13/5.4. Infra (Postgres vanilla self-managed/CloudNativePG, Valkey,
   fila-no-Postgres+outbox, k8s Talos/k3s+kind, Keycloak self-managed **separado p/ admin interno**,
   Pedestal+Malli, Next self-hosted) é **substrato** — não vira feature, mas **entra no
   dimensionamento de time** (§18 / seção 6).

6. **✅ Monólito modular cravado (§22.10, v1.29–v1.30).** Ports & adapters (versão Nubank); **7
   contextos de domínio + 2 módulos de projeção (`paineis`/`tempo_real`) + 1 supratenant
   (`admin_sistema`)**; comunicação inter-módulo só HTTP/eventos; schema-por-módulo **sem FK/JOIN
   cross-schema**. **Transversal/arquitetural — não vira feature**, mas é a régua de "onde cada coisa
   mora": read-models de 16.11 = módulo `paineis`; SSE de 4.2/7.2 = módulo `tempo_real`; verdade
   durável de notificação = ledger no `paineis` (flag 8); relatório = projeção, **não** módulo (16.10/16.11).

7. **✅ Administração em DUAS esferas (GAP 1+3 e GAP 2, v1.31–v1.32).** Esta solução é SaaS → tem
   **operador** (acima do tenant) e **administrador do ente** (a câmara administrando a si mesma), em
   esferas **disjuntas**. **Operador SaaS** = épico/módulo **novo supratenant** → **§16.12 / módulo
   `admin_sistema`** (ancorado em §22.10, **fora da §16**). **Admin do ente** = papel estático novo
   `admin_ente` + **área de UI** (não módulo backend, Invariante 5) → features **1.8–1.10** em 16.1.
   **Anti-escalonamento:** token do operador **sem `ente_id`**; o **teste de vazamento ganha 2ª
   dimensão (cross-esfera)** além do cross-tenant (vira gate de CI).

8. **✅ Notificações e relatórios com modelo formal (GAP 4+5, v1.33–v1.34).** **Notificação = 3 planos
   do mesmo evento** (outbox=transporte / SSE=efêmero / notificações=durável); **vista** (sininho/inbox)
   = projeção **dropável** no `paineis`; **entrega** (e-mail/push) = **ledger durável idempotente** — a
   *única peça de verdade durável* da camada de projeção (exceção consciente); **consent-gate** por
   persona (cidadão sim, servidor/vereador na função não). **Relatório = taxonomia, sem módulo** (seria
   JOIN cross-schema, proibido): 3 classes/3 donos — projeção (`paineis`) / artefato regulatório
   (`compliance`) / artefato legal (domínio dono, ICP, imutável). Reconcilia 5.5/7.4/11.6/11.7/10.5 que
   existiam **sem** modelo.

9. **⚠️ Revisão de completude pré-design (doc-mestre v1.38, §16.13 — registro em `produto/14-revisao-completude-features.md`).** Auditoria por três lentes (arquitetura/interop · jurídico-regulatório · paridade/JTBD) achou ~34 gaps que passaram batido — vários **omissões da própria §16**. **Entram na V1 e expandem este catálogo:** sessão plenária completa (tipos de sessão, convocação oficial, incidentes processuais, mesa de condução → 16.4); nova superfície **Expediente/Documentos** (geração de documentos por modelo + protocolo geral → 16.3); **e-SIC amplo + prazo LAI** (corrige a 6.1, juridicamente incorreta → 16.6); espécies Decreto Legislativo/Resolução/Emenda à LOM, julgamento de contas do Prefeito (2/3), audiências públicas LRF, coautoria/subscrição, transparência fiscal do órgão (publicação), Carta de Serviços + ouvidoria 13.460, portal do titular LGPD, numeração por tipo/ano, calendário/recesso, URN/LexML (§22.4 eixo H), portabilidade/saída do contrato, observabilidade do modelo de IA, dados abertos, upload validado. **As tabelas por módulo abaixo foram expandidas com esses itens nesta passada (linhas marcadas `v1.38`); contagem re-somada para 113/12 (§5). `produto/14` segue como registro minucioso (severidade, fontes, itens diferidos).**

> **Delta v1.14 → v1.34 (1 linha por mudança):** NOVO épico 16.12 `admin_sistema` (8 features,
> supratenant) · +3 features em 16.1 (`admin_ente`: 1.8/1.9/1.10) · reescritas 1.1 (passkey), 1.3 (admin
> supratenant + Keycloak separado), 1.4 (`admin_ente` + disjunção de esferas), 7.1 (PWA-first) · anotadas
> 4.7/4.12 (ASR/embeddings self-host) e 3.11/3.12/4.13/5.4 (LLM via porta vendor-agnóstica) ·
> reconciliadas 5.5/7.4/11.6 (notificação 3-planos + ledger) e 11.7/10.5 (taxonomia de relatório/remessa)
> · +4 flags transversais (5–8) + tag nova `[SUPRATENANT]` · contagem **78/11 → 81 tenant-facing/11 + 8
> supratenant/1 = 89/12** · +6 riscos (seção 6).

---

## 2. Backlog por módulo

### 16.1 — Identidade, Perfis e Auditoria · `[PAR]` · M0 · §22.5 / §22.1(inv.10)

| # | Feature | Tags | Notas |
|---|---|---|---|
| 1.1 | Autenticação servidor/vereador **passwordless-first (passkey primário)** | `[PAR]` `§22.5.1` `§22.5.2(eixo F)` | **passkey = fator primário recomendado**; senha + TOTP é **piso sempre disponível** p/ quem não usa passkey; **e-mail código de uso único = bootstrap de enrollment + recuperação, nunca fator standing**; SMS não por default. gov.br é separado (1.2); admin interno usa hardware key física (1.3) |
| 1.2 | SSO gov.br para cidadão | `[PAR]` `§22.5` `§22.9(Eixo 6)` | porta de entrada do público cidadão; gov.br federado no Keycloak self-managed |
| 1.3 | **Admin interno: hardware key física obrigatória + IdP Keycloak fisicamente separado** | `[PAR]` `[SUPRATENANT]` `§22.5.1` `§22.9(Eixo 6)` | admin interno é **principal supratenant, não vínculo de tenant** — a substância vive no console do operador (**12.4**); aqui fica só o ponteiro. WebAuthn **com hardware key física (sem passkey sincronizado)**; instância Keycloak **separada** |
| 1.4 | RBAC por perfil (servidor, vereador, presidente, sec. de mesa, cidadão, **`admin_ente`**) | `[PAR]` `§22.5.2(eixo C)` `§22.5.3(disc.1)` | papéis fixos V1; **`admin_ente` é papel estático novo em `usuario_papel`, escopado ao tenant via vínculo**; é **RBAC de tenant, disjunto do RBAC supratenant do `admin_sistema` (12.4)** — o `admin` do operador SaaS **não** é papel deste módulo; grupos dinâmicos **fora** |
| 1.5 | Funções de relação + DSL de autorização compartilhada | `[PAR]` `§22.5(eixo B)` | mesmo motor declarativo do compliance/tramitação |
| 1.6 | Trilha de auditoria completa (quem/o quê/quando/de onde) | `[DIF]` `§22.1(inv.10)` `§22.5.2(eixo G)` | audit log = domínio de produto, não log técnico; o audit do **operador** vive separado em 12.5 |
| 1.7 | Sessão + gestão de tokens | `[PAR]` `§22.5` | |
| 1.8 | **Área de administração do ente** (UI gated por `admin_ente`): gerir usuários/vínculos, resetar MFA, aprovar recuperação de poder elevado | `[FATO]` `§22.5.2(eixo C)` `§22.10` | **área de UI, não módulo backend** (Invariante 5); compõe endpoints de `identidade`/`cadastros` via HTTP |
| 1.9 | **Config do ente** (visão agregada via HTTP, **sem tabela central**) + **branding/identidade visual + contatos/canais/flags** em `cadastros` | `[FATO]` `§22.5.3(disc.7)` `§22.10` | cada módulo é dono dos próprios tunables; cross-cutting em `cadastros`; **nunca JOIN cross-schema**; alimenta o white-label do portal (5.1) |
| 1.10 | Reset/recuperação de fator com **aprovação dual** para poder elevado (operação `resetar_mfa`) | `[FATO]` `§22.5.2(eixo F)` | operação do `admin_ente`; já descrita em §22.5 |

**Fora (guardrail):** SSO AD/LDAP do município, federação outros IdPs, grupos/funções dinâmicas.
**Nota (GAP 2):** a administração do ente **não é módulo** — é a área de UI 1.8–1.10 (front + contrato
HTTP), superfície de design da trilha UX (ver seção 7).

---

### 16.2 — Cadastros Estruturais Legislativos · `[PAR]` · M0 · §22.4 / §22.5(mandato)

| # | Feature | Tags | Notas |
|---|---|---|---|
| 2.1 | Vereadores (mandato, filiação, suplência, licença, afastamento) | `[PAR]` `§22.5(mandato c/ cascata)` | mandato = entidade com cascata |
| 2.2 | Mesa Diretora (cargos + rotatividade bienal) | `[PAR]` | |
| 2.3 | Comissões permanentes/temporárias + CPIs | `[PAR]` | "comissões obrigatórias por matéria" 🔎 (especialista em regimento, §22.4.4) |
| 2.4 | Legislaturas e sessões legislativas | `[PAR]` `§22.6` | entidades temporais |
| 2.5 | Blocos / frentes parlamentares | `[PAR]` | |
| 2.6 | **Calendário/agenda institucional + recesso legislativo** (calendário de sessões, agenda de comissões, reserva de plenário) | `[PAR]` `§22.7` `v1.38` | **recesso altera a contagem de prazos** (motor §22.7 + prazos 3.8); entidade temporal (G16) |

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
| 3.11 | **Copiloto de redação de projetos** | `[DIF]` `[IA]` `O1` `§22.3` `§22.9(Eixo 10)` | Aposta 1; saída = sugestão editável (16.8); **LLM de fronteira via porta de inferência vendor-agnóstica** (vendor é config trocável; dado sensível filtrado antes da porta) |
| 3.12 | **Busca semântica intra-câmara no acervo** | `[DIF]` `[IA]` `O1` `§22.3` `§22.9(Eixo 10)` | usa **embeddings open-source self-host + `pgvector`** (não LLM externo); depende da transcrição (4.12) e do corpus indexado (O0) |
| 3.13 | **Autógrafo + envio ao Executivo** (C-1) | `[PAR]` `§22.4(origem_versao=redacao_final)` | texto oficial aprovado; **artefato legal** (classe 3 de GAP 5 — domínio dono, ICP, imutável, não relatório); registra envio ao Prefeito |
| 3.14 | **Controle de sanção/veto + apreciação do veto** (C-1) | `[DIF]` `§22.4` `§22.7.5(S4)` | prazo do Executivo (sanção tácita); veto volta à câmara, votação maioria absoluta (reusa 16.4) |
| 3.15 | **Promulgação + numeração canônica da lei + publicação** (C-1) | `[PAR]` `§22.4(origem_versao=promulgacao)` | **artefato legal** (classe 3 de GAP 5); fecha a fronteira "da proposição à publicação" (§15) |
| 3.16 | **Espécies próprias do Legislativo: Decreto Legislativo, Resolução, Emenda à LOM** | `[PAR]` `§22.4(STI)` `§22.7.5(S4)` `v1.38` | fluxo **sem sanção** do Executivo (≠ PL 3.13–3.15); Emenda à LOM 2/3 em dois turnos (quórum no motor S4); rito → especialista de regimento (G8) |
| 3.17 | **Coautoria / subscrição / apoiamento** de proposições | `[DIF]` `§22.4` `§22.5` `v1.38` | múltiplos autores; assinatura de apoio; subscrição de requerimento — JTBD do vereador (Aposta 2) (G11) |
| 3.18 | **Numeração automática por tipo/ano configurável** (reinício anual, reserva/cancelamento) | `[PAR]` `§22.9(Eixo 2: gapless)` `v1.38` | explicita sub-capacidade de 3.1; numeração errada = nulidade de ato (G15) |
| 3.19 | **Identidade canônica interoperável de norma (URN/LexML)** | `[PAR]` `§22.4(eixo H)` `v1.38` | coordenada pública ≠ UUID técnico; base de citação cruzada/intercâmbio SAPL; **decisão de modelo de dados a resolver antes de materializar** (retrofit = refactor estrutural) (G17) |
| 3.20 | **Registro de publicação = condição de eficácia** (data/veículo do ato) | `[PAR]` `§22.5(eixo F)` `v1.38` | fecha o elo de vigência da lei em 3.15; ≠ ser o DOe-de-registro (V1.5) (G32) |
| 3.21 | **Gestão documental de anexos: upload validado** (antivírus, allow-list MIME, limite, hash) | `[PAR]` `§22.3.4(objeto_store)` `v1.38` | transversal; sem validação na borda = vetor de malware em portal gov (G21) |
| 3.22 | **Expediente — geração de documentos a partir de modelos** (ofício, certidão, requerimento administrativo, convite, mala-direta com merge do domínio) | `[DIF]` `[HERO]` `§22.4` `v1.38` | **nova superfície**; trabalho mais frequente do servidor — SAPL e LegisFácil já têm; **maior risco de POC** (G5) |
| 3.23 | **Expediente — protocolo geral/único** (proposição + documento administrativo recebido/expedido) | `[PAR]` `v1.38` | numerador institucional além de proposições (G6) |

**Fora (guardrail):** integração c/ processo legislativo federal/estadual (V2), mineração cross-câmara (V1.5), similaridade entre proposições (V2). **Prazos/rito exato do veto a confirmar com especialista de regimento** (variam por LOM). **Diferidos (revisão v1.38):** recepção estruturada do Executivo (mão inversa do autógrafo), sustação de atos/convocação de secretário/pedidos de informação, documentos acessórios tipados, comissão processante/cassação (DL 201/67, raro).

---

### 16.4 — Sessões Plenárias · `[PAR]`+`[DIF]` · M2–M3 · §22.6 / §22.3.4

| # | Feature | Tags | Notas |
|---|---|---|---|
| 4.1 | Pauta eletrônica (expediente + ordem do dia), versionada | `[PAR]` `§22.6(pauta híbrida)` | |
| 4.2 | Painel eletrônico de votação em telão | `[PAR]` `§22.6` `[SSE]` | real-time via SSE (módulo `tempo_real`, §22.10) |
| 4.3 | Votação nominal / simbólica / secreta com registro auditável | `[PAR]` `§22.4(votos secretos em tabela separada)` | |
| 4.4 | Controle de quórum em tempo real | `[PAR]` `§22.6` `§22.7.5(S4: quórum = guard)` | regra de plenário no motor (envelope de guard, não compliance) |
| 4.5 | Registro de presença por vereador (append-only) | `[PAR]` `§22.6` | presença = eventos |
| 4.6 | Inscrição de oradores + cronômetro de tribuna | `[PAR]` `§22.6(tempos de tribuna = config no motor)` | |
| 4.7 | Captação/gravação de áudio-vídeo + diarização | `[PAR]` `[IA]` `O0` `§22.3.4` `§22.9(Eixo 10)` | pipeline da Onda 0; **ASR Whisper-class self-hosted em GPU própria — áudio de plenário nunca sai**; node-pool de GPU é adição ao cluster CPU-only |
| 4.8 | Endpoint de ingestão de áudio agnóstico à fonte | `[PAR]` `§22.3.4` | M3 (porta única OBS/appliance/estúdio) |
| 4.9 | Utilitário CLI / watch-folder genérico (~2 sem) | `[PAR]` `§22.3.4` | fallback p/ qualquer fonte |
| 4.10 | Transmissão ao vivo via YouTube Live (integração) | `[PAR]` | não reimplementar transmissão |
| 4.11 | Anexação de ata redigida externamente (upload, indexação, vínculo, imutabilidade, ICP-Brasil) | `[PAR]` `§22.4.3(disc.4)` `§22.5(eixo F)` | decisão v1.7; só texto extraível; **artefato legal** (classe 3 de GAP 5) |
| 4.12 | Transcrição automática + pré-atribuição de fala (Caminho C) | `[DIF]` `[IA]` `O0/O1` `§22.3` `§22.9(Eixo 10)` | transcrição no **ASR self-host (Whisper-class, GPU própria)**; **embeddings self-host + `pgvector`** alimentam a busca (3.12); revisão manual opcional |
| 4.13 | **Geração automática de ata pós-sessão por IA** | `[DIF]` `[IA]` `[HERO]` `O1` `§22.6` `§22.9(Eixo 10)` | modo "produtividade"; revisão humana obrigatória (16.8); **rascunho via LLM de fronteira na porta vendor-agnóstica** (dado de sessão fechada filtrado antes da porta); a **ata publicada** é artefato legal (classe 3) |
| 4.14 | **Tipos de sessão como entidade de 1ª classe** (ordinária/extraordinária/solene/especial) | `[PAR]` `§22.6(tipo+capabilities)` `v1.38` | extraordinária só tem OD; solene não delibera; sem isso só roda a ordinária (G1) |
| 4.15 | **Convocação oficial + edital com prazo regimental + ciência registrada** | `[PAR]` `§22.6` `§22.10(GAP 4: ledger)` `v1.38` | convocação fora do prazo **anula a sessão**; ciência = ledger durável (flag 8) (G2) |
| 4.16 | **Incidentes processuais da sessão** (questão de ordem, pedido de vista, votação em bloco, verificação, urgência, retirada de pauta) | `[PAR]` `§22.6(tipo_fala/decisao_mesa)` `§22.7.5(S4)` `v1.38` | uma sessão real trava sem eles; regras no motor (envelope de guard) (G3) |
| 4.17 | **Mesa de condução ao vivo** (abrir/encerrar, conceder/cassar palavra, abrir/fechar votação, declarar resultado, suspender) | `[DIF]` `§22.6` `[SSE]` `v1.38` | painel de **operação** de quem preside (≠ telão 4.2, ≠ dashboard 11.4); persona presidente/Mesa = quem assina (G4) |
| 4.18 | **Audiência pública como tipo de reunião** (metas fiscais LRF art.9§4; PPA/LDO/LOA art.48) | `[PAR]` `§22.6` `v1.38` | acontece na câmara perante comissão; reusa pauta+ata+publicação (G10) |
| 4.19 | **Julgamento das contas do Prefeito + contas da Mesa** (parecer prévio TCE → votação; rejeição 2/3) | `[DIF]` `§22.7.5(S4)` `§22.4` `v1.38` | competência-âncora (CF art.31); reusa votação (4.3) + quórum qualificado (motor S4); matéria = Decreto Legislativo (3.16) (G9) |
| 4.20 | **Legenda/closed-caption da transmissão** (derivada da transcrição 4.12) | `[PAR]` `[IA]` `§22.6` `v1.38` | acessibilidade (LBI 13.146) quase de graça sobre 4.12; janela Libras diferível (G27) |
| 4.21 | **Livro de atas canônico** (coleção numerada/contínua/imutável da legislatura) | `[PAR]` `[RM]` `§22.4.3(disc.4)` `v1.38` | projeção sobre atas (4.11/4.13); artefato que o jurídico audita (G24) |

**Fora (guardrail):** software de captação local proprietário; **Plugin de Captura Sincronizada rico** e adaptadores por fornecedor (satélite); shorts automáticos (V1.5); plataforma própria de transmissão; multi-plataforma simultânea.

---

### 16.5 — Transparência e Portal Público · `[PAR]`+`[DIF]` · M2–M4 · §22.1 / §22.3

| # | Feature | Tags | Notas |
|---|---|---|---|
| 5.1 | Portal público white-label (identidade visual configurável, **não** estrutura) | `[PAR]` | branding configurado na área `admin_ente` (1.9) |
| 5.2 | Publicação automática (proposições, atas, votações nominais, presenças, OD, vereadores, comissões, regimento, legislação) | `[PAR]` | alimentado por domain events |
| 5.3 | Acessibilidade eMAG/WCAG AA + responsivo | `[PAR]` | requisito de design (trilha UX) |
| 5.4 | **Resumo em linguagem simples de proposições** | `[DIF]` `[IA]` `O1` `§22.3` `§22.9(Eixo 10)` | Aposta 1; revisão humana antes de publicar (16.8); **LLM de fronteira via porta vendor-agnóstica** |
| 5.5 | Acompanhamento de proposição por cidadão + notificações (e-mail V1; push c/ PWA) | `[DIF]` `§22.10(GAP 4)` `§22.5` | notificação ao cidadão é **consent-gated** (§22.5); **entrega** = **ledger durável idempotente** no `paineis` (flag 8) |
| 5.6 | **Timeline pública da tramitação** da proposição (#5) | `[DIF]` `[RM]` | read-model sobre eventos; demo power + transparência |
| 5.7 | **Artefato de publicação oficial** (assinado/numerado/imutável) + feed ao DOM externo (C-2 leve) | `[DIF]` `§22.5(eixo F)` `§22.4.3(disc.4)` | **artefato legal** (classe 3 de GAP 5); reusa assinatura; DOe-de-registro = V1.5 (ver Fora) |
| 5.8 | **Legislação consolidada: repositório as-enacted + consolidação manual assistida** (C-3) | `[DIF]` `§22.4(eixo B)` | texto vivo versionado pelo servidor; IA-auto e bulk histórico = depois (ver Fora) |
| 5.9 | **Transparência ativa/fiscal do próprio órgão Câmara — camada de publicação** (rol art.8 LAI: remuneração/diárias/contratos do órgão; LC131 tempo real) | `[PAR]` `§22.10(consumo)` `v1.38` 🔎 | **publica**, não produz (contábil/SIAFIC fica Fora — §17); consome do sistema contábil; 🔎 conector de consumo (G12) |
| 5.10 | **Portal do titular de dados (LGPD art.18)** — acesso/correção/eliminação + contato do Encarregado/DPO | `[PAR]` `§22.5` `v1.38` | obrigação do controlador (Lei 13.709); reusa front do portal; ≠ grant do operador (12.7) (G14) |
| 5.11 | **Dados abertos / API de dataset** (formato aberto, legível por máquina) | `[PAR]` `§22.3.3` `v1.38` | Decreto 8.777 + LAI art.8 §3 — obrigação de transparência ativa; exportável sobre event-driven (G20) |
| 5.12 | **Canal real de integração ao DOM** (formato/protocolo/confirmação/idempotência) | `[PAR]` `§22.7.8(padrão)` `v1.38` 🔎 | 🔎 validar com beachhead se "publicar por nós" é dor; se sim, mesmo rigor do adapter de remessa (G22) |

**Fora (guardrail):** portal da transparência geral (despesas/folha/contratos/licitações da câmara) — **produção** do dado é responsabilidade do sistema administrativo, integramos por consumo (**exceção v1.38:** a *publicação* da transparência ativa do órgão — 5.9 — entra, pois é obrigação própria da casa, §17); **DOe-de-registro** da câmara (adoção legal + SLA elevado + risco jurídico) → V1.5 se cliente exigir; **consolidação automática por IA** (Onda 2, sobre o copiloto) e **consolidação em massa do acervo histórico** (problema de migração, 16.9).

---

### 16.6 — Participação Cidadã (mínima) · `[PAR]` · M4 · §22.4(events)

| # | Feature | Tags | Notas |
|---|---|---|---|
| 6.1 | **e-SIC amplo** (qualquer info pública do órgão) **+ timer de prazo LAI (20+10) + instância recursal** | `[PAR]` `§22.7.7(prazo)` `v1.38` | **corrige** a restrição ilegal anterior — a LAI não restringe o objeto do SIC por tema; timer reusa `prazo_dominio_ativo` (G7) |
| 6.2 | **Ouvidoria conforme Lei 13.460** (decisão 30d prorrogável + relatório anual) + **Carta de Serviços ao Usuário** + roteamento por assunto/comissão | `[PAR]` `§22.7.7(prazo)` `[RM]` `v1.38` | TCE/CGU auditam Carta e relatório; reusa motor de prazo + read-model (G13) |
| 6.3 | Comentários públicos em proposições com moderação | `[PAR]` | |

**Fora (guardrail):** consulta pública estruturada (V2), chatbot cidadão (V2.5+), audiência virtual/e-democracia (V2+), ranking de engajamento por vereador (V2).

---

### 16.7 — Experiência para Vereador (Aposta 2) · `[DIF]` · M3–M4 · §22.5 / §22.6

| # | Feature | Tags | Notas |
|---|---|---|---|
| 7.1 | **PWA do vereador (responsivo + instalável, Web Push)** como entrada principal — **sem app nativo na V1** | `[DIF]` `§22.9(Eixo 9)` | **V1 = PWA-first**: o frontend React/Next (Eixo 8) responsivo+instalável cobre as três superfícies no mobile; **Web Push** (iOS 16.4+/Android) p/ alerta de sessão/votação. **App nativo deferido (RN+Expo, pós-V1, registrado não pré-construído)**. Trilha UX é crítica aqui |
| 7.2 | Dashboard pessoal (próximas sessões, pautas, proposições próprias em tramitação) | `[DIF]` `[SSE]` | tempo real via módulo `tempo_real` (§22.10) |
| 7.3 | Assinatura eletrônica em 2 toques | `[DIF]` `§22.5` | |
| 7.4 | Notificações push | `[PAR]` `§22.10(GAP 4)` | push via **Web Push da PWA** (Eixo 9); **entrega registrada no ledger durável** (flag 8); servidor/vereador na função **não** é consent-gated; consolida na central 11.6 |
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
| 8.6 | **Ciclo de vida/observabilidade do modelo de IA** (qual versão de modelo gerou qual artefato legal; versionamento, rollback, drift) | `[DIF]` `[IA]` `§22.3.4` `§22.9(Eixo 10)` `v1.38` | a porta vendor-agnóstica troca o LLM por config e o ASR self-host muda → ata-IA `[HERO]` muda sem rastro; reprodutibilidade/auditoria (G19) |

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
| 9.6 | **Portabilidade / saída do contrato** (off-boarding: dump completo dos dados do ente ao encerrar) | `[DIF]` `§22.1(inv.2)` `§22.5(LGPD art.18)` `v1.38` | simétrico à entrada (9.1); **objeção jurídica de não-aprisionamento em pregão**; barato no event-driven; fecha o ciclo `encerrado` (12.1) (G18) |

**Fora (guardrail):** conectores automatizados para concorrentes como produto (satélite separado, sem cliente fechado); migração de dados administrativos (folha/contábil/licitações) — não há o que migrar.

---

### 16.10 — Operação, SLA e Compliance · `[DIF]` · M4 · §22.7 / §22.1(inv.9)

| # | Feature | Tags | Notas |
|---|---|---|---|
| 10.1 | SLA de uptime para janelas de sessão (noites ter/qua/qui) | `[DIF]` `§22.1(inv.9 SLIs negócio)` | |
| 10.2 | Suporte de plantão noturno dedicado a sessões | `[DIF]` | confiança operacional como diferencial comercial |
| 10.3 | Status page pública + monitoramento sintético das rotas críticas | `[PAR]` `§22.1(inv.7)` | |
| 10.4 | Motor de regras de compliance configurável (TCE-CE 100% coberto na V1) | `[DIF]` `§22.7` | **motor-dsl/ é o protótipo validado**; conteúdo TCE-CE = config (Invariante 4) |
| 10.5 | Geração de artefato de remessa ao TCE-CE | `[DIF]` `§22.7(+2 eixo aberto)` `§22.10(GAP 5)` 🔎 `[GAP]` | **artefato regulatório** (classe 2 de GAP 5) — dono é `compliance`; a *geração do arquivo* é um dos **+2 eixos do motor ainda não abertos** (§22.7); o runtime só rastreia a **obrigação** (§22.7.7). ⚠️ permanece **bloqueado em layout regulatório real** `[GAP]` |

**Fora (guardrail):** TCEs de outros estados (expansão geográfica), ISO 27001 (ano 2), SOC 2 (ano 2–3).

---

### 16.11 — Painéis, Pendências e Notificações (read-model) · `[DIF]` · M3–M4 · §22.1(inv.9) / §22.7.7

**Racional:** as personas decisoras esperam métricas (presidente "ganha com engajamento cidadão";
servidor com "horas poupadas") mas nenhuma feature as entregava; o motor de compliance (16.10)
monitora prazo mas o estado ficava invisível. Esta camada **torna visível o que já capturamos** —
read-model/projeção sobre o substrato event-driven + audit log + motor de prazo. Custo baixo, valor
alto; **11.1 e 11.4 são entrega de aposta/persona já construída-mas-invisível**, não analytics opcional.
**Mora no módulo de projeção `paineis`** (§22.10); rebuildável do event log — **exceto** o ledger de
entrega de notificação (durável, ver 11.6 / flag 8).

| # | Feature | Tags | Notas |
|---|---|---|---|
| 11.1 | **Painel de prazos "o que vence"** (compliance TCE + tramitação) | `[DIF]` `[RM]` `§22.7.7` | o motor já monitora; **entrega visível da aposta 3**; ataca o gatilho nº1 de compra (TCE) |
| 11.2 | **Caixa de pendências / "minhas tarefas hoje"** (assinar, parecer, revisar ata-IA/transcrição) | `[DIF]` `[RM]` | momento-matador da POC do servidor |
| 11.3 | **Painel de tramitação** (funil/kanban "onde está cada proposição") | `[DIF]` `[RM]` `§22.4(eixo C)` | read-model sobre a máquina de estados |
| 11.4 | **Dashboard institucional da Mesa** (proposições por status, sessões, presença, engajamento cidadão) | `[DIF]` `[RM]` | **cumpre a proposta de valor da persona presidente** |
| 11.5 | Busca global simples (não-IA) | `[PAR]` `[RM]` | distinta da semântica (3.12); table-stakes |
| 11.6 | **Central de notificações/alertas unificada** (C-4) | `[DIF]` `[RM]` `§22.10(GAP 4)` | **vista** (sininho/inbox) = projeção **dropável** no `paineis`; **entrega** (e-mail/push) = **ledger durável idempotente** — a *única peça de verdade durável* desta camada (exceção consciente à regra "projeção não tem verdade"); 3 planos do mesmo evento (outbox/SSE/notificações); redelivery é no-op (nunca re-spam). Graduação a módulo `notificacoes` próprio = V1.5/V2 (push/multicanal/digest) |
| 11.7 | Exportação PDF/CSV de listas (proposições, presenças, votações) | `[PAR]` `[RM]` `§22.10(GAP 5)` | **relatório-projeção** (classe 1 de GAP 5) — mora no `paineis`, dropável; **não é módulo de relatórios** (seria JOIN cross-schema, proibido); BI/report-builder/benchmarking → V2 |
| 11.8 | **Espelho / ficha da matéria** (ficha-resumo canônica: autoria, ementa, situação, histórico, anexos, num/ano — imprimível/citável) | `[PAR]` `[RM]` `v1.38` | read-model sobre o substrato; o servidor imprime/anexa, o jurídico cita (G23) |

**Fora (guardrail):** BI de verdade — report-builder, exportação custom configurável, benchmarking
cross-câmara, ranking de engajamento por vereador (§16.6) — V2 (mesmo substrato, camada robusta).

---

### 16.12 — Console do Operador SaaS (supratenant) · `[DIF]` `[SUPRATENANT]` · M0+M4 · §22.10 / §22.5 / §22.9(Eixo 6)

> **`[FATO]` (GAP 1+3, v1.31).** Categoria de módulo **nova** (3ª — supratenant/operacional):
> `oplenario.admin_sistema`, dono de verdade **acima da linha do tenant** (schema **sem `ente_id`**),
> atrás do Keycloak **fisicamente separado** (§22.9 Eixo 6). **Escopo operador-facing — fora da
> fronteira tenant-facing da §16; ancorado em §22.10, não em §16.** É o único épico do backlog que não
> nasce de uma capacidade §16.

| # | Feature | Tags | Notas |
|---|---|---|---|
| 12.1 | Registry de entes + provisionamento (emite `ente_id`; ciclo `provisionar→ativo→suspenso→encerrado`) | `[FATO]` `[SUPRATENANT]` `§22.10` `§22.7.6` | a tabela-raiz da tenancy; **não pode ter `ente_id`**; outros módulos referenciam por **guard de serviço**, nunca FK/JOIN cross-schema |
| 12.2 | Billing / contrato / plano | `[FATO]` `[SUPRATENANT]` `§22.10` 🔎 | **build-vs-buy a decidir** (ver seção 6); piloto provável c/ cobrança artesanal |
| 12.3 | Feature flags / config global da plataforma (≠ config-do-ente) | `[FATO]` `[SUPRATENANT]` `§22.10` | global da plataforma; distinto da config-do-ente do GAP 2 (área `admin_ente`, 1.9) |
| 12.4 | Principal + papéis do admin interno (RBAC **disjunto** do `usuario_papel` de tenant) | `[FATO]` `[SUPRATENANT]` `§22.5.1` `§22.5.2(eixo D)` | admin interno é **principal supratenant, não vínculo de tenant**; hardware key física obrigatória (§22.5.1) |
| 12.5 | Auditoria interna do operador (retenção máxima) | `[FATO]` `[SUPRATENANT]` `§22.1(inv.10)` `§22.5.1` | append-only; retenção máxima da plataforma |
| 12.6 | Observabilidade cross-tenant (superfície de leitura) | `[FATO]` `[SUPRATENANT]` `§22.10` | leitura cross-tenant legítima do operador |
| 12.7 | **Grant de acesso de suporte** (escopado a um ente / com prazo / justificado / consentido-LGPD / auditado / sob hardware key + step-up) | `[DIF]` `[SUPRATENANT]` `§22.5.2(eixo D)` 🔎 `[GAP]` | a forma como o operador toca dado de tenant; **nunca vínculo standing**; gate de consentimento LGPD depende da revisão jurídica (§22.5.4) — mecânica entrega, consentimento `[GAP]` jurídico |
| 12.8 | Anti-escalonamento cross-esfera (token de operador **sem `ente_id`**; middleware trata `ente_id` ausente como supratenant válido **só** em `admin_sistema`; cross-esfera → 403/404) | `[FATO]` `[SUPRATENANT]` `§22.5.2(eixo E)` `§22.10` | **2ª dimensão do teste de vazamento** (cross-esfera, além de cross-tenant) — vira **gate de CI** |

**Fora (guardrail):** o console **não duplica o catálogo do motor** — só o *opera* (deploy de versão de
template, revisão do type-check); o catálogo segue declarado em código e versionado no `motor`
(§22.7.6). BI/analytics do operador além de observabilidade de leitura → V2. Realm-por-ente (descartado, §22.9 Eixo 6).

---

## 3. As 5 capacidades de IA (visão cross-módulo)

A diferenciação de IA não é um módulo — atravessa o produto. Todas passam pela **Camada de Confiança (16.8)**:

| Capacidade | Mora em | Onda | Hospedagem (§22.9 Eixo 10) | Depende de |
|---|---|---|---|---|
| Transcrição + pré-atribuição (Caminho C) | 4.12 | O0/O1 | **ASR Whisper-class self-host (GPU própria)** | captação 4.7/4.8 |
| Busca semântica intra-câmara | 3.12 | O1 | **embeddings open-source self-host + `pgvector`** | transcrição + corpus indexado (O0) |
| Copiloto de redação | 3.11 | O1 | **LLM de fronteira via porta vendor-agnóstica** | corpus legal (O0) |
| Resumo cidadão em linguagem simples | 5.4 | O1 | **LLM de fronteira via porta vendor-agnóstica** | proposição protocolada |
| **Ata automática por IA** `[HERO]` | 4.13 | O1 | **LLM de fronteira via porta vendor-agnóstica** | transcrição confiável → captação como oferta |

**Fronteira de soberania (Eixo 10):** áudio/dado de sessão fechada e dado pessoal **nunca saem** —
ASR e embeddings rodam self-host; só o que pode sair atravessa a **porta de inferência vendor-agnóstica**
(qualquer LLM é config trocável, não compromisso). **Evolução Onda 2:** consolidação de legislação
assistida por IA (sobre 5.8 + o copiloto) é o 6º uso natural, deferido. **[REC]** a trilha de UX deve
tratar a **superfície de revisão humana (8.5)** como tela de primeira classe — encontro da Aposta 1 (IA)
com a Aposta 2 (UX), onde o servidor decide a POC.

---

## 4. Sequência de construção (§18) → features

| Banda | Foco | Features-chave |
|---|---|---|
| **M0–1** | Fundação + Onda 0 IA + **raiz da tenancy** | 1.x (inc. **1.8/1.10** área `admin_ente`), 2.x, 4.7 (pipeline+diarização), corpus legal indexado, modelo de dados legislativo, **12.1 (registry de entes — emite `ente_id`), 12.4 (admin interno + Keycloak separado), 12.8 (anti-escalonamento cross-esfera)** |
| **M1–2** | Coração legislativo | 3.1–3.10, 8.1–8.5 (1ª versão), 9.5 (1º conector), **12.5/12.6 (auditoria/observabilidade do operador)** |
| **M2–3** | Sessão + captação + ata anexada | 4.1–4.6, 4.8–4.12, 5.1–5.3, 5.6, 7.1–7.2 (inicial). **Endpoint áudio + CLI funcionais até fim do M3** |
| **M3–4** | IA de valor + cidadão + painéis + hardening | 3.11–3.12, **3.13–3.15 (pós-aprovação)**, 4.13 `[HERO]`, 5.4, **5.7–5.8**, 6.x, **7.5**, **1.9 (branding, junto do portal 5.1)**, 10.1–10.4, **16.11 (11.1–11.7 read-models)**, **12.2 (billing)/12.3 (flags globais)/12.7 (grant de suporte)**, 9.x, fechamento |

**Nota (§18):** M4 é piso, não teto. Exige **≥8 eng + designer sênior + PM + especialista em
regimento**. A ata-IA (4.13) + o pós-aprovação (3.14 veto) + os painéis somam ao escopo de 4 meses —
**apertam** o plano (PRD §2/§5). **O console do operador (16.12) não estava no plano de 4 meses
original:** 12.1/12.4/12.8 são fundação barata e inadiável (sem registry de entes não há tenant), mas
12.2/12.6/12.7 **acrescem** escopo (ver seção 6, risco 11). **PWA-first (7.1) alivia** — remove a banda
implícita "codebase nativo" de M2–M4. Os read-models (16.11) são baratos mas dependem do dado fluindo (tardios). **Adições v1.38:** URN/LexML (3.19) e portabilidade (9.6) são decisões de fundação (M0–M1); espécies/coautoria/numeração (3.16–3.18) e Expediente (3.22–3.23) em M1–M2; sessão completa (4.14–4.21) em M2–M3; fiscalização (audiência/contas 4.18–4.19) e transparência legal (5.9–5.11, 6.1–6.2) em M3–M4.

---

## 5. Cobertura — toda capacidade "Entra" da §16 mapeada + épico supratenant

**105 features tenant-facing em 11 módulos** (§16; inclui as ~24 adições da revisão de completude v1.38)
**+ 8 features supratenant em 1 módulo operador** (§22.10 — `admin_sistema`, fora da fronteira §16) =
**113 features, 12 módulos**.

✅ 16.1→F1.1–1.10 · 16.2→F2.1–2.6 · 16.3→F3.1–3.23 · 16.4→F4.1–4.21 · 16.5→F5.1–5.12 ·
16.6→F6.1–6.3 · 16.7→F7.1–7.5 · 16.8→F8.1–8.6 · 16.9→F9.1–9.6 · 16.10→F10.1–10.5 · 16.11→F11.1–11.8 ·
**16.12→F12.1–12.8 `[SUPRATENANT]`**. *(Ranges 2.6 / 3.16–3.23 / 4.14–4.21 / 5.9–5.12 / 8.6 / 9.6 / 11.8 = adições v1.38.)*

**Nenhum item "Entra" da §16 ficou sem feature.** Itens "Não entra" viraram guardrails por módulo. O
épico **16.12 não deriva da §16** (deriva de §22.10 GAP 1+3 — escopo de operador SaaS). **Admin do ente
(GAP 2)** está coberto por F1.8–1.10 (**área de UI, não módulo**).

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
6. **T5 + remessa TCE (10.5) — duplamente bloqueada (`[GAP]`):** "TCE-CE 100% coberto na V1" (10.4) depende
   das listas granulares da DSL (§22.7.4); a **geração do arquivo de remessa** (10.5) tem agora **dono
   nomeado** (`compliance`, classe 2 de GAP 5) mas é um dos **+2 eixos do motor ainda não abertos** (§22.7)
   **e** o layout regulatório real é `[GAP]` não inventado. Promessa comercial à frente da spec.
7. **Métricas de sucesso (PRD §4):** definição operacional de "tempo economizado" segue lacuna.
8. **Especialista em regimento (§22.4.4):** "comissões obrigatórias por matéria" (2.3) é config estática
   ou expressão DSL?
9. ✅ **Stack + dimensionamento de time:** stack cravada (§22.9, 10/10 — flag 5). Resta o **dimensionamento
   de time** vs. o escopo agora maior (16.12 + pós-aprovação + painéis).
10. **Design (próximo passo da trilha):** features `[DIF]` de UX (16.7 PWA, 5.x portal, 8.5 revisão,
    **16.11 painéis**, **área `admin_ente` 1.8–1.9**) precisam de design antes de virar histórias construíveis.
11. **Console do operador fora do plano de 4 meses (16.12). 🔎** 12.1/12.4/12.8 são pré-requisito barato e
    inadiável; **12.2 (billing), 12.6 (observabilidade cross-tenant), 12.7 (grant de suporte) acrescem
    escopo** ao §18 já apertado. **Decisão:** quanto do 16.12 é go-live-blocking do piloto vs. diferível
    para logo-após (1 câmara não exige billing automatizado nem observabilidade cross-tenant madura).
12. **Billing build-vs-buy (12.2). 🔎 `[REC]`** sem direção no doc-mestre; tensão com a disciplina
    provider-neutral/open-source-first (§22.9 Eixo 1) — SaaS de billing externo = vendor-lock; contrato B2G
    tem particularidades (empenho, nota fiscal, licitação). **Rec.:** parquear; piloto roda artesanal,
    build interno mínimo só quando >N entes justificar (régua §15).
13. **Grant de suporte LGPD (12.7) `[GAP]` jurídico.** mecânica (escopo/prazo/auditoria/hardware key/step-up)
    entrega; o **texto de consentimento e a política de anonimização** estão deferidos à revisão jurídica
    (§22.5.4). Bloqueia "operador toca dado de tenant" em produção até o jurídico fechar.
14. **Teste de vazamento cross-esfera = gate de CI (12.8).** 2ª dimensão do teste de isolamento
    (cross-esfera além de cross-tenant); entra na definição de pronto de todo endpoint supratenant.
15. **`admin_ente` desloca complexidade p/ o front (1.8/1.9).** GAP 2 pôs a orquestração no frontend
    (Eixo 8), compondo HTTP de `identidade`/`cadastros` sem tabela central (§22.5.3 disc.7) — é **trabalho
    de front + contrato HTTP**, não feature backend isolada; afeta estimativa e a trilha de UX (seção 7).
16. **Web Push iOS tem piso de versão (7.1/7.4). 🔎** depende de iOS 16.4+; validar a frota do beachhead —
    se não suportar, push de sessão degrada e o "app nativo deferido" pode antecipar (RN+Expo).

---

## 7. Próximo passo da trilha de produto/UX

Com o backlog em pé (**113 features, 12 módulos** — 105 tenant-facing + 8 supratenant), o próximo movimento
é **design** das superfícies `[DIF]`: PWA do vereador (16.7), portal cidadão (16.5), a **superfície de
revisão humana de IA (8.5)**, os **painéis de 16.11** (onde a confiança operacional e o engajamento ficam
visíveis) e a **área de administração do ente (1.8–1.9)**. O **console do operador (16.12)** é superfície de
design própria, mas operador-facing (prioridade depois das telas que decidem a compra). **Ferramenta de
design (decisão do projeto):** UI/UX Pro Max – Design Intelligence como consultor; artefatos à mão em
`produto/design-system/`.
