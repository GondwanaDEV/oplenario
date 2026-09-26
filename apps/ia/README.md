# `apps/ia` — satélite de IA do O Plenário

Python 3.12 · FastAPI · Pydantic v2. Forma e regras em [ADR-0006](../../docs/adr/0006-satelite-de-ia-apps-ia.md);
desenho em `docs/25`, plano em `docs/26`, decisões na §22.11 (`arquitetura/22-11-ia-como-ator.md`).

## O que já existe

Base comum da Track IA (fatias 0.1–0.5) e a transcrição da Faixa A (A.3, [ADR-0008](../../docs/adr/0008-fronteira-core-ia-eventos-de-integracao.md)).

| Camada | O quê |
|---|---|
| `inferencia/` | Porta vendor-agnóstica: fake determinístico (padrão — nada sai do cluster) e adaptador Anthropic (SDK oficial, retries do SDK desligados, erros nas 6 categorias do §22.3.5, fornecedor e modelo usados sempre carimbados). |
| `governanca/` | Filtro fail-closed B1–B4, **único caminho até o LLM**: sigilo degrada a chamada inteira, CPF/CNPJ/e-mail redigidos, conteúdo de terceiro delimitado e marcado, auditoria sem conteúdo. |
| `confianca/` | Camada de Confiança mínima (§16.8): citação por fonte lida na mesma execução e conferida, incerteza, registro append-only sem conteúdo, reportar erro, revisão humana, piso R-IA-1. |
| `avaliacao/` | Harness de avaliação (roda no CI) e custo por execução com `ente_id` (tabela de preços datada em `precos.json`). |
| `nucleo.py` | O pipeline que **toda** capacidade usa: filtro → porta → registro/custo → artefato com incerteza, ou R-IA-1. |
| `fronteira/` | O cliente do core (ADR-0008): puxa o feed de eventos de integração, lê contexto e gravação sob demanda, devolve eventos à caixa de entrada. Falhas nas categorias do §22.3.5; 403 = sigilo (descarta, não é erro). |
| `armazem/` | Armazenamento próprio (§22.3.4): cursor, fila de trabalhos idempotente, transcrições versionadas. Memória (testes) e Postgres (schema `ia`). |
| `transcricao/` | Portas de ASR e diarização (fake no CI; sherpa-onnx self-host no extra `[asr]`) e o **Caminho C**: o grupo de voz recebe o nome de quem tinha a palavra na Mesa; o aparte vence a fala-mãe; sem maioria, fica sem nome. |
| `trabalhador.py` | O laço da Faixa A: `transcrever` (contexto + download + ASR + vozes + Caminho C → transcrição + aviso) e `notificar` (entrega ao core até ser aceito). Espera crescente na falha de infraestrutura, falha na hora na de entrada. |

Capacidade nova (ata, resumo, busca, copiloto, conferência) compõe o `Nucleo` — nunca chama a porta direto
(`tests/test_arquitetura.py` reprova quem tentar):

```python
nucleo = Nucleo(criar_porta(carregar()), RegistroJsonl("registro.jsonl"))
r = nucleo.executar(
    PedidoGovernado(
        ente_id=...,
        correlation_id=...,
        operacao="consulta_norma",
        instrucoes=...,
        pecas=[Peca(texto=..., proveniencia=..., fonte=...)],
    ),
    politica="por_paragrafo",
)
# r é Artefato (rascunho proposto, com citações e incerteza) ou Indisponivel (R-IA-1, "siga pela tela")
```

## Rodar (sempre em container — mandato Docker)

```sh
docker run --rm -v "$PWD":/app -w /app python:3.12-slim sh -c \
  'pip install -q -e ".[dev]" && ruff check . && ruff format --check . && mypy && pytest && oplenario-ia-avaliar avaliacoes'
# a bateria do armazenamento Postgres roda quando OPLENARIO_IA_DATABASE_URL_TESTE aponta para um banco descartável
```

O CI (job `ia` do `.github/workflows/ci.yml`) roda exatamente isso, sempre com o fornecedor fake.

## Avaliação

Conjuntos em `avaliacoes/*.json` (formato em `avaliacao/conjunto.py`): casos `seguranca`, `objetiva` e — com cada
capacidade — `real` (anonimizados, `[GAP]` LGPD). Casos `apenas_fake` testam o pipeline e são pulados contra fornecedor
real.

```sh
oplenario-ia-avaliar avaliacoes                                   # fake, custo zero
oplenario-ia-avaliar avaliacoes --vendor anthropic --saida avaliacoes/resultados/$(date +%F).json
```

Contra fornecedor real **gasta dinheiro** e é **obrigatório antes de trocar fornecedor ou modelo** (§22.11.8) — junto
com a atualização de `avaliacao/precos.json`. O fornecedor da avaliação vem só da linha de comando, nunca do ambiente.

## Trabalhador da Faixa A

```sh
OPLENARIO_CORE_URL=http://core:8888 OPLENARIO_IA_SEGREDO=... OPLENARIO_IA_DATABASE_URL=postgresql://.../ia \
OPLENARIO_IA_ASR=sherpa OPLENARIO_IA_MODELOS=/modelos oplenario-ia-trabalhador          # ou --uma-vez
```

Com `OPLENARIO_IA_ASR=sherpa`, a imagem precisa do extra (`docker build --build-arg EXTRAS=asr`) e dos modelos dos
releases do sherpa-onnx em `/modelos` (os mesmos do PoC: `sherpa-onnx-whisper-turbo/`, `silero_vad.onnx`,
`sherpa-onnx-pyannote-segmentation-3-0/`, `nemo_en_titanet_small.onnx`). Medido em 4 CPUs: 57 s de áudio em ~100 s,
com o carregamento dos modelos.

## Configuração (ambiente)

| Variável | Padrão | |
|---|---|---|
| `OPLENARIO_IA_VENDOR` | `fake` | `anthropic` exige DPA de não-treino (`[GAP]` jurídico, LGPD art. 33) |
| `OPLENARIO_IA_MODELO` | `claude-opus-5` | |
| `OPLENARIO_IA_TIMEOUT_S` | `60` | |
| `OPLENARIO_IA_REGISTRO_JSONL` | — | registro append-only em arquivo; sem ele, em memória |
| `ANTHROPIC_API_KEY` | — | lida pelo SDK, vinda do cofre (Eixo 11f) |
| `OPLENARIO_CORE_URL` | — | a API do core (fronteira, ADR-0008) |
| `OPLENARIO_IA_SEGREDO` | — | o segredo de serviço core↔satélite (o mesmo do core); sem ele, `/v1/*` responde 503 |
| `OPLENARIO_IA_DATABASE_URL` | — | Postgres do satélite (schema `ia`); sem ela, fila e transcrições em memória (só dev) |
| `OPLENARIO_IA_ASR` | `fake` | `sherpa` = Whisper + pyannote self-host (extra `[asr]`) |
| `OPLENARIO_IA_MODELOS`, `OPLENARIO_IA_WHISPER`, `OPLENARIO_IA_IDIOMA` | `/modelos`, `turbo`, `pt` | |
