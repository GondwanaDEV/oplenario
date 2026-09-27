# ADR-0010 — Identidade delegada do agente: credencial opaca emitida pelo core, ator com `:via`, interseção a cada chamada

- **Status:** Aceito · 2026-09-27
- **Decisor:** Daouda Traore (CTO) — execução da fatia **B.2** do plano da Track IA (`docs/26`), sob o "Confirmo"
  dado com o merge do PR #38.
- **Fonte canônica:** `docs/25` Eixo 3 (3.1–3.5), Eixo 4.2/4.4, Eixo 5.2 (navegador → core → satélite; o core emite
  o token delegado); §22.5 (sessão opaca, `resolver-sessao`, fail-closed); ADR-0009 (catálogo). Em conflito, a
  SSOT prevalece.
- **Aplica-se a:** `apps/backend` — `identidade` (credencial e resolução), `kernel/catalogo.clj` (interseção de
  classes e audit), `oplenario.catalogo` (conjunto pelo público da credencial), `integracao_ia` (registro das
  chamadas), `interceptors.clj` (porta do agente).

## Contexto

O Eixo 3 decidiu que o agente age **em nome de** uma pessoa, com escopo reduzido, auditado como "Fulano, via agente
X", e que a permissão efetiva é a **interseção** avaliada a cada chamada. O 3.4 apontou o mecanismo — token com a
pessoa como sujeito e o agente como ator (token exchange, RFC 8693, no Keycloak) — pedindo para **confirmar o
suporte na versão em uso**.

**Spike (27/09/2026), pelas notas de versão do Keycloak:**
- a versão em uso (**26.0.0**) só tem o token exchange **legado (V1), em preview**, atrás de feature flag;
- a **26.2** tornou suportado o *standard token exchange* (V2), mas **só interno→interno**, explicitamente **sem
  impersonação nem delegação**;
- a delegação (validar que quem pede pode agir por aquela pessoa, com consentimento) chegou na **26.7 como
  experimental** (`token-exchange-delegation`).

Não há, portanto, delegação suportada no Keycloak para apoiar a produção. E o caminho decidido no 5.2 não precisa
dela: a pessoa já está autenticada **no core** (sessão opaca de cookie, §22.5), e é o core que invoca o satélite.

## Decisão

1. **O core emite a credencial delegada** (`identidade.credencial_agente`, mig 0092), no mesmo molde da sessão
   opaca de login: segredo aleatório de 256 bits, **só o hash em repouso**, tabela supratenant acessível só ao
   `oplenario_id_resolver`. Uma credencial por **execução** do agente (`execucao_id` único), com: a pessoa
   (`identidade_id`), a Casa (`ente_id`), o `agente`, o **público** cujo conjunto de ferramentas vale, as **classes
   concedidas** e um prazo curto (**15 min**). Revogável (`revogada_em`) quando a execução acaba ou a pessoa fecha
   o painel. `identidade.autenticacao/emitir-credencial-agente!` emite a partir do ator da sessão de quem invocou.
2. **A credencial não guarda permissão.** A cada chamada, `resolver-agente` resolve a pessoa **agora** pela mesma
   `resolver-sessao` das telas (vínculo ativo + papéis do momento) e só acrescenta `:via {:agente :execucao-id
   :publico :classes :institucional?}`. Vínculo suspenso ou mandato encerrado derruba o agente na chamada seguinte.
3. **Interseção (3.2)** = papéis da pessoa agora (`exige-algum-papel!`, como antes) ∩ conjunto do público da
   credencial (`oplenario.catalogo`: ferramenta fora dele não existe) ∩ classes concedidas (`exige-classe!` no
   kernel do catálogo). O catálogo **só atende ator com `:via`**.
4. **`ato` por agente nunca executa direto** — nem com a classe concedida: é negado (`:ato-so-por-proposta`) até a
   proposta de ato da B.6 (entregue: ADR-0012 — o `ato` pedido por agente agora vira proposta, e `:ato-so-por-proposta` deixou de existir), confirmada pela pessoa na tela (Eixo 4.2 B). O **agente institucional** (sem pessoa,
   3.1 b) nunca recebe `ato` — `CHECK` no banco e negação no kernel; o ator dele nasce sem papel algum até a
   concessão pelo `admin_ente` existir (B.8), então nada executa por construção.
5. **Portas separadas.** Um interceptor novo, `autenticacao-agente`, aceita **só** a credencial delegada (bearer) —
   é o que as rotas do catálogo (o MCP da B.3) usarão. O interceptor das telas não reconhece a credencial (nem como
   bearer, nem como cookie), e o do agente não reconhece a sessão da pessoa. É o "token de agente só é aceito em
   operações que estão no catálogo" do 3.4.
6. **Audit (3.5).** Chamada de agente a ferramenta que **escreve** (`rascunho`/`ato`) vai sempre a
   `integracao_ia.chamada_agente` (mig 0093, append-only, RLS): pessoa + agente + execução + ferramenta + classe +
   desfecho (`ok`, `nao_encontrado`, `negado`, `invalido`, `erro`) — tentativa negada inclusive. Sem o seam de
   registro, a escrita por agente não roda. Leitura segue a regra das telas; o registro completo de cada execução
   fica no log de inferência do satélite (§22.3.4).
7. **Teste de vazamento ganha a dimensão agente** (`catalogo_db_test`): o agente da Casa A não enxerga matéria nem
   sessão da Casa B (por id ou por número), e a credencial emitida na Casa A resolve sempre na A, mesmo que a pessoa
   tenha vínculo na B — o ente vem da credencial, nunca da chamada.
8. **Keycloak fica para o agente de fora.** Quando o Eixo 6 (cliente externo via MCP, consentimento com validade e
   revogação) entrar, reavaliar a delegação do Keycloak (experimental na 26.7) ou emitir pelo mesmo mecanismo desta
   ADR com consentimento explícito. Nenhum upgrade do Keycloak é necessário para a B.2.

## Enforcement

- `catalogo_db_test` (PG real): credencial ponta a ponta, revogação, expiração, vínculo suspenso, institucional sem
  `ato`, conjunto por público, e o vazamento na dimensão agente.
- `credencial_agente_interceptor_test`: portas separadas entre tela e agente.
- `kernel.catalogo-test`: classe não concedida, `ato` por agente, audit com desfecho, escrita sem seam não roda.
- Banco: `CHECK institucional_nunca_ato`, `CHECK` de classes e de prazo; grants só ao `oplenario_id_resolver`.

## Consequências

- O agente nunca tem mais permissão que a pessoa, e perde a que ela perder, na hora.
- Não há token de IdP em repouso nem dependência de feature experimental do Keycloak.
- Uma execução de agente é rastreável de ponta a ponta pelo `execucao_id` (credencial, audit no core, log no
  satélite).
- Credencial vazada vale no máximo 15 minutos, só nas rotas do catálogo, só com as classes daquela execução.

## Alternativas descartadas

- **Token exchange do Keycloak agora:** V1 é preview e V2 não delega na 26.x suportada; subir a versão para uma
  feature experimental troca um risco pequeno por um grande.
- **Repassar a sessão da pessoa ao satélite:** daria ao agente tudo o que a pessoa pode (sem interseção) e a sessão
  abriria qualquer rota de tela.
- **JWT assinado pelo core:** não revoga antes do prazo sem lista de revogação, e a checagem viva do vínculo já
  exige ir ao banco a cada chamada — a credencial opaca faz as duas coisas no mesmo lugar.
