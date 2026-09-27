# `apps/captacao` — utilitário de captação do O Plenário

Leva o arquivo que o **OBS** grava no computador da transmissão até o O Plenário, sem reencoding. É a fonte primária da
gravação na V1 (documento-mestre §16.4, §22.3.4; decisão em [ADR-0007](../../docs/adr/0007-captacao-da-gravacao-local.md)).
Depois de enviada, a gravação aparece na tela **Gravações** do O Plenário, com a sessão sugerida pelo horário; a
secretaria confere e vincula, e é isso que leva a gravação à transcrição e à ata.

Só usa a biblioteca padrão do Python (3.10 ou mais novo): não é preciso instalar mais nada no computador da Câmara.

## Uso

```sh
# um arquivo
oplenario-captar enviar "2026-09-22 18-00-12.mkv"

# um arquivo, já ligado à sessão (id da sessão no O Plenário)
oplenario-captar enviar gravacao.mkv --sessao 5b0c…

# deixar rodando: vigia a pasta de gravação do OBS e envia cada arquivo terminado
oplenario-captar observar "C:\Users\Transmissao\Videos"
```

- **Quando um arquivo é enviado:** quando tamanho e data ficam parados por 120 s (`--estavel`) — o OBS não avisa que
  terminou. A pasta é varrida a cada 30 s (`--intervalo`).
- **Nada é enviado duas vezes:** o que já subiu fica em `.oplenario-enviados.json`, na própria pasta. Reiniciar o
  utilitário ou o computador não reenvia.
- **Sem internet ou servidor fora do ar:** o utilitário espera e tenta de novo (até 30 min entre tentativas).
- **Recusa do servidor** (credencial inválida, arquivo grande demais…): o arquivo é marcado como recusado e o motivo
  aparece no terminal; os outros seguem.
- **Horário:** o início vem do nome que o OBS dá ao arquivo (padrão `AAAA-MM-DD hh-mm-ss`), no fuso do computador
  (`--fuso`, padrão `-03:00`); o fim, da última escrita. Mantenha o relógio do computador certo.
- **Sessão secreta:** use `--restrito`. De qualquer forma, ao vincular a uma sessão secreta o O Plenário marca a
  gravação como restrita sozinho.

## Configuração

| Variável | O quê |
|---|---|
| `OPLENARIO_URL` | Endereço da API do O Plenário da Casa (ou `--servidor`). |
| `OPLENARIO_OIDC_TOKEN_URL`, `OPLENARIO_OIDC_CLIENT_ID`, `OPLENARIO_OIDC_CLIENT_SECRET` | A credencial da Casa para captação (abaixo). |
| `OPLENARIO_TOKEN` | Alternativa para testes: um token de acesso pronto. |

### A credencial de captação (implantação)

Em cada Casa, crie no realm do tenant (Keycloak) um **cliente confidencial com conta de serviço**
(`client_credentials`), por exemplo `captacao-<casa>`, e dê à identidade dessa conta **somente o papel `captacao`**.
Esse papel só envia arquivos: não vê a fila de gravações, não vincula, não faz mais nada. Guarde o segredo no cofre e
no computador da transmissão, e em nenhum outro lugar.

## Desenvolvimento (sempre em container — mandato Docker)

```sh
docker run --rm -v "$PWD":/app -w /app python:3.10-slim sh -c \
  'pip install -q -e ".[dev]" && ruff check . && ruff format --check . && mypy && pytest'
```
