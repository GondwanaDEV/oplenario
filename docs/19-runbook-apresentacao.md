# Runbook — apresentação de O Plenário

> **Para quem vai demonstrar a plataforma a um cliente.** Do zero (máquina desligada) até o fim da
> apresentação. Cada comando aqui foi executado neste repositório; onde algo **não** foi verificado
> nesta forma exata, está marcado `[NÃO VERIFICADO]`.
>
> Atualizado em 12/09/2026, depois das três jornadas de teste exploratório (`docs/16`, seções
> "🔎 Exploratório de fluxo"). **A seção §1 é a mais importante deste documento** — ela é o que
> separa uma demo que vende de uma demo que queima o cliente.
>
> **Versão navegável (celular / segunda tela durante a demo, com checklist que lembra o que já foi
> marcado):** https://claude.ai/code/artifact/9ca8bb00-dbd8-45ed-aee5-37a6b645ec5f

---

## §0 · Leia isto se tiver 2 minutos

| | |
|---|---|
| **Tempo total de preparo** | **~4 min** a frio, medido em 15/09 (quase tudo é espera de container) |
| **O que demonstrar** | 3 atos, um por público decisor: servidora · Mesa · cidadão |
| **O que NUNCA abrir** | §1.2 — a lista de telas e botões que expõem defeito conhecido |
| **Se algo quebrar ao vivo** | §6 — os 6 modos de falha conhecidos e o conserto de cada um |
| **A frase de segurança** | "Essa parte está no roadmap de curto prazo" — e passe adiante. Não improvise sobre o que não está pronto |

---

## §1 · Antes de tudo: o que este produto ainda não faz

O sistema tem 13 módulos, 61 migrations e um backend muito mais completo que o frontend. **A
consequência prática para uma demo: várias coisas funcionam por HTTP e não têm tela.** Se você abrir
uma tela esperando um botão que não existe, a demo trava na sua frente.

### §1.1 · O que está sólido e vende

| Ato | Tela | Por que impressiona |
|---|---|---|
| Protocolar matéria | `/editor-proposicao` | Numeração automática, URN LexML, texto versionado |
| Ficha da matéria | `/ficha-materia/<id>` | Ementa, autoria, texto, linha do tempo de tramitação |
| Chamada e quórum | `/sessoes/<id>/chamada` | Lote "Todos presentes", correção individual, justificativa de ausência com deferimento — **é a tela mais completa do produto** |
| Telão do plenário | `/sessoes/<id>/plenario` | Tempo real por SSE, quórum ao vivo, tribuna |
| Cockpit do vereador | `/votar` | Voto nominal pelo celular |
| Folha da sessão | `/sessoes/<id>/folha` | Documento congelado HTML+PDF com dois hashes |
| Pós-aprovação | `/pos-aprovacao/<id>` | Autógrafo ao Executivo + registro da sanção |
| Portal do cidadão | `/portal/casa/<ente>` | Anônimo, sem login, com balcão e-SIC funcional |
| Dashboard da Mesa | `/paineis/mesa` | 7 cartões; o placar do TCE-CE é o argumento de confiança operacional |

### §1.2 · ⛔ Não abra, não clique, não mencione

| O quê | O que acontece |
|---|---|
| **`/paineis/mesa` logado como o presidente** | **403.** As 4 rotas de `/paineis/*` exigem papel `secretario`; `admin_ente` não abre nada. Apresente o dashboard **como a secretária Marina** |
| Botões "Distribuir a comissão", "Incluir na pauta", "Gerar ficha PDF" na ficha | Estão `disabled` com legenda "Em breve". Não os aponte |
| Qualquer tela de **montagem de pauta** com expectativa de editar | `/pauta-convocacao` é read-only. Incluir, reordenar e remover item não têm tela |
| **Agendar sessão / abrir sessão / encerrar sessão** pela interface | Não existem. Deixe a sessão da demo já aberta pela semente |
| **Tribuna: inscrever orador, conceder palavra, cronômetro** | Sem tela. O telão *mostra* a tribuna, mas quem opera é HTTP |
| **Abrir e encerrar votação** pela interface | Sem tela. A votação da demo já vem aberta pela semente |
| **Promulgar / publicar norma** | Sem rota HTTP nenhuma. A matéria sancionada não vira lei publicada por caminho de cliente |
| **Gerar remessa ao TCE** | Sem rota. Só `validar`/`submeter`/`resposta` têm, e exigem um id que só o Clojure produz |
| **Convocação de sessão** | Não existe: nem rota, nem tabela. O cartão "Convocação" é derivado na tela |
| Botão **"Acompanhar"** no portal | Não existe em tela nenhuma — embora a tela vazia de `/acompanhamentos` instrua a usá-lo |
| **A resposta de um pedido e-SIC** | É write-only. A cidadã vê "Situação: Respondido" e nenhum texto |
| Seção de **leis/normas** no portal | Não há porta. O backend tem `/portal/casa/:ente/legislacao`; o frontend não tem tela |
| **A tela de login do Keycloak** | Diz `Sign in to ente-10000000-0000-…` — **em inglês e com o UUID cru**. É a 1ª tela depois de clicar Entrar. Faça o login ANTES do cliente entrar, ou conserte o `displayName` do realm |
| **A capa do portal, sem preparo** | O destaque de "Em tramitação agora" mostra a matéria de **maior número**, sem filtrar estado — hoje é a **PL 015/2026, ARQUIVADA**. O título da seção promete tramitação e o item está morto |
| **Login gov.br** | Não existe |
| **Console do operador** (supratenant) | 3 linhas de código, zero rotas |

### §1.3 · ⚠️ Se um cliente técnico perguntar

Duas perguntas que um jurídico ou um TI de câmara pode fazer, e para as quais **não invente resposta**:

- **"O sistema garante que só vereador em exercício vota?"** — Hoje não. `legislativo.votos.vereador_id`
  não tem chave estrangeira nem verificação contra o cadastro. Diga que a validação de roster está no
  próximo ciclo e siga.
- **"Como o quórum de maioria absoluta é calculado?"** — Hoje o denominador chega no corpo da requisição
  de encerramento. **Não demonstre encerramento de votação com quórum qualificado.** A correção é
  prioridade 1 do backlog.

Ambos estão registrados em `docs/16` com reprodução. Não são hipóteses.

---

## §2 · Subir a stack do zero

### §2.1 · Pré-requisitos

| | |
|---|---|
| Docker / OrbStack | rodando. **4 GiB na VM bastam** — medido, ver a nota abaixo |
| Portas livres no host | 3000 (frontend) · 8888 (backend) · 5544 (Postgres) · 9100/9101 (MinIO) · 8080 (Keycloak) · 8125 (Mailpit) · 6379 (Valkey) |
| Repositório | `/Users/daoudatraore/oplenario`, branch `main`, árvore limpa |

> **Memória — correção de 15/09, medida.** Uma versão anterior deste runbook exigia ≥ 6 GiB na VM.
> Estava errado, e de um jeito que inviabilizaria a demo nesta máquina (um Mac de 8 GiB no total —
> reservar 6 para a VM deixaria 2 para o macOS). **A stack de demo inteira consome ~1,6 GiB**
> (app 617 MiB · frontend 472 · keycloak 349 · minio 77 · postgres 37 · valkey 12 · mailpit 6), e
> numa VM de 3,9 GiB sobram ~2 GiB. O que NÃO cabe em 3,9 GiB é **stack + harness Playwright + suíte
> de testes ao mesmo tempo** — e nada disso entra numa apresentação. A armadilha registrada em
> `oplenario-vm-orbstack-teto` continua real; o erro foi aplicá-la ao cenário errado.

**Mandato Docker deste projeto:** nunca rode `node`, `npx`, `npm` ou `clj` direto no host. Tudo em
container, sem exceção.

### §2.2 · Reset limpo (obrigatório antes de um cliente)

A Casa da demo é **append-only** e não é limpável incrementalmente — as sementes são idempotentes por
*pular*, então releem em vez de reconstruir. Só `down -v` restaura. Prova em
`demo/POR-QUE-NAO-DA-PARA-LIMPAR.md`.

```bash
cd ~/oplenario/apps/backend

# 1. Rede de segurança (30s) — sempre, antes de qualquer down -v
docker exec oplenario-postgres-1 pg_dump -U oplenario -Fc oplenario \
  > ~/oplenario/.backups/oplenario-pre-demo-$(date +%F-%H%M).dump

# 2. Derrubar TUDO, inclusive volumes (é o -v que limpa)
docker compose --profile auth down -v

# 3. Subir com Keycloak (login real de personas)
OPLENARIO_APP_ENV=production docker compose --profile auth up -d --build
```

**Espere de verdade.** O `up` frio tem três relógios diferentes:

| Serviço | Tempo **medido em 15/09** | Como saber que terminou |
|---|---|---|
| `up -d --build` inteiro (imagens em cache) | **61s** | o comando retorna |
| Backend (`app`) | **31s** até `/saude` responder 200 | `docker logs oplenario-app-1` imprime `[oplenario] sistema no ar` |
| **Keycloak** | **~72s a frio** | Enquanto sobe responde **400** nas chamadas admin — isso **não** é erro de credencial |
| Frontend (`next dev`) | healthy em ~49s; depois cada rota compila sob demanda no 1º acesso | A 1ª visita a cada tela é lenta — aqueça você, não o cliente |
| `semear-tudo.sh` | **26s** | imprime `==> semear-tudo.sh OK` |
| `semear-credenciais.sh` | **11s** | imprime `==> semear-credenciais.sh OK` |

**Preparo real de ponta a ponta: ~4 minutos**, não os 15 que a §0 estimava.

Portão único antes de seguir:

```bash
docker compose --profile auth ps      # 7 serviços, app e frontend "healthy"
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8888/saude    # 200
```

### §2.3 · Semear a Casa

Duas sementes, **nesta ordem**. A segunda depende da primeira.

```bash
cd ~/oplenario

# A Casa: 1 ente + 17 vereadores + Mesa + 3 comissões + acervo + 3 sessões + participação
./demo/semear-tudo.sh

# As credenciais Keycloak das 4 personas (exige o profile auth de pé)
./demo/semear-credenciais.sh
```

`semear-tudo.sh` já embute a **barreira de projeção**: ele espera o relay materializar as matérias e o
perfil público antes de terminar. Se ele falhar nessa espera, o container `app` (que hospeda o relay)
não está de pé — conserte isso antes de seguir.

Ao fim, as credenciais ficam em `e2e/.artifacts/credenciais.edn`. **Os usernames mudam a cada
`down -v`** — leia sempre do arquivo, nunca de memória:

```bash
cat e2e/.artifacts/credenciais.edn
```

### §2.4 · Pré-voo: 6 verificações que nenhum teste faz

Rode isto **20 minutos antes** do cliente entrar, não na hora.

```bash
cd ~/oplenario

# 1. Restart do frontend — obrigatório APÓS UM `--build` SOBRE VOLUME EXISTENTE.
#    O volume anônimo de /app/.next sobrevive ao --build e serve 404 em rota que existe em disco.
#    Depois de um `down -v` o volume nasce limpo e este passo é dispensável (medido em 15/09).
docker restart oplenario-frontend-1

# 2. A JVM está viva? (crash de SIGBUS derruba TODAS as telas de uma vez)
docker logs oplenario-app-1 2>&1 | grep -c SIGBUS      # tem de ser 0

# 3. A Casa tem o que se espera
docker exec oplenario-postgres-1 psql -U oplenario -d oplenario -tAc \
  "set app.ente_id='10000000-0000-0000-0000-000000000001';
   select 'vereadores='||count(*) from cadastros.vereador
   union all select 'sessoes='||count(*) from sessoes.sessao
   union all select 'proposicoes='||count(*) from legislativo.proposicoes;"
# esperado: vereadores=17 · sessoes=3 · proposicoes=24

# 4. O placar de compliance está cheio (é o 1º cartão do dashboard)
docker exec oplenario-postgres-1 psql -U oplenario -d oplenario -tAc \
  "set app.ente_id='10000000-0000-0000-0000-000000000001';
   select estado||'='||count(*) from compliance.prazo_dominio_ativo group by estado;"
# esperado: cumprida=6 · pendente=1 · vencida=1
# se vier VAZIO, a 6ª etapa da semente não rodou — veja §6.5

# 5. Aqueça as telas (o next dev compila sob demanda; faça isso, não o cliente)
for u in / /portal/casa/10000000-0000-0000-0000-000000000001 /entrar/10000000-0000-0000-0000-000000000001; do
  curl -s -o /dev/null -w "$u -> %{http_code} em %{time_total}s\n" "http://localhost:3000$u"
done
```

**6. Abra cada tela do roteiro no browser, uma vez, com a persona certa.** `curl` devolvendo 200 não
prova tela nenhuma aqui — quase tudo é Client Component que busca *depois* da hidratação. Esta é a
única verificação que pega tela em branco, rótulo cru e erro de console.

---

## §3 · O roteiro da apresentação

Três atos, um por público decisor. **~25 minutos.** Cada ato responde à pergunta que aquele público
faz na licitação.

Entrada única: `http://localhost:3000/entrar/10000000-0000-0000-0000-000000000001`
Senha de todas as personas: `Plenario@2026` · usernames em `e2e/.artifacts/credenciais.edn`

> **Prepare as abas antes.** Faça login com as três personas em três janelas/perfis diferentes do
> browser **antes** do cliente entrar. Trocar de persona ao vivo custa 40 segundos de silêncio.

### Ato 1 · A servidora (8 min) — *"isto me poupa trabalho"*

Persona: **Marina Alencar Freire**, Secretária da Mesa.

| # | Faça | Diga |
|---|---|---|
| 1 | `/proposicoes` | "Este é o acervo da Casa. 24 matérias, filtráveis por espécie." |
| 2 | `/editor-proposicao` → protocole um PL novo | "A numeração, o ano e a URN LexML são automáticos. A servidora não digita número de protocolo." |
| 3 | Abra a ficha da matéria recém-criada | "Ementa, autoria, texto integral e a linha do tempo de tramitação, no mesmo lugar." |
| 4 | `/tramitacao` | "O quadro da Casa inteira, por estágio." — **é leitura; não tente arrastar** |
| 5 | `/sessoes/<id-da-sessão-aberta>/chamada` | **O ponto alto do ato.** "Todos presentes" em um clique, corrija um para Ausente, lance a justificativa e defira. |
| 6 | `/sessoes/<id>/folha` | "A ata de presença sai congelada, em HTML e PDF, com hash de integridade." |

### Ato 2 · A Mesa (9 min) — *"isto me faz parecer bem"*

Persona: **Marina** (secretária) — **não** o presidente. Ver §1.2.

| # | Faça | Diga |
|---|---|---|
| 1 | `/paineis/mesa` | "O painel de prestação de contas da Mesa." |
| 2 | Aponte o cartão de saúde institucional: **Em dia 6 · Pendentes 1 · Vencidas 1** | "A Casa sabe, em tempo real, se está em dia com o TCE-CE. A obrigação vencida está sinalizada antes de virar multa ao presidente." |
| 3 | `/calendario` | "Os prazos de compliance no calendário, junto com as sessões." |
| 4 | `/sessoes/<id>/plenario` (telão) | "O telão da sessão, ao vivo: quórum, tribuna e placar de votação, por SSE." |
| 5 | Em outra janela, `/votar` como a vereadora **Fernanda** | "E o vereador vota do celular." Vote, e mostre o telão atualizando |

> **O relógio do telão:** o canal SSE tem retenção de **~5 minutos**. Se a sessão foi semeada há mais
> tempo, a tribuna pode aparecer vazia (o quórum volta, porque lê do banco; a tribuna não). Se for
> demorar, re-semeie os eventos antes deste ato — §6.4.

### Ato 3 · O cidadão (6 min) — *"isto me dá votos"*

**Sem login.** Abra uma janela anônima — o efeito de mostrar que não exige cadastro é parte do argumento.

| # | Faça | Diga |
|---|---|---|
| 1 | `/portal/casa/10000000-0000-0000-0000-000000000001` | "O portal do cidadão. Nada aqui pede login." |
| 2 | Abra uma matéria em destaque | "A ficha completa, com o texto integral." |
| 3 | Abra o perfil público de um vereador | "Cada vereador tem uma página com presença e histórico." |
| 4 | Balcão e-SIC na capa → busque um protocolo | "Pedido de informação com acompanhamento por número, sem cadastro." |
| 5 | `/status` | "A página de status pública — transparência sobre a própria plataforma." |

**Não navegue além disso no portal.** As rotas públicas do frontend são exatamente 4 (capa, matéria,
vereador, status) mais o balcão. Links do menu que apontam para "Em breve" existem.

### Encerramento (2 min)

As três apostas, na ordem em que o cliente as viveu: **experiência de produto** (o que ele acabou de
ver), **confiança operacional** (o painel do TCE), e **IA como copiloto legislativo** — esta última
como visão, porque a Track IA ainda não tem código. Não a demonstre.

---

## §4 · As quatro personas

Leia sempre os usernames de `e2e/.artifacts/credenciais.edn` — eles mudam a cada `down -v`.

| Persona | Papéis | Serve para |
|---|---|---|
| Marina Alencar Freire | `secretario` | **Atos 1 e 2.** É a persona com mais superfície funcional |
| Antônio Carlos Ferreira | `vereador` + `admin_ente` | Presidente da Mesa. **Não abre `/paineis/*` (403)** — use só para votar |
| Fernanda Rocha Pinto | `vereador` | O cockpit de votação em `/votar` |
| Roberta Costa Aguiar | (nenhum) | Cidadã. O portal é anônimo; ela quase não é necessária |

---

## §5 · Modo de desenvolvimento (para você testar, nunca para o cliente)

Existe um segundo modo de autenticação que dispensa Keycloak: o token é um JSON cru na querystring.
Serve para você exercitar fluxos rapidamente — **nunca** para uma apresentação, porque é uma porta
aberta e o cliente pode ver a URL.

```bash
cd ~/oplenario/apps/backend
OPLENARIO_APP_ENV=dev docker compose up -d app frontend    # 'dev' é o default; basta omitir a env

# token da secretária, para colar na querystring como ?token=<url-encoded>
python3 -c "import json,urllib.parse;print(urllib.parse.quote(json.dumps({
  'identidade-id':'<identidade-id da Marina>',
  'ente-id':'10000000-0000-0000-0000-000000000001',
  'papeis':['secretario']})))"
```

Voltar ao modo de apresentação:

```bash
OPLENARIO_APP_ENV=production docker compose --profile auth up -d app frontend
```

> Se o `docker compose up` falhar com `cannot stop container: tried to kill container, but did not
> receive an exit event`, o container fica com o nome corrompido (prefixado com o hash). Vários
> scripts do repositório dependem do nome canônico. Conserto:
> `docker rm -f <nome-corrompido> && docker compose --profile auth up -d frontend`

---

## §6 · Se der errado ao vivo

Os seis modos de falha conhecidos. Todos já aconteceram neste projeto.

### §6.1 · Todas as telas em 500 ao mesmo tempo
A JVM do backend morreu. `docker ps` não mostra `oplenario-app-1`.
```bash
docker logs oplenario-app-1 2>&1 | grep SIGBUS     # confirma o diagnóstico
docker compose up -d app                            # o restart automático já deveria ter agido
```
Causa: páginas de `/tmp/hsperfdata_root` somem sob OrbStack/aarch64. Mitigado por
`-XX:-UsePerfData` + `restart: unless-stopped`, mas o sintoma é reconhecível se voltar.

### §6.2 · Uma rota que existe devolve 404
O volume anônimo de `/app/.next` está servindo o índice de rotas velho. O log do Next registra
`404 in 92ms` como se a rota não existisse.
```bash
docker restart oplenario-frontend-1
```
Por isso o restart do frontend está no pré-voo (§2.4). **Faça-o sempre.**

### §6.3 · O frontend dá 500 em todas as rotas depois de um restart
Panic do worker de PostCSS no `next dev` a frio. Não é CSS nem branch.
```bash
docker restart oplenario-frontend-1     # tente uma vez
```
Se persistir, o caminho confiável é servir o build de produção em vez do `next dev`.
`[NÃO VERIFICADO nesta sessão]`

### §6.4 · O telão está vazio / a tribuna sumiu
O canal SSE tem retenção de ~5 min em Valkey, e um restart do `app` apaga a sessão ao vivo. O quórum
volta (lê do banco); a tribuna não (deriva só do SSE).
```bash
cd ~/oplenario && ./demo/semear-tudo.sh     # idempotente: relê em vez de duplicar
```

### §6.5 · O placar de compliance está zerado
A 6ª etapa da semente (`demo/compliance.clj`) não rodou. Verifique com a consulta do §2.4 item 4.
`semear-tudo.sh` a inclui; se você semeou por outro caminho, ela pode ter ficado de fora.

### §6.6 · "Acesso restrito" logo depois do login
O gate do frontend lê os papéis dos claims do token; o backend lê do banco. Um token sem `papeis`
navega como se não tivesse nenhum, mesmo com o papel gravado. Se acontecer com uma persona do
Keycloak, é bug — reporte. Com um token de dev montado à mão, é o token que está incompleto.

### §6.8 · O Keycloak sai com `Exited (255)` depois de ligar a máquina
Medido em 15/09: ao subir o OrbStack com os containers já existentes, o Keycloak arranca junto com o
Postgres e morre com `Acquisition timeout while waiting for new connection` no pool do Agroal — é
corrida de boot, **não** falta de memória nem credencial errada. Subir de novo resolve, e ele responde
em ~72s:
```bash
OPLENARIO_APP_ENV=production docker compose --profile auth up -d keycloak
until curl -sf -o /dev/null http://localhost:8080/realms/master; do sleep 2; done
```

### §6.7 · O Postgres morre sozinho
OOM da **VM**, não do container. Suba a memória do OrbStack para ≥ 6 GiB. A prova está no `dmesg` da
VM, nunca no log do container.

---

## §7 · Depois da apresentação

```bash
cd ~/oplenario/apps/backend

# Se demonstrou escritas, a Casa mudou. Deixe-a limpa para a próxima:
docker compose --profile auth down -v
# (a próxima demo recomeça do §2.2)

# Ou apenas pare, preservando o estado:
docker compose --profile auth stop
```

> ⚠️ **Nunca rode a suíte de testes contra o banco da demo.** Ela cria dezenas de entes de teste e
> apaga `transparencia.materia`; as sementes não reconstroem. Se rodar, o caminho de volta é
> `down -v` + `semear-tudo.sh`.

---

## §8 · Procedência deste documento

- **Verificado nesta sessão (12/09/2026):** as portas e o `.env`; o modo dev e o `production` com
  Keycloak; o placar de compliance 6·1·1; os usernames em `credenciais.edn`; a recriação de container
  com nome corrompido; e todas as telas listadas em §1.1 e §1.2, exercitadas nas três jornadas de
  teste exploratório.
- **Vem das fontes do repositório, não de memória:** os tempos de boot (`docker-compose.yml`,
  comentários de `start_period` e do `semear-credenciais.sh`), a receita de reset
  (`demo/POR-QUE-NAO-DA-PARA-LIMPAR.md`), os modos de falha §6.1–§6.4 e §6.6
  (memória `oplenario-prontidao-demo`, varredura de 01–02/09/2026).
- **`[NÃO VERIFICADO]`:** o fallback de build de produção em §6.3.
- **Um ciclo completo a frio** (`down -v` → `up --build` → semear → apresentar) **não foi executado
  nesta sessão** — os comandos vêm dos scripts e do compose, e cada um foi lido na fonte. Rode-o uma
  vez, sem cliente, antes da primeira apresentação real.

Achados completos que sustentam a §1: `docs/16-ledger-prontidao.md`, seções "🔎 Exploratório de fluxo".
