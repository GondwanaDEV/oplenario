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
