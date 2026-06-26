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

> **Correção (§5, 26/06):** a **6.2 Carta de Serviços** foi mis-arquivada aqui — é **ausência real**
> (link MORTO em `ouvidoria.html`/`portal-cidadao.html`, tela publicada), não profundidade diferível.
> Promovida a tela-nova no §5. As demais (2.2/3.4/3.6/3.16/3.20/4.5/12.2) seguem profundidade demand-pulled.

---

## 5. Delta de design pós-fundações (reconciliação 26/06)

> O catálogo de 47 telas (§1–§4) foi congelado em **22/06**, ancorado em `produto/13`+`14` (113 features).
> Desde então decidiu-se muito: as auditorias **`produto/16`** (rodada 2), **`produto/17`+`18`** (classes de
> gate) e a **`produto/19`** (camada de atenção, ~67 features), mais as **5 fundações** (doc-mestre v1.40–v1.44).
> Esta §5 reconcilia esse delta contra o catálogo. Workflow de 10 agentes (6 leitores + síntese + 3 lentes
> adversariais), correções da crítica dobradas, **enquadramento confirmado pelo Emilio (26/06)**.

### 5.0 A virada de enquadramento

**"O que há de novo" NÃO é um catálogo de telas novas.** O grosso do delta é **retrofit de telas que já
existem** + **1 receita transversal**. Números honestos (pós-crítica):

- **0 telas novas** para a camada de atenção — é re-articulação das homes existentes.
- **~16 telas existentes mudam de premissa** — o esforço REAL (cada uma = redesign + AA 2 temas + commit).
- **1 tela nova no núcleo confirmado:** Grant de suporte lado-ente.
- **~8 "telas novas" + profundidades = backlog pré-existente, demand-pulled** pela régua §15 — não é "o novo".

**Disciplina de origem:** só `produto/19` (camada de atenção) está **confirmado** pelo Emilio. As auditorias
`produto/16-18` são **auditorias de completude** — backlog candidato, gatilho = cliente validado (régua §15),
não escopo herdado. A §5 não reabre auditoria; traduz o confirmado + o candidato em ação de design.

### 5.1 A camada de atenção — AMBOS, mas ZERO tela nova

É (1) uma **receita de chassi** — o **cartão de sinal** `{ator · gravidade⟂prazo · UMA ação · deep-link}`,
hoje copiado inline em 3-4 formas (`minhas-pendencias` `.item`, `vereador-app` `.card`, fila de `paineis-mesa`)
→ vira componente; e (2) a **re-articulação das 4 homes que já existem**. **NÃO substitui** `minhas-pendencias`
nem `notificacoes`: `minhas-pendencias` vira a **home do servidor** (cabeçalho situational acima da fila);
`notificacoes` fica **distinta** como inbox/insumo.

**Regra de fronteira (cravada pela crítica) — o mesmo sinal não pode aparecer em 3 lugares para a mesma pessoa:**
- O sinal **ACIONÁVEL** vive só na **fila/home**; esvazia por **evento de conclusão** (parecer dado, remessa
  aceita), **não** por botão "feito" — microcopy ensina ("sai sozinho quando o parecer for registrado").
- O **inbox** (`notificacoes`) mostra só a classe **informativa/insumo**; única ação = **marcar lido**. Nunca
  um cartão acionável que "completa".
- O **sino** conta a **fila**, não fila+inbox. O dedup do motor é cross-PESSOA (sem duplo-push entre
  servidor/jurídico/Mesa); a regra acima é o dedup cross-SUPERFÍCIE da mesma pessoa.
- **Contrato de altitude** entre as duas homes-cockpit: `minhas-pendencias` = saúde do MEU trabalho (individual,
  acionável); `paineis-mesa` = vitrine institucional + prova de risco (leitura, política). Cabeçalhos situational
  devem diferir em conteúdo e altitude — não duas peles do mesmo grid.

### 5.2 Núcleo confirmado do 1º push (Fase A + B + C)

**Fase A — promover ao chassi (`sistema/chassi.css` + `componentes.html`), ANTES das homes — ✅ FEITA (26/06):**
- ✅ **Cartão de sinal** (`.sinal`) — UM componente parametrizado por gravidade (informativo ↔ **crítico/FALHA**,
  telha só na variante crítica). Absorve as 3 formas inline (`minhas-pendencias .item` · `vereador-app .card` ·
  `paineis-mesa .fila-item`). Fronteira §5.2 documentada na galeria: o sinal acionável esvazia por evento, não
  por botão "feito". Commit `98223b8`.
- ✅ **Receitas ≥2 usos** promovidas: **azulejo** (faixa + stepper de tramitação), **ilha-palco**, **ilha-papel**,
  **chips** (família unificada + `.chip-cheio`), **selo encadeado**, **passos do wizard**, **botão gov.br**.
  Commits `5b35750` (azulejo+ilhas) · `9dea3ad` (selo+wizard+gov.br). AA medida nos 2 temas com flush em cada leva.
- **Não promovidas** (2ª leva, deixadas como receita): camada de confiança IA, campos, `.card`, anel de prazo,
  nota [GAP]. E **respeitado `PADROES §1`**: o *estado degradado de IA* e a *FALHA de sistema* **não** foram
  pré-fabricados — a variante crítica do `.sinal` é o tier de "ação requerida", e essas aplicações se constroem
  por-superfície no 1º uso real (Fase B).
- **Achado AA registrado:** `.ilha-papel .merge` no escuro = 3.72 (token `--merge` herdado; sublinhado 2px é o
  sinal gráfico) — candidato a um passe futuro de token (`GUIDELINES §5.1`), não re-tonalizado num commit de promoção.

**Fase B — retrofit barato (0 tela nova, maior retorno por hora) — EM CURSO (26/06):**
- ✅ **R-IA-1 em `editor-proposicao` FEITO (26/06)** — 1ª superfície, **cravou o padrão transversal**: banner honesto
  reusa o **`.sinal` do chassi** (gravidade=**atenção**, não crítico — IA-off é recuperável, não FALHA do trabalho)
  + **contrato visível** ("o que segue funcionando": redige/salva/**protocola** normal; técnica legislativa LC 95/1998
  volta ao reconectar; nada se perde). Fallback **por-superfície**: rail desabilita, pinos da margem pausam,
  "Pedir ao copiloto" inerte — **"Enviar para protocolo" permanece ATIVO** (o contrato). Estados Ativo↔Indisponível
  alternáveis por **tira de demonstração** (scaffolding marcado, preserva o herói da Aposta 1). AA medida nos 2 temas
  (banner 15.51/12.62 · borda-atenção 4.92/9.23 · contrato 5.12–15.51 · chip 7.54/5.90). **Achado §5.1:** pílula
  selecionada cheia com `--marca`+branco falha no escuro (2.35) → invertida p/ `--texto/--surface` (`GUIDELINES §5.1`).
- ✅ **R-IA-1 em `ata-revisao` FEITO (26/06)** — 2ª superfície. Fallback **distinto** (a IA redige a ata inteira → IA-off
  = sem rascunho): cai para **ata manual** (painel de ata-ausente em `--surface` temático, não a ilha-papel creme, p/
  os botões passarem AA; "Redigir manualmente" / "Anexar ata externa") + banner `.sinal` + contrato (gravação preservada,
  placar oficial é a fonte, votações/presença não dependem da IA). **Honestidade periférica:** chips "Rascunho de IA"/"3
  pontos" somem e o comando "Aprovar" desabilita no IA-off (não há ata). AA 2 temas (banner 15.51/12.62 · borda 4.92/9.23).
- ✅ **R-IA-1 em `portal-cidadao` FEITO (26/06)** — 3ª superfície, pública/white-label. Fallback **mínimo e honesto**:
  só o **resumo em linguagem simples** é IA → no IA-off ele vira **nota âmbar** ("resumo indisponível; volta ao
  reconectar"), e **todo o factual permanece** — a faixa de azulejo da tramitação, ref/autoria/"Em votação", permalink
  URN e **"Ler o texto completo"** (a autoridade). O link "Achou um erro no resumo?" some. Reforça o contrato: a IA é
  assistiva, o ato oficial prevalece. AA 2 temas (nota 15.51/12.62 · borda âmbar 3.63/6.95 ✓≥3 · "ler texto" 9.02/5.72).
- ✅ **R-IA-1 em `legendas-ao-vivo` FEITO (26/06)** — 4ª superfície, **a de maior gravidade (piso LEGAL LBI 13.146)**.
  Fallback **crítico (telha), NUNCA silencioso**: a própria faixa de legenda **fala** ("Legendas automáticas
  interrompidas — acionamos a legendagem humana; a transmissão continua"), o switch apaga + rótulo vira "Legendas
  interrompidas", e um **banner crítico `.sinal-critico`** traz a nota LBI (incidente, não indisponibilidade comum) +
  contrato (legendagem humana acionada, a ata é o registro oficial) + **recurso "Relatar perda de acessibilidade"**.
  Distinção provada: gravidade **crítica** aqui vs **atenção** nas outras 3. AA 2 temas (banda 19.4/12.99 · banner
  15.51/12.62 · borda telha 4.46 ✓≥3 · rótulo 13.94 — corrigido de 3.28: cor não é sinal único, `--texto` + switch + banner).
- ✅ **R-IA-1 na busca FEITO (26/06)** — 5ª e última superfície, em `proposicoes` **e** `legislacao` (mesma superfície,
  2 telas-lista, 1 commit). Fallback **distinto e o mais barato**: o estado **Ativo** ganha um realce **"✦ Busca
  inteligente"** (chip cobalto = ranqueamento por relevância + linguagem natural, Aposta 1); o IA-off **degrada para
  correspondência textual literal** + **aviso âmbar** ("Busca inteligente indisponível — mostrando correspondências
  exatas por número/texto/filtros; **todo o acervo continua pesquisável**"). Honra o contrato (nada some — número e
  filtros são a **autoridade exata**, a IA só ranqueia) e a fonte-é-autoridade. Alternável por **tira de demonstração**
  (scaffolding marcado). AA 2 temas (aviso-texto 6.67–7.05 · corpo 14.72–15.55 · borda âmbar 3.42–9.23 ✓≥3 · realce-IA
  5.34/4.93 — cobalto clareado no escuro p/ ≥4.5 · botão-demo 15.51/12.62). **Com isso o eixo R-IA-1 fecha (5/5).**
- **Próximo grande bloco da Fase B:** camada de atenção nas 4 homes (`minhas-pendencias`
  → home do servidor · `notificacoes` classe FALHA + dedup · `vereador-app` 2º estado · `paineis-mesa` pele FALHA condicional).
  `portal-cidadao`, busca (`proposicoes`/`legislacao`), `legendas-ao-vivo`. **Não é um componente único:** só o
  **banner honesto** + o **contrato "IA-off = o ato legislativo fecha sem ela"** é transversal; o **fallback é
  por-superfície** (editor desabilita o rail + "siga redigindo"; ata cai para anexação manual; portal esconde o
  resumo com nota; busca cai para textual não-IA com aviso). **`legendas-ao-vivo` tem piso LEGAL (LBI Lei
  13.146)** — fallback explícito não-silencioso (handoff a estenógrafo / alerta de perda de acessibilidade),
  nunca "indisponível" mudo. **R-IA-1 NÃO depende do failover (#4)** — consome eventos de falha já existentes
  (`TranscricaoFalhou`/`ResumoFalhou`); entra independente da fundação fast-follow.
- **Camada de atenção nas 4 homes** (em curso):
  - ✅ **`minhas-pendencias` → home do servidor FEITO (26/06)** — 1ª das 4. Cabeçalho **situational** na altitude
    "o MEU trabalho" (lede: "Dois atos vencem hoje e a janela do TCE fecha em 5 dias. Nada está atrasado." — distinto
    da altitude institucional de `paineis-mesa`, honra o contrato de altitude §5.1) + **priorização 2D**: a **borda
    do item = gravidade** (legal âmbar ⟂ regimental telha ⟂ administrativa jade) e a **seção = prazo** (vence hoje →
    prazo legal → esta semana → no seu ritmo), com gchip de gravidade redundante ao lado. A seção "Sem prazo" **deixa
    de ser depósito** → vira **"No seu ritmo"** com razão explícita ("rotina sem prazo legal/regimental, depende da sua
    iniciativa"). **Esvazia por EVENTO** cravado em microcopy global ("cada item sai sozinho quando o ato é concluído;
    não há botão 'feito'") — regra §5.1. **Tipos novos** materializados: adiado **C31** ("REQ voltou para a pauta"),
    **expediente recebido** ("distribuir documentos"), e seção **"Para ciência"** (acusar ciência em 1 toque, "não
    conclui o ato"). AA 2 temas (lede 14.41/15.51 · sub 7.11/7.83 · gchips 5.12–8.50 · bordas-gravidade legal 3.42/9.23,
    reg 3.77/4.46, **adm 8.50/7.59** — jade sólido, corrigido de 1.40 a 55%-alpha · res-ciência 5.41/5.71).
  - ✅ **`notificacoes` FEITO (26/06)** — 2ª das 4. **Classe FALHA distinta** (telha: acento + tag + ícone, vs o
    não-lido jade) para falhas de sistema — transcrição interrompida (consumidor R-IA-1) e remessa rejeitada pelo TCE;
    **informa a falha, a recuperação vive na home/editor** (deep-link "Resolver na ata"/"Corrigir a remessa"), nunca
    completa o ato aqui. **Dedup §5.1 cravado** em nota de cabeçalho ("aqui você acompanha; o acionável fica em Minhas
    pendências e some de lá sozinho; 'lido' mora só aqui; o sino conta a sua fila, não esta lista") + filtro "Falhas".
    AA 2 temas (dedup 7.54/5.90 · falha-tag 4.76/5.33 · acento-telha 3.50/3.71 ✓≥3 · h3 14.42/12.00 · deep-link 7.91/6.32).
  - ✅ **`vereador-app` FEITO (26/06)** — 3ª das 4 (Aposta 2). **2º estado fora-de-sessão** (toggle de demo Em
    sessão↔Fora): o herói ao-vivo (palco escuro, votação) dá lugar a um **herói calmo jade** ("Sem sessão agora /
    Tudo em dia, Helena" + próxima sessão), sem inventar urgência. **Chip de ciclo do voto** no herói ao-vivo
    (pendente→**enviado**→confirmado, com nota "entra no placar oficial — é ele que confirma; pode alterar enquanto
    aberta" = honra a fonte-é-autoridade do Invariante). **Ciência em 1 toque** (cartão cobalto → "Dar ciência" →
    **"Ciente · registrado hoje, 09:14"** = a prova com data/hora). AA 2 temas (voto-tracker 6.69–8.93 no palco ·
    fora-hero estado 4.50/4.87, h2 13.63/10.97, resumo 6.25/5.96 · ciência ci-top 5.82/5.53 — cobalto clareado no
    escuro, h3 14.86/9.19, ciente 8.15/4.84).
  - **Resta (1/4):** `paineis-mesa` → ver abaixo (HERO de venda aprovado — pele FALHA condicional, nunca faixa fixa).
- **`paineis-mesa` (HERO aprovado):** o verde **"A Casa está em dia" continua canônico e o default da demo**; o
  cartão de FALHA é **pele condicional do MESMO placar** (ramo telha já antecipado no SVG `:435`), renderizado só
  quando há falha real — **nunca faixa fixa nova** (não inverter a 1ª impressão de venda). + lente **gated do
  jurídico** (3º decisor): cartões de risco (incidente LGPD, grant ativo) como recorte por papel, não faixa fixa.

**Fase C — a 1 tela nova do núcleo:**
- **Grant de suporte lado-ente** (nova; espelho de `console-operador-tenant.html`; arquétipo cockpit) — o
  admin_ente/DPO aprova/nega/revoga/audita o acesso de suporte com escopo+prazo (reusa selo encadeado). Exposição
  LGPD **viva no go-live**: hoje o grant é renderizado 100% pela ótica do operador; "a câmara autoriza" é só texto.

### 5.3 Mudanças de premissa nas telas existentes (o esforço real)

| Tela | O que muda (núcleo V1) |
|---|---|
| `paineis-mesa` | verde canônico + cartão FALHA condicional (nunca fixo) + lente jurídica gated + engajamento como **número bruto** (sem série/donut) |
| `minhas-pendencias` | vira **home do servidor**: cabeçalho situational + priorização 2D + esvaziar por evento + tipos novos (expediente recebido, adiados C31, ciência) |
| `notificacoes` | **confirmada distinta** (não vira módulo): classe FALHA + dedup; "lido" mora só aqui |
| `vereador-app` | 2º estado fora-de-sessão + ciência 1-toque (prova) + chip de voto (pendente→enviado→confirmado) |
| `editor-proposicao` | IA-degradada + (depois) campo de tema, badge de regime, seletor de espécies |
| `ata-revisao` | IA-degradada (→ anexação manual) + (fast-follow) selo de proveniência de vendor ao signatário |
| `portal-cidadao` | recibo de protocolo no ato + link VIVO p/ Carta de Serviços + agenda pública + IA-degradada no resumo |
| `pauta-convocacao` | roster de ciência (enviado/entregue/**ciente**/bounce) + adiados reaparecendo (C31) + badge de regime |
| `sessao-ao-vivo`/`telao` | **V1: estado de EMPATE + verificação/recontagem (ato nomeado) + presença append-only**; Expediente-ao-vivo e ata-anterior-ao-vivo = fast-follow |
| `console-operador` | handoff operador→1º admin (C03) + semântica de lifecycle (suspensão preserva portal/e-SIC, LAI) |
| `console-operador-tenant` | premissa muda ao exigir a tela-espelho lado-ente (Fase C) |
| `config-ente` | aba Privacidade/LGPD (ROPA) operável pelo DPO |
| `admin-usuarios` | menu três-pontinhos → ações reais (reset-MFA dual, papel com vigência, vínculo) master-detail |
| `tramitacao-board` / `pos-aprovacao` | badge de regime (C40, correção do motor) / encaminhamento externo + status "atendido" (C64) |
| `status.html` | **JÁ feito (v1.44)** — mock de uptime → "plataforma em implantação" |

### 5.4 Backlog demand-pulled (re-entra item-a-item pela régua §15)

Não é "o novo" — é candidato das auditorias, gatilho = cliente validado:
- **Esteira de remessa TCE:** V1 = **estado-rejeitada + ação inline "reenviar"** sobre a superfície existente (o
  cartão de FALHA já carrega o deep-link). **Detalhe + wizard de reenvio + fila cross-competência = diferidos**
  (códigos de erro do TCE são `[GAP]`, zero rejeição real observada, V1 = 1 câmara/cadência mensal).
- **Ciência da convocação:** V1 = roster simples (enviado/entregue) + 1-toque (prova primária, Fund.#3). O
  **quórum-de-ciência derivado** que gateia validade-de-sessão + prazo-mínimo computado = **diferido** (spec da
  prova que a LOM exige é `[GAP]`).
- **Voto offline (PWA):** V1 = só o **chip de estado**. Service-worker + background-sync + reconciliação =
  fast-follow (cenário de voto remoto/móvel não-validado; a votação V1 é no recinto) — e é o **maior risco de
  dimensionamento oculto** (CONC-1, deferido ao chat de stack).
- **Novas telas demand-pulled:** Incidente de dados ANPD (forma da máquina entra quando o jurídico do edital
  pedir; prazo ANPD `[GAP]`) · Carta de Serviços 13.460 (puxar cedo — link morto em tela publicada) · Agenda
  pública (forma **pública/lista**, não grade de calendário) · Expediente recebido/correspondência (cotidiano de
  maior volume) · cockpit-comissão · home-cockpit-admin · progresso/diff de migração operador.
- **Profundidade conhecida (§4):** espécies no editor (3.16) · diff de versões (3.4) · apensação (3.6) · presença
  (4.5) · Mesa Diretora (2.2) · registro DOM (3.20) · billing (12.2).
- **Acoplados (segurar até a dependência aterrar):** onboarding DNS self-serve de e-mail (acoplado ao white-label
  diferido) · selo de proveniência de vendor (acoplado ao failover não-materializado).

### 5.5 `[GAP]` e dependências (não desenhar à frente do conteúdo)

Códigos de erro do TCE (esteira de remessa) · prazo ANPD (incidente) · regra de desempate/voto de qualidade
(empate) · natureza da prova de ciência que a LOM exige · efeito legal de cada estado de lifecycle do tenant ·
esquema URN/LexML por espécie (bloqueia o schema legislativo) · formato de origem SAPL/checksum (migração) ·
conteúdo da Carta de Serviços (por câmara) · turnos/quórum por espécie (`[Regimento]`) · definição de "hora
poupada" (PRD) · lista de vendors de IA elegíveis (art.33). **A FORMA da UI entra; o CONTEÚDO não se inventa.**
