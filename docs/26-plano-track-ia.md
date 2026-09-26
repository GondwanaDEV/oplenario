# docs/26 — Plano de execução da Track IA

> **O análogo IA do `docs/13` (Track FE).** Transforma os 8 eixos confirmados em `docs/25-desenho-ia-na-plataforma.md`
> em ondas, fatias e marcos. **Ler o `docs/25` antes** — este plano não repete o porquê das decisões.
>
> **Status (26/09/2026):** plano proposto; depende do **"Confirmo" do Daouda** sobre o `docs/25` + este plano.
> Nenhuma fatia começou.
>
> **Regra-mãe (herdada do Eixo 1):** nenhuma feature de IA abre caminho próprio até os dados. Toda feature é
> **cliente do catálogo** (Eixo 2); o que faltar no catálogo entra no catálogo, na mesma fatia.
>
> **Trilho de cada fatia (o mesmo de F0–F7 e da Track FE):** branch própria → TDD (red → green) → review `ecc` →
> merge. Mandato Docker (Python do satélite também roda em container). Toda peça estrutural nova entra como ADR.

---

## 1. Ponto de partida (medido no código, 26/09/2026)

- **Core pronto do lado dele:** eventos de integração emitidos (`gravacao.segmento-*`, `proposicao.protocolada`);
  `policy.check` in-domain (`kernel/autorizacao.clj`); outbox + audit; SSE (`tempo_real`); esquemas Malli em
  `wire/in`·`wire/out` com codegen para TS (`codegen/`); `legislativo.norma` com URN LexML; tribuna com instantes
  de fala (`fala_executada` — insumo do Caminho C da ata).
- **Satélite de IA: zero código.** `prototipos/governanca-ia/` valida a forma do filtro (B1–B4) com 6 limitações
  listadas no README dele.
- **Rotas com forma de tela** (duplicadas por papel) — o catálogo nasce do lado dos `controllers`, não das rotas.
- **Keycloak 26.0.0** no compose. A delegação do Eixo 3.4 usa *token exchange* (RFC 8693); o suporte padrão
  ("standard token exchange") parece ter chegado depois da 26.0 — **confirmar no spike 0.3 e, se for o caso,
  subir a versão**.
- **Fatia 2a (PR #37)** entregou a tela "Novo requerimento" — é onde o copiloto (Onda 2) entra.

## 2. Eixo de ordenação

1. **Fundação antes de feature** — senão cada feature carrega seu pedaço de infra (é o avulso).
2. **Pedido de cliente real puxa a ordem** (Baturité): consulta LOM/RI → copiloto do requerimento → ata-IA +
   leitura. Daouda: consulta LOM/RI **entra na V1**.
3. **O maior risco técnico começa cedo, em paralelo:** qualidade de transcrição/diarização em câmara real (a PoC
   de DER que a v1.8 já mandava fazer antes de fechar a arquitetura da ata).
4. **Cada onda termina num marco demonstrável** (MIA-1…MIA-5), como MFE-1…4 na Track FE.

## 3. Ondas

### Onda 0 — Fundação: catálogo, identidade delegada, MCP, satélite

| Fatia | O quê | Eixo |
|---|---|---|
| **0.1 ADR-0006 — catálogo e adaptador MCP** | Onde a entrada do catálogo mora na silhueta do módulo (ADR-0001), formato da entrada (nome, descrição p/ agente, Malli in/out, classe `leitura`/`rascunho`/`ato`, papéis), conjuntos por público. **Lint no CI:** rota nova sem entrada = falha, salvo "só-tela" com motivo; rotas existentes entram numa lista-base congelada (não reescrever os 13 módulos). Classe ausente = `ato` (4.4). | 2, 4.4, 5.1 |
| **0.2 Primeiras entradas** | Consultas do legislativo: situação da matéria, tramitação, pauta da próxima sessão. Malli → JSON Schema gerado (mesmo espírito do `codegen/`). | 2 |
| **0.3 Identidade delegada** | Spike de token exchange no Keycloak (e upgrade se preciso). Ator ganha `:via {:agente-id :execucao-id}`; permissão = interseção (3.2), reavaliada a cada chamada; token de agente só em operação do catálogo; audit e eventos carregam pessoa + agente. **Teste de vazamento do CI ganha a dimensão "agente".** Agente institucional (3.1 b) como principal próprio, só `leitura`/`rascunho`. | 3 |
| **0.4 Servidor MCP no core** | Adaptador de entrada ao lado do HTTP (Inv. 5): `tools/list` e `tools/call` sobre o catálogo, filtrado pelo conjunto do token. Teste de contrato com o cliente MCP oficial (do lado do satélite). | 5.1 |
| **0.5 Satélite `apps/ia/`** | ADR-0007 (Python no monorepo, container, job de CI). Porta de inferência com adaptador fake determinístico + 1 fornecedor real por config; **filtro de governança de produção** (porta do protótipo corrigindo as 6 limitações: SHA-256, dígito verificador de CPF, guarda de payload vazio, contrato de erro, …); laço de agente fino + cliente MCP; registro de definições de agente versionadas (5.4); **harness de avaliação no CI** com o fake (8.1). | 5, 8.1 |
| **0.6 Tela ↔ agente** | Core expõe "executar agente" e repassa a resposta em SSE (5.2); satélite nunca exposto. Registro de custo por execução com `ente_id` (8.3 — só medir, ainda sem orçamento). Estado "IA indisponível, siga pela tela" (R-IA-1). | 5.2, 8.3 |

**Marco MIA-1 — "o agente existe e é auditável":** na tela de uma proposição, a pessoa pergunta "em que pé
está?" e o agente responde usando a ferramenta do catálogo; a auditoria mostra "Fulano, via agente X"; a mesma
pergunta feita pelo MCP interno dá a mesma resposta; um vereador de outra Casa não enxerga nada (teste de vazamento).

### Onda 1 — Conhecimento normativo + consulta à LOM/RI

| Fatia | O quê | Eixo |
|---|---|---|
| **1.1 Modelo da norma de referência** | Norma estruturada por dispositivo com endereço estável (URN + fragmento); camadas federal/estadual (referência supratenant) e municipal (LOM do Município, RI da Câmara); versão conferida com "conferida por X em dd/mm". Entra por **lote com efetivação** (fundação #2). | 7.1, 7.2, 7.4 |
| **1.2 Ingestão + conferência** | Upload da Casa e coleta de fonte pública (começar por LexML federal + LOM e RI de **Baturité** e **Fortaleza**); OCR; satélite quebra em dispositivos; **tela de conferência** para a pessoa aprovar antes de valer. | 7.3 |
| **1.3 Índice e ferramentas** | Embeddings self-host + `pgvector` no satélite (CPU basta no volume da V1); busca híbrida (termo exato + sentido); ferramentas **buscar dispositivos** e **ler dispositivo** no catálogo. | 7.5 |
| **1.4 Feature: consulta à LOM/RI** | Secretaria e vereador perguntam; resposta sempre cita dispositivo lido na mesma execução (senão "sem fonte"); mostra até quando o texto foi conferido. Avaliações com perguntas de resposta conhecida. | 7.5, 8.1 |
| **1.5 Regra ↔ dispositivo** | `referencia_normativa` passa a apontar o dispositivo, começando por `tempo_regimental` (casa com a tela de tempos da tribuna, que segue pendente fora desta track). | 7.6 |

**Marco MIA-2 — "a IA sabe a lei da Casa":** a secretaria de Baturité pergunta "qual o quórum para aprovar
emenda à Lei Orgânica?" e recebe a resposta com o artigo citado e clicável.

### Onda 2 — Rascunhos, propostas de ato e o copiloto do requerimento

| Fatia | O quê | Eixo |
|---|---|---|
| **2.1 Rascunho e proposta de ato** | Entidade genérica de proposta de ato no core; **confirmação só na tela da plataforma**, reusando o ritual de assinatura em 2 toques; lista dos atos que o agente nem propõe (voto, presença, condução ao vivo); marcação de origem e execução "contaminada" + bloqueio de levar dado restrito para fora. | 4.1–4.5 |
| **2.2 Copiloto do requerimento** | Na tela da fatia 2a: o vereador descreve o pedido em linguagem natural; o agente escolhe o modelo, preenche os campos, rascunha a justificativa com citação da LOM/RI; o vereador revisa e assina pelo fluxo que já existe. | 1, 4.6 |
| **2.3 Agente institucional: conferência** | `ProposicaoProtocolada` → agente lê os dispositivos aplicáveis → **rascunho de nota técnica com citações** na fila da secretaria. | 3.1 b, 5.7, 7.7 |
| **2.4 Segurança, custo e painel da Casa** | Avaliações de segurança completas (instrução escondida em e-SIC/participação/PDF); orçamento mensal por Casa com aviso a 80% e pausa dos agentes de segundo plano primeiro; painel da Casa (consumo, aceitação, erros reportados). | 4.5, 8.1–8.4 |
| **2.5 Resumo cidadão + busca semântica** | As outras duas capacidades de IA da V1, agora como clientes do catálogo: resumo é rascunho → revisão → publicação no portal; busca reusa o índice da 1.3. | 1 |

**Marco MIA-3 — "o pedido do stakeholder, inteiro":** o vereador pede em palavras, a IA redige com citação, ele
assina; a secretaria recebe a conferência automática contra a LOM/RI; a auditoria mostra quem fez o quê e via
qual agente.

### Onda 3 — Áudio, ata-IA e leitura da ata (fecha M4)

| Fatia | O quê |
|---|---|
| **3.1 Captação** | Ingestão da gravação local do OBS pelo utilitário CLI/pasta observada (§16.4). Baturité grava com OBS transmitindo ao YouTube: **recomendar gravar local também** (YouTube é só contingência, §22.3.4). |
| **3.2 Transcrição + diarização** | ASR Whisper-class self-host (node pool de GPU alugado, §22.9); atribuição de falante pelo **Caminho C**, usando os instantes de `fala_executada` da tribuna. |
| **3.3 Ata-IA** | Rascunho → revisão humana → `ata_publicada` com `origem_redacao = gerada_automaticamente` (esquema já existe). |
| **3.4 Leitura da ata** | Modo por Casa/sessão: **IA (voz sintetizada), presencial ou dispensada** (pedido do stakeholder); a IA lê a ata **revisada e aprovada**, nunca o rascunho. |

**Marco MIA-4 — M4 fechado:** a sessão acontece, a ata sai por IA, é revisada, publicada e lida na sessão seguinte.

**Trilha paralela desde a Onda 0 — PoC de áudio:** medir DER e precisão com gravações reais de Baturité (o
YouTube serve para a PoC). Decide o dimensionamento da GPU e se o Caminho C basta. É o maior risco técnico;
não esperar a Onda 3.

### Onda 4 — MCP externo e operação

| Fatia | O quê | Eixo |
|---|---|---|
| **4.1 MCP público (fase 1)** | Endereço MCP por Casa, sem login, só consultas públicas, com cache e limite; catálogo externo versionado como contrato. **Pode ser puxado para logo depois da Onda 1** se o comercial quiser (custo baixo, bom para venda). | 6.1, 6.4, 6.5 |
| **4.2 MCP autenticado (fase 2)** | OAuth 2.1 com Keycloak; tela de consentimento (validade, revogação); `admin_ente` escolhe os clientes permitidos (padrão: nenhum); saída passa pelo mesmo filtro da porta. | 6.2, 6.3 |
| **4.3 Painel do operador** | A tela `observabilidade-ia` já desenhada (volume, p95, custo, fallback por recurso e fornecedor). | 8.4 |
| **4.4 Propostas de ato via MCP (fase 3)** | Só depois da 4.2 estável; confirmação sempre na plataforma. | 6.1, 4.2 |

**Marco MIA-5 — "a plataforma tem MCP":** o vereador conecta a IA que já usa e consulta a sua Casa com o login
dele; um jornalista consulta os dados públicos sem login.

## 4. Pendências que não são de engenharia (destravar em paralelo)

| Pendência | Quem destrava | Trava |
|---|---|---|
| Fornecedor de LLM + DPA de não-treino + LGPD art. 33 | Jurídico + Daouda | Onda 0.5 com fornecedor real (o fake não trava) |
| Retenção das conversas e anonimização para avaliação | Jurídico (LGPD) | Onda 0.6 em produção |
| Valores do orçamento por Casa | Comercial | Onda 2.4 |
| Termo do agente público para MCP autenticado | Jurídico | Onda 4.2 |
| Licença de agregadores privados de leis municipais | Jurídico | Onda 1.2 (usar fonte oficial até lá) |
| Voz sintetizada (fornecedor ou self-host) | Daouda | Onda 3.4 |
| Alvo `ata < X min` | Daouda + cliente | Onda 3.3 (latência) |
| GPU alugada na cloud-BR | Infra | Onda 3.2 (a PoC mede o tamanho) |

## 5. Consolidação no documento-mestre (após o "Confirmo")

Proposta: nova subseção **`arquitetura/22-11-ia-como-ator.md`** (catálogo, identidade delegada, fronteira do ato,
onde roda o agente, MCP externo, conhecimento normativo, qualidade e custo) + linha no §24 do doc-mestre
(**v1.45**) + nota nos pontos tocados: Inv. 3 (IA também age sobre a plataforma), §16.8 (citação por dispositivo,
proposta de ato), §22.3 (MCP como canal agente → core), §22.5 (principal delegado e agente institucional).
**Não altera** decisões fechadas — estende.

## 6. Registro

| Data | O quê |
|---|---|
| 26/09/2026 | Plano proposto a partir dos 8 eixos confirmados em `docs/25`; aguarda "Confirmo" do Daouda |
