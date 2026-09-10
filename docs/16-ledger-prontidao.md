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

---

# FASE 5 — as 7 jornadas restantes, caminhadas (07/09/2026, 22h)

J9 · J2 · J3 · J5 · J6 · J7 · J8. Todas no navegador, contra a stack viva e a semente real. **Nenhum
dos achados abaixo aparece na suíte** — backend 2028 e frontend 1116 estavam verdes no mesmo instante.

## Placar novo: 3 `MATA` · 3 `CONSTRANGE`

| # | Jornada | Tela | O que se vê | Causa (verificada) | Classe | Gravidade |
|---|---|---|---|---|---|---|
| **14** | J9 | `/expediente` | **"Nenhum registro no Protocolo Geral deste ano ainda."** no Livro do ano corrente | `legislativo.protocolo_geral` tem **102 linhas**, e **zero do ente da demo** — pertencem a **72 entes aleatórios**, resíduo de rodadas de teste. `GET /legislativo/protocolo-geral` devolve `{"itens":[]}`: a tela é honesta, o dado é que não existe. A semente nunca passa por `protocolar!` | **C — dado** | **MATA** |
| **15** | J2 | `/ficha-materia/:id` | **"Nenhum texto vigente registrado ainda para esta matéria."** | `legislativo.proposicao_texto_versao` = **0 linhas** para o ente, nas **8 partições**. **Nenhuma das 24 matérias tem corpo** — vale para toda ficha, não para uma | **C — dado** | **MATA** |
| **16** | J3 | `/vereador` | **"SEM SESSÃO AGORA · Nenhuma votação aberta"** e **"Nenhuma sessão agendada · Ainda não há próxima sessão publicada"** | **Falso nos dois.** No mesmo instante existe sessão **ABERTA** (`…0211`, com orador na tribuna há ~4h) e **agendada** para 14/09 — e `/paineis/mesa` mostra "Próxima sessão: agendada para 14/09/2026" corretamente. Duas telas do mesmo sistema se contradizendo | **A — defeito** | **MATA** |
| **17** | J3 | `/vereador` | **"Estado: em comissoes"**, **"Estado: arquivada"** — enum cru, sem acento | A ficha da matéria rende **"Em comissões"** certo. Mesma família de #9/#10 (já mortos), ressurgida noutra tela | **A — defeito** | **CONSTRANGE** |
| **18** | J3 | `/vereador` | **"Parecer em aguardando assinatura"** | Frase quebrada com o enum embutido cru no meio | **A — defeito** | **CONSTRANGE** |
| **19** | J7 | `/paineis/mesa` | **"A Casa está em dia com o TCE-CE."** sobre **0 conformes · 0 pendentes · 0 vencidas** | Afirma conformidade a partir de **zero dado**. Mesma classe do juiz fail-open. É a tela do público decisor **jurídico/administrativo** | **A — defeito** | **CONSTRANGE** |

## O que passou

- **J5 (a lei nasce) — íntegra.** `/pos-aprovacao` mostra a cadeia inteira (autógrafo nº 006/2026 →
  Executivo → sancionado), com rótulo **`GAP` honesto** sobre o prazo da LOM não informado. Sem defeito.
- **J7 (a Mesa) — rica e correta**, ao contrário do que o plano previa ("beco"): prazos que vencem,
  carga por estágio, 24 proposições nomeadas por estado, próxima sessão. O único senão é o #19.
- **J9/J2/J6 — os placeholders são HONESTOS.** "EM BREVE" dizendo o que falta e por quê, em vez de
  botão morto. `PASSA`.
- **J2 — autoria resolve por NOME** ("Fernanda Pinto"), não UUID.

## Não caminhado

**J8 (entrar de verdade):** `/entrar/:ente` renderiza ("Entrar em CM Fortaleza" → redireciona ao login
oficial). O fluxo completo **não foi percorrido** porque exige digitar credenciais — fora do que este
agente faz. Fica como a única jornada com verificação parcial.

---

# FASE 6 — os 3 `MATA` da Fase 5, mortos (07-08/09/2026, branch `conserta-3-mata`)

6 commits. **Provado na tela após reconstrução limpa** (`down -v` → `up --build` → `semear-tudo.sh`),
nunca contra dado remendado.

| # | Causa raiz (verificada) | Conserto | Prova na tela |
|---|---|---|---|
| **16** | **Não existia `GET /sessoes`.** Havia `POST /sessoes` e `GET /sessoes/:id`; a home era chamada com `sessoes=[]` e convertia "não sei" em "não há" | `c3df2e5` cria a rota (authz **por linha**, sessão secreta invisível); `d047d01` liga a tela e separa carregando / erro / de fato nenhuma | "SESSÃO EM ANDAMENTO · A sessão está acontecendo agora" + próxima em 14/SET |
| **14** | `protocolar!` de proposição **não** compõe `protocolo-geral/protocolar!` — só o fluxo de documento administrativo o faz | `19644ad` faz a semente inscrever as 24; `07444cd` corrige `sentido` p/ `interno` | Livro com 24 entradas `2026/00001`–`2026/00024`, gapless, "Interno" |
| **15** | `protocolar!` cria e promove versão de texto, mas só `(when-let [corpo (:texto p)])` — a semente nunca passava `:texto`. **Produto correto; semente incompleta** | `19644ad` passa o texto; `07444cd` tira a sintaxe markdown visível | Corpo da lei, Art. 1º–4º, específico da ementa, sem `##` |

## O que a caminhada PÓS-conserto achou — e é o padrão da noite

**Consertar abre porta.** Três vezes:

- **F2** — ligar o card de próxima sessão fez aparecer **"ordinaria"** cru. O card antes nunca aparecia
  (era sempre `null`), então o enum nunca chegava à tela. Família já morta duas vezes (#9, #10),
  ressurgida por porta nova. Corrigido em `2a3092c` reusando `formatarTipoSessao`.
- **#17 pego junto** — "Estado: em comissoes" → **"Em comissões"**, reusando `derivarTramitacao`. Sem
  segundo vocabulário de rótulos.
- **A1 (bloqueador da revisão)** — `proximaSessaoFutura` filtrava só por **data**, não por estado.
  `agendada → nao_realizada` é transição legal e **não apaga `agendada_para`**: a Mesa cancela por luto
  e a home anuncia a sessão cancelada. **O gênero exato do #16, nascido dentro do conserto do #16.**
  Corrigido em `130d4ef`.

## Cobertura que faltava e agora existe (`130d4ef`)

O #15 existiu por meses **com a suíte verde**. As três lacunas que permitiam a volta calada:
teste afirmando que **as 24 matérias têm texto** (uma 25ª sem texto reprova); teste da **inscrição no
Livro** (gapless + `sentido`); e o caso de **ente diferente** na listagem — o fake sempre devolvia
tenant casado, então dropar o check de isolamento passava em tudo.

## Carries registrados, NÃO consertados (decisão de desenho, não defeito)

- Teto de 500 da listagem é medido **antes** do filtro de authz: 480 públicas + 30 secretas = 422 para
  quem teria direito a 480 — negação por dado invisível.
- O mesmo teto quebra Casa com ~10 anos de acervo (~50-60 sessões/ano). O desenho certo é `?de`/`?ate`
  (a assiduidade já tem), com o teto como guard-rail.
- O vereador **não vê a própria sessão secreta**: a política só excetua `secretario`, então durante uma
  secreta a home diz "Sem sessão agora" a quem vota nela.
- `formato "markdown"` é gravado enquanto a ficha é **texto puro**. O #15 foi consertado no dado, não no
  descasamento.
- `outbox/drenar!` não é escopado por `ente-id` — fonte estrutural de flakiness da suíte cheia.
- **`[GAP]` de produto:** o Livro se anuncia "numerador único · **proposições** e documentos
  administrativos", mas no fluxo real protocolar uma proposição não a inscreve. Hoje só a semente
  inscreve. **Se o Livro é mesmo o numerador único da Casa, falta uma composição no `protocolar!`.**

---

# Fase 7 — TRILHA 1 (operação): a plataforma local roda sem falha

> Plano: `docs/superpowers/plans/2026-09-08-exploratorio-de-escrita.md`, Trilha 1.
> Eixo desta fase é **QUEBRA / FRÁGIL / COSMÉTICO / GAP**, não `MATA/CONSTRANGE/PASSA`.
> Branch `trilha-1-operacao`. Máquina: Mac de 8 GB, VM do OrbStack com **3.9 GiB**.

## O achado que domina a trilha: o Postgres cai por falta de memória da VM, não por defeito de código

Com a stack completa de pé (**incluindo o perfil `auth`**) e a sonda das 27 rotas rodando, o
`oplenario-postgres-1` **crashou duas vezes em três minutos**:

```
LOG:  server process (PID 822) exited with exit code 2
LOG:  terminating any other active server processes
LOG:  database system was not properly shut down; automatic recovery in progress
FATAL: the database system is not yet accepting connections
```

O `dmesg` da VM dá a causa, e ela não é do produto:

```
Huh VM_FAULT_OOM leaked out to the #PF handler. Retrying PF
```

Nenhum container foi OOM-killed (`OOMKilled=false`, sem limite de memória por serviço): quem esgotou
foi a **VM inteira**. Consumo no pico: frontend em modo dev **1.2–1.4 GiB**, keycloak 478 MiB,
app 481 MiB, mais o Chromium da sonda — sobre 3.9 GiB totais.

**Verificação do diagnóstico, não só da hipótese:** parar keycloak+mailpit devolveu ~500 MiB, e a
sonda inteira voltou a rodar com o mínimo disponível em **1.23 GiB** e **zero crash**. O dado
sobreviveu ao crash (redo do WAL, sem perda) — mas isso é o Postgres se defendendo, não a plataforma
funcionando.

| Classe | Item |
|---|---|
| **QUEBRA** | A plataforma local + o próprio harness de verificação não cabem juntos na VM de 3.9 GiB. É `QUEBRA` porque o modo de falha é o banco morrer no meio da operação, não uma tela feia. |
| — | **Decisão do Daouda, não conserto unilateral:** subir a memória da VM do OrbStack, e/ou servir o frontend em **modo produção** na verificação (o `next dev` é o maior consumidor isolado e ainda compila cada rota sob demanda — 27 rotas levaram ~12 min). |

## T1.1 — Sobe do zero e volta sozinha

- **Tempo medido** (com volumes preservados, build em cache): `up -d --build` **65s** + `semear-tudo.sh`
  **96s** = **2min41s**, sem intervenção manual, com as duas barreiras de projeção passando.
- **`down -v` não foi executado** — a ação é destrutiva e ficou pendente de autorização. Logo o
  critério "de máquina fria à Casa semeada" está **provado por reconstrução parcial**, não total, e a
  armadilha do lock `-1` do migratus (T1.5) segue **não exercida**.
- **FRÁGIL, consertado:** só o `app` tinha `restart: unless-stopped`. Os outros **seis** serviços não
  tinham política nenhuma — parar o Docker e religar deixava toda a infra `Exited` com o `app` sozinho
  em crash-loop contra um banco ausente. Todos ganharam `unless-stopped`; o `migrate` fica `"no"` de
  propósito (é one-shot). **A verificação empírica — parar e religar o Docker — não foi feita**, pela
  mesma razão de a ação ser disruptiva.

## T1.2 — Zero erro em log durante operação normal

**`Apparent connection leak detected` não era vazamento — era falso-positivo estrutural.** Causa raiz
lida na fonte, não inferida:

- `outbox_relay.clj:30` abre `lock-conn` e a segura pela vida inteira do relay — o advisory lock é
  **session-level**, é assim que a liderança se sustenta. O docstring do próprio worker já dizia isso.
- `datasource.clj:27` liga `setLeakDetectionThreshold 30000` **no mesmo pool**.
- Resultado: aviso com stack trace **garantido em todo boot**, 30s depois de subir, apontando para uma
  conexão que está exatamente onde deveria estar.

Conserto: pool **dedicado** `:ds-lock` (1 conexão, detector desligado) para a liderança; o pool de
trabalho mantém o detector ligado — **o conserto não pode ser desligar o detector**. É o pool que o
próprio docstring do relay já previa.

**Prova:** app rebuildado, 90s de carga (`/saude`, `/portal/.../materias`, portal da casa) →
**0 ocorrências de `Apparent connection leak detected`, 0 `ERROR`, 0 `Exception`** no log do app.
Varredura dos outros serviços na janela de operação: `frontend` 0, `valkey` 0, `minio` 0, `postgres`
0 erro de query (os `ERROR: relation ... does not exist` do log são do bootstrap de 02:34, antes das
migrations).

**Teste que impede a volta calada:** `outbox_relay_test/a-lideranca-nao-prende-conexao-do-pool-principal`
— afirma que o pool de lock existe, é distinto, tem o detector **desligado**, que o pool de trabalho
tem o detector **ligado**, e que a conexão de liderança sai do pool de lock. **Provado com defeito
plantado:** revertendo `ds-lock` → `ds`, o teste reprova nomeando o defeito (`esperado 1, obtido 0`).

## T1.3 — Suíte 100% verde

Os dois carries viraram escopo e foram consertados na causa:

| # | Defeito | Causa raiz | Conserto |
|---|---|---|---|
| 1 | `demo.casa-test/semear-produz-uma-unica-casa` reprova sempre | O teste **redigitou metade da regra** da produção: leu só `System/getenv "DEMO_ARTIFACTS_DIR"`, enquanto a produção é `getenv OU ".artifacts"`. Nenhum comando de suíte seta a variável — **nem o do CI** — então `(io/file nil "…")` estourava NPE | `casa/diretorio-de-artefatos` vira **pública** e é a única fonte da regra; o teste pergunta em vez de reescrever |
| 2 | `notificacao-autor-test` floca em run cheio, passa isolado | `(is (= 2 (drenar! …)))` mede efeito **global**: `outbox/drenar!` não é escopado por `ente-id` e drena o outbox inteiro do banco compartilhado | A asserção passa a ser **escopada ao ente** (2 processados, 0 pendentes) — a pergunta que o critério realmente faz |

**Resultado: 2051 testes, 5528 asserções, 0 falhas** (dois runs cheios consecutivos).

## T1.4 — As armadilhas conhecidas

| # | Armadilha | Veredicto |
|---|---|---|
| 1 | JVM morrendo com SIGBUS (perf-data mmapeado) | **Não reproduz** — 0 ocorrências; a mitigação `-XX:-UsePerfData` está viva no `JAVA_TOOL_OPTIONS` do container |
| 2 | `.next` obsoleto servindo 404 em rota que existe | **REPRODUZ.** Ver a seção própria abaixo — a primeira medição desta sessão disse "não reproduz" e **estava errada** |
| 3 | Token sem `papeis` navegando como sem papel nenhum | **Não reproduz, e o backend é mais forte que "corrigido":** a mesma identidade de secretaria autoriza com `papeis`, **sem** `papeis` e com `papeis: []` — todos 200, os papéis vêm do banco. Identidade de cidadão com claim **mentindo** `papeis:["secretario"]` → **401**, fail-closed. No FE, a tela renderiza igual com e sem o claim |
| 4 | Restart do `app` apagando a tribuna ao vivo | **Não reproduz** — `docker restart oplenario-app-1` com fala aberta, e `GET /sessoes/:id/tribuna` volta com o mesmo `fala-id`, o mesmo orador e a fase `grande_expediente`. O read-model da tribuna (07/09) sustenta |

## T1.5 — Os workers estão vivos

- **Relay: provado.** `semear-tudo.sh` tem duas barreiras de projeção que **falham alto** e ambas
  passaram: as matérias e o read-model do perfil do vereador apareceram no portal público sem
  intervenção — ou seja, evento emitido → projetado.
- **Auto-cura provada por acidente:** durante o crash do Postgres o relay perdeu a conexão de
  liderança, logou `relay: falha ao drenar o outbox — retenta no proximo tick` e **voltou a drenar
  sozinho** quando o banco reabriu. O caminho de reconexão do `loop-relay` existe e funciona.
- **Lock `-1` do migratus: NÃO exercido** — depende do `down -v`.

## Armadilha #2: reproduz, e a primeira medição desta sessão errou

**Como errei.** Logo após `up -d --build frontend` medi 7 rotas, todas 200, e escrevi "não reproduz".
As 7 eram rotas de servidor; a família que quebra é outra. A segunda rodada da sonda pegou:

- `/portal/casa/<ente>/materias/<id>` → **404 no frontend**, com o **backend servindo a mesma matéria
  em 200** e o `page.tsx` presente em disco e visível dentro do container.
- `docker restart oplenario-frontend-1` → **200**. É o remédio registrado, e ele funciona.

**Mecanismo, agora estrutural:** `.next` é um **volume anônimo** (`docker inspect` mostra
`volume:843ecc…→/app/.next`). O `next dev` persiste cache de build ali ("Finished writing to
filesystem cache"); o container morto no meio dessa escrita deixa estado parcial, e o container
seguinte confia nele. `up -d --build` recria o container e **reata o mesmo volume**.

**Tentei consertar e piorei — e revertí.** Fiz o stage `dev` do Dockerfile limpar `.next` no start do
container. Resultado medido: **a subárvore dinâmica inteira de `/portal/casa/[ente]/*` passou a 404**,
inclusive `vereadores/<id>`, que estava passando. Revertido o Dockerfile, as duas rotas voltaram a 200
na mesma medição. **Registro isso como erro meu, não como propriedade do sistema:** limpar o cache no
start não é o conserto, e a causa de por que ele quebra o roteamento não foi investigada.

**Carry:** a armadilha #2 continua **viva e sem conserto**. O que existe é o procedimento decorado
(`docker restart oplenario-frontend-1`), que é exatamente o que o plano dizia não aceitar.

## O que a revisão adversarial (`ecc:clojure-reviewer`) reprovou — e o que virou conserto

A revisão **bloqueou** o commit inicial. Nove achados; o núcleo do conserto (pool de lock dedicado)
foi confirmado correto, e os problemas estavam todos nas bordas:

| # | Achado | Classe | Desfecho |
|---|---|---|---|
| A1 | O teste novo subia um relay **real** com registro vazio contra o banco compartilhado. Ganhando o lock 911, `drenar!` marcaria `processed_at` **sem consumidor** — descartando em silêncio todo evento pendente. E o procedimento canônico de suíte manda parar o `app` antes do run, o que faz do teste **o líder mais provável** | MAIOR | **Consertado:** o teste toma o lock 911 numa conexão crua (fora dos dois pools) **antes** de subir o relay. A asserção não muda; o efeito colateral desaparece |
| A2 | `restart: unless-stopped` faz a stack voltar **pulando o `migrate`** — política de restart é do dockerd, e o `depends_on` não é honrado no religar do daemon | MAIOR | **Conserto tentado e reprovado pela realidade:** o `serve` checando pendências morre com `permission denied for schema public` — o papel do app **não tem `USAGE` em `public`**. Fechá-lo exige conceder leitura do ledger de migrations ao papel do app: decisão de segurança do Daouda. **Documentado no compose como carry** |
| A3 | Falha parcial no `start` órfã o pool de trabalho (Hikari é ansioso; o segundo pool pode estourar depois do primeiro ter sucedido) | MENOR | **Consertado:** `try/catch` fecha o primeiro antes de relançar |
| A4 | Guard `(if ds …)` deixou de cobrir o invariante; `:ds-lock` nil vira laço infinito logando **"a conexão de liderança caiu"** — causa falsa | MENOR | **Consertado:** guard sobre os dois campos + o relay falha no `start` nomeando o defeito. Teste novo cobre |
| A5 | A docstring **nova** dizia "dimensionar contra a concorrência normal apenas" — **invertendo a verdade**: o orçamento virou `pool-max-size + 1` por processo | MENOR | **Consertado:** a docstring passa a dizer `(pool-max-size + 1) × réplicas` |
| A6 | `liberar-lider!` **não tem chamador em `src/`**: a conexão voltava ao pool ainda segurando o advisory lock; o contador re-entrante só crescia | MENOR (pré-existente, agravado pelo pool de 1) | **Consertado:** `finally` libera a liderança |
| A7 | `Thread/sleep 1500` fixo (o teste vizinho documenta explicitamente que sleep fixo é flaky) e **faltava a asserção que o nome do teste promete** | MENOR | **Consertado:** poll com prazo de 15s + `(is (zero? (ativas (:ds d))))` |
| A8 | Type hint de retorno no nome do var (clj-kondo reprova; o mesmo commit acertou a forma em `casa.clj`) | MENOR | **Consertado:** hint no vetor de argumentos |
| A9 | `unless-stopped` aplicado a 3 serviços **sem healthcheck** — contradizendo o comentário do próprio arquivo ("sem healthcheck, `unless-stopped` esconde crash-loop") | MENOR | **Consertado:** healthcheck em `minio` e `frontend` (ambos verificados subindo a `healthy`), e `keycloak`/`mailpit` **perderam** a política — política de restart ignora `profiles`, e ~500 MiB voltando sozinhos numa VM de 3.9 GiB empurra o OOM que domina esta trilha |

Detalhe que só apareceu ao verificar: o healthcheck do frontend com `localhost` fica em
`Connection refused` para sempre — o `next dev` faz bind em `0.0.0.0` (IPv4) e o `wget` do busybox
tenta `::1` primeiro. Com `127.0.0.1`, saudável em 20s.

## Sobre a própria sonda (FRÁGIL da ferramenta, não do produto)

- Roda em **série**, 30s de timeout por rota, e **só imprime no fim**: com a stack degradada, gastou
  **921 segundos imprimindo zero bytes**. "Trabalhando", "travada" e "vai reprovar tudo" são
  indistinguíveis pelo lado de fora — o progresso real só ficou visível no log do frontend.
- **Não faz pre-flight de saúde**: com o alvo fora do ar, paga 27×30s para chegar à conclusão que uma
  requisição a `/saude` daria em 1 segundo.
- Rodada válida (stack sadia): **26/27**. A única reprovada é `/sessoes/:id/plenario`, com
  `net::ERR_NETWORK_CHANGED` no console — é a rota que abre **SSE**, e o erro tem cara de interrupção
  de transporte, não de defeito de tela. **Fica aberto**: precisa de segunda medição para separar
  flake de defeito.

## Dois achados de infra que a T1.1 destapou depois do conserto

- **FRÁGIL — o estado local mora em volumes ANÔNIMOS.** O `docker-compose.yml` não tem seção
  `volumes:` de topo: `postgres` (`/var/lib/postgresql/data`) e `minio` (`/data`) persistem em volumes
  sem nome. Eles sobrevivem a um `up` que recria o container (o compose reusa o volume anônimo), mas
  morrem em `down -v` ou `--renew-anon-volumes`, não são descobríveis por nome e não são backupáveis.
  Para "a plataforma local roda sem falha", o estado inteiro pendurado num identificador que só existe
  no histórico do container é frágil. **Conserto = volumes nomeados, e ele implica reconstruir a Casa —
  decisão do Daouda, não ação unilateral.**
- **Política escrita ≠ política aplicada.** Depois de editar o compose, `valkey` e `minio` continuavam
  com `RestartPolicy=no` em runtime — o arquivo estava certo e a máquina não. Só um `up -d` que
  **recria** o container aplica a mudança. Verificado serviço a serviço com
  `docker inspect --format '{{.HostConfig.RestartPolicy.Name}}'`; os cinco serviços em execução hoje
  estão em `unless-stopped`. **Editar o compose não é a prova; o `inspect` é.**

## Carries desta fase

- **`down -v` + reinício do Docker pendentes de autorização** — sem eles, T1.1 e o lock do migratus
  (T1.5) ficam provados pela metade.
- **Memória da VM é decisão de produto/infra do Daouda** (subir a VM, e/ou frontend em modo produção
  na verificação).
- **`/sessoes/:id/plenario` com `ERR_NETWORK_CHANGED`: era flake.** Segunda medição passou limpo.
- **Armadilha #2 sem conserto** — só o procedimento decorado (`docker restart oplenario-frontend-1`).
  A tentativa de limpar `.next` no start do container quebrou o roteamento dinâmico e foi revertida.
- **A2: o `serve` não consegue checar migrations pendentes** — o papel do app não tem `USAGE` em
  `public`. Fechar isso exige conceder leitura do ledger de migrations: decisão de segurança.
- **`outbox/drenar!` continua sem escopo de `ente-id`.** Consertei a *asserção* que dependia disso; a
  função segue global. Para a suíte isso basta; para uma segunda réplica, não.

## Armadilha #2 — investigação da causa raiz (08/09, sessão seguinte)

**O mecanismo registrado acima ("`.next` é volume anônimo, o container morto no meio da escrita deixa
estado parcial, e o container seguinte confia nele") NÃO se sustenta. Refutado por experimento.**

### O que foi medido, e o que cada medição elimina

| # | Experimento | Resultado | O que elimina |
|---|---|---|---|
| 1 | Requisitar rota **existente e fora do manifesto** (`/entrar/[ente]`) | **200 em 4s**, e a rota **entra** no `app-paths-manifest.json` | Elimina "compilação sob demanda devolve 404". Ela devolve 200 |
| 2 | `up -d --build frontend` e comparar o volume de `/app/.next` | **Mesmo volume anônimo** (`843ecc…`) reatado | Confirma a premissa do volume — mas veja o #3 |
| 3 | Ler o manifesto **logo após** recriar, antes de qualquer request | Manifesto **zerado**: só `{"/page": …}` (tinha 6 rotas antes) | **Elimina a hipótese do cache obsoleto.** O dev server **reescreve** o manifesto no start; ele não "confia" no anterior |
| 4 | Bateria estratificada logo após recriar (raiz · estática · dinâmica · dinâmica **aninhada**) | **6/6 em 200**, ≤1s cada | A armadilha **não reproduz** por recriação de container com volume quente |
| 5 | Varredura de 13 rotas internas sob carga, com corte automático a <400 MiB livres | **Zero 404, zero erro**; frontend 613→870 MiB; livre estável ~2 GiB | Não reproduz sob carga de compilação, **sem keycloak** |

**Estado da árvore durante toda a investigação:** keycloak e mailpit **fora** — ou seja, ~478 MiB a
menos do que na medição em que a armadilha apareceu.

### A hipótese que sobrevive, e por que ela muda a decisão

A #2 tem cara de **sintoma da pressão de memória da VM**, não de defeito independente:

- A falha original ocorreu com **`--profile auth` de pé** (+478 MiB) **e a sonda rodando** (Chromium),
  que é exatamente a condição que **crashou o Postgres duas vezes** por `VM_FAULT_OOM` da VM.
- Explica o que o cache não explica: **por que limpar `.next` piorou.** Cache frio força recompilar
  tudo — mais memória e mais CPU, exatamente na direção do teto.
- Explica por que `docker restart oplenario-frontend-1` "conserta": devolve a memória acumulada do
  processo `next dev` (613 MiB fresco × 1,2–1,4 GiB no pico).

**Consequência prática:** se confirmada, a #2 deixa de ser um item aberto próprio e passa a depender
da **decisão 1 do Daouda** (memória da VM / frontend em modo produção na verificação). Duas pendências
viram uma.

**Não confirmada porque a confirmação exige induzir o OOM** — subir keycloak + rodar a sonda, que é a
condição que já derrubou o banco. É ação disruptiva e fica **pendente de autorização**, junto com o
`down -v`.

### O buraco de instrumentação que a investigação original deixou

No momento do 404, ninguém capturou **o log do `next dev`**. É o artefato mais informativo e o mais
barato: um `docker logs oplenario-frontend-1` na hora diria se houve falha de compilação (sustenta a
hipótese de memória) ou um 404 limpo do roteador (derruba). **Na próxima reprodução, capturar o log do
frontend ANTES de aplicar o `docker restart` que apaga a evidência.**

### Nota de método

A medição original errou ao concluir "não reproduz" a partir de 7 rotas **da mesma família** (todas de
servidor). A bateria acima é **estratificada pelo mecanismo** — raiz, estática, dinâmica e dinâmica
aninhada — porque é a distinção que o defeito faz. Amostra escolhida por conveniência mede a
conveniência.

# Fase 8 — TRILHA 2 grupo A: as 17 rotas de condução de sessão, por HTTP

> Plano: `docs/superpowers/plans/2026-09-08-exploratorio-de-escrita.md`, Trilha 2, grupo A.
> Eixo desta fase é **QUEBRA / FRÁGIL / COSMÉTICO / GAP**, não `MATA/CONSTRANGE/PASSA`.
> Branch `trilha-2-escritas`. Script: `e2e/.sonda/t2-grupo-a.sh`. Relatório completo em
> `/tmp/t2-grupo-a.md` (tabela rota×caminho feliz×caminhos de erro×veredicto).

**Método:** bash+curl+psql no host (o alvo é o backend por HTTP puro — nenhum app-code do projeto roda
fora de container, então o mandato Docker não se aplica a `curl`/`docker exec`, mesma leitura que
`e2e/semear.sh` já faz). Sessão `…0212` (agendada→aberta→encerrada, via de mão única — não é
re-rodável sem `./demo/semear-tudo.sh`). Sessão `…0211` (demonstrativa): só lida, nunca escrita.

**Placar:** 17/17 rotas exercidas, caminho feliz + caminho de erro em cada uma · 96 checagens OK · **5
QUEBRA** · 0 FRÁGIL · 0 COSMÉTICO · 1 GAP (reentrância tolerada, não é defeito — ver abaixo). Isolamento
multi-tenant testado com identidade REAL de outro ente em 6 rotas: **0 vazamentos**.

## Os 5 achados QUEBRA

1. **`POST /votacoes/:id/votos`, voto duplicado do mesmo vereador → 500, não 409.**
   `legislativo/diplomat/http/in.clj` `voto-handler` (rota da Mesa) não tem `try/catch`; o UNIQUE
   `votos_ente_id_votacao_id_vereador_id_key` sobe cru até o interceptor global. O irmão self-service
   `meu-voto-handler` TEM esse tratamento (`:conflito/voto-duplicado` → 409) — só a rota da Mesa não.
   Confirmado por `docker logs`: `PSQLException ... duplicate key value violates unique constraint`.
2. **CAS obrigatório em 7 rotas (`transicao` · pauta `PATCH`/`DELETE` · `votacoes/:id/encerramento` ·
   `inscricoes/:id/desistir` · `falas/:id/encerrar` · `gravacao/:id/vincular`), e nenhum GET nem
   recibo de criação jamais devolve `lock-version`.** As 5 saídas envolvidas (`sessoes.adapters.out.
   {sessao,pauta,tribuna,gravacao}` + `legislativo.adapters.out.votacao`) repetem a mesma frase de
   docstring ("filtra lock-version, interno"). Um cliente real (FE incluído) não tem como montar a 2ª
   chamada de qualquer fluxo de mais de um passo sem ler o Postgres direto — a sonda teve de fazer
   isso em 7 pontos só para conseguir avançar. Achado estrutural, não de uma rota isolada.
3. **`POST /votacoes/:id/encerramento`, repetição (já encerrada) → 400, não 409.**
   `legislativo/controllers.clj` `encerrar-votacao` tem guard explícito que mapeia "estado terminal"
   para `:validacao/invalido` → 400 — deliberado (evita o 500 do db), mas semanticamente errado (400 =
   "conserte seu pedido"; 409 = "seu pedido era válido, o recurso mudou") e inconsistente com os
   outros 5 conflitos de "já terminal" deste MESMO grupo de 17 rotas, todos 409.
4. **`POST /sessoes/:id/pauta/itens` aceita escrita numa sessão JÁ ENCERRADA** (201 confirmado ao
   vivo). Nenhum controller de pauta/tribuna/incidente/decisão-mesa checa `estado` da sessão — só
   `pode-ver-sessao?` (mesma Casa).
5. **`POST /sessoes/:id/votacoes` aceita abrir votação numa sessão JÁ ENCERRADA** (201 confirmado ao
   vivo). `legislativo.logic/pode-dirigir-votacao?` também só checa mesma Casa. Por leitura de fonte,
   o MESMO gate vale para inscrições/falas/decisões-mesa/incidentes (não testadas ao vivo pós-fecho,
   mas o código é idêntico).

## O que funcionou (não são achados — é o que prova que o resto está são)

Anti confused-deputy (item de pauta, fala, decisão-mesa de outra sessão → 404, 3/3) · isolamento
multi-tenant (0/6 vazamentos, com identidade REAL de outro ente, não forjada) · CAS correto em 5 das 6
rotas que o usam (só a votação erra o código, achado #3) · injeção de autor no servidor (nunca do
corpo) · validação de borda (enum, coerência de campo, FK-por-tipo) sempre 400, nunca 500 do banco.

## Gotcha de método desta fase

Primeira corrida truncada por `bash script.sh | tee log | head -100` — `head` fecha o pipe, `SIGPIPE`
mata o script NO MEIO de uma escrita (sessão ficou em `aberta`, roteiro incompleto). A sonda sobreviveu:
rodada de novo, detectou pelo `GET` inicial que a sessão não estava mais `agendada` e seguiu (GAP, não
crash). Nunca pipe um script que muta estado através de `head`/`tail`; redirecionar para arquivo e ler
depois.

## Terreno confirmado são (checado antes de acusar, per protocolo)

`docker ps` 5/5 `Up (healthy)` do início ao fim; único par de `ERROR` no log do `app` é exatamente o
achado #1 (repetido de propósito); zero `Apparent connection leak`; memória da VM com folga larga
(`app` 523 MiB, `frontend` 931 MiB, de um teto de 3,9 GiB).

# Fase 9 — os 5 `QUEBRA` da Fase 8, consertados

Dois commits: `922cdf4` (A1/A2/A3) e `9051787` (o estrutural). **Cada conserto verificado por mim, no
controlador, contra a stack viva — não pela alegação do implementador.**

| # | Defeito | Conserto | Verificação independente |
|---|---|---|---|
| **4/5** | Sessão **encerrada** aceitava escrita (201) | `exigir-sessao-aberta!` em `sessoes/controllers.clj`, **11 call-sites**, reusando `estados-sessao-fechada` (não um conjunto novo). O `legislativo` não pode importar `sessoes.logic` (ADR-0001), então recebe `sessao-fechada?` injetado por `rotas.clj` — mesma mecânica de `consultar-sessao` | `POST /sessoes/<encerrada>/votacoes` → **409** `{"erro":"sessao ja fechada; escrita de votacao bloqueada"}` |
| **1** | Voto duplicado da Mesa → **500** com `PSQLException` crua | Espelhado o `try/catch` de `23505` que o irmão self-service `registrar-meu-voto!` já tinha | 500 → **409**, sem ERROR no log |
| **3** | Encerrar votação terminal → **400** | `:validacao/invalido` → `:conflito/votacao-terminal` → **409**, consistente com os outros 5 conflitos "já terminal" do grupo | 400 → **409** |
| **2** | CAS exigido em 7 rotas e `lock-version` **nunca devolvido** | Exposto nas 5 saídas, com fonte identificada para **cada uma** das 7 rotas; contrato TS regenerado (+5 interfaces) | `GET /sessoes/:id`, `/pauta` e `/tribuna` passam a trazer `lock-version`; contrato com 7 campos |

## Dois achados de método que valem mais que os consertos

**O `lock-version` não era decisão deliberada a reverter — era a própria regra do time aplicada pela
metade.** O ns `sessoes.wire.out` esconde tokens de mecânica interna por default, **e já documentava a
exceção certa para este exato caso** (`JustificativaAbertaOut`: "aqui o token de CAS não é interno, é
PARTE DO PROTOCOLO"). A exceção nunca foi estendida às outras 7. Investigar a intenção antes de expor
transformou "reverter uma decisão" em "terminar de aplicar uma regra" — decisões opostas sobre o mesmo
diff.

**Uma otimização recusada por evidência.** O implementador considerou mapear `:conflito/*` → 409
genericamente no interceptor global (menos código) e recuou: um teste existente
(`tipo-fora-do-namespace-limite-continua-500`) **prova** que esta base recusa isso de propósito. Ler a
intenção antes de generalizar.

## Custo colateral desta fase (registrado, não escondido)

A verificação ao vivo sujou dado de demonstração: uma **votação órfã** na sessão encerrada `…0210`
(criada por mim ao provar o defeito), a sessão `…0212` percorrida até `encerrada` (era a cobaia), e a
**votação que ficava aberta na `…0211`** para o telão — esta última por omissão minha: a restrição
"não escreva na `0211`" estava no primeiro despacho e não foi carregada para o segundo. Tudo
reconstruível com `./demo/semear-tudo.sh`. A `…0211` segue `aberta`.

## Aberto

- **Pergunta de regimento (Daouda):** o portão cobre `encerrada`/`nao_realizada`/`arquivada`. Falta
  decidir se **votação e tribuna** também devem ser bloqueadas em `agendada` e `suspensa` — montar
  pauta com a sessão agendada é legítimo, abrir votação talvez não. Não foi decidido por engenharia.
- **Gap pré-existente:** `AberturaOut` não está no manifesto do codegen do `legislativo`, então o
  `lock-version` da abertura de votação não chega ao contrato TS. Fora do escopo desta fase.
- `demo.sessoes-test` falha porque a sonda da Fase 8 mutou a sessão `…0212`. É poluição de dado da
  própria varredura, não regressão — some com `semear-tudo.sh`.

---

# Fase 10 — TRILHA 2 grupo B: as 25 escritas restantes, por HTTP

> Plano: `docs/superpowers/plans/2026-09-08-exploratorio-de-escrita.md`, Trilha 2, grupos B–G.
> Branch `trilha-2-escritas`. Script: `e2e/.sonda/t2-grupo-b.sh`.
> Commits: `6eaa28c` (contador) · `a8b9bbc` (apreciação de veto) · `3ad57b0` (a sonda).

**Placar final:** 25/25 rotas exercidas, caminho feliz + caminhos de erro em cada uma · **157
checagens OK · 0 QUEBRA · 0 FRÁGIL · 7 GAP declarados**. Duas corridas consecutivas com placar
idêntico. Isolamento multi-tenant testado com identidade REAL de outro ente em 8 rotas: **0
vazamentos**. Suíte do backend: 2085 testes, 5588 asserções, 3 falhas — todas em `demo.*` por
poluição de dado, nenhuma regressão (prova abaixo).

**Como o placar chegou aqui:** 58 QUEBRA na 1ª corrida → 16 → 2 → 0. Cada queda foi destravada por um
conserto real ou por uma acusação retirada com evidência. As 4 corridas vermelhas são a prova de que
este gate reprova; nenhuma delas foi ajustada para ficar verde.

## O achado que domina a fase: a Casa da demo estava em 500 permanente

`POST /portal/esic/pedidos`, `/portal/lgpd/solicitacoes` e `/portal/ouvidoria/manifestacoes`
devolviam **500 para qualquer submissão de cidadão**. Não era flake: era determinístico e
irreversível.

**Mecanismo, verificado na fonte e no banco.** O contador gapless (`shared.sequencial`) e as linhas
numeradas nas tabelas de módulo são um **par de invariante**.
`test/integration/oplenario/kernel/sequencial_test.clj:22` truncava `shared.sequencial`
**globalmente** — sem escopo de tenant, e sem truncar as tabelas numeradas junto. Rodar a suíte
contra um banco com dado semeado apagava o contador de **todos** os entes e deixava as linhas para
trás. `proximo!` então volta a 1, colide na UNIQUE `(ente, ano, sequencial)`, e **a colisão aborta a
transação inteira — revertendo o próprio incremento do contador**. O contador nunca ultrapassa a
colisão.

**Extensão real, medida:** a Casa da demo tinha **17 escopos com linhas numeradas** (até
`projeto_lei:2026`=15 e `protocolo_geral:2026`=24) e **um único contador vivo**. Criar proposição,
protocolar documento, gerar autógrafo ou abrir qualquer pedido de cidadão: 500. **Numa demonstração,
isso mataria M5 e M6 ao vivo.**

**Por que o grupo A não pegou:** conduzir sessão não numera nada. Votação, pauta, fala e incidente
não passam por `shared.sequencial`. A varredura de 17 rotas passou ao lado do defeito.

**E `./demo/semear-tudo.sh` não reparava.** A semente é idempotente **por pular** (`ja-semeada?` →
relê), então nunca reprotocola e nunca reconstrói o contador. O script terminava com **exit 0** e a
Casa continuava quebrada. A memória do projeto afirmava que ele "resolve tudo" — não resolvia, e essa
afirmação foi corrigida.

**Conserto (`6eaa28c`), em três peças:**

| Peça | O quê |
|---|---|
| Raiz | O TRUNCATE sai do fixture (os testes já isolam por `random-uuid`) e vira **gate de CI**: `kernel/sequencial-lint-test` reprova se qualquer teste truncar de novo. |
| Reparo | `kernel/sequencial/reconciliar!` levanta o contador até um piso explícito (`GREATEST` — nunca abaixa). O kernel não conhece módulo: quem lê o piso é o chamador. |
| Operação | `demo/reconciliar_contadores.clj` + 5ª etapa em `semear-tudo!`, que reconcilia e **imprime** o que levantou. Aplicado ao banco vivo: 17 escopos, e as 16 rotas de participação foram de 500 a verde. |

## O 2º achado: a apreciação de veto devolvia 500 em todos os caminhos de erro

`POST /legislativo/tramitacoes-executivas/:id/apreciacao` (a única rota do grupo G):

| Caminho | Era | Ficou |
|---|---|---|
| Tramitação não está `vetado` | **500** | **409** |
| `lock-version` divergente | **500** | **409** |
| `veto-votacao-id` inexistente | **500** cru de FK | **400** com o campo nomeado |

Causa: `db/tramitacao_executiva` lançava `ex-info` **sem `:tipo`** (o interceptor global cai no
`:else`), e a violação de FK subia crua. Conserto (`a8b9bbc`) estende o que a base já faz: tag no
`db/`, tradução de 23503 no Repo-Component (mesmo predicado de `cadastros/ligar-identidade!`), e
tradução das tags no diplomat (como `resposta-conflito-sessao-fechada` do grupo A). A rota irmã
`/legislativo/autografos/:id/resposta` foi corrigida junto — não está nas 25, mas compartilha a mesma
dupla de funções e o mesmo guard; declarado, não silencioso.

**E a docstring do schema mentia.** `wire/in/pos_aprovacao` afirmava que `veto-votacao-id` era
"forward-ref (sem FK declarativa no domínio)". O banco desmente: a FK
`(ente_id, veto_votacao_id) → legislativo.votacoes` existe desde a migration 0022. Corrigida, e
ancorada por teste que reprova se a FK sair.

## Dois testes que estavam mentindo (o mesmo mecanismo, duas formas)

1. **`registrar-resposta-conflito-lock-version-400` fabricava a exceção.** O fake lançava
   `{:tipo :validacao/invalido}` — formato que a produção **nunca produziu** (lançava sem tipo
   nenhum). O teste ficava verde afirmando 400 enquanto a borda real devolvia 500. É o mesmo
   mecanismo já registrado duas vezes neste projeto: **fixture que inventa a forma mantém verde um
   caminho morto.** Agora o fake usa a tag real, e a tag está ancorada na fonte.
2. **Os guards de `db` usavam `(is (thrown? Exception ...))`.** Isso passa igual para uma `ex-info`
   sem `:tipo` — exatamente a que vira 500. Uma asserção que não distingue "lança certo" de "lança
   virando erro interno" não é cobertura. Trocadas por asserções sobre o `:tipo` da `ex-data`.

## Quatro defeitos do próprio instrumento (a sonda mediu a si mesma antes de acusar)

1. **Cascata.** Um fixture que não nasceu gerou **40 achados derivados** — todos "esperava 200, veio
   400 Ambiguous URI empty segment", porque o id vazio montava `/esic/pedidos//resposta`. Quarenta
   linhas escondendo o único defeito real. `exigir` agora registra **um** achado e pula o bloco.
2. **Não-re-rodabilidade.** CPF fixo reusava a mesma identidade (já ligada) e o `PATCH` dava 409
   legítimo, lido como defeito do produto. CPF passou a ser gerado com dígito verificador calculado.
3. **A sonda acusando o próprio fixture.** O check de fidelidade da semente rodava **depois** de a
   sonda licenciar um mandato por psql, e culpava a semente por isso. Movido para antes.
4. **`veto-votacao-id` aleatório.** Há FK real — mandar UUID inventado media a sonda, não o produto.

## Duas acusações RETIRADAS depois de ler a fonte

O método do grupo A ("investigar a intenção antes de reverter") valeu de novo, e nos dois casos a
fonte venceu:

- **`/identidade/acessos` → 500 com IdP fora não é descuido.** O handler documenta "Erro de infra do
  KC PROPAGA -> 500 (nunca 401)" e existe teste `conceder-acesso-keycloak-fora-do-ar-500` que o
  exige. O "banco antes do Keycloak" também é deliberado e explicado (fail-closed: vínculo sem
  credencial não deixa ninguém entrar, e repetir conserta). Reclassificado para GAP — carry F1.4.
- **O "cidadão da semente sem vínculo" tem decisão explícita** em `demo/casa.clj:106`, para *leitura*
  pública. O que a decisão não cobre é a semente ter estendido a mesma identidade a uma *escrita*
  autenticada (3 pedidos e-SIC). Vira decisão de narrativa da demo, não defeito de rota.

## Afirmação de cobertura (uma corrida ficou verde cobrindo 24 de 25)

"Zero achados" e "não rodou" tinham a mesma saída. A cobertura virou **asserção**: se uma rota não
teve nenhuma checagem OK, o gate cai. Provado subindo o esperado para 26 e vendo reprovar.

## As 3 falhas de suíte, provadas como dado e não como regressão

| Teste | Por quê | Evidência |
|---|---|---|
| `demo.sessoes-test` (×2) | Sessão `…0212` = `encerrada` e zero votações abertas na `…0211` | Poluição da **Fase 8**, anterior a esta sessão |
| `demo.participacao-test` | Fila de moderação com 10 pendentes em vez de 2 | Comentários rotulados `Sonda T2B —` no banco |

**E não dá para limpar.** O banco de participação é **append-only por desenho**
(`shared.imut_append_only` barra DELETE em `moderacao_comentario`/`denuncia_comentario`), e a FK
impede apagar o comentário. Tentei e o banco recusou — corretamente.

## O conflito estrutural que isso destapa (decisão do Daouda)

Os testes `demo.*` afirmam o **conteúdo exato** de uma Casa **compartilhada e append-only**. Qualquer
escrita exploratória — que é o objetivo inteiro da T2 e da T3 — a suja de forma **permanente**, e
`semear-tudo.sh` não restaura (idempotência por pular + append-only). O único reset é `down -v`.

Ou seja: **"suíte 100% verde" (T1.3) e "exercitar as 66 escritas" (T2/T3) são hoje mutuamente
exclusivos nesta máquina.** O plano já dizia que a sonda deveria rodar "numa Casa própria"; isso
deixou de ser preferência e virou pré-requisito.

Três saídas, e a escolha não é de engenharia:
1. **Casa própria para sonda** (um `ente` de varredura, separado do da demo) — mais trabalho, resolve de vez.
2. **Testes `demo.*` param de afirmar contagem exata** e passam a afirmar invariantes ("existe ao menos um pendente") — mais barato, perde poder de detecção.
3. **`down -v` antes de cada suíte cheia** — zero código, mas depende de autorizar o `down -v` (ainda pendente da T1) e custa o tempo de reconstrução.

## Aberto

- **A escolha das 3 saídas acima.** É o que trava o critério "dois runs cheios 100% verdes" do plano.
- **`veto-votacao-id` é carimbado sem checar se a votação é DA apreciação daquele veto.** A FK garante
  que a votação existe e é da Casa; nada garante que é a votação certa. Não é defeito desta frente —
  é `[GAP]` de regra de negócio, e a rota nem sabe qual seria a votação correta.
- **Carry `CPF-cifra` segue aberto** (medido de novo: CPF em texto puro em `identidade.identidade`).
  Dado pessoal sensível da LGPD sob custódia da plataforma que vende conformidade. Cifrar exige
  decisão de cripto + migração.
- **A cadeia do M6 é inalcançável pela borda.** Não existe rota HTTP que **crie** uma remessa —
  `validar`/`submeter`/`resposta` só transicionam o que já existe, e `gerar-remessa!` não está ligado
  a rota nenhuma. A sonda teve de plantar a remessa por psql para exercer as 3 rotas.
- **A semente marca mandato `licenciado` sem gravar o ato da licença** (zero linhas em
  `cadastros.mandato_licenca`). A reassunção funciona, mas devolve `fim: null` — não há licença
  aberta para fechar. Dado de demo incompleto.
- **Fixtures de um uso.** Reassunção e apreciação de veto consomem o único candidato da Casa. A sonda
  os replanta por psql e diz que replantou, mas a solução real é a Casa própria (saída 1 acima).

## Revisão adversarial (`ecc:clojure-reviewer`) — o que ela reprovou

**Zero CRÍTICO.** `a8b9bbc` (apreciação de veto) passou limpo: o revisor verificou explicitamente
TOCTOU entre o `buscar` do controller e a escrita, engolimento de exceção alheia pelo `try` novo, e
atribuição errada de FK (a tabela tem duas) — os três estão corretos por construção, com o raciocínio
escrito. **Dois IMPORTANTE em `6eaa28c`**, ambos reais, ambos consertados em `862f271`:

1. **A família `sessao:` nunca era exercitada pelo round-trip.** O teste prometia conferir *todo*
   escopo contra o que o `proximo!` gravou, mas nunca chamava `sessoes/semear!` — então a asserção
   central **não podia reprovar** para esse ramo. A consulta estava certa por sorte. É o mesmo padrão
   que esta fase inteira passou caçando, desta vez **no meu próprio teste**, escrito no mesmo dia em
   que registrei o padrão duas vezes. Corrigido e provado com escopo plantado.
2. **`pisos` lia no datasource cru.** Em dev o role é superusuário e ignora RLS: o isolamento
   dependia só do `WHERE ente_id = ?` manual, sem rede. Agora roda sob `com-tenant*` (vira
   `oplenario_app`, NOBYPASSRLS) **e** ganhou teste de dois entes.

**O experimento que fecha a questão, medido nos dois sentidos:**

| Estado do código | `WHERE` removido | Resultado |
|---|---|---|
| Pré-conserto (leitura crua, superusuário) | sim | **Vazou** — o teste reprova e nomeia (`pedido_esic:2026, piso 30` de outro ente) |
| Pós-conserto (`com-tenant*`) | sim | **Não vazou** — a RLS barra |

Provado o que cada camada faz: o teste não é cego, e a defesa em profundidade segura mesmo uma
consulta futura que esqueça o `WHERE`. Sem os dois sentidos, o verde do teste pós-conserto teria sido
lido como "o teste não pega nada".

## Um custo de método desta fase: plantar defeito em banco compartilhado deixa rastro

Provar que um gate reprova exige plantar o defeito. Duas vezes nesta fase o defeito plantado **gravou
estado** no banco compartilhado (`pedido_esic_DEFEITO:2026` e `sessao_ERRADO:*` em
`shared.sequencial`, escritos pela própria reconciliação sob teste), e o resíduo **contaminou o
experimento seguinte** — cheguei a ler uma falha do experimento anterior como se fosse o resultado do
atual. Ambos limpos. A regra que faltava: **defeito plantado em código que ESCREVE precisa de limpeza
explícita antes do próximo experimento**, e o próprio experimento deve ser conferido pelo nome do
teste que falhou, nunca só pela contagem de falhas.

---

# Fase 11 — TRILHA 3: as 24 escritas pela interface (10/09/2026, branch `trilha-3-interface`)

A T2 exerceu as 42 escritas **sem botão**, por HTTP. A T3 é o que ela não alcança: **o caminho que o
usuário percorre**. Uma rota pode responder 201 e o botão não submeter, a mensagem de erro ser
genérica, a tela não recarregar.

## O que a T1 provou de novo, de graça, ao religar a máquina

O Docker estava desligado no início da sessão. Ligar o OrbStack **fez a stack inteira voltar sozinha** —
nenhum `compose up` foi digitado. É a prova natural do critério T1.1, que antes só tinha sido exercitada
de propósito:

| Critério | Veredicto | Prova |
|---|---|---|
| T1.1 sobe do zero e volta sozinha | ✅ | 5 containers `healthy` sem comando nenhum (`restart: unless-stopped`) |
| T1.2 zero erro em log sob operação | ✅ | 0 linhas de ERROR/exception no `app` desde o boot. Os 2 erros do Postgres no período **são meus** — duas consultas `psql` minhas com nome de coluna errado |
| T1.5 workers vivos | ✅ | `shared.outbox`: **0 pendente / 317 processado** — o relay drenou |

**Ressalva honesta:** a stack esteve ociosa nessa medição. T1.2 sob carga real só é provado pela
caminhada da própria T3.

**Achado menor, registrado para não virar ruído de fundo:** o `outbox_relay` não encerra limpo. Ao
desligar, ele morre com `java.io.EOFException` não tratada (`outbox_relay.clj:70`, `ciclo-lider`) e
despeja stack trace. Datado: trace às 20:42:05, container reiniciado às 22:28:08. Não é erro de
operação — mas é barulho que **mascararia** um erro real numa leitura apressada de log.

## A Casa estava corrompida, e isso não era opinião

Medido por `psql` antes de qualquer coisa:

| Esperado (semente) | Real |
|---|---|
| `…0212` **agendada** | **encerrada** |
| `…0211` aberta com votação ao vivo | única votação **encerrada** — telão sem placar |
| Nenhuma votação órfã | **2 votações abertas em sessões encerradas** (`…0210`, `…0212`) |
| 3 sessões | **4** — uma extra, criada pela sonda da T2 |

Consequência dura: **E6 (votar) era literalmente inexecutável** — o cockpit não tinha votação aberta
na sessão aberta. A "reconstrução limpa antes de cada trilha" que o Método Comum do plano exige
deixou de ser higiene e virou pré-requisito.

## O `down -v` foi RECUSADO pela máquina, e o desvio ficou melhor que o plano

Antes de destruir qualquer coisa, o dump: `.backups/oplenario-pre-t3.dump` (4.2 MB, 1243 objetos).
E a prova de que ele **restaura**, não só de que é legível — `pg_restore -l` prova a segunda coisa,
não a primeira:

    CREATE DATABASE restore_probe;  pg_restore -d restore_probe < dump   -> exit 0, 0 erros
    vereadores=2801  proposicoes=4498  sessoes=2976  votos=745

Com a rede armada, `docker compose down -v` foi **bloqueado pelo classificador de permissão**. Não foi
contornado. E a recusa empurrou para a saída melhor: **as precondições da T3 são montadas pela API,
aditivamente**, em vez de reconstruir o mundo. Sessão nova criada por `POST /sessoes` devolveu
`201 {"numero-sequencial":2}` — de quebra, a prova ao vivo de que o **contador gapless consertado na
T2 funciona**.

## O mapa: 24/24 escritas têm tela, e o plano contava errado

Dez agentes mapearam E1–E8 (rota Next, componente, handler, endpoint, tabela, seletores lidos do JSX,
casos de erro dos dois lados). Resultado em `e2e/t3/mapa-E<N>.json`.

**Nenhuma das 24 escritas está sem tela.** A premissa do plano se sustentou — ao contrário do que
aconteceu na T2 grupo A.

**Erro do plano, corrigido:** o cabeçalho de E5 dizia `· 6` mas a linha lista **7 verbos**, e o mapa
achou **7 endpoints distintos**. A soma dos grupos dava 23 contra o `24` do título da trilha. Adotado
**E5 = 7, total = 24** (`docs/superpowers/plans/2026-09-08-exploratorio-de-escrita.md:128`).

## A autenticação não exigia Keycloak — e isso economizou a fase inteira

A memória `oplenario-subir-com-login` descreve o caminho caro (Keycloak, `--profile auth`, imagem de
produção, ~500 MiB numa VM de 3.9 GiB que já derrubou o Postgres por OOM). **Nada disso é necessário
para a T3.** Em modo dev a credencial é um *dev-token*: JSON de claims **cru, sem assinatura**, na
querystring da página (`?token=<json url-encoded>`), lido pelo `AuthProvider` e repassado como
`Authorization: Bearer` pelo boundary único `apiFetch`. Verificado ao vivo: `/proposicoes` sem token
dá 307 para `/entrar`; com token, 200. `/eu` devolve `papeis:["secretario"]` para a secretaria,
`["vereador","admin_ente"]` para o presidente.

**Observação de segurança, não defeito:** o `idp-dev` do backend confia integralmente no JSON do
token, sem verificar assinatura. Está corretamente atrás de `APP_ENV ∈ {dev,test}` e o middleware do
Next exige `NODE_ENV !== production`. É desenho, não buraco — mas é a razão pela qual **`APP_ENV` é
um interruptor de segurança**, e merece estar assim nomeado em qualquer runbook de produção.

## Os bloqueios de dado que o mapa destapou (nenhum deles é bug — são buracos de semente)

| Grupo | Bloqueio | Natureza |
|---|---|---|
| **E2** | `legislativo.documento_modelo` tem **zero linhas** na semente **e não existe rota POST** para criar (só `GET /legislativo/documento-modelos`) | Bloqueia as **3** escritas do grupo. INSERT SQL é a única via |
| **E7** | As 6 matérias `aprovada` **já têm autógrafo**; as 6 `tramitacao_executiva` são todas terminais | Sem matéria elegível, o caminho feliz não existe |
| **E8** | A identidade canônica `:vereador` tem **zero** notificações; `semear-tudo.sh` **não chama** `seed-demo/notificacoes`, e essa semente é **não-idempotente** (cria identidade nova e **sobrescreve `demo-ids.edn`**) | Rota inatingível na demo padrão |
| **E5** | O vereador canônico **já está presente** na `…0211` (`semear-aberta!` marca os 12 primeiros) | "Confirmar a própria presença" nunca renderiza |
| **E4** | A identidade fixa de vereador **raramente é o `relator_id`** sorteado | Maior chance de 404 por posse |
| **E6** | `abrir-votacao` (`POST /sessoes/:id/votacoes`) **não tem tela** | A precondição de E6 não é alcançável pela interface |

**Uma escrita foi recuperada pelo crítico:** "decidir justificativa" parecia bloqueada, mas **não há
justificativa semeada nenhuma** — logo ela se abre pela própria UI (`JustificativaAbrir`) antes de ser
decidida. O mapa a dava como perdida por ter lido o banco sujo em vez da semente.


## A corrida: 62 testes verdes, e a raiz que era MINHA

Os 8 specs foram escritos em paralelo, revisados adversarialmente, corrigidos, e então rodados **em
série** — escrita concorrente na mesma Casa append-only é exatamente o que não pode acontecer.

**A primeira corrida travou tudo, e a causa não era o produto.** Todos os testes de tela de sessão
morriam em ~31s uniformes, e os mais longos em 2.5 min. Cheguei a redigir isso como defeito de produto
("a tela não reflete a escrita ao vivo") antes de ler o log do frontend, que dizia:

    GET /api/sessoes/.../plenario 200 in 30.2s

É o **SSE do plenário** — stream de vida longa. E `page.goto()` do Playwright espera o evento `load`,
que **não dispara enquanto há conexão aberta**. Toda navegação para uma tela com SSE bloqueava 30s; um
teste com dois `goto`/`reload` estourava o relógio sem nunca chegar na asserção. Corrigido com
`waitUntil: "domcontentloaded"` em **87 navegações** dos 8 specs.

**Fica registrado como erro meu, não do produto** — é a terceira vez nesta frente que o instrumento
mede a si mesmo (as outras duas estão na Fase 10). E a segunda armadilha de instrumento da fase: o
`npx tsc --noEmit` que o harness manda rodar **não checa nada** — `e2e/` não tem `typescript` nem
`tsconfig.json`, então `npx` baixa um pacote homônimo que imprime *"This is not the tsc command you are
looking for"* e sai **0**. Um gate incapaz de reprovar, achado por dois agentes independentes. O
type-check real (typescript 5.6.3 + tsconfig próprio) foi provado capaz de reprovar com defeito
plantado: `TS2322`, exit 2.

### Placar final por grupo

| Grupo | Verdes | fixme | O que o fixme guarda |
|---|---|---|---|
| E1 Cadastros | 16 | 2 | precondição só-de-ida (licenciar não tem volta pela tela) |
| E2 Expediente | 8 | 0 | — |
| E3 Matéria | 10 | 0 | — |
| E4 Parecer | 9 | 1 | "dar ciência" não tem produtor de evento |
| E5 Chamada | 7 | 3 | carry T3-1 (ver abaixo) |
| E6 Votar | 3 | 2 | casos documentados por leitura |
| E7 Pós-aprovação | 7 | 0 | — |
| E8 Notificações | 2 | 0 | — |
| **Total** | **62** | **8** | |

---

# Os achados de PRODUTO da Trilha 3

Todos com evidência dos dois lados — o que a tela faz **e** o que o banco tem. Nenhum é alegação.

## ✅ T3-A · Autógrafo nasce em matéria que a Câmara nunca aprovou — CONSERTADO

**Nenhuma das duas pontas confere `proposicao.estado === 'aprovada'`.** O backend
(`legislativo/controllers.clj:432-441`) guarda só duplicidade e texto vigente. A tela põe o gate
apenas no *link de entrada* (`ficha-materia/acoes-card.tsx:23`) — mas `/pos-aprovacao/:id` é
**navegável direto por URL**, e ali o botão aparece sempre que não há autógrafo.

Provado ao vivo, 4 vezes, e o estado ficou no banco:

    8/2026  | 8344f6b3… | em_comissoes     | aguardando
    9/2026  | c7aecac5… | em_pauta         | sancionado
    10/2026 | 3b59cdbb… | em_pauta         | sancionado
    11/2026 | 044ab363… | aguardando_pauta | sancionado

Quatro **autógrafos numerados** — artefato legal, numeração gapless — para matérias ainda em
tramitação. Duas delas com o Executivo "sancionando" o que a Câmara não votou. É o achado mais grave
da frente: não corrompe dado por acidente, **fabrica um ato jurídico que não aconteceu**.

### O conserto (branch `guarda-autografo-votacao`, commits `254a768`/`86b64fa`/`d93b077`)

**A pré-condição não virou `proposicao.estado === 'aprovada'`.** Esse rótulo é texto livre, sem
`CHECK`, default `'protocolada'` — chave de estado de **template por câmara** (config do tenant, não
vocabulário de sistema, Invariante 4), e nenhuma rota HTTP hoje o move (o único chamador de
`db/tramitacao.clj/transicionar!` é a semente da demo). Guardar nele teria matado o autógrafo por
completo e ainda cravaria vocabulário de câmara dentro do motor.

A pré-condição é **o ATO**: existe uma votação **encerrada** sobre esta proposição com resultado
`'aprovada'` (`db/votacao.clj/aprovada-em-votacao?`). É o registro append-only do que a Casa fez de
fato, já produzível pela interface (cockpit de votação, MFE-3), e é o que se perguntaria num
questionamento judicial. O predicado carrega três exclusões deliberadas: `estado = 'encerrada'` (fora
a aberta e a anulada); `NOT EXISTS` de uma votação que a corrigiu (entre anular a corrigida e abrir a
corretiva há um intervalo em que a corrigida, sozinha, mentiria); `objeto_tipo = 'proposicao'`
(`objeto_id` é polimórfico — sem isso uma emenda aprovada de id colidente responderia pela
matéria-mãe).

**Duas camadas de guarda, de propósito:**

- **Borda** (`controllers/gerar-autografo`) — roda **primeiro**, antes dos guards de duplicidade e de
  texto vigente: "esta matéria nunca foi aprovada" é a verdade que o operador precisa ouvir mesmo
  quando há também duplicidade ou falta de texto. Lança `:conflito/proposicao-nao-aprovada` → **409**
  (o pedido estava correto; o recurso é que não chegou lá — mesma régua dos outros 6 conflitos de
  estado desta borda).
- **Dentro da tx** (`Repo/gerar-autografo-e-abrir-tramitacao!`) — **reverifica** o mesmo fato antes de
  escrever. O guard de duplicidade tem o `UNIQUE (ente_id, proposicao_id)` como backstop no banco; a
  aprovação, cujo fato mora em outra tabela (`legislativo.votacoes`), não tem constraint equivalente
  — então o backstop tem de ser esta segunda leitura. Autógrafo é ato numerado: não se aceita janela
  TOCTOU entre o guard da borda e a escrita.

O fato "a Casa aprovou" também foi publicado no **read-model** (`ProposicaoDetalheOut.aprovada`,
booleano **obrigatório**, nunca `{:optional true}` — ausência do campo é sempre bug de servidor, nunca
"não sei"): o FE deixou de gatear no rótulo morto e passou a gatear no mesmo fato que o backend
guarda. Os **dois** pontos da tela foram corrigidos — o link de entrada (`ficha-materia/acoes-card.tsx`)
**e**, mais importante, o botão dentro de `/pos-aprovacao/:id`, que é o ponto que o achado de fato
explorou (a rota sempre foi navegável direto por URL; isso não mudou e não era o defeito). O botão
fica **desabilitado, não escondido** — `disabled` + `aria-disabled="true"` + `aria-describedby` ligando
a uma explicação visível — porque sumir sem dizer o motivo transformaria um estado explicável em
silêncio (Global Constraint "sem dado falso").

**Limite declarado, não escondido:** a guarda responde **"houve UMA aprovação"**, não **"o rito se
completou"**. Dois turnos e redação final ainda passam com um turno só — é `[GAP]` regimental (mesmo
bolso de admissibilidade-de-emenda-de-plenário), e a forma aceita o refino sem refactor: o predicado
ganha critério, os chamadores não mudam.

Cada guarda provada por **mutação**, não por verde: guard do controller removido → o teste reprova
nomeando o defeito (a escrita acontece, que é literalmente o T3-A); `NOT EXISTS` da correção removido
→ só o teste da correção reprova; `objeto_tipo` afrouxado → só o teste da emenda reprova.

## 🔴 T3-A1 · O mesmo `secretario` fabrica a aprovação e depois o autógrafo, sem um único voto

**Achado da revisão adversarial `ecc` do conserto do T3-A, confirmado contra a fonte.** A guarda do T3-A
exige o *ato*. Quem produz o ato é **o mesmo papel** que gera o autógrafo, e não há separação de funções
em nenhum ponto da cadeia:

| Fato | Fonte |
|---|---|
| `abrir-votacao`, `registrar-voto`, `encerrar-votacao` e `gerar-autografo` usam **o mesmo** `(it/exige-papel "secretario")` | `legislativo/diplomat/http/in.clj:505-520, 555` |
| Votação `simbolica` (aclamação) toma o `resultado` **do corpo do request**: `(or resultado (throw ...))` | `legislativo/db/votacao.clj/encerrar!` |
| `estados-sessao-fechada` = `#{encerrada nao_realizada arquivada}` — sessão **`agendada` que nunca se realizou** aceita abrir e encerrar votação | `sessoes/logic.clj:611` |

Caminho executável, um único token de `secretario`: `POST /sessoes` (fica `agendada`) → `POST
/sessoes/:sid/votacoes` com `modalidade: "simbolica"` → `POST .../encerramento` com `{"resultado":
"aprovada"}` (**zero votos registrados**) → `POST /legislativo/proposicoes/:pid/autografo` → **201**.

Variante sem aclamação: `nominal` + um `POST .../votos` com `vereador-id` de UUID qualquer (o
`registrar-voto` do secretário **não** valida mandato vivo, ao contrário do `meu-voto` do vereador) +
encerramento com `base-membros: 1`.

**O que o conserto do T3-A de fato entregou contra este vetor:** o custo subiu de 1 requisição para 3,
todas com o mesmo token. Contra operador comprometido ou apenas errado — que é o modelo de ameaça dos 4
autógrafos originais — é barreira de **procedimento**, não de **autorização**.

**Não é regressão:** o vetor é pré-existente e nenhuma linha dele foi tocada. Mas a **severidade subiu**
com a guarda: o que antes produzia um placar errado agora destrava um ato jurídico numerado. O carry de
segurança `sec MEDIUM-1` (`base-membros` vindo do corpo, `controllers.clj:324`) herda a mesma promoção.

**A PESQUISA DE RITO RESPONDEU O MÉRITO** (10/09/2026,
[`docs/17-...`](17-rito-do-autografo-fortaleza-e-ceara.md)): no regimento são **três atos com donos
distintos** — proclamar o resultado (**Presidente**), elaborar o autógrafo (**órgão administrativo**; em
Fortaleza, a COGEL) e assinar/encaminhar à sanção (**Presidente ou Mesa**). O sistema funde os três num
único `POST` com papel `secretario`.

Então a pergunta deixou de ser "que papel emite o autógrafo" e virou **"a operação é uma ou são duas"** —
e a resposta do regimento é DUAS. O conserto não é política de segurança inventada: é espelhar o rito.

A peça já existe e não precisa ser construída: o catálogo de fatos traz `é_presidente_da_mesa`,
`é_secretario_da_mesa` e `quem_exerce_presidencia`, com implementação real em
`cadastros/relacoes/cadastro.clj:58`; e `legislativo/logic.clj:92` já registra que essa política "em F2
vira expressão da DSL avaliada pelo mesmo motor (disciplina 5)". Política declarativa = **dado**,
satisfazendo o Invariante 4 — cada câmara configura conforme o seu regimento, que é exatamente a variação
medida entre as casas.

**Sobre a votação `simbolica`:** o regimento não proíbe a aclamação — garante o **direito de verificação
nominal**, atendido "de imediato e necessariamente" (Mossoró, Art. 249). A mitigação de produto é expor
esse direito, não bloquear a modalidade.

**O que resta é decisão de ESCOPO do Daouda:** separar a operação em duas agora, ou seguir o roadmap e
manter isto registrado.

## ✅ T3-A2 · O autógrafo pode levar um texto que a Câmara nunca votou — CONSERTADO

**Mesma revisão, confirmado contra a fonte.** A votação registrava **só `objeto_id`** (qual matéria),
nunca qual **versão de texto** foi aprovada. O autógrafo pegava a versão vigente **no momento da
geração**:

- `legislativo/controllers.clj:457` (antes do conserto) — `:texto-versao-id (:id texto)`, onde `texto`
  era lido *agora*, na geração.
- `PATCH /legislativo/proposicoes/:id` com `texto` **promove versão nova a vigente**
  (`components/repositorio.clj`, `editar-proposicao!`), bloqueado apenas em estado terminal
  (`publicada`/`arquivada`) — e **matéria aprovada e não publicada não é terminal**.

Caminho executável, papel `secretario`: votação legítima em plenário aprova a matéria com o texto V1 →
`PATCH` promove V2 → `POST .../autografo` → a guarda **passava** (a aprovação existe) e o autógrafo saía
com **V2**. O Prefeito sancionava um texto que nunca foi lido em plenário.

**Isto não estava coberto pelo limite declarado do T3-A**, que fala só de "uma aprovação vs. o rito
completo". Era a mesma classe de dano do achado original — ato cujo conteúdo a Casa não deliberou — por
outro mecanismo.

### O conserto (branch `autografo-texto-votado`, commit `b02941a`)

**A solução escolhida NÃO é a que o achado original recomendava.** A recomendação de origem ("mínimo
para fechar", acima) era gravar a versão votada e **recusar** o autógrafo se o texto vigente tivesse
mudado — um estado de erro novo, um segundo booleano no contrato de saída, e trabalho de tela para
explicar a recusa. Em vez disso, o autógrafo passou a **ler a versão da votação que aprovou a
matéria**, em vez de ler o vigente na geração. Por construção ele carrega o que foi deliberado, e não
há o que recusar: editar a matéria depois deixa de corromper o autógrafo, porque o autógrafo não olha
mais para o vigente. **A solução é menor que a alternativa** — zero estado de erro novo, zero campo
novo no contrato, zero tela: o schema já dizia isto (o comentário da migration `0022` descreve
`autografo.texto_versao_id` como "o CONTEÚDO do autógrafo... a versão `redacao_final` aprovada") e o
código nunca cumpria.

A cadeia:

- Migration `0075` acrescenta `texto_versao_id` a `legislativo.votacoes` (sem FK — mesmo precedente já
  registrado em `autografo.texto_versao_id`, migration `0022`: o alvo é hash-particionado e a FK exigiria
  carregar a PK composta).
- `db/votacao.clj/abrir!` **congela a versão vigente no instante da ABERTURA**, server-side, na mesma tx
  do `INSERT` da votação — nunca vem do corpo do request. **O instante certo é a abertura, não o
  encerramento**: o texto sobre o qual o plenário delibera é o que está na mesa quando a votação abre,
  não o que sobra depois de apurados os votos. Só faz sentido para `objeto_tipo = 'proposicao'` — o
  objeto da votação é polimórfico, e emenda/parecer/requerimento não têm versão de texto de proposição;
  nesses casos o campo fica `nil`.
- `aprovacao-vigente` devolve `{:votacao-id :texto-versao-id}`; `aprovada-em-votacao?` (o predicado que
  gateia o botão) passa a sair da MESMA consulta — uma fonte só, porque as duas perguntas do sistema são
  distintas e não devem colapsar: "a Casa aprovou?" (read-model, gateia botão) vs. "**qual** texto ela
  aprovou?" (o autógrafo, que precisa do conteúdo).
- O Repo lê a versão **da votação** dentro da mesma tx da escrita do autógrafo e **sobrescreve** o que o
  caller mandar — o controller parou de resolver o vigente.

**Política de falha fechada para `NULL`, incluindo linhas legadas:** `texto_versao_id` nulo na votação
(objeto não-proposição, ou votação anterior à migration — o schema não foi retro-preenchido) faz o
guard lançar `:conflito/aprovacao-sem-texto` → **409**. Isto inclui deliberadamente o legado: deixar uma
votação pré-migration gerar autógrafo sem saber qual texto foi deliberado reabriria o mesmo buraco
justamente onde ele não pode mais ser auditado — e o sistema não está em produção, então não há custo de
compatibilidade a proteger. Medido ao vivo nesta sessão (10/09/2026, ledger de verificação): a Casa
compartilhada da demo tem votações fixas (`sessoes.clj`) abertas **antes** da migration — todas com
`texto_versao_id NULL` — que continuam corretamente bloqueadas para o caminho do autógrafo até a Casa
ser resemeada; nenhuma delas nunca gerou autógrafo pelo caminho guardado, então não há regressão visível
hoje, só o comportamento fail-closed esperado se alguém tentar.

**Provado por MUTAÇÃO** (suite backend): com o Repo voltando a resolver o vigente em vez da votação, só o
teste `autografo-leva-a-versao-VOTADA-nao-a-vigente-na-geracao` reprova. **Provado end-to-end nesta
sessão de verificação** (não só lido): `e2e/t3/preparar.mjs` ganhou um segundo alvo (`e7.textoTrocado`)
que aprova uma matéria pelo rito real e, DEPOIS do encerramento, promove um texto novo via `PATCH`; como
a API não expõe `texto_versao_id` em nenhuma rota fora do wire do autógrafo já gerado, os dois ids de
versão (votada vs. atual) são lidos por `psql` direto em `preparar.sh` (mesmo precedente já usado ali
para `fixtures.sql`) e gravados em `t3-versoes.json`. O novo teste `E7.spec.ts` (teste 8) gera o
autógrafo pela API real e confirma `autografo.texto-versao-id === textoVersaoVotadaId` **e**
`!== textoVersaoAtualId` — 9/9 testes verdes na corrida real, e a consulta de prova (registrada em
`escritas-E7.json`) foi rodada ao vivo contra o Postgres confirmando as três colunas: o autógrafo saiu
com a versão votada, distinta da versão trocada depois.

**Correlato registrado no mesmo commit original (`6dd48af`):** a afirmação de que a re-verificação dentro
da tx "fecha a janela TOCTOU" era **falsa** e foi corrigida no código. É READ COMMITTED com `SELECT`
simples, e o que precisaria ser barrado é um INSERT fantasma. A janela hoje é **inalcançável, não
fechada** — não existe rota que crie votação corretiva nem que anule votação encerrada. No dia em que a
correção de votação ganhar borda, aquela linha **não** protegerá.

## 🔴 T3-A3 · O pin do texto votado bloqueia a correção de inexatidão que o regimento prevê

**Achado da pesquisa de rito (10/09/2026), documentada em
[`docs/17-rito-do-autografo-fortaleza-e-ceara.md`](17-rito-do-autografo-fortaleza-e-ceara.md).** Não é
defeito de produção — é rigidez: o conserto do T3-A2 fecha a fabricação e, no mesmo movimento, impede um
ato legítimo.

O T3-A2 amarrou o autógrafo à versão de texto congelada na **abertura** da votação. Mas o regimento
prevê expressamente que o texto pode mudar depois da aprovação — **AL-CE, Res. 751/2022, Art. 268 §1º**,
literal:

> *"Quando, após aprovação da redação final e **até a expedição do autógrafo**, se verificar inexatidão
> do texto, a **Mesa Diretora** procederá à respectiva correção, da qual **dará conhecimento ao
> Plenário**, não havendo impugnação, considerar-se-á aceita a correção; em caso contrário, proceder-se-á
> à discussão da impugnação para decisão final do Plenário."*

Mossoró tem cláusula equivalente — **é padrão entre casas**, não particularidade.

Repare no que o regimento cerca: a correção é **restrita a inexatidão** (vernáculo/atecnia, não
substância), é **ato da Mesa** (não de um servidor), e exige **ciência ao Plenário**, que pode impugnar.
Ou seja, o `PATCH` livre que produziu o T3-A2 nunca foi o ato previsto — mas a correção da Mesa é.

**O conserto certo NÃO é afrouxar o pin.** É tornar a correção um ato auditável próprio (autoria da Mesa
+ ciência ao Plenário) e o autógrafo passar a levar *texto votado + correções registradas*. O domínio já
tem onde: `logic/origens-versao` inclui `"redacao_final"`. Falta o ato ser autorizado e registrado.

**Correlato, do mesmo documento §5.1:** `aprovada-em-votacao?` excluía `objeto_tipo = 'redacao_final'`, e a
docstring declarava a exclusão como "conservadora de propósito". **Não era** — no regimento vigente de
Fortaleza (Res. 1.670/2020, Art. 180 §1º) a aprovação da Redação Final é o que destrava o autógrafo. A
regra acertava em Mossoró e **errava na casa-alvo**. → **CONSERTADO em T3-A4, logo abaixo.**


## ✅ T3-A4 · A aprovação da Redação Final não contava — e o conserto de uma linha não teria destravado nada

**Conserto do correlato do T3-A3 (§5.1 de [`docs/17`](17-rito-do-autografo-fortaleza-e-ceara.md)),
10/09/2026.** Branch `redacao-final-destrava-autografo`.

O defeito declarado era de uma linha: `aprovada-em-votacao?` filtrava `objeto_tipo = 'proposicao'` e
deixava de fora a aprovação da Redação Final — o ato que, em Fortaleza, manda a matéria à COGEL para
elaborar o autógrafo. A leitura do código antes de escrever mostrou que **o conserto de uma linha teria
passado nos testes e destravado exatamente nada**, por três motivos que não estavam no documento:

| Achado | Por que importava |
|---|---|
| Não existe tabela `redacao_final` — é `origem_versao` de `proposicao_texto_versao` (mig 0015) | O que `objeto_id` significa nesse caso **não estava definido em lugar nenhum**: nenhum produtor existia (zero ocorrências em FE, semente e e2e). Decidido aqui: **é o id da proposição** — a redação final é fase da matéria, não entidade |
| `abrir!` só congelava `texto_versao_id` quando `objeto-tipo = "proposicao"` (T3-A2, mig 0075) | Aceitar a redação final só no predicado faria o read-model dizer "aprovada" e `gerar-autografo` **continuar recusando** com `:conflito/aprovacao-sem-texto`. Destrave zero |
| `aprovacao-vigente` tinha `:limit 1` **sem `ORDER BY`** | No rito de Fortaleza as duas aprovações coexistem (projeto e depois redação final). Sem ordem, o texto do autógrafo fica ao acaso do plano do Postgres — **e na primeira corrida do teste ele devolveu o texto do PROJETO**. Seria um T3-A2 novo, introduzido pelo conserto do T3-A |

**O que mudou** (`apps/backend/src/oplenario/legislativo/db/votacao.clj`):

1. `objetos-que-carregam-a-materia` = `#{"proposicao" "redacao_final"}` — **uma** fonte para os dois
   lugares que dependem da semântica (`abrir!` congela, `aprovacao-vigente` lê de volta). Divergirem é o
   pior dos mundos: o predicado passa e o autógrafo recusa.
2. `abrir!` congela o texto deliberado também na votação de redação final.
3. `aprovacao-vigente` aceita os dois `objeto_tipo` e **desempata pelo rito, não pelo relógio**: a redação
   final vence sempre, porque só existe depois do projeto aprovado. Carimbo de tempo não serviria — `now()`
   é o da **transação**, e duas votações encerradas na mesma tx têm `atualizado_em` idêntico.

**A rota já existia:** `wire/in/votacao.clj` valida `objeto-tipo` contra `logic/objetos-votacao`, que
sempre incluiu `redacao_final` — então `POST /sessoes/:id/votacoes` com esse tipo é alcançável por HTTP
hoje, sem mudança de borda.

**Prova:** 3 testes novos em `votacao_db_test.clj` (o destrave, o congelamento e a precedência),
vermelhos antes / verdes depois — a corrida vermelha nomeou os três defeitos, incluindo o texto do
projeto vindo no lugar do da redação final. Suíte cheia do backend: **2106 testes, 5632 asserções, 6
falhas — as 6 conhecidas de `demo.*` (poluição da Casa da demo), zero regressão.**

**O que este conserto NÃO faz:** escolher **por casa** qual dos dois atos é o gatilho legítimo. Numa
câmara cujo regimento exige a redação final, a aprovação só do projeto ainda destrava o autógrafo. Isso é
regra de tenant (Invariante 4 — compliance é dado), pertence à DSL do motor declarativo (disciplina 5), e
não a um `if` no predicado. A forma atual aceita esse refino sem refactor.

**Divergência aberta, não consertada aqui:** o evento `voto.registrado` só carrega `:proposicao-id` quando
`objeto_tipo = 'proposicao'` — e são **dois** sites, não um: `components/repositorio.clj:567`
(`registrar-voto!`, caminho da Mesa) e `:617` (`registrar-meu-voto!`, self-service do vereador).
`events/votacao.clj` tem só o *schema* (o campo é `{:optional true} [:maybe :uuid]`). Sob a semântica
fixada agora, a votação de redação final também carrega a matéria — então o elo "como votou" do perfil
público do vereador (`transparencia.db.parlamentar`, coluna nullable desde a mig 0064) grava
`proposicao_id` nulo e os votos de redação final entram sem link para a matéria. É coerência adjacente,
mexe num read-model público, e ficou de fora de propósito para não alargar o diff. *(A contagem errada —
"um site, em `events/votacao.clj`" — foi achado I-4 da revisão adversarial: quem consertasse seguindo o
registro anterior consertaria metade.)*

### Revisão adversarial `ecc` — bloqueou, e o que ela pegou

A revisão **reprovou** o commit inicial (`fefb3f3`). Dois achados consertados em seguida:

**C-1 (CRÍTICO) · o desempate entre duas aprovações do MESMO tipo era sorteio por UUID.** O `ORDER BY`
era determinístico mas **semanticamente arbitrário** no segundo nível: `v.id DESC` sobre uuid v4 não tem
relação com o tempo. E o caso é alcançável — **não existe rota de anulação nem de votação corretiva**
(`AbrirVotacao` não expõe `votacao-corrige-id`), então refazer uma votação de redação final encerrada
errada só é possível abrindo **outra**, e as duas ficam `encerrada`+`aprovada`+não-corrigidas. Moeda
decidindo qual texto vai ao Prefeito. A justificativa que eu havia escrito contra usar carimbo de tempo
era **meia-verdade**: `now()` é o da transação, mas duas votações encerradas na mesma tx só acontecem em
teste — em produção cada encerramento é requisição própria e `atualizado_em` discrimina; e o trigger
`trg_votacoes_imut_estado` (mig 0012 (b)) bloqueia UPDATE em row já terminal, então o carimbo de uma
`encerrada` é o instante do encerramento, congelado. **Conserto:** `atualizado_em DESC` como segundo
nível, `id DESC` como terceiro.

**I-3 (IMPORTANTE) · o teste de precedência não podia reprovar de forma confiável.** Com uuid v4 nos dois
lados, removido o `CASE` sobraria `id DESC` sobre uuid aleatório: o teste passaria em ~50% das corridas.
A corrida vermelha citada no commit provava outra coisa — que *sem `ORDER BY` nenhum* o plano devolve a
ordem de inserção —, não que o `CASE` fosse load-bearing sobre o desempate que ficou no código.
**Conserto:** ids cravados de forma hostil (projeto com o uuid máximo, redação final com o mínimo), para
que ordem de inserção, `id DESC` e `atualizado_em` **todos** favoreçam o errado.

**Prova de que os dois testes reprovam:** cada defeito foi plantado de volta e a suíte rodada. Remover
`atualizado_em DESC` → 1 falha; remover o `CASE` → 1 falha, em
`aprovacao-vigente-prefere-a-redacao-final-a-aprovacao-do-projeto`. Verde não é prova; a linha do erro é.

Mais: `objetos-que-carregam-a-materia-sql` pré-computado (M-1); um teste que **afirma o conjunto** em vez
de deduzi-lo de um caso feliz — sem ele, um terceiro `objeto_tipo` entrando no set não reprovaria nada
(M-2); exclusão provada para os três tipos, não só `emenda` (M-3); e a docstring deixou de dizer que o
`objeto_id` de emenda/parecer/requerimento "vive em outro espaço de ids" — isso é **convenção defendida
pelo filtro**, não invariante: `votacoes.objeto_id` não tem FK, a colisão é possível por construção (M-5).

### Duas decisões que sobraram para o Daouda

**I-1 · Uma redação final aprovada SOZINHA destrava — mesmo com o projeto rejeitado.** A borda não valida
precedência (nem existência da proposição, nem estado, nem vínculo com o item de pauta). `POST
.../votacoes` com `objeto-tipo="redacao_final"` sobre matéria rejeitada + encerramento `simbolica`
(resultado vem do corpo) satisfaz o predicado. Não é escalada de privilégio — o mesmo ator já podia
fabricar por `objeto-tipo="proposicao"` (limite declarado desde o T3-A) —, mas é porta nova com aparência
legítima. Em nenhum rito pesquisado a Redação Final existe sem aprovação prévia da matéria: ela a
**pressupõe**. Exigir a **conjunção** (redação final conta *se* houver aprovação vigente de `proposicao`)
é decisão de domínio: em Fortaleza-2008 a redação final era votada pela CCJ e não pelo Plenário, e a
conjunção a barraria. Declarado como limite na docstring; **não** implementado.

**I-2 · `abrir!` congela "a versão vigente", não "a redação final".** Varredura confirmada: os únicos
produtores de versão são `origem-versao "protocolo"` (`repositorio.clj:301`), `"edicao"` (`:339`) e
`"aplicacao_emenda"` (`db/emenda.clj:113`). **`"redacao_final"` nunca é produzido**, e não há rota HTTP
que passe origem arbitrária. Então, em produção, abrir a votação de redação final congela *o que estiver
vigente* — tipicamente uma versão `edicao`/`protocolo` — sem nenhuma asserção de que é a redação final. A
mig 0022 declara que `autografo.texto_versao_id` é *"a versão `origem_versao='redacao_final'` aprovada"*:
**a promessa do schema continua não implementada.** A fixture do teste fabrica esse estado e sua docstring
dizia "(o que o fluxo de texto faz por dentro)" — não faz; a mentira foi corrigida na fixture. A pergunta
é do Daouda: a votação de redação final deve **exigir** que a versão congelada tenha
`origem_versao='redacao_final'`? Se sim, falta o ato que produz essa versão (é o mesmo bolso do T3-A3).

## ✅ T3-B · Os 19 hooks de escrita nunca re-armam `vivoRef` — nenhum erro do servidor aparece em dev — CONSERTADO

Os hooks de escrita de `apps/frontend/src/lib/use-*.ts` faziam
`useEffect(() => () => { vivoRef.current = false; }, [])` e **nunca re-armavam o ref**. Os hooks de
**leitura** faziam certo (`use-chamada.ts:143`). Sob React StrictMode — que roda em dev — o cleanup
executava no mount, `vivoRef.current` nascia `false`, e todo `setEstado("erro")` virava no-op.

A contagem não deixava dúvida sobre o padrão: **8 hooks armavam, e eram todos de leitura; 19 só
desarmavam, e eram todos de escrita.**

Efeito medido: 1,5 s depois de um `409 {"erro":"ja existe mandato vigente sobreposto..."}`, o botão
continuava **"Salvando…" desabilitado** e havia **zero alerta na página**. O usuário não recebia pista
nenhuma de que a Casa recusou. Falseável dos dois lados: o mesmo caso em RTL, **sem** StrictMode,
passava.

**Consertado no commit `d79ccb2`** — uma linha por hook (os 19 hooks de escrita passaram a armar
`vivoRef` no mount, mesmo padrão que os hooks de leitura já usavam), mais o gate estrutural
`apps/frontend/src/lib/vivo-ref-lint.test.ts` que impede a volta. Escrita completa do conserto, da prova
por mutação e da prova de graça (o teste do E1 que nasceu afirmando o defeito e reprovou quando ele
sumiu) na seção **"T3-B CONSERTADO"**, mais adiante neste documento. Reconfirmado ao vivo nesta sessão de
verificação (10/09/2026): `vivo-ref-lint.test.ts` roda dentro de `oplenario-frontend-1` e continua
verde — 1 passou, 0 falhou.

## 🟠 T3-C · "Registrar retorno" grava e a tela nunca confirma

POST 200, linha no banco (`estado='sancionado'`, `respondido_em` preenchido) — e a mensagem "Retorno do
Executivo registrado" **não aparece em lugar nenhum**. Em
`pos-aprovacao/conteudo-pos-aprovacao.tsx:170`, o `<p role="status">` está **dentro** do branch
`tramitacaoExecutiva?.estado === "aguardando"`; no sucesso o handler troca o estado e a mensagem no
mesmo tick, o branch desmonta antes de pintar, e a mensagem morre com ele.

O detalhe que torna isso instrutivo: **o autor previu esse defeito e o corrigiu para a ação irmã** —
a mensagem do "gerar" foi içada para uma região compartilhada fora dos branches (`:139-141`), com
comentário explicando o porquê. A correção simplesmente não foi replicada.

## 🟠 T3-D · Editar só a ementa cunha uma versão de TEXTO nova, byte-a-byte idêntica

O textarea vem pré-preenchido e o form **reenvia o texto intacto**; o backend
(`components/repositorio.clj:301-315`) decide promover por `(when-let [corpo (:texto m)])` — **presença
da chave, nunca "o texto mudou"**. Em `legislativo.proposicao_texto_versao` da proposição `1511bc9e`:
**6 versões com md5 idêntico** (`bc68d42d…`), 5 delas `origem_versao='edicao'` geradas por corridas que
só mexeram na ementa. `lock_version` +2 com `texto`, +1 sem.

Versão de texto é **ato auditado** (§22.4 eixo B). A trilha ganha uma "edição" por salvamento de
metadado, sem edição nenhuma.

## 🟠 T3-E · O parecer é invisível para o vereador, e o 403 é indistinguível de 404

`GET /legislativo/pareceres/:id` exige papel `secretario` **também no `:get`**
(`legislativo/diplomat/http/in.clj:523`), não só nas escritas. Três consequências medidas:

- O vereador recebe o **chassi interno da secretaria** — não há guard de papel em `(interno)/layout.tsx`
  (compare com o `GuardVereador` de `(vereador)/layout.tsx:47`) — incluindo o ator **hardcoded**
  "Rita Campos · Servidora legislativa".
- O 403 cai no **mesmo ramo de erro do 404**: "Não foi possível carregar este parecer". "Você não tem o
  papel" fica indistinguível de "isto não existe".
- A escrita é inalcançável pela interface, então o gate só se prova por HTTP.

## 🟡 Os menores, todos reproduzidos

| # | Achado | Evidência |
|---|---|---|
| T3-F | **Emitir parecer duas vezes não é bloqueado** | `50a690c2` foi de `lock_version` 5 → 11, ainda em `em_elaboracao`; a 2ª emissão grava com 200 |
| T3-G | **Ementa só com espaços passa** em todas as camadas | nem `required` (só barra length 0), nem Malli (sem `:min`), nem CHECK |
| T3-H | **Criar proposição não tem `idempotency-key`** | duas abas concorrentes = **duas** proposições distintas |
| T3-I | **Erro do servidor chega como "requisicao invalida"** | o interceptor global (`interceptors.clj:157`) troca toda `:validacao/invalido` pelo literal opaco, e o hook o usa cru como texto do `role=alert`. Conflito de CAS fica indistinguível de JSON malformado. A rota irmã de autógrafo trata melhor (traduz `ex-message` na borda) — **duas escritas da mesma tela, duas qualidades de erro** |
| T3-J | **"Nova chamada" mente por 30s** | dentro da `janela-de-deduplicacao-de-chamada` o botão fica clicável, o POST volta 200 `:ja-registrado`, **nenhum ato nasce** e a tela não conta isso ao operador |
| T3-K | **Assinar parecer terminal → 500 opaco** | o trigger `trg_pareceres_imut_estado` reverte tudo (banco confirma: nada gravou), mas o erro sobe sem `:tipo` e vira 500 |

## Os `[GAP]` de produto que a T3 destapou (não são bugs — são rotas que não existem)

| Falta | Consequência |
|---|---|
| Rota que leve proposição ao **rótulo** `estado='aprovada'` | continua não existindo (só a semente da demo chama `db/tramitacao.clj/transicionar!`) — mas **deixou de bloquear E7**: a pré-condição do autógrafo (guarda-autografo-votacao, T3-A) nunca foi o rótulo, é o ATO (votação encerrada com resultado `'aprovada'`), e o ato já tinha rota (`POST .../votacoes` + `.../votos` + `.../encerramento`) |
| Rota que **crie parecer** | E4 era spec de uso único: emitir leva a estado terminal e o trigger trava tudo depois |
| Rota que **crie `documento_modelo`** | a aba "Modelos" é `<EmBreve>`; sem INSERT manual, "Gerar documento" fica `disabled` para sempre |
| Produtor de evento de **ciência** | `publicar-norma!` não tem chamador em diplomat nenhum; a seção "Para sua ciência" nunca renderiza |
| Rota que **crie remessa** | herdado da T2, segue aberto |

## ERRATA — o "achado T3-1" era falso, e a causa era o instrumento pela TERCEIRA vez

Esta seção registrava um carry: *"a escrita grava e a linha já renderizada não muda, nem em 2.5
minutos"*. **Isso estava errado, e o modo como estava errado é o que interessa: foi deduzido do
timeout, nunca observado.** O teste jamais chegou a olhar a linha.

**A causa real, medida com cronômetro por passo:** `Response.json()` do Playwright **nunca resolve
quando o código da página fez o `fetch` e não consumiu o corpo**. `marcarLinha`
(`apps/frontend/src/lib/use-chamada.ts`) só lê o corpo no ramo de erro — no `201` o stream fica
intacto. O helper `idDaResposta` do spec ficava pendurado ali. **149 dos 150 s eram uma única linha, e
era minha.** O teste 1 passava porque `registrarChamada` faz `await ra.json()` no cliente, drenando o
corpo.

Dois agravantes esconderam isso por cinco ciclos: o `await` estava dentro de um `try/catch` silencioso,
e o erro final apontava sempre o passo *seguinte* (`page.reload`), a 149 s de distância da causa.

**O comportamento real, medido por sonda não-bloqueante** lendo o DOM cru em t+0 ms, t+1,5 s e t+5 s
depois do 201 — nas três leituras:

    data-estado="ausente" · pressed=[presente-plenario=false, presente-remoto=false, ausente=true]

A atualização otimista chega à tela **na hora**. É verdade que `marcarLinha` é a única das 4 mutações
do hook sem `recarregar()` no sucesso — mas isso é **desenho, não defeito**: ela aplica `linhasOtimistas`
com rollback exato, e o SSE reconcilia logo atrás. O teste passou a **afirmar** esse comportamento, com
teto curto de 5 s de propósito, para que a re-hidratação periódica de 30 s não "salve" a asserção e o
teste passe a provar a coisa errada.

**E5 fechou 12/12, zero fixme.**

## Achado de método que esta fase pagou caro para aprender

**Um timeout de teste aponta onde o relógio acabou, nunca onde o tempo foi gasto** — e aumentar o
timeout (90 s → 150 s) **afasta** a medição em vez de aproximá-la. Um cronômetro por passo custou uma
corrida e derrubou um "defeito de produto" que já estava escrito como conclusão em três lugares do
spec e em uma seção deste ledger.

É a terceira vez nesta frente que o instrumento mede a si mesmo, e a terceira raiz diferente:
Fase 10 (a sonda medindo o próprio fixture), Fase 11 (o `goto` esperando `load` com SSE aberto), e
agora o `json()` esperando um corpo que ninguém iria drenar. As três tinham a mesma assinatura — um
resultado estável e plausível que eu quase publiquei como fato do produto.

## Defeito de teste achado ao destravar, e que valia um voto errado

`getByRole("button", { name: "Deferir" })` casa **por substring** e resolvia dois elementos: "Deferir" e
"Ind**eferir**", lado a lado no mesmo bloco (`chamada/page.tsx:650-666`). O strict mode do Playwright
pegou. **Num teste menos estrito, o clique em "Deferir" poderia ter virado indeferir em silêncio** —
e a asserção seguinte ainda passaria, porque as duas decisões mudam o mesmo campo. Corrigido com
`exact: true` nos dois locators.

## Carry aberto — risco latente nos outros 7 specs

`grep` confirma: E1, E3, E6 e outros fazem `await resposta.json()` sobre respostas de
`page.waitForResponse` **sem teto**. Estão verdes hoje só porque, nesses casos, o código da página
consome o corpo. **Qualquer hook novo que deixe de ler o corpo de uma resposta de sucesso reproduz o
travamento** — com o erro apontando o lugar errado, como aconteceu aqui. A correção, se o Daouda
quiser, é promover o `idDaResposta` com teto a helper compartilhado dos 8 specs.

## A suíte da T3 fecha VERDE

Corrida cheia, os 8 specs na ordem de dependência, `preparar.sh` antes de cada um:

| Grupo | Verdes | fixme |
|---|---|---|
| E1 Cadastros | 16 | 2 |
| E2 Expediente | 8 | 0 |
| E3 Matéria | 10 | 0 |
| E4 Parecer | 9 | 1 |
| E5 Chamada | 12 | 0 |
| E6 Votar | 3 | 2 |
| E7 Pós-aprovação | 7 | 0 |
| E8 Notificações | 2 | 1 |
| **Total** | **67** | **6** |

**Duas correções de fixture que a corrida cheia destapou** (e que rodar spec a spec escondia): as
notificações de fixture do E8 ficavam lidas da corrida anterior e o INSERT idempotente não as
restaurava — `fixtures.sql` passou a **resetá-las**; e o E3 escolhia o alvo "sem texto" por **índice
fixo**, sendo que a ordem de `GET /proposicoes` não é estável entre corridas — passou a escolher por
propriedade (`lockVersion === 0` ⟺ sem versão de texto, conferido por SQL).

---

# T3-B CONSERTADO — e o teste que documentava o defeito reprovou, como devia

O achado **T3-B** (os hooks de escrita que nunca re-armam `vivoRef`) não ficou só registrado: foi
fechado, com o gate que impede a volta.

**O conserto:** 19 hooks de escrita passaram de
`useEffect(() => () => { vivoRef.current = false; }, [])` para o mesmo padrão que os hooks de leitura
já usavam — armar no mount, desarmar no cleanup. **Auditoria depois: 27 hooks armam, nenhum desarma
sem armar.**

**O gate estrutural:** `apps/frontend/src/lib/vivo-ref-lint.test.ts`. É lint e não teste de
comportamento **de propósito** — o defeito é uma *omissão* que se repete a cada hook novo, e um teste
por hook seria esquecido exatamente do mesmo jeito que o `vivoRef.current = true` foi. O gate foi
**provado capaz de reprovar**: com a linha removida de um hook, ele falha **nomeando o arquivo**
(`use-marcar-lida.ts`) — vermelho que aponta o defeito, não vermelho genérico. Traz também uma guarda
contra virar vácuo: afirma que inspecionou mais de 20 arquivos, para o caso de uma renomeação de pasta
esvaziar o glob e deixar o teste passar sem olhar nada.

**A prova de que o conserto é real veio de graça, e é o melhor pedaço desta fase.** O teste
`criar mandato — mandato sobreposto (409)` do E1 **nasceu afirmando o defeito**: 1,5 s depois do 409, o
botão preso em "Salvando…" e zero alerta. Na primeira corrida cheia depois de mexer no hook, **ele
reprovou** — porque o alerta passou a aparecer. É a armadilha que este projeto já tinha registrado
(`oplenario-teste-que-afirma-o-vazamento`): **teste que exige o defeito trava o conserto**. Quem o
escreveu previu o momento e deixou, no próprio corpo do teste, a instrução do que trocar no dia do
conserto. Trocado: o teste virou de *vermelho-que-documenta* para *verde-que-prende*.

## Estado final da Trilha 3

| Gate | Resultado |
|---|---|
| **T3, corrida cheia 1** | **67 passou · 0 falhou · 6 fixme** |
| **T3, corrida cheia 2** | **67 passou · 0 falhou · 6 fixme** |
| Suíte unitária do frontend | **137 arquivos · 1132 testes · 0 falhas** |
| Lint estrutural do `vivoRef` | verde, e **provado capaz de reprovar** |

Duas corridas cheias consecutivas, 100% verdes — o critério de pronto que o plano pedia para a T1.3 e
que a Fase 10 não conseguia cumprir por causa do conflito estrutural.

