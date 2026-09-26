# docs/26 — Plano de execução da Track IA

> **O análogo IA do `docs/13` (Track FE).** Transforma os 8 eixos confirmados em `docs/25-desenho-ia-na-plataforma.md`
> em ondas, fatias e marcos, **dentro do roadmap que o projeto já tinha**: doc-mestre §7 (ondas de IA), §8 (três
> forças de ordenação), §9 (dependências cruzadas), §15 (régua de escopo), §16.8 (Camada de Confiança mínima),
> §18 (ordem de construção da V1) e `docs/11` (Track IA como plano próprio; marcos M3/M4). **Ler o `docs/25` antes.**
>
> **Status (26/09/2026):** plano proposto — **revisão 2** (a revisão 1 não respeitava o roadmap; ver §7).
> **"Confirmo" do Daouda dado com o merge do PR #38 (26/09/2026)**; desenho consolidado no doc-mestre v1.46 (§22.11). **Em execução: base comum** (branch `claude/track-ia-base`).
> **Branch da frente:** `claude/track-ia` (aberta de `main` em 26/09/2026 com o desenho e este plano; as fatias
> seguem o trilho "uma branch por frente").
>
> **Regra-mãe (Eixo 1):** nenhuma feature de IA abre caminho próprio até os dados. Toda ação sobre a plataforma é
> **cliente do catálogo** (Eixo 2); o que faltar entra no catálogo, na mesma fatia.
>
> **Trilho de cada fatia (o de F0–F7 e da Track FE):** branch própria → TDD (red → green) → review `ecc` → merge.
> Mandato Docker (o Python do satélite também roda em container). Toda peça estrutural nova entra como ADR.

---

## 1. O que o projeto já tinha decidido e este plano segue

| Fonte | O que diz | Como o plano honra |
|---|---|---|
| Doc-mestre §7 | Onda 0 = pipeline de áudio com diarização + **corpus legal indexado**; Onda 1 (V1) = busca intra-câmara, resumo cidadão, copiloto de redação, **ata-IA** | As quatro capacidades da V1 estão no **escopo V1** deste plano; áudio e índice começam **agora**, não no fim |
| Doc-mestre §8 | Força **comercial**: ata-IA é a **feature-âncora de compra** (nº 1 motivo de troca, arma contra o SAPL); força de **confiança**: alto risco reputacional depois | A ata-IA ganha **faixa própria desde o início** (§3, Faixa A); MCP externo fica atrás de gatilho (§4) |
| Doc-mestre §9 | Busca semântica é **infra triplicada** ("pior lugar para economizar"); cadeia ata → resumo amplifica erro | **Um índice só** (proposições, transcrições, normas) servindo busca, copiloto e consulta à LOM/RI; qualidade da ata com avaliação desde o início |
| Doc-mestre §15 | Régua das 4 perguntas; sem requisito de cliente validado = parqueado | Tudo o que não é V1 (MCP externo, propostas de ato via IA de fora) está marcado **pós-V1, atrás de gatilho** |
| Doc-mestre §16.8 | Camada de Confiança mínima: **citação, indicação de incerteza, log auditável, reportar erro, revisão humana** | Os cinco entram na base comum (0.4), antes de qualquer feature |
| Doc-mestre §18 | Mês 0-1 Onda 0 em paralelo; mês 2-3 **endpoint de ingestão + CLI/pasta observada** + transcrição com **Caminho C**; mês 3-4 busca, resumo, copiloto, ata | Mesma sequência lógica (sem as datas, que eram do plano original de 4 meses) |
| Doc-mestre §10 | Especialista em regimento desde cedo; dataset golden (transcrição × ata humana anexada) | Curadoria normativa e avaliação da ata dependem dele; o caminho de **anexação de ata** coexiste e alimenta o golden |
| `docs/11` Track IA | Plano próprio; honrar o contrato §22.3 (eventos `core→IA` e `IA→core`, idempotência, `correlation_id`+`ente_id`, 6 categorias de erro) | A Faixa A **é** esse contrato, sem mudança |
| `docs/11` marcos | M3 incluía "editor + **copiloto (c/ IA)**"; M4 = "gravação → transcrição → **ata-IA revisável**" | MIA-3 entrega o copiloto que faltou no M3; MIA-4 fecha o M4 |

**Fora da V1 pelo roadmap (não entra aqui):** pipeline de vídeo e shorts, busca cross-câmara (Onda 2 / V1.5);
pauta inteligente, similaridade, camada de confiança robusta (V2); chatbot cidadão, briefing, constitucionalidade (V2.5+).

## 2. Ponto de partida (medido no código, 26/09/2026)

- **Core pronto do lado dele:** eventos de integração emitidos (`gravacao.segmento-*`, `proposicao.protocolada`);
  `policy.check` in-domain (`kernel/autorizacao.clj`); outbox + audit; SSE (`tempo_real`); Malli em `wire/in`·`wire/out`
  com codegen para TS; `legislativo.norma` com URN LexML; tribuna com instantes de fala (`fala_executada` — insumo do
  Caminho C); `ata_publicada` com `origem_redacao`.
- **Satélite de IA: zero código.** `prototipos/governanca-ia/` valida a forma do filtro (B1–B4) com 6 limitações
  listadas no README dele.
- **Keycloak 26.0.0** no compose. A delegação do Eixo 3.4 usa *token exchange* (RFC 8693); o suporte padrão parece
  ter chegado depois da 26.0 — **confirmar no spike B.2 e, se for o caso, subir a versão**.
- **Fatia 2a (PR #37)** entregou a tela "Novo requerimento" — é onde o copiloto entra.

## 3. A estrutura: uma base comum e duas faixas em paralelo

A revisão 1 punha tudo numa fila só (fundação de agente → normas → copiloto → **áudio e ata por último**), o que
empurrava a feature-âncora de compra para o fim. A ata-IA **não depende** do catálogo nem do agente: é artefato
produzido pelo satélite pelo contrato §22.3, já desenhado. Então:

```
            ┌── Faixa A · Artefatos (contrato §22.3) ── captação → transcrição → ata-IA → leitura; índice → busca; resumo
Base comum ─┤
            └── Faixa B · Agente (docs/25) ─────────── catálogo + identidade → consulta LOM/RI → copiloto → conferência
```

### Base comum (as duas faixas dependem dela — pequena, primeiro)

| Fatia | O quê | Origem |
|---|---|---|
| **0.1 ADR — satélite `apps/ia/`** | Python no monorepo, container, job de CI (decisão estrutural → ADR). | §22.2, `docs/11` |
| **0.2 Porta de inferência** | Adaptador fake determinístico + 1 fornecedor real por config (o fake não espera o jurídico); vendor usado sempre registrado. | §22.9 Eixos 10/13 |
| **0.3 Filtro de governança de produção** | Porta do protótipo corrigindo as 6 limitações (SHA-256, dígito verificador de CPF, guarda de payload vazio, contrato de erro, …). Único ponto de saída para o LLM (e, depois, para o MCP externo — 6.3). | B1–B4, Eixo 11h |
| **0.4 Camada de Confiança mínima** | As cinco peças do §16.8 como infra reutilizável: **citação**, **indicação de incerteza**, **log auditável**, **reportar erro**, **revisão humana** (+ estado "IA indisponível, siga pela tela", R-IA-1). | §16.8, R-IA-1 |
| **0.5 Harness de avaliação + custo** | Avaliação no CI do satélite (8.1); registro de custo por execução com `ente_id` (8.3 — medir; orçamento vem depois). | Eixo 8, §22.3.5 |

### Faixa A — Artefatos: áudio, ata-IA, índice, busca, resumo *(a feature-âncora)*

| Fatia | O quê | Origem |
|---|---|---|
| **A.1 PoC de áudio** *(começa junto da base)* | Medir DER e precisão com gravações reais de Baturité (YouTube serve para a PoC). Decide o tamanho da GPU e se o Caminho C basta. | v1.8 (PoC de DER antes de fechar a ata) |
| **A.2 Captação** | Endpoint de ingestão padronizado + utilitário CLI/pasta observada para o arquivo **local** do OBS. Baturité hoje só transmite ao YouTube: **recomendar gravar local também** (YouTube é contingência, §22.3.4). | §16.4, §18 mês 2-3 |
| **A.3 Transcrição + diarização** | ASR Whisper-class self-host (GPU alugada, dimensionada pela A.1); atribuição pelo **Caminho C** com os instantes de `fala_executada`; eventos `TranscricaoConcluida`/`TranscricaoAtribuida`/`TranscricaoRevisada`. | §22.6, `docs/11` |
| **A.4 Índice único** | Embeddings self-host + `pgvector` sobre **proposições + transcrições** (e normas, quando a B.4 chegar); busca híbrida (termo exato + sentido). | §9 ("infra triplicada"), §22.3.4 |
| **A.5 Busca intra-câmara** | Feature V1 sobre o índice. | §7 Onda 1 |
| **A.6 Ata-IA** | `AtaRascunhoPronta` → revisão humana → `ata_publicada` com `origem_redacao = gerada_automaticamente`; anexação de ata externa segue coexistindo e alimenta o golden. Avaliação ata humana × gerada. | §7, §16.4, §16.8 |
| **A.7 Leitura da ata** | Modo por Casa/sessão: **IA (voz sintetizada), presencial ou dispensada** (pedido do stakeholder); lê só a ata **revisada e aprovada**. | Pedido Baturité |
| **A.8 Resumo cidadão** | Rascunho → revisão → publicação no portal (vem **depois** da ata: a cadeia amplifica erro, §9). | §7 Onda 1 |

**Marco MIA-A — M4 fechado:** a sessão acontece, a ata sai por IA, é revisada, publicada e lida na sessão
seguinte; a busca acha o que foi dito em plenário.

### Faixa B — Agente: catálogo, conhecimento normativo, copiloto *(o desenho do `docs/25`)*

| Fatia | O quê | Eixo |
|---|---|---|
| **B.1 ADR — catálogo e adaptador MCP no core** | Onde a entrada mora na silhueta (ADR-0001); formato (nome, descrição p/ agente, Malli in/out → JSON Schema, classe `leitura`/`rascunho`/`ato`, papéis); conjuntos por público; **lint no CI** (rota nova sem entrada = falha, salvo "só-tela"; rotas existentes numa lista-base congelada); classe ausente = `ato`. Primeiras entradas: situação da matéria, tramitação, pauta. | 2, 4.4, 5.1 |
| **B.2 Identidade delegada** | Spike de token exchange (e upgrade do Keycloak se preciso); ator com `:via`; interseção reavaliada a cada chamada; audit "Fulano, via agente X"; **teste de vazamento ganha a dimensão agente**; agente institucional só `leitura`/`rascunho`. | 3 |
| **B.3 MCP interno + tela ↔ agente** | Servidor MCP no core (adaptador ao lado do HTTP); satélite chama por ele; core expõe "executar agente" em SSE; satélite nunca exposto. | 5.1, 5.2 |
| **B.4 Norma de referência + ingestão** | Norma por dispositivo com endereço estável; camadas federal/estadual/municipal (LOM do Município, RI da Câmara); coleta de fonte pública (LexML + LOM e RI de **Baturité** e **Fortaleza**) + upload; OCR; **conferência humana** e lote com efetivação. Entra no índice da A.4. | 7.1–7.4 |
| **B.5 Consulta à LOM/RI** *(V1 — decisão Daouda)* | Ferramentas **buscar/ler dispositivo**; toda afirmação normativa cita dispositivo lido na execução; mostra até quando o texto foi conferido. `referencia_normativa` de `tempo_regimental` aponta o dispositivo. | 7.5, 7.6 |
| **B.6 Proposta de ato** | Entidade genérica no core; confirmação **só na tela da plataforma** (2 toques); atos que o agente nem propõe; marcação de origem e execução contaminada. | 4.1–4.5 |
| **B.7 Copiloto do requerimento** *(o copiloto que o M3 previa)* | Na tela da fatia 2a: o vereador descreve em palavras; o agente escolhe o modelo, preenche, rascunha a justificativa com citação; o vereador revisa e assina pelo fluxo existente. | §7 Onda 1, 4.6 |
| **B.8 Conferência institucional** | `ProposicaoProtocolada` → rascunho de nota técnica com citações na fila da secretaria. | 3.1 b, 5.7, 7.7 |
| **B.9 Orçamento e painel da Casa** | Orçamento mensal por Casa (aviso a 80%, pausa do segundo plano primeiro); painel da Casa; avaliações de segurança completas. | 8.1–8.4 |

**Marco MIA-B — "o pedido do stakeholder, inteiro":** a secretaria consulta a LOM/RI com citação; o vereador pede
o requerimento em palavras, a IA redige, ele assina; a secretaria recebe a conferência automática.

### Ordem e prioridade entre as faixas

- **Base comum primeiro** (é pequena e as duas faixas precisam dela).
- **A.1 (PoC de áudio) começa no mesmo dia da base** — maior risco técnico e prazo de GPU.
- Se houver só uma frente de gente: **Faixa A tem prioridade** (feature-âncora de compra, §8; fecha M4). Com duas
  frentes, as faixas correm em paralelo. A B.5 (consulta LOM/RI) depende da B.4 e do índice da A.4.
- As datas do §18 (4 meses, ≥8 engenheiros) eram do plano original; o dimensionamento real é decisão do Daouda.

## 4. Pós-V1, atrás de gatilho (régua §15)

O desenho (Eixos 5 e 6) garante que o MCP externo sai quase de graça depois da B.3 — é o mesmo servidor. Mas ele
**não tem requisito de cliente validado** e não passa na régua do §15, então fica **parqueado com gatilho**:

| Item | Gatilho para puxar |
|---|---|
| **MCP público** (sem login, só consulta) | Comercial validar que ajuda a vender (transparência, observatórios, imprensa). Custo baixo; pode vir logo após a B.3. |
| **MCP autenticado** (vereador/servidor com a IA dele) | Uma Casa pedir + jurídico fechar o termo do agente público e a LGPD (6.3). |
| **Propostas de ato via MCP** | MCP autenticado estável em produção. |
| **Painel do operador** (`observabilidade-ia`) | Primeira Casa usando IA em produção (é fast-follow, R-IA-3/4). |

## 5. Pendências que não são de engenharia (destravar em paralelo)

| Pendência | Quem destrava | Trava |
|---|---|---|
| Fornecedor de LLM + DPA de não-treino + LGPD art. 33 | Jurídico + Daouda | Uso real de 0.2 (o fake não trava) |
| Retenção das conversas e anonimização para avaliação | Jurídico (LGPD) | B.3 em produção |
| Gravação local do OBS em Baturité | Cliente | A.2 em produção |
| GPU alugada na cloud-BR | Infra | A.3 (a A.1 mede o tamanho) |
| Alvo `ata < X min` | Daouda + cliente | A.6 (latência) |
| Voz sintetizada (fornecedor ou self-host) | Daouda | A.7 |
| Valores do orçamento por Casa | Comercial | B.9 |
| Licença de agregadores privados de leis municipais | Jurídico | B.4 (usar fonte oficial até lá) |
| Termo do agente público para MCP autenticado | Jurídico | §4 (pós-V1) |

## 6. Consolidação no documento-mestre (após o "Confirmo")

Proposta: nova subseção **`arquitetura/22-11-ia-como-ator.md`** (catálogo, identidade delegada, fronteira do ato,
onde roda o agente, MCP, conhecimento normativo, qualidade e custo) + linha no §24 (**v1.45**) + notas nos pontos
tocados: Inv. 3 (IA também age sobre a plataforma), §7 (consulta à LOM/RI entra na Onda 1 — decisão Daouda), §16.8
(citação por dispositivo, proposta de ato), §22.3 (MCP como canal agente → core), §22.5 (principal delegado e
agente institucional). **Estende; não altera decisões fechadas.**

## 7. Registro

| Data | O quê |
|---|---|
| 26/09/2026 | Revisão 1 proposta a partir dos 8 eixos confirmados em `docs/25` |
| 26/09/2026 | **Revisão 2** — alinhada ao roadmap do projeto: a revisão 1 empurrava áudio/ata-IA (feature-âncora, §8; Onda 0 no §7/§18) para a última onda, estreitava o índice às normas (contra §9), omitia a "indicação de incerteza" do §16.8 e punha o MCP externo no plano sem passar pela régua do §15. Agora: base comum + Faixa A (artefatos, prioritária) + Faixa B (agente) em paralelo; MCP externo pós-V1 atrás de gatilho |
