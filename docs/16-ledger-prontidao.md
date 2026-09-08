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
