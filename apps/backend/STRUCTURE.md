# Monólito modular — O Plenário (backend)

Esqueleto do padrão **ports & adapters (versão Nubank)** consolidado em **§22.10** do documento-mestre.

- `src/oplenario/<ctx>/` — um módulo por bounded context (§22.2). `legislativo/` é o template completo.
  Camadas: `wire/in`·`wire/out`(externo; out→TS) `models/`(interno) `adapters/in`·`adapters/out`(gate por direção: in valida/coage, out projeta/filtra) `db/`(funções; next.jdbc+HoneySQL, **sem ORM**)
  `events/` + `logic` `controllers` `relacoes` · `diplomat/`(IO por direção: `http/in` · `http/out` · `consumers` · `producers`)
  · `components`(recursos = `defprotocol`+`defrecord` co-localizados; Stuart Sierra). **Sem pasta `port/`** —
  o protocolo de saída mora no `http/out` (dep de módulo) ou em `components/` (recurso/estratégia).
- `src/oplenario/kernel/` — compartilhado puro (não importa módulo).
- `src/oplenario/motor/` — o motor de regras (§22.7), **biblioteca compartilhada** (kernel/motor nunca
  importam módulo). **Dobrado** do protótipo `../../prototipos/motor-dsl/`: núcleo DSL real (`tipos/nucleo/catalogo/verificador/
  runtime/templates`) + fachada `api` + persistência stub `db/` (deferida §22.4.4). Schema `motor` na
  migration `…0006` (5 tabelas estáticas do Eixo B §22.7.6). Detalhe em `src/oplenario/motor/README.md`.
- `src/oplenario/{main,sistema,http}.clj` — host/composição.

Comunicação inter-módulo: **só HTTP (diplomat/http/out → diplomat/http/in) ou eventos (producers/consumers)**.
Dentro do módulo: **`adapters/` só é chamado pelo `diplomat/`** (núcleo `controllers`/`logic` trabalha em `models`; tradução wire↔model só na borda) — lint enforça.

## Rodar
- Stack: `docker compose up`
- Testes: `clojure -M:test :unit` · `:integration` · `:e2e`  (suítes em `tests.edn`)

## Módulos de projeção (read-models — §22.10)
- `paineis/` (§16.11 Painéis/Pendências/Notificações) e `tempo_real/` (§22.6 eixo G, fan-out SSE).
- Silhueta enxuta: `consumers` + `db`(read-model) + `wire/`(`in`·`out`)/`models`/`adapters` + `http/in`; **sem** `logic`/`relacoes`. Rebuildáveis do event log.
- **Exceção (GAP 4):** o **ledger de entrega de notificação** (`paineis.notificacao_entrega`, migration `…0004`) é **durável/idempotente** — e-mail/push que saiu não se re-projeta; a *vista* (sininho/inbox) segue dropável. Três planos do evento: outbox (transporte) / SSE (efêmero) / notificações (durável).

## Módulo supratenant/operacional (3ª categoria — §22.10)
- `admin_sistema/` — a área do **operador SaaS**. Dono de verdade **acima da linha do tenant**: schema próprio **sem `ente_id`** (é quem *emite* o `ente_id`), **sem RLS / sem partição** `hash(ente_id)`.
- Dono de: registry de entes/provisionamento, billing/plano, feature flags/config global, principal+papéis do admin interno (RBAC **disjunto** do `usuario_papel` de tenant), auditoria do operador, observabilidade cross-tenant, grant de acesso de suporte (§22.5.2 eixo D). **Não duplica** o catálogo do `motor` — só o opera.
- Atrás do **Keycloak fisicamente separado** (§22.9 Eixo 6). Token do operador **não carrega `ente_id`** → middleware (§22.5 eixo E) só o aceita em endpoints daqui; cross-esfera = 403/404.
- Migration própria: `20260620000003-admin-sistema.*` (schema + registry `ente`). Refs a `admin_sistema.ente` de outros módulos cruzam por **guard de serviço**, nunca FK/JOIN cross-schema.

## TODO (deferido p/ implementação — validação ecc)
- **Carries do F3.8b (pós-aprovação — norma promulgada, 28/06; review ecc clojure+database):** `norma`
  (artefato legal, NÃO-particionada) nasce de um desfecho **promulgável** (`logic/promulgavel?`); **numeração
  canônica gapless** por (ente, tipo_norma, ano) via `kernel/sequencial`; **URN-de-norma LexML** nasce na
  promulgação (`logic/urn-norma`, imutável) — forma canônica `urn:lex:br;uf;municipio:tipo:data;numero` (sem
  `camara.municipal`, ≠ URN da proposição: a lei é do município). Imutabilidade **parcial nível (c)** bespoke
  (`trg_norma_imut_parcial`, espelha apensação): conteúdo legal congela; só `promulgada→publicada` muta (uma
  vez). `tipo-proposicao->tipo-norma` fail-closed (indicação/requerimento/moção não viram norma). **(→ F4)**
  sem eventos (`Norma*`/`Publicacao*`) nem wire/in; a publicação como **feed ao DOM** (artefato oficial) é
  produto posterior. **(→ eixo B)** o texto promulgado (`origem_versao='promulgacao'`) é forward-ref
  `texto_versao_id` — o caller cria a versão. **(→ regimento [GAP])** segmento exato de autoridade da URN por
  espécie (ato da câmara vs lei do município); veto parcial→promulgação parcial. **Aplicado:** clojure-MAJOR
  (guard `texto-versao-id` em `promulgar!`, simetria c/ `publicar!`), 2 MENOR (guard `numero` nil na URN;
  model `texto-versao-id` sem `{:optional true}`); DB-MAJOR (CHECK **bicondicional** `norma_publicacao_coerente`
  — fecha a janela de 'promulgada' com `publicado_em` preenchido via os campos mutáveis do trigger).
- **Carries do F3.8a (pós-aprovação — autógrafo + sanção/veto, 28/06; review ecc clojure+database):**
  `autografo` (artefato legal **append-only puro**, numerado gapless por ente/ano via `kernel/sequencial`,
  UNIQUE por proposição) + `tramitacao_executiva` (state machine sanção/veto: `aguardando` → {`sancionado`|
  `sancao_tacita`|`vetado`}; `vetado` → {`veto_mantido`|`veto_derrubado`}; trava terminal nível b). Apreciação
  do veto **reusa a votação do eixo G** (maioria absoluta; `veto_votacao_id` FK same-tenant). **(→ F3.8b)** a
  promulgação (norma + numeração canônica + **URN-de-norma LexML** + publicação) — `logic/promulgavel?` já
  gate-keeps os 3 desfechos que viram norma. **(→ F5)** o **processamento do prazo** de sanção/veto (sanção
  tácita ao vencer `prazo_resposta_em`) = worker `prazo_dominio_ativo`, mesmo deferimento do
  `proposicao_prazo_ativo`. **(→ track cripto/NFR)** assinatura **ICP-Brasil** do autógrafo = `[GAP]`. **(→
  regimento [GAP])** prazos/base do quórum/veto parcial→promulgação parcial. **(→ F4)** sem eventos de domínio
  (`Autografo*`/`Sancao*`) ainda; sem wire/in. **Não-adotado (com razão):** CHECK `veto_votacao_id NOT NULL`
  nos terminais de veto — `veto_mantido` pode ocorrer sem votação registrada na V1 (sobre-restringe o `[GAP]`
  regimental). **Aplicado:** clojure-M1 (`promulgavel?` puro → `logic`), M2 (guard valida vocabulário do
  `veto-tipo`), m1 (sets de transição → constantes `logic`), m2 (ordem do require); DB-CRÍTICO
  (`exec_veto_requer_tipo` cobre os 3 estados de veto, fecha a janela de terminal incoerente), 2 DB-MAJOR
  (drop de `idx_autografo_proposicao`/`idx_exec_autografo`, redundantes c/ as UNIQUEs), DB-MENOR (CHECK
  `autografo_efetivado_tem_texto`).
- **Carries do F3.7 (eixo G — votação, 28/06; review ecc clojure+database):** `votacoes`+`votos` (nominal,
  append-only, UNIQUE por vereador)+`votos_secretos` (anônimo, sem `vereador_id`/`created_by` — sigilo no
  schema), todas NÃO-particionadas; quórum verificado por **aritmética exata** (`logic/resultado-votacao`,
  inteiros, sem float). **(eixo G → F4)** eventos `Votacao*` (Aberta/VotoRegistrado/Encerrada) + **fan-out
  real-time** do `VotoRegistrado` (sessão nominal ao vivo) = F4 (mesma infra de bus-consumer da integração do
  parecer). **(eixo G → motor, gated)** a verificação de quórum como **regra DECLARATIVA por matéria** via
  DSL do motor = mesmo gate do type-check dos agregadores do parecer; hoje a aritmética vive em `logic`
  (pura/correta). **(eixo G → regimento [GAP])** a BASE exata do quórum por tipo (Casa vs votos válidos vs
  presentes) é detalhe regimental — default defensável hoje. **(eixo G → F4)** prevenção de **voto-duplo no
  SECRETO** é procedimental (presença/cédula) — `votos_secretos` não liga voto→votante por design.
  **Aplicado:** clojure-MAJOR (`apurar` via `linhas->kebab`, robusto vs. namespace por-tabela), 2 MENOR
  (guard de terminal em `encerrar!` + doc de `votos-da-votacao`); DB-MAJOR (FK same-tenant + índice em
  `votacao_corrige_id`, anti cross-tenant), DB-MENOR (remove `idx_votos_votacao` redundante c/ a UNIQUE).
- **Carries do F3.6b (eixo F — texto do parecer + votos divergentes, 28/06; review ecc clojure+database):**
  `parecer_texto_versao` espelha o eixo B (append-only no conteúdo, híbrido inline/URI, promoção
  rascunho→vigente que reaponta `pareceres.texto_vigente_versao_id`), **não-particionada**; `parecer_voto_divergente`
  = aux **append-only puro**. **(eixo B / backport)** o trigger de imutabilidade de conteúdo de
  `proposicao_texto_versao` (mig 0015) **NÃO congela `origem`/`origem_importado_em`** — o trigger do parecer
  AGORA congela (DB-MENOR); fazer o mesmo na proposição numa **migration de hardening do eixo B** (nova migration,
  nunca editar a 0015). **(eixo F → F3.6c)** o `voto_relator` na entity + agregadores que consomem os votos
  divergentes (`pareceres.algum_rejeitou` etc.) entram no F3.6c. **Aplicado:** clojure-MAJOR (pré-check de
  terminal em `promover!` → erro inspecionável antes do trigger opaco, via `logic/estados-parecer-terminais`),
  clojure-MENOR1/2 (testes cross-parecer + parecer-terminal), clojure-MENOR3 (`voto` `[:string {:min 1}]`),
  DB-MENOR (congela `origem`/`origem_importado_em` no trigger do parecer).
- **Carries do F3.6a (eixo F — parecer, NÚCLEO, 28/06; decisão de reuso do motor por workflow + review ecc):**
  Decisão **(b)** (engine próprio reusando o avaliador DSL + tabelas de template compartilhadas; NÃO generaliza
  o engine do eixo C). **(eixo F / disc.6 §22.4.3)** extrair o **core de transição compartilhado**
  (proposição+parecer+Mesa) no **3º sujeito** (eleição da Mesa §22.5) — hoje `parecer_tramitacao` e `tramitacao`
  duplicam ~15 linhas de orquestração (FOR UPDATE + CAS + 1º-guard-que-passa); o comentário cross-ref está nos
  dois, mas **falta um teste de paridade** (cenário guard+bloqueio idêntico nos 2 engines = alarme de drift se
  um fix de concorrência cair só num lado). **(eixo F → F3.6b)** `parecer_texto_versao` (padrão eixo B) +
  `parecer_voto_divergente` (aux append-only). **(eixo F → F3.6c)** os **7 eventos tipados** `ParecerComissao*`
  (mapeamento `para_estado`→evento) + o **consumer PUSH da mãe** que reavalia (§22.4:26, `diplomat/consumers` é
  stub) + os **agregadores DSL** (`pareceres.todos_concluidos`/`algum_rejeitou`/…) no RegistroFatos do motor.
  **(eixo F → DIFERIDO F5)** `ParecerComissaoPrazoVencido` + `prazo_vencido_em_alguma` (materialização automática
  de prazo — overlap `proposicao_prazo_ativo`/§22.7.7; cross-schema proibido §22.10); o terminal `prazo_vencido`
  fica alcançável por transição explícita. **(Inv.10/armadilha herdada)** o trigger `imut_trava_estado_terminal`
  lê TG_ARGV literal, não `template_estado.terminal` — um template que marque terminal fora dos 4 não trava o
  UPDATE no banco (documentado no comentário do trigger, igual à proposição). **(forward eixo G)** parecer como
  objeto polimórfico de votação (`objeto_tipo='parecer'`). **Aplicado:** clojure-MAJOR1 (`:id` param em
  `registrar-transicao!` p/ paridade), clojure-MAJOR2 (tipo-desconhecido ≠ objeto-órfão), clojure-MENOR1 (guard
  fail-closed p/ parecer inexistente), 2 MENOR de comentário; DB-MAJOR (índice FK `template_id`), DB-MENOR
  (índice parcial `relator_id`).
- **Carries do F3.5 (eixo E — apensação, 28/06; review ecc clojure+database incorporada):** (eixo E /
  regimento) **colapso vs cadeia** — hoje `cadeia` é sempre traversal genuíno (não colapsa níveis); se
  é colapso automático universal ou variável por câmara é **[GAP] regimental** deferido (§22.4.4), não
  se crava. (DB, hardening project-wide) o **soft-delete via `efetivado_em`→NULL** foi fechado AQUI por
  trigger bespoke nível (c); o **mesmo vetor existe nas tabelas que usam o helper compartilhado de
  estado-terminal** (emendas/proposições não guardam `efetivado_em`) — candidato a guarda uniforme num
  hardening pass. (eixo E) `ato_apensacao_ref`/`ato_desapensacao_ref` são **forward-refs sem FK** (a
  entidade despacho ainda não existe; mesmo padrão de `origem_ref`). (eixo E) **sem evento de domínio**
  (a §22.4 eixo E não lista, como o D) e **sem wire/in+controllers** (borda HTTP posterior, como B/D).
  **Aplicado nesta rodada:** 2 índices FK não-parciais (DB-M1); guarda one-way de `efetivado_em` +
  teste (DB-M2); `origem_importado_em` no congelado do trigger (DB-m1); `km/Instante` canônico no model
  (clojure-consistência); comentário de params posicionais + asserção de ciclo exata (2 MENOR).
- **Carries do F3.4 (eixo D — emendas, 28/06; review ecc clojure+database incorporada):** (eixo D / regimento)
  **precondição fina de `aprovar!`** — hoje só barra estado terminal (aprova de `apresentada`|`admitida`); exigir
  `admitida`? trilho rápido p/ emenda de plenário (`momento='plenario'`)? = **[GAP] regimental** deferido ao
  especialista (§22.4.4), não se crava. (DB/clojure, hardening) `numero_local` por `SELECT MAX+1` tem janela
  TOCTOU — a UNIQUE `(ente_id,mae,numero_local)` barra (erro não-silencioso, sem retry), **mesmo padrão de
  `nova-versao!`**; candidato a advisory-lock junto do hardening de F3.2. (DB, quando houver requisito) coerência
  `autor_tipo`⋈`autor_id` está frouxa de propósito (import/externo usa `autor_texto`) — CHECK só quando regra de
  cliente exigir. (FE0/wire-in) o XOR inline/uri **não** é validado no model Malli (igual ao `texto-versao`) — vai
  no `adapters/in` quando o eixo D ganhar `wire/in`+`controllers` (hoje só db+model+repo, como o eixo B). **Aplicado
  nesta rodada:** CHECK `emenda_aprovada_requer_versao` (DB-C1); `mudar-estado!` recusa `'aprovada'` (DB-M2);
  `aprovar!` crava proveniência (destructure só do conteúdo do seed, não `merge` — clojure-MAJOR).
- **Carries do F3.3b (eixo C — emissão + guard-gate, 28/06; review ecc clojure+database incorporada):**
  (eixo C / catálogo) **type-check estático COMPLETO do guard** no save — hoje `motor/validar-guarda` é
  só SINTÁTICO (parse); o type-check vs `Booleano` exige catalogar o vocabulário de tramitação (registros
  `proposicao`/`contexto`) no registry do motor (análogo a §22.7.5 p/ compliance). (eixo C) **integridade
  referencial dos estados**: `template_transicao.{de_estado,para_estado}` e `template_tramitacao.estado_inicial`
  são texto livre p/ `template_estado.chave` SEM FK/CHECK — adicionar validação no save (ou FK composta
  `(ente_id,template_id,chave)`) p/ barrar transição p/ estado inexistente (slice de autoria de template).
  (DB, nova migration) `proposicao_transicao_historico.lote_id` + índice de staging são **inertes** (append-only
  nunca estagia) — remover; e o trigger BEFORE-ROW append-only exige **PG≥13** em tabela particionada (stack
  CloudNativePG PG14+ OK; considerar piso de versão no tooling de migration). (DIFERIDO de F3.3a, ainda aberto)
  `proposicao_prazo_ativo` (materialização de prazo de tramitação — overlap com `prazo_dominio_ativo` §22.7.7,
  candidato a F5) + **action-handlers ricos** (a `:acao` da transição é gravada/retornada mas não executa).
- **Carries da auditoria ampla (F3 hardening, 28/06):** (MAJOR) `kernel/tenancy/com-correcao-auditada*` com authz — único caminho a setar o GUC `app.correcao_auditada` (hoje só o teste seta; o lint barra src fora do kernel) — construir junto do fluxo de correção; fiar `outbox-relay`+`scheduler` no `sistema.clj` + teste de boot do system-map (F4); `GRANT SELECT` nas tabelas `motor.*` p/ `oplenario_app` na migration de F5. (MENOR) `db/vinculo` `criar!`→`inserir!` (consistência); `legislativo/models` alias `CriadoEm`; `parse-memo` limitado (motor, pré-F5); `main` shutdown-hook em try/catch; `verificador` atom→`reduce`; `membros-da-casa` `ente_id` explícito; senha `oplenario_pool` literal na migration 0009 → placeholder (pré-prod); strip de CPF no `wire/out` de identidade (FE0); `ex-info` de `municipio-slug` sem o nome cru (F7 logs).
- `relacoes/` → registro no catálogo do `motor` (depende da dobra do `motor-dsl-clj`).
- `policy.clj` por módulo (política declarativa; mecânica em `kernel/autorizacao`).
- HoneySQL **sempre** schema-qualified (`:legislativo.proposicao`); **nunca** `search_path` global (vaza entre módulos no pool compartilhado).
- `arquitetura_test.clj`: implementar a varredura real da matriz de import-lint.
- Dockerfile de produção: uberjar + `eclipse-temurin:21-jre`.
- `sistema.clj`/`legislativo/components.clj`: fiação `using` mínima como exemplo-template.
- `compliance` (§22.7): avaliar **CHECK constraints** em `compliance_avaliacao.veredito`/`origem_avaliacao` (hoje texto+comentário, padrão do projeto — valor é gerado pelo motor em código, não input). Hardening adiado: a tabela é a prova do Invariante 10 (sem UPDATE/DELETE p/ corrigir dado corrompido). Decidir junto da dobra do `motor-dsl-clj`.
- `motor` (§22.7, **dobrado**): (a) **persistência real** do `db/*.clj` sobre as 5 tabelas do catálogo — stub hoje, deferida ao chat de stack (§22.4.4: não materializar o repositório antes); (b) **orquestração de runtime** que o `compliance` opera — o `runtime` em-memória (`atom`) é referência; produção persiste em `compliance.prazo_dominio_ativo`/`compliance_avaliacao` chamando `motor/avaliar` com **resolvedor de fatos injetado** (funções de relação via contexto dono, §22.5.3 disc.5), não o `:estado` em-memória; (c) popular `[GAP]` de `calendario_feriado`/`prazo_dominio_vigente` (conteúdo regulatório real, com o especialista em regimento). **Reconciliação registrada:** as 5 tabelas do Eixo B vão ao schema `motor` (não `compliance` como dizia `docs/06` pré-§22.10) — razão: resolução junta definição+binding, sem cross-schema JOIN (§22.10). **Seed `../motor-dsl-clj/` superseded** — candidato a remoção quando a dobra buildar verde sob kaocha.
