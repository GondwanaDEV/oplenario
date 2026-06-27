# oplenario.compliance

Módulo (bounded context §22.2) — **dono da execução do compliance por tenant**. Mesma silhueta de
`legislativo` (§22.10): `schema/ models/ adapters/ db/ events/ logic controllers relacoes diplomat/(http/in·http/out·consumers·producers) components — sem pasta port/`.

## Tabelas (schema `compliance`, migration `…0005`)
- **`prazo_dominio_ativo`** — obrigação materializada com prazo (§22.7.7). **Polimórfica** (`objeto_tipo`/`objeto_id`,
  disc.6: `proposicao_prazo_ativo` → `prazo_dominio_ativo`). Ciclo enum **fixo em código**:
  `pendente|cumprida|vencida|dispensada|cancelada`. Só o sabor **deadline-bound** materializa; o **contínuo** não.
- **`compliance_avaliacao`** — **append-only**, a *prova de compliance* (Invariante 10). `veredito` ∈
  `conforme|nao_conforme|inaplicavel`; `origem` ∈ `evento|sweep|sob_demanda`. `obrigacao_id` é `NULL` para
  regra contínua / `inaplicavel`.
- **`remessa_gerada`** — artefato de envio ao TCE (§22.7.8). O **artefato é imutável por versão** (re-emissão =
  **nova versão**, nunca muta `hash`/ref — `UNIQUE(ente_id, template_chave, competencia, versao)`); já o **estado
  de submissão evolui** no ciclo `rascunho|validada|submetida|aceita|rejeitada` (UPDATE). O binário mora no
  `objeto_store` (aqui só metadados + ponteiro). **Costura:** `remessa_enviada(ente, sistema, competencia)` é
  verdade em **`aceita`** (rejeição **não** cumpre). Por isso a tabela **não** é append-only pura como
  `compliance_avaliacao` — só o registro do artefato/versão não é apagado nem sobrescrito.

## Forma da remessa (§22.7.8)
- Ports de saída: **`SerializadorRemessa`** (1 adapter SIM; generaliza no 2º TCE/S2), **`TransporteRemessa`**
  ("download manual" V1; API do TCE deferida `[GAP]`), **`fontes`** (read-ports **em lote** p/ proveniência, D7).
- **`gerador_remessa`** = renderizador próprio do **descritor declarativo de layout** (dado, dec. 2b — reusa o
  registry como fonte; **não** estende a DSL de avaliação, **não** é código por TCE — honra o Invariante 4).

## Fronteiras
- O **catálogo** (`template`/`regra`, domínio sem `ente_id`) e o **binding por tenant** vivem no `motor`
  (§22.7.6) — chegam com a dobra de `../motor-dsl-clj/` → `src/oplenario/motor/`.
- A **avaliação da DSL** é do `oplenario.motor`; este módulo é a casca **tenant** (runtime + remessa) que o opera.
- O **layout físico do SIM** = `[GAP]` de conteúdo regulatório real — não inventado.
