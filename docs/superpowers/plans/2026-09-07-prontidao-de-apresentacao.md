# Prontidão de Apresentação — Mapa de Fluxos, Teste Local e Correção

> **Para executores agênticos:** SUB-SKILL OBRIGATÓRIA: usar `superpowers:subagent-driven-development`
> (recomendado) ou `superpowers:executing-plans` para executar tarefa a tarefa. Os passos usam
> caixa (`- [ ]`) para rastreio.

**Objetivo:** que o Daouda consiga subir a plataforma localmente e conduzir uma apresentação
completa — as jornadas dos três públicos decisores, do começo ao fim, clicando, sem `curl`,
sem tela vazia e sem UUID no lugar de nome.

**Arquitetura:** três frentes que não se confundem — **(1) o dado** (uma Casa única, coerente e
re-semeável, em vez das 4 ilhas de hoje), **(2) a costura** (a UI que falta para conduzir a sessão,
hoje só alcançável por `curl`), **(3) a prova** (varredura automática das 27 telas + caminhada
exploratória por jornada, com ledger de defeitos e correção priorizada). Nenhuma linha de domínio
novo: tudo que este plano constrói chama endpoint que já existe e já tem teste.

**Stack:** Clojure/Pedestal + Postgres/Valkey/MinIO + Keycloak · Next.js 16 · Playwright em
container · `seed_demo.clj` como semeador.

**Spec:** este próprio documento. O estado medido da §1 é a especificação de entrada — foi
levantado ao vivo em 07/09/2026 contra a stack de pé, não deduzido do repositório.

## Restrições globais

- **Mandato Docker, sem exceção.** Nunca rodar `node`/`npx`/`clj` direto no host. Backend em
  container efêmero `clojure:temurin-21-tools-deps`; frontend dentro de `oplenario-frontend-1`.
- **Nunca mutar um mount vivo.** O compose monta `../frontend:/app` e `apps/backend` como fonte
  servida. Container efêmero monta `:ro` e mantém cache em volume de container (`CLJ_CACHE=/tmp/cpcache`).
- **A suíte do backend aborta inteira sem os overrides de env** — `DATABASE_URL`, `DB_USER`,
  `DB_PASSWORD`, `VALKEY_URI`, `MINIO_ENDPOINT`. Somar `--skip :keycloak` e **parar `oplenario-app-1`**
  durante o run (advisory lock do `outbox-relay-test`).
- **Nunca rodar a suíte do backend e a do FE ao mesmo tempo** — contenção de CPU produz falha fantasma.
- **Uma branch por frente**, mergeada em `main` com `--no-ff`, branch fechada. TDD por fatia
  (red → green) e revisão `ecc` adversarial antes do merge.
- **Português** em todo artefato, commit e mensagem de tela.
- **Nada de DSL nova.** Regras declarativas reusam o motor compartilhado (Disciplina 5, §22.4.3/§22.5.3).
- **Design de tela exige os dois consultores** (`frontend-design` + `ui-ux-pro-max`) e o
  `GUIDELINES-CHECKLIST.md` como gate, com AA medido em pixel composto, um tema por chamada.

---

## §1 — Estado medido (07/09/2026, stack de pé há 12h)

### 1.1 O que responde

| Componente | Estado |
|---|---|
| 7 containers (app, frontend, keycloak, mailpit, postgres, valkey, minio) | de pé, saudáveis |
| Backend `:8888` · Frontend `:3000` | respondem; portal público devolve matérias reais |
| Migrations | 61 aplicadas; `migrate` roda como serviço do compose |
| Reset | **limpo** — não há volume nomeado; `docker compose down -v` + `up -d --build` re-migra do zero |

### 1.2 O dado — o gargalo real

| Medição | Valor | Leitura |
|---|---|---|
| Entes no banco | **8.121** | aterro de resíduo da suíte; milhares de "Camara Municipal de Fortaleza" com 1 vereador e 1 sessão |
| Sessões / proposições | 17.055 / 46.952 | `entes c/ sessão` = 15.384 **>** 8.121 entes existentes → `ente_id` órfão (sem FK cross-schema, por design da §22.10) |
| **A Casa mais rica de todo o banco** | **7 vereadores · 2 sessões · 8 proposições** | é o **teto** do que existe hoje para demonstrar |
| Presenças na sessão de demo | **14 presenças, 14 pessoas, 0 no cadastro** | os 7 vereadores nominados têm **zero** presença |
| Duplicata | "Ana Ribeiro" **duas vezes**, ids diferentes | sementes `vereadores` e `notificacoes` criam a mesma pessoa |

**As 4 ilhas.** As 12 funções de `seed_demo.clj` produzem **4 Casas isoladas**: o grupo `base`
(9 sementes compartilham um ente), `secretario` (ente próprio), `login-kc` (ente próprio + realm KC
próprio) e `slice5` (ente próprio + realm KC próprio). Quem entra pelo Keycloak cai numa Casa vazia;
quem abre a pauta cai numa terceira. **Nenhum fluxo atravessa duas telas com o mesmo dado.**

### 1.3 A superfície — 27 telas, 121 rotas

**121 rotas HTTP** (sessoes 33 · legislativo 26 · participacao 22 · transparencia 10 · cadastros 9 ·
paineis 7 · identidade 6 · compliance 4 · SSE 1 · host 3 · admin_sistema 0).

**68 são de escrita. Apenas 29 alcançáveis por alguma tela — 39 só por `curl`.**

| Capacidade sem nenhuma tela | Rotas | Efeito na apresentação |
|---|---|---|
| Abrir votação · lançar voto pelo secretário · encerrar e apurar | 3 | **o placar não pode ser produzido pela interface** |
| Agendar sessão · transicionar estado da sessão | 2 | **não se começa uma sessão clicando** |
| Tribuna (inscrever, desistir, iniciar fala, cronômetro, encerrar) | 5 | o telão exibe a tribuna; nada na UI a movimenta |
| CRUD de pauta (adicionar, reordenar, remover item) | 3 | a pauta é lida, nunca montada |
| e-SIC, LGPD, ouvidoria, moderação de comentários | 19 de 22 | módulo inteiro sem interface |
| Remessa ao TCE (validar, submeter, resposta) | 3 | M6 invisível; só o card resumido na Mesa |
| Reassunção de mandato | 1 | existe a licença, não o retorno |

### 1.4 Buracos de acabamento confirmados no código

| Achado | Onde | Confirmação |
|---|---|---|
| **3 telas são casca** (`EmBreve` fixo, zero fetch) | `/expediente/modelos`, `/expediente/protocolo`, `/expediente/recebidos` | lidas: nenhum GET, nenhuma ação |
| **Portal do cidadão não tem nenhuma escrita** | "Abrir pedido" e-SIC + 5 botões LGPD + 6 cartões de navegação cívica | todos `EmBreve`/inertes |
| **`/vereador` diz "Nenhuma sessão agendada" sempre** | `(vereador)/vereador/page.tsx:126` | comentário do próprio código: `sessoes=[]` nesta fatia |
| **3 botões da ficha da matéria são inertes** | `/ficha-materia/[id]` — "Incluir na pauta", "Gerar PDF", "Distribuir a comissão" | `disabled` |
| **"Gerar PDF" do expediente sempre desabilitado** | `/expediente` | rota não existe |
| **Zero navegação entre as telas de sessão** | `/chamada`, `/plenario`, `/folha` | 0 `Link`/`router.push` nas três — só por URL digitada |
| **`/paineis/mesa` e `/pauta-convocacao` são becos** | ambas | 0 links; só leitura |
| **Ator do topo é fixo, não vem da autenticação** | `/paineis/mesa` ("Sérgio Lopes"), grupo proposições ("Rita Campos") | hardcoded |
| **3 rotas fora do gate do middleware** | `/cadastros/vereadores`, `/sessoes/:id/chamada`, `/notificacoes` | risco de UX (sem redirect), não de segurança — backend ainda 401/403 |
| **Biometria da assinatura é mock local** | `/parecer/[id]/assinar` | sem WebAuthn/ICP-Brasil; ato é irreversível |

### 1.5 Autenticação — as duas portas

- **Modo real:** cookie httpOnly `sessao`, minted por `POST /auth/sessoes` após PKCE contra Keycloak;
  o backend **re-verifica** o token contra o Keycloak, nunca confia no corpo.
- **Modo dev:** `?token=` na URL é JSON de claims **sem assinatura**, aceito por `IdpDev`.
  **Dois gates independentes o impedem em produção:** o backend só liga o `idp-dev` se `APP_ENV`
  for exatamente `dev`/`test` (`sistema.clj:128-142`), e o `AuthProvider` do FE **lança erro** se
  `?token=` aparecer em modo real (`lib/auth.tsx:37-58`); o `next build` ainda quebra se
  `NEXT_PUBLIC_DEV_TOKEN` estiver setado com `NODE_ENV=production`. **Fail-safe por desenho.**
- **Consequência para a demonstração:** existem dois roteiros possíveis — **token de dev** (rápido,
  sem Keycloak) e **login de verdade** (Keycloak, PKCE, cookie). O segundo é o que se mostra a
  cliente. Este plano exige que **as duas** funcionem sobre a mesma Casa.

---

## §2 — O mapa de fluxos

Nove jornadas cobrem as 27 telas. Para cada uma: as telas na ordem, as escritas reais, o que a
jornada prova a quem assiste, e o veredicto de viabilidade **hoje**.

| # | Jornada | Telas na ordem | Escritas reais | Prova a quem | Viável hoje? |
|---|---|---|---|---|---|
| **J1** | **A Casa existe** | `/cadastros/vereadores` | criar vereador · editar · registrar mandato · registrar licença · conceder acesso (3 POSTs) | servidor | ✅ **sim** — a jornada mais completa do produto |
| **J2** | **A matéria nasce** | `/editor-proposicao` → `/proposicoes` → `/ficha-materia/:id` → `/tramitacao` | protocolar · editar | servidor | ⚠️ **parcial** — a ficha é beco: 3 ações inertes |
| **J3** | **O parecer** | `/ficha-materia/:id` → `/parecer/:id` (servidor prepara) → `/vereador` → `/parecer/:id/assinar` (vereador assina) | salvar rascunho · emitir parecer · emitir meu parecer | servidor + vereador | ⚠️ **parcial** — biometria é mock; `/vereador` precisa do card |
| **J4** | **A sessão acontece** (HERO) | `/pauta-convocacao` → `/sessoes/:id/chamada` → `/sessoes/:id/plenario` → `/votar` → placar no telão → `/sessoes/:id/folha` | presença (unitária/lote/confirmar) · registrar chamada · justificativa + decisão · meu voto · congelar folha | os três públicos | ❌ **NÃO fecha** — abrir sessão, abrir votação, encerrar votação e mover a tribuna **não têm tela**; e não há navegação entre as três telas |
| **J5** | **A lei nasce** | `/ficha-materia/:id` → `/pos-aprovacao/:id` → `/notificacoes` (autor) | gerar autógrafo · registrar retorno do Executivo | servidor + vereador | ✅ **sim** — cadeia completa até a notificação |
| **J6** | **O cidadão** | `/portal/casa/:ente` → `/materias/:id` → `/vereadores/:id` | **nenhuma** | cidadão + presidente | ⚠️ **só leitura** — e-SIC, LGPD e navegação cívica são placeholders |
| **J7** | **A Mesa enxerga** | `/paineis/mesa` | nenhuma | presidente | ⚠️ **beco** — dashboard sem um único link |
| **J8** | **Entrar de verdade** | `/entrar/:ente` → Keycloak → sessão com cookie | mint de sessão · logout | todos | ✅ **sim** — mas hoje leva a uma Casa vazia (ilha `login-kc`) |
| **J9** | **O expediente** | `/expediente` → gerar documento → protocolar | gerar · editar rascunho · protocolar e numerar | servidor | ⚠️ **parcial** — 3 abas irmãs são casca; "Gerar PDF" morto |

### 2.1 As três classes de falha

Toda falha achada na caminhada entra em **uma** destas classes, e a classe decide o tratamento:

- **(A) DEFEITO** — o código faz a coisa errada. *Trata-se corrigindo, com teste de regressão que
  falha antes.* Ex.: enum cru na tela, UUID onde vai nome, 500 numa rota.
- **(B) LACUNA** — nunca foi construído. *Trata-se decidindo:* construir agora, contornar no roteiro,
  ou assumir como roadmap na fala. **Não vira defeito e não entra na fila de correção sem decisão
  explícita do Daouda.** Ex.: as 39 rotas sem tela.
- **(C) DADO** — o código está certo e a semente não sustenta a história. *Trata-se na Fase 0.*
  Ex.: as 14 presenças fantasma.

**A regra que evita o erro mais caro deste plano:** ao achar uma tela vazia, **primeiro provar de
qual classe é** — consultando o endpoint direto com `curl` antes de acusar o frontend. Tela vazia
com endpoint devolvendo dado é defeito de FE; tela vazia com endpoint devolvendo `[]` é dado; tela
vazia sem endpoint nenhum é lacuna.

---

## §3 — FASE 0: a Casa (dado)

**Entrega:** uma única Casa coerente, re-semeável do zero, onde as nove jornadas acontecem sobre o
mesmo ente, os mesmos vereadores e as mesmas matérias.

**Branch:** `demo-casa-unica`

### Task 0.1: Zerar e reconstruir o banco

**Arquivos:** nenhum (operação de ambiente) · **Cria:** `docs/15-roteiro-de-demonstracao.md` (seção "Reset")

- [ ] **Passo 1: medir o antes** — registrar o censo atual para poder provar a limpeza

```sh
docker exec oplenario-postgres-1 psql -U oplenario -d oplenario -tAc \
  "select 'entes', count(*) from cadastros.ente
   union all select 'proposicoes', count(*) from legislativo.proposicoes;"
```
Esperado: `entes|8121`, `proposicoes|46952` (ou o que houver — anotar o número real).

- [ ] **Passo 2: derrubar com volume**

```sh
cd apps/backend && docker compose --profile auth down -v
```

- [ ] **Passo 3: subir e re-migrar**

```sh
cd apps/backend && docker compose --profile auth up -d --build
docker compose logs migrate | tail -20
```
Esperado: as 61 migrations aplicadas, `migrate` sai com código 0.
**Armadilha conhecida:** migratus deixa lock `-1` preso se uma migration quebrar — se o log parar
num `applying`, limpar `schema_migrations` manualmente antes de repetir.

- [ ] **Passo 4: provar o zero**

Repetir o `psql` do Passo 1. Esperado: `entes|0`. **Se não for 0, parar** — o volume não foi removido.

- [ ] **Passo 5: commit da seção de reset no roteiro**

```sh
git add docs/15-roteiro-de-demonstracao.md
git commit -m "docs(demo): a receita de reset do banco, medida contra a stack"
```

### Task 0.2: A semente narrativa — a Casa e sua gente

**Arquivos:** Modificar `apps/backend/demo/seed_demo.clj` · Criar `apps/backend/demo/casa.clj`
· Test: `apps/backend/test/oplenario/demo/casa_test.clj`

**Interfaces:**
- Produz: `(casa/semear! sistema)` → mapa com `:ente`, `:legislatura`, `:vereadores` (vetor de
  `{:id :nome :partido :mandato-id}`), `:identidades` `{:secretaria :presidente :vereador :cidadao}`,
  gravado em `.artifacts/demo-ids.edn`. **Toda semente posterior lê esse mapa — nenhuma cria ente.**

- [ ] **Passo 1: o teste que falha — uma Casa, não quatro**

```clojure
(deftest semear-produz-uma-unica-casa
  (with-sistema [s]
    (let [r1 (casa/semear! s)
          r2 (casa/semear! s)]
      (testing "re-executar não cria uma segunda Casa"
        (is (= (:ente r1) (:ente r2))))
      (testing "17 vereadores, todos com mandato vigente na data de hoje"
        (is (= 17 (count (:vereadores r1))))
        (is (every? :mandato-id (:vereadores r1))))
      (testing "nenhum nome duplicado no roster"
        (is (= 17 (count (distinct (map :nome (:vereadores r1))))))))))
```

- [ ] **Passo 2: rodar e ver falhar** — `casa/semear!` não existe.

```sh
docker run --rm --network host -v "$PWD/apps/backend:/app:ro" -v oplenario_e2e_m2:/root/.m2 \
  -e CLJ_CACHE=/tmp/cpcache \
  -e DATABASE_URL="jdbc:postgresql://localhost:5544/oplenario" -e DB_USER=oplenario -e DB_PASSWORD=dev \
  -e VALKEY_URI="redis://localhost:6379" -e MINIO_ENDPOINT="http://localhost:9100" \
  -w /app clojure:temurin-21-tools-deps clojure -M:test --focus oplenario.demo.casa-test
```
Esperado: FALHA com `Unable to resolve symbol: casa/semear!`.

- [ ] **Passo 3: implementar `casa/semear!`**

Cria, em uma transação por agregado: 1 município (Fortaleza, IBGE 2304400, idempotente) · 1 ente
**estável** (UUID fixo em constante, não `random-uuid` — é o que torna a semente re-executável) ·
1 legislatura 2025–2028 vigente · **17 vereadores** com nome, nome parlamentar e partido distintos ·
17 mandatos vigentes (1 deles licenciado, para a jornada de licença/reassunção) · Mesa Diretora
(presidente, vice, 1º e 2º secretários) · 3 comissões permanentes (CCJ, Finanças, Obras) com
presidente e membros · 4 identidades com vínculo e papel: **secretária** (`secretario`), **presidente
da Mesa** (`vereador` + `admin_ente`), **vereador comum** (`vereador`), **cidadão** (sem papel).

- [ ] **Passo 4: rodar e ver passar** — mesmo comando do Passo 2. Esperado: PASSA.

- [ ] **Passo 5: commit**

```sh
git add apps/backend/demo/casa.clj apps/backend/test/oplenario/demo/casa_test.clj
git commit -m "feat(demo): uma Casa unica e estavel — 17 vereadores, Mesa e 3 comissoes"
```

### Task 0.3: As presenças que existem de verdade

**Arquivos:** Modificar `apps/backend/demo/casa.clj` · Test: `apps/backend/test/oplenario/demo/casa_test.clj`

**Este é o defeito de classe (C) mais caro que já está medido** — 14 presenças de 14 pessoas que
não existem no cadastro, enquanto os 7 vereadores nominados têm zero.

- [ ] **Passo 1: o teste que falha — toda presença é de gente do roster**

```clojure
(deftest toda-presenca-e-de-vereador-do-cadastro
  (with-sistema [s]
    (let [{:keys [ente vereadores]} (casa/semear! s)
          ids-roster (set (map :id vereadores))
          ids-presenca (casa/ids-com-presenca s ente)]
      (testing "nenhuma presença de pessoa fora do cadastro"
        (is (empty? (clojure.set/difference ids-presenca ids-roster))
            "presença fantasma: o telão mostraria UUID sem nome"))
      (testing "o quórum da sessão em curso é de gente nominada"
        (is (>= (count ids-presenca) 9))))))
```

- [ ] **Passo 2: rodar e ver falhar.** Esperado: FALHA listando os ids fantasma.

- [ ] **Passo 3: implementar** — a semente de presença passa a iterar o vetor `:vereadores`
  devolvido por `casa/semear!`, nunca `random-uuid`.

- [ ] **Passo 4: rodar e ver passar.**

- [ ] **Passo 5: provar no telão, não só no teste** — abrir `/sessoes/:id/plenario` no browser e
  confirmar nome real em toda linha, zero fragmento de UUID.

- [ ] **Passo 6: commit**

```sh
git add apps/backend/demo/casa.clj apps/backend/test/oplenario/demo/casa_test.clj
git commit -m "fix(demo): a presenca era de gente que nao existia, e o telao mostrava UUID"
```

### Task 0.4: O acervo legislativo

**Arquivos:** Criar `apps/backend/demo/acervo.clj` · Test: `apps/backend/test/oplenario/demo/acervo_test.clj`

- [ ] **Passo 1: o teste que falha — todo estado do rito tem exemplar**

```clojure
(deftest acervo-cobre-todos-os-estados-do-rito
  (with-sistema [s]
    (let [{:keys [ente]} (casa/semear! s)
          _ (acervo/semear! s ente)
          por-estado (acervo/contar-por-estado s ente)]
      (doseq [estado ["protocolada" "em_comissoes" "pronta_para_pauta"
                      "em_pauta" "aprovada" "arquivada"]]
        (testing (str "existe matéria em " estado)
          (is (pos? (get por-estado estado 0))))))))
```

- [ ] **Passo 2: rodar e ver falhar.**

- [ ] **Passo 3: implementar** — 24 proposições de autoria distribuída entre os 17 vereadores,
  cobrindo os 6 estados; 3 pareceres (1 rascunho, 1 emitido, 1 aguardando assinatura do relator);
  2 autógrafos (1 aguardando o Executivo, 1 sancionado); 4 normas promulgadas e publicadas.
  **Ementas plausíveis de câmara municipal** — nada de "teste 1", "foo".

- [ ] **Passo 4: rodar e ver passar.**

- [ ] **Passo 5: commit**

```sh
git add apps/backend/demo/acervo.clj apps/backend/test/oplenario/demo/acervo_test.clj
git commit -m "feat(demo): o acervo legislativo — 24 materias cobrindo os 6 estados do rito"
```

### Task 0.5: As três sessões

**Arquivos:** Criar `apps/backend/demo/sessoes.clj` · Test: `apps/backend/test/oplenario/demo/sessoes_test.clj`

A jornada J4 precisa de três sessões simultâneas em estados diferentes — sem isso não há como
mostrar a folha (exige encerrada) e o telão ao vivo (exige em curso) na mesma apresentação.

- [ ] **Passo 1: o teste que falha**

```clojure
(deftest tres-sessoes-em-estados-distintos
  (with-sistema [s]
    (let [{:keys [ente]} (casa/semear! s)
          {:keys [encerrada em-curso agendada]} (sessoes-demo/semear! s ente)]
      (testing "a encerrada tem chamada registrada e votação apurada"
        (is (= "encerrada" (:estado (sessoes-demo/buscar s ente encerrada))))
        (is (pos? (sessoes-demo/votos-apurados s ente encerrada))))
      (testing "a em curso tem quórum e pauta publicada"
        (is (>= (sessoes-demo/quorum s ente em-curso) 9)))
      (testing "a agendada tem pauta montada e nenhuma presença"
        (is (pos? (sessoes-demo/itens-de-pauta s ente agendada)))
        (is (zero? (sessoes-demo/quorum s ente agendada)))))))
```

- [ ] **Passo 2: rodar e ver falhar.**

- [ ] **Passo 3: implementar** — **encerrada** (semana passada: chamada registrada, 2 votações
  apuradas, tribuna com falas encerradas, pronta para congelar folha) · **em curso** (hoje: quórum
  aberto com presenças do roster real, 1 orador na tribuna com cronômetro correndo, 1 votação
  nominal **aberta** para o vereador votar ao vivo) · **agendada** (semana que vem: pauta montada
  com 5 itens, nenhuma presença).

- [ ] **Passo 4: rodar e ver passar.**

- [ ] **Passo 5: commit**

```sh
git add apps/backend/demo/sessoes.clj apps/backend/test/oplenario/demo/sessoes_test.clj
git commit -m "feat(demo): tres sessoes — encerrada, em curso e agendada — na mesma Casa"
```

### Task 0.6: A participação cidadã

**Arquivos:** Criar `apps/backend/demo/participacao.clj` · Test: `apps/backend/test/oplenario/demo/participacao_test.clj`

- [ ] **Passo 1: o teste que falha** — 3 pedidos e-SIC (aberto no prazo, respondido, em recurso),
  2 solicitações LGPD, 2 manifestações de ouvidoria, 5 comentários (3 aprovados, 2 na fila de
  moderação), 1 Encarregado/DPO.

```clojure
(deftest participacao-tem-exemplar-de-cada-estado
  (with-sistema [s]
    (let [{:keys [ente]} (casa/semear! s)
          r (participacao-demo/semear! s ente)]
      (is (= 3 (count (:esic r))))
      (is (= #{"aberto" "respondido" "em_recurso"} (set (map :estado (:esic r)))))
      (is (= 2 (count (:moderacao-pendente r))) "a fila de moderação não pode estar vazia")
      (is (some? (:encarregado r))))))
```

- [ ] **Passo 2: rodar e ver falhar.**
- [ ] **Passo 3: implementar.**
- [ ] **Passo 4: rodar e ver passar.**
- [ ] **Passo 5: commit**

```sh
git add apps/backend/demo/participacao.clj apps/backend/test/oplenario/demo/participacao_test.clj
git commit -m "feat(demo): e-SIC, LGPD, ouvidoria e fila de moderacao com exemplar de cada estado"
```

### Task 0.7: O orquestrador e a idempotência

**Arquivos:** Modificar `e2e/semear.sh` · Criar `demo/semear-tudo.sh` · Test: o próprio script

- [ ] **Passo 1: o teste que falha — semear duas vezes seguidas**

```sh
./demo/semear-tudo.sh && ./demo/semear-tudo.sh
```
Esperado hoje: FALHA na segunda (realm Keycloak duplicado em `login-kc`/`slice5`; template
`rito_fixture_portal` duplicado em `materias`).

- [ ] **Passo 2: implementar** — ordem obrigatória `casa → acervo → sessoes → participacao → keycloak`,
  cada etapa idempotente por chave natural (`ON CONFLICT DO NOTHING` ou "reusar-se-existir"), e o
  provisionamento de realm tolerando realm já existente.

- [ ] **Passo 3: rodar duas vezes e ver passar.**

- [ ] **Passo 4: a barreira da projeção** — manter e ampliar o poll de `semear.sh`: esperar o relay
  materializar `transparencia.materia` **e** o read-model do perfil do vereador antes de declarar pronto.

- [ ] **Passo 5: commit**

```sh
git add demo/semear-tudo.sh e2e/semear.sh
git commit -m "feat(demo): semeadura idempotente e ordenada, com barreira de projecao"
```

### Task 0.8: Os três logins de verdade

**Arquivos:** Modificar `apps/backend/demo/casa.clj` (bloco Keycloak) · Criar `docs/15-roteiro-de-demonstracao.md` (seção "Entrar")

- [ ] **Passo 1: o teste que falha** — os 3 atores (secretária, presidente, vereador) entram pelo
  Keycloak **na mesma Casa** e chegam à tela certa.
- [ ] **Passo 2: implementar** — um realm por ente da demo, 3 usuários com senha conhecida,
  `required actions` limpas, vínculo e papéis já concedidos.
- [ ] **Passo 3: provar em browser** — `/entrar/:ente` → Keycloak → `/paineis/mesa` com o **nome real
  do ator no topo** (hoje é fixo — se continuar "Sérgio Lopes", é defeito de classe A, vai para o ledger).
- [ ] **Passo 4: commit**

```sh
git add apps/backend/demo/casa.clj docs/15-roteiro-de-demonstracao.md
git commit -m "feat(demo): tres logins reais de Keycloak sobre a MESMA Casa"
```

**Gate da Fase 0:** do banco zerado, um comando reconstrói a Casa; as 9 jornadas têm dado; rodar duas
vezes não quebra; os 3 logins funcionam. **Sem esse gate, a Fase 2 não começa** — testar jornada sem
dado coerente produz ledger de falso defeito.

---

## §4 — FASE 1: varredura automática das 27 telas

**Entrega:** a sonda estendida, rodando contra a Casa nova, com asserções que **podem reprovar**.

**Branch:** `sonda-27-telas`

### Task 1.1: A sonda passa a ler os ids da semente

**Arquivos:** Modificar `e2e/.sonda/sonda.mjs`

Hoje os ids e tokens estão **cravados no topo do arquivo** e o README manda trocar à mão — o que
garante que a sonda mede a Casa errada assim que a semente roda de novo.

- [ ] **Passo 1: o teste que falha** — rodar a sonda depois de um reset. Esperado: 26 rotas em erro
  (ids de uma Casa que não existe mais).
- [ ] **Passo 2: implementar** — ler `e2e/.artifacts/demo-ids.edn`; **falhar alto** se o arquivo não
  existir, com mensagem acionável ("rode `./demo/semear-tudo.sh` antes").
- [ ] **Passo 3: rodar e ver passar.**
- [ ] **Passo 4: commit**

```sh
git add e2e/.sonda/sonda.mjs
git commit -m "fix(sonda): os ids eram cravados a mao e mediam a Casa errada depois de re-semear"
```

### Task 1.2: A 27ª rota e as asserções que reprovam

**Arquivos:** Modificar `e2e/.sonda/sonda.mjs`

- [ ] **Passo 1:** acrescentar `/entrar/:ente` (a única das 27 fora da sonda).
- [ ] **Passo 2: o teste que falha** — a sonda hoje **reporta** e sempre sai com código 0. Plantar
  um defeito conhecido (um enum cru numa tela) e verificar que ela **não** reprova.
- [ ] **Passo 3: implementar o veredicto** — sair com código ≠ 0 quando, em qualquer rota: status ≥ 400 ·
  fragmento de UUID no texto visível · chave de enum crua · erro de console · texto de erro genérico.
  **`EmBreve` conta como aviso, não como falha** — é lacuna conhecida (classe B), não defeito.
- [ ] **Passo 4: provar que reprova** — repetir o defeito plantado; esperado: código ≠ 0 nomeando a rota.
- [ ] **Passo 5: reverter o defeito plantado e rodar limpo.**
- [ ] **Passo 6: commit**

```sh
git add e2e/.sonda/sonda.mjs
git commit -m "feat(sonda): 27/27 rotas e um veredicto que REPROVA — antes so' reportava"
```

**Gate da Fase 1:** `./e2e/.sonda/rodar.sh` cobre 27/27, reprova defeito plantado, e passa limpo
contra a Casa da Fase 0. O que sobrar de `EmBreve` sai listado como lacuna, não como falha.

---

## §5 — FASE 2: a caminhada exploratória (o teste de verdade)

**Entrega:** o ledger de defeitos, produzido por percorrer as nove jornadas em browser real, na
ordem em que a apresentação vai acontecer.

**Método por jornada:** abrir a primeira tela como o ator certo (login real, não token de dev) →
executar cada escrita → **provar o efeito na tela seguinte, não no banco** → anotar tudo que
divergir do esperado. Um arquivo de ledger, uma linha por achado.

**Formato obrigatório do ledger** (`docs/16-ledger-prontidao.md`):

| # | Jornada | Tela | O que fiz | O que esperava | O que aconteceu | Classe | Gravidade | Endpoint direto responde? |
|---|---|---|---|---|---|---|---|---|

**Gravidade** — `MATA` (não dá para apresentar), `CONSTRANGE` (dá, com desculpa), `PASSA` (ninguém nota).
**A coluna do endpoint é obrigatória** e é o que separa classe A de classe C: antes de acusar a tela,
chamar o endpoint com `curl` e colar a resposta.

### Task 2.1 a 2.9 — uma por jornada

Para cada jornada da tabela da §2, nesta ordem (J1, J9, J2, J3, J4, J5, J7, J6, J8 — as de escrita
primeiro, porque produzem o dado que as de leitura vão exibir):

- [ ] **Passo 1:** abrir a primeira tela da jornada, autenticado como o ator da coluna "prova a quem".
- [ ] **Passo 2:** executar cada escrita da coluna "escritas reais", uma a uma.
- [ ] **Passo 3:** depois de cada escrita, **abrir a tela que deveria mostrar o efeito** e conferir.
- [ ] **Passo 4:** para cada divergência, chamar o endpoint com `curl` e anotar a resposta.
- [ ] **Passo 5:** escrever a linha no ledger com a classe e a gravidade.
- [ ] **Passo 6:** capturar tela do que estiver `MATA` — a imagem entra no ledger.

**Regra de disciplina (custou 8 defeitos reais em sessões anteriores):** a caminhada não termina
quando a tela responde; termina quando o **efeito** aparece em outra tela. "O POST devolveu 200" não
é prova — o que prova é o número mudando no telão.

**Gate da Fase 2:** ledger fechado, com as nove jornadas percorridas e toda linha classificada.
**Nenhuma correção começa antes disso** — corrigir durante a caminhada esconde o segundo defeito
que o primeiro estava mascarando.

---

## §5b — FASE 2b: varredura de API das 52 rotas sem tela

**Por que existe:** as 9 jornadas exercitam as 69 rotas que têm consumidor no FE. As outras **52 não
têm tela nenhuma** e, sem esta fase, atravessariam a apresentação sem nenhuma verificação de
integração — apoiadas só em teste unitário. Neste mesmo repositório, teste unitário já ficou verde
por meses sobre um read-model morto (o numerador do perfil público comparava `tipo = "presente"`,
valor que produtor nenhum emite). **"O backend está pronto" é alegação até alguém chamar a rota.**

**Entrega:** um script que chama as 52 rotas contra a Casa da Fase 0, com o papel certo, e afirma
status e forma do corpo. Sem browser, sem design, sem UI.

**Branch:** `varredura-api-52`

### Task 2b.1: O inventário executável

**Arquivos:** Criar `e2e/.api/rotas-sem-tela.json` · Criar `e2e/.api/varredura.mjs`

**Interfaces:**
- Consome: `e2e/.artifacts/demo-ids.edn` (ente, sessão, proposição, vereadores, identidades da Fase 0)
- Produz: código de saída 0/≠0 + relatório por rota em `e2e/.api/resultado.json`

- [ ] **Passo 1: escrever o inventário** — uma entrada por rota, com método, path (com os
  parâmetros ligados aos ids da semente), papel exigido, status esperado, e uma asserção de forma
  do corpo. Distribuição confirmada: sessões 17 · participação 19 · transparência 5 · legislativo 4
  · compliance 3 · cadastros 1 · identidade 1 · host 2.

```json
{ "modulo": "participacao", "metodo": "POST", "path": "/portal/esic/pedidos",
  "papel": "cidadao", "status": 201, "corpo": ["protocolo", "pedido-id"],
  "payload": { "assunto": "Despesas com diarias em 2026", "detalhamento": "..." } }
```

- [ ] **Passo 2: o teste que falha** — rodar a varredura antes de implementar o executor.
  Esperado: FALHA (`varredura.mjs` não existe).

- [ ] **Passo 3: implementar o executor** — para cada entrada: mintar o token do papel, chamar a
  rota, comparar status, e conferir que **cada chave declarada em `corpo` existe na resposta**.
  Rotas de escrita rodam em ordem de dependência (protocolar antes de responder, responder antes de
  recorrer) — declarada no próprio JSON por um campo `depende-de`.

- [ ] **Passo 4: rodar e ver o placar real.** Esperado: **algumas reprovam.** Esta fase existe para
  achar, não para confirmar. Toda reprovação vira linha no ledger da §5 com classe e gravidade.

- [ ] **Passo 5: provar que a varredura PODE reprovar** — mudar o status esperado de uma rota que
  passou e confirmar saída ≠ 0 nomeando a rota. Reverter em seguida.
  **Asserção que não pode reprovar não é cobertura.**

- [ ] **Passo 6: commit**

```sh
git add e2e/.api/rotas-sem-tela.json e2e/.api/varredura.mjs
git commit -m "feat(api): varredura das 52 rotas sem tela — o backend deixa de ser alegacao"
```

### Task 2b.2: As três que não se testam por chamada isolada

**Arquivos:** Modificar `e2e/.api/varredura.mjs`

- [ ] **Passo 1:** `POST /gravacoes` (upload binário multipart), `GET /sessoes/:id/plenario` (SSE, não
  encerra) e `GET /assiduidade` no recorte CSV precisam de tratamento próprio — `Content-Type`,
  leitura de stream com corte por tempo, e comparação de cabeçalho de CSV.
- [ ] **Passo 2:** implementar os três casos especiais, com o SSE cortado após o primeiro frame.
- [ ] **Passo 3:** rodar e ver passar.
- [ ] **Passo 4: commit**

```sh
git add e2e/.api/varredura.mjs
git commit -m "feat(api): upload binario, SSE e CSV — os tres casos que a chamada simples nao cobre"
```

**Gate da Fase 2b:** as 52 chamadas executadas contra a Casa da Fase 0, com placar declarado
(quantas passaram, quantas reprovaram, quantas ficaram indeterminadas e por quê). **Zero achados e
"não rodou" têm a mesma saída** — o relatório afirma o volume inspecionado, não só o resultado.

**Cobertura depois desta fase:** 121/121 rotas exercitadas contra a Casa real — 69 pela interface
nas jornadas, 52 por chamada direta. O que continua sem cobertura é o que **não tem código**:
Track IA e os dois IdPs (gov.br, operador).

---

## §6 — FASE 3: a decisão sobre as lacunas

**Entrega:** decisão do Daouda, item a item, sobre as lacunas de classe (B). **Esta fase é uma
conversa, não código.**

O material da decisão é a tabela da §1.3 mais o que a caminhada acrescentar. A recomendação que já
se pode fazer, sem esperar o ledger:

| Lacuna | Recomendação | Por quê |
|---|---|---|
| **Condução da sessão sem tela** (abrir sessão, abrir/encerrar votação, tribuna, pauta — 13 rotas) | **construir agora**, escopo mínimo | É a jornada HERO. Sem ela a apresentação tem `curl` no meio. Backend pronto e testado: é só a borda |
| **`/vereador` com `sessoes=[]`** | **construir agora** | Uma linha de fetch; sem ela o vereador vê "nenhuma sessão" durante a sessão que está acontecendo |
| **Navegação entre chamada/plenário/folha** | **construir agora** | 3 links. Hoje só se troca de tela digitando URL na frente do cliente |
| **Ator do topo hardcoded** | **construir agora** | O nome errado no cabeçalho destrói a credibilidade do login que acabou de ser mostrado |
| **e-SIC / LGPD / ouvidoria sem tela** (19 rotas) | **contornar** — mostrar pelo portal em leitura e narrar como roadmap | Bloco grande de UI; o backend demonstra por `curl` se alguém perguntar |
| **3 abas casca do expediente** | **contornar** — não abrir na apresentação | Nada a ganhar; o `/expediente` principal funciona |
| **3 botões inertes da ficha** | **esconder** em vez de exibir `disabled` | Botão cinza convida a pergunta que não tem resposta boa |
| **Remessa ao TCE sem tela** | **contornar** — narrar pelo card da Mesa | M6 existe; a tela é Onda E |
| **Biometria mock** | **contornar** — não chamar de biometria na fala | Dizer "assinatura" e seguir; ICP-Brasil é carry conhecido |

**Gate da Fase 3:** cada lacuna com um veredicto escrito — *construir*, *contornar* ou *esconder*.
Sem isso a Fase 4 vira escopo aberto.

---

## §7 — FASE 4: correção

**Entrega:** os defeitos de classe (A) corrigidos e as lacunas aprovadas para construção, na ordem
de gravidade do ledger.

**Branch:** uma por bloco (`correcao-<bloco>`), mergeada e fechada antes da seguinte.

**Método por defeito, sem exceção:**

- [ ] **Passo 1:** escrever o teste que reproduz o defeito **e falha**.
- [ ] **Passo 2:** rodar e ver falhar — colar a linha do erro que nomeia o defeito.
- [ ] **Passo 3:** corrigir minimamente.
- [ ] **Passo 4:** rodar e ver passar.
- [ ] **Passo 5:** **reabrir a jornada no browser** e provar ao vivo — a suíte verde não prova
  geometria, fuso, contraste nem enum cru.
- [ ] **Passo 6:** commit, uma correção por commit.

**Ordem:** todo `MATA` antes de qualquer `CONSTRANGE`; dentro de cada faixa, a jornada mais cedo no
roteiro primeiro (um defeito na J1 estraga tudo que vem depois).

**Revisão adversarial `ecc` antes de cada merge**, apontada explicitamente para a distância entre o
nome do teste e o que ele exercita — foi o padrão dominante de falha em três frentes seguidas.

---

## §8 — FASE 5: o ensaio

**Entrega:** a prova de que a apresentação inteira acontece do zero, duas vezes seguidas.

- [ ] **Passo 1:** `docker compose --profile auth down -v && up -d --build`
- [ ] **Passo 2:** `./demo/semear-tudo.sh`
- [ ] **Passo 3:** `./e2e/.sonda/rodar.sh` — esperado: 27/27 sem falha.
- [ ] **Passo 4:** percorrer o roteiro de `docs/15-roteiro-de-demonstracao.md` inteiro, cronometrado,
  com login real, sem tocar em `curl`.
- [ ] **Passo 5:** repetir os passos 1–4 **do zero**. Um ensaio que só passa na segunda vez é um
  ensaio que falha na apresentação.
- [ ] **Passo 6:** commit do roteiro final com os tempos medidos.

**Gate final:** dois ensaios limpos consecutivos, partindo de banco zerado.

---

## §9 — O que este plano NÃO faz

Declarado para que o silêncio não seja lido como promessa:

- **Não constrói a Track IA.** Ata-IA, copiloto e busca semântica seguem sem código; M4 continua
  parcial. Se a apresentação precisar falar de IA, é fala de roadmap.
- **Não constrói o broker gov.br** nem o IdP do operador (stub de 3 linhas).
- **Não faz a Onda E** — as ~13 telas de cauda seguem sem rota; este plano só constrói a costura da
  jornada HERO, que é subconjunto pequeno e específico.
- **Não resolve a assinatura ICP-Brasil** (`STUB-ICP-v0`) nem troca a biometria mock por WebAuthn.
- **Não cria repositório remoto nem CI.** Segue sendo decisão do Daouda; a verificação continua
  local, nesta máquina, como foi em F0–F7.
- **Não toca nos 26 outros TCEs** — a forma segue validada só contra o CE.

---

## §10 — Auto-revisão deste plano

**Cobertura:** 27/27 telas — 22 percorridas em jornada na §2, e 5 (raiz, `/entrar` e as 3 cascas do
expediente) só pela sonda da §4, porque não têm o que percorrer · **121/121 rotas exercitadas contra
a Casa real** — 69 pela interface nas jornadas, 52 por chamada direta na Fase 2b · as 3 classes de
falha têm tratamento distinto e declarado · o que fica sem cobertura é o que não tem código, listado
na §9.

**Premissa que pode estar errada e como o plano reage:** a §1 foi medida contra a stack de hoje. Se
a Task 0.1 revelar que o reset não produz `entes|0`, ou que alguma migration não aplica do zero,
**parar e reportar** — o resto do plano assume banco reconstruível, e essa é uma alegação que só o
Passo 4 da Task 0.1 verifica. Especificação aprovada é hipótese, não verdade.

**Risco declarado:** a Fase 2 vai produzir defeitos que este plano não previu. É o objetivo dela. O
plano não tenta enumerá-los antes — enumerar defeito antes de medir é como o teste com o nome da
garantia que não a exercita.
