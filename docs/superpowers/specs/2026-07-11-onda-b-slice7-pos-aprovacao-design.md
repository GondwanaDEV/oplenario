# Spec — Track FE · Onda B · Slice 7 · `pos-aprovacao` (autógrafo + sanção/veto)

> **Fatia** do `docs/13-plano-track-fe.md` (Onda B — "o fluxo diário do servidor"), sétima e ÚLTIMA
> vertical, fecha o **Marco MFE-2** ("o servidor trabalha o dia": protocolar→tramitar→ficha→parecer→
> expediente→**pós-aprovação**, tudo navegável).
>
> Fonte de design: `produto/design-system/o-plenario/telas/pos-aprovacao.html` — features **3.13**
> (autógrafo + envio ao Executivo) e **3.14** (controle de sanção/veto + apreciação do veto),
> `produto/13-decomposicao-features-v1.md` linhas 164-165.
>
> Domínio já fechado desde **F3.8a** (`db/autografo.clj`, `db/tramitacao_executiva.clj`, migration
> `20260620000022`, 7 testes de integração verdes) — **esta fatia é 100% borda** (HTTP + FE), zero
> desenho de domínio novo.
>
> **Data:** 2026-07-11 · **Branch sugerida:** `fe-13-pos-aprovacao` (off `main`).

Escopado via `Workflow` (5 agentes em paralelo: mockup+registro de features, estado do domínio,
template de borda HTTP da fatia mais recente, viabilidade de reuso da votação, convenções de FE) —
achados brutos no journal da run `wf_9baafc1b-8a2`.

---

## 1. Objetivo e escopo

Dar ao servidor o **último passo do dia**: depois de uma proposição ser aprovada em plenário (Slice 4
já mostra isso no board de tramitação), acompanhar o que acontece com ela até virar lei — autógrafo
gerado e enviado ao Executivo, prazo de resposta, sanção/veto, e (se vetada) o desfecho da apreciação
do veto pela Câmara.

**No escopo:**
1. `POST /legislativo/proposicoes/:id/autografo` — gera o autógrafo (numeração gapless) e abre a
   tramitação executiva (`'aguardando'`) na mesma ação — botão **"Gerar autógrafo e enviar ao
   Executivo"** na ficha da matéria, visível quando `proposicao.estado = "aprovada"` e ainda não existe
   autógrafo.
2. `GET /legislativo/proposicoes/:id/pos-aprovacao` — leitura composta (autógrafo + tramitação
   executiva, mesma disciplina de `buscar-ficha-materia`/`buscar-proposicao-detalhe`: uma tx, sem
   short-circuit, `{:autografo nil}` se ainda não gerado).
3. `POST /legislativo/autografos/:id/resposta` — **"Registrar retorno"**: o secretário registra o que o
   Executivo fez (`sancionado` | `sancao_tacita` | `vetado` + `veto-tipo`/`veto-razoes` se vetado).
4. Uma página nova `/pos-aprovacao/[id]` (por `proposicaoId`), linkada a partir da ficha da matéria.
5. Pipeline visual de 5 etapas (Autógrafo → No Executivo → Sanção/Veto → Promulgação → Publicação),
   card do autógrafo, card "prazo do Executivo" (contagem regressiva a partir de
   `autografo.prazo_resposta_em`, que **já existe como dado** — sem tabela nova).

**Fora do escopo, com decisão explícita (não "em breve" — genuinamente não existe ou é outra fatia):**
- **Ação de disparar a votação de apreciação do veto.** O mockup em si **não tem esse botão** (única
  ação visível é "Registrar retorno") — e hoje **não existe NENHUMA UI de votação no frontend inteiro**
  (só o placar read-only ao vivo da sessão; achado do agente `votacao-reuse`). Construir o trigger de
  voto é fatia própria, futura, do domínio de votação como um todo — não específica de veto. Esta fatia
  expõe `POST /legislativo/tramitacoes-executivas/:id/apreciacao` (`apreciar-veto!`, já pronto no
  domínio) pela borda, mas **sem botão de FE** — só leitura do resultado, se/quando carimbado por outro
  canal. É o mesmo tipo de corte já usado em outras fatias (expor a borda completa, FE consome o que já
  tem caminho de chegar lá).
- **Passos 4 (Promulgação) e 5 (Publicação) do pipeline sem dado vivo.** `legislativo.norma` (F3.8b)
  e o artefato de publicação (`transparencia`, já tem rota pública de download por F6c) **não ganham
  borda nova nesta fatia** — são domínios próprios, zero acoplamento com autógrafo/tramitação executiva
  além de "vêm depois". Os 2 últimos passos do pipeline renderizam como **etapas futuras estáticas**
  (mesmo estado do mockup-fonte, que também não tem dado real ali). Vira fatia própria quando/se um
  cliente precisar acompanhar promulgação pela mesma tela.
- **Card "Encaminhamentos externos"** (indicação/requerimento aprovado enviado a órgão externo,
  resposta "atendido"). É **outro domínio** (feature C64 do gap-audit, `produto/16`) — confirmado por
  grep que `encaminhamento` **não existe em lugar nenhum do backend** hoje. Está no mesmo mockup por
  economia visual (mesma "ficha de vida pós-aprovação"), mas não tem nenhuma relação de dado com
  autógrafo/tramitação executiva. Fica de fora inteira — fatia própria se/quando priorizado.
- **Geração automática de autógrafo ao aprovar** (gatilho `proposicao.estado → "aprovada"` disparando
  `gerar-autografo!` sozinho, ou o encadeamento "Encerrar sessão" → autógrafo do gap C34). Continua
  **ação manual do secretário** (botão explícito) — o rito exato de quando/quem gera o autógrafo é
  `[GAP]` regimental (mesma nota já registrada na migration `20260620000022`); automatizar sem esse
  rito fechado seria inventar conteúdo.
- **Worker de prazo/sanção tácita automática** ao vencer `prazo_resposta_em` — já diferido para F5
  (comentário da própria migration). O secretário sempre registra manualmente, inclusive quando o
  resultado real foi silêncio do Executivo (`resultado "sancao_tacita"` no mesmo form).

## 2. Decisão de fundo: por que isto é só borda, sem desenho de domínio

F3.8a já fechou o domínio inteiro (`db/autografo.clj`, `db/tramitacao_executiva.clj`, migration
`20260620000022`, `pos_aprovacao_db_test.clj` com 7 testes cobrindo numeração gapless, append-only,
sanção/sanção tácita/veto, apreciação do veto via votação real do eixo G, guards de state machine e
trava terminal) — e o `Repo` (`components/repositorio.clj` linhas 116-124/487-496) **já expõe todos os
8 métodos** (`gerar-autografo!`, `buscar-autografo`, `autografo-da-proposicao`,
`iniciar-tramitacao-executiva!`, `registrar-resposta-executivo!`, `apreciar-veto!`,
`buscar-tramitacao-executiva`, `tramitacao-executiva-do-autografo`) como wrappers finos de uma
transação cada. **Zero hit** para `autografo`/`tramitacao-executiva` em `wire/`/`adapters/` — a única
coisa que falta é a borda HTTP + o consumo pelo FE. Isso torna esta fatia mecânica e de baixo risco:
replicar a silhueta já provada em `documento`/`protocolo_geral` (Slice 6), sem decisão de schema nova.

**Correção importante achada na pesquisa:** o agente de design-ref levantou como gap a generalização de
`prazo_dominio_ativo` (polimórfico) para bancar o anel de contagem "Prazo do Executivo" — mas isso é
desnecessário: `autografo.prazo_resposta_em` **já é uma coluna concreta da tabela** (migration
`20260620000022`, comentário "prazo de sancao/veto (DADO aqui...)"). O anel é um cálculo client-side
trivial (`prazo_resposta_em - agora`), sem tabela nova nem worker. A generalização polimórfica segue
diferida para quando aparecer um **segundo** domínio de prazo além de proposição/compliance — não é
gatilho desta fatia.

## 3. Backend — contratos novos

### 3.1 Composição no `Repo` (novo método, mesma disciplina de `buscar-ficha-materia`)

- **`buscar-pos-aprovacao [this ente-id proposicao-id]`** (novo) — compõe `autografo-da-proposicao` +
  (se autógrafo existir) `tramitacao-executiva-do-autografo`, numa tx: `{:autografo nil-ou-map
  :tramitacao-executiva nil-ou-map}`. Sem short-circuit no nil do autógrafo (mesmo estilo de
  `buscar-proposicao-detalhe`/`buscar-ficha-materia`) — o controller decide o que vira 404 vs. corpo
  parcial.
- **`gerar-autografo-e-abrir-tramitacao! [this ente-id m]`** (novo, composto) — chama
  `autografo/gerar!` + `exec/iniciar!` na MESMA tx (paralelo a `protocolar!` estendido da Slice 2:
  reaproveita métodos já existentes em vez de duplicar composição). Devolve `{:autografo-id :numero
  :tramitacao-executiva-id}`.
- `registrar-resposta-executivo!` e `apreciar-veto!` **já existem** como métodos do `Repo` — nenhuma
  composição nova ali, só a borda HTTP em cima.

### 3.2 Rotas HTTP (papel `"secretario"`, mesmo gate grosso de toda leitura/escrita interna hoje)

| Rota | Ação | Authz |
|---|---|---|
| `POST /legislativo/proposicoes/:id/autografo` | gera autógrafo + abre tramitação executiva | `exige-papel "secretario"` |
| `GET /legislativo/proposicoes/:id/pos-aprovacao` | leitura composta (autógrafo + tramitação) | `exige-papel "secretario"` |
| `POST /legislativo/autografos/:id/resposta` | registra sanção/sanção tácita/veto | `exige-papel "secretario"` |
| `POST /legislativo/tramitacoes-executivas/:id/apreciacao` | carimba apreciação do veto (sem FE nesta fatia — ver §1) | `exige-papel "secretario"` |

Silhueta idêntica a `documento`/`protocolo_geral` (Slice 6, template extraído em detalhe pela pesquisa):
`wire/in` (`GerarAutografo`/`RegistrarRespostaExecutivo`/`ApreciarVeto`, `:closed true`, `lock-version`
obrigatório nas escritas que mutam `tramitacao_executiva`, nunca aceita `id`/`ente-id`/`created-by` do
corpo) → `adapters/in` (whitelist + `m/explain` + injeta `id`/`created-by` do `ator` resolvido) →
`controllers` (thin, resolve o autógrafo/tramitação ANTES de chamar o `Repo`, devolve `nil` para a
borda traduzir em 404 — nunca lança para not-found) → `adapters/out` (projeta + `validado` contra o
`wire/out`, lança "violação de contrato" se a projeção não bater — defesa contra drift) → `wire/out`
(`PosAprovacaoOut` com `autografo`/`tramitacao-executiva` opcionais; campos de vocabulário como `estado`
seguem `:string`, nunca enum fechado — mesma convenção do módulo). Erros: CAS/guard de state machine
inválido → 400 (mesmo bucket `{:tipo :validacao/invalido}` de todo o módulo; **não existe 409** nesta
base de código); not-found → controller devolve `nil`, diplomat monta 404 explícito.

`POST /legislativo/tramitacoes-executivas/:id/apreciacao` reusa `votacao-id` como **forward-ref sem
FK declarativa** vindo do corpo (a votação real é aberta/encerrada via `POST /sessoes/:id/votacoes*`
já existente, module-owned por `legislativo`, `objeto-tipo "proposicao"`, `quorum-tipo
"maioria_absoluta"` — mecânica já provada ponta-a-ponta em `pos_aprovacao_db_test.clj`,
**sem rota nova aqui**, decisão fechada por §5 do doc-mestre "não construir DSLs/subsistemas
distintos").

### 3.3 Codegen

`gerar_legislativo.clj` ganha 2-3 entradas novas no `manifesto`: `["AutografoOut" wire.out.autografo/
AutografoOut]`, `["TramitacaoExecutivaOut" wire.out.tramitacao-executiva/TramitacaoExecutivaOut]`,
`["PosAprovacaoOut" wire.out.pos-aprovacao/PosAprovacaoOut]` (nesta ordem — o composto referencia os
dois primeiros).

## 4. Frontend — contratos novos

- **Rota:** `(interno)/pos-aprovacao/[id]/page.tsx` (por `proposicaoId`) — segue o padrão de página de
  `expediente`/`editor-proposicao`: `TopoInterno` + composição de hooks + subcomponentes burros.
- **Ponto de entrada:** botão/link "Ver pós-aprovação" na ficha da matéria (`ficha-materia`), visível
  quando `proposicao.estado === "aprovada"`. Se ainda não há autógrafo, a própria página de
  pós-aprovação mostra o botão **"Gerar autógrafo e enviar ao Executivo"** (evita um segundo ponto de
  decisão na ficha).
- **Hooks:** `use-pos-aprovacao.ts` (read, mesmo idioma de `use-ficha.ts`: reseta estado
  sincronamente quando `proposicaoId` muda); `use-gerar-autografo.ts` e `use-registrar-resposta.ts`
  (mutation hooks, mesmo template de `use-criar-proposicao.ts`/`use-gerar-documento.ts`: `vivoRef` +
  `enviandoRef`, `corpoKebab()`, distingue erro tratado de falha de rede).
- **Componentes:** `pipeline-pos-aprovacao.tsx` (o stepper horizontal de 5 etapas — componente NOVO,
  não é o `AzulejoFaixa` já existente de tramitação; candidato a promoção ao chassi só no 2º uso,
  `PADROES-DE-COMPOSICAO.md`), `card-autografo.tsx`, `card-prazo-executivo.tsx` (anel calculado
  client-side a partir de `autografo.prazoRespostaEm`), `form-registrar-retorno.tsx` (modal/form:
  radio sancionado/sanção-tácita/vetado, campos condicionais de `veto-tipo`/`veto-razoes`).
- **Contrato:** `contrato-legislativo.gen.ts` regenerado (`AutografoOut`/`TramitacaoExecutivaOut`/
  `PosAprovacaoOut` novos).
- **Nav:** nenhum item novo em `DESTINOS_NAV` (a página é alcançada só via ficha da matéria, não é
  destino de topo — mesmo padrão de `editor-proposicao/[id]`, que também não tem entrada própria no
  topo).

## 5. Testes

- **Backend TDD red→green:** `Repo.buscar-pos-aprovacao` (composição, autógrafo ausente → `{:autografo
  nil}`, sem lançar); `Repo.gerar-autografo-e-abrir-tramitacao!` (atomicidade — se `iniciar!` falhar,
  `gerar!` desfaz); `wire/http/in` das 4 rotas (201/200/400/403/404, corpo inválido, `veto-tipo`
  ausente com `resultado "vetado"` → 400, autógrafo duplicado para a mesma proposição → 400).
- **Frontend:** hooks (sucesso/erro/loading, guard de unmount); `FormRegistrarRetorno` (campos
  condicionais por resultado); `PipelinePosAprovacao` (5 estados de cada etapa: feita/atual/futura);
  render da página (sem autógrafo → botão gerar; com autógrafo → pipeline+cards).
- **Review `ecc`:** clojure+database (backend), react+security (frontend).
- **E2E manual em Docker:** aprovar uma proposição (ou usar fixture já aprovada) → gerar autógrafo →
  registrar retorno (sanção) → pipeline atualiza; fluxo de veto (registrar retorno vetado → estado
  mostra "aguardando apreciação da Câmara", sem botão de ação); AA nos 2 temas.

## 6. Carries (não bloqueiam esta fatia)

- Trigger de FE para abrir/registrar a votação de apreciação do veto (carry do domínio de votação como
  um todo, não específico desta fatia).
- Passos 4/5 do pipeline (promulgação/publicação) com dado vivo — hoje estáticos.
- Card "Encaminhamentos externos" (C64) — domínio próprio, inexistente no backend.
- Geração automática de autógrafo (encadeamento pós-"Encerrar sessão", gap C34) e worker de sanção
  tácita automática (F5) — ambos dependem de rito regimental `[GAP]`.
- Lista/índice de itens em pós-aprovação (hoje só detalhe por proposição, via ficha da matéria).
