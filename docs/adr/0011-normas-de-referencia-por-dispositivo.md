# ADR-0011 — Normas de referência por dispositivo: módulo `normas`, parser determinístico e conferência humana

- **Status:** Aceito · 2026-09-27
- **Decisor:** Daouda Traore (CTO) — execução da fatia **B.4** do plano da Track IA (`docs/26`), sob o "Confirmo"
  dado com o merge do PR #38.
- **Fonte canônica:** `docs/25` Eixo 7 (7.1 camadas, 7.2 forma por dispositivo, 7.3 entrada com conferência, 7.4 onde
  vive, 7.5 como o agente consulta); fundação #2 (lote com efetivação); §22.10 (módulos). Em conflito, a SSOT prevalece.
- **Aplica-se a:** `apps/backend` (módulo novo `normas`, mig 0094) e `apps/frontend` (telas `/normas`).

## Contexto

O agente precisa citar "art. 12, § 1º, da LOM" — dispositivo lido na mesma execução (Eixo 7.5). Para isso a norma tem
de existir **por dispositivo**, com endereço estável, e só valer depois de uma pessoa conferir (Eixo 7.3). O
`legislativo.norma` que existe é a norma **produzida pela Casa** (lei promulgada na plataforma, com URN e
imutabilidade); a norma **de referência** (LOM, Regimento, CF, leis anteriores) tem outro ciclo de vida — importação,
conferência, versões consolidadas — e camadas que não são da Casa (federal, estadual).

## Decisão

1. **Módulo novo `normas`** (bounded context "conhecimento normativo de referência"), na matriz do import-lint. Não
   importa ninguém; o município da Casa chega por seam do host sobre `cadastros`.
2. **Três tabelas** (mig 0094): `norma` (identidade: camada, espécie, número, título), `versao` (cada importação: o
   texto como chegou, o hash, os alertas do parser, a fonte, "consolidada até", o estado) e `dispositivo` (append-only:
   endereço, rótulo, tipo, pai, ordem, texto, agrupador). Estados da versão: `em_conferencia` → `vigente` (a anterior
   vira `substituida`, guardada) ou `descartada`. Uma vigente e uma em conferência por norma, no banco.
3. **Camadas (Eixo 7.1)** com o dono na linha: federal e estadual **sem `ente_id`** (curadoria do produto, visíveis a
   todas as Casas); municipal (LOM, leis — carrega `municipio_ibge`) e casa (Regimento, resoluções) com o `ente_id` da
   Casa que conferiu. RLS: lê referência + a da Casa; escreve só a da Casa. Quando a Prefeitura existir, a
   visibilidade da camada municipal passa a ser pelo município.
4. **Parser determinístico, sem IA** (`normas.logic/dispositivos`): artigo (`Art. N`, `Art. N-A`), parágrafo (`§ N`,
   parágrafo único), inciso (romano + travessão), alínea (`a)`), item; agrupadores (Título, Capítulo, Seção) com o nome
   na linha seguinte. Endereço no estilo LexML (`art12_par1_inc2_ali1`, `art2_cpt_inc1`, `art10_par1u`) e rótulo de
   citação ("art. 12, § 1º, II, a"). O que ele não entende vira **alerta** para quem confere (salto na numeração,
   dispositivo repetido, inciso fora de lugar, nenhum artigo). A IA não quebra o texto: um parser testável e a
   conferência humana são mais baratos e auditáveis que um modelo, e o Eixo 7.3 exige a pessoa de qualquer jeito.
5. **Curadoria pela secretaria** (`/normas`): importar texto (colado ou .txt; o corpo desta rota aceita até 4 MiB, o
   resto da borda segue com 256 KiB), conferir lendo o texto separado com os alertas no alto, e publicar em dois passos
   ou descartar. As rotas ficam **fora do catálogo** (curadoria é ato de pessoa); o agente lê norma pelas ferramentas da
   B.5, e só a versão vigente.

## Fica para depois (sem reabrir esta ADR)

- **B.4b** — evento de vigência → índice do satélite (Eixo 7.4: busca e embeddings na IA).
- **B.4c** — PDF e OCR; coleta de fonte pública (LexML, sites das câmaras). Hoje a rede do ambiente de desenvolvimento
  bloqueia os sites oficiais, e os textos reais de Baturité e Fortaleza ainda não chegaram.
- Curadoria federal/estadual pelo operador (depende do console do `admin_sistema`).

## Enforcement

- `normas.parser-test` (endereços, rótulos, agrupadores, alertas), `normas-http-test` (PG real: importar → conferir →
  publicar → nova versão guarda a anterior; 409 de conflito; outra Casa não vê; federal visível a todas; um Regimento
  maior que 256 KiB cabe), `arquitetura_test` (módulo na matriz), `catalogo_lint_test` (rotas com motivo).
- Banco: CHECK de camada/dono, índices únicos de vigente e em conferência, dispositivo append-only.

## Alternativas descartadas

- **Estender `legislativo.norma`**: misturaria a norma que a Casa produz com a que ela consulta, e as camadas federal e
  estadual não têm Casa.
- **PDF + busca por trechos (Eixo 7.2 A)**: já descartado — a citação precisa de endereço conferível.
- **IA quebrando o texto**: custo por Casa, resultado não determinístico e, ainda assim, conferência humana.

## Adendo (27/09/2026) — a versão vigente no índice da IA (B.4b)

1. Publicar uma versão grava `norma.versao-vigente` no outbox **na mesma transação** da conferência; descartar não
   emite nada. A fronteira (ADR-0008) promove a `NormaVigente` v1, chave `NormaVigente:v1:<versao-id>`, só com
   identidade (norma, versão, espécie).
2. O satélite lê os dispositivos por uma rota de serviço nova, `GET /integracao/ia/v1/entes/:ente/normas/versoes/:id/
   dispositivos`, que **só entrega a versão vigente e só da Casa do caminho** (404 para em conferência, substituída,
   descartada ou de outra Casa). O seam vem do host sobre o repositório de `normas`.
3. No satélite, o trabalho `indexar_norma` indexa um trecho por dispositivo como tipo `dispositivo`, **por norma** —
   publicar uma versão nova substitui os trechos da anterior. O trecho leva o título e o rótulo ("Lei Orgânica do
   Município, art. 11: …"), para "art. 11" casar pela palavra e o assunto pelo sentido; o endereço e o rótulo vão na
   meta, que é por onde o agente lê e cita (B.5). Versão que já não vale (404) conclui sem indexar.
4. A busca da tela (A.5) continua pedindo só proposição e transcrição; dispositivo é pedido explicitamente pelo agente.
   Normas federais e estaduais (sem Casa) ainda não entram no índice por Casa: entram junto com a curadoria do produto.
