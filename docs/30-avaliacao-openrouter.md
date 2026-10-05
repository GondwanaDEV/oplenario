# OpenRouter como intermediário para os fornecedores de LLM — avaliação técnica

- **Status:** ✅ decidido em 05/10/2026 pela [ADR-0023](adr/0023-openrouter-como-fornecedor-de-modelo-de-linguagem.md):
  o OpenRouter é o fornecedor de modelo de linguagem de toda a plataforma (contra o item 1 da recomendação abaixo).
  A avaliação fica como registro; o `[GAP]` jurídico que ela descreve continua valendo para ligar em produção.
- **Pergunta:** vale pôr o OpenRouter entre o satélite `apps/ia` e os fornecedores de modelo de linguagem?
- **Relacionadas:** ADR-0006 (satélite e porta de inferência), ADR-0014 (orçamento de IA),
  `arquitetura/22-9-stack.md` Eixos 10, 11(h) e 13 (failover), `arquitetura/22-3-contrato-core-ia.md` §22.3.5,
  `docs/25` §5.6 e §8, `docs/26` (`[GAP]` jurídico do fornecedor).

## Veredito

**Para dado de Casa em produção, não agora. Para avaliar modelos, sim, e é barato.**

O OpenRouter não resolve o bloqueio real do fornecedor, que é jurídico: DPA de não-treino e transferência
internacional pela LGPD art. 33. Ele **acrescenta um operador** na cadeia, com contrato próprio, e não tem região no
Brasil. Hoje o único DPA assinável é o do plano Enterprise, e a empresa está no meio de uma troca de controle (compra
pela Stripe, anunciada em 19/08/2026). O ganho técnico que ele oferece — uma chave e uma API para centenas de modelos,
com failover entre provedores — é o que mais vale para **medir modelos** (R-IA-4, `docs/25` §8.1) com o conjunto de
avaliação, que não leva dado de Casa. Para produção, o caminho mais curto continua sendo contratar o fornecedor direto
(ou o Bedrock, se houver modelo em `sa-east-1`) e implementar o failover que o Eixo 13 já desenhou atrás da porta.

> **Limites das fontes.** O proxy de saída desta sessão bloqueou `openrouter.ai`, `trust.openrouter.ai` e a imprensa
> (CNBC, Payments Dive). Tudo sobre o OpenRouter abaixo veio de **trechos de busca** — da documentação oficial quando o
> trecho a identificava, de terceiros nos demais casos — e está marcado **[trecho]** ou **[secundário]**. Antes de
> decidir, conferir na fonte: preços e taxas, a lista de recursos repassados no endpoint compatível com a Anthropic, o
> DPA e o status do fechamento da compra. O que se diz do nosso código foi lido no repositório, com `arquivo:linha`.

## O que o satélite faz hoje

O satélite já tem a peça que um gateway costuma oferecer: **uma porta de inferência neutra**, com fornecedor por
configuração. Isso pesa na avaliação, porque o OpenRouter competiria com algo que já existe.

- **Porta e adaptadores.** `PortaInferencia` (`apps/ia/src/oplenario_ia/inferencia/porta.py:10-16`) com dois
  adaptadores: `fake` (padrão) e `anthropic` (`inferencia/anthropic_adapter.py`), escolhidos por `OPLENARIO_IA_VENDOR`
  (`config.py:16,43-48`). O adaptador Anthropic usa o SDK oficial, `max_retries=0` e timeout de 60 s (`:36`), e chama só
  `messages.create` síncrono com texto (`:48-61`). **Não usa** streaming, tool use nativo, prompt caching, Citations,
  saída estruturada nem Batch. Tool use, citação e JSON são feitos no prompt e conferidos de forma determinística
  (`agente/laco.py:38-49,173-194`; `confianca/citacao.py`).
- **O que já não sai do cluster.** Embeddings (`fastembed`, `busca/fastembed_embedder.py`) e transcrição/diarização
  (`sherpa-onnx`, `transcricao/sherpa.py`) são self-host. **O OpenRouter só tocaria o modelo de linguagem.**
- **Governança antes da porta.** O filtro B1–B4 (`governanca/filtro.py`) barra peça sigilosa, redige CPF/CNPJ/e-mail e
  registra só hashes. Ele **não tem noção de fornecedor aprovado, DPA ou região**: carimba `porta.vendor` e pronto
  (`filtro.py:86`).
- **Custo e cota.** Tokens vêm do `usage` da resposta; o custo sai da tabela local `avaliacao/precos.json`, por chave
  `vendor/modelo`. Modelo sem preço dá custo `None`, não zero (`avaliacao/custo.py:45-55`).
- **Resiliência.** Retry só no trabalhador assíncrono, com backoff de 30 s·2ⁿ (`trabalhador.py:89-107`). **Não existem
  ainda** circuit breaker, rate limit por Casa nem failover entre fornecedores — o Eixo 13 os desenhou como fast-follow,
  num "adapter-roteador composto atrás da porta" com breaker em Valkey (`arquitetura/22-9-stack.md:122-132`).

## O que o OpenRouter oferece, contra o que precisamos

### 1. Encaixe técnico: barato

O OpenRouter expõe um endpoint compatível com a API de Mensagens da Anthropic (`/api/v1/messages`), além do formato
OpenAI **[trecho]**. Com isso, um adaptador `openrouter` seria o adaptador Anthropic de hoje com outra `base_url` e
outra chave. A troca mexe em quatro lugares:

1. `Vendor = Literal["fake","anthropic","openrouter"]` em `config.py`, mais a variável da chave;
2. o adaptador (ou um parâmetro `base_url` no atual) e o mapeamento de erro, porque os códigos de erro e o
   `request-id` passam a ser os do OpenRouter;
3. entradas em `precos.json` com a chave `openrouter/<modelo>`, já somando a taxa da plataforma;
4. o roteamento por requisição, no corpo: `provider: {only: [...], allow_fallbacks: false, zdr: true,
   data_collection: "deny"}` **[trecho]**. Pelo SDK, isso vai em `extra_body`; **conferir** se o endpoint compatível
   com a Anthropic aceita o campo `provider`.

O `test_arquitetura.py` já confina SDK de fornecedor a `inferencia/*_adapter.py`, então a fronteira fica protegida.
Um dia de trabalho, com teste. **O custo de integrar não é o argumento — nem a favor, nem contra.**

Duas ressalvas de desenho. O ADR-0006 pede "um adaptador por fornecedor real, com SDK oficial" e descartou "HTTP cru"
(`docs/adr/0006`, `:26-30,78-81`); usar o SDK da Anthropic contra um compatível de terceiro é zona cinzenta, e precisa
de nota no ADR. E o `msg.model` que gravamos na proveniência passa a ser o nome do OpenRouter; o provedor que de fato
atendeu precisa vir da resposta, ou o registro perde o "vendor usado" que o Eixo 13 (E5) exige.

### 2. Dado e LGPD: o OpenRouter piora o `[GAP]`, não o resolve

Este é o ponto que decide.

- **Mais um operador na cadeia.** Hoje: Câmara (controladora) → O Plenário (operador) → fornecedor do modelo. Com o
  OpenRouter: Câmara → O Plenário → OpenRouter → provedor que atendeu. O DPA de não-treino e a avaliação do art. 33
  passam a ser **dois contratos**, e o segundo (OpenRouter ↔ provedor) não é nosso — controlamos por um parâmetro da
  requisição, não por cláusula. "Um toggle não é um contrato" é a crítica recorrente **[secundário]**.
- **Sem Brasil.** O roteamento em região existe para **UE e EUA**, nos planos Business e Enterprise, por
  `eu.openrouter.ai` e `us.openrouter.ai` **[trecho]**. O plano padrão roda na borda global da Cloudflare, sem garantia
  de residência **[secundário]**. A arquitetura prefere "in-region / BR-soberano onde der"
  (`arquitetura/22-9-stack.md:11,14,81`); pelo OpenRouter, todo prompt sai do país.
- **DPA.** Há DPA incorporado ao Enterprise Access Agreement; no autoatendimento, não há DPA assinado **[trecho]**. É um
  DPA de modelo GDPR (art. 28). Para a LGPD, a transferência por cláusula contratual exige as **cláusulas-padrão da
  ANPD adotadas na íntegra e sem alteração** (Resolução CD/ANPD nº 19/2024, Anexo II; prazo de adequação encerrado em
  23/08/2025) **[trecho]**. Um aditivo com as cláusulas da ANPD seria negociação de Enterprise, não um formulário.
- **Retenção.** O OpenRouter não guarda prompt nem resposta salvo opt-in; guarda metadados (tokens, latência, modelo,
  custo) **[trecho]**. ZDR é imposto por conta, por grupo de modelos ou por requisição (`zdr: true`), e cobre só a
  inferência, não plugins como busca na web **[trecho]**. Do lado da Anthropic, os "Covered Models" (Fable 5 e 5.1,
  Mythos 5 e 5.1) exigem 30 dias de retenção em qualquer plataforma e ficam fora de ZDR **[trecho]**. O nosso padrão,
  `claude-opus-5`, não está nessa lista; mas `zdr: true` vai excluir esses modelos do roteamento, e isso precisa estar
  escrito.
- **Troca de controle.** A Stripe anunciou a compra do OpenRouter em 19/08/2026 (valor reportado acima de US$ 7 bi);
  em agosto o fechamento estava "sujeito às condições usuais", sem data confirmada **[trecho]**. Termos, preços e
  políticas de dado podem mudar no fechamento. Assinar DPA com uma empresa nesse estado é risco de contrato, não de
  engenharia.

### 3. Custo

- **Preço do modelo:** o de tabela do provedor. **Taxa da plataforma:** 5,5 % na compra de créditos (mínimo US$ 0,80);
  em BYOK (chave própria do provedor), 5 % sobre o que passar de US$ 25 mil/mês de inferência a preço de tabela, regra
  mudada em agosto/2026; o plano Business, que dá o roteamento em região, cobra 8 % **[secundário]**.
- **Na nossa escala**, isso é pequeno em valor absoluto, e o roteamento em região é o item caro. O ponto que importa é
  outro: **a cota por Casa (ADR-0014) e a tabela `precos.json` passam a precisar da taxa somada**, ou o painel do
  administrador mostra menos do que se paga.
- **Batch.** O OpenRouter tem Batch próprio (`/api/v1/batches`, inclusive no formato Anthropic) **[trecho]**. O Batch
  da Anthropic, com 50 % de desconto, só existe direto na Anthropic **[secundário]**. O satélite hoje não usa nenhum dos
  dois; o resumo cidadão e a nota técnica (segundo plano) seriam os candidatos.
- **Prompt caching** é repassado à Anthropic (`cache_control`, com roteamento "pegajoso" para o mesmo provedor)
  **[trecho]**. Também não usamos ainda; é alavanca de custo prevista em `docs/25` §8.3.

### 4. Confiabilidade e latência

- **Latência acrescida:** entre ~15 e ~40 ms, conforme a página **[trecho]**. Irrelevante contra a geração de uma ata
  ou um resumo.
- **Disponibilidade:** a disponibilidade passa a ser OpenRouter × provedor. Houve incidentes com 80–90 % de falha em
  17 e 19/02/2026 (camada de cache derrubando conexões, devolvendo 401) e falhas curtas em 23–24/09/2026 **[trecho]**.
  O ganho é o failover entre provedores **do mesmo modelo** (Anthropic direto, Bedrock, Vertex) — exatamente o que
  `allow_fallbacks: false` e `only: [...]` desligariam, se a lista elegível tiver de ser só a que tem DPA.
- **Para nós o impacto é contido:** a IA não está no caminho crítico da sessão (Eixo 13, E1: "a câmara fecha ata sem
  IA"), e o estado R-IA-1 já cobre "IA fora, siga manual".

### 5. Aderência às regras que a arquitetura já fixou

| Regra fixada | Com o OpenRouter |
|---|---|
| Porta neutra, fornecedor trocável por config, sem lock-in (`22-9-stack.md:81,84`) | Cabe como mais um adaptador. Não cria lock-in de código; cria dependência de um intermediário. |
| Failover só para vendor com DPA de não-treino + art. 33 (`22-9-stack.md:130`) | O failover nativo do OpenRouter **contraria** a regra se ligado; desligado (`allow_fallbacks: false`), perde o principal benefício. |
| Nunca degradar soberania em silêncio; mudança de região é decisão registrada (`22-9-stack.md:130`) | Exige travar `provider.only` e a região na config, e auditar que o provedor que atendeu está na lista. |
| Vendor usado sempre na proveniência; selo de vendor secundário para quem assina (Eixo 13, E5) | Depende de ler o provedor real na resposta; o `model` sozinho não basta. |
| Filtro pré-porta idêntico para todo adapter (`22-9-stack.md:130`) | Nada muda: o filtro roda antes de qualquer adaptador. |
| Avaliação obrigatória antes de trocar modelo (`docs/25` §8.1, R-IA-4) | **Melhora:** uma chave dá acesso a vários modelos para rodar `oplenario-ia-avaliar`. |

## Recomendação

1. **Não usar o OpenRouter no caminho de dado de Casa agora.** A decisão de fornecedor real continua presa ao `[GAP]`
   jurídico, e o OpenRouter não o encurta: soma um contrato, tira a chance de região no Brasil e chega num momento de
   troca de controle.
2. **Usar o OpenRouter só na avaliação, se quisermos comparar modelos** (Claude, Sabiá/Maritaca se estiver no catálogo,
   modelos abertos). O conjunto `apps/ia/avaliacoes/` não leva dado de Casa. Isso é um adaptador `openrouter` aceito
   **apenas** pelo `oplenario-ia-avaliar --vendor`, nunca pela `Config` de produção — o teste de arquitetura pode cobrar
   isso. Hoje os três conjuntos são sintéticos (textos de fixture, como `SESSAO-SECRETA-42`). Mas o `base-comum.json`
   avisa que "casos reais entram com cada capacidade (anonimizados, [GAP] LGPD)": quando entrarem, o adaptador de
   avaliação passa a só rodar os casos marcados como sintéticos, ou cai no mesmo `[GAP]` da produção.
3. **Para produção, seguir o desenho do Eixo 13**: primário contratado direto (Anthropic ou Bedrock em `sa-east-1`, se
   houver o modelo lá — **a verificar**), secundário com o mesmo critério, roteador atrás da porta com breaker em
   Valkey. É o mesmo benefício de failover, com contratos que são nossos.

**Reabrir esta avaliação se:** o OpenRouter oferecer região no Brasil ou assinar as cláusulas-padrão da ANPD; a compra
pela Stripe fechar e os termos se estabilizarem; ou precisarmos de mais de dois fornecedores em failover, quando manter
N contratos e N adaptadores ficar mais caro que um intermediário.

**O que pede o "Confirmo":** o item 2 (adaptador só de avaliação). Os itens 1 e 3 não mudam nada do que já está
decidido.

## Fontes

Trechos de busca; as páginas do OpenRouter e da imprensa estavam bloqueadas pelo proxy.

- OpenRouter — [Zero Data Retention](https://openrouter.ai/docs/guides/features/zdr) ·
  [Provider Routing](https://openrouter.ai/docs/guides/routing/provider-selection) ·
  [In-Region Routing](https://openrouter.ai/docs/guides/features/in-region-routing) ·
  [Prompt Caching](https://openrouter.ai/docs/guides/best-practices/prompt-caching) ·
  [Anthropic Messages: create a message](https://openrouter.ai/docs/api/api-reference/anthropic-messages/create-a-message) ·
  [Batch API](https://openrouter.ai/blog/announcements/batch-api/) ·
  [Latency and Performance](https://openrouter.ai/docs/guides/best-practices/latency-and-performance) ·
  [Outages de 17 e 19/02/2026](https://openrouter.ai/blog/announcements/openrouter-outages-on-february-17-and-19-2026/) ·
  [Enterprise Access Agreement](https://openrouter.ai/terms-of-service-enterprise) ·
  [DPA (FAQ)](https://openrouter.zendesk.com/hc/en-us/articles/47828437697051-How-do-I-get-OpenRouter-s-Data-Processing-Agreement-DPA-for-GDPR-compliance) ·
  [Trust Center](https://trust.openrouter.ai/)
- Preços e taxas [secundário] — [TrueFoundry](https://www.truefoundry.com/blog/openrouter-pricing) ·
  [aireiter, BYOK](https://aireiter.com/blog/openrouter-byok-fees-fallback-guide) ·
  [Neomanex, plano Business](https://neomanex.com/news/openrouter-business-tier-8-percent-fee-in-region-routing)
- Compra pela Stripe — [TechCrunch](https://techcrunch.com/2026/08/16/stripe-will-reportedly-acquire-ai-gateway-startup-openrouter-for-7b/) ·
  [CNBC](https://www.cnbc.com/2026/08/19/stripe-openrouter-fintech-ai-model-marketplace-.html) ·
  [Neomanex, "confirmado, não fechado"](https://neomanex.com/news/stripe-openrouter-acquisition-confirmed)
- Anthropic — [API e retenção de dados](https://platform.claude.com/docs/en/manage-claude/api-and-data-retention) ·
  [Covered Models](https://support.claude.com/en/articles/15425996-data-retention-practices-for-covered-models)
- ANPD — [Resolução CD/ANPD nº 19/2024](https://www.gov.br/anpd/pt-br/acesso-a-informacao/institucional/atos-normativos/regulamentacoes_anpd/resolucao-cd-anpd-no-19-de-23-de-agosto-de-2024) ·
  [prazo de adequação](https://lefosse.com/noticias/alerta/transferencia-internacional-de-dados-prazo-para-adocao-das-clausulas-padrao-da-anpd-se-encerra-em-23-de-agosto/)
