# 25 — Desenho: o lugar da IA na plataforma (IA como núcleo, catálogo de ações, MCP)

> **Status: sessão de desenho EM ANDAMENTO** (aberta em 26/09/2026). Eixo por eixo, com "Confirma?" em cada.
> Decisões marcadas **CONFIRMADO** foram confirmadas na sessão; a consolidação no `documento-mestre-camaras.md`
> (e em `arquitetura/`) só acontece com o **"Confirmo" do Daouda** sobre o conjunto, ao final dos 8 eixos.
> **Para retomar:** leia §1–§3, veja a tabela do §4 e continue do primeiro eixo que não está CONFIRMADO.

## 1. Por que esta sessão existe

O pedido do stakeholder (Baturité, set/2026 — requerimento redigido por IA, consulta à Lei Orgânica/Regimento,
ata lida por IA) trouxe à tona uma preocupação do fundador: **a IA não pode entrar na plataforma como coisas
avulsas**; a plataforma será fortemente orientada a IA, **incluindo um MCP da plataforma**.

A primeira resposta (a "Onda 0": porta de inferência, corpus, pipeline de áudio) era uma **ordem de obra**, não
uma resposta à pergunta "onde a IA mora". Esta sessão responde à pergunta.

## 2. O que já está decidido e NÃO se reabre

- **Invariante 3** — IA como plataforma (camada horizontal), não como feature (§22.1).
- **Satélite Plataforma de IA** separado, Python (§22.2) + contrato core↔IA (§22.3: topologia síncrono/assíncrono/
  streaming, eventos de integração, propriedade de dados por artefato, 6 categorias de erro).
- **Porta de inferência vendor-agnóstica** + failover vendor→vendor (§22.9 Eixos 10 e 13); ASR e embeddings self-host.
- **Filtro de governança fail-closed** antes do LLM externo (`prototipos/governanca-ia/`: proveniência de sigilo,
  chokepoint único, degradação, auditoria sem conteúdo).
- **Camada de Confiança** (§16.8): IA propõe, pessoa revisa e assina; **R-IA-1** — "IA fora, siga manual", o ato
  nunca bloqueia.

## 3. O buraco que esta sessão fecha

Tudo acima trata a IA como **fábrica de artefatos** (plataforma → IA → transcrição/ata/resumo/embeddings). Ninguém
desenhou a IA como **ator sobre a plataforma** — consultar estado, preparar e propor ações em nome de alguém. Sem
isso, cada copiloto abriria seu próprio caminho privado até os dados (o "avulso").

A arquitetura existente ajuda: Invariante 5 (core ↔ presentation) faz do MCP só mais uma porta de entrada;
`policy.check` in-domain (`kernel/autorizacao.clj`) dá ao agente exatamente a permissão da pessoa; eventos +
audit (Inv. 2 e 10) tornam toda ação de agente rastreável; esquemas Malli já geram TS (`codegen/`) e podem gerar
JSON Schema para ferramentas.

## 4. Os 8 eixos — estado

| # | Eixo | Estado |
|---|---|---|
| 1 | Lugar da IA | **CONFIRMADO — B**: produz artefatos **e** age sobre a plataforma |
| 2 | Catálogo de ações | **CONFIRMADO — B** + 2 regras (§6) |
| 3 | Identidade do agente | **CONFIRMADO** — 3.1 (b) + 3.2 a 3.5 (§7) |
| 4 | Fronteira do ato | **CONFIRMADO** — 4.2 (B) + 4.1, 4.3–4.6 (§7a) |
| 5 | Onde roda o agente | **CONFIRMADO** — 5.1 (b), 5.2 (b) + 5.3–5.7 (§7b) |
| 6 | MCP externo | **CONFIRMADO** — 6.1 (B), 6.3 (i)+(ii) + 6.2, 6.4, 6.5 (§8) |
| 7 | Conhecimento (LOM/RI/leis em camadas por município) | **CONFIRMADO** — 7.2 (B) + 7.1, 7.3–7.7 (§8a) |
| 8 | Qualidade e custo | Direção confirmada; **sub-decisões em debate (§8b)** |

## 5. Eixo 1 — Lugar da IA · CONFIRMADO (B)

Opções debatidas: **A** manter só fábrica de artefatos, MCP como produto à parte depois (é o caminho do avulso; MCP
vira retrofit) · **B** fábrica **e** ator, com catálogo único servindo telas, agentes internos e MCP · **C** chat
como interface principal (contraria Aposta 2; servidor precisa de tela determinística para ato legal; descarta as
47 telas desenhadas).

**Decisão: B.** Regra que torna "IA como núcleo" verificável: **toda ação nova nasce com duas bocas — a tela e a
ferramenta do agente.** Os copilotos da própria plataforma são o **primeiro cliente** do catálogo; uma feature de IA
que precise de algo fora do catálogo **acrescenta ao catálogo**, nunca abre caminho próprio.

## 6. Eixo 2 — Catálogo de ações · CONFIRMADO (B)

Situação medida no código: rotas têm **forma de tela** (a mesma operação duplicada por papel —
`/legislativo/pareceres/:id/emissao` e `/meu/pareceres/:id/emissao`; rotas que só servem tela, como `/meu/painel`,
`/meu/requerimentos/previa`); a ação real mora nos `controllers` (onde roda `policy.check`), com esquema Malli.

Opções: **A** uma ferramenta por rota HTTP, gerada (≈200 ferramentas com forma de tela, duplicadas — agentes erram
com ferramentas demais e parecidas) · **B** uma ferramenta por **ação de domínio**, num catálogo declarado · **C**
poucas ferramentas por tarefa, feitas à mão (volta ao avulso).

**Decisão: B.** Cada entrada do catálogo declara:
- nome e **descrição em português escrita para o agente**;
- esquema de entrada/saída — os **mesmos Malli** de `wire/in`/`wire/out`, convertidos a JSON Schema;
- **classe do ato**: `leitura` | `rascunho` | `ato` (insumo do Eixo 4);
- papéis que podem usar. **Uma** ferramenta atende vereador e servidor; o `policy.check` decide.

**Regra 1 — granularidade:** uma ferramenta = **uma ação que uma pessoa da Câmara diria em voz alta** ("protocolar
requerimento", "consultar a tramitação da proposição X", "listar a pauta da próxima sessão"); não uma tabela, não
uma tela. Consulta agregada de tela só entra se alguém realmente faria aquela pergunta.

**Regra 2 — fonte única enforçada:** o catálogo é a fonte; rota HTTP e ferramenta referenciam a mesma entrada.
**Rota nova sem entrada no catálogo quebra o CI** (como o `estrutura_lint_test`), salvo se marcada "só-tela" com
motivo.

**Migração:** não se reescrevem os 13 módulos. Regra vale para o novo; o existente entra conforme os agentes
precisarem — começando pelas consultas do legislativo (situação da matéria, tramitação, pauta).

**Conjuntos por público** (vereador, secretaria, cidadão) ficam sobre o catálogo e **expõem menos** do que a
permissão da pessoa — é o "escopo reduzido" do Eixo 3.

## 7. Eixo 3 — Identidade do agente · CONFIRMADO (26/09/2026)

Direção confirmada: o agente age **em nome de** uma pessoa, com **escopo reduzido**, auditado como **"Fulano, via
agente X"**. Ponto de partida no código: o ator é `{:identidade-id :papeis :ente-id}` e já existe `ator-sistema`
para jobs (`kernel/autorizacao.clj`).

Sub-decisões (todas confirmadas):

- **3.1 Tipos de principal.** (a) só delegado · (b) delegado **+ agente institucional da Casa** (sem pessoa por
  trás — ex.: conferir todo requerimento protocolado contra a LOM/RI), restrito a `leitura` + `rascunho`, com todo
  resultado caindo numa fila para uma pessoa. **Confirmado: (b).**
- **3.2 Permissão efetiva = interseção**, nunca união: o que a pessoa pode **agora** ∩ conjunto de ferramentas do
  agente ∩ classes concedidas. Avaliada **a cada chamada** (mandato encerrado ou vínculo suspenso derruba o agente
  na hora, como já acontece com as telas).
- **3.3 Concessão.** Agente interno invocado pela pessoa dentro de uma tela: usa a sessão dela, limitado ao
  conjunto daquela tela, sem consentimento extra (ela clicou). Agente institucional: concedido pelo `admin_ente`.
  Agente externo (Eixo 6): consentimento explícito, com validade e revogação.
- **3.4 Mecanismo.** Token com a pessoa como sujeito e o agente como ator (token exchange, RFC 8693, no Keycloak que
  já temos — confirmar suporte na versão em uso). No core, o ator ganha `:via {:agente-id :execucao-id}`; token de
  agente só é aceito em operações que estão no catálogo.
- **3.5 Auditoria.** Escritas: sempre no audit com pessoa + agente + execução + ferramenta + classe. Leituras: mesma
  regra de hoje para as telas (acesso a dado pessoal/sigiloso vai ao audit), acrescida da identificação do agente;
  o registro completo de ferramentas chamadas por execução fica no log de inferência do satélite (§22.3.4).

## 7a. Eixo 4 — Fronteira do ato · CONFIRMADO (26/09/2026)

Direção confirmada: o que o agente faz sozinho × o que só uma pessoa faz; defesa contra instruções escondidas em
texto de terceiros. As três classes vêm do catálogo (Eixo 2): `leitura`, `rascunho`, `ato`.

- **4.1 Definição de `ato`.** Tudo o que produz efeito institucional ou fala pela Casa para outra pessoa:
  protocolar, assinar, emitir parecer, tramitar/despachar, publicar (ata, resumo no portal), responder e-SIC,
  alterar pauta, enviar comunicação a terceiros. `rascunho` = algo que só o dono (pessoa ou setor) vê até alguém
  promover; sempre carimbado "produzido por IA" (proveniência, §16.8). `leitura` = livre dentro da permissão (3.2).
- **4.2 O que o agente pode fazer com um `ato`.** (A) nunca — prepara e manda a pessoa para a tela · (B) cria uma
  **proposta de ato** com o conteúdo exato; a pessoa confirma **na interface da própria plataforma** (não no chat
  do agente), com o mesmo ritual da tela (assinatura em 2 toques, step-up quando houver) · (C) executa sozinho
  sob pré-autorização ("pode protocolar requerimento de informação"). **Confirmado: B.** Confirmar fora do canal
  do agente impede que um agente (ou um LLM externo, Eixo 6) descreva uma coisa e assine outra.
- **4.3 Atos que o agente nem propõe** (pessoais e intransferíveis): **voto**, **registro de presença** e
  **condução da sessão ao vivo** (abrir/encerrar votação, conceder tempo). Continuam só pela tela, pela pessoa.
- **4.4 Classificação fail-closed.** A classe é dado no catálogo, revisada em PR; ação sem classe = `ato`.
- **4.5 Instruções escondidas em texto de terceiros** (e-SIC, participação cidadã, e-mail, PDF enviado,
  transcrição de fala em plenário):
  1. *Defesa principal — estrutural:* o agente só tem a permissão da interseção (3.2) e ato exige confirmação
     humana (4.2) → o pior caso de uma injeção é um rascunho ruim ou uma proposta que a pessoa recusa. Não se
     depende de o modelo "resistir".
  2. *Marcação de origem:* toda saída de ferramenta diz se o conteúdo é interno ou de terceiro. Execução que leu
     conteúdo de terceiro fica **contaminada**: a proposta de ato mostra ao confirmador "feita depois de ler
     e-SIC nº X"; e proposta que **leva para fora** dado restrito/sigiloso lido na mesma execução é bloqueada
     (defesa contra vazamento).
  3. *Delimitação no prompt* (conteúdo de terceiro entre marcadores, tratado como dado) — reforço, nunca a defesa.
- **4.6 Ligação com o que existe:** a pessoa que confirma um ato vindo de rascunho de IA assume a autoria
  ("revisado e assinado por"), como a Camada de Confiança já exige; R-IA-1 continua — sem IA, a tela faz tudo.

## 7b. Eixo 5 — Onde roda o agente · CONFIRMADO (26/09/2026)

Direção confirmada: o laço do agente roda no **satélite de IA** (Python, §22.2) e chama o core **pelo catálogo**; o
core nunca embute modelo. Sub-decisões (todas confirmadas):

- **5.1 Por onde o satélite chama o core.** (a) API HTTP interna própria · (b) **o mesmo servidor MCP** que os
  clientes de fora usarão (Eixo 6), com outro login e outro conjunto de ferramentas. **Confirmado: b** *(um caminho
  só; o MCP externo deixa de ser um produto à parte e vira "o mesmo, aberto para fora".)* O servidor MCP mora no
  **core, como mais um adaptador de entrada** (Inv. 5), ao lado do HTTP; cada módulo declara as entradas do
  catálogo que são suas. Vira **ADR** na implementação (nova peça na silhueta do ADR-0001).
- **5.2 Por onde a tela fala com o agente.** (a) navegador → satélite direto · (b) **navegador → core → satélite**.
  **Confirmado: b.** O core emite o token delegado (3.4), aplica tenancy, limite por Casa e auditoria num lugar
  só, e devolve a resposta em **SSE**, que já é o protocolo de streaming (§22.3.2, §22.6 eixo G). O satélite nunca
  fica exposto ao navegador.
- **5.3 Onde fica o registro de cada execução.** A conversa/execução (mensagens, ferramentas chamadas) é artefato
  técnico do **satélite** (como o log de inferência, §22.3.4). O que tem valor institucional mora no **core**:
  rascunhos, propostas de ato (4.2) e auditoria (3.5). Retenção da conversa (contém dado pessoal) = `[GAP]`
  LGPD, junto com os demais prazos de retenção.
- **5.4 O que é um agente.** Um agente = **definição versionada** (instruções + conjunto de ferramentas + classe de
  modelo + avaliações do Eixo 8), mantida pelo produto no satélite, com `agente_id` (o que aparece em "via agente
  X"). Na V1 a Casa **liga/desliga e parametriza**; não escreve agentes próprios.
- **5.5 Como o laço é construído.** Laço **próprio e fino** sobre a porta de inferência (que normaliza a chamada
  de ferramentas entre fornecedores) + cliente MCP oficial. **Sem framework pesado de agentes** preso a um
  fornecedor — a porta vendor-agnóstica e o failover (Eixos 10 e 13) exigem trocar de fornecedor sem reescrever.
- **5.6 Falha no meio (reuso do Eixo 13, sem regra nova).** Agente **interativo** que perde o fornecedor não troca
  no meio: encerra com "IA indisponível, siga pela tela" (R-IA-1). Agente **institucional** (assíncrono, 3.1 b)
  recomeça a execução inteira no secundário sob a mesma chave de dedup; **nunca costura dois fornecedores**.
- **5.7 Como cada tipo de agente dispara** (topologia do §22.3.1, sem regra nova): interativo = streaming SSE a
  partir da tela; institucional = fila, disparado por **evento de integração** que já existe (ex.:
  `ProposicaoProtocolada` → conferência contra LOM/RI).

## 8. Eixo 6 — MCP externo · CONFIRMADO (26/09/2026)

**O que é:** deixar IAs que **não são nossas** (o Claude/ChatGPT do vereador, a IA de um jornalista, outro sistema)
se conectarem ao O Plenário. Pelo Eixo 5.1 é **o mesmo servidor MCP** dos nossos agentes, com outro login e outro
conjunto de ferramentas.

Sub-decisões (todas confirmadas):

- **6.1 Para quem e em que ordem.** (A) tudo de uma vez · (B) **em fases** · (C) só agentes internos por ora.
  **Confirmado: B.**
  1. **Público, sem login, só consulta** — proposições, pautas, votações nominais, atas publicadas, leis: o que já
     está no portal/dados abertos. Risco baixo; argumento de venda para o presidente da Mesa (transparência) e
     para observatórios sociais, imprensa, pesquisa.
  2. **Vereador e servidor autenticados** — leitura + rascunho (o rascunho cai na plataforma para a pessoa revisar).
  3. **Propostas de ato** — sempre confirmadas na tela da plataforma (4.2 B), nunca na IA de fora.
  Integração sistema-a-sistema (Prefeitura, contábil) **fica fora**: é outro tipo de principal e pertence à API
  pública da V2 (Inv. 5).
- **6.2 Login e município.** Padrão de autorização do MCP (OAuth 2.1): o **Keycloak** é o servidor de autorização;
  a pessoa entra com o login normal (passkey), vê a tela de consentimento (3.3: o que a IA pode, por quanto tempo,
  revogável) e o token sai com `ente_id` + pessoa + cliente como agente (3.4). **Um endereço MCP por Casa**, no
  mesmo domínio do portal white-label — o token é sempre de uma Casa, como hoje. Consulta agregando vários
  municípios fica parqueada.
- **6.3 LGPD — o dado vai para a IA que a pessoa escolheu.** A Câmara é controladora; mandar dado a um fornecedor
  que ela não contratou pode ser compartilhamento irregular. Proposta:
  - **(i) a saída do MCP externo passa pelo MESMO filtro fail-closed** que protege a nossa saída para o LLM
    (`prototipos/governanca-ia/`, B1–B4): só cruza o que é comprovadamente público ou do próprio trabalho da
    pessoa sem dado pessoal de terceiro; sigiloso/restrito nunca sai. É o mesmo problema (dado saindo para LLM de
    terceiro), então é o mesmo mecanismo — não um segundo;
  - **(ii) o `admin_ente` escolhe quais clientes externos podem se conectar** com login (padrão: nenhum; o público
    fica aberto);
  - `[GAP]` jurídico: confirmar (i)+(ii) na passada de LGPD (art. 33 e termo de uso do agente público).
- **6.4 Custo e abuso.** O modelo roda na conta de quem conecta — nosso custo é só consulta. Limite por token/IP e
  por Casa como **tunable** (§22.5 disc. 7); camada pública com cache. Preço (incluso no plano ou à parte) =
  `[GAP]` comercial, não de arquitetura.
- **6.5 O catálogo externo é contrato público.** Promover uma ferramenta para fora é decisão com revisão, versionada,
  com mudança incompatível coexistindo em transição — **a mesma disciplina dos eventos de integração**
  (§22.3.3). O conjunto público começa pequeno.

## 8a. Eixo 7 — Conhecimento normativo · CONFIRMADO (26/09/2026)

Direção confirmada: Lei Orgânica, Regimento Interno e leis federais/estaduais **em camadas por município**, sem
nada especializado em uma Casa (Daouda: são dados públicos; a plataforma é multi-município).

O que já existe no código e é reaproveitado: `legislativo.norma` (lei/ato promulgado **dentro** da plataforma, com
URN LexML e imutabilidade); a decisão v1.14 (repositório *as-enacted* + consolidação manual assistida; consolidação
automática por IA fora da V1); o motor de regras já separa as fontes normativas em `federal` ·
`tribunal_de_contas` · `regimento_tenant` (`motor/models/catalogo.clj`); regras como `tempo_regimental` já têm
`referencia_normativa`; embeddings self-host + `pgvector` no satélite (§22.9 Eixo 10, §22.3.4); staging por lote
com efetivação (fundação #2, §22.2).

Sub-decisões (todas confirmadas):

- **7.1 As camadas** — a **mesma taxonomia do motor**, sem inventar outra:
  - **federal** (CF, LC 95/1998 de técnica legislativa, LRF, LAI, LGPD, Lei 14.063…) — uma cópia para todos,
    curada pelo produto;
  - **estadual** (Constituição Estadual, resoluções do TCE) — uma cópia por UF;
  - **municipal** — a **LOM pertence ao Município** (entidade de referência do Inv. 1, compartilhada com a futura
    Prefeitura); o **Regimento Interno e as resoluções pertencem à Câmara** (ente); leis municipais, ao Município.
- **7.2 Forma do texto.** (A) PDFs + busca por trechos · (B) **norma estruturada por dispositivo** (artigo,
  parágrafo, inciso, alínea), cada um com endereço estável (URN LexML + fragmento) · (C) consolidação automática
  por IA. **Confirmado: B.** A Camada de Confiança exige citar a fonte; com (B) a citação é "art. 12, § 1º, da LOM
  de Baturité", clicável e conferível. (C) já está fora da V1 (v1.14).
- **7.3 Como o acervo entra (proativo, sem esperar a Casa mandar PDF).** Pipeline de coleta de fontes públicas
  (LexML, sites de câmaras/prefeituras, diários oficiais) + upload da Casa; OCR quando for imagem; a IA quebra em
  dispositivos; **uma pessoa confere** antes de valer. Entra como **lote com efetivação** (fundação #2): não
  conferido não aparece para ninguém. Cada norma diz "conferida por X em dd/mm". A curadoria federal/estadual é do
  produto (especialista em regimento, §10). `[GAP]`: licença de agregadores privados (ex.: portais de leis
  municipais) — preferir fonte oficial.
- **7.4 Onde vive.** Texto e dispositivos = **artefato legal no core** (camadas federal/estadual = tabelas de
  referência supratenant, como `cadastros.municipios`; municipal com `ente_id`/município). Índice de busca e
  embeddings = **satélite** (já decidido, §22.3.4), alimentado pelo evento de efetivação.
- **7.5 Como o agente consulta.** Duas ferramentas no catálogo (Eixo 2): **buscar dispositivos** (busca híbrida
  — palavra exata + semântica, porque "art. 45" precisa casar literalmente) e **ler dispositivo** pelo endereço.
  Regra de resposta: **toda afirmação normativa do agente cita um dispositivo lido na mesma execução**; sem
  citação, a resposta sai marcada "sem fonte". A versão usada aparece sempre ("consolidação conferida até
  dd/mm").
- **7.6 Ligação com o motor de regras.** A `referencia_normativa` das regras (tempo de tribuna, quórum…) passa a
  apontar para o endereço do dispositivo. Isso permite ao agente explicar "por que 3 minutos" e ao agente
  institucional apontar **divergência entre a regra configurada e o texto** — como aviso para uma pessoa; a IA
  **nunca altera regra** (configuração é ato, Eixo 4).
- **7.7 A conferência de requerimento contra LOM/RI** (o pedido do stakeholder) sai daqui: agente institucional
  (3.1 b) disparado por `ProposicaoProtocolada` (5.7), que lê os dispositivos aplicáveis e produz um **rascunho
  de nota técnica com citações** para a secretaria — nunca uma decisão.

## 8b. Eixo 8 — Qualidade e custo · EM DEBATE

Direção confirmada: avaliação por ação, teto de custo por Câmara, painel de acompanhamento da IA. Já existe e é
reaproveitado: métricas obrigatórias da fronteira (categoria de erro, latência, custo, confiança — §22.3.5);
**R-IA-3** (teto de custo por ente) e **R-IA-4** (detectar piora de qualidade ao trocar de fornecedor), ambos
`fast_follow` em `produto/17`; a tela **`observabilidade-ia`** já desenhada (cockpit do operador: volume, p95,
custo, fallback por recurso e fornecedor); botão "reportar erro" e revisão humana (§16.8); a versão robusta da
governança (amostragem de auditoria, qualidade por Câmara) já está na Onda 2 — não se reabre.

Sub-decisões propostas:

- **8.1 Avaliação antes de ir ao ar.** (A) só revisão manual · (B) **cada agente e cada capacidade de IA tem um
  conjunto de avaliação, rodado no CI do satélite e obrigatoriamente antes de trocar fornecedor ou modelo** · (C) B
  + juiz automático sobre amostras de produção. *(Recomendado: B agora; C na Onda 2, junto da camada robusta.)*
  O conjunto tem três partes: **casos reais conferidos** (atas humanas × geradas, requerimentos, conferências
  contra LOM/RI com resposta conhecida); **casos de segurança** (instruções escondidas do 4.5, tentativa de ato
  sem confirmação, atos que o agente nem propõe do 4.3); **checagens objetivas** (a citação existe e diz o que o
  agente afirmou — 7.5; ferramenta certa chamada). Versão de agente que piora não sobe — fecha R-IA-4.
- **8.2 Qualidade em produção medida pelo que a pessoa faz**, sem trabalho extra de anotação: rascunho aceito
  como veio / editado (quanto) / descartado; proposta de ato confirmada / recusada; "reportar erro". São eventos
  de domínio que já passam pelo core → métricas por agente, versão e Casa. Casos reais só viram conjunto de
  avaliação anonimizados — `[GAP]` LGPD junto da retenção (5.3).
- **8.3 Custo.** Toda execução registra tokens, fornecedor, agente, ferramenta e `ente_id` (§22.3.5 + Inv. 8).
  **Orçamento mensal por Casa** (tunable, §22.5 disc. 7) com aviso a 80%; ao estourar: **agentes institucionais
  (segundo plano) pausam primeiro**; os interativos seguem até um teto duro e então mostram "IA indisponível — cota
  da Casa", com a tela fazendo tudo (R-IA-1). **Nunca troca para um modelo pior em silêncio por causa de custo.**
  Alavancas de custo ficam na definição do agente (5.4): classe de modelo por tarefa (modelo menor para
  classificar, de fronteira para redigir) — escolhida **pela avaliação**, não só pelo preço; cache de prompt.
  Valores do orçamento = `[GAP]` comercial (dependem do preço do plano).
- **8.4 Painéis — dois públicos, no módulo `paineis`** (read-model, F7): **operador** = a tela
  `observabilidade-ia` já desenhada (fast-follow); **Casa** (`admin_ente`) = consumo × orçamento, taxa de
  aceitação por agente e erros reportados, na área de administração do ente.
- **8.5 Métrica sem conteúdo.** Painéis e métricas carregam contagens e identificadores, nunca o texto (mesma
  regra B4 da auditoria da porta). O conteúdo das execuções fica só no satélite, sob a retenção do 5.3.

## 9. Contexto que alimenta os eixos seguintes

- Stakeholder (Rigoni, Baturité): leitura da ata **opcional** (IA, presencial ou nenhuma); a IA lê a ata que gerou
  da transcrição; gravação via **OBS com transmissão ao YouTube**; boa qualidade de áudio.
- Daouda: gov.br **só para assinatura** (não login do vereador); consulta LOM/RI **entra na V1**; LOM/RI são dados
  públicos e a solução é **multi-município** — nada especializado em uma Casa.
- Ordem de funcionalidades provável depois do desenho: consulta LOM/RI → copiloto do requerimento (sobre a tela da
  fatia 2a, PR #37) → ata-IA + leitura → resumo/busca.

## 10. Registro da sessão

| Data | O quê |
|---|---|
| 26/09/2026 | Eixo 7 CONFIRMADO (7.2 B + demais); Eixo 8 aberto em sub-decisões |
| 26/09/2026 | Eixo 6 CONFIRMADO (6.1 B, 6.3 i+ii + demais); Eixo 7 aberto em sub-decisões |
| 26/09/2026 | Eixo 5 CONFIRMADO (5.1 b, 5.2 b + demais); Eixo 6 aberto em sub-decisões |
| 26/09/2026 | Eixo 4 CONFIRMADO (4.2 B + demais); Eixo 5 aberto em sub-decisões |
| 26/09/2026 | Eixo 3 CONFIRMADO (3.1 b + 3.2–3.5); Eixo 4 aberto em sub-decisões |
| 26/09/2026 | Sessão aberta; 8 eixos definidos; Eixo 1 e Eixo 2 CONFIRMADOS (B); direção dos Eixos 3, 4, 5, 7, 8 confirmada; Eixo 3 aberto em sub-decisões |
