# Plano — o read-model da tribuna (defeito #7 do ledger de prontidão, `MATA`)

## Contexto

A tela do plenário (`/sessoes/:id/plenario`, a HERO) tem 5 painéis. Quatro sabem se reconstruir
sozinhos por GET; **a tribuna não** — tem 5 rotas de ESCRITA e zero de leitura, única entidade da
sessão nessa situação. O dado existe (`sessoes.fala_executada` com `encerrou_em IS NULL`); o que
falta é o GET. Anatomia verificada na fonte em 07/09/2026 e registrada em `docs/16-ledger-prontidao.md`
(commit `9c34d62`).

Três momentos apagam o telão hoje: **F5** (o cursor do stream é um `useRef` que some no reload),
**abrir a tela depois da fala começar**, e **queda de rede > 5 min** (a janela de replay do canal).
Em qualquer um deles o telão diz "Ninguém com a palavra no momento" enquanto um vereador fala.

`rehidratar()` já existe em `use-plenario.ts` e rebusca quórum, sessão e composição — a tribuna não,
porque não há rota.

## Spec (a autoridade)

`GET /sessoes/:id/tribuna` devolve o estado corrente da tribuna, suficiente para reconstruir os três
campos que hoje nascem vazios no reducer: `oradorAtual`, `marcosCronometro` e `inscritos`.

## Global Constraints (vinculantes — o revisor mede por elas)

1. **Docker, sem exceção.** Nunca rodar `clj`/`node`/`npx` no host. Suíte do backend, de dentro de
   `apps/backend/` (os `-e` NÃO são opcionais — sem eles o run aborta inteiro com `ConnectException`):
   ```bash
   docker run --rm --network oplenario_default -v "$PWD":/app -v ~/.m2:/root/.m2 -w /app \
     -e DATABASE_URL="jdbc:postgresql://postgres:5432/oplenario" -e DB_USER=oplenario -e DB_PASSWORD=dev \
     -e VALKEY_URI="redis://valkey:6379" -e MINIO_ENDPOINT="http://minio:9000" \
     --entrypoint bash clojure:temurin-21-tools-deps -c 'clojure -M:test --skip :e2e --skip :keycloak'
   ```
   Parar `oplenario-app-1` antes do run (advisory lock derruba `outbox-relay-test`); religar depois.
   Frontend roda DENTRO de `oplenario-frontend-1`. **Nunca mutar o mount vivo de `apps/frontend`.**
   **Nunca rodar as duas suítes ao mesmo tempo** — contenção de CPU gera falhas fantasmas.
2. **TDD por fatia: red → green.** O teste que falha vem antes da implementação, e o relatório traz a
   linha do erro do estado vermelho — não só "ficou verde". Verde não é prova; o artefato é.
3. **SEM migration.** Todas as tabelas já existem (`fala_executada` mig `…033`, `inscricao_oradores`
   `…032`, `fala_cronometro_evento`). Qualquer plano que precise de DDL está errado — pare e reporte.
4. **UMA transação.** A leitura inteira (sessão + fala aberta + marcos do cronômetro + fila) sai de um
   único `fn [tx]` no Repo, molde literal de `chamada-da-sessao` em
   `src/oplenario/sessoes/components/repositorio.clj:309`. Ler em tx separadas permite a Mesa encerrar
   a fala no meio do request e a tela publicar um orador que já desceu da tribuna.
5. **SEM aritmética nova.** Reusar as funções de `db/tribuna.clj` que já existem
   (`listar-inscricoes`, `listar-falas-da-sessao`, `listar-eventos-cronometro`) ou uma consulta
   equivalente direta. Não inventar um segundo cálculo de "quem está com a palavra".
6. **AUTHZ — a linha que não pode cair.** A camada fina roda `logic/pode-ver-quorum-da-sessao?`
   (mesma Casa **E** (transmissão pública **OU** papel `secretario`)) — **nunca** `pode-ver-sessao?`
   cru, que reabre a porta dos fundos da **sessão SECRETA** (achado MAJOR de uma revisão anterior;
   ver a docstring de `quorum-handler`). **Verifique o nome e a semântica dessa função na fonte antes
   de usar** — não aceite este briefing como verdade.
7. **O payload é a UNIÃO EXATA do que o SSE já transmite, e nada mais.** Os eventos em
   `src/oplenario/sessoes/events/tribuna.clj` (`FalaIniciadaPayload`, `FalaCronometroPayload`,
   `InscricaoRegistradaPayload`) já entregam esses campos ao MESMO público pelo canal do plenário.
   **Confira campo a campo contra aquele arquivo** e justifique no relatório qualquer campo que você
   sirva e que o SSE não sirva. Um campo a mais é vazamento novo, não conveniência.
8. **§22.10 import-lint.** `sessoes` nunca importa `cadastros`. Nada de nome de vereador nesta rota —
   o nome já vem de `/composicao`, que é justamente o irmão que resolve isso.
9. **Contrato TS regenerado pelo codegen**, nunca à mão. Drift zero.
10. **Um commit por fatia**, mensagem em português sem acentos no assunto.

## Task 1 — backend: a rota de leitura da tribuna

Espelhar a rota irmã `composicao` (commit `22b8951`), que é o molde literal desta fatia.

**Arquivos (mesmos papéis da irmã):**
- `src/oplenario/sessoes/components/repositorio.clj` — novo método `tribuna-da-sessao` no protocolo
  `RepoSessoes` + implementação em UMA tx (molde de `chamada-da-sessao`, linha 309). Curto-circuito:
  sessão inexistente devolve `nil` sem fazer as demais leituras.
- `src/oplenario/sessoes/controllers.clj` — `tribuna-da-sessao`: camada FINA. Lê pelo Repo, roda
  `authz/check!` com `logic/pode-ver-quorum-da-sessao?` **sobre a sessão lida nessa mesma tx, ANTES
  de qualquer uso do dado**, projeta o mapa de domínio. `nil` -> 404 no diplomat.
- `src/oplenario/sessoes/adapters/out/tribuna.clj` — `tribuna-sessao->wire` + schema Malli `TribunaOut`
  (`{:closed true}`), instantes -> string ISO como as irmãs.
- `src/oplenario/sessoes/diplomat/http/in.clj` — `tribuna-handler` (docstring no padrão denso das
  irmãs, explicando POR QUE a authz é a do quórum e não `pode-ver-sessao?`) + rota
  `["/sessoes/:id/tribuna" :get [auth (tribuna-handler ...)] :route-name :sessoes/tribuna]`,
  vizinha da rota `composicao`.
- `src/oplenario/codegen/gerar_sessoes.clj` — registrar `TribunaOut` para o contrato TS.

**Forma do payload** (confirmar contra `events/tribuna.clj`, Constraint 7):
- `orador-atual`: `nil` quando não há fala em curso; senão `{fala-id, orador-id, tipo-fala, fase,
  iniciou-em, inscricao-id?}` — exatamente `FalaIniciadaPayload` menos `sessao-id` (redundante no path).
- `marcos-cronometro`: lista dos marcos **da fala em curso**, `{tipo, ocorrido-em, segundos-adicionais?}`
  — exatamente `FalaCronometroPayload` menos os ids. Vazia quando não há fala em curso.
- `inscritos`: fila ATIVA (desistências fora), `{inscricao-id, vereador-id, origem-inscricao, fase,
  ordem}` — exatamente `InscricaoRegistradaPayload` menos `sessao-id`, ordenada por `(fase, ordem)`.
- `sessao-id` no topo.

**Testes (integração, `test/integration/oplenario/sessoes/tribuna_http_in_test.clj`)** — mínimo:
1. Fala em curso: 200 e `orador-atual` preenchido com os campos acima.
2. **Nenhuma fala em curso** (nenhuma fala, e fala já encerrada): `orador-atual` = `nil`, `marcos` vazio.
3. **Fala encerrada não vaza**: iniciar + encerrar, e a rota não devolve aquele orador. (Este é o
   teste que prova que o filtro é `encerrou_em IS NULL` e não "a última fala".)
4. Marcos do cronômetro da fala em curso aparecem; marcos de uma fala ANTERIOR não.
5. Fila: inscritos ordenados; **inscrito que desistiu não aparece**.
6. **Sessão SECRETA / sem transmissão pública -> 403** para vínculo ativo da Casa sem papel
   `secretario`, e **200 para `secretario`**. (Constraint 6 — é a regressão que a rota magra já sofreu.)
7. Sessão de outra Casa -> 404 (isolamento por ente).
8. Sessão inexistente -> 404.

## Task 2 — frontend: o telão volta a se reconstruir sozinho

- Regenerar o contrato TS pelo codegen (`TribunaOut` -> `apps/frontend/src/lib/contrato-sessoes.gen.ts`).
- `src/lib/plenario-reducer.ts` — `hidratarTribuna(estado, cru)` e `falharTribuna(estado)`, molde
  literal de `hidratarComposicao`/`falharComposicao` (linhas 132 e 151). `hidratarTribuna` é **TOTAL**:
  corpo de forma inesperada devolve o estado praticamente inalterado e **nunca lança** — o React pode
  avaliar o updater na fase de RENDER.
- **Precedência:** a hidratação NUNCA sobrescreve um estado mais novo vindo do SSE. Um `fala.encerrada`
  que chegou depois do request sair não pode ser desfeito por uma resposta atrasada. Decidir e
  documentar a regra (o molde do quórum é `hidratarQuorum`, que não funde número do servidor com delta
  do SSE); testar o caso da resposta atrasada explicitamente.
- `src/lib/use-plenario.ts` — buscar `/api/sessoes/${sessaoId}/tribuna` (a) no carregamento inicial,
  ao lado do bloco `1c) COMPOSIÇÃO`, e (b) **dentro do `rehidratar()` que já existe**, que é o que
  cobre a reconexão (é aí que mora o defeito da queda > 5 min). Best-effort e TOTAL: rede/403/500/parse
  não travam o painel nem derrubam o SSE.
  **Atenção:** `rehidratar()` hoje sai cedo com `if (!comQuorum ...) return;` — o cockpit do vereador
  não deve entrar no caminho da tribuna sem necessidade, mas o TELÃO precisa da tribuna na reconexão.
  Resolver isso explicitamente e justificar no relatório qual tela busca o quê e por quê.
- Testes: `plenario-reducer.test.ts` (hidratação, tolerância a corpo inesperado, precedência do SSE)
  e o teste do hook/tela cobrindo "abrir a tela com fala já em curso" — o caso 2 dos três momentos.

## Task 3 — verificação na stack viva (não é dispensável)

Suíte verde não vê o defeito que este plano existe para matar. Provar na stack de pé:
`./demo/semear-tudo.sh`, iniciar uma fala por POST, abrir o telão, **dar F5**, e o orador continuar na
tela. Depois `./e2e/.sonda/rodar.sh` e confirmar que o placar não regrediu.
