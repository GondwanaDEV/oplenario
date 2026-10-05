# ADR-0023 — OpenRouter como fornecedor de modelo de linguagem da plataforma

- **Status:** ✅ **Aceita** (05/10/2026). Pedido direto: "Precisamos usar openrouter para uso de modelos por tudo na
  plataforma." Revoga o item 1 da recomendação de [`docs/30`](../30-avaliacao-openrouter.md) ("não usar no caminho de
  dado de Casa agora").
- **Relacionadas:** ADR-0006 (satélite e porta de inferência), ADR-0014 (orçamento de IA por Casa),
  `arquitetura/22-9-stack.md` Eixos 10 e 13, `docs/25` §5.6 e §8, `docs/26` (`[GAP]` jurídico do fornecedor),
  `docs/30` (a avaliação técnica).

## Contexto

O satélite fala com o modelo de linguagem por uma porta neutra (`inferencia/porta.py`), com o adaptador `fake`
(padrão) e o `anthropic` (SDK oficial, contrato direto). A avaliação de 03/10/2026 (`docs/30`) mediu o OpenRouter e
recomendou usá-lo só para comparar modelos. A decisão foi outra: **todo uso de modelo de linguagem da plataforma passa
pelo OpenRouter.**

## Decisão

1. **O OpenRouter é o fornecedor real do modelo de linguagem.** Novo adaptador `inferencia/openrouter_adapter.py`,
   escolhido por `OPLENARIO_IA_VENDOR=openrouter`. As oito capacidades (ata, resumo cidadão, agente planejar e
   responder, requerimento preencher e justificar, nota técnica, análise do relator) passam a ele sem mudar uma linha:
   todas compõem o `nucleo`, que só conhece a porta. O `fake` segue o padrão de dev, CI e de qualquer deploy sem a
   chave.
2. **API de chat (`/chat/completions`, formato OpenAI) por `httpx`.** É o formato que serve qualquer modelo do
   catálogo. Exceção registrada à ADR-0006 ("SDK oficial, nunca HTTP cru"): o OpenRouter não tem SDK oficial estável,
   o contrato dele é HTTP+JSON, e o `httpx` já é dependência (fronteira com o core). A porta continua isolando isso do
   resto do código, e o mapeamento de status para as 6 categorias está testado contra transporte mock.
3. **Política de dado travada em toda requisição, sem chave para desligar:** `provider.data_collection = "deny"` e
   `provider.zdr = true`, mais `provider.require_parameters = true` (só atende quem honra todos os parâmetros do
   pedido). `OPLENARIO_IA_OPENROUTER_PROVEDORES` (lista separada por vírgula) vira `provider.only`:
   o failover nativo do OpenRouter fica restrito aos provedores aprovados. Vazio = qualquer provedor que cumpra ZDR
   e não colete dado.
4. **Um modelo só por requisição.** Nunca `models` (fallback de modelo do OpenRouter): o modelo não troca em silêncio
   (§22.11.8). Pela mesma razão a config recusa, no boot, slug que não aponta um modelo fixo: `openrouter/*`
   (roteador e meta-modelos), alias `~…` e o sufixo `:online` (busca na web). Sem retry no cliente: retry, backoff e failover seguem nossos (§22.3.5).
5. **Proveniência:** `vendor = "openrouter"`, `modelo` = o `model` da resposta (slug do catálogo, ex.
   `openai/gpt-oss-120b`) e o novo campo `provedor` = o `provider` da resposta (quem de fato atendeu). O
   `provedor` entra no registro da execução (`RegistroExecucao.provedor`, no corpo JSON do `ia.registro_evento`, sem
   migração).
6. **Custo:** vale o custo que o OpenRouter declara na resposta (`usage.cost`), multiplicado pelo `acrescimo` do
   fornecedor na tabela (`precos.json`, `"openrouter": "1.055"`, a taxa de 5,5% na compra de créditos — fonte
   secundária, conferir no contrato). Sem custo declarado, a tabela é a reserva; modelo sem preço continua `None`,
   nunca zero.
7. **Modelo padrão, provisório:** com `vendor=openrouter` e sem `OPLENARIO_IA_MODELO`, o modelo é
   `openai/gpt-oss-120b` (pedido em 05/10/2026, "por enquanto"). É um modelo de pesos abertos servido por vários
   provedores, então o preço muda conforme quem atende. Por isso ele fica sem linha na `precos.json` e o custo vem
   do `usage.cost` declarado. Ainda não passou pela avaliação (R-IA-4): rodar `oplenario-ia-avaliar` com ele antes de
   ligar: os prompts das capacidades só foram medidos contra o fake até hoje.
8. **Folga de raciocínio por modelo (05/10/2026):** num modelo que raciocina, o `max_tokens` cobre o raciocínio E a
   resposta, e os limites das capacidades (1000 a 2000) foram medidos num modelo que não raciocina. Na avaliação, o
   qwen gratuito gastou os 2000 inteiros raciocinando e devolveu texto vazio (`finish_reason=length`). Cada modelo de
   `MODELOS_OPENROUTER` (`config.py`) declara a folga somada ao limite do pedido: 4000 no `gpt-oss-120b`, 8000 no qwen
   gratuito. Paga-se só o que o modelo gera. O `reasoning.effort` NÃO vai por padrão: com `require_parameters`, um
   provedor que não o aceite sairia do roteamento. Quando acontece mesmo assim, o erro diz "o modelo esgotou o limite
   de tokens raciocinando" (sem o raciocínio, B4). **Não medido ao vivo:** o qwen gratuito estava com limite no
   provedor (429) e a cota diária quase no fim; reavaliar com `oplenario-ia-avaliar` quando houver crédito.
9. **Chave:** `OPENROUTER_API_KEY`, do cofre (Eixo 11f). Sem ela, a execução sai "indisponível" (R-IA-1), nunca 500.
10. **Fora do escopo do OpenRouter:** a transcrição (Whisper + pyannote no sherpa-onnx) e os embeddings da busca
   (fastembed) continuam self-host — nada sai do cluster, e o OpenRouter não oferece transcrição com diarização.
   Trocar embeddings mudaria a dimensão do índice e pediria reindexação; não foi pedido.

## O que veio do munex

O munex (`colmeia-solucoes/munex`, ADR-0053 de lá) já usa o OpenRouter como único caminho para modelos e verificou ao
vivo o que aqui só está em teste: o bloco `provider` com `only`, `zdr`, `data_collection` e `require_parameters` é
aceito, e a resposta traz o campo `provider`. A regra do slug fixo e o `require_parameters` foram copiados de lá. O
comparativo de lá (E19–E21, dado sintético) pôs o `openai/gpt-oss-120b` no topo, servido pela DeepInfra com a Groq
como segundo host: é a origem do modelo padrão e o ponto de partida sugerido para
`OPLENARIO_IA_OPENROUTER_PROVEDORES=deepinfra,groq`. Diferença consciente: lá vai `allow_fallbacks: false`; aqui o
failover entre os provedores da lista fica ligado, porque a lista já é a dos aprovados.

## O que NÃO muda

- **O `[GAP]` jurídico continua.** Ligar `OPLENARIO_IA_VENDOR=openrouter` em produção com dado de Casa exige DPA de
  não-treino e a base da transferência internacional (LGPD art. 33) **com o OpenRouter**, que passa a ser o operador
  contratado. Pelo `docs/30`: DPA só no plano Enterprise, sem região no Brasil, compra pela Stripe em fechamento. Até
  o jurídico fechar, produção segue no `fake`.
- O filtro de governança B1–B4 roda antes de qualquer adaptador, igual.
- O adaptador `anthropic` fica: é o caminho direto se o contrato com o OpenRouter não sair, e o secundário natural do
  failover do Eixo 13.

## Consequências

- Uma chave dá acesso a todo o catálogo: trocar de modelo é `OPLENARIO_IA_MODELO`, sempre depois da avaliação
  (`oplenario-ia-avaliar --vendor openrouter --modelo <slug>`, R-IA-4) e com o preço na tabela ou declarado.
- A disponibilidade passa a ser OpenRouter × provedor. A IA não está no caminho crítico da sessão (Eixo 13, E1).
- `zdr: true` exclui do roteamento os modelos que exigem retenção (os "Covered Models" da Anthropic); configurar um
  deles devolve 404 → "configuração do fornecedor".

## Materialização

- `apps/ia/src/oplenario_ia/inferencia/openrouter_adapter.py`, `config.py`, `inferencia/fabrica.py`,
  `inferencia/modelo.py` (`provedor`, `custo_informado`), `avaliacao/custo.py` (`acrescimo`, custo informado),
  `avaliacao/precos.json`, `confianca/registro.py`, `nucleo.py`, `avaliacao/cli.py`.
- Testes: `apps/ia/tests/test_openrouter.py` (corpo e política de dado, normalização, recusa, 11 status, erro em
  200, rede, chave ausente, config, custo, ponta a ponta pelo núcleo).
- **Verificado ao vivo em 05/10/2026**, com uma chave de nível gratuito e sem crédito:
  - `openai/gpt-oss-120b` → 402, classificado como "créditos do OpenRouter esgotados", sem nova tentativa. **Ele não
    foi avaliado:** não existe variante gratuita dele.
  - modelos `:free` sem provedor com ZDR → 404 "No endpoints found matching your data policy" → "configuração do
    fornecedor". Confirma que a política de dado vai e é obedecida.
  - `qwen/qwen3.8-27b:free` (provedor ModelRun, aceita ZDR) respondeu. A resposta real tem `provider`, `usage.cost`,
    `prompt_tokens_details.cached_tokens` e `cache_write_tokens` com os nomes que o adaptador lê; o raciocínio vem à
    parte (`message.reasoning`) e não entra no texto; o corpo pode vir com espaço em branco antes do JSON.
  - avaliação com ele (`oplenario-ia-avaliar avaliacoes`): `base-comum` 8/8, `copiloto-relator` 1/1 (os casos só-fake
    pulados) e `agente-seguranca` entre 2/7 e 4/7. As reprovações são do modelo e da conta, não do adaptador: em parte
    dos planos o modelo gasta os 2000 tokens raciocinando e não escreve a resposta (`finish_reason=length`, texto
    vazio → "saída inválida"), e o nível gratuito devolve 429 depois de ~16 requisições seguidas ("sobrecarga").
- **Lista de modelos permitidos** (`MODELOS_OPENROUTER_PERMITIDOS`, `config.py`): o satélite só SOBE
  (`carregar()`) com `openai/gpt-oss-120b` ou `qwen/qwen3.8-27b:free`. A avaliação monta a `Config` direto e roda
  qualquer slug fixo — é por ela que um modelo entra na lista. O `qwen/qwen3.8-27b:free` entrou a pedido, com preço
  zero na tabela; o limite diário do nível gratuito não o deixa servir a produção.

## Exceção temporária: modelos gratuitos sem ZDR (05/10/2026)

Decisão do Daouda, por escrito: "use os melhores modelos gratuitos mesmo não sendo compliance à nossa política, vamos
seguir assim excepcionalmente até conseguir comprar créditos", para os testes e para produção. É uma exceção à
política de dado desta ADR (`zdr` e `data_collection: deny` em toda requisição), não uma mudança dela.

- **Como liga:** `OPLENARIO_IA_OPENROUTER_POLITICA=excecao-gratuita`. O padrão continua `zdr`, com o comportamento de
  antes. Na exceção, o bloco `provider` leva só `require_parameters` (e `only`, se configurado), e o satélite sobe só
  com modelos de `MODELOS_OPENROUTER_GRATUITOS_EXCECAO` (todos `:free`), com um aviso no log a cada subida.
  `OPLENARIO_IA_MODELOS_RESERVA` dá a ordem de troca: em 429, 503, 408, 5xx, falha de rede ou 404, a chamada vai ao
  próximo modelo; nunca em 400, 401, 402 ou 403. O modelo e o provedor que de fato atenderam continuam vindo da
  resposta e vão ao registro.
- **Os três, em ordem PROVISÓRIA (triagem ao vivo de 05/10/2026, uma pergunta só; a ordem entre eles não está medida):** `nvidia/nemotron-3-super-120b-a12b:free` (principal),
  `qwen/qwen3.8-27b:free` e `nvidia/nemotron-3-ultra-550b-a55b:free` (reservas, nessa ordem).
- **A triagem:** 19 modelos gratuitos chamam ferramentas. Cada candidato recebeu "Qual o quórum para derrubar um
  veto?" com a busca nas normas e respondeu com o dispositivo devolvido.

  | Modelo | Provedor | ZDR | Chamou a busca | Resposta | Tempo (2 passos) |
  |---|---|---|---|---|---|
  | nemotron-3-super-120b-a12b | Nvidia | não | sim | maioria absoluta, art. 45 | 3,7 s |
  | qwen3.8-27b | ModelRun | sim | sim | maioria absoluta, art. 45 | 3,1 s |
  | nemotron-3-ultra-550b-a55b | Nvidia | não | sim | maioria absoluta, art. 45; acrescenta "metade mais um" | 12,6 s |
  | ling-3.0-flash-sante | Novita | sim | sim | maioria absoluta, art. 45 | 2,8 s |
  | apodex-1.1-mini | Novita | sim | sim | maioria absoluta, art. 45 | 2,1 s |
  | dots-3-note-preview | AtlasCloud | não | nome da ferramenta errado (`/buscar_dispositivos`) | — | — |
  | gemma-4-31b-it, gemma-4-26b-a4b-it | Google | não | 429 do provedor gratuito, sem vaga | — | — |
  | inkling | Thinking Machines | não | 403 | — | — |

  Cinco empataram nessa pergunta; o desempate foi critério, não medida: o nemotron-3-super por ser o maior entre os
  rápidos e não acrescentar nada à fonte; o qwen3.8-27b por ser o único já avaliado nos conjuntos e ter provedor com
  ZDR; o nemotron-3-ultra por tamanho, com a ressalva do acréscimo que não estava na fonte. Essa pergunta usou a
  chamada nativa de ferramentas, que a Clara não usa (o laço planeja em JSON): a próxima triagem passa pelo laço real
  (`oplenario-ia-avaliar` com 4 casos de `clara-papeis`) e reordena a lista. Os dois menores (ling, apodex) ficam como próxima troca se um dos três cair.
  **A avaliação completa (5 conjuntos, 42 casos) não rodou:** a conta bateu o limite diário no meio da segunda rodada.
- **O que se aceita com a exceção:**
  - o provedor gratuito pode guardar e usar para treino o que recebe; com dado real, isso é tratamento sem contrato e
    com transferência internacional (LGPD art. 33), o `[GAP]` jurídico desta ADR, assumido pelo dono do produto;
  - **sem crédito comprado, a conta inteira tem 50 chamadas por dia** a modelos gratuitos (`free-models-per-day`;
    cada pergunta à Clara gasta de 2 a 4). Passado o limite, o satélite responde 429 e a tela mostra "indisponível,
    siga pela tela" (R-IA-1). A reserva não ajuda: o limite é da conta, não do modelo. Com US$ 10 comprados uma vez,
    o limite sobe para 1000 por dia;
  - modelo gratuito some ou muda sem aviso; o 404 cai na reserva.
- **Como sai:** comprado o crédito, `OPLENARIO_IA_OPENROUTER_POLITICA=zdr` e `OPLENARIO_IA_MODELO` de
  `MODELOS_OPENROUTER_PERMITIDOS`, depois da avaliação; a lista da exceção pode ser apagada.

- **05/10/2026, 17h34 UTC:** o `qwen/qwen3.8-27b:free` deixou de ser gratuito (404 "This model is unavailable for
  free. The paid version is available now") e saiu das duas listas (`MODELOS_OPENROUTER` e a da exceção). A exceção
  fica com o `nvidia/nemotron-3-super-120b-a12b:free` (padrão) e o `nvidia/nemotron-3-ultra-550b-a55b:free` (reserva)
  até a triagem pelo laço real; na política `zdr` sobra só o `openai/gpt-oss-120b`.
