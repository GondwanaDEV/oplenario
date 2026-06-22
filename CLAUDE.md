# CLAUDE.md — O Plenário · Discovery de Arquitetura

> **Nome do produto: O Plenário** — tagline de trabalho *"Onde a câmara acontece."*
> (provisório até confirmar domínio e marca — ver `docs/04-nome-e-marca.md`).

> **Este arquivo é lido automaticamente pelo Claude Code ao abrir a pasta.**
> Ele orienta como continuar o trabalho. Leitura obrigatória antes de qualquer resposta.
> A referência canônica de decisões é o `documento-mestre-camaras.md`. Em qualquer
> conflito entre o que se lembra de um chat antigo e o documento-mestre, **o documento prevalece.**

---

## 1. O que é este projeto

**O Plenário** é uma plataforma **SaaS de gestão pública para câmaras municipais brasileiras**,
tratando a câmara como **instituição** — nunca sistemas de gabinete de vereador (escopo de
gabinete está excluído desde a origem e não se reabre).

- **Beachhead:** Fortaleza / Nordeste (incumbentes do Sul/Sudeste têm baixa penetração — wedge geográfico real).
- **Estratégia (Rota D):** entrar com módulo legislativo como *wedge*, expandir para suite administrativa completa, eventualmente prefeituras. Alvo de IPO/exit R$ 1B+ em 8-12 anos.
- **Fundador/CTO:** Emilio — decisor técnico único, 10+ anos de engenharia. Comunicação direta, intolerante a complexidade desnecessária e framing rebuscado.

**Três apostas de produto da V1:** (1) IA como copiloto legislativo; (2) experiência de
produto moderna para três públicos (servidor, vereador, cidadão); (3) confiança operacional
como diferencial comercial (migração como feature, SLA de janela de sessão, compliance TCE automático).

**Três públicos decisores em licitação**, cada um com porta de entrada própria: servidor
(avalia na POC — ganha com IA e UX), presidente da Mesa (aprova politicamente — ganha com
engajamento cidadão), jurídico/administrativo (avalia risco — ganha com confiança operacional).

---

## 2. Em que fase estamos

**North Star Architecture** — desenho da arquitetura-alvo de 5 anos compatível com a Rota D,
trabalhado **uma seção arquitetural por sessão**, com o **documento-mestre** como artefato
canônico de handoff entre sessões.

**Fechado e consolidado no documento-mestre:** §22.1 (invariantes), §22.2 (alto nível —
tenancy, modelo de serviços, ingestão de legado), §22.3 (contrato core ↔ IA), §22.4 (modelo de
dados legislativo), §22.5 (auth), §22.6 (sessão plenária + áudio + real-time), **§22.7 Eixos A, C, B
e o Eixo de runtime (vocabulário da DSL do motor de compliance + o stress-test que o validou + o
schema das tabelas de template/regra + o comportamento temporal de runtime + a geração de artefatos de remessa (§22.7.8) + **a expansão a outros TCEs (§22.7.9)** — **§22.7 COMPLETO, v1.37**; §22.7.6 averbada na v1.36 = as 5 tabelas do catálogo vivem no schema `motor`)**.
Detalhe do que cada uma decidiu em `docs/00-estado-e-roadmap.md`.

**§22.7 — Motor de regras de compliance** (materialização do Invariante 4) é subseção própria
desde a v1.9, trabalhada **por eixos**. **Eixos A (vocabulário da DSL), C (stress-test com
requisitos reais do TCE-CE), B (schema das tabelas) e o Eixo de runtime (comportamento temporal)
fechados** — o C validou a forma A2 e derivou de carga real o vocabulário (**§22.7.5, v1.10**); o B
cravou o schema estático, separando definição de domínio (sem `ente_id`) de binding por tenant
(**§22.7.6, v1.11**); o de runtime cravou materialização de obrigação (`prazo_dominio_ativo`
polimórfico), avaliação (evento+sweep+sob demanda), monitoramento de prazo (S1) e auditoria
append-only (**§22.7.7, v1.12**). **O avaliador executável da DSL está construído** (`motor-dsl/`,
zero-dep Python, **39 checagens verdes**) e validou *end-to-end* a forma A2 + o loop de runtime — a
primeira implementação de fato (§7). **§22.7.8 (geração de artefatos de envio ao TCE) consolidado
(v1.35)** — forma fechada (spec de layout = descritor declarativo próprio, dec. 2b; `remessa_enviada`
cumpre a obrigação em `aceita`); conteúdo do layout SIM segue `[GAP]`. **§22.7 completo (v1.37):** o último eixo — expansão a
outros TCEs (§22.7.9) — fechou. O granular que resta a reconciliar (mecânica fina do registry, formas descartadas no
Eixo A) segue em §22.7.4.

---

## 3. ⚠️ Estado do cursor + primeira ação

**Estado (v1.39, 21/06/2026):** **Reorganização do doc-mestre FECHADA** — a §22 (densa) foi extraída para `arquitetura/` (7 arquivos por subseção, **verbatim**, cada um com cabeçalho de SSOT; headings promovidos −2); o doc-mestre virou **espinha estratégica** — §1–§21 + §22.1/§22.2/§22.8 inline + índice da §22 (stub = resumo + ponteiro por subseção) + §23–§24, com **§24 = autoridade única de versão**; encolheu ~1.435→~630 linhas. Validada por `ecc:architect` (sem CRÍTICOS: verbatim, âncoras `§22.x` resolvem, sem duplicação/drift). Corte e checklist em `docs/09`. **Antes nesta sessão — Revisão de completude das features (gate pré-design) FECHADA.** Auditoria por 3 lentes
(arquitetura/interop · jurídico-regulatório · paridade/JTBD) achou ~34 gaps que passaram batido — vários **omissões da
própria §16**; **24 entraram na V1**: sessão plenária completa (tipos de sessão, convocação, incidentes processuais, mesa de
condução), nova superfície de **Expediente/documentos** (geração de docs por modelo + protocolo geral), **e-SIC amplo**
(corrige a 6.1 que era juridicamente ilegal), espécies Decreto Leg./Resolução/Emenda à LOM, julgamento de contas (2/3),
audiências LRF, transparência fiscal do órgão (publicação), Carta de Serviços/ouvidoria 13.460, portal do titular LGPD,
URN/LexML, portabilidade/saída, observabilidade do modelo de IA, dados abertos, upload validado. Consolidado em
**§16.13 + changelog v1.38** do doc-mestre; registro minucioso em **`produto/14`**; tabelas de `produto/13` expandidas
(**113 features / 12 módulos**). Também nesta sessão: o **arco A+B+C de governança da porta de IA** (pesquisa de vendor +
filtro B1–B4 + protótipo Clojure `governanca-ia-clj/`, suíte verde, ecc-validado — **ainda não commitado**). Antes:
**§22.7 (motor de compliance) FECHADO por completo** — o último eixo,
expansão a outros TCEs (§22.7.9), foi consolidado. Antes nesta sessão: `motor-dsl-clj/` dobrado em
`backend/src/oplenario/motor/` + §22.7.6 averbada (catálogo no schema `motor`, v1.36).
A trilha de produto/comercial está **completa** (pasta `produto/`). §22.7 tem agora **Eixos A, C, B, o
Eixo de runtime e a geração de artefatos** no documento-mestre. **Decisão central (2b):** a spec de
layout da remessa é **descritor declarativo próprio** (dado, reusa o registry, renderizador próprio) —
não estende a DSL de avaliação nem é código por TCE; honra o Invariante 4. **Costura:** `remessa_enviada`
cumpre a obrigação em **`aceita`** (rejeição não cumpre). Artefato = registro **append-only
`remessa_gerada`** (binário no `objeto_store`). Forma fechada; **layout físico do SIM segue `[GAP]`**. O Eixo de runtime (**§22.7.7**, bump **v1.12** no §24) cravou o
**comportamento temporal** do motor — elevado por S1: o motor _monitora prazo_, não só avalia
booleano. Decisões centrais: obrigação temporal em **dois sabores** (com prazo materializa instância;
contínua não materializa, só avalia); **generalização disparada (disc. 6)** de `proposicao_prazo_ativo`
→ **`prazo_dominio_ativo` polimórfico**; ciclo da obrigação é **enum fixo em código, não template**
(como emendas §22.4 eixo D); `compliance_avaliacao` **append-only** = a **prova de compliance**
(Invariante 10). Duas tabelas novas de runtime (tenant): `prazo_dominio_ativo`, `compliance_avaliacao`.
Modelo de avaliação (evento+sweep+sob demanda) decidido; infra deferida ao chat de stack. Rascunho de
origem em `docs/07-eixo-runtime-motor-rascunho.md`.

**Feito desde a consolidação:** a **primeira implementação de fato** (§7) — o **avaliador executável
da DSL** — está em `motor-dsl/` (parser + type-checker do save time + loop de runtime materializa →
avalia → monitora → audita). Roda em `python3 motor-dsl/test_motor.py` (39 checagens) e
`python3 motor-dsl/demo.py`. Validou *end-to-end*: os 4 templates do Eixo C tipam; T4 (quórum) é
rejeitado pelo envelope (S4); 5 regras mal-tipadas barram no save (dec. 2); aritmética exata (armadilha
do quórum); dois sabores de obrigação; re-stamp S3; auditoria append-only. É protótipo de validação —
**não** decisão de stack (deferida, §22.4.4) — e não inventou conteúdo regulatório (`[GAP]` segue GAP).

**Feito nesta sessão (implementação §7 + averbação §22.7.6):** o **`motor-dsl-clj/` foi dobrado** em
`backend/src/oplenario/motor/` — núcleo DSL realocado **verbatim** (`tipos·nucleo·catalogo·verificador·
runtime·templates`; suíte **12 testes/63 asserções verde** no novo local, clj-kondo limpo) + fachada
`api.clj` (`verificar-fonte` **real**; `avaliar`/resolução db-backed = **seams documentados, não expostos** —
o resolvedor de fatos de produção ainda não está fiado) + persistência `db/` **stub** (deferida §22.4.4) +
**migration `…0006-motor-catalogo`** (schema `motor` + as **5 tabelas estáticas do Eixo B** §22.7.6 + índices
UNIQUE-`COALESCE` p/ a armadilha `NULL≠NULL`). **Validado por ecc** (architect + clojure-reviewer + database-reviewer;
CRÍTICOS de UNIQUE+NULL aplicados). **Averbado no doc-mestre (v1.36):** as 5 tabelas vivem no schema **`motor`**
(não `compliance`) — razão: definição⋈binding têm FK real + resolução conjunta; §22.10 proíbe cross-schema JOIN.
Seed `motor-dsl-clj/` segue como **referência superseded** (não deletado; candidato a remoção). *(Sessão anterior,
`375e4ef`: módulo `compliance` materializado — migration `…0005`, runtime §22.7.7/8.)*

**Também nesta sessão (design — §22.7.9, v1.37):** consolidado o **último eixo de §22.7 — expansão a outros TCEs**
(E1–E4): **E1** resolução `câmara→tribunal` por tabela `jurisdicao_camara` (domínio no `cadastros`) + função de
relação `tribunal_competente` (reconcilia o "UF JOIN" do Eixo B com a §22.10); **E2** camada `tce_estadual`→`tribunal_de_contas`
(aplicado no código do motor — `verificador`/templates/comentários `…0006`; suíte verde); **E3** variação = dado exceto
**3 exceções-de-código bounded por eixo compartilhado, nunca por tribunal** (encoding/protocolo/vocabulário) + playbook de
onboarding (type-check do save = rede de segurança); **E4** forma validada contra 1 tribunal (CE), conteúdo `[GAP]`, rollout
demand-pulled (NE→N/CO→S/SE, gatilho = cliente validado). Rascunho `docs/08`. **Com isso §22.7 fecha por completo.**

**ESCALAR O DESIGN SYSTEM — FASE E (ALTA) + Eixo 4 FECHADOS (mandato Emilio 22/06: fazer sozinho TODA a parte faltante do design, parar só quando não der p/ decidir).** Plano em 5 eixos **TODOS fechados**: **✅0+1** Fundação + Biblioteca viva · **✅3** Inventário · **✅2** 4 arquétipos novos · **✅4** Guidelines-checklist · **✅E (ALTA)** telas reais. **Design system TOTALMENTE ESCAFOLDADO** (8 arquétipos provados) **+ as 8 telas ALTA feitas = 14 telas no total; os 3 públicos decisores cobertos e as 3 apostas de produto materializadas.** Telas Fase E desta rodada: **Editor+copiloto IA** (`editor-proposicao.html`, Aposta 1) · **Tramitação board** (`tramitacao-board.html`, read-model não-kanban) · **Minhas pendências** (`minhas-pendencias.html`) · **Pauta+convocação** (`pauta-convocacao.html`) · **Revisão de ata-IA** (`ata-revisao.html`, Aposta 1) · **App do vereador PWA** (`vereador-app.html`, Aposta 2). **Eixo 4** = `GUIDELINES-CHECKLIST.md` (gate de revisão; §5.1 = 3 armadilhas de contraste medidas). Commits Fase E: `1d744c2`(editor) `8b536c7`(board) `9e99513`(pendências) `7918a7d`(pauta) `744377c`(ata) `a014592`(app) `2c41a28`(Eixo 4 + docs). Antes: `4de6bd5`(0+1) `33d561a`(inventário) `1fa54f4`(lista) `d5a10b7`(ficha) `6fac030`(wizard) `71c330a`(config).

**✅ Eixo 0+1 FECHADO (commit `4de6bd5`).** A "República Luminosa" deixou de viver copiada inline e ganhou **fonte única** em `produto/design-system/o-plenario/sistema/` — `tokens.css` (paleta-marca fixa + semânticos por `[data-tema]` claro/escuro/auto), `chassi.css` (reset·base·`.topo`·`.tema-btn`·`.btn`·`.comando`), `tema.js` (toggle). As **4 telas retrofitadas**: linkam a fundação e tiveram removido TODO CSS que a replicava (tokens+base+componentes) — antes o chassi ficava sombreado pelo inline; agora consomem-no de fato. **`componentes.html`** = styleguide vivo (linka a mesma fundação) + **`PADROES-DE-COMPOSICAO.md`** (arquétipos + registro de receitas, gatilho de promoção 2º-uso→chassi). Reconciliações: `.btn-primaria` canônica (`.btn-primario` alias); portal em `body.superficie-publica` (--max 1200/lh 1.6); tokens AA novos `--aviso-texto`/`--amarelo-traco`. **Revisão adversarial 3-lente incorporada + medida em pixel** (chip-alerta 4.63 · badge sino 5.44 · arco "Atenção" 3.63 · tema-btn 44px · borda de campo ≥3:1). Validado: 5 páginas × 2 temas, a11y estrutural, todos os `var()` resolvem.

**Estado: ✅ ALTA (8/8) + ✅ MÉDIA (13/13) + ✅ Eixo 4 + TODOS OS 10 ARQUÉTIPOS PROVADOS — só a tier BAIXA resta (nicho/operador, diferível).** MÉDIA feitas: Login/MFA (`login.html`, arquétipo **auth**) · Central de notificações (`notificacoes.html`, inbox) · Cadastro de vereadores (`cadastro-vereadores.html`, master-detail) · Livro de atas (`livro-atas.html`) · Ouvidoria 13.460 (`ouvidoria.html`) · Telão de votação (`telao-votacao.html`, arquétipo **display**) · Calendário (`calendario.html`, arquétipo **calendário**) · Admin usuários (`admin-usuarios.html`) · Comissões (`comissoes.html`, cards) · Parecer (`parecer.html`, balcão) · Pós-aprovação (`pos-aprovacao.html`, tracking) · Portal-matérias (`portal-materias.html`, pública) · Legislação (`legislacao.html`, pública). Commits MÉDIA: `1b35ef8`·`aa9c4b7`·`8b43179`·`23ced11`·`f44ab29`·`56c93d2`·`ddf5d6b`·`c822e31`·`2868f99`·`c661a4f`·`38083ab`·`a803187`·`79df7ea`. **27 telas no total.**

**✅ BAIXA FECHADA (8/8) — tier BAIXA = 35 telas** (depois auditado: faltavam 13 gaps, fechados na rodada seguinte → 47). BAIXA feita: Audiência pública (`audiencia-publica.html`) · Julgamento de contas (`julgamento-contas.html`) · Transparência fiscal (`transparencia-fiscal.html`, charts honestos) · Dados abertos (`dados-abertos.html`) · Status page (`status.html`) · Exportar dados do ente (`exportar-dados.html`, sem lock-in) · Console do operador SaaS (`console-operador.html`, supratenant) · Cadastros menores (`cadastros-menores.html`). Commits BAIXA: `d03316a`·`f3ca46c`·`f3f253d`·`84b7724`·`06ee89a`·`bf6e0db`·`9f6a6d3`·`29debee`.

**✅ Auditoria de completude (22/06) — os 13 GAPS foram FECHADOS.** O passe independente achara 13 GAPS + ~13 coberturas fracas (cobertura real ~90%). **Mandato Emilio (22/06): fechar tudo sozinho, sem perguntar o que dá p/ decidir.** Feitas **12 telas novas (35→47)**, cada uma com arquétipo provado + `GUIDELINES-CHECKLIST` + AA medida nos 2 temas + commit individual: **trilha-auditoria** (1.6, selo encadeado) · **ficha-materia-publica** (5.2/5.5/6.3, faixa de azulejo + resumo IA + comentários; `portal-materias` reapontada) · **perfil-vereador-publico** (5.2, institucional) · **moderacao-comentarios** (6.3, fila) · **autoria-apoiamento** (3.17, passo do wizard) · **assinatura-2-toques** (7.3, ilha-papel + folha biométrica, Aposta 2) · **vereador-estatisticas** (7.5, charts honestos) · **anexar-ata-externa** (4.11, upload+ICP+imutável) · **legendas-ao-vivo** (4.20, LBI) · **entrar-govbr** (1.2, cidadão) · **console-operador-tenant** (12.3/12.5/12.7, grant LGPD) · **observabilidade-ia** (8.6, IA-ops). Commits: `e23271f`·`70d91b7`·`b7eced6`·`0ec8290`·`26edf7f`·`6dede68`·`5533aa0`·`8176bfc`·`49836bc`·`99b3529`·`1c768f0`·`879b8af` + docs `98aa1e8`. **Fix de chassi:** `.btn-encerrar` branco-s/-telha 4.0 → `--telha-fundo` 5.44 (`0ec8290`, §5.1). **Coberturas fracas remanescentes = backlog de profundidade DENTRO de telas já cobertas** (não ausências; várias já reforçadas): 2.2 Mesa, 3.4 versionamento/diff, 3.6 apensação, 3.16 espécies no editor, 3.20 publicação, 4.5 presença, 6.2 Carta de Serviços (13.460), 12.2 billing — `INVENTARIO §4`. `governanca-ia-clj/` versionado.

**Primeira ação agora — o design do catálogo está COMPLETO (47 telas, 13 gaps fechados); segue a ENGENHARIA.** Os 3 públicos cobertos, as 3 apostas materializadas, os 10 arquétipos provados, a camada pública navegável e o lado do vereador (Aposta 2) fechados. **Próximo macro-passo (abrir em sessão fresca dedicada):** materializar o **front real** (React/Next self-host, §22.9; gerar tokens/TS a partir de `sistema/` — Malli→TS) consumindo a fundação provada **+** a **materialização do motor** (§22.4.4: persistência real `motor/db/` + tabelas do `cadastros` incl. `jurisdicao_camara` + orquestração de runtime `motor/avaliar` com resolvedor de fatos injetado). **Se voltar a fazer design:** o que resta é **profundidade dentro de telas já cobertas** (`INVENTARIO §4` — backlog: Carta de Serviços 13.460, billing do operador, diff de versionamento, apensação, espécies no editor) — incrementos, não ausências; só puxar se um cliente pedir. **Cada tela (protocolo, se houver mais):** linka `../sistema/`, arquétipo provado, reusa receitas, **`GUIDELINES-CHECKLIST.md` como gate** (esp. §5.1 — branco-sobre-telha→`--telha-fundo`; fill de gráfico escuro no escuro→clarear; âmbar→`--aviso-texto`), AA **medida em pixel composto, um tema por chamada com flush** (a 2ª passada limpa elimina o artefato de stale-bg do `body`), commit (uma tela/commit). **Promover ao chassi as receitas ≥2 usos** (camada de IA, azulejo, ilha-papel, chips, campos, `.card`, faixa de tramitação, selo encadeado, passos do wizard, botão gov.br) — `PADROES §3`. **Fronteira do descarte:** só `produto/design-system/` é design; resto de `produto/` (01–14) intacto. **Servidor:** `python3 -m http.server 8755` na raiz; galeria em `componentes.html`. **Pendências paralelas (não bloqueiam):** materialização do motor (§22.4.4). **Branch: `design-do-zero`** (último commit de DS: `98aa1e8`; **47 telas = catálogo de design COMPLETO** = 4 herói + 8 ALTA + 13 MÉDIA + 8 BAIXA + 12 de fechamento de gaps + Eixo 4; **nada de design resta como ausência**).

**Histórico — DESIGN DO ZERO (decisão Emilio, 21/06/2026):** ✅ **linguagem ESCOLHIDA (Emilio) = "República Luminosa" / Opção B**, agora em **dois modos — claro + escuro** ("a noite de Brasília") — jade `#0C5340` + telha/coral `#D9542B` + azulejo cobalto/Marajó, fontes **Sora · Hanken Grotesk · IBM Plex Mono**, assinatura = **faixa de azulejo da tramitação** (Bulcão); tokens semânticos por `[data-tema]` (respeita `prefers-color-scheme` + `localStorage`), documentados no `LINGUAGEM-VISUAL.md`. v0 **removido** (recuperável: `git checkout 9e48fd0 -- produto/design-system/_v0-descartado/`). **1ª tela-que-decide-a-compra aplicada e aprovada pelo Emilio: `telas/sessao-ao-vivo.html`** — Mesa de condução ao vivo (4.2/4.3/4.4/4.16/4.17): placar nominal em tempo real, hemiciclo de quórum, faixa de azulejo da tramitação, barra de comando da Mesa; dual-theme, validada desktop+mobile, a11y AA+. Canônica em `produto/design-system/o-plenario/linguagem-visual.html` + `LINGUAGEM-VISUAL.md`; as opções A/C/D ficam em `produto/design-system/o-plenario/opcoes/` como registro da exploração. **Fronteira do descarte:** só `produto/design-system/`; o resto de `produto/` (01–14: JTBD, decomposição das 113 features, completude) **NÃO é design — é o input do novo processo e fica intacto.** **Toolchain (§4):** `frontend-design` como **diretor de arte + copy** + `ui-ux-pro-max` para **catálogo/paleta/fonte, UX guidelines e charts**, artefatos autorados à mão em `produto/design-system/`. **Sequência:** (1) ✅ **linguagem visual do zero** (claro + escuro) — FEITO → (2) **telas que decidem a compra**: ✅ sessão ao vivo + mesa de condução (4.17) — FEITO; ✅ **Expediente · gerar documento (`telas/expediente.html`, 3.22/3.23/3.18/3.10)** — FEITO (balcão do servidor/HERO de POC; herói = documento em papel ao vivo c/ merge do cadastro **sublinhado em telha** + **carimbo do Protocolo Geral** + **livro do Protocolo Geral** = numerador único 3.23; nova **ilha de papel** clara nos 2 temas = inversão da ilha-placar escura; dual-theme, desktop+mobile, a11y AA+); ✅ **Portal do Cidadão (`telas/portal-cidadao.html`, 6.1 e-SIC amplo + 5.10 titular LGPD + 5.4/5.6 demo-power)** — FEITO (a **porta da rua white-label**: a Câmara lidera a marca, "O Plenário" recua ao rodapé; sem cadastro p/ consultar; herói = proposição em **tramitação viva** [faixa de azulejo, 5.6] + **resumo em linguagem simples** c/ camada de confiança da IA [rótulo+revisão humana+reportar erro]; dois **balcões de direito** — e-SIC **amplo** [qualquer info pública, **sem seletor de tema** = a correção da 6.1 ilegal; **anel do prazo legal** LAI 20+10 + recurso + azulejo do ciclo do pedido] e **Meus dados/LGPD** [balcão **separado**, Encarregado/DPO público, sem cravar prazo `[GAP]`]; fundamentação + **crítica adversarial multi-lente** [a11y/anti-IA/jurídico/consistência] via workflows, 17 correções incorporadas; dual-theme, desktop+mobile, a11y AA+); ✅ **Painéis da Mesa (`telas/paineis-mesa.html`, módulo 16.11 read-model: 11.4 dashboard institucional + 11.1 "o que vence" + 11.2 despachos + 11.3 pipeline + ponte 4.17)** — FEITO (**a tela do comprador**: Presidente/Mesa lê a vitrine de cima, jurídico/risco a prova embaixo — mesmo read-model, leituras diferentes; herói = **ilha-placar da saúde institucional** ["A Casa está em dia com o TCE-CE": placar de obrigações 11·1·0 + anel da próxima remessa + **3ª leitura do azulejo** = ciclo de obrigação de compliance + selos de continuidade que desarmam o jurídico]; charts honestos — semáforo por linha, trilho de prazos, tabuleiro de estágios, fila de ação, **sem donut/KPI-card**; disciplina **[GAP]** = datas do TCE = "regra em homologação" + nota visível; fundamentação por workflow + **crítica adversarial 4-lente** incorporada [data da audiência LRF corrigida art. 9º §4º, orgulho fora da barra fixa, sparklines→referência honesta, placar unificado, **contraste AA medido em pixel composto** nos 2 temas]; dual-theme, desktop+mobile, a11y AA+). **Com isto as 4 telas-que-decidem-a-compra fecham — os 3 públicos decisores cobertos. Próximo macro-passo:** deixa de ser telas-herói e vira **escalar o design system ao catálogo amplo** (113 features / 12 módulos) — biblioteca de componentes/padrões reusáveis a partir da linguagem provada; por protocolo (um tópico macro/sessão), abrir em **sessão fresca dedicada**. **Pendências paralelas (não bloqueiam):** (a) **commitar o arco de governança da porta de IA** (`governanca-ia-clj/`, ainda untracked); (b) **implementação/materialização do motor** (persistência real `motor/db/` + tabelas do `cadastros` incl. `jurisdicao_camara` + orquestração de runtime — `motor/avaliar` com resolvedor de fatos injetado, §22.5.3 disc.5; deferida ao chat de stack §22.4.4); (c) §22.8 sem itens parqueados (item 1 LLM/soberania resolvido no Eixo 10, v1.27).

- **Trilhas concluídas:** produto/comercial (completa, `produto/`); arquitetura **§22.7 COMPLETO** (Eixos A, C, B,
  runtime §22.7.7, geração de artefatos §22.7.8, **expansão §22.7.9**); **esqueleto `backend/` com `compliance`
  materializado e `motor` dobrado** (catálogo §22.7.6 no schema `motor`).
- **Nenhum eixo de design de arquitetura aberto.** Resta: **implementação/materialização** (persistência real +
  orquestração de runtime + tabelas do `cadastros` incl. `jurisdicao_camara` — deferida ao chat de stack §22.4.4);
  o **layout físico do SIM** + conteúdo regulatório por tribunal seguem `[GAP]`; **§22.8 sem itens parqueados**
  (item 1 LLM/soberania resolvido no Eixo 10, v1.27 — híbrido + porta vendor-agnóstica; vendor concreto = deploy-config/comercial). O granular do registry (§22.7.4) segue a reconciliar.

---

## 4. Como trabalhamos (protocolo)

**Detalhe completo em `docs/01-metodologia.md`.** Resumo operacional:

- **Ferramentas fixas deste projeto (decisão do Emilio, 20/06/2026):** todo trabalho de
  **discovery e engenharia** (pesquisa de mercado, code review, build, etc.) usa o plugin
  **`ecc`** (suas skills/subagents); o **design de UI/UX** usa **dois consultores complementares** (decisão Emilio, 21/06/2026 — substitui o
  arranjo só-UI/UX-Pro-Max): **(a) `frontend-design`** (skill oficial Anthropic do repo `anthropics/skills`, via o
  plugin `example-skills`) como **diretor de arte + copy** — direção visual ousada/não-templated e microcopy
  (erro/empty/voz, crítico p/ e-SIC e LGPD), nas telas que decidem a compra; **(b) `ui-ux-pro-max`** como
  **biblioteca + UX + charts** — catálogo de paleta/fonte, 99 UX guidelines, 25 tipos de chart e consistência de
  sistema através das 113 features / 3 públicos. Os **artefatos seguem autorados à mão em `produto/design-system/`**
  (fonte de verdade). *(O `frontend-design` antigo de `claude-plugins-official` foi desabilitado p/ evitar colisão
  de nome; ambos os consultores ficam habilitados.)* **Usar sempre que houver trabalho dessas naturezas** — não é
  preferência pontual, é o trilho do projeto.
- **Português em toda sessão técnica.**
- **Um tópico macro por sessão**, fechado e consolidado no documento-mestre antes de seguir.
- **Eixo por eixo:** abrir opções por eixo → debater tradeoffs explicitamente → chegar a
  decisão confirmada → consolidar. **Não se relitiga item já fechado.**
- **Confirmação explícita antes de prosseguir:** protocolo "Confirmo" / "Confirma?". Emilio
  intervém com correções cirúrgicas e espera incorporação imediata.
- **Bump de versão vs. patch:** correções dentro de uma sessão podem ser patch de mesma
  versão ou bump, conforme a natureza da mudança.
- **Viés forte por consistência disciplinar:** estender padrões existentes (DSL
  compartilhada, taxonomia de eventos, mecânica de registries) em vez de introduzir conceitos novos.
- **Escopo diferido por default:** itens sem requisito de cliente validado são parqueados,
  não pré-construídos. A régua das 4 perguntas (§15 do documento-mestre) é o filtro permanente.

---

## 5. Invariantes que NÃO podem driftar

Os 10 invariantes da §22.1 são lei estrutural de 5 anos. Para o trabalho de §22.7, dois
pesam mais:

- **Invariante 4 — regras de compliance são dados, não código.** TCE-CE na V1 é
  *configuração*, não branch de código. A DSL precisa permitir expressar todos os requisitos
  do TCE-CE como dado na V1, **sem refactor estrutural** para adicionar outros estados depois.
- **Disciplina 5 de §22.4.3 e §22.5.3 — motor declarativo compartilhado.** A DSL e a mecânica
  de avaliação são as **mesmas** entre tramitação (§22.4 eixo C), autorização (§22.5 eixo B),
  regras de plenário (§22.6 — quórum, regras de votação por matéria, tempos de tribuna) e
  agora compliance. **Não construir DSLs distintas.** Partir sempre do que já está fechado.

Princípio comercial que justifica rigor técnico aqui: **uma regra de compliance falhando em
runtime e fazendo um cliente perder janela de envio ao TCE é incidente inaceitável** — é o
que justifica type-checking estático no momento de salvar a regra (decisão do Eixo A).

---

## 6. Mapa da pasta

| Arquivo | Para quê |
|---|---|
| `documento-mestre-camaras.md` | **Single source of truth.** Decisões consolidadas. Em conflito, prevalece. **A versão vive no cabeçalho + §24, nunca no nome do arquivo** (evita trocar referências a cada bump). |
| `arquitetura/` | **Parte do SSOT** — as subseções densas da §22, extraídas (v1.39), uma por subseção: `22-3-contrato-core-ia` · `22-4-dados-legislativo` · `22-5-auth` · `22-6-sessao-plenaria` · `22-7-motor-compliance` · `22-9-stack` · `22-10-monolito`. Cada arquivo abre com cabeçalho de SSOT; **versão do conjunto governada pelo §24 do doc-mestre**, nunca por arquivo. |
| `docs/00-estado-e-roadmap.md` | **Comece aqui.** Estado do cursor, o descompasso a reconciliar, e o roadmap de §22.7 (eixos + parqueados + pendências). |
| `docs/01-metodologia.md` | Método de trabalho (eixo a eixo, confirmação, versionamento, escopo). |
| `docs/02-eixo-A-fechado-rascunho.md` | Decisões do Eixo A de §22.7 — **consolidadas em §22.7 (v1.9)**; mantido como rascunho de origem. Listas granulares seguem "a transcrever" em §22.7.4. |
| `docs/03-proxima-sessao-eixo-C.md` | Brief + prompt de abertura da próxima sessão: templates TCE-CE como stress-test da DSL. |
| `docs/04-nome-e-marca.md` | Decisão de nome (**O Plenário**), tagline, e os checks pendentes (domínio + INPI). |
| `README.md` | Orientação geral da pasta (visão humana). |

---

## 7. Onde o Claude Code agrega além da conversa de design

Até aqui o trabalho foi 100% conversa de arquitetura. A partir desta pasta, dois movimentos
ficam disponíveis quando os eixos pedirem:

- **No Eixo C (próximo):** ainda é design — o stress-test pega requisitos reais do TCE-CE e
  os expressa na DSL fechada no Eixo A para **descobrir lacunas** na forma da DSL antes de
  cravar schema. O Claude Code pode ajudar a **prototipar um avaliador mínimo da DSL** e
  rodar os templates-exemplo contra ele para validar que a gramática cobre os casos reais.
- **No Eixo B (depois de C):** materialização concreta — DDL real das tabelas de
  template/regra, parser/validador da DSL com type-checking estático, suíte de testes de
  regra. Aqui o Claude Code passa de interlocutor de design para implementação de fato.

Regra de ouro mantida: **não puxar materialização para antes da hora.** A ordem A → C → B é
deliberada justamente para validar a forma antes de gastar com schema.
