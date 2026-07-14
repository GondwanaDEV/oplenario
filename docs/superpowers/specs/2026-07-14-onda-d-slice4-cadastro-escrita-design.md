# Onda D Slice 4 — Cadastro de Vereadores: escrita (Tier 1)

> Spec de design. Continuação direta da Slice 3 (borda read-only do `cadastros`,
> `2026-07-14-onda-d-slice3-cadastro-vereadores-design.md`). Preenche exatamente os 3
> `EmBreve` que a ficha deixou stub: "Novo vereador", "Editar", "Registrar licença".

**Data:** 2026-07-14
**Branch:** `fe-20-cadastro-escrita` (off `main`)

---

## Objetivo

Dar ao servidor (papel `secretario`) a capacidade de **montar a composição cadastral da Casa**
pela UI: criar/editar vereador, registrar mandato e registrar licença — tudo dentro do bounded
context `cadastros`, sem tocar identidade/CPF nem provisionar login. É o lado de escrita da borda
HTTP que a Slice 3 abriu somente para leitura.

## Fronteira de escopo (Tier 1)

**DENTRO desta fatia:**
- Criar vereador (`nome`, `nome-parlamentar?`); `identidade_id` fica `NULL`.
- Editar vereador (`nome?`, `nome-parlamentar?`).
- Registrar mandato (`legislatura-id`, `partido?`, `natureza`, `vigencia-inicio`, `vigencia-fim?`).
- Registrar licença **record-only**: grava a linha `mandato_licenca` + vira o mandato vigente
  para `estado='licenciado'` numa única transação.

**FORA (deferido a fatias próprias, com revisão de segurança própria):**
- **Tier 2 — vínculo de identidade + provisionamento de login:** ligar vereador → identidade
  (CPF, via o split-privilege `oplenario_id_resolver`), provisionar usuário Keycloak + papel,
  consentimento LGPD. É a vertical pesada de segurança; não entra aqui.
- **Promoção automática do suplente** na licença (§22.5 eixo C: licença → suplente assume o
  exercício). A resolução `tem_mandato_vigente`/`quem_exerce_presidencia` já lê `estado`, então o
  efeito de autorização da licença é imediato mesmo sem promover o suplente — a promoção é
  conveniência de composição, não correção de segurança. Deferida.
- Comissões (membership/cargos), suplência (ordem), legislatura/sessão-legislativa: escrita fica
  para fatias de cadastros seguintes.

## Arquitetura

Estende a silhueta ADR-0001 já provada na Slice 3 com o lado de escrita:

```
diplomat/http/in.clj  (rotas POST/PATCH, gate `secretario` após `auth`)
        │  valida a ENTRADA com…
   wire/in/vereador.clj  (Malli :closed dos corpos de request)
        │
   controllers.clj  (orquestração fina — pass-through + resolução de `hoje`)
        │
   components/repositorio.clj  (RepoCadastros: novos métodos de escrita, cada um numa tx)
        │
   db/vereador.clj · db/mandato.clj  (INSERT/UPDATE HoneySQL)
```

**Invariantes que a fatia honra:**
- **Tenant por RLS** (`RepoCadastros/transacao` = `tenancy/com-tenant*`); todo INSERT/UPDATE carrega
  `ente_id` do ator; JOIN/predicados casam `ente_id` (defesa em profundidade, espelha `relacoes/cadastro`).
- **Inv.10 — sem DELETE.** O grant das tabelas cadastrais é `SELECT, INSERT, UPDATE` (sem DELETE).
  "Remover" um mandato = mudar `estado` (fora desta fatia). Editar = UPDATE.
- **`efetivado_em = now()` direto.** Escrita interativa não passa pelo staging `lote_id`/`ver_lote`
  (esse caminho é só p/ import de legado). Sem `efetivado_em`, a política RLS esconde a linha
  (a cláusula `efetivado_em IS NOT NULL OR lote_id = ver_lote`), então toda escrita da UI seta
  `efetivado_em = now()` no INSERT para ficar imediatamente visível.
- **Authz `secretario`** em toda rota de escrita — mesmo gate da leitura (`it/exige-papel "secretario"`).

## Superfície HTTP

Todas gated `secretario` após `auth`. `ente-id` vem do ator; `hoje` resolvido na borda
(`tempo/hoje-de (tempo/agora relogio) (ZoneId/of "America/Fortaleza")`).

| Método+rota | Corpo (wire/in) | Efeito | Sucesso | Erros |
|---|---|---|---|---|
| `POST /cadastros/vereadores` | `{nome, nome-parlamentar?}` | INSERT vereador (`identidade_id` NULL, `efetivado_em=now()`) | `201 {id}` | 400 corpo inválido · 401 |
| `PATCH /cadastros/vereadores/:id` | `{nome?, nome-parlamentar?}` (≥1) | UPDATE nome/nome-parlamentar da linha efetivada | `200 {id}` | 400 · 404 vereador inexistente/outro-tenant · 401 |
| `POST /cadastros/vereadores/:id/mandatos` | `{legislatura-id, partido?, natureza, vigencia-inicio, vigencia-fim?}` | INSERT mandato (`estado='vigente'`, `efetivado_em=now()`) | `201 {id}` | 400 · 404 vereador/legislatura inexistente · 409 mandato vigente sobreposto · 401 |
| `POST /cadastros/vereadores/:id/licencas` | `{inicio, fim?, motivo?}` | resolve mandato **vigente** do vereador → INSERT `mandato_licenca` + UPDATE `mandato.estado='licenciado'` (1 tx) | `201 {id}` | 400 · 404 vereador inexistente · 409 sem mandato vigente / já licenciado · 401 |

**Decisões de forma:**
- **Licença por vereador, não por mandato-id.** A ficha da UI é centrada no vereador e há
  exatamente um mandato vigente; o backend resolve. Assim a UI não precisa expor `mandato.id` e
  não há como mirar um mandato não-vigente. `409` cobre "sem vigente" e "já licenciado".
- **`natureza`** = `titular` | `suplencia` (CHECK do schema). `estado` do mandato não é entrada —
  nasce `vigente`.
- **`:id` malformado** → 404 (não 500): `parse-uuid` guardado, igual à Slice 3.
- Retorno mínimo `{id}` (não a ficha inteira); o FE dá refetch da ficha/lista após sucesso.

## Regras de domínio

### Mandato sobreposto (nova migration EXCLUDE)
A leitura de Slice 3 não conseguia criar sobreposições; **esta fatia consegue** — é o carry (c)
que a Slice 3 registrou. Adicionamos agora:
- **Migration** `cadastros.mandato` ganha um `EXCLUDE USING gist` anti-overlap de **mandato
  `vigente` (efetivado) por (ente_id, vereador_id)** sobre `daterange(vigencia_inicio,
  COALESCE(vigencia_fim,'infinity'),'[]')`, `WHERE estado='vigente' AND efetivado_em IS NOT NULL`
  (espelha o padrão `uq_uma_mesa_ativa` já no schema; `btree_gist` já está instalado).
- **Guard app-level** no repo: antes do INSERT, checa sobreposição e devolve conflito → a borda
  traduz para `409` (mensagem amigável). A constraint é a rede; o guard é a UX.

### Licença (record-only, 1 tx)
1. Resolve o mandato **vigente** do vereador (`estado='vigente'`, cobre `hoje`). Ausente → conflito → 409.
2. INSERT `mandato_licenca {mandato_id, inicio, fim?, motivo?, efetivado_em=now()}`
   (`mandato_suplente_id` NULL — promoção deferida).
3. UPDATE `mandato.estado = 'licenciado'`.
Passos 1–3 numa única `transacao` do tenant (atomicidade).

## Frontend

Liga os 3 `EmBreve` da ficha (`app/(interno)/cadastros/vereadores/page.tsx`):
- **"Novo vereador"** → form (nome obrigatório, nome-parlamentar opcional) → `POST` → refetch lista + seleciona o novo.
- **"Editar"** (na ficha) → form pré-preenchido → `PATCH` → refetch ficha.
- **"Registrar licença"** (na ficha, só quando há mandato vigente) → form (início obrigatório, fim/motivo opcionais) → `POST /licencas` → refetch ficha (o chip vira "licenciado").
- **Registrar mandato** → form na ficha (seletor de legislatura, partido, natureza, vigência) → `POST /mandatos` → refetch ficha.

**Padrões:** view-models puros p/ validação de form (mensagens de erro em pt-BR, empty/erro
honestos); hooks de mutação (POST/PATCH → invalida/refetch, estados `"pronto"|"enviando"|"erro"`);
mesma linguagem visual (`../sistema/`) e AA medida nos 2 temas (chip "licenciado" âmbar =
`--aviso-texto`, §5.1). Sem novo arquétipo — reusa form/campo/botão do chassi.

## Testes (TDD por silhueta)

- **db** (PG real, role OWNER `oplenario`): INSERT/UPDATE round-trips; isolamento RLS (escrita de
  um tenant não vaza/atinge outro); a tx da licença (estado vira licenciado + linha gravada
  atomicamente); a constraint EXCLUDE rejeita mandato vigente sobreposto.
- **controllers** (DB-free, repo fake): pass-through exato dos args; resolução de `hoje` na borda.
- **diplomat** (borda): 201/200 caminhos felizes; 400 corpo inválido; 404 `:id` malformado /
  inexistente; 409 sobreposição / licença sem vigente; 401 sem papel `secretario`.
- **FE**: view-model puro (validação de cada form); hooks de mutação (sucesso→refetch, erro→estado
  erro sem throw); página (submit, botões desabilitados quando inválido, refetch pós-sucesso).

## Sequência de execução (para o plano)

1. Migration EXCLUDE anti-overlap de mandato (+ down).
2. `wire/in/vereador.clj` — Malli `:closed` dos 4 corpos.
3. `db/vereador.clj` — `inserir!`, `atualizar!`; `db/mandato.clj` — `inserir!`, `vigente-de-vereador`, `licenciar!` (INSERT licença + UPDATE estado), guard de sobreposição.
4. `components/repositorio.clj` — novos métodos de escrita (cada um numa `transacao`).
5. `controllers.clj` — pass-throughs de escrita.
6. `diplomat/http/in.clj` — 4 rotas gated `secretario`; `rotas.clj` já injeta o repo.
7. Codegen (se novos tipos de saída) — provavelmente nenhum; retornos são `{id}`.
8. FE: view-models → hooks de mutação → forms na página.

## Riscos / notas

- **Sobreposição de mandato:** a migration EXCLUDE muda uma tabela já populada em dev/seed — a
  suíte roda em PG limpo, mas o seed demo pode ter dados que violem; validar o seed contra a nova
  constraint (ajustar datas se preciso).
- **`vigencia-fim` opcional** em mandato: NULL = em aberto (consistente com o schema).
- **Reconciliação do carry `cargo-mesa`** (lista via `comissao_cargo` × ficha via `comissao_membro`):
  **não** é resolvido aqui — esta fatia não escreve comissão/cargo. Continua carry até a fatia de
  escrita de comissões criar esse dado.
