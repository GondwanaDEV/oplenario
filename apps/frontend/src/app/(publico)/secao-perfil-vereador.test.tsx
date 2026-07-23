import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { SecaoPerfilVereador } from "./secao-perfil-vereador";

// Onda E fatia 2 (Task 6) — wrapper CLIENTE do perfil público do vereador: compõe usePerfilVereador
// (fetch, Task 5) + derivarPerfil (view-model, Task 5). Mesma razão de secao-ficha.tsx: o fetch real mora
// aqui, não em page.tsx (SSR não resolve fetch relativo).
//
// O que estes testes travam é a DEGRADAÇÃO, não o layout. Duas assimetrias que a nota de metodologia e o
// contrato impõem, e que só se verificam no DOM: (a) no caminho de ERRO nada de esqueleto — um perfil com
// campos vazios insinua "parlamentar real sem nenhuma atuação", que o próprio handler do backend chama de
// difamatório; (b) listas vazias com HTTP 200 são parlamentar REAL (suplente que ainda não tomou posse tem
// perfil) e a página renderiza inteira — vazio nunca vira 404.
//
// A revisão adversarial mostrou que essa promessa não estava cumprida: a fixture única fixava um só estado
// de presença (3 dos 4 ramos do JSX nunca renderizavam), os três `.num-card` não tinham asserção nenhuma
// (dava para trocar os números entre os rótulos e passar verde), as linhas de truncamento nunca eram
// desenhadas e o bloco de identidade preenchido — o caminho NORMAL em produção — não era verificado. Cada
// buraco desses vira um teste abaixo, com fixture PRÓPRIA por estado.
//
// Fixture em kebab-case: é o wire cru; quem cameliza é `buscarPublico`.

const presencaFracao = {
  "sessoes-presente": 8,
  "sessoes-com-chamada": 12,
  "janela-de-exercicio-conhecida": true,
  "janela-anterior-a-projecao": false,
};

const perfilWire = {
  "vereador-id": "v1",
  "nome-parlamentar": "Helena Past",
  "nome-civil": "Helena Pastore Matos",
  legislatura: { numero: 19, "ano-inicio": 2025, "ano-fim": 2028 },
  "cargo-mesa": "2ª Secretária da Mesa",
  comissoes: ["Comissão de Meio Ambiente", "Comissão de Finanças"],
  materias: [
    {
      "proposicao-id": "p1",
      tipo: "projeto_lei",
      ano: 2026,
      sequencial: 42,
      ementa: "Hortas comunitárias em terrenos públicos",
      estado: "em_comissoes",
    },
  ],
  // os três contadores DIFERENTES entre si de propósito: com valores iguais, um teste de pareamento
  // valor↔rótulo não detectaria troca nenhuma.
  "materias-total": 7,
  "normas-de-autoria": 3,
  votos: [
    {
      "votacao-id": "vt1",
      voto: "sim",
      "ocorrido-em": "2026-05-18T14:00:00Z",
      // valor REAL do wire: `(str tipo " " sequencial "/" ano)` com o tipo CRU e sem zero-padding.
      "materia-rotulo": "projeto_lei 22/2026",
      "materia-ementa": "Incentivo à energia solar no município",
    },
  ],
  "votos-total": 214,
  presenca: presencaFracao,
  "acervo-com-elo-de-autoria-desde": "2025-03-04",
  "presenca-projetada-desde": "2026-07-20",
};

const perfilVazioWire = {
  ...perfilWire,
  "cargo-mesa": null,
  comissoes: [],
  materias: [],
  "materias-total": 0,
  "normas-de-autoria": 0,
  votos: [],
  "votos-total": 0,
};

function mockFetch(resposta: { ok: boolean; json?: () => Promise<unknown> }) {
  global.fetch = vi.fn(async () => ({
    ok: resposta.ok,
    json: resposta.json ?? (async () => ({})),
  })) as unknown as typeof fetch;
}

/** Renderiza e espera a seção de presença existir (é o marcador de "perfil pronto na tela"). */
async function renderizarPerfil(wire: unknown) {
  mockFetch({ ok: true, json: async () => wire });
  const utils = render(<SecaoPerfilVereador ente="fortaleza" vereadorId="v1" />);
  await waitFor(() => expect(utils.container.querySelector(".perfil-presenca")).toBeTruthy());
  return utils;
}

describe("SecaoPerfilVereador", () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it("carregando -> nada VISÍVEL, e uma região viva que anuncia o carregamento", () => {
    global.fetch = vi.fn(() => new Promise(() => {})) as unknown as typeof fetch; // nunca resolve
    const { container } = render(<SecaoPerfilVereador ente="fortaleza" vereadorId="v1" />);
    expect(container.querySelector('[aria-busy="true"]')).toBeTruthy();
    // `aria-busy` num <div> vazio não anuncia nada: sem região viva e sem nome acessível o leitor de tela
    // encontra um <main> mudo. O texto é `.sr-only` — a tela continua sem nada visível.
    expect(screen.getByRole("status")).toBeTruthy();
    expect(screen.getByText(/carregando o perfil do vereador/i).className).toContain("sr-only");
    expect(container.querySelector(".perfil-cab")).toBeNull();
    expect(container.querySelector(".nums")).toBeNull();
  });

  it("erro -> o título NÃO afirma que o vereador não existe (a borda não sabe se foi 404 ou instabilidade)", async () => {
    mockFetch({ ok: false });
    render(<SecaoPerfilVereador ente="fortaleza" vereadorId="v1" />);
    await waitFor(() => expect(screen.getByText(/instabilidade passageira/i)).toBeTruthy());
    expect(screen.getByRole("status")).toBeTruthy();
    expect(screen.getByText("Não foi possível exibir este perfil")).toBeTruthy();
    // o título é o elemento de maior peso visual e é lido isolado: afirmar inexistência ali é afirmar
    // sobre uma pessoa que pode existir e estar em exercício.
    const titulo = document.querySelector(".em-breve-titulo") as HTMLElement;
    expect(titulo.textContent).not.toMatch(/não encontrado/i);
  });

  it("erro -> NÃO renderiza o esqueleto do perfil com campos vazios (perfil vazio insinuaria parlamentar real sem atuação)", async () => {
    mockFetch({ ok: false });
    const { container } = render(<SecaoPerfilVereador ente="fortaleza" vereadorId="v1" />);
    await waitFor(() => expect(screen.getByText(/instabilidade passageira/i)).toBeTruthy());
    expect(screen.queryByRole("heading", { level: 1 })).toBeNull();
    expect(container.querySelector(".perfil-presenca")).toBeNull();
    expect(container.querySelector(".perfil-cab")).toBeNull();
  });

  it("perfil com listas todas vazias -> a página renderiza inteira: 200 é parlamentar real, nunca vira 404", async () => {
    const { container } = await renderizarPerfil(perfilVazioWire);
    expect(screen.getByRole("heading", { level: 1 }).textContent).toBe("Helena Past");
    expect(screen.getByText("Nenhuma matéria de autoria consta desta lista.")).toBeTruthy();
    expect(screen.getByText("Ainda não há votos nominais registrados para este vereador.")).toBeTruthy();
    expect(screen.getByText("Não há comissões registradas para este vereador.")).toBeTruthy();
    // a declaração de recorte do acervo é o que impede "0 matérias" de virar "não é autor de nada".
    expect(screen.getByText(/Matérias de autoria estão publicadas a partir de 04\/03\/2025/)).toBeTruthy();
    expect(screen.queryByText(/não encontrado/i)).toBeNull();
    expect(container.querySelector(".perfil-tags")).toBeNull(); // sem cargo e sem comissão, sem lista vazia
  });

  it("o bloco de identidade preenchido chega ao DOM: nome civil, legislatura e os chips como LISTA rotulada", async () => {
    const { container } = await renderizarPerfil(perfilWire);
    expect(screen.getByRole("heading", { level: 1 }).textContent).toBe("Helena Past");
    expect(screen.getByText("Helena Pastore Matos")).toBeTruthy();
    expect(screen.getByText("19ª Legislatura (2025–2028)")).toBeTruthy();
    // WCAG 1.3.1: `<span>` soltos entregavam quatro itens de naturezas diferentes numa sequência plana.
    const lista = screen.getByRole("list", { name: "Cargo na Mesa e comissões" });
    expect([...lista.querySelectorAll("li")].map((li) => li.textContent)).toEqual([
      "2ª Secretária da Mesa",
      "Comissão de Meio Ambiente",
      "Comissão de Finanças",
    ]);
    expect(container.querySelector(".ptag.mesa")?.textContent).toBe("2ª Secretária da Mesa");
  });

  it("os três num-card pareiam o número CERTO com o rótulo CERTO", async () => {
    // sem esta asserção, trocar `normasDeAutoria` por `votosTotal` no JSX publicava "214 viraram lei" e
    // "3 votos nominais" — afirmação falsa sobre a produção legislativa de uma pessoa identificada.
    const { container } = await renderizarPerfil(perfilWire);
    const cards = [...container.querySelectorAll(".nums .num-card")].map((c) => [
      c.querySelector("b")?.textContent,
      c.querySelector("span")?.textContent,
    ]);
    expect(cards).toEqual([
      ["7", "matérias de autoria"],
      ["3", "viraram lei"],
      ["214", "votos nominais"],
    ]);
  });

  it("a declaração de recorte dos CONTADORES fica no mesmo bloco dos cards, não duas seções abaixo", async () => {
    const { container } = await renderizarPerfil(perfilVazioWire);
    const nums = container.querySelector(".nums") as HTMLElement;
    const nota = nums.nextElementSibling as HTMLElement;
    expect(nota.className).toContain("nota-secao");
    expect(nota.textContent).toContain(
      "Os números de matérias de autoria e de leis cobrem apenas o acervo publicado a partir de 04/03/2025",
    );
  });

  it("a seção de presença renderiza os DOIS números, nunca um percentual — nem em atributo", async () => {
    const { container } = await renderizarPerfil(perfilWire);
    const secao = container.querySelector(".perfil-presenca") as HTMLElement;
    expect(secao.querySelector(".presenca-fracao")?.textContent).toBe("8 de 12");
    expect(secao.textContent).toContain(
      "Compareceu a 8 das 12 sessões com registro de presença que a Câmara realizou enquanto este " +
        "vereador estava em exercício do mandato, descontados os períodos de licença registrados.",
    );
    // `textContent` NÃO inclui atributos: um percentual em aria-label/title/data-* passaria despercebido,
    // e o §9 da nota proíbe o número, não só o nó de texto. `outerHTML` varre os dois.
    expect(secao.outerHTML).not.toContain("%");
    expect(secao.outerHTML).not.toMatch(/por ?cento/i);
    expect(secao.textContent).toContain("O documento de fé é a ata de cada sessão.");
  });

  it("presença sem janela de exercício -> o bloco do §3 no DOM, e NENHUMA fração", async () => {
    const { container } = await renderizarPerfil({
      ...perfilWire,
      presenca: { ...presencaFracao, "janela-de-exercicio-conhecida": false },
    });
    const secao = container.querySelector(".perfil-presenca") as HTMLElement;
    expect(secao.textContent).toContain("Período de exercício não informado.");
    expect(secao.querySelector(".presenca-fracao")).toBeNull();
    expect(secao.outerHTML).not.toContain("%");
  });

  it("presença em exercício e sem sessão com chamada -> a frase única do §2, e NENHUMA fração", async () => {
    const { container } = await renderizarPerfil({
      ...perfilWire,
      presenca: { ...presencaFracao, "sessoes-presente": 0, "sessoes-com-chamada": 0 },
    });
    const secao = container.querySelector(".perfil-presenca") as HTMLElement;
    expect(secao.textContent).toContain("Ainda não houve sessão com registro de presença neste mandato.");
    expect(secao.querySelector(".presenca-fracao")).toBeNull();
    expect(secao.textContent).not.toContain("0 de 0");
  });

  it("presença de mandato inteiramente anterior à projeção (0/0) NUNCA publica '0 de 0' na tela", async () => {
    // o ex-vereador de mandato encerrado antes do registro eletrônico. Este é o estado que o JSX publicava
    // como fração — "0 de 0" mais "Compareceu a 0 das 0 sessões" sob o nome de uma pessoa.
    const { container } = await renderizarPerfil({
      ...perfilWire,
      presenca: {
        "sessoes-presente": 0,
        "sessoes-com-chamada": 0,
        "janela-de-exercicio-conhecida": true,
        "janela-anterior-a-projecao": true,
      },
    });
    const secao = container.querySelector(".perfil-presenca") as HTMLElement;
    expect(secao.querySelector(".presenca-fracao")).toBeNull();
    expect(secao.textContent).not.toContain("0 de 0");
    expect(secao.textContent).not.toContain("das 0 sessões");
    expect(secao.textContent).toContain(
      "O período de exercício deste mandato é anterior ao início do registro eletrônico de presença.",
    );
  });

  it("presença com exercício anterior à projeção e sessões contadas -> fração MAIS a ressalva do §6", async () => {
    const { container } = await renderizarPerfil({
      ...perfilWire,
      presenca: { ...presencaFracao, "janela-anterior-a-projecao": true },
    });
    const secao = container.querySelector(".perfil-presenca") as HTMLElement;
    expect(secao.querySelector(".presenca-fracao")?.textContent).toBe("8 de 12");
    expect(secao.textContent).toContain(
      "Há período de exercício deste mandato anterior aos dados publicados",
    );
  });

  it("trilha aponta para a página inicial do portal, não para a lista de matérias", async () => {
    const { container } = await renderizarPerfil(perfilWire);
    const migalha = container.querySelector(".migalha") as HTMLElement;
    const link = migalha.querySelector("a") as HTMLAnchorElement;
    expect(link.getAttribute("href")).toBe("/portal/casa/fortaleza");
    expect(link.textContent).toBe("Início");
    expect(migalha.textContent).toContain("Helena Past");
    expect(migalha.textContent).not.toMatch(/matérias/i);
  });

  it("o segmento do ente é CODIFICADO em todos os destinos, como a vista já faz no link da matéria", async () => {
    mockFetch({ ok: true, json: async () => perfilWire });
    const { container } = render(<SecaoPerfilVereador ente="casa/estranha" vereadorId="v1" />);
    await waitFor(() => expect(container.querySelector(".migalha")).toBeTruthy());
    const hrefs = [...container.querySelectorAll("a")].map((a) => a.getAttribute("href"));
    expect(hrefs).not.toContain("/portal/casa/casa/estranha");
    expect(hrefs).toContain("/portal/casa/casa%2Festranha");
    expect(hrefs).toContain("/portal/casa/casa%2Festranha#esic-titulo");
    expect(hrefs).toContain("/portal/casa/casa%2Festranha/materias/p1");
  });

  it("cada matéria linka para a ficha pública e leva aria-label da faixa de tramitação", async () => {
    await renderizarPerfil(perfilWire);
    expect(screen.getByText("Hortas comunitárias em terrenos públicos")).toBeTruthy();
    const link = screen.getByRole("link", { name: /PL 042\/2026/ });
    expect(link.getAttribute("href")).toBe("/portal/casa/fortaleza/materias/p1");
    expect(
      screen.getByRole("img", {
        name: "Tramitação de PL 042/2026: concluídos Protocolo; atual Comissões; pendente 1º turno, 2º turno, Sanção.",
      }),
    ).toBeTruthy();
  });

  it("lista truncada declara o corte na tela — e descreve a ORDEM REAL de cada lista", async () => {
    // sem isto, 1 de 7 matérias e 1 de 214 votos apareciam como se fossem tudo. `materias` ordena por
    // numeração (não por data); `votos`, por `ocorrido_em` — as duas frases dizem coisas diferentes de
    // propósito.
    const { container } = await renderizarPerfil(perfilWire);
    expect(container.textContent).toContain(
      "Mostrando 1 de 7 matérias, da numeração mais alta para a mais baixa.",
    );
    expect(container.textContent).toContain("Mostrando os 1 votos mais recentes, de 214 no total.");
  });

  it("voto nominal sai com rótulo textual E forma além da cor (cor nunca é o único sinal)", async () => {
    const { container } = await renderizarPerfil(perfilWire);
    const voto = container.querySelector(".votos .voto") as HTMLElement;
    expect(voto.textContent).toContain("Incentivo à energia solar no município");
    const chip = voto.querySelector(".chip.chip-ok") as HTMLElement;
    expect(chip.textContent).toBe("A favor");
    expect(chip.querySelector("svg")).toBeTruthy();
    // a MESMA proposição não pode ter duas grafias na mesma página: a lista de autoria usa `derivarRef`.
    expect(voto.textContent).toContain("PL 022/2026");
    expect(voto.textContent).not.toContain("projeto_lei");
  });

  it("voto de matéria não projetada não imprime o rótulo de fallback duas vezes", async () => {
    const { container } = await renderizarPerfil({
      ...perfilWire,
      votos: [
        {
          "votacao-id": "vt9",
          voto: "nao",
          "ocorrido-em": "2026-05-18T14:00:00Z",
          "materia-rotulo": null,
          "materia-ementa": null,
        },
      ],
      "votos-total": 1,
    });
    const voto = container.querySelector(".votos .voto") as HTMLElement;
    const ocorrencias = voto.textContent?.split("voto em matéria não publicada").length ?? 0;
    expect(ocorrencias - 1).toBe(1);
    expect(voto.querySelector(".tit span")?.textContent).toBe("18/05/2026");
  });
});
