# ADR-0006 — Satélite de IA em `apps/ia/` (Python): silhueta, porta de inferência e trilho de CI

- **Status:** Aceito · 2026-09-26
- **Decisor:** Daouda Traore (CTO) — "Confirmo" sobre o desenho da IA (`docs/25`) e o plano (`docs/26`) dado com o
  merge do PR #38; esta ADR materializa a fatia **0.1** da base comum do plano.
- **Fonte canônica:** §22.2 (satélite separado, Python), §22.3 (contrato core↔IA), §22.9 Eixos 10/11/13 (porta de
  inferência, transporte, failover) e §22.11 (IA como ator) do `documento-mestre-camaras.md` / `arquitetura/`.
  Em conflito, a SSOT prevalece.
- **Aplica-se a:** `apps/ia/` (novo) e ao `.github/workflows/ci.yml` (job `ia`).

## Contexto

A §22.2 decidiu, desde o dia 1, que a Plataforma de IA é um **satélite separado**: Python-nativo, GPU-aware, com
dependências pesadas que não entram no container do core, escala própria. Até 26/09/2026 o satélite tinha **zero
código** — só o protótipo de forma do filtro de governança (`prototipos/governanca-ia/`, Clojure) e o PoC de áudio
(`prototipos/poc-audio/`, fora da `main`). A Track IA (`docs/26`) começa por uma **base comum pequena** de que as
duas faixas dependem: esqueleto (0.1), porta de inferência (0.2), filtro de governança de produção (0.3), Camada de
Confiança mínima (0.4) e avaliação + custo (0.5). Sem uma forma decidida, cada capacidade de IA inventaria a sua.

## Decisão

1. **Lugar e linguagem.** `apps/ia/` no monorepo, ao lado de `apps/backend/` e `apps/frontend/`. **Python 3.12**,
   pacote `oplenario_ia` em `src/`, empacotado por `pyproject.toml`. Roda em container (mandato Docker); o
   `Dockerfile` do satélite é a imagem de produção.
2. **Silhueta por camada de plataforma, não por feature** (Invariante 3):
   - `inferencia/` — a **porta** vendor-agnóstica (§22.9 Eixo 10): um protocolo, um adaptador **fake determinístico**
     (testes, CI, avaliação — nunca espera o jurídico) e um adaptador por fornecedor real, escolhido por config.
     Cada adaptador usa o **SDK oficial** do fornecedor; retries do SDK **desligados** — retry, backoff e failover são
     política nossa (§22.3.5, Eixo 13), não do SDK. O fornecedor e o modelo **efetivamente usados** saem sempre na
     resposta.
   - `governanca/` — o **filtro fail-closed** (B1–B4), único ponto de saída para o LLM externo. Porta de produção do
     protótipo, com as 6 limitações dele corrigidas. Todo pedido ao LLM passa por aqui; nenhum adaptador é chamado
     por fora.
   - `confianca/` — a **Camada de Confiança mínima** (§16.8) como infra reutilizável: citação (por dispositivo,
     §22.11.7), indicação de incerteza, registro auditável sem conteúdo, reportar erro, revisão humana, e o piso
     **R-IA-1** ("IA indisponível — siga pela tela").
   - `avaliacao/` — o **harness de avaliação** que roda no CI (§22.11.8) e o **registro de custo** por execução com
     `ente_id`.
   - `nucleo.py` — o **pipeline único** que toda capacidade usa: filtro → porta → registro de execução →
     artefato com incerteza, ou o piso R-IA-1. Capacidade nova (ata, resumo, busca, copiloto, conferência) **compõe
     o núcleo**, não chama a porta direto.
3. **Borda HTTP:** FastAPI (HTTP/JSON com OpenAPI é o protocolo síncrono do §22.3.2). Nesta fatia só `/saude` e o
   **contrato de erro** estruturado com as **6 categorias** do §22.3.5. Endpoints nascem com a capacidade que os usa
   (régua §15), não antes.
4. **Contratos em Pydantic v2**, com os nomes do domínio em português (mesma disciplina do Malli no core). Quando a
   fronteira ganhar eventos de integração (Faixa A), o schema é versionado como manda o §22.3.3.
5. **Registro de execução append-only e sem conteúdo** (B4, §22.11.8.5): hashes SHA-256, contagens, vendor, modelo,
   tokens, custo, decisão. Nesta fatia o armazenamento é uma **porta** com adaptador em memória e em arquivo JSONL
   append-only; o adaptador Postgres (schema próprio do satélite, §22.3.4) entra com a primeira capacidade que roda
   em produção.
6. **CI:** job `ia` no `ci.yml` — `ruff` (lint), `mypy --strict` (tipos), `pytest` e o **conjunto de avaliação** da
   base, sempre contra o adaptador fake (custo zero, determinístico). Rodar a avaliação contra fornecedor real é
   comando manual, obrigatório antes de trocar fornecedor ou modelo (§22.11.8), porque gasta dinheiro.

## Enforcement

- **Único caminho até o LLM:** o adaptador real só é construído pela fábrica da porta e só é chamado pelo filtro;
  teste de arquitetura (`tests/test_arquitetura.py`) falha se algum módulo fora de `inferencia/` e `governanca/`
  importar um adaptador real ou o SDK de um fornecedor.
- **CI bloqueante:** lint, tipos, testes e avaliação reprovam o merge.

## Consequências

- Capacidades de IA ganham, de graça, governança, confiança, custo e avaliação — e não podem pular nenhuma.
- A escolha de fornecedor continua **config de deploy** (Eixo 10): o adaptador fake é o padrão, então nada sai do
  cluster até alguém configurar um fornecedor com DPA de não-treino (`[GAP]` jurídico).
- O fallback de modelo **server-side** que alguns fornecedores oferecem (reexecutar em outro modelo em caso de
  recusa) **fica desligado**: trocaria o modelo por conta própria, contra a regra de nunca trocar de modelo em
  silêncio (§22.3.5, §22.11.8). Recusa volta como resultado explícito — o piso R-IA-1 com motivo `recusa`, tokens e
  custo registrados — e o failover é nosso (Eixo 13, fast-follow).
- Streaming (SSE) e fila entram com as capacidades que precisam deles (copiloto interativo; transcrição/ata).

## Alternativas descartadas

- **Satélite em Clojure (como o protótipo de governança):** contraria a §22.2 (Python-nativo, ecossistema de
  ASR/embeddings/GPU). A política de governança continua **semente core-owned** — a proveniência de sigilo nasce no
  core e viaja com o conteúdo; o satélite **lê a tag**, não re-deriva.
- **Framework de agentes pronto (LangChain & afins):** prende a fornecedor e esconde a chamada — contra §22.11.5
  (laço próprio e fino) e o failover do Eixo 13.
- **HTTP cru em vez do SDK oficial do fornecedor:** perde tipos, exceções por status e manutenção do fornecedor; a
  porta já isola o SDK do resto do código.
- **Retries do SDK ligados:** duplicariam o backoff do §22.3.5 e esconderiam a falha do failover.
