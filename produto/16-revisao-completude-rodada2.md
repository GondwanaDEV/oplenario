# 16 · Revisão de completude das features da V1 — Rodada 2 (superfícies admin · analítica · ritual)

> **Trilha de produto/UX — auditoria de profundidade, pós-design.** Segunda passada de completude sobre o
> catálogo de `13-decomposicao-features-v1.md` (113 features / 12 módulos), pedida pelo fundador depois que a
> rodada 1 (`produto/14`, G1–G34) fechou as ausências estruturais e o catálogo de design fechou 47 telas. Onde a
> r1 cavou **ausências de feature**, esta cava **profundidade de superfície** — as três zonas que a r1 admitiu
> serem as mais finas: **administração (do tenant e da plataforma)**, **analítica/storytelling por unidade de
> leitura** e **ritual de sessão + automações**. Filtrada contra `produto/13`, `produto/14`, as 47 telas
> (`design-system/o-plenario/telas/`), `INVENTARIO-TELAS.md` e a §22, e passada pela **régua §15**.
>
> **Convenção:** `[FATO]` (fonte legal/competidor/arquivo confirmado) · `[INF]` (inferência derivada) · `[REC]`
> (recomendação) · 🔎 (validar em campo/com especialista). **Severidade:** 🔴 ALTA · 🟠 MÉDIA · ⚪ BAIXA.
> **Status:** `gap_real` (ausente em catálogo E design) · `cobertura_fraca` (capacidade existe, profundidade
> falta) · `coberto_só_catalogo` (feature catalogada, tela diferida/ausente).
>
> **Limite da moldura (ler antes da §0):** as 8 lentes desta rodada e os 87 C-itens auditam **profundidade de
> feature-de-negócio no caminho feliz** (institutos legislativos, superfícies admin, leitura analítica). Cinco
> **classes de preocupação ortogonais** ficaram sistematicamente fora — caminhos de **falha/recuperação**,
> **migração como produto operado**, **não-funcional/segurança/privacidade-operacional** (`produto/15`),
> **notificação como entrega comprovável** e **concorrência/carga**. Elas estão levantadas, **não auditadas**, na
> **§6** ao final. O veredito de completude da §0 vale **dentro** desta moldura, não para a V1 inteira.

---

## 0. Veredito

O catálogo de 113 features é **maduro** — a r1 já o havia endurecido (sessão completa, Expediente, e-SIC amplo,
LGPD, julgamento de contas) e o design provou 10 arquétipos em 47 telas. Esta rodada **não relitiga** nada disso:
ela mergulha nas superfícies que a r1 declarou serem as mais finas e descobre que a fragilidade ali é, na esmagadora
maioria, **de profundidade, não de ausência**. Dos **87 itens auditados (C01–C87)**: **23 são `gap_real`** (genuinamente
ausentes em catálogo E design), **55 são `cobertura_fraca`** (a capacidade está catalogada e/ou o substrato está
cravado, mas a superfície não exercita o JTBD inteiro), e **9 são `coberto_só_catalogo`** (feature existe, tela
deliberadamente diferida). *(Números = contagem determinística do verificador sobre os 87 vereditos; o corpo agrupa por tema, então a fronteira gap-real/fraca de alguns itens-limite é discutível — cada linha carrega sua disposição §15 + severidade, que é o que decide.)* O padrão importa: o produto **não tem buracos estruturais novos** — tem **bordas de
profundidade** concentradas em (a) o ciclo de vida do tenant operador↔ente, (b) a leitura analítica por unidade
(comissão, relator, presidente, jurídico) e (c) o **rito processual fino** (regime de urgência, turnos, diligência,
prejudicialidade) e as **automações de sessão** que o fundador nomeou. **Doze itens sobem para `entra_v1`** (de 87; mais 49 `fast_follow` e 20 `diferir`) — quase
todos por **risco de fluxo ou risco jurídico em superfície já comprometida** (handoff de go-live, regime de
tramitação, ciência de convocação, recibo de protocolo cidadão, Carta de Serviços, encaminhamento de
indicação/moção, workstream do recebido, classificação temática, agenda pública de sessões). O resto é
**fast-follow V1.5** (incrementos sobre substrato fechado) ou **diferível** sob a régua §15.

> **Ressalva de escopo do veredito.** "Estruturalmente completo, resta cavar profundidade" é defensável **para a
> dimensão feature-de-negócio/caminho feliz** que esta rodada mediu. **Não** é um atestado de prontidão da V1 para
> go-live B2G: as cinco classes ortogonais da §6 (rejeição de remessa ao TCE, IA indisponível em runtime, migração
> operada, NF/segurança/privacidade do `produto/15`, notificação comprovável, concorrência/carga) **não foram
> auditadas** e três delas tocam diretamente as próprias apostas que o produto vende. A V1 **não deve ser declarada
> completa** antes de cruzá-las — ver §6.

---

## 1. Reconciliação dos 4 itens do Daouda Traore

O fundador apontou 4 pendências. Nenhuma é buraco estrutural — cada uma tem cobertura parcial real e uma lacuna de
profundidade precisa. Honestamente, item por item:

### Item 1 — Área administrativa do administrador do tenant (a câmara administrando a si mesma)

**(a) Onde já existe.** O `admin_ente` é capacidade catalogada: **1.8** (gerir usuários/vínculos, resetar MFA,
aprovar recuperação de poder elevado), **1.9** (branding/contatos/flags), **1.10** (resetar_mfa com aprovação dual).
Em design, `admin-usuarios.html` e `config-ente.html` materializam as páginas-folha. `produto/13` é explícito que
`admin_ente` é **"área de UI, não módulo backend"** (flag 7 / Nota GAP 2). **(b) Lacuna real.** Faltam **três
profundidades**: a **home/cockpit agregadora** ("o que precisa da minha atenção" — convites/resets pendentes,
usuários sem MFA, grants de suporte ativos, config incompleta, status de contrato — **C06**); o **detalhe de usuário
com ações renderizadas** (o menu de três-pontinhos de `admin-usuarios.html` não expande reset-MFA dual, atribuição de
papel com vigência, vínculo a mandato — **C07**); e o **lado-ente do grant de suporte** (`console-operador-tenant.html`
renderiza o grant 100% pela ótica do operador — "a câmara autoriza" é só texto; não há a tela onde o admin aprova/nega/revoga/audita
— **C09**, que **entra na V1** por ser obrigação do controlador LGPD). Edição de parâmetros regimentais (**C08**) e curadoria
de tabelas de domínio (**C10**) são `decisao_fundacao` (dependem do especialista de regimento).

### Item 2 — Dashboards com storytelling por comissão + analítica por perfil de usuário

**(a) Onde já existe.** O módulo **16.11** (read-models institucionais: 11.1 "o que vence", 11.2 pendências, 11.3
pipeline, 11.4 dashboard institucional) e **7.5** (estatísticas do vereador) cobrem a **casa** e o **indivíduo**.
`paineis-mesa.html` e `vereador-estatisticas.html` provam o arquétipo cockpit + charts honestos. **(b) Lacuna real.**
Falta a **unidade-de-leitura intermediária** entre a casa e o indivíduo. Especificamente: a **comissão como unidade
de storytelling** (`comissoes.html` é CRUD de cards, não produtividade/relatoria atrasada/matéria parada — **C16**,
`gap_real`); o **engajamento cidadão como narrativa para o presidente** (11.4 prometeu "engajamento cidadão" mas
`paineis-mesa` entrega 4 números institucionais estáticos, nenhum de engajamento — **C19**, **entra na V1** por ser o
KPI da persona que assina a compra); o **cockpit do servidor / horas-poupadas** (KPI da persona que valida a POC, mas
a definição operacional de "tempo economizado" está aberta no próprio PRD — **C20**, `decisao_fundacao`); a **camada
narrativa transversal** (frase-síntese gerada sobre read-models, pedido literal "storytelling, não tabela" — **C22**,
`gap_real`); e a **home por perfil-de-trabalho** (relator/líder/secretário da Mesa — **C21**, que é a soma de
C16/C17/C18 e sai de graça quando elas entram).

### Item 3 — Admin da plataforma (operador): cria os tenants e faz a config inicial mínima → handoff

**(a) Onde já existe.** O épico operador é **12.1–12.8** (módulo 16.12, §22.10): 12.1 nomeia o ciclo
`provisionar→ativo→suspenso→encerrado`; a arquitetura cobre o **efeito** (emitir `ente_id`, schema/RLS, semear o
catálogo do motor via `jurisdicao_camara`, §22.7.9); `console-operador.html` é o cockpit supratenant. **(b) Lacuna
real — e é aqui que o ciclo se quebra.** O fundador descreveu **provisionar → config-mínima → handoff** como uma
cadeia, e é exatamente a cadeia que não está costurada:
- **Provisionar (C01, `cobertura_fraca`):** 12.1 existe e a fundação está cravada, mas `console-operador.html` tem só
  o **botão-stub "Provisionar câmara"** sem wizard (identificar ente → escolher jurisdição TCE → plano/vagas →
  subdomínio → disparar). Diferível (operador-facing, piloto nasce por script).
- **Config-mínima / seed (C02, `decisao_fundacao`):** os ingredientes existem espalhados (legislatura 2.4, Mesa 2.2,
  comissões 2.3, calendário/recesso 2.6, numeração §22.4 eixo H), mas **qual pacote vem de fábrica vs. exige entrada**
  é decisão remetida ao especialista de regimento (§22.4.4). Desenhar a tela sobre conteúdo `[GAP]` é prematuro.
- **Handoff (C03, `gap_real`, ENTRA NA V1):** este é o nó faltante. A auth (§22.5) só descreve **enrollment genérico**
  — não o **ato deliberado** de o operador designar o primeiro `admin_ente`, acompanhar o aceite e recuar o grant de
  provisionamento. É o **"dia zero" que destrava o go-live de cada cliente**. `admin-usuarios.html` tem "Convidar
  usuário" genérico que já pressupõe um admin operando.
- **Lifecycle (C04, `decisao_fundacao`, ALTA):** os 4 estados são dado, mas o **efeito legal** de suspender por
  inadimplência (derrubar portal/e-SIC viola a LAI) não está cravado — `console-operador.html` renderiza
  "Inadimplente" como chip sem ação nem semântica.

> **Nota de moldura (item 3).** A cadeia `provisionar→seed→handoff` aqui auditada é o **caminho feliz** do go-live. O
> **antecedente operacional real** desse go-live — a **migração do acervo legado** (Aposta 3 "migração como feature")
> como workstream operado, com staging, reconciliação e relatório de cobertura assinável — **não foi sondado nesta
> rodada** e está levantado como **R-MIG** na §6. C01–C05 tratam provisionamento/off-board, não a importação de dados.

### Item 4 — Leitura de ata na sessão + automação de coisas repetitivas

**(a) Onde já existe.** A ata é coberta por 4.13 (redação), 4.21 (livro), e `ata-revisao.html` (revisão da ata-IA);
o Eixo B de §22.6 modela "leitura/comunicado" como item de pauta com `texto_descricao`. **(b) Lacuna real — o ritual
ao vivo e as automações.** A **leitura/aprovação da ata anterior como ato conduzido** não existe (é só rótulo estático
em `pauta-convocacao` + fato narrado em `ata-revisao` — **C26**); a **condução do Expediente ao vivo** não roda (o
palco de `sessao-ao-vivo.html` só renderiza a Ordem do Dia — **C27**); e as **automações** que ele citou estão
parcialmente servidas mas incompletas: **auto-montagem de pauta** dos aptos (o rail "Prontas, fora da pauta" é lista
estática — **C30**); **retomada automática do adiado** (`gap_real`, **ENTRA NA V1** — item adiado que não retorna cai
no esquecimento = falha processual — **C31**); **auto-esqueleto de ata** do dado estruturado (a própria tela admite
gerar do áudio o que deveria vir determinístico de 4.5/4.3 — **C32**); **encerramento de sessão** que encadeia
consequências (a barra só "encerra votação" — **C34**); **votação em bloco** com agrupamento+propagação (só botão sem
fluxo — **C35**); e **geração em lote** de documentos (mala-direta 3.22 existe como template, mas não como job de N
ofícios personalizados — **C38**). TTS/leitura automatizada de textos (**C37**, `gap_real`) seria a 6ª capacidade de
IA — diferida.

---

## 2. Gaps reais (status `gap_real`)

Genuinamente ausentes em catálogo **E** design. Agrupados por tema.

### Tema A — Ciclo de vida do tenant (operador↔ente)

| ID | Título | Persona | Por que importa | Âncora | §15 | Sev |
|---|---|---|---|---|---|---|
| **C03** | Handoff operador→admin do ente (1º admin + passagem de bastão) | Operador / admin_ente | "Dia zero" que destrava o go-live de **cada** cliente; enrollment genérico (§22.5) não cobre o ato deliberado de designar/acompanhar/recuar | §22.5 (enrollment) + 12.7 (grant) | **entra_v1** | 🔴 |

> Reusa enrollment §22.5 + grant 12.7, mas precisa ser **ato de 1ª classe nomeado** com estado "handoff concluído"
> auditável dos dois lados. Sem ele, nenhum cliente vai ao ar.

### Tema B — Analítica por unidade de leitura

| ID | Título | Persona | Por que importa | Âncora | §15 | Sev |
|---|---|---|---|---|---|---|
| **C16** | Cockpit/storytelling da comissão | Presidente de comissão | "O que travou na minha CCJ e quem é o relator" não tem casa — 11.4 agrega na casa, `comissoes.html` é CRUD | 16.11 (recorte sobre 11.1/11.3) + 16.3 (3.5/3.7/3.8) | fast_follow | 🟠 |
| **C18** | Painel do líder de bancada/bloco | Líder | Genuinamente ausente, mas líder **não é persona compradora** (ICP crava servidor→presidente→jurídico); flerta com ranking político (§16.6/16.11 deferem V2) | 2.5 (cadastro de bloco) | **diferir** | ⚪ |
| **C22** | Camada narrativa/storytelling sobre read-models | Presidente (não-técnico) | Pedido literal do fundador; reusa porta de IA + §16.8 (confiança) sobre read-models fechados — alucinação institucional se sem rótulo+revisão | §16.8 (camada de confiança) | fast_follow | 🟠 |

### Tema C — Rito processual fino

| ID | Título | Persona | Por que importa | Âncora | §15 | Sev |
|---|---|---|---|---|---|---|
| **C31** | Retomada automática de itens adiados | Servidor / Mesa | Item adiado que não retorna **cai no esquecimento** = matéria perdida, prazo estourado; o dado existe (decisao_mesa/pauta_alteracao), o ciclo de retorno não | 4.16 ↔ 4.1/3.8 | **entra_v1** | 🔴 |
| **C40** | Regime de tramitação (ordinário/urgência/urgentíssima) | Mesa / servidor | **Instituto que reconfigura o rito**, não incidente: urgência dispensa interstício, urgentíssima vota na mesma sessão, sobresta/tranca a pauta. Sem o atributo, o motor (3.8) e a OD (4.1) calculam ERRADO. O catálogo confundiu "votar urgência" (coberto) com "estar em regime" (ausente) | 3.8 + 4.1 | **entra_v1** | 🔴 |
| **C42** | Prejudicialidade (matéria prejudicada) | Servidor / jurídico | Causa terminal distinta de arquivamento/rejeição; sem ela o servidor arquiva com motivo errado, distorcendo a causa que o jurídico e o TCE auditam. É estado na máquina declarativa (Eixo C), não refactor | §22.4 Eixo C/F | fast_follow | 🟠 |
| **C45** | Diligência / baixa em diligência (suspende prazo) | Servidor | Sem ela o motor (3.8) conta dias que não deveria enquanto se aguarda resposta externa — erro de prazo que corrói a Aposta 3. O ciclo `prazo_dominio_ativo` hoje **não tem estado suspenso** | §22.4.3 disc.6 | fast_follow | 🟠 |
| **C46** | Recurso ao plenário contra decisão da Mesa | Vereador | Instituto garantístico real, mas raro; quando ocorre é estruturalmente uma votação (Eixo G já vota objeto polimórfico sobre decisao_mesa) | §22.6 Eixo F + G | **diferir** | ⚪ |
| **C47** | Desarquivamento + arquivamento em massa de fim de legislatura | Servidor | Evento periódico inevitável (toda virada de mandato); sem automação o servidor faz centenas à mão com risco nas exceções. Reusa estado arquivado (3.9) + entidade legislatura (2.4) | 2.4 + 3.9 | fast_follow | 🟠 |
| **C49** | Comissão Geral (plenário em comissão) | Mesa | Uso ocasional; o Eixo A já desacopla capabilities do tipo nominal e a fala de convidado existe — falta nomear o modo | §22.6 Eixo A/F | **diferir** | ⚪ |
| **C85** | Acordo de líderes / orientação de bancada | Líder / Mesa | Instituto que estrutura a OD real (votar em bloco/dispensar interstício/ordem acordada); hoje "líder" é só rótulo. Reusa pauta_alteracao + decisao_mesa | §22.6 (pauta_alteracao) | fast_follow | 🟠 |

### Tema D — Correspondência e processo administrativo

| ID | Título | Persona | Por que importa | Âncora | §15 | Sev |
|---|---|---|---|---|---|---|
| **C58** | Correspondência/expediente recebido como workstream (receber→autuar→despachar→responder→fechar) | Servidor | Lado inverso, cotidiano e de **maior volume** do Expediente (HERO de POC); o catálogo trata o recebido como evento morto no numerador; o design já confessa a lacuna (`minhas-pendencias` lista "Protocolar 3 ofícios recebidos" sem tela que sirva) | 3.23 + 3.8 + 11.2 | **entra_v1** | 🔴 |
| **C61** | Relação com órgãos de controle (TCE/MP/TJ) como interlocutor | Jurídico | Genuinamente ausente, mas o caso material (prazo fatal de requisição) cai em C58+C59+classificação; entidade dedicada por órgão é especulativa sem cliente | 10.4/10.5 (só remessa) | **diferir** | 🟠 |
| **C65** | Comunicação interna Mesa↔vereadores (avisos/circulares) | Mesa | Canal técnico (11.6) entrega o aviso; falta a peça institucional com autoria-da-Mesa + arquivo + ciência. Distinta da convocação oficial (4.15) | 11.6 + 4.15 | **diferir** | ⚪ |

### Tema E — Participação cidadã (institutos faltantes)

| ID | Título | Persona | Por que importa | Âncora | §15 | Sev |
|---|---|---|---|---|---|---|
| **C69** | Iniciativa popular de lei | Cidadão / servidor | Instituto constitucional (CF art.29 XIII); a Casa recebe/protocola/verifica subscrições — dentro de escopo, não gabinete. Raro no município, recepção manual na V1 | §16.6 + 1.2 (gov.br) | fast_follow | 🟠 |
| **C70** | Tribuna popular / fala do cidadão na sessão | Cidadão | Instituto regimental da instituição; conecta com a fase `tribuna_livre_cidadao` (C28). Reusa 4.6 + porta gov.br | 4.6 + §22.6 Eixo B | fast_follow | 🟠 |
| **C73** | Agenda pública de sessões/audiências (superfície cidadã) | Cidadão / presidente | JTBD central de participação ("quando é a próxima sessão e o que está na pauta") e alimenta o KPI de engajamento do comprador; é leitura pública do read-model já fechado (5.2/2.6) — barato | 5.2 + 2.6 | **entra_v1** | 🟠 |
| **C78** | Página "Como participar" (mapa de portas) | Cidadão | Camada editorial agregadora; vários institutos que mapearia (C69/C70) ainda não existem | §16.6 | **diferir** | ⚪ |
| **C79** | Comprovante/recibo de protocolo ao cidadão | Cidadão | **Prova jurídica** recuperável (nº, data, objeto, prazo legal) para recurso ao MP/CGU/judiciário sobre e-SIC (LAI) e ouvidoria (13.460). Nº já existe (3.23); confirmação no ato é barata | 3.23 + 6.1/6.2 | **entra_v1** | 🔴 |

### Tema F — Estrutura legislativa e classificação

| ID | Título | Persona | Por que importa | Âncora | §15 | Sev |
|---|---|---|---|---|---|---|
| **C52** | Vínculo matéria↔norma-citada (proposição↔norma↔proposição) | Servidor / jurídico | Alimentador automático do grafo de C51; sem ele, quando a lei nasce (3.15) não há captura do que mexeu no acervo. Depende de C51 | C51 + 11.8 | fast_follow | 🟠 |
| **C53** | Indexação por assunto / classificação temática (tesauro leve) | Servidor / cidadão | **Table-stake determinística e barata** (≠ IA semântica): habilita filtro público por tema, distribuição automática à comissão competente (fecha a nota 🔎 da 2.3) e relatórios por tema. SAPL já tem | 3.7 + 2.3 | **entra_v1** | 🟠 |
| **C54** | Etiquetas/tags livres internas | Servidor | Muleta organizacional, não obrigação; montagem editorial de pauta já serve parcialmente. Promover só se beachhead migrando do SAPL reclamar | 3.3 | **diferir** | ⚪ |

### Tema G — Vereador (atos próprios)

| ID | Título | Persona | Por que importa | Âncora | §15 | Sev |
|---|---|---|---|---|---|---|
| **C84** | Declarações obrigatórias + impedimento/suspeição de voto | Vereador | (b) impedimento **afeta quórum e validade** (institucional, sério, raro) — sobe a fast_follow junto de C83 se houver nulidade; (a) declaração de bens tangencia gabinete | 4.3 (motor de votação) | **diferir** | ⚪ |

### Tema H — Mais automações de sessão / produtividade

| ID | Título | Persona | Por que importa | Âncora | §15 | Sev |
|---|---|---|---|---|---|---|
| **C37** | Leitura automatizada (TTS) de textos na sessão | Mesa / acessibilidade | Diferenciador + acessibilidade (LBI), mas é **6ª capacidade de IA** (síntese de voz) fora das 5 cravadas — abre porta de modelo/infra. Secretário lê em voz alta hoje | §16.8 (capacidades IA) | **diferir** | ⚪ |

---

## 3. Cobertura fraca (depth backlog)

A capacidade está catalogada e/ou o substrato cravado; falta a profundidade de superfície. Tabela enxuta —
**o que existe** vs. **o que falta**. (Severidade e §15 entre parênteses.)

| ID | O que existe | O que falta | §15 · Sev |
|---|---|---|---|
| **C01** | 12.1 (ciclo) + arquitetura (ente_id/schema/RLS/seed motor); botão-stub "Provisionar câmara" | Wizard: identificar ente + jurisdição TCE + plano/vagas + subdomínio → disparar provisionamento | diferir · 🟠 |
| **C02** | Ingredientes do seed (2.2/2.3/2.4/2.6 + numeração eixo H + jurisdição); `config-ente` aba "Sessões" VAZIA | Pacote mínimo de seed (quóruns/prazos/ritos/recesso/jurisdição default) de fábrica vs. entrada — **depende do especialista de regimento** | decisao_fundacao · 🟠 |
| **C04** | 4 estados como dado (12.1); chip "Inadimplente" sem ação | **Efeito legal** de cada estado: o que sobrevive à suspensão por obrigação LAI (portal/e-SIC); destino de dados no encerrado | decisao_fundacao · 🔴 |
| **C05** | 9.6 (off-board, G18) + `exportar-dados.html` (export voluntário do ente) | Costura encerrar(operador)↔sair(ente): dump final + janela + prova + off-board bilateral auditável | fast_follow · 🟠 |
| **C06** | Páginas-folha `admin-usuarios`/`config-ente`; admin_ente = "área de UI" | Home-cockpit agregadora "o que precisa da minha atenção" (reusa arquétipo Painéis da Mesa) | fast_follow · 🟠 |
| **C07** | 1.8/1.10 + auth §22.5 (MFAReset, vínculo com vigência); lista + menu três-pontinhos sem ações | Painel de detalhe: papel com vigência, vínculo a mandato, matriz RBAC, reset-MFA com caminho dual | fast_follow · 🟠 |
| **C08** | Tunables como config por módulo (§22.5 L164/L184); `config-ente` só Identidade/Canais | Conjunto de tunables auto-serviço (quórum/prazos/tribuna/substituição Mesa/recesso) com envelopes seguros — **especialista** | decisao_fundacao · 🟠 |
| **C09** | 12.7 (grant) + §22.5.2 eixo D (mecânica bilateral); grant 100% lado-operador | **Lado-ente:** ver pedidos pendentes, aprovar/negar com escopo+prazo, revogar, auditar (trilha LGPD do controlador) | **entra_v1** · 🔴 |
| **C10** | STI híbrido (espécie = colunas tipadas + CHECK); 3.18/3.23 pressupõem tipos | Decidir quais tabelas de tipo são curáveis pelo ente (situações 3.3, tipos de doc admin) vs. fixas por lei | decisao_fundacao · 🟠 |
| **C12** | 12.6 (observabilidade cross-tenant) + arquitetura; `console-operador` só agregados comerciais | Tela de saúde/adoção por tenant: uso real, saúde técnica vs. SLA 10.1, risco de churn, ônus de onboarding | fast_follow · 🟠 |
| **C17** | 3.5 (balcão de produzir parecer); `minhas-pendencias` (fila genérica) | Recorte "minhas relatorias" (carteira × prazo × status), distinto da fila por urgência | fast_follow · ⚪ |
| **C19** | 11.4 promete engajamento cidadão; `paineis-mesa` entrega 4 números institucionais estáticos | Engajamento cidadão de fato: evolução de comentários/e-SIC, picos por matéria, alcance, tendência | **entra_v1** · 🔴 |
| **C20** | ICP crava KPI servidor; PRD admite "tempo economizado = lacuna aberta" | **1º** definição operacional de hora poupada; **2º** leitura analítica (ata-IA gerada vs. revisada, docs, tramitações sem erro) | decisao_fundacao · 🟠 |
| **C23** | `vereador-estatisticas` já tem série temporal pessoal; `paineis-mesa` só comparação de uma linha | Série/narrativa institucional ao longo do biênio (recorte longitudinal de 11.4) | fast_follow · ⚪ |
| **C24** | Guardrail anti-ranking (16.6/16.11); presença individual já pública (5.2) | Comparativo institucional defensável (comissões/períodos lado a lado), delimitado do ranking político | diferir · ⚪ |
| **C25** | `paineis-mesa` dá ao jurídico só compliance TCE; `trilha-auditoria` é log | Painel de exceções/conformidade processual (atos sem ICP, prazos regimentais estourados, numeração pendente) | fast_follow · 🟠 |
| **C26** | Rótulo "Leitura e aprovação da ata" + fato narrado; aprovação reusa 4.3, ata 4.21 | Ato vivo: abrir discussão, registrar retificações, votar aprovação, efetivar definitiva no livro | fast_follow · 🟠 |
| **C27** | Fase Expediente como aba "✓"; Protocolo 3.23 desenhado; Eixo B modela itens | Modo "Expediente" no palco: fila de correspondências/ofícios percorrível/marcável-como-lido ao vivo | fast_follow · 🟠 |
| **C29** | Barra de comando com botões soltos; "dica-mesa" pontual | Roteiro passo-a-passo (próximo ato + fórmula/fala + botão acoplado) — **depende de conteúdo regimental** | diferir · ⚪ |
| **C30** | Rail "Prontas, fora da pauta" (lista estática de 2 itens); 3.3/3.8/11.1 sabem o estado | Popular o pool deterministicamente do estado (aptidão por parecer/prazo/2ª votação) com o motivo por item | fast_follow · 🟠 |
| **C32** | `ata-revisao` admite que chamada/placar são a autoridade mas gera do áudio | Pré-montar esqueleto factual (chamada, resultados, incidentes, horários) determinístico de 4.5/4.3/decisao_mesa | fast_follow · 🟠 |
| **C33** | Convocação como ilha-papel + "21 serão notificados"; 4.15 nomeia ciência/ledger (GAP 4) | **Ledger de ciência por vereador** + **validação computada do prazo mínimo** da LOM (hoje prazo é só texto) | **entra_v1** · 🔴 |
| **C34** | Botão "Encerrar VOTAÇÃO"; consequências existem isoladas (4.13/5.2/3.13/3.15) | Ato "Encerrar sessão" que encadeia snapshot → esqueleto de ata → fila de publicação → atos pendentes | fast_follow · 🟠 |
| **C35** | Botão "Votação em bloco" (disparador sem fluxo) | Mecânica: identificar bloco-elegíveis + agrupar + placar único + propagar a cada matéria | fast_follow · 🟠 |
| **C36** | Palco mostra título/nº/autoria/modalidade + faixa de tramitação | Acesso in-context ao texto/parecer (3.5)/emendas (3.2/11.8) — painel deslizante de anúncio/leitura | fast_follow · ⚪ |
| **C38** | `expediente` tem mala-direta (3.22) + `proposicoes` tem ações em massa | Job de **geração em lote**: modelo + lista de destinatários → N docs personalizados com merge → expedir em massa | fast_follow · 🟠 |
| **C39** | Slots de dado (origem_versao=redacao_final, votação sobre redação final no Eixo G) | Ato "enviar à redação final" + parecer/relator de redação + votação da redação final — **especialista** | fast_follow · 🟠 |
| **C41** | Modelo reconhece votação dupla (Eixo B); rótulos de turno no design | Turno como instituto que governa **interstício mínimo** (barra votar o 2º turno cedo) — **especialista** | fast_follow · 🟠 |
| **C43** | Votação polimórfica (Eixo G) suporta votar emenda/dispositivo; "votação em bloco" coberta | Relação de **destaque/DVS** (item retirado do bloco) + composição do resultado separado no texto/ata | fast_follow · 🟠 |
| **C44** | Enum tipo_emenda (substitutiva_total/redação); emenda→proposicao_mae_id | **Subemenda impossível** no schema (falta emenda→emenda); substitutivo virar texto-base; emenda de redação leve | fast_follow · 🟠 |
| **C48** | pauta_alteracao tipo "inversao" (manual); controles de ordem nas telas | Requerimento de **preferência** (regra) + precedência automática por regime/prazo fatal. Liga-se a C40 | fast_follow · 🟠 |
| **C50** | pauta_alteracao "retirada_pedido_autor" (adiamento, coberto); arquivamento genérico (3.9) | Estado terminal "retirada definitiva" + ato "requerer retirada da tramitação", distinto do adiamento | fast_follow · ⚪ |
| **C51** | 5.8 (repositório + consolidação manual, `[DIF]`); Eixo H (numeração canônica) | Entidade "norma" de 1ª classe + **grafo de relações tipadas** norma→norma (altera/revoga/regulamenta/suspende) | fast_follow · 🟠 |
| **C55** | 11.7 (exportação PDF/CSV de listas); pauta-PDF/ata-PDF parciais | Relatório de tramitação **parametrizado** (ano/tipo/local/situação→PDF) como leitura de 1ª classe sobre o read-model | fast_follow · 🟠 |
| **C56** | Item de OD ↔ resultado amarrado (ata-IA/telão); G1 cravou extraordinária=só OD | OD como **artefato estruturado** de 1ª classe (extrato citável/imprimível com resultado por item) | fast_follow · ⚪ |
| **C57** | 3.21 (upload validado = segurança); slot "anexos" em 11.8; G25 diferiu | Anexo/Documento Acessório como entidade **tipada/numerada** (ofício, justificativa, estudo) vinculada à matéria | fast_follow · 🟠 |
| **C59** | `prazo_dominio_ativo` polimórfico; e-SIC já reusa (timer LAI) | Materializar prazo sobre o **documento recebido** (não só e-SIC) e plugar em 11.1/11.2. Depende de C58 | fast_follow · 🟠 |
| **C60** | 11.2 (painel de despachos = leitura); 3.7 (distribuição de proposição) | Despacho como **peça composta** (texto+fundamentação+assinatura+responsável+prazo) registrável na trilha | fast_follow · 🟠 |
| **C63** | 3.14 (sanção/veto + prazo + votação do veto) | Camada de correspondência do veto: mensagem recebida + ofícios de resposta/promulgação como peças. Sobre C58/C66 | fast_follow · ⚪ |
| **C64** | 3.1 protocola indicação/req/moção; 3.3 tramita; 7.5 status | **Encaminhamento ao destinatário externo + resposta/providência** + status "atendido pelo Executivo" (maior volume!) | **entra_v1** · 🔴 |
| **C66** | Ledger de ciência só para convocação de sessão (4.15, GAP 4) | Generalizar recibo/ciência (AR, protocolo do destino) ao **ofício comum expedido** (3.22) | fast_follow · 🟠 |
| **C67** | 3.23 = numerador plano; roteamento por assunto só na ouvidoria (6.2) | Tipologia no protocolo geral: tipo-doc + sigilo + origem → triagem/roteamento. Pré-req de C58/C59 | fast_follow · 🟠 |
| **C68** | 2.6 (calendário procedimental) + `calendario.html` (sessão/comissão/audiência/prazo) | Categoria "compromisso/cerimonial institucional" (posse, visita oficial, representação) — fronteira com gabinete | diferir · ⚪ |
| **C71** | "Acompanhar pelo número" (busca avulsa); login gov.br; 5.5 (proposição) | Tela "Meus pedidos" do cidadão logado agregando e-SIC/ouvidoria/recursos com status/prazo/histórico | fast_follow · 🟠 |
| **C72** | 5.5 (acompanhamento de proposição, consent-gated); motor de prazo + ledger | Disparo de notificação na resposta/vencimento do pedido do cidadão + gestão de "matérias que sigo" | fast_follow · 🟠 |
| **C75** | 4.20 (caption ao vivo da transmissão); G27 diferiu janela Libras | Widget **VLibras** (gratuito, baixo custo) nas superfícies públicas — barato | fast_follow · 🟠 |
| **C76** | `.tema-btn` claro/escuro do chassi; 5.3 (WCAG AA declarado) | Barra de acessibilidade cidadã (A+/A−, alto contraste dedicado, atalhos) — padrão de portais gov BR | fast_follow · ⚪ |
| **C77** | 5.3 declara eMAG/WCAG AA em linha única; aria-labels pontuais | **Padrão de fundação verificável**: skip-links, landmarks, ordem de foco, jornada cidadã testada por leitor de tela | decisao_fundacao · 🟠 |
| **C80** | 5.2 (publicação crua), 5.9 (fiscal), perfil do vereador | Painel público de transparência da atividade legislativa como **narrativa anual** (adjacente a e-democracia V2) | diferir · ⚪ |
| **C81** | §22.6 modela `justificativa_ausencia` (state machine pendente→aprovada/indeferida) | Feature/superfície: submissão pelo app + deferimento pela Mesa + efeito na leitura de presença/quórum | fast_follow · 🟠 |
| **C82** | 2.1 (suplência/licença como atributo); §22.5 (mandato com cascata) | Fluxo de licença → **convocação de suplente** com prazo → posse → retorno → efeito na composição vigente | fast_follow · 🟠 |
| **C83** | §22.6 modela `inscricao_oradores` (intenção); lado-Mesa em `sessao-ao-vivo` | Porta do **vereador**: inscrição antecipada, pedido de vista, subscrição, declaração de impedimento (efeito em quórum) | fast_follow · 🟠 |
| **C86** | 3.23 numera doc de terceiro; `expediente` é o balcão; e-SIC/ouvidoria auto-serviço | Caminho "servidor abre pedido **em nome do cidadão presencial**" + anexar físico digitalizado + comprovante | fast_follow · 🟠 |
| **C87** | 4.5 (presença plenária); §22.6 `presenca_evento` genérica mas usada só em sessão | Estender presença/quórum/ata à **reunião de comissão** (generalizar sessao→reunião deliberativa) + publicar | fast_follow · 🟠 |

---

## 4. Já coberto, mas pouco visível (onde o Daouda Traore "não viu")

Itens catalogados cuja superfície foi deliberadamente diferida ou cujo substrato já neutraliza o risco que parece
faltar. Aqui o fundador acha o que procurava:

- **Admin do tenant (item 1):** as capacidades **existem** — `admin-usuarios.html` (1.8) e `config-ente.html` (1.9)
  estão desenhadas; `produto/13` declara `admin_ente` como **"área de UI, não módulo backend"** (flag 7). O que falta
  é a **home agregadora** (C06) e as ações dentro do detalhe (C07) — não as capacidades.
- **Admin da plataforma (item 3):** o épico **12.1–12.8** está inteiro no catálogo (módulo 16.12) e
  `console-operador.html` + `console-operador-tenant.html` cobrem o cockpit supratenant. A fundação de
  provisionamento (`ente_id`, schema/RLS, seed do motor via `jurisdicao_camara`, §22.7.9) **está cravada** — o que
  falta é só o wizard (C01) e o ato de handoff (C03).
- **Multi-órgão / consórcios / TCM (C11, `coberto_só_catalogo`):** o risco-de-refactor que parece assustador **já
  está neutralizado** — `documento-mestre` L478 crava **Ente polimórfico** + Município como referência compartilhada
  desde o dia 1, e a jurisdição TCM vive em `jurisdicao_camara` (§22.7.9). Falta só a **afordância de UI** (seletor de
  troca-de-ente) e o multi-vínculo de admin — diferíveis até cliente multi-ente real. **Diferir / baixa.**
- **Saúde/adoção dos tenants (C12, `coberto_só_catalogo`):** **12.6** (observabilidade cross-tenant) existe no
  catálogo e na arquitetura; `console-operador.html` só mostra os agregados comerciais. Falta a tela dedicada de
  saúde/adoção — **fast_follow V1.5** quando >1 tenant justificar suporte proativo.
- **Billing/contrato do operador (C13, `coberto_só_catalogo`):** **12.2** está catalogada `[FATO][SUPRATENANT]` com
  o pin 🔎 "build-vs-buy a decidir; piloto provável c/ cobrança artesanal". `produto/14` risco 12 **já decidiu
  parquear**. O status "Inadimplente" do cockpit é setável manualmente no piloto. **Diferir** — a decisão já foi
  tomada, não é gap descoberto.
- **Feature flags globais (C14, `coberto_só_catalogo`):** **12.3** distingue explicitamente flags **globais** de
  config-do-ente (1.9); o design só cobriu a por-tenant. Roadmap (`produto/13` M3-4) **já agenda** as flags globais
  pós-piloto. **Fast_follow V1.5.**
- **Console do catálogo do motor (C15, `coberto_só_catalogo`):** na V1 o catálogo é **declarado em código e
  versionado no schema `motor`** (§22.7.6); o "deploy de versão" é hoje um processo de engenharia (migration +
  type-check no save), não superfície de produto. Materializar só quando houver rotatividade real de regras
  (§22.7.9 = rollout demand-pulled). **Diferir.**
- **Recepção do Executivo (C62, `coberto_só_catalogo`):** **G28** já achou e **deliberadamente diferiu** (entrada
  manual na V1); a **saída** está coberta (3.13/3.14/3.15). Reavaliar quando uma câmara do beachhead exigir
  tramitação estruturada de projeto de iniciativa do Executivo. **Diferir.**
- **Carta de Serviços (C74, `coberto_só_catalogo`, ENTRA NA V1):** **6.2 / G13** já decidiram **ENTRA** — mas a
  página não foi desenhada (`ouvidoria.html` e `portal-cidadao.html` têm só link morto). É **obrigação Lei
  13.460/2017** que TCE/CGU auditam; gap de **design de feature comprometida**, não de escopo. **Materializar na V1.**
- **Justificativa de ausência (C81), licença/suplente (C82), atos do vereador (C83):** os **modelos de dados** já
  estão cravados em §22.6/§22.5 (`justificativa_ausencia` com state machine; mandato com cascata `tem_mandato_vigente`;
  `inscricao_oradores` separada de `fala_executada`). Não são fundação faltante — são **superfícies** a expor em V1.5.

---

## 5. Implicações

**Aperta o §18?** Sim, mas de forma controlada e diferente da r1. A r1 somou **escopo estrutural** (sessão completa,
Expediente) e empurrou o §18 para dimensionamento de time. Esta rodada soma sobretudo **profundidade dentro de telas
já cobertas** — o delta de V1 é pequeno e concentrado: **10 itens `entra_v1`**, dos quais a maioria reusa substrato
fechado. Os que mais pesam são **C40 (regime de tramitação)** — porque corrige um cálculo **errado** do motor de
prazos e da OD, não adiciona uma tela — e o trio de **go-live** (C03 handoff, C33 ciência de convocação, C79 recibo
cidadão), que são **baratos mas inadiáveis** porque destravam o primeiro cliente e fecham risco jurídico. O grosso
(65 `cobertura_fraca`) é **fast-follow V1.5**, não pressão de cronograma da V1.

**Reabre a §16?** Pontualmente, e menos que a r1. Quatro reaberturas defensáveis: **(1)** §16.3 deve **nomear** o
regime de tramitação (C40), a prejudicialidade (C42), a diligência (C45) e a retomada do adiado (C31) como institutos
de 1ª classe da máquina de estados — hoje confundidos com incidentes ou ausentes; **(2)** §16.3 deve nomear o
**workstream do recebido** (C58) e o **encaminhamento-e-retorno de indicação/moção** (C64) — o lado de maior volume
do Expediente que o catálogo trata como evento morto; **(3)** §16.5/§16.6 ganham **classificação temática** (C53,
fecha a nota 🔎 da 2.3) e a **agenda pública de sessões** (C73) como leitura cidadã de 1ª classe; **(4)** §16.11
precisa explicitar que **11.4 inclui engajamento cidadão como narrativa** (C19) e admitir o **recorte por comissão**
(C16) — a §16 omitiu a unidade-de-leitura intermediária. A **§16.12** (operador) deve nomear o **handoff** (C03) e a
**semântica de lifecycle** (C04) como atos, não só o ciclo como dado.

**Reusa substrato?** Massivamente — é a marca desta rodada e a razão de tão poucos `entra_v1`. O **motor de prazo
polimórfico** `prazo_dominio_ativo` absorve C45 (diligência = estado suspenso novo), C59 (prazo do recebido), C64
(resposta a requerimento), C41 (interstício de turno); a **máquina de estados declarativa** (Eixo C) absorve C42
(prejudicada), C50 (retirada definitiva), C47 (arquivamento de legislatura) — todos como configuração de template,
sem refactor; a **votação polimórfica** (Eixo G) absorve C43 (DVS), C46 (recurso ao plenário), C39 (votação da
redação final); o **ledger de ciência** de 4.15 (GAP 4) generaliza para C33, C66, C79; o **arquétipo cockpit** (já
provado em Painéis da Mesa) serve C06, C09, C12, C16. **Três pontos exigem decisão de modelo antes de materializar
o `cadastros`/Eixo D:** o **vínculo emenda→emenda** da subemenda (C44, hoje estruturalmente impossível — merece ser
cravado para não virar refactor, à la G17/URN), a **entidade norma + grafo de relações** (C51, toca o Eixo H), e a
**curadoria de tabelas de tipo** (C10, tensão com Invariantes 4/5 e o STI híbrido). E **quatro `decisao_fundacao`
não-de-tela** ficam represadas no **especialista de regimento (§22.4.4)** ou no produto: o pacote de seed (C02), os
tunables auto-serviço (C08), a definição operacional de hora-poupada (C20) e a semântica de lifecycle de suspensão
(C04) — nenhuma se desenha antes de decidir o conteúdo. **Conclusão (dentro da moldura desta rodada):** o produto está
estruturalmente completo no eixo feature-de-negócio/caminho feliz; o que resta ali é cavar profundidade sobre fundações
já cravadas — exatamente onde o fundador apontou. **Fora da moldura**, porém, ficam cinco classes de preocupação não
auditadas que a §6 levanta e que **devem ser sondadas antes de declarar a V1 pronta para go-live B2G.**

---

## 6. Pontos não sondados / sondar a seguir

> **NÃO-VERIFICADOS — candidatos a uma próxima rodada, não gaps confirmados.** Esta seção registra os **residuais de
> uma crítica adversarial** ao rascunho. Eles **não passaram pela varredura C01–C87 contra `produto/13`/`14`, as 47
> telas e a §22** — são **hipóteses de ausência**, não achados. O ponto de fundo da crítica: as 8 lentes e os 87
> C-itens auditam **profundidade de feature-de-negócio no caminho feliz**; o que segue são **cinco classes de
> preocupação ortogonais** — não "mais profundidade da mesma feature", e sim **dimensões inteiras** (falha/recuperação,
> migração operada, não-funcional/segurança/privacidade, notificação comprovável, concorrência/carga). Três delas
> (rejeição de remessa ao TCE, NF4/ANPD, migração operada) tocam **diretamente as 3 apostas** e a viabilidade de
> passar num primeiro pregão. **Recomendação da crítica: não declarar a V1 "estruturalmente completa" antes de cruzar
> `produto/15` e os caminhos de falha das 3 apostas.** Cada item abaixo precisa de uma passada própria — Read de fonte,
> régua §15, cruzamento com o design — antes de virar `gap_real`/`cobertura_fraca`/diferível.

### R-MIG — Migração / onboarding de dados como JTBD vivo (a Aposta 3 nominal) · 🔴 candidato a 9ª lente

- **Pergunta não sondada.** Qual é a **superfície** que o servidor-migrador e o operador usam durante os 30 dias
  contrato→go-live? Onde se vê o **progresso da importação**, se **concilia** o que entrou (contagens esperadas vs.
  importadas), se trata o **registro que falhou no parse**, se **aprova/rejeita lote**, e como o cliente **vê que sua
  história legislativa chegou inteira** antes de assinar o aceite?
- **Provável gap.** As 8 lentes incluíram "ciclo de vida do tenant" e "paridade competitiva", mas trataram migração só
  como **handoff/provisionamento (C01–C05)** e como **conector (9.5)**. A migração como **workstream operado** —
  staging, dry-run, reconciliação, fila de exceções de parse, diff esperado-vs-importado, aprovação de lote, relatório
  de cobertura entregue ao cliente — é a **materialização concreta da Aposta 3 ("migração como feature")** e está
  totalmente fora do design (16.9 tem só endpoints 9.1 + script artesanal 9.4). É o JTBD que mais decide a **renovação**
  e o de maior **atrito real no beachhead** (SAPL/Softcam têm acervo sujo).
- **A sondar (próxima rodada).** Dedicar uma **9ª lente — "migração como produto operado"**. No mínimo: tela de
  staging/reconciliação por módulo, fila de exceções de importação, relatório de cobertura **assinável** pelo cliente,
  e o gate **"go-live só com X% conciliado"**. Provavelmente **2–4 telas novas** + estados em `prazo_dominio`/auditoria
  — **não é depth backlog, é ausência de superfície de uma das 3 apostas.**

### R-REM — Caminhos de falha e recuperação da submissão ao TCE (Aposta 3) · 🔴

- **Pergunta não sondada.** O que acontece na superfície quando uma remessa ao TCE é **REJEITADA**, quando o layout do
  tribunal **muda na véspera** do prazo, quando a **janela de envio fecha**, ou quando o gerador produz **artefato
  inválido**? Onde o servidor vê o erro de validação do tribunal, **corrige e reenvia DENTRO do prazo fatal**? Existe
  **alerta proativo** de "remessa vai vencer / falhou" antes de o cliente perder a janela?
- **Provável gap.** O `CLAUDE.md` crava que **"uma regra de compliance falhando em runtime e fazendo perder janela de
  envio é incidente inaceitável"** — é a tese da Aposta 3. O motor (§22.7.8) modela `remessa_gerada`/`remessa_enviada`
  e cumpre obrigação em **`aceita`**, mas o rascunho audita apenas o **caminho feliz** e os institutos legislativos. A
  **esteira de exceção** da remessa (rejeição→diagnóstico→correção→reenvio, alerta de prazo iminente de **envio**, fila
  de remessas com erro) é justamente a superfície onde a promessa comercial **vive ou morre**, e não aparece em nenhuma
  das 8 lentes nem nos C-itens.
- **A sondar.** Estados de **erro de remessa**, diagnóstico legível do rejeite do tribunal, **reenvio versionado**, e
  **alerta proativo de janela-de-envio-em-risco** (distinto do prazo de tramitação). **Cruzar com `paineis-mesa.html`**
  (hoje só mostra "11·1·0" verde) — falta a leitura do **"estamos com 1 remessa REJEITADA e o prazo vence em 2 dias"**.

### R-NF — Requisitos não-funcionais / segurança / privacidade como superfície e como gate (`produto/15` nunca cruzado com o design) · 🔴 gate de go-live B2G

- **Pergunta não sondada.** As obrigações de privacidade e segurança que `produto/15` marca 🔴 (**RIPD/DPIA**, resposta
  a **incidente + notificação ANPD art.48**, cripto em trânsito/repouso, gestão de segredos) e as **19 NF-pendências**
  têm **dono, superfície e gate**? Em particular: existe a **tela/fluxo de notificação de incidente** de dados ao titular
  e à ANPD que o controlador (a câmara) é legalmente obrigado a ter, e o **registro de tratamento (ROPA)** que o DPO opera?
- **Provável gap.** Nenhuma das 8 lentes é não-funcional/segurança/privacidade-operacional. `produto/15` lista isso como
  omissões de **fundação (NF1–NF6)** e **processo (NF4 incidente/ANPD)**, explicitamente "**zero menção no repo**". O
  rascunho audita features-de-negócio e conclui "estruturalmente completo", mas a completude da V1 **para venda a órgão
  público** (editais ISO 27001/27017) e **para conformidade LGPD do controlador** depende dessas peças. **NF4 (RIPD +
  incidente + ANPD)** é obrigação legal direta **com componente de superfície** (registro/fluxo), não só infra.
- **A sondar.** Cruzar `produto/15` com o catálogo de design **antes** de declarar a V1 completa. No mínimo levantar:
  (a) superfície de **gestão de incidente de dados + notificação ANPD/titular**; (b) **ROPA/registro de tratamento**
  operável pelo DPO (complementa o portal do titular 5.10); (c) os **alvos numéricos vazios** (NF7–NF12:
  uptime/RTO/RPO/sizing de áudio) que precisam virar número antes do primeiro edital. **São gates de go-live B2G, não
  fast-follow.**

### R-IA — Operação contínua da IA: degradação, fallback de vendor e custo (não só proveniência) · 🟠

- **Pergunta não sondada.** O que o produto faz quando a **porta de inferência vendor-agnóstica FALHA**, fica lenta ou
  **estoura custo** no meio de uma sessão ao vivo (**ata-IA HERO**) ou de uma redação com copiloto? Existe **degradação
  graciosa**, fila de retry, **fallback entre vendors**, indicação ao usuário de "IA indisponível, siga manual", e
  **teto de custo por ente**? E quando o vendor muda e a qualidade do resumo cidadão **regride**, há **detecção**?
- **Provável gap.** A lente de analítica tocou "observabilidade do modelo" (8.6) só como **proveniência** (qual versão
  gerou qual artefato legal). A **resiliência operacional** da porta de IA — timeout/fallback/retry/teto de
  custo/degradação graciosa em runtime — **não foi sondada** por nenhuma das 8 lentes. Com IA híbrida e LLM de fronteira
  atrás de porta trocável (§22.9 Eixo 10), a **indisponibilidade do vendor durante uma sessão ao vivo derruba uma
  feature HERO**; o produto precisa de comportamento de borda definido, e a **régua FinOps (NF18, custo por ente)**
  também é virgem.
- **A sondar.** Estado **"IA indisponível"** em `editor-proposicao`/`ata-revisao`/portal (degradar para fluxo manual
  **sem bloquear o ato legislativo**), retry/fallback entre vendors, **teto de custo/quota por ente**, e **detecção de
  regressão de qualidade** ao trocar vendor. Toca `produto/15` **NF18** e a camada de confiança **16.8**.

### R-NOT — Notificação como sistema de entrega confiável + preferências do usuário (não só o inbox in-app) · 🟠 (lado faltante do C33 que o próprio rascunho promove)

- **Pergunta não sondada.** Para as ciências e prazos juridicamente relevantes que o rascunho **promove à V1** (C33
  ledger de convocação, C79 recibo cidadão, C64 retorno de indicação, alertas de remessa), **como a notificação sai da
  casa de forma comprovável**? Há **canal externo** (e-mail/SMS/push) com **prova de entrega**, retry, gestão de bounce,
  **preferências por usuário**, e quiet hours? Ou tudo depende do usuário **abrir o inbox in-app**?
- **Provável gap.** A lente do "ritual de sessão" tocou convocação/ciência (C33), mas a **infraestrutura de entrega** da
  notificação (11.6 canal técnico) não foi auditada quanto a **confiabilidade jurídica**: uma ciência de convocação que
  **conta prazo da LOM** precisa de prova de **envio E entrega**, não só de "foi pro inbox". Igualmente, a notificação ao
  cidadão na resposta do e-SIC (**prazo LAI**) precisa de canal externo confiável. Preferências de canal/quiet-hours por
  usuário e tratamento de bounce/falha de entrega **não aparecem**.
- **A sondar.** Notificação como **subsistema**: matriz **canal×evento**, **prova de entrega** para os eventos
  juridicamente vinculantes (convocação, resposta e-SIC/ouvidoria, vencimento de prazo), retry/bounce, e **central de
  preferências** do usuário (in-app/e-mail/SMS/push, quiet hours). Sem isso, **o ledger de ciência (C33) que o rascunho
  promove à V1 fica sem o lado da entrega comprovável.**

### R-CONC — Concorrência, multi-sessão e comportamento sob carga real na sessão ao vivo (correção, não layout) · 🟠

- **Pergunta não sondada.** Quando **dois servidores editam a mesma pauta**, quando o secretário **corrige o placar
  enquanto a votação fecha**, quando a **conexão do app do vereador cai no meio de uma votação nominal** (PWA, Aposta 2),
  ou quando há **quórum disputado em tempo real** — o que o produto faz? Há **trava otimista/pessimista**, **reconciliação
  de voto offline**, e comportamento definido de **empate/recontagem**? E o **portal público aguenta o pico** de acesso no
  dia da sessão polêmica?
- **Provável gap.** As lentes de "ritual de sessão" e "profundidade do processo" auditaram **que atos existem**, mas não a
  **correção concorrente** desses atos sob uso real e simultâneo. `produto/15` marca **NF10/NF11 (carga/concorrência)** e
  **zero teste de estresse** como vazios. A sessão ao vivo é **tempo-real multi-ator com consequência jurídica** (voto
  nominal); conflito de edição, voto offline do PWA que reconecta, e recontagem/empate são **caminhos de borda não
  sondados** — e o **pico de portal** no dia da sessão é o momento exato em que o **engajamento cidadão (KPI do comprador)**
  é medido.
- **A sondar.** **Correção concorrente e carga**: política de conflito na edição de pauta/placar, **reconciliação de voto
  do PWA offline→online (idempotência)**, regra explícita de **empate/recontagem/anulação** de votação, e **alvo de carga
  do portal público (NF11)** para o dia de sessão. É **correção de domínio, não polish de tela.**

### Síntese da crítica (avaliação geral)

O rascunho é **forte e honesto DENTRO da sua moldura**, mas a moldura tem uma forma reveladora: as 8 lentes e os 87
C-itens são quase inteiramente sobre **profundidade de feature-de-negócio no caminho feliz** — institutos legislativos,
superfícies admin, leitura analítica. A conclusão "estruturalmente completo, resta cavar profundidade" é **defensável
para essa dimensão**. O que ficou sistematicamente fora são **cinco eixos ortogonais** que não são "mais profundidade da
mesma feature" e sim **classes de preocupação ausentes**: (1) caminhos de **falha e recuperação** — rejeição de remessa
ao TCE, IA indisponível, voto offline, conflito de edição concorrente — justamente onde a Aposta 3 (confiança
operacional) e as Apostas 1/2 (IA, PWA) são testadas de verdade; (2) **migração como produto operado** (a Aposta 3
nominal), com staging/reconciliação/exceções, hoje só script artesanal; (3) o **não-funcional/segurança/privacidade-
operacional** inteiro (`produto/15` — RIPD, incidente+ANPD, cripto, alvos numéricos), que é gate de edital B2G e de
conformidade do controlador, **nunca cruzado com o design**; (4) **notificação como entrega comprovável**, que o próprio
rascunho pressupõe ao promover ciências/prazos à V1 sem auditar o canal; (5) **concorrência e carga** na sessão ao vivo,
correção de domínio sob uso simultâneo real. Nenhum desses **repete** os C-itens — são **complementares** e, três deles
(rejeição de remessa, NF4/ANPD, migração operada), tocam diretamente as próprias apostas que o produto vende e a
viabilidade de passar num primeiro pregão. **Recomendação: NÃO declarar a V1 "estruturalmente completa" até cruzar
`produto/15` e os caminhos de falha das 3 apostas.** O caminho feliz está maduro, mas **as bordas que decidem renovação e
go-live B2G seguem virgens.**