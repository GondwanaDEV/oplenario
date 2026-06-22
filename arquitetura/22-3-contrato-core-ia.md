# 22.3 Contrato entre core e Plataforma de IA

> *Parte do SSOT (documento-mestre). Versão canônica do conjunto em `documento-mestre-camaras.md` §24.*
> *Em conflito com memória de chat antigo, este arquivo prevalece.*

A fronteira entre o core monolítico modular e o satélite Plataforma de IA é definida por cinco decisões interligadas: topologia de comunicação, protocolo concreto, fluxo de domain events, propriedade de dados, e modelo de erros e retry.

## 22.3.1 Topologia de comunicação

**Híbrida por classe de operação.** A topologia é escolhida por *operação*, não por *feature*:

- **Síncrono request-response** para operações interativas com latência alvo < 2s e resposta única — copiloto de redação, busca semântica, resumo de proposição curta.
- **Assíncrono via fila + evento de retorno** para operações > 10s ou em background — transcrição, geração de ata, embeddings em lote, bulk de áudio histórico.
- **Streaming** para operações interativas que produzem output incremental — copiloto que escreve token a token, transcrição ao vivo durante sessão.

Disciplinas derivadas: fila persistente como infra obrigatória desde o dia 1; idempotência ou chave de deduplicação obrigatórias em toda operação assíncrona; tracing distribuído com `ente_id` + `correlation_id` atravessando a fronteira.

## 22.3.2 Protocolo concreto

| Modo | Protocolo | Uso primário |
|---|---|---|
| Síncrono | HTTP/JSON com OpenAPI | Copiloto, busca semântica, resumo curto |
| Assíncrono | Bus de eventos + filas de comando | Transcrição, ata, embeddings em lote, bulk |
| Streaming | SSE | Copiloto incremental, transcrição ao vivo |

Disciplinas derivadas: schema versionado em todo lugar (OpenAPI no síncrono, schema explícito por evento e por comando no assíncrono); idempotency keys obrigatórias em endpoints síncronos com efeito colateral; distinção semântica preservada entre evento de domínio (passado: `SessaoEncerrada`) e comando (imperativo: `TranscreverSessao`) — confundir os dois é o caminho mais rápido pra fronteira virar bagunça.

gRPC explicitamente avaliado e descartado para a V1: ganho marginal dado o volume de chamadas, ferramental e codegen viram custo desnecessário, ergonomia HTTP/JSON com OpenAPI casa melhor com a separação core ↔ presentation (Invariante 5) e com a futura exposição como API pública na V2.

**Segurança de transporte da fronteira (§22.9 Eixo 11, v1.40).** O canal carrega **áudio bruto + dado pessoal antes do filtro**, então transporte cifrado é obrigatório, não opcional. Intra-cluster (satélite de IA self-hosted) = **TLS server-side via CA interna + `NetworkPolicy`** na V1 (mesh/mTLS bilateral diferido à Rota D); para o **frontier LLM externo** (Eixo 10) = TLS público do vendor + API key, **não** mTLS bilateral, **condicional ao modo** (externo vs self-host). A soberania do prompt que cruza a fronteira nacional é **`[GAP]` regulatório** (LGPD art.33 + DPA de não-treino), decidido na passada jurídica — **não** "transporte resolvido".

## 22.3.3 Fluxo de domain events — buses separados com eventos de integração

**Buses lógicos separados.** Core tem seu bus interno (eventos de domínio para coordenação entre bounded contexts internos, schema interno, evolui livremente). Plataforma de IA tem o dela. A fronteira passa por **eventos de integração** — conjunto explícito, pequeno, versionado, com contrato deliberado.

Eventos de domínio interno **não atravessam** a fronteira. Eventos de integração são candidatos naturais a virar webhooks públicos na V2.

Eventos de integração iniciais (V1):

*Do core para a Plataforma de IA:* `ProposicaoProtocolada`, `ProposicaoAtualizada`, `SessaoEncerrada`, `AtaRevisadaEPublicada`, `AudioHistoricoIngerido`.

*Da Plataforma de IA para o core:* `TranscricaoConcluida(sessao_id, transcricao_uri, ...)`, `AtaRascunhoPronta(sessao_id, ata_rascunho_uri, ...)`, `ResumoCidadaoPronto(proposicao_id, resumo_uri, ...)`, `EmbeddingsGerados(entidade_id, ...)`, eventos de erro (`TranscricaoFalhou`, `AtaFalhou`, `ResumoFalhou`).

Disciplinas derivadas: promoção de evento interno para evento de integração é decisão arquitetural com review (compromisso de longo prazo, vira webhook na V2); schema versionado com rigor (versão no nome ou no payload, breaking changes coexistem em transição); cada evento de integração entra no audit log do lado emissor; separação lógica não exige separação física — pode ser uma única infra de mensageria com streams/topics dedicados.

## 22.3.4 Propriedade de dados — dividida por natureza do artefato

| Artefato | Vive em | Por quê |
|---|---|---|
| Áudio bruto | Object storage compartilhado (BR, S3-compatible) | Grande, imutável, ambos os lados acessam |
| Transcrição diarizada | Plataforma de IA | Artefato técnico, versionado por modelo, reprocessável |
| Ata em rascunho | Plataforma de IA (transitório) | Output bruto antes de revisão humana |
| Ata revisada e publicada | Core | Artefato legal, sob mesmo regime de auditoria/RLS/retenção |
| Resumo cidadão (rascunho) | Plataforma de IA | Output bruto |
| Resumo cidadão (publicado) | Core | Vai para o portal público |
| Embeddings vetoriais | Plataforma de IA | Modelo + embeddings ficam acoplados; busca local |
| Logs de inferência | Plataforma de IA | Insumo de eval framework, não audit log de produto |

**Promoção rascunho → publicado é fluxo cross-side explícito:** servidor revisa via UI do core (que lê rascunho da IA via API); aprovação dispara cópia do conteúdo final para tabela do core; core emite `AtaRevisadaEPublicada`; o ato de copiar é o que transfere ownership.

**Busca semântica via API síncrona à IA** (sem replicação de embeddings no core): core recebe query → IA computa embedding e faz similarity search → retorna IDs com score → core busca dados completos nas suas tabelas.

**Caminho de ingestão de áudio bruto (decisão v1.6, casa com §16.4).** Áudio bruto vive em object storage compartilhado em região BR; o caminho até o storage tem três fontes possíveis em cascata, escolhidas conforme premissa de cadeia operacional da câmara:

1. **Fonte primária — gravação local pós-sessão (V1).** Arquivo produzido pelo OBS (ou por appliance proprietário) sobe para o storage via endpoint de ingestão padronizado. Sem reencoding, autoritativo, sem dependência de plataforma externa para artefato legal. Suficiente para V1 (ata pós-sessão, transcrição em batch). Caminho operacionalizado pelo utilitário CLI/watch folder mínimo da §16.4.
2. **Fonte secundária — RTMP duplicado durante a sessão (V2+, depende de satélite).** Quando o satélite Plugin de Captura Sincronizada for construído (§16.4), a ferramenta de captação pode streamar simultaneamente para o destino de transmissão (YouTube Live, tipicamente) e para um endpoint nosso. No caso de OBS — primeiro adaptador concreto provável pela prevalência de mercado — isso é viabilizado pelo plugin "Multiple RTMP Outputs" (estável e oficial no ecossistema OBS). Outras ferramentas de captação têm capacidades equivalentes próprias. Permite transcrição quase ao vivo durante a sessão (latência 2-5s). Necessário para features V2+ (alertas regimentais ao vivo, legendas em tempo real, painel de votação reativo).
3. **Fonte terciária — YouTube Live API (fallback de contingência).** Existe e funciona, mas tem latência maior, qualidade reencoded, e cria dependência em sistema externo. Útil só como contingência (gravação local corrompeu, RTMP duplicado fora). Não é candidato a fonte primária.

A taxonomia de propriedade de dados (tabela acima) **não depende da fonte de ingestão** — uma vez no object storage, "áudio bruto" é áudio bruto. A fonte fica registrada como metadata do objeto (`fonte_ingestao` ∈ `{gravacao_local_pos_sessao, rtmp_duplicado_ao_vivo, youtube_api_fallback, importacao_legado}`) para fins de auditoria, debugging de qualidade e métricas operacionais. Disciplina derivada: contratos de eventos `SessaoEncerrada` e `AudioHistoricoIngerido` não mudam por fonte; quem consome o áudio (Plataforma de IA) tem comportamento uniforme; diferenças de latência e qualidade são contornadas por políticas operacionais, não por fluxos diferentes na fronteira.

Disciplinas derivadas: object storage entra como infra arquitetural na North Star (não é mera escolha de stack); URIs como cidadãos de primeira classe nos contratos (conteúdo grande é fetched on demand, não trafega inline); LGPD direito ao apagamento orquestrado via evento `ApagamentoSolicitado(sujeito_id)` consumido pelos dois lados; backup/restore é tripé (core + IA + storage), DR coordena os três.

## 22.3.5 Modelo de erros e retry

Falhas em fronteira IA são qualitativamente diferentes de falhas em endpoint CRUD comum — têm gradiente. Taxonomia de seis categorias com tratamento específico em cada uma:

1. **Falha de infraestrutura** (IA fora, rede, fila): síncrono → timeout do cliente, UI mostra erro, sem retry server-side; assíncrono → backoff exponencial 3-5 tentativas em ~30min, depois dead-letter queue.
2. **Falha por sobrecarga** (saturação, rate limit do provedor de LLM): circuit breaker no chamador + backpressure na fila + rate limit por ente.
3. **Falha de input** (input inválido, áudio corrompido): sem retry; síncrono → 422 com mensagem clara; assíncrono → evento de falha imediato.
4. **Falha de modelo** (saída inválida estruturalmente): retry curto 2-3 vezes (LLM é não-determinístico); se persistir, log completo e operação falha pra inspeção humana.
5. **Saída plausível mas errada** (alucinação): **NÃO** é problema técnico — é resolvido pelo workflow de revisão humana (Camada de Confiança §16.8). Toda saída de IA é proposta; humano revisa antes de virar artefato legal.
6. **Saída com baixa confiança**: score/sinal de confiança propagado como metadata; UI de revisão sinaliza ("revisar com atenção").

Disciplinas derivadas:

- Toda fronteira síncrona retorna erros com schema estruturado (categoria + detalhes), versionado.
- Toda operação assíncrona é idempotente; retry com backoff é responsabilidade da fila/worker, com limite explícito; DLQ com ferramenta operacional para inspecionar, re-enfileirar, descartar.
- Falha tipo 5 não é tratável tecnicamente — Camada de Confiança da §16.8 é a defesa.
- Métricas por categoria de erro, latência, custo de inferência, e score de confiança são obrigatórias; dashboard dedicado à fronteira IA é requisito operacional.
- Três defesas contra sobrecarga: circuit breaker no chamador, backpressure na fila, rate limit por ente.
- Fallback de modelo é política operacional interna da Plataforma de IA, transparente para o core. **Failover vendor→vendor em runtime (fundação #4, R-IA-2, v1.43) é a EXTENSÃO desta cláusula, não conceito novo** — a porta de inferência vendor-agnóstica (§22.9 Eixos 10/13) mantém lista ordenada (1 primário + N secundários) atrás da porta, transparente ao core. **Gatilho:** erro de infra / timeout / rate-limit persistente (cat.1/2) em ambos os regimes; **latência-como-gatilho** (re-rotear o assíncrono por p95 estourado) é forma cravada mas **bloqueada até o alvo `ata < X min` (abaixo) deixar de ser `[GAP]`**; **custo NÃO dispara failover** (vira teto por ente = R-IA-3, classe à parte). Síncrono < 2s não faz failover em série (estouraria o orçamento → cai em R-IA-1 inline); assíncrono tem folga. **Estado parcial:** failover só na fronteira da operação — assíncrono descarta o parcial e regenera no secundário sob a **mesma chave de dedup da operação de domínio** (estável através do re-enfileiramento, não da tentativa-de-vendor; ponto de dedup = escrita do artefato, idempotente mesmo se o primário ressuscitar — reuso da disciplina de idempotência acima); streaming/síncrono **não** faz failover no meio (encerra → R-IA-1); **nunca costura saída de dois vendors** numa só geração (regressão silenciosa). **Piso:** se todos os vendors caem, o estado é **R-IA-1** ("IA fora, siga manual", §16.8) — a IA é produtividade, o ato nunca bloqueia. **Proveniência:** o vendor efetivamente usado é sempre carimbado (§8.6/G19) e, quando vier de secundário, **sinalizado ao revisor que assina** (selo discreto na tela de revisão), não só ao operador. A lista de vendors elegíveis é governada pelo `[GAP]` regulatório por-vendor do §22.9 Eixo 11(h)/13 (DPA de não-treino + LGPD art.33). Materialização = **fast-follow** (não gateia go-live).
- SLO da fronteira IA é separado do SLA do core; latência por classe de operação tem alvo próprio (busca < 2s p95, ata em < X min pós-sessão).

## 22.3.6 Decisões arrastadas que viram parte da North Star

Algumas decisões fechadas neste contrato têm escopo maior que apenas a fronteira core ↔ IA — viram premissas para o resto da arquitetura:

- **Object storage compartilhado em região BR** entra como infra arquitetural (não só stack).
- **Fila persistente** entra como infra obrigatória desde o dia 1.
- **SSE como protocolo de streaming** já está adotado entre core e IA — facilitou a decisão sobre real-time para o cliente final (modelo fechado em §22.6 eixo G).
- **Distinção evento de domínio vs. evento de integração** vira disciplina de design para qualquer fronteira futura (Migração quando virar satélite, módulos extraídos do monolito no futuro, APIs públicas da V2).
