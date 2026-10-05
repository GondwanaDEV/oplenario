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
   `provider.zdr = true`. `OPLENARIO_IA_OPENROUTER_PROVEDORES` (lista separada por vírgula) vira `provider.only`:
   o failover nativo do OpenRouter fica restrito aos provedores aprovados. Vazio = qualquer provedor que cumpra ZDR
   e não colete dado.
4. **Um modelo só por requisição.** Nunca `models` (fallback de modelo do OpenRouter): o modelo não troca em silêncio
   (§22.11.8). Sem retry no cliente: retry, backoff e failover seguem nossos (§22.3.5).
5. **Proveniência:** `vendor = "openrouter"`, `modelo` = o `model` da resposta (slug do catálogo, ex.
   `anthropic/claude-opus-5`) e o novo campo `provedor` = o `provider` da resposta (quem de fato atendeu). O
   `provedor` entra no registro da execução (`RegistroExecucao.provedor`, no corpo JSON do `ia.registro_evento`, sem
   migração).
6. **Custo:** vale o custo que o OpenRouter declara na resposta (`usage.cost`), multiplicado pelo `acrescimo` do
   fornecedor na tabela (`precos.json`, `"openrouter": "1.055"`, a taxa de 5,5% na compra de créditos — fonte
   secundária, conferir no contrato). Sem custo declarado, a tabela é a reserva; modelo sem preço continua `None`,
   nunca zero.
7. **Modelo padrão:** com `vendor=openrouter` e sem `OPLENARIO_IA_MODELO`, o modelo é `anthropic/claude-opus-5`.
8. **Chave:** `OPENROUTER_API_KEY`, do cofre (Eixo 11f). Sem ela, a execução sai "indisponível" (R-IA-1), nunca 500.
9. **Fora do escopo do OpenRouter:** a transcrição (Whisper + pyannote no sherpa-onnx) e os embeddings da busca
   (fastembed) continuam self-host — nada sai do cluster, e o OpenRouter não oferece transcrição com diarização.
   Trocar embeddings mudaria a dimensão do índice e pediria reindexação; não foi pedido.

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
- **Não verificado contra o OpenRouter real:** a sessão não tem rede até `openrouter.ai` nem chave. Antes de ligar,
  rodar a avaliação com a chave e conferir na resposta real: `provider`, `usage.cost` e
  `prompt_tokens_details.cached_tokens`.
