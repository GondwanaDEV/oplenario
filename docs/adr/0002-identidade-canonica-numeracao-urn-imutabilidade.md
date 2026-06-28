# ADR-0002 — Identidade canônica, numeração, URN/LexML e imutabilidade (gate de F3)

- **Status:** Aceito · 2026-06-27
- **Decisor:** Daouda Traore (CTO)
- **Fonte canônica:** §22.4 **eixo H** do `documento-mestre-camaras.md` (SSOT) + G17 de `produto/14`
  (URN/LexML, adição v1.38). Esta ADR **crava as decisões concretas** do eixo H **antes de qualquer
  DDL do módulo `legislativo`** — porque retrofitar identidade/numeração/URN é **refactor estrutural**
  (risco 🔴 CRÍTICO de `docs/11`). Em conflito, a SSOT (§22.4) + esta ADR prevalecem.
- **Aplica-se a:** toda tabela de domínio legal do `legislativo` (e, por extensão, atos/normas de F4/F5).

## Contexto

O coração do produto (proposição → norma) precisa de **três identidades distintas**, decididas **antes**
de cravar o schema: (1) a chave técnica interna; (2) a numeração oficial que o cidadão lê (`PL 042/2026`);
(3) a coordenada **pública e interoperável** de norma (URN/LexML — citação cruzada, intercâmbio SAPL/
Interlegis). Misturá-las, ou assumir a #3 conhecida no protocolo, custa um refactor de modelo de dados
depois. A imutabilidade pós-publicação (Invariante 10) também é decisão de schema, não de código só.

## Decisão

### 1. PK interna = UUID (decidido na SSOT — fixado aqui)
Toda tabela de domínio legal tem `id uuid PRIMARY KEY` gerado pelo sistema (`kernel/ids`), usado em
**todas as FKs internas, eventos e payloads de IA**. **Nunca** se expõe o UUID como número oficial nem
se usa a numeração oficial como FK. Desacopla a chave técnica da coordenada de negócio.

### 2. Numeração canônica de protocolo = `(ente_id, tipo, ano, sequencial)` UNIQUE, gapless
- Constraint `UNIQUE (ente_id, tipo, ano, sequencial)` na proposição. O `sequencial` é gerado
  **atomicamente na transação de protocolo**, nunca pelo cliente.
- **Mecanismo (resolve o que a SSOT deferiu ao "chat de stack"): `kernel/sequencial` (F0)** —
  contador-por-linha (`shared.sequencial`, UPSERT atômico), **gapless** (anda só no commit → rollback
  não deixa buraco, ao contrário de `SEQUENCE` nativa) e **tenant-scoped** (ente do GUC `app.ente_id`).
  Escopo = a string **`"{tipo}:{ano}"`** (ex.: `"projeto_lei:2026"`). Rodar **dentro de `com-tenant*`**.
  - *Por que gapless importa aqui:* numeração legislativa oficial **não pode pular** (uma lacuna em
    "Lei 14, 16…" é incidente jurídico). `SEQUENCE` nativa pula em rollback; o contador-por-linha não.
- **Formato de exibição** (`PL 042/2026`) = **template configurável por ente** (coluna no `cadastros`
  ou config do ente), **não engessado no schema**. O schema guarda `tipo`/`ano`/`sequencial` crus.

### 3. URN/LexML = coordenada pública interoperável, **computada e imutável** (G17 — a decisão nova)
- **Formato (padrão LexML Brasil / Interlegis — `[FATO]`):**
  `urn:lex:br;{uf};{municipio-slug}:camara.municipal;{tipo-lexml}:{ano};{sequencial}`
  Ex.: `urn:lex:br;ce;fortaleza:camara.municipal;projeto.lei:2026;42`.
  - `{uf}` = sigla minúscula (`ce`); `{municipio-slug}` = nome normalizado (sem acento, minúsculo,
    `.`-separado) — derivado de `cadastros.municipios` (fonte canônica de UF/IBGE).
  - `{tipo-lexml}` = vocabulário LexML mapeado do `tipo` da proposição (`projeto.lei`,
    `projeto.lei.complementar`, `projeto.resolucao`, `projeto.decreto.legislativo`,
    `proposta.emenda.lei.organica`, …). Tabela de mapeamento `tipo → tipo-lexml` no módulo.
  - `{ano};{sequencial}` = os mesmos do §2.
- **Duas identidades de coordenada, não uma:** a URN do **PROJETO** (acima) nasce no protocolo. A URN
  da **NORMA** (lei promulgada, com numeração PRÓPRIA de lei, distinta do número de projeto) é atribuída
  **na promulgação/publicação** (pós-aprovação, §16.3) — modelada quando esse fluxo aterrissar. **Não
  assumir a URN-de-norma conhecida no protocolo.**
- **Persistência:** coluna `urn_lex text` na proposição, **computada no protocolo** a partir dos
  componentes e **imutável** depois (nível (a) abaixo). Indexável/única por ente para citação cruzada.

### 4. Taxonomia de imutabilidade em três níveis (SSOT §22.4.3 item 4 — fixada com mecanismo)
- **(a) Append-only puro** — `proposicao_texto_versao`, `votos`, `proposicao_transicao_historico`,
  audit, `urn_lex`/numeração de protocolo: **sem UPDATE/DELETE jamais**, enforçado por **trigger no
  banco** (`BEFORE UPDATE/DELETE → RAISE`).
- **(b) Mutação controlada com travamento por estado** — `proposicoes`, `parecer_comissao`, `votacoes`,
  `emendas`: mutação livre **durante** tramitação, **travada em estado terminal** (publicada/arquivada).
  Enforço **duas camadas**: guard no domínio (controller) **+ trigger** que bloqueia UPDATE em row com
  `estado` terminal, **exceto** em fluxo de **correção auditada** (transação com flag explícita
  `app.correcao_auditada` no GUC + linha de auditoria obrigatória).
- **(c) Mutação parcial em campos específicos** — `proposicao_apensacao` (só `desapensada_em` + motivos,
  uma vez): trigger por-coluna, caso a caso.
- O **padrão de trigger** (a) e (b) é **kernel-level reusável** (uma função SQL parametrizável), não
  copiado por tabela — a materializar como helper de migration na 1ª DDL de F3.

## Consequências

- A 1ª migration de F3 (eixo A `proposicoes`) já nasce com: `id uuid`, `(ente_id,tipo,ano,sequencial)`
  UNIQUE, `urn_lex` imutável, e o trigger de imutabilidade-por-estado — **sem retrofit**.
- `kernel/sequencial` (F0) é a dependência direta; o módulo não reinventa numeração.
- O mapeamento `tipo → tipo-lexml` e o `municipio-slug` são **dado** (tabela/derivação), não código por
  ente — honra o Invariante 4 e o white-label.
- A URN-de-norma fica **explicitamente diferida** para o fluxo de pós-aprovação (mesma fatia que cria a
  entidade `norma`/ato promulgado) — registrado para não ser esquecido nem antecipado.

## Alternativas descartadas
- **`SEQUENCE` nativa p/ o sequencial** — descartada: pula em rollback (lacuna na numeração oficial =
  incidente). Contador-por-linha gapless vence (já existe, F0).
- **URN assumida no protocolo como identidade-de-norma** — descartada: a norma tem numeração própria
  atribuída na promulgação; cravar no protocolo forçaria retrofit.
- **Imutabilidade só no service layer** — descartada: sem trigger, um bug de controller ou acesso
  direto corrompe registro legal. Defesa em profundidade (guard + trigger) é a SSOT.
