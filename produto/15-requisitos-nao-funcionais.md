# 15 · Requisitos não-funcionais da V1 — registro de pendências (gate)

> **Trilha de produto/arquitetura — registro de pendências NÃO-funcionais.** Companheiro de
> [`13-decomposicao-features-v1.md`](./13-decomposicao-features-v1.md) (catálogo funcional) e
> [`14-revisao-completude-features.md`](./14-revisao-completude-features.md) (auditoria funcional).
> Enquanto `13` cataloga *o que o sistema faz*, este cataloga *como ele tem de se comportar* —
> segurança, privacidade operacional, confiabilidade, performance, operação/SRE, custo, legal-B2G —
> e **onde cada requisito já está decidido vs. onde é ponto cego.**
>
> **Status (decisão Daouda Traore, 22/06/2026): REGISTRO. Não endereçar agora** — *"guarda essas pendências,
> futuramente vamos endereçar esses pontos não-funcionais."* Este doc **não toma decisões de
> arquitetura**; mapeia cobertura e nomeia omissões com proposta de régua §15, para abrir uma passada
> dedicada depois (sob o protocolo *Confirma?*, porque vários itens reabrem §16/§22 e o §18).
>
> **Método:** auditoria por **3 lentes não-funcionais independentes** (segurança+privacidade ·
> confiabilidade/DR/performance · ops/QA/custo/legal-B2G), cada uma varrendo o SSOT inteiro
> (doc-mestre §22.1 invariantes; `arquitetura/` §22.3–§22.10; `produto/13–14`) e classificando
> **COBERTO / PARCIAL / AUSENTE** com citação `arquivo:linha`.
>
> **Convenção:** `[FATO]` · `[INF]` · `[REC]` · 🔎 a validar. **Severidade:**
> 🔴 fundação / pré-deploy-gov · 🟠 alvo ou processo a cravar · 🟡 profundidade · ⚪ trivial.

---

## 0. Veredito

O **funcional** está mapeado (113 features, auditado em `14`). O **não-funcional** está **decidido na
substância onde é modelo de domínio** e **em branco onde é camada transversal de infra/operação**.
Padrão nítido:

> O SSOT decidiu a fundo **todo NFR que é modelo de domínio** (isolamento multi-tenant, imutabilidade
> de auditoria, apagamento/consentimento LGPD) e **toda resiliência de runtime** (idempotência
> exactly-once, graceful drain, DLQ, fallback de stream). **Nunca abriu** a camada de **hardening de
> infra + alvos quantitativos + processo operacional.** Várias omissões 🔴 são **pré-condição de
> qualquer deploy gov** e ferem os próprios editais ISO 27001/27017 que `22-9` invoca como argumento
> de procurement — e **nenhuma delas está protegida pela régua §15 como diferimento consciente**:
> são pontos cegos, não escolhas.

Contagem: **6 omissões 🔴 de fundação · ~13 omissões 🟠 (alvos quantitativos + processo SRE/CI) ·
profundidade 🟡 + diferidos conscientes (corretos) · 1 trivial ⚪.**

---

## 1. O que JÁ está COBERTO (não relitigar)

| Dimensão | Onde vive | Evidência |
|---|---|---|
| **Isolamento multi-tenant** | RLS + `ente_id` + partição `hash(ente_id)` + **teste de vazamento cross-tenant E cross-esfera no CI** | §22.2; `22-9:21`; `22-5:70,166`; `22-10:48`; feature 12.8 |
| **Auditoria imutável + retenção por classe** | append-only nível-a (constraint/trigger) + tabela de retenção com pisos legais | Inv.10; `22-4:50`; `22-5:106,116–126` |
| **LGPD (modelo de domínio)** | apagamento diferenciado por classe + consentimento versionado + consent-gate | `22-5:132–142`; `22-10:23`; feature 5.10 |
| **Resiliência de runtime** | idempotência exactly-once (UNIQUE+CAS), outbox transacional, taxonomia 6-falhas, DLQ | `22-3:16,75–85`; `22-9:19,27` |
| **Deploy sob SLA de sessão** | graceful drain SSE (`preStop`+grace longo) + rolling/blue-green + **gate de janela de deploy** | `22-9:46` |
| **Continuidade da sessão ao vivo** | SSE c/ long-poll fallback, replay `Last-Event-ID` (janela 5 min), cronômetro client-side | `22-6:42`; `22-9:38` |
| **Gatilhos de escala** | shared-DB→pool-per-UF (gatilhos explícitos) + GPU node-pool | §22.2; `22-9:21,47` |
| **Testes em níveis + import-lint** | unit/integração/E2E + matriz de fronteira no CI | `22-10:42–48` |
| **Migrations** | Migratus, pares `.up`/`.down` por módulo | `22-9:62`; `backend/resources/migrations/` |
| **Acessibilidade de produto** | WCAG AA/eMAG (medido à mão no design-system, 2 temas) | feature 5.3; design-system |

---

## 2. 🔴 Omissões de fundação / pré-deploy-gov (pontos cegos — não diferimentos)

| # | Omissão | Por que é 🔴 | Régua §15 — proposta [REC] |
|---|---|---|---|
| **NF1** | **Cripto em trânsito + mTLS core↔IA** | a fronteira `22-3` trafega **áudio de plenário + dado pessoal antes do filtro** sem cláusula de transporte cifrado; editais ISO 27001/27017 (`22-9:11`) presumem | **decisão de fundação** — cravar antes de materializar |
| **NF2** | **Cripto em repouso** (Postgres, MinIO, backups) | atas/leis/votos em repouso sem cifragem decidida | fundação |
| **NF3** | **Gestão de segredos** (cofre + rotação) | credenciais de Postgres/Keycloak/MinIO/porta-LLM sem cofre nem rotação | fundação |
| **NF4** | **Resposta a incidente + notificação à ANPD** (LGPD art.48) + RIPD/DPIA como artefato | obrigação legal direta do controlador; **zero menção** no repo | entra leve (processo) |
| **NF5** | **Programa de teste de segurança** (SAST/dep-scan/DAST/pentest) + **gate de segurança no CI** | o CI valida fronteira/vazamento, **nunca segurança** | entra leve (gate) + pentest pré-go-live |
| **NF6** | **Cadeia de suprimentos** (scan de deps, SBOM, imagens base fixadas/assinadas) | Talos é base enxuta, mas nada escaneia/assina dependências | hardening M4 |

---

## 3. 🟠 Alvos quantitativos em branco (precisam virar número)

Nenhum protegido pela §15 — são vazios, não escolhas. (Os únicos percentuais de uptime no repo vivem
num **mock de UI**, `status.html:75–79` — não são alvo committed.)

| # | Omissão | Nota |
|---|---|---|
| **NF7** | Alvo numérico de uptime/disponibilidade | SLA é só qualitativo ("janela de sessão"); número AUSENTE (`§16.10`, `10.1`) |
| **NF8** | RTO/RPO explícitos | ingredientes existem (PITR, failover CloudNativePG `22-9:45`) sem objetivo |
| **NF9** | Budgets de latência por fluxo core | Inv.9 p99 é exemplo retórico; budget real só na fronteira IA (`22-3:84`) |
| **NF10** | Estratégia de teste de carga/estresse | só "perfil de carga em rajada" como racional (`22-9:25`), sem plano |
| **NF11** | Alvos de concorrência (vereadores/sessões simultâneas) | nenhum número; só "~1.500 entes" como denominador |
| **NF12** | Sizing de storage (áudio + transcrições) | retenção/expurgo diferida (G30); sizing nunca calculado |

---

## 4. 🟠 Processo operacional / SRE / disciplina de CI

| # | Omissão | Nota |
|---|---|---|
| **NF13** | SLO interno + error budget | distinto do SLA comercial; só SLO de IA existe (`22-3:90`) |
| **NF14** | Incidente / on-call / postmortem | "plantão noturno" (`10.2`) é a *oferta*, não o *processo* — zero runbook/on-call |
| **NF15** | Restore testado + runbook de DR + ensaio de failover | reconhecido como custo de SRE (`22-9:49`), sem disciplina definida |
| **NF16** | Staging/homologação + segredos por ambiente | só dev (`kind`) e prod (Talos); sem ambiente intermediário |
| **NF17** | Migrations zero-downtime (expand-contract) | tensiona com o gate blue-green (`22-9:46`); não tratado |
| **NF18** | FinOps / custo por ente | Inv.8 dá o gancho `ente_id`; nada o usa p/ unit-economics |
| **NF19** | Gate de regressão de a11y no CI | AA é requisito (`5.3`), medido à mão; sem gate automatizado |

---

## 5. 🟡 Profundidade (nomeado, mecânica indefinida) + diferidos conscientes (§15 OK)

**Profundidade — feature existe, mecânica a materializar:** upload validado (3.21) · provenance
modelo-IA→artefato-legal (8.6 / G19, datamodel a cravar) · papel do Encarregado/DPO + fluxo interno
(5.10) · spec de off-boarding (9.6: formato/escopo/SLA do dump, inclui binários do `objeto_store`?) ·
disciplina de restore testado.

**Diferidos conscientes (corretos sob §15):** anti-abuso de portal — rate-limit/CAPTCHA/moderação
como produto (G31, hardening M4) · retenção/expurgo de áudio bruto (G30) · mecânica de billing
B2G — empenho / nota de empenho / NF (12.2, build-vs-buy parqueado).

---

## 6. ⚪ Trivial — só cravar a postura

**NF20 — i18n/localização:** **silêncio**, nem decisão de diferimento (todos os outros diferimentos
do projeto são explícitos). Cravar: *"PT-BR único; i18n fora de escopo (régua §15)."*

---

## 7. Sequência recomendada [REC] (a confirmar — endereçamento futuro)

1. **Cravar antes de materializar** (fundação barata mas inadiável): NF1, NF2, NF3 (cripto
   trânsito+repouso, segredos) + postura i18n (NF20).
2. **Entra leve na V1** (processo/legal): NF4 (incidente/ANPD), NF5 (gate de segurança no CI).
3. **Vira número/processo no hardening M4:** NF6–NF19 (alvos quantitativos + processo SRE + SBOM +
   a11y-gate).
4. **Diferidos:** §5 acima.

> **Reabre §18** (dimensionamento de time): os 🔴 são fundação barata mas inadiável; os 🟠 acrescem
> trabalho de SRE/segurança ao plano já apertado. A decisão de quais entram na V1 fica para a passada
> dedicada, sob *Confirma?*.

---

## 8. Fontes (âncoras)

- **LGPD** — Lei 13.709/2018 arts.18/23/41/**48** (direitos do titular, agente público, Encarregado,
  **notificação de incidente à ANPD**).
- **Procurement gov / segurança da informação** — ISO/IEC 27001 · 27017 (cloud) · 27018 (PII em cloud);
  invocados em `arquitetura/22-9-stack.md` como argumento de "procurement-safe".
- **Hardening** — OWASP ASVS · CIS Benchmarks (referência de prática, não citados no SSOT ainda).
- **Invariantes** — §22.1 (esp. 6 soberania · 7 observabilidade-4-tipos · 8 `ente_id` em todo sinal ·
  9 SLIs de negócio · 10 audit imutável).
- **Subseções** — `arquitetura/22-3-contrato-core-ia.md` · `22-5-auth.md` · `22-9-stack.md` ·
  `22-10-monolito.md`.
