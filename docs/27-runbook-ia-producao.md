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

## 7. Valkey do tempo real: senha, TLS e rede

Não é do satélite (ele não usa o Valkey), mas a topologia do Dokploy está descrita aqui. O Valkey guarda o canal ao
vivo do plenário (`tempo_real`, janela de 5 minutos) e só o `backend` fala com ele.

| O quê | Onde se resolve |
|---|---|
| O que sai do Valkey nunca vira objeto (texto EDN de dado puro, sem Nippy na leitura) | código — nada a fazer |
| O `backend` manda a senha (`VALKEY_PASSWORD`, ou na `VALKEY_URI`) e o usuário de ACL opcional (`VALKEY_USERNAME`) | código + variável no Dokploy |
| Fora de `APP_ENV` dev/test, com `TEMPO_REAL_BACKPLANE=valkey` e sem senha, o `backend` **sobe** e registra `Valkey sem senha em producao` em nível `error`, a cada boot | código |
| Com `VALKEY_EXIGIR_SENHA=true`, o mesmo caso **não sobe** (`Valkey sem senha e VALKEY_EXIGIR_SENHA ligada`) | código + variável no Dokploy |
| TLS: `VALKEY_URI=rediss://…` | variável no Dokploy + certificado no servidor |
| O Valkey exigir a senha (`requirepass`) | **só no Dokploy** |
| A porta 6379 não ser publicada | **só no Dokploy** |

### Pôr a senha e ligar a exigência (uma vez, depois de promover)

Promover a versão não pede nada: sem senha ela sobe e avisa. A ordem abaixo é o que fecha o aviso.

1. Procure `Valkey sem senha em producao` nos Logs do `backend`. Se não aparece no último boot, a senha já existe:
   pule para o passo 5.
2. Gere uma senha longa e aleatória e ponha no Valkey (a senha do serviço no Dokploy, ou
   `valkey-server --requirepass <senha>` no comando). Faça o Deploy do Valkey.
3. No `backend`, defina `VALKEY_PASSWORD` com a mesma senha e faça o Deploy. Entre os passos 2 e 3 o painel ao vivo
   para de atualizar (o relay tenta de novo sozinho); faça fora de sessão.
4. Confira nos Logs do `backend` que ele subiu, que o aviso **sumiu** e que um painel ao vivo atualiza.
5. Só então defina `VALKEY_EXIGIR_SENHA=true` no `backend` e faça o Deploy. Daí em diante, perder a senha do
   ambiente impede o boot em vez de deixar o Valkey aberto sem ninguém notar.

Nunca ligue `VALKEY_EXIGIR_SENHA` antes do passo 4: sem a senha no ambiente o `backend` não sobe.

Na primeira subida da versão nova, o que ainda estiver no canal no formato antigo é recusado e vira aviso de lacuna:
quem está com o painel aberto recarrega o estado pelo snapshot. Passa em 5 minutos.

### Conferir que a porta não está exposta

- No serviço do Valkey no Dokploy, a porta externa fica **vazia** (nenhum mapeamento para o host).
- De fora da VPS, `nc -zv <host-da-vps> 6379` tem de falhar. Se conectar, feche a porta antes de qualquer outra coisa.
- O Valkey só precisa estar na rede interna do projeto, onde o `backend` o alcança pelo appName.

### Trocar a senha

1. `ACL SETUSER default ><senha-nova>` no Valkey (a antiga continua valendo; as duas convivem).
2. Troque a senha no `backend` e faça o Deploy.
3. `ACL SETUSER default <<senha-antiga>` remove a antiga. Grave a nova também na configuração do serviço, senão um
   restart do Valkey volta para a antiga.

### TLS

Só é preciso se o Valkey sair da rede interna. `rediss://` usa o truststore padrão da JVM: o certificado do servidor
tem de encadear numa autoridade que ela conhece. O cliente valida a cadeia, mas **não** confere o nome do host.

### Quando o `backend` não sobe por causa do Valkey

| Log | Causa |
|---|---|
| `Valkey sem senha e VALKEY_EXIGIR_SENHA ligada` | falta `VALKEY_PASSWORD` (ou a senha na `VALKEY_URI`); para subir já, tire `VALKEY_EXIGIR_SENHA` |
| `NOAUTH` / `WRONGPASS` no start | o Valkey exige senha e o `backend` não tem, ou tem outra |
| `Connection refused` | appName errado na `VALKEY_URI`, ou o Valkey fora do ar |
