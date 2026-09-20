import { test, expect } from "@playwright/test";
import { lerEnteId } from "./seed";

// Task 3: substitui o seed-smoke.spec.ts (Task 2, "só prova que o portal carrega") pelo spec COMPLETO
// do Portal do Cidadão — os 6 asserts do contrato descritos no plano (FE→BFF→backend→DB→render).
//
// Seletores calibrados contra o DOM REAL (não o ponto de partida do brief — ver relatório da task para
// o detalhe de cada divergência): lidos em
// apps/frontend/src/app/(publico)/{barra-institucional,rodape-institucional,secao-em-tramitacao,
// destaque-tramitacao,balcao-esic,balcao-lgpd,navegacao-civica,capa}.tsx + lib/{materia-vista,
// tramitacao-vista,use-materias,use-encarregado,portal-api}.ts, e confirmados batendo o ente semeado
// real via `curl http://localhost:8888/portal/casa/<ente>/materias`.
test.describe("Portal do Cidadão — e2e", () => {
  const enteId = lerEnteId();

  test("renderiza nome real, matéria semeada e balcões, sem erro de console", async ({ page }) => {
    const erros: string[] = [];
    page.on("console", (m) => {
      if (m.type() === "error") erros.push(m.text());
    });

    // assert #4: a chamada de rede das matérias devolve 200 — prova o proxy BFF (rewrite same-origin
    // de next.config.ts) -> backend, não só render estático. `use-materias.ts` faz fetch client-side
    // para `/api/portal/casa/{ente}/materias` (SSR não resolve fetch relativo — decisão documentada no
    // próprio hook), então o waitForResponse tem que ficar armado ANTES do goto.
    const materias = page.waitForResponse(
      (r) => r.url().includes(`/api/portal/casa/${enteId}/materias`) && r.status() === 200,
    );
    await page.goto(`/portal/casa/${enteId}`);
    await materias;

    // assert #1: nome real da Casa no header E no rodapé — prova que o white-label lê o `ente` do
    // banco (buscarNomeCasa em page.tsx), não um placeholder hardcoded. `<header className="topo">` e
    // `<footer className="rodape">` não estão aninhados em nenhum landmark que os demoveria, então
    // mantêm os papéis implícitos banner/contentinfo.
    // M10 (revisão final): o nome semeado real é fixo (`seed_demo.clj` -> "Câmara Municipal de
    // Fortaleza") — checar o literal é estritamente mais forte que a regex genérica `/Câmara|Camara/`
    // (que passaria mesmo se o white-label mostrasse o nome ERRADO de outra câmara).
    await expect(page.getByRole("banner")).toContainText("Câmara Municipal de Fortaleza");
    await expect(page.getByRole("contentinfo")).toContainText("Câmara Municipal de Fortaleza");

    // assert #2: a matéria semeada renderizada por inteiro — identificador (ref), estado da
    // tramitação, URN/LexML e a faixa de azulejo visível. `DestaqueTramitacao` NÃO é o único
    // `<article>` da página — os balcões e-SIC e LGPD também são `<article>` — então o
    // `.filter({ hasText: ... })` é o que desambigua o destaque dos demais, não defesa extra.
    //
    // DIVERGÊNCIA do ponto de partida: `derivarRef` faz `padStart(3, "0")` no sequencial (ex.
    // "PL 003/2026", não "PL 3/2026") — a regex `\d+` do brief já cobre isso, sem mudança necessária.
    // A URN não é um elemento separado: é o próprio `<p className="permalink">{destaque.permalink}</p>`
    // (= `m.urnLex`, ex. "urn:lex:br;ce;fortaleza:camara.municipal;projeto.lei:2026;3") dentro do
    // article — `toContainText` sobre o article já alcança.
    const materia = page.getByRole("article").filter({ hasText: /[A-Z]+ \d+\/\d{4}/ });
    await expect(materia).toBeVisible();
    await expect(materia).toContainText(/urn:lex:br/);
    // M11 (revisão final): o assert #2 não checava título/ementa da matéria, que a §4.2 da spec pede
    // explicitamente. `seed-demo/materias` sempre protocola as MESMAS 6 ementas fixas (`materias-seed`
    // em seed_demo.clj); o destaque é `itens[0]` da listagem ordenada por `[:ano :desc][:sequencial :desc]`
    // e o `sequencial` é gapless POR TIPO (`kernel/sequencial`, escopo "tipo:ano") — como 4 das 6 matérias
    // têm tipos diferentes (indicacao/requerimento/projeto_lei_complementar têm sequencial=1 cada, sem
    // relação com a ordem de protocolo global), qual delas empata e vence o desempate não é uma garantia
    // documentada do SQL. Em vez de fixar qual das 6 é a destacada (frágil), a alternância abaixo cobre
    // as 6 ementas conhecidas — qualquer que seja o destaque, o assert prova que ementa REAL do seed
    // apareceu, não um vazio/placeholder.
    await expect(materia).toContainText(
      /Hortas Comunitárias|Arborização viária|Código de Posturas|Denominação de via pública|iluminação pública na Praça da Gentilândia|Programa de Compostagem/,
    );
    // estado da tramitação: o rótulo vem de `ROTULO_SITUACAO_POR_ESTADO`/os dois terminais
    // (Aprovado/Arquivada) de tramitacao-vista.ts — não fixamos qual estado específico o seed vai
    // sortear como destaque (é o `itens[0]`, "mais recente primeiro" do backend), só que ALGUM rótulo
    // conhecido apareça — honesto com o contrato de tramitação, não com um estado específico do seed.
    await expect(materia).toContainText(
      /Protocolado|Em comiss(ões|oes)|Em pauta|Em 1º turno|Em 2º turno|Em sanção|Aprovado|Arquivada/,
    );
    // faixa de tramitação: `AzulejoFaixa` é um `<svg role="img" aria-label={rotuloAria}>` e
    // `descreverFaixa` sempre começa o rótulo com "Tramitação de {ref}: ..." — o brief já acertou a
    // mecânica aqui, sem divergência.
    await expect(materia.getByRole("img", { name: /Tramitação/ })).toBeVisible();

    // assert #3: os dois balcões de direito (e-SIC + LGPD) presentes, com o contato do
    // Encarregado/DPO CARREGADO — nada de mensagem de erro de carregamento (o 404 do encarregado que
    // existia antes do seed não pode reaparecer, ver Task 2). Os headings reais são
    // `<h3 id="esic-titulo">Acesso à informação</h3>` e `<h3 id="lgpd-titulo">Os seus dados
    // pessoais</h3>` — batem literalmente com o ponto de partida do brief, sem divergência.
    await expect(page.getByRole("heading", { name: /Acesso à informação/ })).toBeVisible();
    await expect(page.getByRole("heading", { name: /dados pessoais/i })).toBeVisible();
    await expect(page.getByText(/Não foi possível carregar o contato do Encarregado/)).toHaveCount(0);
    // positivo: o contato real do Encarregado (email) apareceu — não é só ausência do erro, é presença
    // do dado real que o seed_demo.clj/seed-demo/encarregado escreveu.
    // M8 (revisão final): trocado o único seletor por classe CSS do spec por um role-based. Confirmado
    // contra o DOM real (`apps/frontend/src/app/(publico)/balcao-lgpd.tsx`): o card do Encarregado é
    // `<div className="encarregado">` DENTRO do `<article className="balcao balcao-lgpd"
    // aria-labelledby="lgpd-titulo">`, com `<a href={`mailto:${encarregado.email}`}>{encarregado.email}</a>`
    // — âncora `mailto:` real, não texto solto. "dados pessoais" (do heading "Os seus dados pessoais" +
    // do parágrafo-lead) só aparece no balcão LGPD entre os 3 `<article>` da página, então
    // `.filter({ hasText })` desambigua sem precisar de `.locator(".encarregado")`.
    const balcaoLgpd = page.getByRole("article").filter({ hasText: /dados pessoais/ });
    await expect(balcaoLgpd.getByRole("link", { name: /@/ })).toBeVisible();

    // assert #6: os placeholders honestos existem ("Em breve") — é contrato de produto (EmBreve, Global
    // Constraints "sem dado falso"), não bug. `EmBreve` renderiza `<p class="em-breve-rotulo">Em
    // breve</p>` — aparece várias vezes na página (Capa, NavegacaoCivica, os dois balcões); `.first()`
    // já basta para provar que o padrão existe.
    await expect(page.getByText("Em breve").first()).toBeVisible();

    // assert #5: zero erro de console inesperado. Sem filtro nomeado nesta rodada — a verificação real
    // (ver relatório) não observou ruído estrutural do next dev/HMR neste spec; se aparecer no futuro,
    // documentar o padrão filtrado aqui, nunca silenciar erro do próprio código do produto.
    expect(erros, `erros de console: ${erros.join(" | ")}`).toEqual([]);
  });
});

// Regressão do achado do teste exploratório contra a homologação (método docs/20 §1, regra de ouro:
// "um achado de M4 só está fechado quando vira M3/M2"). O defeito: a capa resolvia o nome da Casa com
// `buscarNomeCasa`, que devolvia `null` tanto para "esta Casa não existe" quanto para "falha ao
// resolver", e degradava pro slug da URL nos DOIS casos — renderizando o Portal do Cidadão INTEIRO
// (hero, balcões e-SIC/LGPD, rodapé com `© <slug>`) para um ente inexistente ou malformado. Na prática,
// um link errado exibia um portal de transparência crível para uma Casa que não existe.
//
// Este spec NÃO depende da semente (é o caso negativo), então roda no CI mesmo em banco vazio.
test.describe("Portal do Cidadão — Casa inexistente não vira portal", () => {
  // uuid bem-formado, porém sem Casa (o backend responde 404 em /portal/casa/:ente)
  const enteInexistente = "00000000-0000-0000-0000-0000000000ff";
  // id malformado (o backend responde 400) — o outro caso reproduzido ao vivo
  const enteMalformado = "xyz-invalido";

  for (const [rotulo, ente] of [
    ["uuid inexistente (404)", enteInexistente],
    ["id malformado (400)", enteMalformado],
  ] as const) {
    test(`${rotulo}: mostra "Câmara não encontrada" e NÃO renderiza o portal`, async ({ page }) => {
      await page.goto(`/portal/casa/${ente}`);

      await expect(page.getByText("Câmara não encontrada")).toBeVisible();

      // O coração do achado: nada do portal cívico pode aparecer para uma Casa que não existe.
      await expect(page.getByRole("heading", { name: /Os seus direitos, em dois balcões/i })).toHaveCount(0);
      await expect(page.getByText(/Acompanhe a sua/i)).toHaveCount(0);

      // E, sobretudo, o id cru NUNCA pode ser exibido como se fosse o nome da instituição.
      await expect(page.locator(".marca-nome")).toHaveCount(0);
      await expect(page.getByText(ente, { exact: true })).toHaveCount(0);
    });
  }
});
