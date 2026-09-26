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
| 3 | Identidade do agente | Direção confirmada ("em nome de", escopo reduzido, "Fulano via agente X"); **sub-decisões em debate (§7)** |
| 4 | Fronteira do ato | Direção confirmada; a abrir |
| 5 | Onde roda o agente | Direção confirmada (satélite de IA, chamando o core pelo catálogo; core nunca embute modelo); a abrir |
| 6 | MCP externo | A abrir — explicação no §8 |
| 7 | Conhecimento (LOM/RI/leis em camadas por município) | Direção confirmada; a abrir |
| 8 | Qualidade e custo | Direção confirmada; a abrir |

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

## 7. Eixo 3 — Identidade do agente · EM DEBATE

Direção confirmada: o agente age **em nome de** uma pessoa, com **escopo reduzido**, auditado como **"Fulano, via
agente X"**. Ponto de partida no código: o ator é `{:identidade-id :papeis :ente-id}` e já existe `ator-sistema`
para jobs (`kernel/autorizacao.clj`).

Sub-decisões propostas (recomendação entre parênteses — aguardando "Confirma?"):

- **3.1 Tipos de principal.** (a) só delegado · (b) delegado **+ agente institucional da Casa** (sem pessoa por
  trás — ex.: conferir todo requerimento protocolado contra a LOM/RI), restrito a `leitura` + `rascunho`, com todo
  resultado caindo numa fila para uma pessoa. *(Recomendado: b.)*
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

## 8. Eixo 6 — MCP externo · explicação (a abrir)

Até aqui "agente" = IA que **nós** construímos dentro do O Plenário. O Eixo 6 trata de **deixar IAs que não são
nossas se conectarem** ao O Plenário via MCP: o vereador no Claude/ChatGPT dele perguntando "quais projetos meus
estão parados na comissão?"; jornalista/cidadão consultando dados públicos; outro sistema (Prefeitura) usando o
mesmo caminho.

Decisões que o eixo terá de tomar: para quem abrir e em que ordem · como a IA de fora faz login em nome da pessoa
(Keycloak) · responsabilidade LGPD quando o dado da Câmara vai para o modelo que a pessoa escolheu (sai do nosso
controle) · custo e limite de abuso.

Direção provável a recomendar, em fases: (1) **dados públicos**, sem login; (2) **vereador autenticado, só
consultas**; (3) ações com efeito.

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
| 26/09/2026 | Sessão aberta; 8 eixos definidos; Eixo 1 e Eixo 2 CONFIRMADOS (B); direção dos Eixos 3, 4, 5, 7, 8 confirmada; Eixo 3 aberto em sub-decisões |
