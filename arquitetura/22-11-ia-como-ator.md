# 22.11 IA como ator sobre a plataforma (catálogo de ações, identidade delegada, fronteira do ato, MCP)

> *Parte do SSOT (documento-mestre). Versão canônica do conjunto em `documento-mestre-camaras.md` §24.*
> *Em conflito com memória de chat antigo, este arquivo prevalece.*
> *Consolidado na v1.46 a partir da sessão de 26/09/2026 (`docs/25-desenho-ia-na-plataforma.md`, oito eixos
> confirmados um a um) com o "Confirmo" do Daouda Traore (merge do PR #38). O debate (opções, tradeoffs) mora no
> `docs/25`; aqui ficam as decisões. Plano de execução: `docs/26-plano-track-ia.md`.*

**Estende, não reabre.** Invariante 3 (IA como plataforma), §22.2 (satélite Python), §22.3 (contrato core↔IA),
§22.5 (authz), §22.9 Eixos 10/13 (porta de inferência + failover) e §16.8 (Camada de Confiança) seguem como estão.
O que esta subseção acrescenta: até aqui a IA era só **fábrica de artefatos** (transcrição, ata, resumo,
embeddings); agora ela também **age sobre a plataforma** — consulta estado, prepara rascunhos e propõe atos em nome
de alguém — por um caminho único, sem cada copiloto abrir o seu até os dados.

## 22.11.1 Lugar da IA (Eixo 1)

A IA **produz artefatos e age sobre a plataforma.** Regra verificável: **toda ação nova nasce com duas bocas — a
tela e a ferramenta do agente.** Os copilotos da própria plataforma são o primeiro cliente do catálogo; feature de
IA que precise de algo fora dele **acrescenta ao catálogo**, nunca abre caminho próprio. Chat como interface
principal foi descartado (o servidor precisa de tela determinística para ato legal; Aposta 2).

## 22.11.2 Catálogo de ações (Eixo 2)

**Uma ferramenta por ação de domínio**, num catálogo declarado. Cada entrada declara: nome e descrição em português
escrita para o agente; esquema de entrada/saída (os **mesmos Malli** de `wire/in`·`wire/out`, convertidos a JSON
Schema); **classe do ato** (`leitura` | `rascunho` | `ato`); papéis que podem usar (uma ferramenta atende vereador e
servidor; o `policy.check` decide).

- **Granularidade:** uma ferramenta = uma ação que uma pessoa da Câmara diria em voz alta ("protocolar
  requerimento", "consultar a tramitação da proposição X") — não uma tabela, não uma tela.
- **Fonte única enforçada:** rota HTTP e ferramenta referenciam a mesma entrada; **rota nova sem entrada no catálogo
  quebra o CI** (como o `estrutura_lint_test`), salvo marcada "só-tela" com motivo.
- **Migração:** a regra vale para o novo; o existente entra conforme os agentes precisarem.
- **Conjuntos por público** (vereador, secretaria, cidadão) ficam sobre o catálogo e expõem **menos** que a
  permissão da pessoa.

## 22.11.3 Identidade do agente (Eixo 3) — estende §22.5

- **Dois tipos de principal:** **delegado** (o agente age em nome de uma pessoa) e **agente institucional da Casa**
  (sem pessoa por trás — ex.: conferir todo requerimento protocolado contra a LOM/RI), este restrito a `leitura` +
  `rascunho`, com todo resultado caindo numa fila para uma pessoa.
- **Permissão efetiva = interseção**, nunca união: o que a pessoa pode **agora** ∩ ferramentas do agente ∩ classes
  concedidas, **reavaliada a cada chamada** (mandato encerrado ou vínculo suspenso derruba o agente na hora).
- **Concessão:** agente interno invocado na tela usa a sessão da pessoa, limitado ao conjunto daquela tela; agente
  institucional é concedido pelo `admin_ente`; agente externo exige consentimento explícito, com validade e revogação.
- **Mecanismo:** token com a pessoa como sujeito e o agente como ator (token exchange, RFC 8693, no Keycloak —
  confirmar suporte na versão em uso). No core o ator ganha `:via {:agente-id :execucao-id}`; token de agente só é
  aceito em operação que está no catálogo.
- **Auditoria:** escritas sempre com pessoa + agente + execução + ferramenta + classe ("Fulano, via agente X");
  leituras seguem a regra atual das telas, acrescida do agente; o registro completo da execução fica no log de
  inferência do satélite (§22.3.4).

## 22.11.4 Fronteira do ato (Eixo 4) — estende §16.8

- **`ato`** = tudo o que produz efeito institucional ou fala pela Casa para outra pessoa (protocolar, assinar,
  emitir parecer, tramitar, publicar, responder e-SIC, alterar pauta, comunicar a terceiros). **`rascunho`** = só o
  dono vê até alguém promover; sempre carimbado "produzido por IA". **`leitura`** = livre dentro da permissão.
- **Com um `ato`, o agente cria uma PROPOSTA DE ATO** com o conteúdo exato; a pessoa confirma **na interface da
  própria plataforma** (não no chat do agente), com o mesmo ritual da tela (assinatura em 2 toques, step-up quando
  houver). Confirmar fora do canal do agente impede que ele descreva uma coisa e assine outra.
- **Atos que o agente nem propõe** (pessoais e intransferíveis): **voto**, **presença** e **condução da sessão ao
  vivo**.
- **Classificação fail-closed:** a classe é dado no catálogo, revisada em PR; ação sem classe = `ato`.
- **Instruções escondidas em texto de terceiros** (e-SIC, participação, e-mail, PDF, fala transcrita): defesa
  **estrutural** (interseção + confirmação humana: o pior caso é um rascunho ruim ou uma proposta recusada);
  **marcação de origem** (execução que leu conteúdo de terceiro fica contaminada e o confirmador vê isso; proposta
  que leva para fora dado restrito lido na mesma execução é bloqueada); delimitação no prompt só como reforço.
- Quem confirma um ato vindo de rascunho de IA assume a autoria ("revisado e assinado por"); **R-IA-1** continua —
  sem IA, a tela faz tudo.

## 22.11.5 Onde roda o agente (Eixo 5) — estende §22.3

- O laço do agente roda no **satélite** e chama o core **pelo mesmo servidor MCP** que os clientes de fora usarão,
  com outro login e outro conjunto de ferramentas. **O MCP mora no core como mais um adaptador de entrada** (Inv. 5),
  ao lado do HTTP; cada módulo declara as entradas do catálogo que são suas (vira ADR na implementação).
- **Navegador → core → satélite**, nunca navegador → satélite: o core emite o token delegado, aplica tenancy, limite
  por Casa e auditoria, e devolve em **SSE** (§22.3.2, §22.6 eixo G).
- **Registro:** a conversa/execução é artefato técnico do satélite (como o log de inferência); o que tem valor
  institucional (rascunhos, propostas de ato, auditoria) mora no core. Retenção da conversa = `[GAP]` LGPD.
- **Um agente = definição versionada** (instruções + ferramentas + classe de modelo + avaliações), mantida pelo
  produto no satélite, com `agente_id`. Na V1 a Casa liga/desliga e parametriza; não escreve agentes.
- **Laço próprio e fino** sobre a porta de inferência + cliente MCP oficial; **sem framework pesado de agentes**
  preso a fornecedor.
- **Falha no meio** (reuso do Eixo 13): interativo encerra com R-IA-1; institucional recomeça inteiro no secundário
  sob a mesma chave de dedup; **nunca costura dois fornecedores**.
- **Disparo:** interativo = streaming SSE a partir da tela; institucional = fila, por **evento de integração** que
  já existe (ex.: `ProposicaoProtocolada` → conferência contra LOM/RI).

## 22.11.6 MCP externo (Eixo 6) — pós-V1, atrás de gatilho (régua §15)

IAs que não são nossas conectando-se à Casa, **em fases**: (1) público sem login, só consulta (o que já está no
portal); (2) vereador e servidor autenticados, leitura + rascunho; (3) propostas de ato, sempre confirmadas na tela
da plataforma. Integração sistema-a-sistema fica na API pública da V2.

- **Login:** OAuth 2.1 com o **Keycloak** como servidor de autorização, tela de consentimento, token com `ente_id` +
  pessoa + cliente como agente; **um endereço MCP por Casa**, no domínio white-label.
- **LGPD:** a saída do MCP externo passa pelo **MESMO filtro fail-closed** da porta do LLM (§22.9 Eixo 10.3 /
  B1–B4) — só cruza o comprovadamente público ou do próprio trabalho da pessoa; o **`admin_ente` escolhe** quais
  clientes externos podem conectar (padrão: nenhum). `[GAP]` jurídico (art. 33 + termo do agente público).
- **Custo:** o modelo roda na conta de quem conecta; limite por token/IP e por Casa como tunable. Preço = `[GAP]`
  comercial.
- **O catálogo externo é contrato público:** promoção com revisão, versionada, mudança incompatível coexistindo em
  transição — a disciplina dos eventos de integração (§22.3.3).

## 22.11.7 Conhecimento normativo (Eixo 7)

- **Camadas** — a mesma taxonomia do motor de regras (`federal` · `tribunal_de_contas` · `regimento_tenant`):
  **federal** (uma cópia para todos, curada pelo produto); **estadual** (uma por UF); **municipal** — a **LOM
  pertence ao Município** (Inv. 1, compartilhada com a futura Prefeitura), **Regimento Interno e resoluções à Câmara**
  (ente), leis municipais ao Município. Nada especializado em uma Casa.
- **Norma estruturada por dispositivo** (artigo, parágrafo, inciso, alínea), cada um com **endereço estável** (URN
  LexML + fragmento). A citação vira "art. 12, § 1º, da LOM de Baturité", clicável e conferível. Consolidação
  automática por IA segue fora da V1 (v1.14).
- **Entrada proativa:** coleta de fontes públicas + upload da Casa; OCR quando for imagem; a IA quebra em
  dispositivos; **uma pessoa confere** antes de valer; entra como **lote com efetivação** (fundação #2). Cada norma
  diz "conferida por X em dd/mm". `[GAP]`: licença de agregadores privados — preferir fonte oficial.
- **Onde vive:** texto e dispositivos = **artefato legal no core** (federal/estadual como referência supratenant;
  municipal com `ente_id`/município). Índice e embeddings = **satélite**, alimentado pelo evento de efetivação.
- **Consulta do agente:** duas ferramentas — **buscar dispositivos** (híbrida: literal + semântica) e **ler
  dispositivo**. **Toda afirmação normativa cita um dispositivo lido na mesma execução**; sem citação, sai marcada
  "sem fonte"; a versão usada aparece sempre.
- **Motor de regras:** a `referencia_normativa` das regras passa a apontar para o endereço do dispositivo; o agente
  institucional aponta divergência entre regra configurada e texto **como aviso**; a IA **nunca altera regra**.
- **Conferência de requerimento contra LOM/RI** (pedido do stakeholder): agente institucional disparado por
  `ProposicaoProtocolada`, produz **rascunho de nota técnica com citações** para a secretaria — nunca uma decisão.

## 22.11.8 Qualidade e custo (Eixo 8) — estende §22.3.5

- **Avaliação antes de ir ao ar:** cada agente e cada capacidade de IA tem um **conjunto de avaliação**, rodado **no
  CI do satélite** e **obrigatório antes de trocar fornecedor ou modelo** (fecha R-IA-4). Três partes: casos reais
  conferidos; casos de segurança (instrução escondida, ato sem confirmação, ato que nem se propõe); checagens
  objetivas (a citação existe e diz o que foi afirmado; ferramenta certa). Juiz automático sobre produção: Onda 2.
- **Qualidade em produção medida pelo que a pessoa faz** (rascunho aceito/editado/descartado; proposta
  confirmada/recusada; "reportar erro") — eventos que já passam pelo core. Casos reais só viram avaliação
  anonimizados (`[GAP]` LGPD).
- **Custo:** toda execução registra tokens, fornecedor, agente, ferramenta e `ente_id`. **Orçamento mensal por Casa**
  (tunable) com aviso a 80%; ao estourar, institucionais pausam primeiro; interativos seguem até teto duro e então
  R-IA-1 "cota da Casa". **Nunca troca para modelo pior em silêncio por custo.** Valores = `[GAP]` comercial.
- **Painéis** no módulo `paineis`: operador (`observabilidade-ia`, fast-follow) e Casa (`admin_ente`: consumo ×
  orçamento, aceitação por agente, erros reportados).
- **Métrica sem conteúdo:** contagens e identificadores, nunca o texto (regra B4); o conteúdo fica só no satélite.

## 22.11.9 `[GAP]` e pendências herdadas

Fornecedor de LLM com DPA de não-treino + LGPD art. 33 (trava o uso real da porta; o adaptador fake não espera);
retenção das conversas e anonimização para avaliação (LGPD); suporte a token exchange na versão do Keycloak;
licença de agregadores de leis; termo do agente público e preço do MCP externo; valores do orçamento por Casa.
