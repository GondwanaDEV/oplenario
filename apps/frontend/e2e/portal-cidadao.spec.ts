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
    await expect(page.getByRole("banner")).toContainText(/Câmara|Camara/);
    await expect(page.getByRole("contentinfo")).toContainText(/Câmara|Camara/);

    // assert #2: a matéria semeada renderizada por inteiro — identificador (ref), estado da
    // tramitação, URN/LexML e a faixa de azulejo visível. `DestaqueTramitacao` é o único
    // `<article>` da página (a lista "mais em tramitação" usa `<ul>/<li>`), então o filtro por texto é
    // defesa extra, não estritamente necessário — mantido por clareza de intenção.
    //
    // DIVERGÊNCIA do ponto de partida: `derivarRef` faz `padStart(3, "0")` no sequencial (ex.
    // "PL 003/2026", não "PL 3/2026") — a regex `\d+` do brief já cobre isso, sem mudança necessária.
    // A URN não é um elemento separado: é o próprio `<p className="permalink">{destaque.permalink}</p>`
    // (= `m.urnLex`, ex. "urn:lex:br;ce;fortaleza:camara.municipal;projeto.lei:2026;3") dentro do
    // article — `toContainText` sobre o article já alcança.
    const materia = page.getByRole("article").filter({ hasText: /[A-Z]+ \d+\/\d{4}/ });
    await expect(materia).toBeVisible();
    await expect(materia).toContainText(/urn:lex:br/);
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
    await expect(page.locator(".encarregado")).toContainText("@");

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
