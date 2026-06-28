# oplenario.motor

Motor de DSL/regras de compliance (§22.7) — **biblioteca compartilhada in-process**, não módulo
bounded-context. §22.10: *"`kernel`/`motor` nunca importam um módulo"*; é o **coração compartilhado**
pelos 4 usos da DSL (tramitação §22.4 eixo C, autorização §22.5 eixo B, plenário §22.6, compliance
§22.7) — Disciplina 5. Materializa o **Invariante 4** ("regras de compliance são dados, não código").

Dobrado do seed `../../../../motor-dsl-clj/` (port Clojure validado: 12 testes / 63 asserções, lint
clj-kondo limpo). O seed permanece como **referência validada superseded** — candidato a remoção
quando esta dobra buildar verde sob kaocha.

## Estrutura

**Núcleo DSL (real — realocado verbatim, código validado):**
- `tipos` — sistema de tipos A2 (tipo = mapa de dados; igualdade estrutural).
- `nucleo` — lexer + parser + AST + loader do envelope de compliance.
- `catalogo` — **registry/catálogo B3** (§22.7.6): tipos, enums, registros, builtins e assinaturas de
  funções de relação **declarados em código** + `CATALOGO-VERSAO`. É o que o type-checker lê.
- `verificador` — **type-check do save time** (Eixo A dec. 2): regra mal-tipada não vira `vigente`.
- `runtime` — avaliador tree-walk + loop de referência (materializa→avalia→monitora→audita). O
  `atom` em-memória é **referência**; a persistência de produção mora no schema `compliance` (§22.7.7).
- `templates` — os 4 templates do Eixo C + negativos, verbatim de `docs/05` (`fonte_yaml`; conteúdo `[GAP]`).
- `api` — **fachada pública** (o seam que os módulos importam): `verificar-fonte` (type-check, real/pronto).
  Avaliação e resolução db-backed (`avaliar`, `regras-aplicaveis`, `prazo-vigente`) = **seams ainda não
  estabilizados** — o avaliador puro existe em `runtime/avaliar`, mas a injeção do resolvedor de fatos de
  produção (funções de relação por contexto dono, §22.5.3 disc.5) é o que falta fiar; documentados, não expostos.

**Persistência (stub — `db/`, deferida ao chat de stack §22.4.4):** funções HoneySQL schema-qualified
sobre as 5 tabelas do catálogo. Não materializar o repositório antes da decisão de stack.

## Schema `motor` (migration `…0006-motor-catalogo`)

As **5 tabelas estáticas** do Eixo B (§22.7.6) — a *forma* do motor:
- `template_compliance` (B1, domínio sem `ente_id`) — definição versionada por cópia integral.
- `compliance_regra_tenant` (B2, tenant `ente_id`) — binding por escopo (param/opt-out/pin).
- `prazo_dominio_vigente` (B4, domínio) — referência de prazo com override por circular (S3).
- `calendario_feriado` (B4, domínio) — feriados nacional + municipal.
- `registry_catalogo_versao` (B3, domínio) — log de versão do catálogo p/ re-validação.

**Por que schema `motor` (não `compliance`):** a resolução "regras aplicáveis a um ente" junta
`template_compliance` ⋈ `compliance_regra_tenant`; §22.10 proíbe JOIN cross-schema → definição e
binding **co-localizam** no `motor`. Reconcilia `docs/06` (que dizia "Módulo: compliance", escrito
**antes** da §22.10) com §22.10 l.1265 ("catálogo versionado no `motor`") + o cursor. Revisável.

## Fronteira com `compliance`

O `compliance` (bounded context, schema homônimo) é a **casca tenant** que **opera** o motor: lê as
regras aplicáveis via esta biblioteca, materializa obrigações (`compliance.prazo_dominio_ativo`),
audita (`compliance.compliance_avaliacao`) e gera remessa (`compliance.remessa_gerada`) — runtime,
§22.7.7/§22.7.8, migration `…0005`. O motor **avalia**; o compliance **persiste o ciclo**.

## Testes

`test/unit/oplenario/motor/motor_test.clj` (ns `oplenario.motor.motor-test`) — a suíte de aceitação,
realocada verbatim. Pura (sem DB) → suíte **unit** do kaocha (`clojure -M:test :unit`). O `test_runner`
custom do seed não veio (kaocha descobre).
