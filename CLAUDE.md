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
- **Fundador/CTO:** Daouda Traore — decisor técnico único, 10+ anos de engenharia. Comunicação direta, intolerante a complexidade desnecessária e framing rebuscado.

**Três apostas de produto da V1:** (1) IA como copiloto legislativo; (2) experiência de
produto moderna para três públicos (servidor, vereador, cidadão); (3) confiança operacional
como diferencial comercial (migração como feature, SLA de janela de sessão, compliance TCE automático).

**Três públicos decisores em licitação**, cada um com porta de entrada própria: servidor
(avalia na POC — ganha com IA e UX), presidente da Mesa (aprova politicamente — ganha com
engajamento cidadão), jurídico/administrativo (avalia risco — ganha com confiança operacional).

---

## 2. Em que fase estamos

**Implementação.** As três trilhas de discovery estão **fechadas** e viraram fundo de consulta,
não frente de trabalho:

- **Arquitetura (§22) — COMPLETA.** Todas as subseções fechadas; o doc-mestre está na v1.39, com a
  §22 densa extraída para `arquitetura/` (7 arquivos, um por subseção). **Nenhum eixo de design de
  arquitetura aberto.** O granular que resta a reconciliar (mecânica fina do registry, formas
  descartadas no Eixo A de §22.7) vive em §22.7.4 e não bloqueia nada.
- **Produto/comercial — COMPLETA** (`produto/`, 113 features / 12 módulos).
- **Design system — COMPLETO** (`produto/design-system/`, 47 telas, 10 arquétipos provados, os 3
  públicos decisores cobertos). O que resta ali é **profundidade dentro de telas já cobertas**
  (`INVENTARIO §4`), não ausência — só puxar se um cliente pedir.

**O plano de execução da engenharia (`docs/11-plano-execucao-engenharia.md`) também acabou:
as 8 fases F0–F7 estão mergeadas em `main`** — F0 plataforma base · F1 cadastros+identidade ·
F2 resolvedor de fatos (o KEYSTONE) · F3 legislativo (8 eixos) · F4 sessões+tempo real (HERO) ·
F5 compliance/remessa · F6 transparência/participação · F7 painéis/observabilidade. Ao fim da F7 eram 13
módulos e 61 migrations; em 04/10/2026 são 121 migrations. **A track de frontend (`docs/13-plano-track-fe.md`) fechou as Ondas A–D**, com os
marcos MFE-1 a MFE-4 cumpridos.

**Marcos de valor demonstrável:** M1 (a Casa existe), M2 (compliance vivo), M3 (coração
legislativo), M5 (a porta da rua) e M6 (remessa ao TCE) **fechados**. **M4 (a sessão acontece) é o
único parcial** — sessão ao vivo, quórum, presença, tribuna e placar funcionam e estão portados; o
que falta é a ata-IA, que depende da Track IA (ver §3).

O detalhe rolling de cada fase vive nas memórias de sessão (`oplenario-f0-execucao` … `oplenario-f7-execucao`,
`oplenario-fe-execucao`, `oplenario-proxima-sessao`), não neste arquivo.

---

## 3. ⚠️ Estado do cursor + primeira ação

**Estado (04/10/2026):** o caminho crítico do plano de engenharia está cumprido. As frentes
abertas, em ordem de importância:

**1. Track IA — base comum ENTREGUE (26/09/2026); Faixas A e B são o maior bloco restante.** O satélite existe:
`apps/ia/` (Python 3.12, **[ADR-0006](docs/adr/0006-satelite-de-ia-apps-ia.md)**), com porta de inferência (fake por
padrão + OpenRouter + Anthropic), filtro de governança B1–B4 (único caminho até o LLM), Camada de Confiança mínima (citação conferida,
incerteza, registro sem conteúdo, revisão humana, R-IA-1), `nucleo.Nucleo` (o pipeline que **toda** capacidade compõe) e
avaliação no CI (`oplenario-ia-avaliar avaliacoes`) + custo por Casa — [PR #42](https://github.com/GondwanaDEV/oplenario/pull/42).
**Faixa A em curso (26/09/2026):** captação (A.2), transcrição com Caminho C + fronteira core↔IA
([ADR-0008](docs/adr/0008-fronteira-core-ia-eventos-de-integracao.md), A.3) e **ata-IA completa (A.6)** — ata como
artefato legal do core com retificação, rascunho pelo núcleo com citação conferida e `[confirmar: …]`, revisão humana
medida de volta na IA — PRs #43–#47 — e **leitura da ata anterior (A.7)** como ato da sessão (voz sintetizada,
presencial ou dispensada; a voz é a do navegador da Mesa, provisória até a decisão do fornecedor de voz). **Tudo roda
com o fornecedor fake** (conferência real, texto de roteiro). **Índice único e busca intra-câmara (A.4/A.5)** entregues — PR #49 e o seguinte: a IA devolve ids, o core decide o que se vê; IA fora cai na busca pela ementa. **Resumo cidadão (A.8)** entregue: a IA redige a cada versão do texto, a secretaria revisa e publica, o portal mostra com o selo de revisão humana — **a Faixa A está completa em código**; a A.1 (qualidade medida) espera o áudio de
Baturité com 15 min anotados. A **Faixa B** (agente, consulta LOM/RI, copiloto) começou pelo **catálogo de ações (B.1, [ADR-0009](docs/adr/0009-catalogo-de-acoes-e-adaptador-mcp-no-core.md))**: uma ferramenta por ação de domínio, declarada em `diplomat/catalogo.clj` de cada módulo; **rota nova sem entrada no catálogo (ou motivo em `resources/catalogo/fora-do-catalogo.edn`) quebra o CI**. A **identidade delegada (B.2, [ADR-0010](docs/adr/0010-identidade-delegada-do-agente.md))** está feita: o core emite uma credencial opaca por execução do agente (o Keycloak 26.0 não delega), a permissão é recalculada a cada chamada e `ato` por agente só vira proposta (B.6). O **assistente da Casa (B.3)** está no ar em `/assistente`: pergunta em palavras, o agente consulta o core pelo servidor MCP como a pessoa e responde citando o que consultou. As **normas da Casa (B.4, [ADR-0011](docs/adr/0011-normas-de-referencia-por-dispositivo.md))** entram por texto, quebradas por dispositivo, conferidas pela secretaria em `/normas` e indexadas na IA ao publicar; na **B.5** o assistente lê a LOM e o Regimento pelo artigo (`buscar_dispositivos`, `ler_dispositivo`) e cita o dispositivo com a data até quando o texto foi conferido. Na **B.6 ([ADR-0012](docs/adr/0012-proposta-de-ato.md))** o agente passa a PROPOR atos: nada executa sozinho — a pessoa lê e confirma em `/propostas/:id` (o vereador já pede o requerimento em palavras em `/vereador/assistente`); voto, presença e condução da sessão nem são propostos. Na **B.7** o copiloto entra no "Novo requerimento": o vereador descreve em palavras e o formulário volta preenchido, com a justificativa citando a norma da Casa — ele revisa e assina pelo fluxo de sempre. Na **B.8 ([ADR-0013](docs/adr/0013-agente-institucional-e-conferencia.md))** estreia o agente institucional da Casa (sem pessoa, ligado pelo `admin_ente`): a cada proposição protocolada ele lê a matéria e a LOM/RI e deixa uma nota técnica em rascunho, com citações, na fila `/conferencias` da secretaria, que aproveita ou descarta. Na **B.9 ([ADR-0014](docs/adr/0014-orcamento-de-ia-e-painel-da-casa.md))** entra a cota de IA por Casa (orçamento do plano definido pelo operador; aviso a 80%, segundo plano pausa primeiro, teto duro → "cota da Casa") e o painel `/paineis/ia` do administrador; **a Faixa B está completa em código** — faltam os valores comerciais do orçamento e o fornecedor real. As **normas de referência (B.4a, [ADR-0011](docs/adr/0011-normas-de-referencia-por-dispositivo.md))** entram em `/normas`: a secretaria importa o texto, o parser separa em dispositivos com endereço estável e só vale depois de uma pessoa conferir. **Falta receber a LOM e o RI reais de Baturité e Fortaleza.** M4 fecha em código com a
A.6; a qualidade real depende da A.1 e do fornecedor real. Uso real de fornecedor segue travado no `[GAP]` jurídico (DPA de
não-treino, LGPD art. 33) — o fake não espera. **O OpenRouter é o fornecedor de modelo de linguagem da plataforma
(05/10/2026, [ADR-0023](docs/adr/0023-openrouter-como-fornecedor-de-modelo-de-linguagem.md)),** contra a recomendação
de [`docs/30`](docs/30-avaliacao-openrouter.md): adaptador `openrouter` (`OPLENARIO_IA_VENDOR=openrouter` +
`OPENROUTER_API_KEY`), ZDR, "sem coleta" e `require_parameters` travados em toda requisição, provedor e custo
declarados no registro. As 8 capacidades passam por ele; transcrição e embeddings seguem self-host. O satélite só sobe
com modelo de `MODELOS_OPENROUTER_PERMITIDOS` (`openai/gpt-oss-120b`, padrão provisório, e `qwen/qwen3.8-27b:free`).
**Conferido ao vivo (05/10/2026, chave gratuita sem crédito):** o formato da resposta bate com o adaptador, a política de
ZDR é obedecida (404 quando nenhum provedor a cumpre); o qwen gratuito passou em `base-comum` e `copiloto-relator` e
reprovou no `agente-seguranca` (o modelo esgota os tokens raciocinando, e a conta gratuita devolve 429) — cada modelo
da lista ganhou uma folga de raciocínio somada ao `max_tokens` (`MODELOS_OPENROUTER`), ainda não medida ao vivo; o `gpt-oss-120b` **ainda não foi avaliado** (402, falta crédito). Ligar em produção espera o mesmo
`[GAP]` jurídico, agora com o OpenRouter como contratado. Desenho: **`docs/25`** + doc-mestre §22.11 (v1.46); plano: **`docs/26`**
(rev. 2, "Confirmo" do Daouda com o merge do PR #38). **Ler os dois antes de escrever qualquer feature de IA.**
`prototipos/governanca-ia/` é só referência histórica (o filtro de produção está em `apps/ia/`).
**O satélite está EM PRODUÇÃO (27/09/2026), com o fornecedor fake:** `ia-api` + `ia-trabalhador` no Dokploy, no
mesmo banco do core (schema `ia`, **o Postgres precisa de pgvector**), ligados ao core pelo segredo compartilhado; a
busca responde `modo: "ia"` e o acervo da Casa demo foi indexado (`ia-republicar-proposicoes`). Topologia,
variáveis, deploy (`build-ia-prd.yaml`, só na branch `production`), reindexação, orçamento e o que fazer quando a
busca cai em `sem-ia`: **`docs/27-runbook-ia-producao.md`**. Smoke das telas de IA: `fumaca-ia.yaml`.

**2. IdPs — o gov.br do cidadão ENTREGUE (27/09/2026); o do operador segue aberto.** O broker **gov.br**
([ADR-0015](docs/adr/0015-cidadao-entra-pelo-govbr.md)) é IdP do realm de cada Casa (OIDC+PKCE, `GOVBR_AMBIENTE`
= producao|homologacao|simulado): do gov.br entram só CPF e nome, o 1º login cria identidade (pelo CPF) + vínculo
de cidadão + consentimento, e a sessão aberta pelo gov.br é **só de cidadão** (zero papéis, mesmo para quem é
vereador). O portal tem "Entrar para participar" (`/portal/casa/[ente]/participar`); a cidadã cai em
`/acompanhamentos`. Dev/demo/CI usam o **gov.br simulado** (`demo/govbr_simulado.clj`, realm no próprio Keycloak).
**Formulários do cidadão ENTREGUES (27/09/2026):** no portal, abrir pedido de e-SIC (`/esic/novo`), exercer direito
LGPD (`/lgpd/novo?tipo=`), manifestar à ouvidoria (`/ouvidoria`, inclusive anônima), comentar e acompanhar a matéria
na ficha pública — cada escrita devolve o recibo com protocolo. Na área da cidadã, `/meus-protocolos` (rota
`GET /portal/meus-protocolos`) mostra estado, prazo e a resposta da Câmara, e o recurso do e-SIC. **Fora por não
existir no backend:** anexo da ouvidoria, cartão do ouvidor, denunciar comentário (sem desenho). **Falta, `[GAP]`
externo:** o credenciamento no gov.br (client de homologação/produção + URL de retorno por Casa).
**O console do operador ENTREGUE (27/09/2026, [ADR-0016](docs/adr/0016-operador-da-plataforma-e-registro-de-casas.md)):**
o operador entra pelo realm `operacao` (Keycloak separado em produção, `OPERACAO_KC_*`) com **senha + chave física**
(WebAuthn cross-platform; atestação `direct` exige as raízes FIDO no truststore em produção, `none` em dev/demo/CI),
cookie `sessao_operacao`; nenhuma credencial de Casa abre o console e vice-versa (2ª dimensão do vazamento, testada).
Em `/operacao` ele vê as Câmaras, **provisiona** (registro emite o `ente_id`, perfil no cadastros, 1º `admin_ente` pelo
CPF, convite) e acompanha o **handoff**: a Casa vira "ativa" quando o 1º administrador entra (evento
`identidade.vinculo.primeiro_acesso`). A atuação da Operação é append-only com selo encadeado. Primeiro operador:
`oplenario.main operador-convidar`. **A área do `admin_ente` ENTREGUE (28/09/2026, ADR-0005):** `/administracao`, onde
o administrador concede acesso aos vereadores; quem só tem esse papel pousa nela (não mais na tela da cidadã).
**Suspender/reativar Casa ENTREGUE (30/09/2026, [ADR-0018](docs/adr/0018-suspender-e-encerrar-casa.md) fatia 1).**
- O que existe:
  - dois operadores para suspender; o incidente começa com um e volta sozinho se o 2º não confirmar em 24 h;
  - a suspensão com sessão em curso fica agendada;
  - escrita fora da allowlist → **423 "acesso restrito"**. A allowlist fica em `restricao_da_casa.clj` e cobre os
    protocolos do cidadão, as respostas dos servidores, a remessa ao TCE e o encarregado LGPD;
  - a cota de IA fica zerada enquanto a Casa está suspensa;
  - faixa sem motivo em todas as superfícies;
  - "Acesso da Câmara" na ficha do console.
- **Encerrar ENTREGUE (02/10/2026, fatia 2):**
  - exportação completa em formato aberto, gerada pelo `admin_ente` ou pelo operador; só o `admin_ente` baixa, com o
    cidadão pseudonimizado;
  - confirmação pelo SHA-256, depois guarda de 90 dias;
  - apagamento aprovado por dois operadores, feito por uma função `SECURITY DEFINER` que confere tudo no banco, e que
    apaga também o object storage, o realm e o satélite; retomável;
  - estado `encerrado` imutável, com 410 e a página "não usa mais O Plenário".
- **Falta:**
  - o e-mail de aviso (SMTP);
  - acesso de suporte (12.7, `[GAP]` jurídico);
  - flags (12.3);
  - billing (12.2, parqueado).

**Comunicados internos ENTREGUES (02/10/2026, [ADR-0020](docs/adr/0020-comunicados-internos-e-setores.md)):** a
resposta ao "servidor de e-mail" do Daouda é uma caixa DENTRO do sistema, sem e-mail.
- Setores da Casa (`cadastros.setor`, em `/administracao`).
- Comunicado com protocolo `COM-AAAA-NNNNNN`, imutável, endereçado a pessoa, vereador, setor, todos os setores ou
  comissão. A lista é congelada no envio.
- Marcas insert-only `recebido`/`lido`/`ciente`. Ciência opcional, com prazo em dia.
- Painel de leitura para quem enviou, a secretaria e o admin.
- Anexos, link para sessão/proposição/protocolo e substituição.
- `/caixa` para todos (o vereador na aba Avisos), juntando comunicados e avisos do sistema. Avisos automáticos de
  pauta publicada e de parecer jurídico pedido.
- Módulo de domínio novo `comunicacao`; o host compõe os destinatários em `destinatarios.clj`.
- **Falta:**
  - e-mail/push (quando houver SMTP);
  - a convocação oficial de sessão (4.15), que espera a resposta jurídica sobre a prova.

**Balcão de atendimento ao cidadão ENTREGUE (04/10/2026):** a secretaria vê e responde em `/atendimento` o que o
cidadão protocola no portal — antes só havia as rotas de escrita, sem lista nem tela.
- Filas e-SIC · Ouvidoria · LGPD (`GET /atendimento/{esic,ouvidoria,lgpd}`, papel `secretario`), abertos pelo prazo que
  vence primeiro; detalhe com histórico e só as ações que cabem no estado (responder, decidir recurso, prorrogar, arquivar).
- Prorrogação do e-SIC (LAI art. 11 §2º, +10 dias, uma vez) e `GET /lgpd/encarregado` para editar o contato do DPO.
- Identidade: e-SIC e LGPD mostram nome + CPF mascarado no SQL; a ouvidoria mostra só identificada/anônima (Lei 13.460
  art. 10 §7). Nenhuma rota do balcão é ferramenta do agente (dado pessoal → `[GAP]` do fornecedor de IA).
- Junto: "Denunciar" no comentário da ficha pública (6.3) e "Reportar erro" na resposta do assistente (8.4, só categoria,
  sem texto). O botão também está no copiloto do relator, no do requerimento, no rascunho da ata, no do resumo cidadão
  e na nota técnica do agente institucional (`/conferencias`, `/juridico/notas`; o satélite manda `execucao-ia` em
  `registrar_nota_tecnica`, ADR-0013; nota anterior fica sem botão) (04/10/2026).
- **Indeferir, ciência da prorrogação e anexos ENTREGUES (04/10/2026,
  [ADR-0022](docs/adr/0022-indeferir-ciencia-da-prorrogacao-e-anexos-no-balcao.md), aceita):**
  - indeferir e-SIC e LGPD com fundamentação obrigatória: ato próprio, conta como prazo cumprido, e o recurso do e-SIC
    continua cabendo;
  - o requerente lê a prorrogação e a justificativa em `/meus-protocolos`; as rotas públicas por número de protocolo
    não mostram a justificativa;
  - anexos (`participacao.anexo`): a Casa anexa à resposta e o requerente ao próprio pedido, nos 10 minutos seguintes
    ao ato, até 5 arquivos de 10 MB, tipos fechados e conferidos pelo conteúdo; baixam só a secretaria e o dono do
    protocolo; a secretaria retira um anexo errado, com motivo;
  - o upload (também o dos comunicados) confere o pedido antes de ler o corpo e tem teto de 4 envios simultâneos e de
    um por pessoa (`oplenario.interceptors/anexo-multipart`);
  - **todo upload passa por esse parser (04/10/2026, PRs #99 e #100)**, inclusive os documentos das contas, que antes
    usavam o multipart do Ring:
    - nome de arquivo de até 255 caracteres acentuados é aceito (o cabeçalho da parte tem teto de 2048 bytes; o padrão
      da commons-fileupload2 2.0.0-M5, 512, devolvia 400 "malformado");
    - tipo de mídia declarado acima de 200 caracteres é guardado como `application/octet-stream`;
    - rota nova de upload usa a fábrica, nunca o `multipart-params` do Ring, que não expõe o teto;
    - nas contas, o `?tipo=` é conferido antes de ler o corpo; a existência da prestação continua depois, e o caso 14
      da `fumaca-hml` depende disso (envia a uma prestação inexistente para exercitar o parser sem gravar nada).
  - **complemento da resposta:** depois da janela, a secretaria acrescenta um texto imutável a um protocolo já
    respondido (`participacao.complemento`); não mexe em estado nem prazo e reabre por 10 minutos a janela de anexos da Casa.
- **Login e gate (04/10/2026):** `?redirect=/.//host` não escapa mais da origem depois do login, e o middleware cobre
  todas as páginas autenticadas; `middleware.test.ts` mede a lista contra as páginas em disco, então página nova sem
  entrada no gate reprova.
- **Substituir anexo e reconciliação (04/10/2026, ADR-0022):**
  - a secretaria troca um anexo da Casa por outro num só ato, com motivo, a qualquer tempo
    (`POST /atendimento/…/anexos/:anexo/substituir`); o antigo vira "Substituído em…" e o novo ocupa a vaga;
  - `reconciliar-anexos [--ente] [--apagar-orfaos]` compara banco e object storage (balcão e comunicados): por padrão só
    relata; com a opção apaga só blob sem linha com mais de 24 h. Uso em `docs/27`, seção 8. Nunca rodou em produção.
- **Falta:**
  - antivírus nos anexos;
  - a prova de que o requerente viu a prorrogação, que espera o e-mail;
  - o manifestante anônimo não tem canal para receber a justificativa da prorrogação.

**3. Onda E da track FE — MEDIDA em 10/09/2026, e a cauda NÃO é trabalho mecânico.** A descrição
anterior deste item ("~13 telas com design pronto e zero rota Next… trabalho mecânico, o design já foi
pago") estava **errada**, e errada de um jeito caro: mandaria 12 agentes portar telas que não têm API
para chamar. A medição (14 telas × medir + refutar; ledger `docs/16`, seção Onda E) devolveu:

| Veredito | Telas |
|---|---|
| **BLOQUEADO — o domínio não existe no backend** (8) | `transparencia-fiscal` · `console-operador` ✅ · `console-operador-tenant` ✅ · `livro-atas` ✅ · `audiencia-publica` ✅ · `julgamento-contas` ✅ · `trilha-auditoria` ✅ · `observabilidade-ia` ✅ |
| **PARCIAL** (4) | `dados-abertos` ✅ · `calendario` ✅ · `vereador-estatisticas` ✅ · `notificacoes` ✅ |
| **PORTÁVEL** (1) | `status` ✅ — e só porque o design é texto fixo, sem binding |
| **JÁ FEITA** (1) | `perfil-vereador-publico` (a lista anterior a dava como pendente) |

✅ = **entregue** na branch `onda-e-cauda`. As 3 fatias entregáveis foram feitas: `/status` (pública),
`/calendario` (interno, sessões + prazos de compliance) e o incremento de `/notificacoes`.

**Demanda do stakeholder (30/09/2026) — parecer jurídico ENTREGUE (fatia 1):** pesquisa em [`docs/29`](docs/29-pesquisa-parecer-juridico-nas-camaras.md)
(≈110 Regimentos lidos: o parecer jurídico é opinativo e só existe onde o Regimento prevê — Baturité não prevê; o gate
real da pauta é o parecer da comissão) e [ADR-0019](docs/adr/0019-parecer-juridico-e-o-caminho-da-materia-ate-a-pauta.md),
aceita. Entregue: papel `juridico` (com qualificação e OAB, concedido em `/administracao`), pedido de parecer (matéria ou
consulta avulsa), fila `/juridico`, parecer assinado e **imutável** (correção = substituição), portal só depois da
deliberação, e o caminho da comissão por tela (encaminhar às comissões, designar relator). **Fatias 2, 3 e 4 ENTREGUES
(30/09/2026):**
- **Fatia 2a — nota da IA vira rascunho do advogado:** a nota técnica chega à fila `/juridico` quando a Casa tem
  jurídico ativo; "Usar como rascunho" abre um parecer do advogado. `publicar_ao_assinar` antecipa o portal, por Casa.
- **Fatia 2b — copiloto do relator:** capacidade `relator.analisar` no satélite, painel nos dois editores de parecer
  com citação conferida. O relator pede o parecer jurídico em `/parecer/[id]/redigir`.
- **Fatia 2 — o agente propõe:** pedir parecer, encaminhar às comissões e designar relator. Assinar continua só pela
  tela.
- **Fatia 3 — publicar a pauta:** regra da Casa (quem publica, antecedência), versão congelada com avisos sem bloquear,
  republicação justificada, portal `/portal/casa/[ente]/pautas`, selo na TV, `publicar_pauta` no catálogo.
- **Fatia 4 — etapa obrigatória:** fato `tem_parecer_juridico_assinado`, desligado por padrão, mais o carimbo
  `STUB-ICP-v0` do parecer.
- **Falta, de propósito:** pedido sobre emenda, autorização fina do relator e verificação de vigência do dispositivo
  citado. Ver *Materialização* na ADR.
- **As perguntas ao Rigoni foram decididas pela pesquisa (02/10/2026):** o código já atende; ver o fim da ADR.

**Audiência pública e julgamento das contas ENTREGUES (03/10/2026, [ADR-0021](docs/adr/0021-audiencia-publica-e-julgamento-das-contas.md)):**
as recomendações de [`docs/28`](docs/28-proposta-audiencia-publica-e-julgamento-de-contas.md) confirmadas e implementadas.
- **Audiência pública:** tipo de sessão `audiencia_publica` (não delibera, não exige quórum, aceita inscrição do
  cidadão), promovida por comissão, com tema e finalidade (temática, metas fiscais, LDO, LOA, PPA).
  - O cidadão se inscreve pelo gov.br no portal (`/portal/casa/[ente]/audiencias/[sessao]`, protocolo `AUD-…`); a Mesa
    inscreve presencialmente, chama, cronometra e encerra em `/sessoes/[id]/audiencia`.
  - Sessão que não delibera não abre votação (vale para solene e especial). A audiência não conta na assiduidade nem é
    a "sessão anterior" da leitura de ata.
- **Julgamento das contas:** `prestacao_contas` em `legislativo` (`/contas`). Registrar a do Prefeito protocola o PDL.
  - Notificação e defesa: a pauta só aceita o PDL depois do prazo de defesa ou da defesa juntada.
  - A votação sobre o parecer prévio é travada pela regra-dado `contas_prefeito`: 2/3 dos membros, nominal. O
    resultado sai em palavras ("O parecer prevalece: 12 votos pela rejeição, eram precisos 14.").
  - Contas da Mesa: só acompanhamento. Prazos por Casa em `/administracao`. Portal `/portal/casa/[ente]/contas`.
- **Motor:** a primeira regra com gatilho em produção.
  - `audiencia_metas_fiscais`: LRF art. 9 §4 — fim de maio, setembro e fevereiro.
  - `julgamento_contas_prefeito`: o prazo da LOM por Casa.
  - Avaliadas pelo gatilho do host (`gatilho_compliance.clj`) ao ler o painel e depois dos atos. Nada anterior ao
    vínculo da Casa à regra.
- **Falta, `[GAP]`:** datas de LDO/LOA/PPA por LOM; prazos de defesa e julgamento por LOM (há padrão editável); efeito
  do vencimento; comunicação do resultado ao TCE; contas da Mesa com deliberação (confirmar com o jurídico).

`trilha-auditoria` ✅ **entregue em 29/09/2026** ([ADR-0017](docs/adr/0017-trilha-de-auditoria-da-casa.md), aceita):
- **o que é:** o módulo `auditoria`, uma corrente selada por Casa (particionada por mês, append-only; só o IP pode virar
  NULL depois de 6 meses);
- **o que registra:** o interceptor global grava escrita, negação, entrada e a leitura da trilha;
- **quem vê** (`/auditoria`): o `auditor` (controle interno, servidor, concedido pelo `admin_ente` em `/administracao`)
  vê a Casa inteira, confere a cadeia e exporta CSV; o `admin_ente` vê os acessos; cada pessoa vê a sua;
- **a âncora pública:** o selo do dia, ancorado na atuação da Operação e publicado em Dados abertos do portal;
- **tentativa antes, desfecho depois (04/10/2026, adendo da ADR-0017):** toda escrita grava uma "tentativa" antes do
  handler e o desfecho depois; ato cujo desfecho não gravou aparece como "ação iniciada, desfecho não registrado" na
  tela, no CSV e na conferência. Por padrão a trilha fora do ar NÃO para a Casa (erro no log, o ato segue);
  `AUDITORIA_EXIGIR_TENTATIVA=true` troca para 503. Custa uma gravação a mais na corrente por escrita (21 escritas
  simultâneas: 132–184 ms contra 80–152 ms, medido com handler de teste, não com o voto real);
- **falta:** o resumo legível por ação ainda é incremental; login, jobs e consumidores do outbox não passam pela
  tentativa — ver *Materialização* na ADR.

**O que sobra não é FE adiado, é domínio ausente** — e três dessas dependem de decisão, não de código:
- `transparencia-fiscal` — o **documento-mestre §289/§404 veta** produzir o dado fiscal: isso é do sistema
  contábil, e entra só a camada de publicação, por consumo. É `[GAP]` de conector externo (qual sistema,
  qual protocolo), da mesma família do layout SIM do TCE-CE. **Não é backlog de engenharia.**
- `console-operador` (+tenant) — ✅ **entregue em 27/09/2026** (ADR-0016, item 2 acima): lista, provisionar e a ficha
  da Casa com o handoff e a atuação selada. Os blocos de acesso de suporte e flags por Casa esperam fatia própria.
- `observabilidade-ia` — ✅ **entregue em 29/09/2026** no console do operador (`/operacao/ia`, 24 h / 7 dias): o
  satélite agrega o registro da Camada de Confiança de TODAS as Casas (`GET /v1/observabilidade`) — execuções, p50/p95,
  custo, o que não rodou e por quê, por capacidade e por fornecedor/modelo, série por hora — e o core expõe só ao
  operador (`GET /operacao/ia`); IA fora = `disponivel: false`, nunca 500. Sem texto e sem Casa identificada. Cobre o
  modelo de linguagem; transcrição e busca não passam pelo registro.

`livro-atas` ✅ **entregue em 28/09/2026**, destravada pela ata-IA: `/atas` (interno; a ata de sessão secreta só para a
secretaria) e `/portal/casa/[ente]/atas` (cidadão; só sessões públicas). A vigente aberta como folha, retificação como
versão legível, a leitura no plenário e o SHA-256 do texto; selo "Publicada", nunca "Aprovada" — o sistema não registra
aprovação da ata. O assistente lê atas pela ferramenta `ata_da_sessao` (só sessões públicas).

`dados-abertos` ✅ **entregue em 28/09/2026** (`/portal/casa/[ente]/dados-abertos`, Decreto 8.777 + LAI art. 8 §3):
proposições, legislação e votos nominais em CSV inteiro com dicionário de colunas; despesas e presença por sessão ficam
como em-breve com o motivo (dado fiscal é do sistema contábil; presença segue a nota docs/14).

`vereador-estatisticas` ✅ **entregue em 28/09/2026** como "Minha atuação" (`/vereador/atuacao`, aba Perfil do app do
vereador): os números do perfil público + pareceres como relator + votos por opção; as proposições agrupadas pelo estado
do rito da Casa (o estado não está mais morto, mas não tem categoria para os 5 grupos fixos do design). Fora por falta de
dado: percentual (proibido), presença por mês, ausência justificada e filtro de período.

**4. A verificação independente começou — CI destravado na infra, 3 testes `demo.*` faltam.**
*(atualizado 15/09/2026, tarde)* O repositório **tem remote** (`github.com/GondwanaDEV/oplenario`) e
`main` está publicada. O `.github/workflows/ci.yml` **executou** e o buraco de verificação começou a
fechar, em duas etapas de infra:
- **MinIO:** o namespace `minio/*` **sumiu do Docker Hub** (API de tags do Hub → 404 "object not found";
  `library/postgres` → 200). Não era rate-limit nem `:latest` faltando — a imagem não está mais lá.
  Consertado: `docker-compose.yml` puxa de `quay.io/minio/minio` (mesmo registro do keycloak).
- **Valkey:** o CI não subia o `valkey`; o teste do backplane (conecta em `redis://localhost:6379`)
  dava `Connection refused`. Consertado: `ci.yml` sobe `valkey` + readiness.
Com isso o CI passou de "morre em 15s sem puxar imagem" para **rodar a suíte inteira**. Os 3 erros
finais eram `oplenario.demo.*_test` (`participacao_test`, `sessoes_test`) dependendo de `acervo/semear!`
ter rodado antes no MESMO banco — premissa de ordem que o runner não garante; consertado tornando cada
teste **auto-suficiente** (semeia o acervo ele mesmo, idempotente), sem afrouxar asserção.
**RESULTADO: o CI chegou a VERDE (run #6, 2352 testes / 6341 asserções); a flakiness que restava foi
diagnosticada e CONSERTADA.** Runs de commits só-de-docs alternavam verde/vermelho (#6✓ #7✓ #8✓ #10✗
#11✓ #12✗) — ~⅓ vermelho, sempre 13 erros em `folha-congelamento-test`. Causa raiz (não era carga/pool):
a tabela de referência `cadastros.municipios` não é semeada por migration; todos os testes que criam ente
semeiam o município via `inserir-municipio!` — menos `folha_congelamento_test`/`folha_controller_test`, e
como o **kaocha randomiza a ordem**, quando um deles rodava antes de um seeder o FK `ente_municipio_ibge`
estourava. Consertado: os dois passam a semear `2304400` (idempotente), sem afrouxar asserção. Detalhe e
procedência em `docs/16`, seção "Progressão do CI". Já existe um PR ([#1](https://github.com/GondwanaDEV/oplenario/pull/1)) com os consertos de
infra + a re-verificação; abrir/mergear é decisão do Daouda. Um plano de teste completo de toda a
plataforma está em **`docs/20`** (4 métodos, ~8 personas reais, ondas T0–T6), e a Trilha 3 (`e2e/t3/`, 9
specs de browser autenticadas) cobre boa parte da Onda T1 e **é gate do CI desde 02/10/2026** (`t3-e2e` sem
`continue-on-error`; voto e presença ao vivo fora da quarentena SSE porque o placar hidrata por snapshot; só as
3 sondas de cockpit seguem opt-in em `E2E_T3_SSE`). A presença do próprio vereador, que não hidratava por
snapshot (revisita >5 min voltava a pedir presença), foi consertada em 04/10/2026: GET
`/sessoes/:id/presenca/minha` + hidratação no page-load e na reconexão do cockpit. Detalhe e procedência em `docs/16`, seções
"Progressão do CI" e "A Trilha 3 vira gate".
**Testes do frontend que dependiam de tempo (04/10/2026, PRs #97 e #98):** o job `frontend` reprovava "do nada"
porque o `findBy`/`waitFor` drena com `setTimeout(0)` e o React pinta com `setImmediate`. Varredura com detector
dinâmico (2757 testes), 8 corridas consertadas sem afrouxar asserção. **Teste novo de tela assíncrona: rodar
`docker compose exec frontend npm run test:atraso -- <arquivo>` antes do merge** (opt-in, não é gate; os falsos
vermelhos conhecidos estão no cabeçalho de `apps/frontend/vitest.atraso.setup.ts`). Detalhe em `docs/16`, seção
"Testes do frontend que dependiam de tempo".

**Tempo real e relay (04/10/2026):**
- A mensagem do canal no Valkey deixou de ser Nippy: é EDN de dado puro com allowlist (`tempo_real/codec.clj`). Antes,
  quem escrevesse no Valkey escolhia a classe que o backend instanciava.
- Senha por `VALKEY_PASSWORD` e TLS por `rediss://`. Sem senha em produção o sistema SOBE e registra erro a cada boot.
  **Passo do operador, pendente:** pôr a senha no Dokploy, conferir que o aviso sumiu e só então ligar
  `VALKEY_EXIGIR_SENHA=true` (`docs/27`, seção 7).
- Relay: evento sem Casa não trava mais o barramento no consumidor `integracao_ia` (`paineis` e `legislativo` já
  tinham a guarda).
- Telão e cockpit: encerramento de votação e mudança de estado da sessão perdidos numa queda longa são reconciliados
  por HTTP. O resultado de uma votação já ENCERRADA segue sem rota de leitura.

**Exploratório de 12/09: retriagem fechada em 05/10/2026.** 84 achados · 15 abertos · 69 fechados · 0 sem decidir.
Lista com `arquivo:linha` em `docs/16`, seção "Retriagem do exploratório". Os mais graves em aberto:
- o mesmo `secretario` abre, vota, encerra e emite o autógrafo (decisão do Daouda);
- gerar remessa ao TCE sem rota; convocação oficial inexistente;
- a rota da Mesa aceita voto de quem está ausente justificado (confirmar se é regra da Casa).

**Frentes de 05/10/2026 (do exploratório), todas na `main`:**
- **Portal do cidadão:** `/portal/casa/[ente]/leis` (lista filtrável e ficha da norma), `/vereadores` (quem está em
  exercício) e `/votacoes` (votações encerradas de sessões públicas, com o voto por vereador quando nominal). A regra
  de sessão secreta da página de votações é a do livro de atas e é conferida na lista e no detalhe. A raiz `/` deixou
  de dizer "em construção".
- **Revogar acesso (adendo da ADR-0005):** em `/administracao`, o `admin_ente` revoga, com motivo, o acesso de
  vereador, controle interno e jurídico. A linha revogada fica como histórico e conceder de novo abre outra. Sem papel
  ativo na Casa, o vínculo é encerrado e a sessão cai na chamada seguinte; as credenciais do agente da pessoa caem
  junto. `admin_ente` e `secretario` não são revogáveis por essa rota. O Keycloak não é tocado.
- **Promulgar e publicar a norma:** em `/pos-aprovacao/:id`, a matéria sancionada (ou com veto derrubado) vira lei
  com número da espécie no ano, URN LexML e o texto do autógrafo; registrar a publicação (o veículo é a prova) emite
  `norma.publicada` e a lei vai ao portal. Quem promulga e o prazo seguem `[GAP]` por LOM.
- **Pauta:** a mesma matéria não entra duas vezes ativa na pauta de uma sessão (409). O índice único vale para itens
  criados depois da migration; os antigos são cobertos por checagem em código e não foram alterados.
- **Telão:** o placar nominal mostra o nome parlamentar, nunca o prefixo do UUID.
- **Desfecho da matéria (retriagem 18 e 30):** cada ato depois do plenário (aprovada/rejeitada, autógrafo, sanção ou
  veto, apreciação do veto, promulgação) emite `proposicao.desfecho-registrado`; o portal o põe em "Por onde a matéria
  passou" e guarda o último em `transparencia.materia.desfecho`. O selo público muda a partir do autógrafo ("Sancionada",
  "Virou lei"); a votação aparece na frase "Última votação em plenário" e não mexe no selo. A ficha interna lê os mesmos
  atos pela rota de pós-aprovação. A migration 20261005000210 reconstrói os atos anteriores.
- **Cockpit com duas sessões em curso:** `/votar` abre a aberta mais recente e oferece a troca para a outra (`?sessao=`);
  `GET /meu/sessao-atual` lista `sessoes-vivas`. A Trilha 3 abre o cockpit com a sessão explícita.
- **Dashboard da Mesa:** "em tramitação" vem do rito (`template_estado.terminal`, levado pelo evento
  `proposicao.transicionou`); a sessão em curso aparece primeiro, com a hora no fuso da Casa; as remessas ao TCE
  aparecem no card de compliance.
- **Votos:** `votos.vereador_id` segue sem FK (ADR-0001 §6 proíbe FK entre schemas); a integridade é a recusa na
  aplicação, provada com Postgres real, mais um CHECK contra UUID nulo.
- **Segunda rodada de 05/10 (PRs #143 a #153):**
  - **Compliance:** aceitar a remessa cumpre a obrigação. O gatilho do host (`gatilho_compliance.clj`, parte
    `:remessas`) reavalia a competência no aceite e, antes do sweep, em toda leitura do painel. O módulo `compliance`
    continua sem emitir evento.
  - **Voto por vereador só de sessão pública:** CSV de dados abertos, perfil público e "Minha atuação" leem
    `voto_parlamentar` por `parlamentar/da-votacao-publica` (fail-closed); leitura nova que esqueça reprova o
    `voto_publico_estrutura_test`.
  - **Tramitação pública:** `transparencia.materia_movimentacao` (por evento; os eventos `proposicao.protocolada` e
    `transicionou` carregam o rótulo da etapa) e "Por onde a matéria passou" na ficha pública. Histórico incompleto
    aparece como "disponível a partir de".
  - **Portal:** lista de leis paginada (20 por página), `tem-texto` na norma (sem botão de baixar quando não há),
    `/votacoes?materia=` ligando a ficha às votações, menu com destinos distintos, acompanhamentos com link, exemplo
    de protocolo do e-SIC correto, título da norma no lugar da URN, notificação com sigla e fase em palavras.
  - **Proposições:** um só rótulo de estado para ficha, lista e quadro (`rotularEstado`); `em_pauta` é "Em Plenário";
    estado desconhecido cai em "Em tramitação"; autor vereador ligado ao cadastro; filtro com as 8 espécies.
  - **Sessão:** item numerado por posição dentro da fase (só apresentação); nome no lugar do prefixo de UUID na
    chamada e nas próximas folhas; folha com numeração contínua; fila da tribuna sem quem já falou.
  - **Pós-aprovação:** o prazo de sanção ou veto é informado ao gerar o autógrafo (opcional, sem padrão; não se
    corrige depois; prazo no passado → 400).
  - **Dashboard da Mesa:** gráfico colorido por posição, cartões com link, plural de verdade, denominador na vitrine.
- **Falta:**
  - autógrafo, sanção e promulgação na linha do tempo da ficha da matéria (a rota da ficha não os devolve);
  - o vereador ver o próprio voto de sessão secreta (hoje "Minha atuação" usa a rota pública);
  - a faixa "Onde está a matéria" ainda depende do nome do estado (nenhuma rota devolve a ordem das etapas do rito);
  - não vistos em browser: o formulário do prazo do Executivo, o telão e a TV ao vivo, a folha em PDF com nome;
  - link no app para a página de conta do Keycloak, onde a pessoa troca o próprio e-mail (o `admin_ente` só troca o
    de quem nunca entrou, ao reconceder);
  - vistas em browser (tema escuro, 800 px, Casa demo): leis, vereadores, votações e o detalhe, a raiz, "Quem tem
    acesso" em `/administracao` e o dashboard da Mesa. A passada achou três defeitos visuais, consertados no PR #137
    (cargo da Mesa como chave crua, cabeçalho de votações sem estilo, botões das leis sem variante). Não vistos: 375 px,
    tema claro, o ato de revogar e o telão com o nome.

**Dívida técnica conhecida (não bloqueia):** assinatura ICP-Brasil ainda é `STUB-ICP-v0`; registro de
passkey depende de secure context (carry de ambiente); PWA cerimonial e app Flutter parqueados atrás
de gatilho de cliente validado; migratus deixa lock `-1` preso em crash de migration (limpar manual).

**`[GAP]` que dependem de informação externa, não de engenharia:** layout físico do arquivo SIM do
TCE-CE; prazos corridos vs. úteis (LAI 20+10, LGPD, ouvidoria 13.460); rito de sanção/veto por LOM;
layout do Diário Oficial; admissibilidade de emenda de plenário; e os outros 26 TCEs (forma validada
só contra o CE, rollout demand-pulled).

**Tipografia trocada (05/10/2026, PRs #121 e #125, em produção):** Mona Sans (título e corpo) + Geist Mono (dados), no
lugar de Plus Jakarta Sans + Inter + JetBrains Mono.
- A hierarquia vem do eixo de **largura** da Mona Sans: `--largura-titulo` (112%) no `h1`, `--largura-densa` (92%) na
  tabela e nas grades nominais.
- As fontes saem do próprio front (`apps/frontend/src/app/fontes.css` + `public/fontes/`), sem Google Fonts. As telas
  HTML do design system seguem no Google Fonts.
- Três armadilhas:
  - o `tabular-nums` da Mona Sans corta o zero; número grande em `--display` usa `var(--numero-destaque)`;
  - `h1` com texto longo (ementa) leva `.titulo-longo`, senão vira um bloco estreito de linhas curtas;
  - `font-stretch` só vale em fonte com eixo de largura.
- Regras em `produto/design-system/o-plenario/LINGUAGEM-VISUAL.md`, seção Tipografia.

**Se voltar a fazer design:** o protocolo por tela segue valendo — linkar `produto/design-system/o-plenario/sistema/`,
usar arquétipo já provado, reusar receitas de `PADROES-DE-COMPOSICAO.md`, passar pelo
`GUIDELINES-CHECKLIST.md` como gate (esp. §5.1: branco-sobre-telha → `--telha-fundo`; fill de gráfico
escuro no escuro → clarear; âmbar → `--aviso-texto`), **medir AA em pixel composto, um tema por
chamada com flush**, e commitar uma tela por commit. Servidor: `python3 -m http.server 8755` na raiz;
galeria em `componentes.html`. **Fronteira do descarte:** só `produto/design-system/` é design — o
resto de `produto/` (01–14) é o input do processo e fica intacto.

---

## 4. Como trabalhamos (protocolo)

**Detalhe completo em `docs/01-metodologia.md`.** Resumo operacional:

- **Ferramentas fixas deste projeto (decisão do Daouda Traore, 20/06/2026):** todo trabalho de
  **discovery e engenharia** (pesquisa de mercado, code review, build, etc.) usa o plugin
  **`ecc`** (suas skills/subagents); o **design de UI/UX** usa **dois consultores complementares** (decisão Daouda Traore, 21/06/2026 — substitui o
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
- **Confirmação explícita antes de prosseguir:** protocolo "Confirmo" / "Confirma?". Daouda Traore
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
| `apps/` | **Monorepo — os componentes de código** (decisão Daouda Traore, 27/06/2026): `apps/backend/` (Clojure, o monólito modular — internamente em `STRUCTURE.md`), `apps/frontend/` (Next.js — porta o design-system), `apps/ia/` (Python — o satélite de IA, ADR-0006), `apps/mobile/` (Flutter — diferido, PWA-first na V1). **Rodar/testar o backend é de dentro de `apps/backend/`** (ver memória `oplenario-rodar-local`). |
| `e2e/` | **Harness de browser-e2e (Playwright) do Portal do Cidadão** — projeto Node isolado (`package.json` próprio, só `@playwright/test`), fora de `apps/` porque não é um componente deployável: atravessa a stack inteira (frontend+backend+DB) já de pé. Comando canônico `./e2e/rodar.sh` (semeia via `seed_demo.clj` em container efêmero + roda o Playwright no container oficial). Nunca muta o mount vivo de `apps/frontend`. |
| `prototipos/` | **Referência histórica (não é produto)** — `motor-dsl/` (protótipo do avaliador da DSL) e `governanca-ia/` (arco da porta de IA). Superseded por `apps/backend/`; mantidos para consulta. Não buildados pelo CI. |
| `documento-mestre-camaras.md` | **Single source of truth.** Decisões consolidadas. Em conflito, prevalece. **A versão vive no cabeçalho + §24, nunca no nome do arquivo** (evita trocar referências a cada bump). |
| `arquitetura/` | **Parte do SSOT** — as subseções densas da §22, extraídas (v1.39), uma por subseção: `22-3-contrato-core-ia` · `22-4-dados-legislativo` · `22-5-auth` · `22-6-sessao-plenaria` · `22-7-motor-compliance` · `22-9-stack` · `22-10-monolito`. Cada arquivo abre com cabeçalho de SSOT; **versão do conjunto governada pelo §24 do doc-mestre**, nunca por arquivo. |
| `docs/adr/` | **ADRs** — decisões de arquitetura navegáveis (formato curto). **[ADR-0001](docs/adr/0001-estrutura-de-pastas-e-silhueta-de-modulo.md) = estrutura de pastas + silhueta de módulo** (autoridade da forma do `apps/backend/`: `wire/in`·`wire/out`, sem `port/`, recursos via Component, sem ORM). **Enforçada por máquina** — `estrutura_lint_test` (CI falha se `port/`/`schema/` voltarem) + `arquitetura_test` (import-lint §22.10). Toda decisão estrutural nova entra como ADR. |
| `docs/11-plano-execucao-engenharia.md` | **O plano de engenharia** — as 8 fases F0–F7, os marcos M1–M6 e os tracks paralelos (FE, IA). **F0–F7 estão todas mergeadas**; o que resta aberto está em §3 acima. Brief de origem em `docs/10`. |
| `docs/13-plano-track-fe.md` | **O plano da track de frontend** — Ondas A–E, marcos MFE-1..4. **A–D fechadas; a Onda E (cauda) não foi iniciada.** |
| `produto/` | Trilha de produto/comercial **completa** (01–14): JTBD, decomposição das 113 features / 12 módulos, completude. **`produto/design-system/`** = o design system (47 telas, `LINGUAGEM-VISUAL.md`, `PADROES-DE-COMPOSICAO.md`, `GUIDELINES-CHECKLIST.md`). |
| `docs/00-estado-e-roadmap.md` | **Histórico da fase de design** — estado do cursor e roadmap de §22.7. ⚠️ Não reflete a engenharia: lido isolado, dá a impressão de que o projeto ainda está em discovery de arquitetura. O estado vivo é o §3 deste arquivo. |
| `docs/01-metodologia.md` | Método de trabalho (eixo a eixo, confirmação, versionamento, escopo). |
| `docs/02-eixo-A-fechado-rascunho.md`, `docs/03-proxima-sessao-eixo-C.md` | Rascunhos de origem dos eixos de §22.7 — **consolidados no doc-mestre**; valor só arqueológico. |
| `docs/04-nome-e-marca.md` | Decisão de nome (**O Plenário**), tagline, e os checks pendentes (domínio + INPI). |
| `README.md` | Orientação geral da pasta (visão humana). |

---

## 7. Como rodar e verificar

- **Subir a stack:** `cd apps/backend && docker compose up -d --build` (migrate → app; postgres,
  valkey, minio; keycloak e mailpit atrás de `--profile auth`). Portas via `.env` — neste dev:
  frontend :3000, backend :8888, Postgres :5544, MinIO :9100. Detalhe na memória `oplenario-rodar-local`.
- **Mandato Docker (sem exceção):** nunca rodar `node`/`npx`/`clj` direto no host — tudo em container.
  Testes do backend rodam em container efêmero de Clojure; os do frontend, dentro de `oplenario-frontend-1`.
- **Nunca mutar um mount vivo.** O compose monta `../frontend:/app` para o `next dev`; um container
  efêmero que escreva ali derruba o frontend (já aconteceu). Monte só o necessário, prefira `:ro`,
  mantenha deps/caches em volume de container. Memória: `oplenario-e2e-container-guardrails`.
- **Browser-e2e:** `./e2e/rodar.sh` da raiz — semeia dado real via `seed_demo.clj` em containers
  efêmeros, espera a projeção do relay e roda o Playwright contra a stack de pé.
- **Uma branch por frente,** mergeada em `main` e fechada; a frente seguinte abre branch nova.
- **TDD por fatia** (red → green) e **revisão `ecc` antes do merge** — é o trilho que sustentou F0–F7.
