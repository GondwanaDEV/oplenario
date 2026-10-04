# 27 — Runbook: o satélite de IA em produção

> Estado de 27/09/2026. Como o satélite (`apps/ia`, [ADR-0006](adr/0006-satelite-de-ia-apps-ia.md)) roda em
> produção, como se liga ao core ([ADR-0008](adr/0008-fronteira-core-ia-eventos-de-integracao.md)) e o que fazer
> quando a IA "some". **Nenhum valor secreto vive aqui** — só nomes de variáveis e onde elas moram.

## 1. Topologia

Tudo no Dokploy (`vps.calvetec.com.br`), projeto **`oplenario`**, ambiente **`production`**:

| App | appName (rede interna) | Imagem | Comando |
|---|---|---|---|
| `backend` (o core) | `oplenario-backend-zvr55n` | `ghcr.io/gondwanadev/oplenario-api-prd:latest` | padrão (`serve`, porta 8888) |
| `ia-api` | `oplenario-iaapi-h9hzqw` | `ghcr.io/gondwanadev/oplenario-ia-prd:latest` | padrão (uvicorn, porta 8090) |
| `ia-trabalhador` | `oplenario-iatrabalhador-izlwf3` | a mesma do `ia-api` | `oplenario-ia-trabalhador` |
| banco | `oplenario-database-0u5jm6` | **`pgvector/pgvector:pg16`** | — |

- **Um banco só:** o satélite usa o mesmo banco `oplenario` do core, no schema **`ia`**, criado pelo próprio satélite
  ao subir (migrações em `apps/ia/src/oplenario_ia/armazem/postgres.py`, uma transação só).
- **O banco PRECISA de pgvector.** A migração 4 do satélite roda `CREATE EXTENSION vector` (o índice de busca). Com a
  imagem `postgres` comum a transação inteira falha, o schema `ia` nem nasce e a busca cai em `sem-ia`. Foi o que
  travou a primeira subida.
- **Fornecedor:** o fake (padrão). Nada sai do cluster. O fornecedor real espera o `[GAP]` jurídico (DPA de
  não-treino, LGPD art. 33) — trocar é `OPLENARIO_IA_VENDOR` + a chave, nos dois apps de IA.

## 2. Variáveis (aba Environment de cada app)

| Onde | Variável | Valor |
|---|---|---|
| `backend` | `OPLENARIO_IA_URL` | `http://oplenario-iaapi-h9hzqw:8090` |
| `backend` | `OPLENARIO_IA_SEGREDO` | o segredo compartilhado (o **mesmo** dos apps de IA) |
| `ia-api` e `ia-trabalhador` | `OPLENARIO_CORE_URL` | `http://oplenario-backend-zvr55n:8888` |
| `ia-api` e `ia-trabalhador` | `OPLENARIO_IA_SEGREDO` | o mesmo do `backend` |
| `ia-api` e `ia-trabalhador` | `OPLENARIO_IA_DATABASE_URL` | `postgresql://<DB_USER>:<DB_PASSWORD>@oplenario-database-0u5jm6:5432/oplenario` (os dados de banco do `backend`) |

Sem `OPLENARIO_IA_SEGREDO`/`OPLENARIO_IA_URL` no `backend`, a IA fica **desligada** e o core segue funcionando: a busca
cai na ementa (`modo: "sem-ia"`), o assistente responde "indisponível" (R-IA-1), nada vira 500. Os dois apps de IA
puxam a imagem do ghcr com o mesmo login do `backend` (aba General → Provider).

## 3. Deploy e verificação

Os workflows de produção vivem **só na branch `production`** (como `build-api-prd.yaml` e `build-web-prd.yaml`):

- **`build-ia-prd.yaml`** — em todo push em `production` que toque `apps/ia/**`: publica a imagem, acha os dois apps
  pelo appName e dispara o deploy; depois **verifica a fronteira**: loga como a secretaria e exige que
  `GET /api/busca` responda `modo: "ia"`.
- **`configurar-ia-prd.yaml`** — passo avulso (dispara quando o próprio arquivo muda). A última versão fez a carga
  inicial do índice (seção 4). Reescrevê-lo e dar push roda o que estiver nele.

Mudar variável no Dokploy **não** vale até o **Deploy** do app (o último deploy do `backend` precisa ser posterior à
mudança).

Smoke das telas de IA: **`fumaca-ia.yaml`** (em `main`, `workflow_dispatch`) — as 6 telas + as leituras da API como a
persona `apresentacao`; `perguntar=true` faz uma pergunta real ao assistente.

## 4. Índice de busca: carga inicial e reindexação

Proposições novas e editadas entram sozinhas (o relay promove `proposicao.protocolada`/`.editada` para o feed da IA e
o trabalhador indexa a cada 15 s). O acervo que **já existia** entra com o comando da própria imagem da API:

```
java -jar oplenario.jar ia-republicar-proposicoes <ente-id>
```

- Idempotente pela chave: rodar de novo não duplica — é também o comando depois de **trocar o modelo de embeddings**.
- Onde rodar: no terminal do app `backend` no Dokploy (**Open Terminal** — o container já tem a conexão do banco), ou
  como passo de workflow com a imagem `oplenario-api-prd` e a mesma conexão do `migrate`.
- Em 27/09/2026: 24 proposições da Casa `10000000-0000-0000-0000-000000000001` publicadas e indexadas.
- Transcrições: `oplenario-ia-trabalhador --reindexar` (no trabalhador). Normas: entram ao publicar em `/normas`.

## 5. Orçamento de IA da Casa (B.9, [ADR-0014](adr/0014-orcamento-de-ia-e-painel-da-casa.md))

Sem orçamento definido a Casa **não é bloqueada** — a cota só mede (`estado: "sem_orcamento"` em `/paineis/ia`).
Para definir (valores comerciais do plano, na moeda da tabela de preços da IA):

```
java -jar oplenario.jar ia-orcamento <ente-id> <mensal> <teto-duro> [moeda=USD]
```

### Observabilidade da plataforma (Onda E)

O operador vê a saúde da IA de todas as Casas em `/operacao/ia` (console, 24 h ou 7 dias): execuções, tempo de
resposta p50/p95, custo, o que não rodou e por quê, por capacidade e por fornecedor/modelo. A fonte é o registro da
Camada de Confiança (`ia.registro_evento`, só execuções do modelo de linguagem — transcrição e busca não entram), lido
pelo satélite em `GET /v1/observabilidade?horas=` (1–168, segredo de serviço). Com a IA fora a tela diz
"não respondeu agora" — o core devolve `disponivel: false`, nunca 500. O fornecedor fake registra latência 0.

## 6. Quando a busca responde `sem-ia`

Na ordem em que já aconteceu:

1. **O `backend` não foi redeployado** depois de ganhar as variáveis de IA.
2. **`OPLENARIO_IA_URL` aponta para outro app** (o appName tem sufixo aleatório; confira na página do `ia-api`).
3. **Segredo diferente** entre `backend` e satélite (o satélite responde 401).
4. **Banco sem pgvector** — o schema `ia` não existe; olhar os Logs do `ia-api`.
5. **`ia-api` fora do ar** — Logs do app no Dokploy.

O `backend` loga o motivo como `plataforma de IA indisponivel` (`integracao_ia/diplomat/http/out.clj`).

## 7. Anexos: reconciliar o banco com o object storage (comando do `backend`)

Compara os anexos do balcão (`participacao.anexo`, `atendimento/<ente>/<protocolo>/<id>`) e dos comunicados (`comunicacao.anexo`,
`comunicados/<ente>/<comunicado>/<id>`) com os blobs do storage, uma Casa por vez (leitura na transação do tenant). Mesmo terminal
do app `backend`; **não há agendador**, é comando de operação ([ADR-0022](adr/0022-indeferir-ciencia-da-prorrogacao-e-anexos-no-balcao.md), Eixo 3).

```
java -jar oplenario.jar reconciliar-anexos [--ente <uuid>] [--apagar-orfaos]
```

- **Padrão: só relata.** Por Casa e por pasta: linhas (e quantas retiradas), blobs, a lista de **blob sem linha** (upload que gravou o
  arquivo e falhou antes do INSERT) e a de **linha sem blob**. Cada lista mostra até 200 itens e traz o total verdadeiro ao lado.
- **Anexo retirado não é divergência:** a retirada apaga o blob de propósito. Retirado que ainda tem blob aparece como órfão "anexo retirado".
- **`--apagar-orfaos`:** remove do **storage** só o blob sem linha com **mais de 24 h** (upload em curso tem blob antes da linha); o recente é
  listado como "recente, nao apaga". Nunca apaga nem altera linha do banco; imprime cada blob apagado e cada falha.
- **Linha sem blob** é só relatada: o arquivo se perdeu e a decisão (pedir o reenvio, retirar o anexo) é da secretaria.
- **Casa:** sem `--ente`, todas as do registro; `--ente` precisa estar no registro (senão sai com 2: confira o `DATABASE_URL`). Casa `encerrado` ou
  com o apagamento em curso (ADR-0018) não é tocada.
- **Código de saída:** `0` íntegro (ou só o que o `--apagar-orfaos` limpou) · `1` sobrou divergência · `2` uso ou Casa fora do registro.
