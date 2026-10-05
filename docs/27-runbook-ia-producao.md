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
- **Fornecedor:** o fake (padrão). Nada sai do cluster. O fornecedor real é o **OpenRouter**
  ([ADR-0023](adr/0023-openrouter-como-fornecedor-de-modelo-de-linguagem.md)) e espera o `[GAP]` jurídico (DPA de
  não-treino com o OpenRouter, LGPD art. 33). Ligar, nos dois apps de IA: `OPLENARIO_IA_VENDOR=openrouter`,
  `OPENROUTER_API_KEY` (do cofre) e, se a lista de provedores aprovados estiver fechada,
  `OPLENARIO_IA_OPENROUTER_PROVEDORES` (ex.: `deepinfra,groq`, os hosts que o munex mediu para o `gpt-oss-120b`; ADR-0023). Antes, rodar
  `oplenario-ia-avaliar avaliacoes --vendor openrouter` com a chave e conferir o custo e o provedor no registro.

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

O comando deixa dois registros na atuação da Operação da Casa (aparecem na ficha dela no console): `ia-orcamento-iniciado`
(antes de mexer, com o que valia e o que se pretende) e `ia-orcamento-definido` (depois, apontando o primeiro). A linha de
comando não tem pessoa: o registro diz `origem: linha-de-comando`. Se a atuação estiver fora, o comando **não roda**; se só
o registro final falhar, o comando termina com erro dizendo que o orçamento **foi** definido e a tentativa fica sem
desfecho (a conferência `tentativas-sem-desfecho` a acusa). Rodar de novo é seguro: define de novo e registra outro par.

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

## 8. Anexos: reconciliar o banco com o object storage (comando do `backend`)

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

## 9. Login das Câmaras: o tema do O Plenário no Keycloak e a entrada pelo CPF

[ADR-0025](adr/0025-entrada-pelo-cpf-e-o-keycloak-escondido.md). Servidor e vereador entram em `/entrar` com o CPF; a
senha (e o código do aplicativo) é pedida pelo Keycloak da Câmara, numa tela com a cara do O Plenário. Até o passo 1
abaixo, o Keycloak de produção mostra a própria tela (em português e com o nome da Câmara, mas no visual padrão dele): o
login funciona, só não está escondido.

### Ligar (uma vez, depois de promover)

1. **Imagem do Keycloak com o tema:** workflow **`build-keycloak-prd.yaml`**. Roda sozinho no push da `production` que
   mexe em `apps/keycloak/**` (e por `workflow_dispatch`). Ele:
   - publica `ghcr.io/gondwanadev/oplenario-keycloak-prd` (`:latest` e `:<sha>`), a 26.0.0 com o diretório
     `/opt/keycloak/themes/oplenario`;
   - acha no Dokploy o **compose** do Keycloak das Casas pelo nome do container que a API usa (o host de
     `KEYCLOAK_BASE_URL` da API, `<appName do compose>-keycloak-1`). O Keycloak do operador é outro e não é tocado. Sem
     exatamente um compose, sem o compose guardado no Dokploy (`raw`) ou sem exatamente uma linha
     `image: quay.io/keycloak/keycloak:…`, para sem mexer em nada;
   - troca só essa linha pela imagem do sha e reimplanta o compose. Mesmo banco, mesmas variáveis, mesmo comando. O
     servidor puxa do GHCR com o login que já usa para as imagens da API;
   - confere no `serverinfo` do Keycloak que o tema `oplenario` carregou. Se não carregar em 12 min, **volta o compose
     anterior**, reimplanta e falha.

   **Se o workflow parar no "Achar o Keycloak":** a troca é à mão. No compose do Keycloak das Casas no Dokploy, a
   imagem passa a `ghcr.io/gondwanadev/oplenario-keycloak-prd:latest` (o servidor precisa de login no GHCR).
   Reimplante e espere o healthcheck.
2. **Backend:**
   - `KEYCLOAK_TEMA_LOGIN` pode ficar ausente (padrão `oplenario`);
   - `KEYCLOAK_TEMA_LOGIN=""` desliga o tema: o login de cada Câmara volta ao padrão do Keycloak quando se reaplica a
     configuração dela (passo 3);
   - o limite da entrada pelo CPF é `ENTRADA_LIMITE_POR_IP` (padrão 30) por `ENTRADA_JANELA_MIN` (padrão 5) minutos;
   - **o limite conta o IP que o Traefik acrescenta ao `X-Forwarded-For`** (o último item, lido pelo frontend). Se
     algum dia houver outro proxy ou CDN na frente do Traefik, o último item passa a ser o dele e todo mundo cai no
     mesmo balde: rever `ipDoCliente` (`apps/frontend/src/lib/entrada-cpf.ts`) antes.
3. **Reaplicar o login de cada Câmara:** workflow **`reaplicar-login-prd.yaml`** (só `workflow_dispatch`, confirmação
   `reaplicar-login`, `ente` vazio = todas). Ele:
   - roda o comando `reaplicar-login` da imagem da API (antes confere que a imagem já tem o comando);
   - usa o **ambiente da API lido do Dokploy**: o provisionamento grava também o SMTP e o gov.br do realm a partir da
     configuração de quem roda. Só o banco troca para a porta externa;
   - a API fala com o Keycloak pela rede interna do Dokploy, fora do alcance do runner: o job usa então a URL pública
     (`KEYCLOAK_BASE_URL_PUBLICO`) e **não reconverge o gov.br do realm** (as URLs do broker saem da base interna; o que
     a API gravou fica). Se nenhuma das duas responde, para sem tocar em nada;
   - grava no realm o nome da Câmara, o pt-BR, o tema (login e e-mail), a política de senha, a trava contra força
     bruta, a ordem senha → código no primeiro acesso e o "voltar" do convite para `/entrar`;
   - é idempotente e pula Câmara encerrada ou com apagamento iniciado (reaplicar recriaria o realm apagado);
   - deixa na atuação da Operação o par `realm-reprovisionamento-iniciado` → `realm-reprovisionado` (ou `…-falhou`),
     com `origem: linha-de-comando`. Sai com erro se alguma Câmara falhou; as outras seguem.

   Para uma Câmara só, o console tem o mesmo efeito: ficha da Câmara → "Reaplicar configuração de login"
   (`POST /operacao/casas/:ente/realm`).
4. **Conferir:**
   - abrir `https://<app>/entrar` e digitar um CPF de quem tem acesso;
   - a tela de senha deve mostrar o nome da Câmara, o cartão do O Plenário e **nenhum** campo de usuário;
   - o título da aba é "Entrar · <nome da Câmara>".

### Quem já tinha usuário antes desta mudança

- **Personas da demo e quem já entrava com senha:** nada muda; continuam entrando (agora pelo CPF). Quem não tem código
  cadastrado entra só com a senha — o código é pedido de quem o tem. Ainda não há tela para exigir o código de quem já
  entra (o `idp/resetar-mfa!` faz isso, mas nenhuma rota o chama): um novo convite resolve, porque pede a senha e o
  código de novo.
- **Quem recebeu o convite antigo (só passkey) e ficou sem senha:** novo convite. Em `/administracao`, o `admin_ente`
  concede o acesso de novo à pessoa (o convite sai outra vez); para o 1º administrador da Câmara, o operador usa
  "Reenviar convite" no console.

### Quando a pessoa diz que não consegue entrar

| O que ela vê | O que é | O que fazer |
|---|---|---|
| "Não encontramos acesso de servidor ou vereador para este CPF" | O CPF não tem vínculo institucional **ativo** em nenhuma Câmara | Conferir em `/administracao` da Câmara se o acesso foi concedido (e não revogado) |
| "Este CPF não tem acesso a esta Câmara" | Entrou pelo link de outra Câmara | Entrar por `/entrar` (sem o link) |
| "Muitas tentativas a partir desta rede" | Passou de 30 consultas de CPF em 5 min no mesmo IP | Esperar; se for a rede da Câmara inteira, subir `ENTRADA_LIMITE_POR_IP` |
| "Muitas tentativas erradas. A conta fica bloqueada por alguns minutos" | Trava do Keycloak (10 senhas erradas) | Esperar (1 a 15 min); o Keycloak destrava sozinho |
| "Senha incorreta" e a pessoa esqueceu a senha | — | Novo convite (ver acima). O "esqueci a senha" do Keycloak fica desligado: depende do SMTP de produção |
