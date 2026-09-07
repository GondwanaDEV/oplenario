# Ledger de prontidão de apresentação

Produzido pela **Fase 2** do plano `docs/superpowers/plans/2026-09-07-prontidao-de-apresentacao.md`:
caminhada exploratória em browser real, sobre a Casa da Fase 0 (ente `10000000-…-0001`), com token
de dev como cada ator.

**Classes:** (A) DEFEITO — código faz a coisa errada · (B) LACUNA — nunca foi construído ·
(C) DADO — código certo, semente não sustenta a história.
**Gravidade:** `MATA` (não dá para apresentar) · `CONSTRANGE` (dá, com desculpa) · `PASSA` (ninguém nota).

**Regra de disciplina:** nenhuma linha entra sem que o endpoint tenha sido chamado direto. Tela vazia
com endpoint devolvendo dado é defeito de FE; tela vazia com endpoint devolvendo `[]` é dado; tela
vazia sem endpoint nenhum é lacuna.

---

## J1 — A Casa existe (`/cadastros/vereadores`, como a secretária)

**Estado geral: a jornada funciona.** 17 vereadores com nome, partido e cargo; ficha com mandato,
legislatura "19ª (2025–2028)" e posse; os 4 botões de escrita (Editar, Registrar mandato, Registrar
licença, Conceder acesso) habilitados; o licenciado aparece com selo "Licença".

| # | Tela | O que aconteceu | Endpoint direto responde? | Classe | Gravidade |
|---|---|---|---|---|---|
| **1** | `/cadastros/vereadores` | A lista mostra **`1_secretario`** e **`2_secretario`** como cargo, crus | Sim — o valor vem assim do banco (`comissao_cargo.cargo`) | **A** | **CONSTRANGE** |
| **2** | topo de toda tela `(interno)` | Cabeçalho diz **"Rita Campos · Servidora legislativa"** — hardcoded, não é quem está autenticado | N/A — não há fetch de ator | **A** | **CONSTRANGE** (vira `MATA` se a demo mostrar login) |
| **3** | ficha do vereador | A ficha de **Antônio Ferreira** diz **"Cargo na Mesa: Sem cargo na Mesa"** e **"Sem comissões atribuídas"** — enquanto a lista **ao lado, na mesma tela**, diz "PT · presidente" | Sim, e **confirma a tela**: `GET /cadastros/vereadores/:id` devolve `cargo-mesa: null`, `comissoes: []`. Mas `GET /sessoes/:id/composicao` devolve `cargo-mesa: "presidente"` **para o mesmo vereador** | **A + C** | **MATA** |
| **4** | (causa raiz de #3) | **`Mesa Diretora` tem 0 membros** em `cadastros.comissao_membro`; as 3 comissões permanentes têm 3 cada. Os 4 cargos existem em `comissao_cargo` com vereador ligado | — | **C** | **MATA** |
| **5** | ficha do vereador | "proposições — Em breve", "presença — Em breve", "Contato institucional — Em breve", botão "Ver proposições" desabilitado | Lacuna declarada no próprio texto da tela | **B** | **PASSA** |

**Nota sobre #3/#4 — o mecanismo:** a ficha resolve cargo de Mesa por um caminho que exige linha em
**`comissao_membro`** *e* em `comissao_cargo`; a lista e o `/composicao` leem `comissao_cargo`
direto. A semente criou só o cargo. Resultado: **três endpoints, dois answers para o mesmo fato**, e a
contradição fica visível lado a lado na mesma tela. Corrigir a semente (dar membros à Mesa) resolve a
demonstração; a divergência entre os três caminhos de leitura é defeito real que sobrevive à semente e
apareceria com dado de cliente.

---

## J4 — A sessão acontece (`/sessoes/:id/plenario`, o telão) — HERO

**O que funciona, e é o ganho da Fase 0:** "Ao vivo", "Sessão ordinária nº 2", modalidade presencial,
cronômetro "Sessão há 25:00", trilha de fase (Agendada ✓ → Aberta) e — o que estava quebrado de manhã
— **"Quórum: 12 de 16 vereadores presentes"**, com número real e **zero UUID na tela**.

| # | Tela | O que aconteceu | Endpoint direto responde? | Classe | Gravidade |
|---|---|---|---|---|---|
| **6** | `/plenario` | A região central, **"Pauta da sessão"**, diz **"Nenhum item ativo na pauta ainda."** — no telão da sessão ao vivo | Sim, corretamente vazio: a sessão `aberta` tem **0 itens**; os 5 itens estão na sessão **`agendada`** | **C** | **MATA** |
| **7** | `/plenario` | A **Tribuna** diz **"Ninguém com a palavra no momento"** — mas o banco tem **1 fala aberta** (`encerrou_em is null`) nessa exata sessão | **Não há endpoint de tribuna.** O plenário lê `/sessoes/:id`, `/quorum`, `/composicao` e `/pauta` — a tribuna vem **só por evento SSE** | **A/B** | **MATA** |
| **8** | `/plenario` | A trilha de fase apresenta **"Suspensa"** como etapa 3 de uma progressão linear Agendada→Aberta→Suspensa→Encerrada | — | **A** | **PASSA** |

**Nota sobre #7 — o achado estrutural da caminhada.** A tribuna do telão **não tem read-model de
montagem**: seu estado existe apenas no canal ao vivo (Valkey, retenção de 5 min). Consequências que
nenhum teste pega e que a apresentação sente:

1. **Uma fala semeada no banco nunca aparece** — a semente escreve no banco, não publica evento.
2. **Recarregar a página apaga a tribuna.** Quem estiver com a palavra some do telão até a próxima
   emissão de evento. Isto **já estava registrado como armadilha conhecida** ("tribuna apagada por
   restart") e acaba de reproduzir.
3. Um `F5` acidental no meio da demonstração esvazia a região.

**Este é o defeito de maior consequência achado até agora**, e é invisível para a suíte: 1986 testes
de backend e 1078 de frontend passam, porque nenhum deles monta a página depois de um restart.

---

## Varredura automática (Fase 1) — 27/27 rotas, veredicto REPROVADO 6/27

A sonda foi corrigida (lia ids cravados à mão; agora lê `e2e/.artifacts/demo-ids.edn` e falha alto se
faltar) e ganhou veredicto — antes **sempre saía com código 0**, só reportava. Provado que reprova:
defeito plantado em `/tramitacao` levou o placar a 7/27 nomeando a rota; revertido byte a byte.
**Os mesmos 6 defeitos em 4 rodadas consecutivas — não é intermitência.**

| # | Rota | Achado | Classe | Gravidade |
|---|---|---|---|---|
| **9** | `/proposicoes` | enum cru **`aguardando_pauta`** na tela | **A** | **CONSTRANGE** |
| **10** | `/paineis/mesa` | enum cru **`aguardando_pauta`** e **`AGUARDANDO_PAUTA`** | **A** | **CONSTRANGE** |
| **11** | `/parecer/:id` | **UUID cru na tela** — a comissão aparece como `9119889e-…` em vez de nome | **A** | **MATA** |
| **12** | `/parecer/:id/assinar` | **404 "parecer não encontrado"** — a identidade `vereador` da demo **nunca é relatora** de parecer nenhum (o acervo sorteia relatores round-robin entre os 17) | **C** | **MATA** |
| **13** | `/portal/casa/:ente` e `…/materias/:id` | "resumo em linguagem simples **indisponível**" | **B** | a decidir na Fase 3 |

**O #11 confirma o suspeito registrado antes da caminhada.** `acervo.clj` grava `comissao-id` como
`random-uuid` (guard ref sem FK, convenção dos próprios testes do módulo) e **a tela mostra esse id**.
Estava marcado como "verificar se é visível"; é. Sobe de suspeita a defeito `MATA`.

**O #12 mata a jornada J3 inteira.** O login "vereador" da demonstração não consegue assinar parecer
nenhum, porque nenhum parecer o tem como relator. É dado, não código — mas fecha uma das nove jornadas.

**O #13 é a Track IA**, que não tem código (CLAUDE.md §3). Não é defeito novo; é a lacuna maior do
projeto aparecendo na tela do cidadão. Entra na decisão da Fase 3 — provavelmente "contornar", já que
o portal público é justamente a tela do público decisor "presidente da Mesa".

---

## Placar até aqui

| Classe | `MATA` | `CONSTRANGE` | `PASSA` | Total |
|---|---|---|---|---|
| **A — defeito** | 2 (#3, #11) | 4 (#1, #2, #9, #10) | 1 (#8) | **7** |
| **C — dado** | 3 (#4, #6, #12) | — | — | **3** |
| **A/B — tribuna sem read-model** | 1 (#7) | — | — | **1** |
| **B — lacuna** | — | — | 2 (#5, #13) | **2** |

**6 defeitos `MATA` com 2 de 9 jornadas caminhadas e a varredura automática feita.** Nenhum deles
aparece na suíte: 1986 testes de backend e 1078 de frontend estão verdes.

---

## Pendente de caminhada

J2 (a matéria nasce) · J3 (o parecer) · J5 (a lei nasce) · J6 (o cidadão) · J7 (a Mesa) ·
J8 (entrar de verdade) · J9 (o expediente).

**Suspeitos já registrados no plano, a confirmar nessas jornadas:** parecer apontando para comissão
inexistente (`acervo.clj` grava `comissao-id` como `random-uuid`) e atos sem autoria
(`created-by`/`updated-by` nulos em todo o acervo).


---

# FASE 4 — o que foi consertado (07/09/2026)

**Prova executada contra reconstrução limpa**, não contra dado remendado: `docker compose down -v` →
`up` (74 migrations, `entes=0`) → `./demo/semear-tudo.sh` → `./e2e/.sonda/rodar.sh`.

## Placar da sonda: **6/27 → 3/27**

| # | Defeito | Estado | Prova |
|---|---|---|---|
| **3, 4** | Ficha do presidente contradizia a lista | ✅ **morto** | `GET /cadastros/vereadores/:id` devolve `cargo-mesa: "presidente"` e `comissoes: ["Mesa Diretora"]` — era `null` e `[]` |
| **6** | Telão ao vivo sem pauta | ✅ **morto** | `GET /sessoes/:id/pauta` da sessão `aberta` devolve **3 itens** — era 0 |
| **9, 10** | `aguardando_pauta` / `AGUARDANDO_PAUTA` crus | ✅ **mortos** | `/proposicoes` rende "Aguardando pauta" ×8, "Em comissões" ×8, zero underscore; `/paineis/mesa` limpo na sonda |
| **12** | J3 morta (404 no assinar) | ✅ **morto** | `/parecer/:id/assinar` responde **200** na sonda |
| **11** | UUID cru em `/parecer/:id` | ❌ **VIVO** | ver abaixo |
| **13** | "resumo em linguagem simples indisponível" | ❌ vivo, **esperado** | Track IA não tem código |
| **7** | Tribuna sem read-model | ❌ **VIVO** | decisão estrutural pendente |
| **1, 2, 8** | `1_secretario` na lista · ator hardcoded · trilha de fase | ❌ vivos | `CONSTRANGE`/`PASSA`, não bloqueiam |

## #11 — a premissa do conserto estava errada

O plano previa que semear comissões reais tiraria o UUID da tela. **Não tirou, e não podia tirar:**
o frontend **nunca resolve `comissaoId` → nome, em lugar nenhum** — `lib/parecer-vista.ts:12-14`
(o comentário do próprio arquivo registra como carry), `app/(interno)/parecer/[id]/page.tsx:93`
(`{dados.comissaoId}` cru), `rail-parecer.tsx:39`. Uma comissão *real* continua sendo um UUID para o
olho de quem assiste. **O conserto de semente tinha valor próprio** (o guard ref deixou de ser órfão)
mas o sintoma é **defeito de frontend**, e continua aberto.

## Divergência estrutural documentada, NÃO consertada (decisão do Daouda)

Três caminhos leem "cargo na Mesa" de duas formas:

| Caminho | Como lê |
|---|---|
| Ficha `GET /cadastros/vereadores/:id` | `cadastros/db/comissao.clj:54-71` — **INNER JOIN em `comissao_membro`** |
| Lista `GET /cadastros/vereadores` e `GET /sessoes/:id/composicao` | `cadastros/db/vereador.clj:276-291` — lê **`comissao_cargo` direto**, sem exigir membro |

A semente foi corrigida (Mesa também vira membro), mas **a divergência sobrevive**: um cliente real
com cargo de Mesa sem membro correspondente — que o modelo permite, e a Mesa historicamente era "só
cargo" — faz a ficha voltar a mostrar `null` enquanto lista e composição mostram o cargo.

## Duas regressões que só a execução real pegou

Nenhuma aparecia em teste unitário; apareceram rodando `semear-tudo.sh` de verdade:

1. `ler-cadastro` mudou a ordem de chave no EDN e quebrou quem lia por posição.
2. `demo/semear-tudo.sh` extraía o id do 1º vereador com um `sed` **ganancioso**, que pegava o
   **último** `:id` do arquivo. Só "funcionava" porque `:vereadores` era, por acaso, a última
   estrutura com `:id` no EDN — ao acrescentar `:comissoes`, quebrou. O comentário do próprio script
   afirmava que pegava o primeiro.

---

# #11 — MORTO (07/09/2026, commit `1546e45`)

**A causa era a que a caminhada apontou, e ela não tem conserto de dado:** não existe, em nenhuma
rota do backend, resolução `comissao-id` → nome. `cadastros/diplomat/http/in.clj` expõe apenas
`/cadastros/vereadores*` e `/cadastros/legislatura-vigente`; a ficha do vereador devolve as
comissões dele por `nome`/`tipo`/`cargo` e **deliberadamente sem o `id`**
(`cadastros/wire/out/vereador.clj`, `ComissaoDoVereadorOut`). O frontend não tinha de onde tirar o
nome — não era preguiça de view-model, era ausência de fonte. E `legislativo.pareceres.comissao_id`
é `uuid NOT NULL` (migration 20260620000019), então imprimir o campo era imprimir um UUID.

**O conserto é a mesma degradação honesta que o relator já usava** ("Relator designado"): afirmar
que *há* uma comissão designada, sem inventar qual. `lib/comissao-vista.ts` passa a ser o único
lugar que decide isso — `nomeDeComissao` devolve `null` para quem prefere **omitir** a linha (o rail
do editor, cujo rótulo do campo já diz "Comissão"), `rotularComissao` devolve o rótulo para quem
exibe a comissão **sozinha** (subtítulo da tela, linha da lista). `derivarRelatoria` deixou de
devolver o `comissaoId`: o que não sai do view-model não tem por onde chegar na tela.

| Superfície | Antes | Agora |
|---|---|---|
| `/parecer/:id` subtítulo | `Comissão 9119889e-…` | `Comissão designada · Aprovado` |
| `/parecer/:id` rail "Relatoria" | `Comissão / 9119889e-…` | a linha some (só Relator + Distribuído) |
| `/ficha-materia/:id` aba Pareceres | um UUID por linha | `Comissão designada` |

## O que este conserto ensinou, e não era sobre comissão

**Três testes AFIRMAVAM o vazamento** — e por isso ficaram verdes durante meses com um UUID no ar:

| Teste | O que exigia |
|---|---|
| `parecer-vista.test.ts` | `expect(v.comissaoId).toBe(base.comissaoId)` |
| `rail-parecer.test.tsx` | `getByText("c-ccj")`, sob o nome *"comissão crua"* |
| `ficha-materia-tabs.test.tsx` | `getByText("CCJ")`, com fixture de **código legível** onde o dado real é `uuid NOT NULL` |

É o mesmo padrão que o handoff já registrava como dominante em três frentes seguidas: **teste com o
nome da garantia que não a exercita**. Aqui a variante é pior — o teste não só deixava de exercitar,
ele *travava* o defeito: consertar a tela reprovava a suíte. **Fixture com formato irreal é o
mecanismo** (`"CCJ"`/`"c-ccj"` onde o banco só produz UUID); as fixtures agora trazem o formato de
verdade e as asserções olham a tela, não o id.

**Quatro asserções de outro conserto meu ainda exigiam a chave crua.** Os defeitos #9/#10
(humanização de enum) foram provados no arquivo de teste do próprio módulo, mas a suíte **inteira**
não foi rodada: `ficha-materia-vista`, `ficha-vista` e `proposicoes-vista` seguiram vermelhos até
agora. Conserto que muda contrato de view-model exige a suíte toda, não o arquivo vizinho.

**Um quarto vazamento, que só a tela viva mostrou.** A aba de pareceres imprimia
`Voto do relator: favoravel` — sem acento e sem maiúscula, portanto **invisível ao detector de
underscore da sonda**. `rotularVoto` já existia em `parecer-vista.ts` e não estava sendo usado ali.
Detector estrutural pega a forma que ele conhece; o resto continua sendo olho.

## Placar da sonda

**3/27 → 2/27.** As duas restantes são o **#13** (`/portal/casa/:ente` e `…/materias/:id`, "resumo em
linguagem simples indisponível") — Track IA sem código, decisão da Fase 3, não defeito novo.

## O nome de verdade — feito (07/09/2026, commit `139159a`)

Não era só decisão de backend: era a metade que faltava. **A tela agora diz "Comissão de Obras e
Serviços Públicos"**, no subtítulo, no rail e na aba da ficha. Como previsto, `rotularComissao`
sumiu do caminho feliz sem que nenhum componente mudasse — o parâmetro passou a ter valor.

**A forma é a exceção nomeada da §22.5.3, a mesma de `resolver-vereador`:** o host resolve e injeta
a fn pronta; o `legislativo` continua sem importar `cadastros`.

| Camada | O que entrou |
|---|---|
| `cadastros/db/comissao.clj` | `nomes-por-id` — o lote numa consulta só |
| `RepoCadastros` | `nomes-de-comissoes` |
| `rotas.clj` | `resolver-comissoes`, injetada em `legislativo-http/rotas` |
| `legislativo/controllers.clj` | `nomear-comissoes` decora cada parecer com `:comissao-nome` |
| wire/out + adapters/out | `comissao-nome` em `ParecerEditorOut` e `ParecerResumoOut` |
| contrato TS | regenerado pelo codegen — +2 campos, zero drift |

**Plural de propósito.** A ficha lista N pareceres; um resolver singular custaria N transações por
request. Materia sem parecer não chama o resolver, e 404 não paga transação de cadastros.

**Degrada, nunca inventa.** Id sem comissão correspondente — guard ref órfão, ou comissão de outra
Casa, que a RLS já corta — não aparece no mapa. `comissao-nome` é `{:optional true} [:maybe :string]`
nos dois wire/out: o contrato não pode quebrar porque o resolver não achou dono, e o adapter jamais
cai no `comissao-id` como substituto — era exatamente isso que punha o UUID na tela.

**Provas, não só verde.** Teste de repositório contra Postgres real cobre lote, id desconhecido,
coll vazia, ids repetidos e **isolamento por RLS** (comissão de outro ente nunca vira nome). Os
testes de borda ganharam uma **segunda linha de parecer cuja comissão o resolver não conhece** —
prova que a ausência sai `nil` e não derruba o 200. E a stack de pé mostra o nome real nas três
telas. Backend 2007 testes, sem falha própria; FE 1093/0.

**A borda `/meu` entrou junto:** o vereador-relator via o mesmo UUID que o servidor.

---

# #7 — a tribuna sem read-model: anatomia verificada (07/09/2026)

Levantado na fonte para a decisão da Fase 3. **Não é opinião de leitura antiga — cada linha foi
conferida no código nesta data.**

## O que é

A tela do plenário tem 5 painéis. **Quatro sabem se reconstruir sozinhos; a tribuna não.**

| Painel | Como obtém o dado |
|---|---|
| Sessão | `GET /sessoes/:id` (`use-plenario.ts:136`) |
| Quórum | `GET /sessoes/:id/quorum` (`use-plenario.ts:73`) |
| Composição | `GET /sessoes/:id/composicao` (`use-plenario.ts:171`) |
| Pauta | `GET /sessoes/:id/pauta` (`use-pauta.ts:27`) |
| **Tribuna** | **nenhum GET existe** |

A tribuna tem **5 rotas de escrita e zero de leitura** (`sessoes/diplomat/http/in.clj:875-889`):
`POST /sessoes/:id/inscricoes` · `.../inscricoes/:insc-id/desistir` · `.../falas` ·
`.../falas/:fala-id/cronometro` · `.../falas/:fala-id/encerrar`. **É a única entidade da sessão nessa
situação** — justificativas, gravação, folhas, pauta, chamada e quórum todas têm GET.

## O dado existe; a tela é que não tem como perguntar

`sessoes.fala_executada` (mig `20260620000033`) guarda a fala com `iniciou_em` preenchido e
`encerrou_em NULL` enquanto em curso. A fila está em `inscricao_oradores` (mig `…032`) e os marcos do
cronômetro em `fala_cronometro_evento` (append-only). O achado original desta caminhada foi exatamente
esse: **o banco tinha 1 fala aberta e a tela dizia "Ninguém com a palavra no momento".**

No frontend, `oradorAtual`, a fila e `marcosCronometro` nascem vazios em `plenario-reducer.ts` e só
são preenchidos por evento SSE.

## Os três momentos em que o telão apaga

1. **F5 / navegador se recuperando.** O cursor do stream é `lastIdRef`, um `useRef` inicializado
   `undefined` (`use-plenario.ts:56`) — some no reload; o stream recomeça "de agora" e o orador atual
   fica invisível **pelo resto da fala dele**.
2. **Abrir o telão depois que a fala começou** — o caso mais provável ao conectar um projetor.
3. **Queda de rede > 5 min** (janela de replay do canal, `tempo_real/components.clj:39-40`). O
   `rehidratar()` da reconexão busca quórum, sessão e composição — **a tribuna não, porque não há rota**.

## ⚠️ Correção: "semear por evento" NÃO é uma terceira opção

Estava listada como opção (c) na Fase 3 e **está errada**. Sem replay por `Last-Event-ID` — e não há,
depois de um F5 — publicar o evento só funciona se a tela já estiver aberta no instante em que ele
sai. **As opções reais são duas: construir ou contornar.**

## Por que a recomendação é CONSTRUIR

**Não é risco de demonstração — é read-model de produto incompleto.** O telão de uma câmara fica
ligado por horas, muitas vezes numa TV sem supervisão: qualquer reinício, oscilação de rede ou aba
recuperada deixa a tribuna em branco **enquanto um vereador fala, na transmissão pública**. Quem entra
na transmissão no meio da sessão nunca vê quem está com a palavra. É justamente o painel que a Aposta 3
(confiança operacional) e o público decisor "presidente da Mesa" olham.

## O custo

Uma fatia **sem migration**, do tamanho da rota `composicao` (`22b8951`: 16 arquivos, +862/−56):

- `GET /sessoes/:id/tribuna` → orador atual (id, `tipo-fala`, fase, `iniciou-em`, marcos do cronômetro)
  + fila de inscritos ativos;
- **mesma authz das irmãs** — mesma Casa **E** (transmissão pública **OU** papel `secretario`);
- leitura direta de `fala_executada` (`encerrou_em IS NULL`), `fala_cronometro_evento` e
  `inscricao_oradores`, **sem aritmética nova** (mesma disciplina de `chamada-da-sessao*`);
- FE: buscar no carregamento inicial **e dentro do `rehidratar()` que já existe**.

## Se a decisão for contornar

Abrir o telão **antes** de qualquer fala começar e não recarregar. Serve à apresentação; o defeito
segue vivo para o primeiro cliente.
