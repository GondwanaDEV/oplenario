# O Plenário — Inventário de telas

> Mapa do que **desenhar** para escalar o design system ao catálogo completo
> (**113 features / 12 módulos**, fonte: [`produto/13-decomposicao-features-v1.md`](../../13-decomposicao-features-v1.md)).
> As 113 features **não** viram 113 telas: muitas são backend, padrões transversais ou embutidas
> noutra superfície. Colapsam em **~30 telas distintas**, das quais **4 já estão feitas** (as que
> decidem a compra). Este doc prioriza as restantes.
>
> Arquétipos e regras de composição: [`PADROES-DE-COMPOSICAO.md`](./PADROES-DE-COMPOSICAO.md).
> Biblioteca: [`componentes.html`](./componentes.html).

## 0. Como ler

**Arquétipo** (forma da tela — ver PADROES §2): `cockpit` · `cabine` (ao vivo) · `balcão` (servidor
produz artefato) · `pública` (portal white-label) · `lista` (tabela filtrável) · `ficha` (detalhe) ·
`wizard` (multi-passo) · `config` (admin) · `calendário` · `display` (telão).

**Cobertura:** ✅ feito · ⬜ a desenhar · 🔩 backend/sem tela própria · ⤵ embutido noutra tela.

**Prioridade de design:** **ALTA** (fecha fluxo central, aposta de produto, ou momento-matador de
POC) · **MÉDIA** (paridade institucional, admin) · **BAIXA** (diferível, nicho, operador-facing).

---

## 1. Matriz por módulo

### 16.1 — Identidade, Perfis e Auditoria
| Feature | Tela / destino | Arquétipo | Cob. | Prio. |
|---|---|---|---|---|
| 1.1 Auth passkey/senha/TOTP | **Login / MFA** | config/auth | ⬜ | MÉDIA |
| 1.2 SSO gov.br (cidadão) | login do portal | ⤵ pública | ⤵ | — |
| 1.4 RBAC por perfil | admin do ente | ⤵ | ⤵ | — |
| 1.6 Trilha de auditoria | **Auditoria / trilha** | lista | ⬜ | MÉDIA |
| 1.8 Área admin do ente (usuários/vínculos/reset MFA) | **Admin do ente — usuários** | lista+config | ⬜ | MÉDIA |
| 1.9 Config do ente + branding white-label | **Config da Câmara** (`telas/config-ente.html`) | config | ✅ | — |
| 1.3/1.5/1.7/1.10 | infra/embutido | 🔩/⤵ | — | — |

### 16.2 — Cadastros Estruturais
| Feature | Tela | Arquétipo | Cob. | Prio. |
|---|---|---|---|---|
| 2.1 Vereadores (mandato, filiação, licença) | **Cadastro de vereadores** | lista+ficha | ⬜ | MÉDIA |
| 2.2 Mesa Diretora | **Mesa Diretora** | config | ⬜ | MÉDIA |
| 2.3 Comissões / CPIs | **Comissões** | lista+ficha | ⬜ | MÉDIA |
| 2.6 Calendário + recesso | **Calendário institucional** | calendário | ⬜ | MÉDIA |
| 2.4 Legislaturas · 2.5 Blocos/frentes | cadastros menores | lista/config | ⬜ | BAIXA |

### 16.3 — Processo Legislativo (coração)
| Feature | Tela | Arquétipo | Cob. | Prio. |
|---|---|---|---|---|
| 3.1 Protocolo de proposições · 3.16 espécies próprias · 3.18 numeração | **Protocolo / nova proposição** (`telas/protocolo.html`) | wizard | ✅ | — |
| 3.11 Copiloto de redação (IA) · 3.2 emendas · 3.4 versionamento · 3.17 coautoria | **Editor de proposição + copiloto** | balcão+IA | ⬜ | ALTA |
| 3.3 Tramitação configurável · 3.7 distribuição a comissões | **Tramitação (board)** = 11.3 | cockpit/board | ⬜ | ALTA |
| 3.5 Pareceres de comissão | **Parecer** | balcão | ⬜ | MÉDIA |
| 3.13 Autógrafo · 3.14 sanção/veto · 3.15 promulgação/publicação · 3.20 | **Pós-aprovação (sanção→lei)** | ficha/tracking | ⬜ | MÉDIA |
| 3.22 Expediente — geração de docs · 3.23 protocolo geral | **Expediente** | balcão | ✅ | — |
| 3.6/3.8/3.9/3.10/3.12/3.19/3.21 | embutidos (ficha, busca, assinatura, anexos) | ⤵ | — | — |

### 16.4 — Sessões Plenárias
| Feature | Tela | Arquétipo | Cob. | Prio. |
|---|---|---|---|---|
| 4.17 Mesa de condução ao vivo · 4.4 quórum · 4.6 tribuna · 4.16 incidentes | **Sessão ao vivo** | cabine | ✅ | — |
| 4.1 Pauta · 4.14 tipos de sessão | **Montagem de pauta** | balcão | ⬜ | ALTA |
| 4.15 Convocação + edital + ciência | **Convocação de sessão** | wizard | ⬜ | ALTA |
| 4.12 Transcrição · 4.13 Ata-IA · 8.5 revisão humana | **Revisão de ata-IA** | balcão+IA | ⬜ | ALTA |
| 4.2 Painel eletrônico (telão) · 4.3 votação | **Telão de votação** | display | ⬜ | MÉDIA |
| 4.21 Livro de atas · 4.11 anexar ata | **Livro de atas** | lista+ficha | ⬜ | MÉDIA |
| 4.18 Audiência pública · 4.19 Julgamento de contas | variantes de pauta/votação | ⤵ | ⬜ | BAIXA |
| 4.5/4.7-4.10/4.20 | presença, captação, caption | 🔩/⤵ | — | — |

### 16.5 — Transparência e Portal Público
| Feature | Tela | Arquétipo | Cob. | Prio. |
|---|---|---|---|---|
| 5.1 Portal white-label (casco) · 5.4 resumo IA · 5.6 timeline | **Portal — home** | pública | ✅ | — |
| 5.2 Publicação · acompanhamento → **lista pública + ficha pública + página do vereador + agenda** | **Portal — navegação** | pública/lista/ficha | ⬜ | MÉDIA |
| 5.8 Legislação consolidada | **Legislação consolidada** | pública/lista | ⬜ | MÉDIA |
| 5.9 Transparência fiscal · 5.11 dados abertos | **Transparência / dados abertos** | pública | ⬜ | BAIXA |
| 5.10 Portal do titular LGPD | (no portal) | pública | ✅ | — |
| 5.3/5.5/5.7/5.12 | a11y, notificação, artefato, DOM | ⤵/🔩 | — | — |

### 16.6 — Participação Cidadã
| Feature | Tela | Arquétipo | Cob. | Prio. |
|---|---|---|---|---|
| 6.1 e-SIC amplo + prazo LAI | (no portal) | pública | ✅ | — |
| 6.2 Ouvidoria 13.460 + Carta de Serviços | **Ouvidoria** | pública+form | ⬜ | MÉDIA |
| 6.3 Comentários públicos | embutido na ficha pública | ⤵ | — | — |

### 16.7 — Experiência para Vereador (Aposta 2)
| Feature | Tela | Arquétipo | Cob. | Prio. |
|---|---|---|---|---|
| 7.2 Dashboard pessoal · 7.5 estatísticas · 7.3 assinatura 2 toques | **Dashboard do vereador (PWA)** | cockpit (mobile) | ⬜ | ALTA |
| 7.1 PWA shell · 7.4 push | casca + notificação | ⤵ | — | — |

### 16.8 — Camada de Confiança (IA)
| Feature | Tela | Arquétipo | Cob. | Prio. |
|---|---|---|---|---|
| 8.1-8.5 citação/incerteza/log/reportar/revisão | **padrão transversal** (camada de IA) | ⤵ | ✅* | — |
| 8.6 Observabilidade do modelo | console do operador | 🔩 | ⬜ | BAIXA |

*o padrão de confiança da IA está provado no Portal e documentado na galeria; reusa-se onde houver IA.

### 16.9 — Migração
| Feature | Tela | Arquétipo | Cob. | Prio. |
|---|---|---|---|---|
| 9.6 Portabilidade / saída (dump do ente) | **Exportar dados do ente** | config | ⬜ | BAIXA |
| 9.1-9.5 | ingestão/conectores | 🔩 | — | — |

### 16.10 — Operação, SLA e Compliance
| Feature | Tela | Arquétipo | Cob. | Prio. |
|---|---|---|---|---|
| 10.4 Motor de compliance (status) · 10.5 remessa TCE | (na saúde institucional dos Painéis) | ⤵ cockpit | ✅* | — |
| 10.3 Status page pública | **Status page** | pública simples | ⬜ | BAIXA |
| 10.1/10.2 | SLA/plantão | 🔩 | — | — |

*o status do TCE-CE é o herói dos Painéis; a *geração* do arquivo de remessa segue `[GAP]` de layout.

### 16.11 — Painéis, Pendências e Notificações (read-model)
| Feature | Tela | Arquétipo | Cob. | Prio. |
|---|---|---|---|---|
| 11.4 Dashboard institucional · 11.1 prazos | **Painéis da Mesa** | cockpit | ✅ | — |
| 11.2 Caixa de pendências "minhas tarefas hoje" | **Minhas pendências** | cockpit/lista | ⬜ | ALTA |
| 11.3 Painel de tramitação (board) | = Tramitação (3.3) | cockpit/board | ⬜ | ALTA |
| 11.8 Espelho / ficha da matéria | **Ficha da matéria** | ficha | ⬜ | ALTA |
| 11.6 Central de notificações | **Central de notificações** | lista/inbox | ⬜ | MÉDIA |
| 11.5 Busca global · 11.7 exportação PDF/CSV | embutidos (busca + componente) | ⤵ | — | — |

### 16.12 — Console do Operador SaaS (supratenant)
| Feature | Tela | Arquétipo | Cob. | Prio. |
|---|---|---|---|---|
| 12.1-12.8 registry/billing/flags/grant/auditoria | **Console do operador** (várias) | cockpit+lista+config | ⬜ | BAIXA |

> Operador-facing, fora da fronteira tenant-facing — não decide compra; design diferido.

---

## 2. Roadmap de telas a desenhar (priorizado)

**✅ Prontas (4 — as que decidem a compra, 3 públicos cobertos):**
Sessão ao vivo · Expediente · Portal do Cidadão (home+e-SIC+LGPD) · Painéis da Mesa.

**🟥 ALTA — fecham os fluxos centrais, as 3 apostas e a POC do servidor — ✅ TODAS FEITAS (8):**
1. ✅ **Lista/tabela filtrável** (`telas/proposicoes.html`): proposições 3.x/11.5/11.7 — filtros, ordenação, seleção em massa, paginação, vazio, azulejo-mini por linha. *Destrava dezenas de telas de gestão.*
2. ✅ **Editor de proposição + copiloto IA** (`telas/editor-proposicao.html`, 3.11/3.1/3.2/3.4/3.16/3.17) — coração + **Aposta 1**. Ilha-papel + copiloto ancorado ao artigo; técnica legislativa (LC 95/1998); camada de confiança.
3. ✅ **Ficha da matéria / espelho** (`telas/ficha-materia.html`, 11.8/3.4/3.6/3.19) — azulejo herói (tramitação completa), abas ARIA, linha do tempo, LexML/URN.
4. ✅ **Tramitação — board** (`telas/tramitacao-board.html`, 3.3/3.7/11.3) — read-model de status (não kanban editável: tramitação avança por ato), azulejo por coluna.
5. ✅ **Minhas pendências** (`telas/minhas-pendencias.html`, 11.2) — fila de ação por urgência, anel de prazo no item do TCE, conecta as telas-irmãs.
6. ✅ **Montagem de pauta + Convocação** (`telas/pauta-convocacao.html`, 4.1/4.14/4.15) — pauta reordenável (editorial) + convocação como ilha-papel (antecedência `[Regimento]`).
7. ✅ **Revisão de ata-IA** (`telas/ata-revisao.html`, 4.13/4.12/8.5) — **Aposta 1**; trechos de baixa confiança ancorados ao áudio; placar oficial > transcrição.
8. ✅ **Dashboard do vereador (PWA)** (`telas/vereador-app.html`, 7.2/7.5/7.3) — **Aposta 2**; mobile-first, herói = votação ao vivo, bottom tab bar.

**🟧 MÉDIA — paridade institucional + administração (≈11):**
Login/MFA (1.1) · Admin do ente — usuários (1.8) · Config do ente/branding (1.9) · Auditoria (1.6) ·
Cadastro de vereadores (2.1) · Comissões (2.3) · Mesa Diretora (2.2) · Calendário institucional (2.6) ·
Pareceres (3.5) · Pós-aprovação sanção→lei (3.13-3.15) · Livro de atas (4.21) · Telão de votação (4.2) ·
Central de notificações (11.6) · Ouvidoria (6.2) · Portal — navegação/lista/ficha pública (5.2) ·
Legislação consolidada (5.8).

**🟩 BAIXA — diferível / nicho / operador (≈8):**
Audiência pública (4.18) · Julgamento de contas (4.19) · Transparência fiscal (5.9) · Dados abertos (5.11) ·
Status page (10.3) · Exportar dados do ente (9.6) · Console do operador (12.x) · cadastros menores (2.4/2.5).

---

## 3. Cobertura — resumo

- **~30 telas distintas** cobrem as 113 features (o resto é backend/embutido/transversal).
- **Os 10 arquétipos estão PROVADOS** ✅ (cabine · balcão · pública · cockpit · lista · ficha · wizard ·
  config · **display** · **calendário**). Toda tela do catálogo tem forma de referência.
- **✅ ALTA = 100% (8/8)** · **✅ MÉDIA = 100% (13/13)** · **✅ BAIXA = 100% (8/8)** + **✅ 12 telas de fechamento de gaps** — **47 telas.**
- ✅ **Auditoria de completude (22/06): os 13 GAPS foram FECHADOS** (12 telas novas). Coberturas fracas remanescentes = backlog de profundidade dentro de telas já cobertas (não ausências). Ver `§4`.
- **Telas feitas (27):** Sessão ao vivo · Expediente · Portal do Cidadão · Painéis da Mesa ·
  Proposições · Ficha da matéria · Nova proposição · Config da Câmara · Editor+copiloto · Tramitação board ·
  Minhas pendências · Pauta+convocação · Revisão de ata-IA · App do vereador (PWA) · Login/MFA ·
  Central de notificações · Cadastro de vereadores · Livro de atas · Ouvidoria · Telão de votação ·
  Calendário institucional · **Admin usuários · Comissões · Parecer · Pós-aprovação · Portal-matérias ·
  Legislação consolidada**. Os 3 públicos cobertos; as 3 apostas materializadas.
- **Eixo 4 (guidelines-checklist)** fechado: [`GUIDELINES-CHECKLIST.md`](./GUIDELINES-CHECKLIST.md) = gate de revisão.
- **✅ BAIXA feita (8):** Audiência pública (`audiencia-publica.html`) · Julgamento de contas
  (`julgamento-contas.html`) · Transparência fiscal (`transparencia-fiscal.html`) · Dados abertos
  (`dados-abertos.html`) · Status page (`status.html`) · Exportar dados do ente (`exportar-dados.html`) ·
  Console do operador SaaS (`console-operador.html`) · Cadastros menores (`cadastros-menores.html`).
- **✅ 12 telas de fechamento de gaps (22/06):** trilha de auditoria · ficha pública da matéria · perfil
  público do vereador · moderação de comentários · autoria+apoiamento · assinatura 2-toques · estatísticas
  do vereador · anexar ata externa · legendas ao vivo (LBI) · entrar com gov.br · câmara no console
  (flags+grant+auditoria) · observabilidade da IA. **Com isso os 13 GAPS da auditoria fecham** — a camada
  pública navegável, o lado do vereador (Aposta 2) e a confiança operacional (auditoria, IA-ops) materializados.
  O que resta é **profundidade** (§4), não ausência: o design do catálogo está cobrindo as 113 features.

---

## 4. Gaps da auditoria (22/06) — ✅ TODOS OS 13 FECHADOS (rodada de 22/06)

Auditoria independente cruzou as 113 features × as 35 telas (abrindo o HTML) e achou **13 GAPS**
(features sem tela e sem embutimento) + ~13 coberturas fracas. **Os 13 GAPS foram fechados** com 12 telas
novas (catálogo 35 → **47 telas**), cada uma rodando o `GUIDELINES-CHECKLIST` + AA medida nos 2 temas.

**✅ GAPS tenant-facing (9) — fechados:**
1. **1.6 Trilha de auditoria** → `trilha-auditoria.html` (lista; selo encadeado append-only, Invariante 10).
2. **Ficha PÚBLICA da matéria** → `ficha-materia-publica.html` (pública/ficha; faixa de azulejo + resumo IA + comentários); `portal-materias` reapontada.
3. **Perfil público do vereador + agenda** → `perfil-vereador-publico.html` (pública; institucional, não gabinete).
4. **6.3 Comentários + moderação** → UI pública na ficha + `moderacao-comentarios.html` (fila de moderação).
5. **3.17 Coautoria/apoiamento** → `autoria-apoiamento.html` (passo do wizard; coautores + apoiamento mínimo).
6. **7.3 Assinatura em 2 toques** → `assinatura-2-toques.html` (app do vereador; ilha-papel + folha biométrica).
7. **7.5 Estatísticas do vereador** → `vereador-estatisticas.html` (app; charts honestos).
8. **4.11 Anexar ata externa** → `anexar-ata-externa.html` (upload validado + ICP + imutável + proveniência).
9. **4.20 Closed-caption** → `legendas-ao-vivo.html` (LBI) · **1.2 SSO gov.br** → `entrar-govbr.html` (cidadão).

**✅ GAPS operador-facing (4) — fechados:** 12.3 flags + 12.5 auditoria do operador + 12.7 grant LGPD →
`console-operador-tenant.html`; 8.6 observabilidade do modelo de IA → `observabilidade-ia.html`.

**Fix de chassi nesta rodada:** `.btn-encerrar` usava branco s/ `--telha` = 4.0 (falha AA latente) →
`--telha-fundo` = 5.44 (§5.1), melhora `sessao-ao-vivo` + `moderacao`.

**Coberturas fracas remanescentes (depth, NÃO gaps — a feature já tem cobertura):** vários foram **reforçados**
por estas telas — 5.5 acompanhar (agora na ficha pública), 3.10 ICP (assinatura-2-toques + anexar-ata),
4.3 voto secreto (nota honesta no perfil), 12.6 observ. cross-tenant (observabilidade-ia). Seguem como
**backlog de profundidade dentro de telas já cobertas** (incrementos, não ausências): 2.2 Mesa Diretora ·
3.4 versionamento (diff navegável) · 3.6 apensação (UI) · 3.16 espécies (Decreto Leg./Resolução/Emenda à LOM
distintas do PL no editor) · 3.20 registro de publicação · 4.5 presença append-only · 6.2 Carta de Serviços
(13.460, página pública) · 12.2 billing (detalhe de faturas no console). Nenhum "passou batido" — são
aprofundamentos de telas existentes, candidatos a uma próxima rodada de polimento se um cliente pedir.
