# 21 — Runbook da apresentação guiada (deploy em produção)

> **Para quem vai demonstrar O Plenário a um cliente no ambiente hospedado.**
> URL única da demo: **https://oplenario.calvetec.com.br/**
>
> Não há stack para subir, nem `docker`, nem reset: a Casa **já está de pé e semeada** nesse endereço.
> Este runbook é focado no **deploy atual** — para o runbook do stack local (docker/localhost) veja
> `docs/19`, que **não** se aplica aqui.
>
> **Atualizado em 21/09/2026.** Os dados vivos da §2 foram colhidos nesse dia, logando de verdade em
> produção (ver §6). Se a Casa for re-semeada, recolha-os antes da demo — a §2 diz como.

---

## §0 · Leia isto se tiver 2 minutos

| | |
|---|---|
| **Onde** | https://oplenario.calvetec.com.br/ — sem instalar nada, num navegador |
| **Entrada** | `/entrar/10000000-0000-0000-0000-000000000001?redirect=/proposicoes` → **Entrar** → cai direto no acervo. Sem o `?redirect=`, o login para na **capa** (`/`), que **não é o menu** — veja §1 |
| **Senha de todas as personas** | `Plenario@2026` |
| **Usuário** | é o **UUID** da persona (ver §1). Faça login ANTES do cliente entrar |
| **O roteiro** | 4 atos: servidora · **condução da sessão ao vivo** · pós-aprovação · cidadão |
| **O que há de novo** | agendar/abrir/suspender/encerrar sessão, votação, tribuna com cronômetro e apreciação de veto **agora têm tela** (§3 Atos 2 e 3) |
| **O que NÃO abrir** | §4 — a lista curta do que ainda não tem porta |
| **Regra de ouro** | prepare as abas e o login antes; nunca improvise sobre o que não está pronto: *"está no roadmap de curto prazo"* e siga |

---

## §1 · Acesso e credenciais

**Casa da demo:** Câmara Municipal de Fortaleza · `ente-id` **`10000000-0000-0000-0000-000000000001`**.

**Entrada (as 4 personas nomeadas usam a mesma URL):**

```
https://oplenario.calvetec.com.br/entrar/10000000-0000-0000-0000-000000000001
```

Clique **Entrar** → cai no Keycloak → informe **usuário + senha** → o app roteia pelo papel do token.

> ### ⚠️ Leia isto — o passo que falta na maioria das demos
> **Depois do login padrão você cai na CAPA (`/`)** — a página "Onde a câmara acontece / Front-end em
> construção", com os botões *Entrar na sua Câmara* / *Status*. **Essa capa NÃO é o menu do sistema** e
> não leva a lugar nenhum útil: o front interno não tem um "home" com botões; você entra nas telas pela
> **URL direta**. A barra de navegação interna (Proposições, Tramitação, Agendar sessão…) só aparece
> *dentro* de uma tela interna.
>
> **Duas formas de não cair no beco da capa:**
> 1. **(Recomendado) Entre já com `?redirect=`** — o login cai direto na primeira tela. Validado em
>    produção: entrar por
>    `…/entrar/10000000-0000-0000-0000-000000000001?redirect=/proposicoes` **cai direto no acervo**, com
>    a barra de navegação. Troque `/proposicoes` pelo caminho que quiser abrir primeiro.
> 2. Se caiu na capa, **cole a primeira URL interna** na barra de endereço (ex.: `…/proposicoes`). A
>    partir daí a barra de navegação carrega você pelo resto.

**Índice de URLs do roteiro** (cole na barra; `…` = `https://oplenario.calvetec.com.br`). As telas de
sessão e a de pós-aprovação **não** estão na barra de navegação — chegue nelas por estas URLs:

| Ato | Tela | URL |
|---|---|---|
| entrada | Login já no acervo | `…/entrar/10000000-0000-0000-0000-000000000001?redirect=/proposicoes` |
| 1 | Acervo de proposições | `…/proposicoes` |
| 1 | Expediente (gerar documento) | `…/expediente` |
| 1 | Agendar sessão | `…/agendar-sessao` |
| 1 | Pauta / convocação | `…/pauta-convocacao` |
| 1 | Chamada (sessão encerrada) | `…/sessoes/10000000-0000-0000-0000-000000000210/chamada` |
| 1 | Folha (sessão encerrada) | `…/sessoes/10000000-0000-0000-0000-000000000210/folha` |
| 2 | **Comando da Mesa** (sessão aberta) | `…/sessoes/10000000-0000-0000-0000-000000000211/conduzir` |
| 2 | Telão ao vivo | `…/sessoes/10000000-0000-0000-0000-000000000211/plenario` |
| 2 | Home do vereador (janela do vereador) | `…/vereador` |
| 2 | Voto (janela do vereador) | `…/votar` |
| 2 | Painel da Mesa (compliance/TCE) | `…/paineis/mesa` |
| 3 | Pós-aprovação (sancionada) | `…/pos-aprovacao/f22f64d6-3a2a-418d-b0f5-ee652280205f` |
| 4 | Portal público (janela anônima) | `…/portal/casa/10000000-0000-0000-0000-000000000001` |
| 4 | Status da plataforma | `…/status` |

### As personas

| Persona | Papel | Usuário (login Keycloak) | Serve para |
|---|---|---|---|
| **Secretária da Mesa** (Marina Alencar Freire) | `secretario` | `585e6532-e754-4d45-ad82-1667fdfb220e` | **Atos 1, 2 e 3.** É a persona com mais superfície |
| **Vereador(a)** | `vereador` | `222dc995-c188-45f4-a9ce-01bef661c05d` | A home do vereador (`/vereador`) e o cockpit de voto (`/votar`) no Ato 2 |
| **Cidadão** | — (sem login) | — | Ato 4: o portal público é anônimo |

- **Senha de todas:** `Plenario@2026` (fixture pública de demonstração — não é segredo de produção).
- **A tela do Keycloak aparece em inglês e mostra o UUID cru** (`Sign in to ente-10000000-…`). **Faça
  o login antes de o cliente estar olhando** — é a primeira tela depois de clicar Entrar.
- ⚠️ **Os usuários (UUIDs) mudam se a Casa for re-semeada.** O UUID da secretária acima foi confirmado
  logando em produção em 21/09/2026. Se o login falhar, a Casa foi re-semeada: pegue os UUIDs novos no
  cartão **"Ids da demo"** da rodada mais recente do workflow **`semear-hml`** (Actions) e atualize aqui.
  O usuário do vereador vem do smoke `fumaca-hml`; se `/votar` não reconhecer a persona, troque pelo
  UUID novo do mesmo cartão.

> **Prepare 2 janelas/perfis antes:** uma logada como **secretária** (Atos 1–3), outra como **vereador**
> (o `/votar` do Ato 2), e uma **janela anônima** para o Ato 4. Trocar de persona ao vivo custa ~40s de
> silêncio.

---

## §2 · Dados vivos da Casa — snapshot de 21/09/2026

Colhido logando em produção (workflow `demo-dados-producao`, §6). **Recolha antes da demo se a Casa foi
re-semeada.** Note a distinção de estabilidade:

- **IDs estáveis** (o padrão `10000000-…`): o ente, a sessão legislativa e as **3 sessões**. Pode
  confiar nestes literais entre re-semeaduras.
- **IDs voláteis** (UUIDs aleatórios): **proposições** e **personas** — regenerados a cada re-semeadura.
  Onde puder, navegue pela **lista** (`/proposicoes`) em vez de colar UUID.

### Sessões (3) — IDs estáveis

| Estado | ID da sessão | Tipo / nº | Data | Use para |
|---|---|---|---|---|
| **ABERTA** | `10000000-0000-0000-0000-000000000211` | ordinária nº 2 | 21/09 | **Condução ao vivo** (Ato 2): comando da Mesa, votação, tribuna, telão, chamada |
| **AGENDADA** | `10000000-0000-0000-0000-000000000212` | ordinária nº 3 | 28/09 | mostrar uma sessão futura / a pauta convocada |
| **ENCERRADA** | `10000000-0000-0000-0000-000000000210` | ordinária nº 1 | 14/09 | **Folha da sessão** (Ato 1): a ata congela só com a sessão encerrada |

Sessão legislativa: `10000000-0000-0000-0000-000000000201` (as 3 sessões pertencem a ela).

### Proposições (24) — navegue pela lista `/proposicoes`

Distribuição por estado: **aprovada 6 · em_comissoes 4 · aguardando_pauta 4 · protocolada 4 ·
em_pauta 3 · arquivada 3**. Exemplos (UUID **volátil** — confira na lista se re-semeada):

| Estado | Exemplo | ID (snapshot 21/09) |
|---|---|---|
| aprovada | PL 2026/14 — "acessibilidade em prédios públicos municipais" | `f22f64d6-3a2a-418d-b0f5-ee652280205f` |
| em_pauta | PEC-LOM 2026/1 — "composição da Mesa Diretora" | `09812b6c-8c78-4dc6-89fd-c49478537f61` |
| aguardando_pauta | PDL 2026/1 — "Diploma de Honra ao Mérito" | `f718d9e2-41f8-4349-aab7-f42cd0dc0881` |
| em_comissoes | Moção 2026/1 — "congratulações à comunidade escolar" | `fe389e4f-2134-46ff-a6f5-24136436ae4e` |
| protocolada | Requerimento 2026/1 — "informações sobre obras" | `945aeab6-1640-4edf-b444-948ae24cdd78` |

### Pós-aprovação — matérias com autógrafo (Ato 3)

Rota: `/pos-aprovacao/<id-da-proposição>` (sem link interno — cole a URL). Todas com autógrafo emitido:

| Matéria | Estado da tramitação executiva | ID (snapshot 21/09) |
|---|---|---|
| PL — "acessibilidade em prédios públicos" | **sancionado** (desfecho completo) | `f22f64d6-3a2a-418d-b0f5-ee652280205f` |
| PL — "Código Municipal de Defesa do Consumidor" | **aguardando** o Executivo (fluxo interativo aberto) | `5cfa8652-c0c6-44ad-ac96-bf0c2f053c5c` |
| PL — "Combate ao Desperdício" | sancionado | `506ba0b0-ed09-411c-ae08-305c5ad558b2` |
| PL — "multa por descarte irregular de resíduos" | sancionado | `89d385ae-e1ed-452e-9269-3c0c73e7a633` |

> **Não há matéria vetada no snapshot atual.** O card "Apreciação do veto" (novo) só aparece quando a
> tramitação está `vetado` — ver o fluxo opcional do Ato 3 para produzir esse estado ao vivo.

---

## §3 · O roteiro (~30 min)

Quatro atos, um por pergunta que o cliente faz. Entrada e senha na §1. **Aqueça cada tela uma vez antes**
(a primeira visita compila/carrega; não deixe o cliente esperar).

### Ato 1 · A servidora (8 min) — *"isto me poupa trabalho"*

Logada como **secretária**.

| # | Faça | Diga |
|---|---|---|
| 1 | **Proposições** (`/proposicoes`) | "O acervo da Casa: 24 matérias, filtráveis por espécie e estado." |
| 2 | **Expediente** → protocole um documento, ou abra a ficha de uma matéria | "Numeração, ano e URN LexML automáticos — a servidora não digita protocolo." |
| 3 | **Agendar sessão** (`/agendar-sessao`) *(novo)* | "A servidora agenda a próxima sessão pela tela: escolhe a legislatura, o tipo e a data. Antes isso só existia por API." — **preencha, mas confirme só se quiser criar de fato uma 4ª sessão** |
| 4 | **Pauta** (`/pauta-convocacao`) | "A pauta e a convocação derivada da sessão agendada." — é leitura; não tente reordenar |
| 5 | Chamada da sessão **encerrada**: `/sessoes/10000000-0000-0000-0000-000000000210/chamada` | **Ponto alto.** "Todos presentes" em um clique, corrija um para Ausente, justifique e defira |
| 6 | Folha da sessão **encerrada**: `/sessoes/10000000-0000-0000-0000-000000000210/folha` | "A ata de presença sai congelada, HTML e PDF, com hash de integridade." — **use a ENCERRADA (`…0210`)**, nunca a aberta: a folha só congela com a sessão encerrada (a aberta devolve 409) |

### Ato 2 · A condução da sessão ao vivo (12 min) — *"isto me faz parecer bem"* · **o coração da demo**

Esta é a novidade desta rodada: o **Comando da Mesa** conduz a sessão inteira pela tela. Logada como
**secretária**; tenha o `/votar` do **vereador** pronto em outra janela.

Cockpit: `https://oplenario.calvetec.com.br/sessoes/10000000-0000-0000-0000-000000000211/conduzir`

| # | Faça | Diga |
|---|---|---|
| 1 | Abra o **Comando da Mesa** (URL acima; a sessão `…0211` está **aberta**) | "Este é o console que conduz a sessão. O telão *mostra*; aqui a Mesa *opera*." |
| 2 | Aponte o **estado da sessão** e os atos disponíveis (Suspender / Encerrar) | "A Mesa suspende, reabre e encerra a sessão daqui — cada ato pede confirmação e registra quem fez." — **pode suspender e reabrir para mostrar; deixe a sessão ABERTA ao final** |
| 3 | **Painel de Votação** → abrir uma votação de um item da pauta | "A votação é aberta pela Mesa: objeto, modalidade e quórum." — abra; **não encerre com quórum qualificado** (ver §4) |
| 4 | **Painel de Tribuna** → **Chamar à tribuna** um inscrito → o **cronômetro** dispara | "A Mesa chama o orador e o cronômetro corre ao vivo: pausar, +1 min, aparte, encerrar a fala." |
| 5 | Telão em outra aba: `/sessoes/10000000-0000-0000-0000-000000000211/plenario` | "O telão da sessão, ao vivo por SSE: quórum, tribuna e placar." |
| 6 | Na janela do **vereador**, abra a home dele: `/vereador` | "Do lado do vereador: a home mostra 'sessão em andamento' e o leva direto à votação." |
| 7 | Ainda como vereador, `/votar` → registre o voto | "E o vereador vota do próprio celular." Mostre o telão/placar refletindo |

> **Escreve na Casa.** Este ato faz transições reais (abrir votação, iniciar fala, votar). É uma demo —
> tudo bem; só saiba que o estado muda no ambiente compartilhado. Se for reapresentar, veja a §5.

### Ato 3 · Pós-aprovação e sanção (6 min) — *"isto fecha o ciclo"*

Logada como **secretária**. Rota por URL (sem link interno): `/pos-aprovacao/<id-da-proposição>`.

| # | Faça | Diga |
|---|---|---|
| 1 | `/pos-aprovacao/f22f64d6-3a2a-418d-b0f5-ee652280205f` (matéria **sancionada**) | "Depois de aprovada, a matéria vira autógrafo ao Executivo. Aqui: o autógrafo, o pipeline 'Etapas da sanção' e o desfecho — sancionada." |
| 2 | *(opcional, interativo)* `/pos-aprovacao/5cfa8652-c0c6-44ad-ac96-bf0c2f053c5c` (**aguardando** o Executivo) | "Quando o Executivo responde, a secretária registra aqui: sanção, veto total ou parcial." — botão **Registrar retorno** |
| 3 | *(opcional, avançado — **escreve**)* No passo 2, registre o retorno como **veto** → aparece o card **Apreciação do veto**, botão **Apreciar o veto** *(novo)* | "Se vier veto, a Câmara aprecia: mantém ou derruba, referenciando a votação do plenário." — só faça se quiser demonstrar o fluxo completo de veto |

> **Card "Apreciação do veto":** é a entrega mais nova (a Câmara mantém/derruba o veto pela tela). Ele só
> aparece com a tramitação no estado `vetado` — e não há matéria vetada no snapshot atual. Para mostrá-lo
> ao vivo, use o passo 3 (registra um veto de verdade na matéria `5cfa8652…`, consumindo-a). Sem isso,
> descreva-o como capacidade e siga.

### Ato 4 · O cidadão (4 min) — *"isto me dá votos"*

**Sem login.** Janela anônima — mostrar que não exige cadastro é parte do argumento.

| # | Faça | Diga |
|---|---|---|
| 1 | `/portal/casa/10000000-0000-0000-0000-000000000001` | "O portal do cidadão. Nada aqui pede login." |
| 2 | Abra uma matéria em destaque | "A ficha completa, com o texto integral." |
| 3 | Abra o perfil público de um vereador | "Cada vereador tem página com presença e histórico." |
| 4 | Balcão e-SIC na capa → acompanhe um protocolo | "Pedido de informação com acompanhamento por número, sem cadastro." |
| 5 | `/status` | "Status público da própria plataforma — transparência sobre o sistema." |

**Não navegue além disso no portal** — as rotas públicas são 4 (capa, matéria, vereador, status) mais o
balcão de acompanhamento.

### Encerramento (2 min)

As três apostas, na ordem em que o cliente as viveu: **experiência de produto** (o que ele acabou de
ver), **confiança operacional** (o painel do TCE-CE em `/paineis/mesa`) e **IA como copiloto
legislativo** — esta última como visão, porque a Track IA ainda não tem código. Não a demonstre.

---

## §4 · O que ainda NÃO abrir / não prometer

O que a §1.2 do `docs/19` listava como ausente e **agora existe** (construído nesta rodada, confirmado em
produção): **agendar sessão**, **abrir/suspender/encerrar sessão**, **abrir/encerrar votação**, **tribuna
(chamar orador + cronômetro + encerrar fala)** e **apreciação de veto**. Já pode demonstrar tudo isso.

O que **continua** sem porta de cliente — não abra, não clique, não prometa:

| O quê | Por quê |
|---|---|
| **`/paineis/mesa` como vereador/presidente** | 403. `/paineis/*` exige papel `secretario`. Apresente o dashboard **como a secretária** |
| **Promulgar / publicar norma** | Sem rota HTTP; e a publicação depende do conector do Diário Oficial (diferido). A matéria sancionada não vira lei publicada por caminho de cliente |
| **Encerrar votação com quórum qualificado** (maioria absoluta) | O denominador ainda chega no corpo da requisição — não demonstre encerramento com quórum qualificado |
| **Balcão e-SIC / LGPD / ouvidoria / moderação de comentários pelo cidadão** | O balcão do cidadão é API pura, sem tela. A **secretária** tem a tela de **Moderação**; o resto responde-se por API |
| **Botão "gov.br Entrar"** no portal público | Está visível mas **morto** (`href="#"`). Não clique nem deixe o cliente clicar |
| **Console do operador** (supratenant) | Zero rotas — não existe |
| **Gerar remessa ao TCE** | Sem rota de cliente |

⚠️ **Se um cliente técnico perguntar** (não invente resposta):
- *"Só vereador em exercício vota?"* — **Pelo celular do vereador (`/votar`), sim**: a rota resolve o
  vereador do próprio autenticado e valida, sob lock, mandato vigente + presença. **Lacuna:** a entrada
  nominal pela Mesa aceita `vereador-id` do corpo sem checar o roster, e a FK do voto ainda é só de
  schema. Diga isso — não "hoje não".
- *"Como o quórum de maioria absoluta é calculado?"* — o denominador chega no corpo hoje; correção é
  prioridade do backlog. Por isso não se demonstra encerramento com quórum qualificado.

---

## §5 · Se algo travar ao vivo

É um ambiente hospedado — não há `docker` para reiniciar. Os recursos práticos:

| Sintoma | O que fazer |
|---|---|
| Uma tela demora / vem em branco no 1º acesso | Recarregue. A primeira visita a cada rota é a mais lenta — **aqueça antes** |
| "Acesso restrito" logo após o login | O papel do token não bate com a tela. Confirme que está na persona certa (secretária para o interno/Mesa) |
| Telão sem a animação dos eventos passados | O replay do canal SSE retém ~5 min; quórum e tribuna re-hidratam do banco, a *animação* dos eventos antigos não. Reabra o telão; se precisar, conduza um evento novo pelo Comando da Mesa |
| A sessão da demo saiu do estado esperado (alguém conduziu antes) | Recolha o estado atual (§6) e ajuste o roteiro; ou agende/abra uma sessão nova pelo próprio Comando da Mesa |
| Login falha para todas as personas | A Casa foi re-semeada e os UUIDs mudaram — pegue os novos no cartão "Ids da demo" do `semear-hml` (§1) |
| A plataforma está fora | Confira `https://oplenario.calvetec.com.br/status` |

**Nunca** rode carga de teste ou reset contra este ambiente durante/antes de uma demo com cliente.

---

## §6 · Como atualizar este runbook (dados vivos)

Os dados da §2 saem de um coletor **só-leitura** que loga em produção como a secretária e imprime um
snapshot (sessões + estados, sessão legislativa, proposições por estado, matérias com autógrafo). Rode-o
na véspera da demo:

- **GitHub → Actions → "Dados da demo (producao, so-leitura)"** (`demo-dados-producao.yaml`) →
  *Run workflow*. Leia o bloco `===== SNAPSHOT =====` no log e atualize a §2.
- É **só-leitura**: não escreve nada em produção.
- Smokes irmãos, também só-leitura, que confirmam que as telas carregam vivas:
  `fumaca-conducao` (Agendar + Comando da Mesa + painéis Votação/Tribuna) e
  `fumaca-pos-aprovacao` (a tela de pós-aprovação).

### Validação de ponta a ponta (21/09/2026)

**Todas as 21 telas do roteiro foram navegadas em produção**, logando de verdade como cada persona
(secretária, vereador, anônimo), e **renderizaram** — nenhuma deu "Acesso restrito" nem erro. Confirmado
nesse dia:

- **Deep-link:** entrar com `?redirect=/proposicoes` cai **direto** no acervo (não passa pela capa).
- **Secretária:** `/proposicoes`, `/tramitacao`, `/expediente`, `/pauta-convocacao`, `/agendar-sessao`,
  `/paineis/mesa` (mostra "1 obrigação venceu o prazo no TCE-CE" — o argumento de compliance), `/calendario`,
  `/moderacao`, `/cadastros/vereadores`, a chamada e a folha da sessão encerrada, o Comando da Mesa e o
  telão da sessão aberta, e as duas telas de `/pos-aprovacao` — todas OK.
- **Vereador:** a home `/vereador` abre ("Sua home" · "Sessão em andamento") e o `/votar` abre o "Cockpit
  de votação" com uma **votação aberta ao vivo** (PL 7/2026). Ambas validadas ao vivo como o vereador.
- **Anônimo:** o portal (`/portal/casa/<ente>`) e `/status` abrem sem login.
- **Nota sobre caminhos:** a tela do vereador é **`/vereador`** — `/meu/painel` é a **rota da API
  (backend)**, não uma página de navegador (digitá-la dá 404; é esperado, não é a tela). Idem
  notificações do vereador: a rota é `/notificacoes`, não `/vereador/notificacoes`.

### Procedência (o que foi confirmado em produção em 21/09/2026)

- **Login da secretária, listagem de sessões e proposições, e a tela `/pos-aprovacao`**: confirmados
  logando de verdade em produção (o snapshot da §2 e o smoke `fumaca-pos-aprovacao`, ambos verdes).
- **Agendar sessão + Comando da Mesa + painéis de Votação e Tribuna**: confirmados renderizando em
  produção pelo smoke `fumaca-conducao` (a sessão `…0211` está aberta, então os painéis aparecem).
- **Card "Apreciação do veto"**: a rota que o hospeda carrega viva; o card em si não foi exercitado ao
  vivo (não há matéria vetada no seed) — sua lógica está coberta por testes de unidade. Ver Ato 3.
- **IDs das personas** são voláteis por re-semeadura; o UUID da secretária foi validado em 21/09. O do
  vereador vem do smoke `fumaca-hml` — reconfirme antes de uma demo importante.
