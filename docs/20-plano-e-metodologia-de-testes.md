# Plano e metodologia de testes — O Plenário

> **Objetivo:** ter certeza de que **cada fluxo da plataforma funciona como se espera**, com um
> método repetível, priorizado e honesto sobre o que já está coberto e o que não está. Este documento
> é o **plano** (o que testar, em que ordem) e a **metodologia** (como testar, como registrar, o que
> conta como "pronto"). A execução vive no ledger `docs/16` e em suítes de teste versionadas.
>
> Criado em 15/09/2026. É frente de trabalho viva — cada onda executada volta como entrada aqui.

---

## §0 · Leia isto primeiro (o estado honesto da cobertura)

A plataforma é grande: **13 módulos, 61 migrations, 113 features, ~113 rotas HTTP, ~30 rotas de
frontend, 3 públicos decisores.** Testar "tudo" sem um mapa é ilusão de cobertura. O ponto de partida
real, medido em 15/09:

| Camada | Cobertura hoje | Leitura |
|---|---|---|
| **Backend (lógica + API + policy)** | **FORTE** — 2352 testes / 6341 asserções (86 unit + 169 integração + 3 keycloak), **verdes no CI** (runner independente do GitHub, run #6) | Regressão de lógica de domínio está bem guardada. Os gates de arquitetura (import-lint, leak 3-dim, authz) falham o build. |
| **Fluxos no browser (o que o usuário vive)** | **FRACO** — 2 specs Playwright (`e2e/portal-cidadao.spec.ts`, `e2e/smoke.spec.ts`), 3 casos, só o Portal do Cidadão | As ~30 telas dos 3 públicos **não têm suíte de browser repetível**. É o maior buraco. |
| **Jornadas exploratórias** | **PARCIAL e não-repetível** — 3 jornadas em `docs/16` (servidora, presidente, cidadão), feitas 1× com dev-token e agentes | Achou 10 críticos + ~50 achados, mas caminhos infelizes / desfazer / 2º tenant / mobile / tema escuro **nunca foram varridos** (ledger §A). |
| **Auth real (PKCE Keycloak / gov.br)** | **NUNCA exercitada ponta a ponta no browser** | As 3 jornadas rodaram em `APP_ENV=dev` (token JSON). O login real é a única perna que nenhum método tocou. |

**Conclusão que orienta o plano:** não é preciso re-testar a lógica de backend do zero — ela tem rede.
O trabalho é **cobrir os fluxos como o usuário os vive, em browser, por público, no caminho feliz E no
infeliz**, e transformar as jornadas manuais de `docs/16` em suítes **repetíveis** (que rodam no CI e não
dependem de um humano lembrar de olhar).

---

## §1 · Os quatro métodos de teste (e quando usar cada um)

Este projeto tem uma pirâmide real. Cada método pega uma classe diferente de defeito; nenhum substitui
o outro.

| # | Método | Pega | Onde roda | Custo | Repetível? |
|---|---|---|---|---|---|
| **M1** | **Rastreamento estático** (FE→rota `/api`→handler→DB, com evidência `arquivo:linha`) | Fiação quebrada, rota inexistente, botão `disabled` que o roteiro manda clicar, afirmação de doc desatualizada | Qualquer lugar (não precisa da stack) | Baixo | Sim (é leitura de código) |
| **M2** | **Suíte backend** (kaocha: unit + integração contra PG/MinIO/Valkey/Keycloak reais) | Regressão de lógica de domínio, quórum, policy, projeção, leak cross-tenant | CI (verde) e local | Médio | Sim (CI a cada push) |
| **M3** | **Browser-e2e** (Playwright contra a stack de pé) | O que só aparece na tela: rótulo cru, tela em branco, hidratação, SSE, estado que só chega depois do fetch | Stack de pé (local ou CI com job de browser) | Alto | **Sim — é o que este plano mais quer expandir** |
| **M4** | **Jornada exploratória** (o método de `docs/16`: um público por vez, sequência real de atos, **3 lentes de refutação** — instrumento / já-conhecido / o-código-faz-isso) | O inesperado, a incoerência entre telas, o caminho infeliz que ninguém desenhou | Stack de pé + operador (humano ou agente) | Alto | Não (por natureza) — mas **seus achados viram specs M3/M2** |

**Regra de ouro:** um achado de M4 (exploração) só está *fechado* quando vira **M3 (spec de browser)** ou
**M2 (teste de backend)** — senão a regressão volta e ninguém vê. M4 descobre; M2/M3 guardam.

**Disciplina de refutação (herdada de `docs/16`, obrigatória em M4):** todo achado passa por 3 lentes
antes de entrar no ledger — (a) *é o instrumento se medindo?* (b) *já é conhecido/decidido?* (c) *o
código realmente faz isso?* (reproduzir direto no endpoint/tela). Sobrevive quem tem < 2 refutações.
**Nenhuma linha entra sem reprodução.**

**Classes de severidade (de `docs/16`):** `MATA` (não dá para apresentar / corromper dado) · `CONSTRANGE`
(dá, com desculpa) · `PASSA` (ninguém nota). E o eixo de natureza: **DEFEITO** (código faz errado) ·
**LACUNA/BURACO** (nunca foi construído) · **DADO/ATRITO** (código certo, semente ou UX não sustenta).

---

## §2 · Método "subir e operar" — o protocolo executável

O método M3/M4 exige a stack de pé. Onde ela sobe, e como operar os testes.

### §2.1 · Onde a stack roda (e onde NÃO roda)

| Ambiente | Sobe a stack? | Uso |
|---|---|---|
| **Máquina do Daouda** (Mac, egresso livre) | ✅ | M3/M4 completos, inclusive auth PKCE real. É a fonte de verdade da demo. |
| **CI (GitHub Actions)** | ✅ para backend (M2, verde); **precisa de um job novo para browser-e2e (M3)** | O caminho **repetível e independente** — recomendado para M3. Ver §2.4. |
| **Este ambiente remoto (sandbox do agente)** | ❌ | Egresso de imagens Docker (Docker Hub/quay) e Clojars **bloqueado por política**. Só M1 (estático) roda aqui. |

**Recomendação:** M3 (browser-e2e) deve virar um **job de CI** — assim cada push prova os fluxos de tela,
não só a lógica. O harness `e2e/` já existe (`./e2e/rodar.sh`); falta o job. É a maior alavanca de
cobertura repetível do projeto.

### §2.2 · Subir a stack (resumo — detalhe em `docs/19` §2)

```bash
cd apps/backend
OPLENARIO_APP_ENV=production docker compose --profile auth up -d --build   # com Keycloak (auth real)
# ... espere: app /saude=200 (~31s), Keycloak (~72s), frontend healthy (~49s)
cd .. && ./demo/semear-tudo.sh && ./demo/semear-credenciais.sh
```

> ⚠️ **Pré-requisito de imagem (novo, 15/09):** `docker pull quay.io/minio/minio` precisa suceder — o
> `minio/*` foi removido do Docker Hub e o compose já aponta para quay. Verifique antes do dia D.

### §2.3 · Operar os testes por fluxo (M3/M4)

1. **Preparar as personas** (uma janela/perfil por público — trocar ao vivo custa silêncio):
   servidora `secretario`, Mesa `secretario`, vereador `vereador`, cidadão anônimo. Usernames em
   `e2e/.artifacts/credenciais.edn` (mudam a cada `down -v`).
2. **Dirigir cada fluxo** pela tela, não pelo curl — quase tudo é Client Component que busca *depois* da
   hidratação; `curl 200` não prova tela. Playwright é o motorista repetível; o humano é o motorista
   exploratório.
3. **Para cada fluxo:** exercer caminho feliz → caminho infeliz (entrada inválida, estado errado, ordem
   trocada, desfazer) → registrar achado com as 3 lentes → virar spec.

### §2.4 · O harness de browser (M3) e o que falta

- **Existe:** `e2e/rodar.sh` — semeia via `seed_demo.clj` em containers efêmeros, espera a projeção do
  relay, roda o Playwright contra a stack de pé. `playwright.config.ts` + `global-setup.ts` prontos.
- **Cobre hoje:** só `portal-cidadao.spec.ts` + `smoke.spec.ts` (Portal do Cidadão).
- **Falta (é o trabalho das ondas abaixo):** specs para os fluxos internos (servidora, Mesa, vereador),
  o telão SSE, e um **job de CI** que rode `./e2e/rodar.sh` (hoje o CI só roda a suíte backend).

---

## §3 · Inventário da superfície testável

A matriz do que existe para testar. Método-alvo por linha; cobertura atual honesta.

### §3.1 · Por módulo de backend (rotas HTTP)

| Módulo | Rotas | Cobertura M2 (backend) | Fluxo de usuário associado |
|---|---|---|---|
| `legislativo` | 29 | Forte (59 deftests) | Protocolar, ficha, tramitação, parecer, votação, autógrafo |
| `sessoes` | 34 | Forte (58) | Chamada, quórum, tribuna, votação, folha, pauta |
| `participacao` | 21 | Fraca (9) — **o maior descompasso rotas×testes** | e-SIC, LGPD, ouvidoria, comentários, acompanhar |
| `transparencia` | 9 | Média (20) | Portal público: capa, matéria, vereador, legislação |
| `cadastros` | 8 | Média (15) | Vereadores, mandato, licença, conceder acesso |
| `paineis` | 6 | Média (17) | Dashboard da Mesa, tramitação, pendências, SLI |
| `compliance` | 3 | Média (12) | Painel TCE, ciclo de remessa (validar/submeter/resposta) |
| `identidade` | 3 | Média (8) | Sessão, papéis, login |
| `admin_sistema` | 0 | — | Console do operador (**não existe** — item §3 CLAUDE.md) |
| `tempo_real` | (SSE) | Fraca (6) | Telão do plenário, backplane Valkey |

**Sinal claro:** `participacao` (21 rotas, 9 deftests) e `tempo_real` (SSE, 6 deftests) são os módulos com
mais superfície por teste — candidatos a reforço M2, e onde M3/M4 mais provavelmente acham coisa.

### §3.2 · Por persona / papel × fluxo (a régua da experiência)

> ⚠️ **Os "3 atos" do runbook `docs/19` são a lente da DEMO** (os 3 públicos decisores em licitação:
> servidor, Mesa, cidadão). **Não são o conjunto de personas da plataforma.** Testar "tudo" exige a lista
> completa abaixo — enumerada contra o código (papéis em `identidade`/`kernel/autorizacao`; personas
> semeadas em `demo/casa.clj:106-132`; papéis concedíveis em `identidade/wire/in/acesso.clj:7`).

**Vocabulário real (autoridade):** vínculo (tipo) ∈ `servidor · vereador · cidadao`; papel ∈
`secretario · vereador · admin_ente · admin_sistema · :sistema` (sem CHECK — papel é dado). **Só
`vereador` é concedível pelo produto** (`acesso.clj:7` = `#{"vereador"}`) — dar `secretario`/`admin_ente`
não tem tela nem rota.

| Persona (papel) | É ato da demo? | Fluxos-chave | Método | Cobertura hoje |
|---|---|---|---|---|
| **Servidor / Secretário** (`servidor`+`secretario`) | Ato 1 | protocolar → ficha → tramitação → chamada → folha; parecer; expediente/protocolo; conceder acesso; dashboard/compliance/calendário (as 4 rotas `paineis` exigem `secretario`) | M3+M4 | Jornada 1× (J-servidora); **sem spec** |
| **Vereador** (`vereador`) | Ato 2 (parcial) | cockpit `/votar` (**mobile — é o celular**), notificações, **assinar parecer** (relator), home | M3+M4 | Jornada parcial; **mobile e parecer nunca em spec** |
| **Presidente da Mesa** (`vereador`+`admin_ente`) | (a demo o evita) | conceder/**revogar** acesso; **NÃO abre `paineis/*` (403)** — crítico aberto; abrir/encerrar sessão (só HTTP) | M4→M2 | Jornada 1× achou o 403; **quebrado, não coberto** |
| **admin_ente** (genérico, não-Presidente) | não | administração da Casa; hoje só "conceder acesso" e mesmo assim 403 nos painéis | M2 (matriz authz) | **surface incompleta — item da frente §3.2 CLAUDE.md** |
| **Cidadão anônimo** (sem login) | Ato 3 | capa, matéria, perfil vereador, `/status`, **buscar** protocolo e-SIC | M3 | **Único com specs** (portal-cidadao) — expandir |
| **Cidadão autenticado** (`cidadao` via gov.br) | não | **escrever** e-SIC/LGPD/ouvidoria, comentar, **acompanhar** matéria | M3+M4 | **BLOQUEADO — gov.br não existe** (§3.2 CLAUDE.md); backend pronto, sem porta |
| **Operador supratenant** (`admin_sistema`) | não | console do operador, observabilidade, onboarding de tenant | — | **AUSENTE — `admin_sistema` tem 0 rotas** (stub); testa quando existir |
| **Sub-personas funcionais** | dentro do vereador | **relator de parecer**; **presidente/membro de comissão** (CCJ etc.); **DPO/Encarregado** (resposta LGPD) | M4→M2/M3 | parcial em M2; sem jornada própria |

**Leitura:** a demo cobre 3 (servidor, Mesa-como-secretário, cidadão anônimo). A plataforma tem **~8
personas**. Três estão **quebradas/ausentes** e amarradas às frentes abertas do CLAUDE.md §3 (403 do
admin_ente; gov.br do cidadão autenticado; console do `admin_sistema`) — o plano as **lista e prioriza**
(T3 authz, T4 auth), não as esconde.

---

## §4 · O plano priorizado (ondas de execução)

Ordenado por risco × valor demonstrável. Cada onda tem método, saída esperada e critério de fechamento.
As ondas T1–T3 fecham o buraco de "fluxo como o usuário vive"; T4+ endereçam o que nenhuma jornada tocou.

### Onda T0 — Fundação repetível *(habilita todo o resto)*
- **Fazer:** (a) adicionar um **job de browser-e2e ao CI** (`./e2e/rodar.sh`) para M3 rodar a cada push;
  (b) confirmar o "subir e operar" a frio numa máquina com egresso (o cold-run que `docs/19` §8 ainda deve).
- **Método:** M2/M3 (infra). **Saída:** CI roda backend **e** browser; runbook de execução validado.
- **Fecha quando:** um push dispara os dois e ambos passam.

### Onda T1 — Caminho feliz **por persona** (todas as que têm surface funcionando), em browser repetível
- **Fazer:** um spec Playwright por persona com fluxo real hoje — **não só os 3 atos da demo**:
  (1) **servidor/secretário** (protocolo→ficha→tramitação→chamada→folha); (2) **vereador** — cockpit
  `/votar` **em viewport mobile** + notificações + **assinar parecer** (relator); (3) **cidadão anônimo**
  (portal→matéria→perfil→`/status`→buscar e-SIC). Uma persona por spec, autenticação via o modo do harness
  (§2.4).
- **Fora de T1 por dependência** (vão para T3/T4, não são esquecidas): **presidente/admin_ente** (403 nos
  painéis — conserto antes de testar), **cidadão autenticado** (gov.br ausente), **operador supratenant**
  (`admin_sistema` sem rotas).
- **Método:** M3 (a partir de M4 já feito). **Saída:** 3 specs de persona verdes no CI (as demais personas
  entram quando a surface existir).
- **Fecha quando:** o caminho feliz de cada persona com surface funcionando roda sem operador humano.

### Onda T2 — Caminhos infelizes e a **família do DESFAZER** *(ledger §A — nunca varrida)*
- **Fazer:** anular/corrigir voto; retirar matéria de pauta; votar em sessão/votação já fechada (os 409);
  encerrar a mesma votação de duas abas (`lock-version` obsoleta); **entrada suja** (ementa 10k, emoji,
  `<script>`, CPF inválido, datas invertidas — hoje só se testou forma/schema, nunca conteúdo).
- **Método:** M4 → M3/M2. **Saída:** achados no ledger + specs de regressão.
- **Fecha quando:** cada caminho infeliz tem um teste que prova a recusa (ou vira achado priorizado).

### Onda T3 — Autorização e multi-tenant *(o 403 do presidente é crítico aberto)*
- **Fazer:** **matriz papel × rota** (todo endpoint × cada papel → esperado 200/403); isolamento
  **cross-tenant** (RLS) num 2º ente semeado; o **relay compartilhado** sob evento de outro tenant
  (memória `oplenario-relay-poison`).
- **Método:** M2 (matriz é ideal para suíte) + M4 (2º tenant). **Saída:** suíte de matriz de authz + 1
  jornada num 2º ente.
- **Fecha quando:** a matriz roda no CI e o 2º tenant não vaza.

### Onda T4 — Auth real (PKCE) ponta a ponta *(a perna que ninguém exercitou)*
- **Fazer:** login PKCE via Keycloak no browser, para cada persona; logout; sessão expirada; papel lido
  do banco vs. claim do token (o modo de falha §6.6 do runbook).
- **Método:** M3 (Playwright com `--profile auth`). **Saída:** spec de login real + o `displayName` do
  realm corrigido (hoje mostra o UUID cru — `docs/19` §1.2).
- **Fecha quando:** um cliente pode ver a tela de login sem constrangimento e o fluxo passa no CI.

### Onda T5 — Não-funcionais *(o que "só se vê olhando" — ledger §A)*
- **Fazer:** **tema escuro + AA em pixel composto** (protocolo do próprio projeto); **mobile** (o cockpit
  é o celular do vereador — as jornadas correram em desktop); **SSE cair no meio** da votação; **fuso**
  (Dashboard imprime relógio do navegador, não da Casa — `proxima-sessao-rail.tsx:34`); **links mortos**
  (varredura de todo `href`, ex. o botão gov.br morto); **impressão** (abrir o PDF da folha e o DO-lite).
- **Método:** M3 + inspeção. **Saída:** achados + specs onde couber.

### Onda T6 — Módulos sub-testados a fundo
- **Fazer:** `participacao` (ciclo e-SIC completo incl. **resposta write-only** — crítico; LGPD; ouvidoria;
  moderação de comentário); parecer/assinatura; expediente/protocolo; **ciclo de remessa ao TCE**
  (validar→submeter→resposta e o placar que não se move — crítico de `docs/16`).
- **Método:** M4 → M2/M3. **Saída:** reforço de cobertura nos módulos com pior razão rotas×teste (§3.1).

---

## §5 · Definição de "pronto" por fluxo

Um fluxo está **verificado** quando:
1. O caminho feliz passa por **M3** (browser repetível no CI) ou, se não tiver tela, por **M2**.
2. Pelo menos os caminhos infelizes óbvios (entrada inválida, estado/ordem errados, desfazer) foram
   **tentados** (M4) e cada recusa esperada tem teste, ou virou achado priorizado no ledger.
3. Nenhum achado `MATA` aberto sobre ele. Achados `CONSTRANGE`/`PASSA` estão registrados com severidade.
4. As afirmações do runbook `docs/19` sobre esse fluxo batem com o código (M1).

Um fluxo **não** está pronto só porque "o backend tem teste" — a experiência é parte do "funciona como
se espera" (é uma das 3 apostas da V1: experiência de produto).

---

## §6 · Onde registrar (e não duplicar)

- **Achados de execução:** `docs/16-ledger-prontidao.md` — mesma forma (severidade, natureza, 3 lentes,
  reprodução). É a fonte de verdade dos achados.
- **Este plano (`docs/20`):** o método e a matriz de cobertura; atualizado quando uma onda fecha.
- **Runbook (`docs/19`):** o que a demo pode/não pode tocar; recebe as correções que a execução impõe.
- **Suítes:** M2 em `apps/backend/test/{unit,integration,keycloak}`; M3 em `e2e/*.spec.ts`. **Todo achado
  vira teste** (§1, regra de ouro).
- **Decisão estrutural nova** (ex.: criar `exige-algum-papel!`): ADR em `docs/adr/`.

---

## §7 · Limites e portões conhecidos (honestidade de escopo)

- **Este ambiente de agente não sobe a stack** (egresso bloqueado) — M2 roda pelo CI; M3/M4 exigem
  máquina com egresso ou o job de CI da Onda T0. M1 (estático) roda aqui e já cobriu os 3 atos + a lista
  "⛔" (`docs/16`, re-verificação 15/09).
- **Dois portões antes de demo externa** (independentes deste plano, de `docs/16`): rodar o cold-run 1×
  na máquina de demo; **fechar o denominador forjado do quórum** (`base-membros` vem do corpo — aprovação
  forjada emite autógrafo ao Prefeito). Nenhuma suíte substitui fechar isso.
- **Escopo diferido por default** (CLAUDE.md §4): fluxos de domínio ausente (console do operador,
  transparência fiscal, ata-IA) não se testa porque não existem — entram no plano quando o domínio entrar.

---

## §8 · Próximo passo sugerido

Executar **Onda T0 + T1**: adicionar o job de browser-e2e ao CI e escrever um spec Playwright por
persona com surface funcionando (servidor, vereador, cidadão anônimo). É o que transforma "testei uma
vez à mão" em "o CI prova a cada push" — a maior alavanca de confiança para uma plataforma deste tamanho.
As ondas seguintes atacam o que nenhuma jornada tocou e as personas quebradas/ausentes: T2 caminhos
infelizes + desfazer, T3 authz × papel + multi-tenant (destrava o presidente/admin_ente), T4 auth real
(destrava o cidadão autenticado), T6 sub-personas (relator, comissão, DPO).
