# ADR-0019 — Parecer jurídico e o caminho da matéria até a pauta

- **Status:** ✅ **Aceita** (30/09/2026). A direção e a ordem das fatias foram combinadas com o Daouda ("de
  acordo") e a fatia 1 foi implementada no mesmo dia (ver *Materialização*). As fatias 2 a 4 seguem propostas.
- **Origem:** pedido do stakeholder Rigoni (30/09/2026): "a função do procurador é dar parecer" sobre proposições e
  requerimentos, "para ver se tem embasamento jurídico", antes de a secretaria pautar e o presidente autorizar.
- **Base:** a pesquisa [`docs/29`](../29-pesquisa-parecer-juridico-nas-camaras.md) (≈110 Regimentos Internos lidos
  na íntegra, capitais, STF/TCU, LAI, SAPL; notas em `docs/evidencias/pesquisa-parecer-juridico/`). **Esta ADR parte
  do que a pesquisa apurou, não do relato**, que descreve uma minoria de Casas.
- **Relacionadas:** ADR-0004 (a guarda de transição só lê fato apurado), ADR-0009 (catálogo de ações), ADR-0012
  (proposta de ato), ADR-0013 (agente institucional e nota técnica), ADR-0017 (trilha de auditoria),
  `arquitetura/22-4-dados-legislativo.md`, documento-mestre linha 165 ("pré-análise de alertas, nunca parecer
  jurídico").

## Contexto

### O que a pesquisa estabeleceu

1. **Quem manda na pauta é a política, não o jurídico.** O requisito regimental quase universal para a matéria ir à
   Ordem do Dia é o **parecer da comissão**, em geral a de Justiça, formada por vereadores. A Ordem do Dia é organizada
   e mandada publicar pelo **Presidente**, pela **Mesa** ou pelo **1º Secretário**. Nenhum Regimento lido exige que o
   Presidente **assine** a pauta.
2. **O parecer jurídico é opinativo sempre.** Nenhum Regimento lido dá ao parecer jurídico contrário o efeito de
   arquivar. Esse efeito vem do parecer da comissão ou de ato do Presidente, como a devolução de proposição
   manifestamente inconstitucional.
3. **Se o parecer jurídico existe, e quando, é decisão do Regimento de cada Casa.** Nenhuma norma constitucional ou
   federal o exige; pelo Tema 1120 do STF, a matéria é *interna corporis*. Os Regimentos se dividem em quatro formas:
   - **não existe:** Baturité, lido na íntegra, e a maioria no Ceará;
   - **facultativo**, a pedido da comissão: Iguatu, Croatá, Cariús;
   - **obrigatório logo após o protocolo**, antes das comissões: Crato, Jaguaribara, Palmácia, Varjota e Patos-PB;
   - **só em ritos especiais:** CPI, cassação, reforma do Regimento.
4. **O parecer jurídico serve a projetos, não a requerimentos, indicações ou moções.** Esses três seguem um caminho
   curto: despacho do Presidente ou votação na mesma sessão. A exceção é o requerimento de CPI. O Presidente também
   pede parecer **avulso, sem matéria**: decoro, contas, admissibilidade de CPI.
5. **No Ceará, o jurídico é, na prática, um advogado só,** em cargo comissionado ou contratado, trocado a cada biênio
   da Mesa. O parecer circula em PDF assinado fora do sistema e quase nunca é publicado.
6. **Quem assina parecer responde pessoalmente** por erro grosseiro (MS 24.631, LINDB art. 28). Os precedentes são de
   licitação, mas fixam a fronteira: **texto de máquina não é parecer até um advogado assinar.**
7. **Publicidade:** pela LAI (art. 7º §3º), o parecer pode ficar reservado até a decisão sobre a matéria; depois dela,
   é público.

### O que o sistema tem hoje

- **O rito é dado por Casa**, executado pelo motor declarativo compartilhado:
  - estados, transições, guardas e autorização (`legislativo/db/tramitacao.clj`);
  - a Casa demo segue `protocolada → em_comissoes → aguardando_pauta → em_pauta → aprovada|arquivada`;
  - **não há tela nem rota para editar o rito**, só semente.
- **O parecer existente é o de comissão**, com `comissao_id NOT NULL` e relator vereador. **Não há rota para
  distribuir a matéria à comissão, designar o relator ou abrir o parecer**: isso só existe na semente da demo. Emitir e
  assinar já têm rota, para a secretaria e para o relator.
- **A nota técnica da IA** (B.8, ADR-0013):
  - é criada a cada matéria protocolada e vai para a fila `/conferencias` da **secretaria**;
  - só o agente institucional a escreve;
  - não aparece na ficha da matéria.
- **A pauta:**
  - a secretaria inclui itens **sem conferência do estado da matéria**;
  - o congelamento da pauta (`publicar-versao!`) existe no repositório de sessões, mas **sem rota**;
  - **ninguém "publica" ou "autoriza" a pauta** como ato registrado.
- **Não existe papel jurídico** nem parecer jurídico.

## Eixos para decidir

### Eixo 1 — O papel `juridico`

- **(a) Papel novo `juridico` (recomendado).**
  - É concedido pelo `admin_ente` em `/administracao`, num vínculo de **servidor**, como o `auditor`.
  - A concessão registra a **qualificação** (procurador efetivo, assessor comissionado ou advogado contratado) e o
    **número da OAB**, porque o parecer precisa dizer a que título foi assinado.
  - O advogado contratado que atende várias Câmaras recebe um vínculo em cada Casa, com a mesma identidade pelo CPF.
- (b) Reusar `secretario`. ✘ Mistura quem monta o expediente com quem assina a opinião técnica, e a independência
  técnica do advogado exige que só ele edite o texto.
- **Uma hierarquia só na V1.** O modelo "procurador redige, procurador-geral aprova" (ALE-RR) fica para quando uma Casa
  pedir.

### Eixo 2 — O pedido de parecer

- **O objeto:** uma **matéria** (proposição ou emenda) **ou uma consulta avulsa**, com assunto em texto e sem matéria,
  para os pedidos do Presidente sobre decoro, contas ou CPI.
- **Quem pede:**
  - a **secretaria**, por despacho da Presidência, com registro de em nome de quem;
  - o **relator da comissão**, sobre a matéria que relata.
- **O prazo** é opcional, um parâmetro da Casa ou de cada pedido. Não há relógio legal a respeitar: o prazo que conta
  no Regimento é o da comissão.
- **Recomendado:** o pedido é **sempre disponível como ação**, qualquer que seja o rito da Casa, e não move a matéria.
  É um documento a mais na ficha. A ação entra no catálogo (ADR-0009), para o agente poder propor o pedido (ADR-0012).

### Eixo 3 — O documento parecer jurídico

- **(a) Entidade própria (recomendado),** `legislativo.parecer_juridico`:
  - **vínculo:** ligada à matéria ou à consulta, com o pedido que a originou;
  - **conteúdo:** relatório, fundamentação e **conclusão**, em vocabulário aberto: `favoravel`, `contrario`,
    `com_ressalvas`, `orientacao` (roteiro de rito);
  - **estados:** `rascunho → assinado`;
  - **depois de assinado é imutável:** corrigir é emitir um parecer novo que **substitui** o anterior, e os dois ficam
    na ficha;
  - **assinatura:** grava, no ato, o nome, a OAB e a qualificação do signatário. É `STUB-ICP-v0` como o resto do
    legislativo, até a ICP real.
- (b) Estender o parecer de comissão. ✘ Ele exige comissão e relator vereador, tem rito e voto divergente próprios.
- (c) Estender a nota técnica. ✘ A nota é da máquina (agente e execução obrigatórios); misturar os dois apaga a
  fronteira do item 6 acima.

### Eixo 4 — Onde o parecer aparece e quando é público

- **Interno:** na ficha da matéria, para a secretaria, a comissão e os vereadores, assim que assinado. A consulta
  avulsa fica na fila do jurídico e na do solicitante.
- **Portal (recomendado):** o parecer de matéria vai ao portal **depois da deliberação** (a votação no plenário ou o
  arquivamento), como a LAI art. 7º §3º permite. A Casa pode **antecipar** para "ao assinar". A consulta avulsa não vai
  ao portal por padrão; responde-se por e-SIC.
- **Trilha de auditoria:** o pedido, a assinatura e a substituição entram com o resumo do efeito (ADR-0017).

### Eixo 5 — A IA trabalha para quem assina

- **Casa com jurídico ativo (recomendado):** a nota técnica da IA passa a chegar **à fila do jurídico** como rascunho.
  "Usar como rascunho" abre um parecer em `rascunho` com o texto da nota, e o advogado revisa, assume e assina.
  - Na ficha e no portal, o parecer é do advogado; a origem do rascunho fica registrada.
  - O texto da IA nunca é chamado de parecer.
- **Casa sem jurídico:** a nota segue para a secretaria, como hoje.
- **Copiloto do relator da Comissão de Justiça** (fatia 2): rascunha a análise de constitucionalidade e de juridicidade
  do parecer da comissão, citando a LOM e o Regimento (B.5). É onde a análise jurídica acontece nas Casas pequenas, e
  alcança mais Casas que a fila do jurídico.

### Eixo 6 — O caminho da comissão (lacuna nossa)

O parecer de comissão é o requisito regimental real e hoje só nasce na semente. **Recomendado:** ações e telas para a
secretaria:
- **distribuir** a matéria às comissões (o gatilho `despachar` do rito, com as comissões de destino);
- **designar o relator**, registrando a determinação do presidente da comissão;
- **abrir o parecer**, que segue o rito de parecer que já existe: relatoria → assinatura → emissão.

A autorização fina (só o presidente da comissão designa) fica para a autorização por transição quando uma Casa pedir.

### Eixo 7 — Publicar a pauta (fatia 3)

- **"Publicar a pauta"** é um ato único sobre a pauta inteira:
  - congela a versão (`publicar-versao!`, que já existe);
  - entra na trilha;
  - é o que o portal e a TV mostram como pauta oficial.
- **Quem publica** é configurável por Casa: Presidente, 1º Secretário ou Mesa. A antecedência mínima também é
  parâmetro (por exemplo, 24 h), com aviso quando descumprida.
- **Aviso, não bloqueio:** a tela de pauta avisa quando uma matéria não tem parecer da comissão ou tem pedido jurídico
  pendente, porque os Regimentos deixam ir a plenário com o prazo da comissão vencido.
- **A assinatura do Presidente não é exigência** em nenhum Regimento lido. Pode ser oferecida como opção, nunca como
  compliance.

### Eixo 8 — A etapa jurídica obrigatória (fatia 4, sob demanda)

- **Para as Casas cujo Regimento exige** (Crato, Jaguaribara, Palmácia…): um **fato novo**,
  `tem_parecer_juridico_assinado`, no registro de fatos (ADR-0004).
  - Com ele, o rito da Casa pode ter o estado `em_analise_juridica` e a guarda que não deixa a matéria seguir sem o
    parecer assinado.
  - É dado no rito, com uma função de fato nova no código: o mesmo caminho de `aprovada_em_votacao`.
- **Padrão: desligado.** Só se faz quando houver uma Casa cliente com essa regra.
- **Parecer contrário não trava:** a guarda pede o parecer **assinado**, não o parecer **favorável**.

## O que fica de fora (e por quê)

- **Etapa jurídica fixa para todas as Casas:** quebra o primeiro cliente, Baturité.
- **Requerimento, indicação e moção ao jurídico:** nenhum Regimento lido faz isso. Quando o Presidente quiser, a
  consulta avulsa atende.
- **Parecer contrário barrando a matéria:** contraria a natureza opinativa.
- **Parecer assinado pela IA, ou nota da IA apresentada como parecer:** documento-mestre linha 165 e o item 6 do
  contexto.
- **Dois níveis no jurídico** (redige e aprova) e **escritório atendendo várias Casas com uma credencial só:** quando
  houver cliente.

## Fatias

1. **Fatia 1: a comissão e o jurídico** (Eixos 1–4 e 6).
   - Distribuir, designar relator e abrir o parecer de comissão.
   - Papel `juridico` com qualificação e OAB.
   - Pedido de parecer (matéria ou consulta).
   - Fila `/juridico`.
   - Parecer jurídico assinado e imutável na ficha interna.
   - Portal após a deliberação.
   - Trilha de auditoria.
2. **Fatia 2: a IA como rascunho** (Eixo 5): a nota técnica na fila do jurídico e o copiloto do relator da Comissão de
   Justiça.
3. **Fatia 3: publicar a pauta** (Eixo 7).
4. **Fatia 4: a etapa obrigatória** (Eixo 8), só com uma Casa cliente que a exija.

## Materialização — fatia 1 (30/09/2026)

**O que existe:**

- **Papel `juridico`** (servidor). O `admin_ente` concede em `/administracao` ("Jurídico da Casa"), com qualificação
  (`efetivo`, `comissionado`, `contratado`) e OAB, que ficam em `identidade.perfil_juridico` (mig `…112`). Qualificação e
  OAB são obrigatórias com o papel e recusadas sem ele; a OAB é normalizada (`CE 12345`); um papel por concessão.
- **Pedido e parecer** (mig `…111`, `legislativo.pedido_parecer_juridico` e `legislativo.parecer_juridico`):
  - o pedido é sobre uma matéria ou uma **consulta avulsa** (sem matéria); pede a secretaria (em nome da Presidência) ou
    o relator; prazo opcional; `pendente → atendido | cancelado`;
  - o parecer nasce `rascunho` e, ao assinar, ganha `numero/ano` sequenciais da Casa (serializados por trava, sem
    buraco sob concorrência) e o **snapshot** de nome, OAB e qualificação **tirado do perfil, nunca do corpo**;
  - **assinado é imutável** no banco (trigger: nem UPDATE nem DELETE); corrigir é `substituição`, que abre um rascunho
    com o texto anterior e reabre o pedido. O antigo segue na ficha, marcado `substituído`;
  - o rascunho é só do advogado: a secretaria vê que o parecer está sendo redigido, sem o texto.
- **Caminho da comissão**: `GET /legislativo/comissoes`, `POST …/proposicoes/:id/pareceres-de-comissao` (um parecer por
  comissão, no rito de parecer da Casa, com relator opcional; repetir devolve o que já existe) e `POST
  …/pareceres/:id/relator`. Sem rito de parecer configurado, 409 nomeado (`sem-rito-de-parecer`), nunca 500.
- **Telas:** `/juridico` (fila) e `/juridico/[id]` (redigir, assinar, substituir); na ficha da matéria, "Encaminhar às
  comissões", "Designar relator" e a aba "Parecer jurídico"; no portal, "Pareceres jurídicos" **só depois da
  deliberação** e só o vigente.
- **Demo:** a persona de apresentação acumula `juridico` (com perfil); a semente traz um parecer assinado (matéria
  aprovada, visível no portal), um pedido pendente com rascunho e uma consulta avulsa.
- **Trilha (ADR-0017):** as escritas entram pelo interceptor global, como todas. Todas as rotas novas estão em
  `fora-do-catalogo.edn` como `:so-tela`, com o motivo: o agente propõe pedido e distribuição na fatia 2.

**O que ficou para depois (nada disso foi esquecido):**

- **Pedido sobre emenda:** o pedido é sobre proposição ou consulta avulsa; emenda não, até uma Casa pedir.
- **Distribuir não move a matéria no rito.** A secretaria abre os pareceres; o gatilho `despachar` continua sendo o ato
  de tramitação de sempre. Designar o relator grava o relator e **não** dispara a transição do rito de parecer
  (`aguardando_designacao → com_relator`), que é dado da Casa; o parecer com relator sai da fila de relatores pendentes da Mesa.
- ~~**Antecipar o portal para "ao assinar"**~~ — feito na fatia 2a.
- ~~**UI do relator pedir o parecer**~~ — feito na fatia 2b (`/parecer/[id]/redigir`).
- ~~**Assinatura com carimbo**~~ — feito junto com a fatia 4 (`STUB-ICP-v0`, como o resto do legislativo).
- **Resumo legível na trilha** por ação (a corrente registra o ato, mas o rótulo é o genérico).
- **Autorização fina** (só o presidente da comissão designa o relator): quando uma Casa pedir.

## Materialização — fatias 2, 3 e 4 (30/09/2026)

**Fatia 2a — a nota técnica da IA vira rascunho do advogado** (migs `…130` e `…131`):

- Na Casa **com jurídico ativo** (alguém com o papel `juridico` vigente, perguntado à identidade pelo seam
  `casa-tem-juridico?`), o jurídico lê as notas técnicas do agente institucional (ADR-0013) na seção "Notas técnicas da
  IA" de `/juridico` e em `/juridico/notas/[id]`.
- "Usar como rascunho" (`POST /legislativo/notas-tecnicas/:id/rascunho-juridico`) faz tudo numa transação: abre o pedido
  ou reaproveita o que existe, cria o rascunho sem as marcas de citação e sem conclusão, registra
  `origem_rascunho = nota_tecnica` e marca a nota como aproveitada.
- A ficha interna mostra a origem; o portal, não. O parecer é do advogado, e o texto da IA nunca é chamado de parecer.
- Casa sem jurídico: a nota segue para a secretaria em `/conferencias`, como antes.
- **Parâmetro `publicar_ao_assinar`** por Casa (`GET/PUT /legislativo/parametros-parecer-juridico`, `admin_ente`),
  exposto no bloco "Parecer jurídico no portal" de `/administracao`. A consulta avulsa nunca vai ao portal.

**Fatia 2b — o copiloto do relator:**

- Capacidade `relator.analisar` no satélite (`POST /v1/entes/:e/pareceres/analises`).
  - Lê a matéria (conteúdo de terceiro) e os dispositivos da LOM e do RI pelo índice (B.5).
  - Redige pelo núcleo, com citação conferida por parágrafo e `[confirmar: …]` onde não há fonte.
  - Sem normas publicadas, rascunha só com a matéria e diz isso.
  - Tem o conjunto de avaliação `copiloto-relator` no CI.
- No core, o painel aparece nos dois editores de parecer de comissão: o da secretaria (`POST
  /legislativo/pareceres/:id/copiloto`) e o do relator (`POST /meu/pareceres/:id/copiloto`, em `/parecer/[id]/redigir`).
  - O core confere as citações: só vale citar a própria matéria ou um dispositivo da Casa.
  - IA fora → 503 (R-IA-1).
  - O rascunho tem o selo "não é parecer"; o relator usa, substitui ou acrescenta, e salva por `PATCH /meu/pareceres/:id`.
- O relator pede o parecer jurídico pela mesma tela.

**Fatia 2 — o agente propõe os atos do jurídico** (ADR-0009/0012):

- Viram ações do catálogo, sempre como proposta que a secretaria confirma em `/propostas`:
  - `pedir_parecer_juridico`;
  - `encaminhar_as_comissoes`;
  - `designar_relator`.
- Leituras novas: `pareceres_juridicos_da_materia`, `comissoes_da_casa` e `vereadores_da_casa`.
- Assinar, salvar e substituir o parecer continuam só pela tela: são atos pessoais do advogado.
- Dois casos de segurança na avaliação do agente: o pedido só vira proposta, e o agente não assina parecer.

**Fatia 4 — a etapa obrigatória, desligada por padrão:**

- Fato `tem_parecer_juridico_assinado(proposicao.id)` no registro do motor (ADR-0004).
  - A costura é *fail-closed*.
  - Pergunta se o parecer foi **assinado**, nunca se é **favorável**.
- A Casa que exige a etapa escreve no rito o estado `em_analise_juridica` e a guarda. Nenhuma Casa tem isso por padrão.
- **Carimbo** (mig `…150`): o `AssinadorICP` assina os bytes canônicos do texto, com número/ano e signatário.
  - Algoritmo, assinatura e SHA-256 ficam gravados.
  - Aparecem na ficha, no pedido e no portal, com o aviso de que o `STUB-ICP-v0` não é ICP-Brasil.

**Fatia 3 — publicar a pauta:** ver o bloco abaixo.

**Ainda de fora, de propósito:**

- **Pedido sobre emenda**: até uma Casa pedir.
- **Resumo legível na trilha** por ação: a corrente registra o ato, mas o rótulo é o genérico (ADR-0017, *Materialização*).
- **Autorização fina** do relator (só o presidente da comissão designa): quando uma Casa pedir, pela autorização por
  transição.
- **Vigência do dispositivo citado:** o copiloto cita o dispositivo conferido, com a data da conferência (B.5), mas não
  verifica se uma norma posterior o revogou.
- **Assinatura ICP-Brasil real:** a dívida `STUB-ICP-v0` de todo o legislativo.

## O que peço para decidir

| Eixo | Pergunta | Recomendação |
|---|---|---|
| 1 | Quem é o jurídico | papel novo `juridico`, vínculo de servidor, com qualificação e OAB; um nível só |
| 2 | Como se pede parecer | ação sempre disponível; matéria ou consulta avulsa; pede a secretaria (pela Presidência) ou o relator; prazo opcional |
| 3 | O que é o parecer | entidade própria, conclusão aberta, assinado e imutável, substituição em vez de edição |
| 4 | Quem vê e quando | interno ao assinar; portal depois da deliberação (antecipável); consulta avulsa por e-SIC |
| 5 | A IA | nota técnica vira rascunho na fila do jurídico; copiloto do relator da Comissão de Justiça |
| 6 | A comissão | distribuir, designar relator e abrir o parecer por tela (hoje só semente) |
| 7 | A pauta | "publicar a pauta" como ato único, configurável por Casa, com aviso e sem bloqueio |
| 8 | Etapa obrigatória | fato `tem_parecer_juridico_assinado` + guarda no rito; desligado por padrão; só sob demanda |


## Para validar com o Rigoni (não bloqueia a fatia 1)

1. De qual Câmara é o fluxo que ele descreveu, e está no Regimento ou é costume?
2. Onde entram as comissões entre o parecer jurídico e a pauta?
3. Quais requerimentos vão ao procurador: nenhum, só CPI, ou consultas do Presidente?
4. "Presidente autoriza" é mandar publicar a pauta, despachar cada matéria ou assinar?
