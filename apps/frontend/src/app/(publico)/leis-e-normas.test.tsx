import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, within } from "@testing-library/react";
import { FichaDaNorma, LeisDaCasa } from "./leis-e-normas";
import type { FiltroDeLeis } from "@/lib/leis-vista";

// Leis e normas no portal do cidadão: a lista do acervo publicado (GET /legislacao, com filtro por tipo/ano/número e
// o total sem teto) e a ficha de cada norma (GET /legislacao/:id). O formato do corpo é o do wire/out do backend
// (NormaOut / NormasOut), em kebab-case.

const ENTE = "10000000-0000-0000-0000-000000000001";
const SEM_FILTRO: FiltroDeLeis = { tipo: "", ano: "", numero: "", ignorados: [] };
const N1 = "20000000-0000-0000-0000-00000000000a";
const N2 = "20000000-0000-0000-0000-00000000000b";

const norma1 = {
  "norma-id": N1,
  "proposicao-id": "30000000-0000-0000-0000-000000000001",
  "tipo-norma": "lei",
  numero: 12,
  ano: 2026,
  urn: "urn:lex:br;baturite:camara.municipal:lei:2026-03-10;12",
  ementa: "Institui o Programa Municipal de Hortas Comunitárias.",
  "publicado-em": "2026-03-10T15:00:00Z",
  "veiculo-publicacao": "Diário Oficial do Município",
};
const norma2 = {
  ...norma1,
  "norma-id": N2,
  "proposicao-id": "30000000-0000-0000-0000-000000000002",
  "tipo-norma": "lei_complementar",
  numero: 3,
  ementa: "Altera o Código de Posturas.",
};

function mockar(rotas: Record<string, { status?: number; corpo?: unknown } | "rede">) {
  const fn = vi.fn(async (url: string) => {
    const r = rotas[String(url)];
    if (r === "rede") throw new Error("rede fora");
    if (r === undefined) return { ok: false, status: 404, json: async () => ({}) } as Response;
    const status = r.status ?? 200;
    return { ok: status < 400, status, json: async () => r.corpo ?? {} } as Response;
  });
  global.fetch = fn as unknown as typeof fetch;
  return fn;
}

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe("LeisDaCasa", () => {
  it("lista as normas com título em palavras, ementa, data e link para a ficha — sem código nem UUID na tela", async () => {
    mockar({ [`/api/portal/casa/${ENTE}/legislacao`]: { corpo: { normas: [norma1, norma2], "normas-total": 2 } } });
    const { container } = render(<LeisDaCasa ente={ENTE} filtro={SEM_FILTRO} />);
    const lista = await screen.findByRole("list", { name: "Leis e normas publicadas" });
    const itens = within(lista).getAllByRole("listitem");
    expect(itens).toHaveLength(2);
    const [primeiro, segundo] = itens;
    const elo = within(primeiro).getByRole("link", { name: /Lei nº 12\/2026/ });
    expect(elo.getAttribute("href")).toBe(`/portal/casa/${ENTE}/leis/${N1}`);
    expect(primeiro.textContent).toContain("Institui o Programa Municipal de Hortas Comunitárias.");
    expect(primeiro.textContent).toMatch(/Publicada em 10\/03\/2026/);
    expect(within(segundo).getByRole("link", { name: /Lei complementar nº 3\/2026/ })).toBeTruthy();
    expect(screen.getByText("2 normas publicadas")).toBeTruthy();
    expect(container.textContent).not.toContain("lei_complementar");
    expect(container.textContent).not.toContain(N1);
  });

  it("uma só norma: o total fica no singular", async () => {
    mockar({ [`/api/portal/casa/${ENTE}/legislacao`]: { corpo: { normas: [norma1], "normas-total": 1 } } });
    render(<LeisDaCasa ente={ENTE} filtro={SEM_FILTRO} />);
    expect(await screen.findByText("1 norma publicada")).toBeTruthy();
  });

  it("acervo vazio: diz que a Câmara ainda não publicou leis aqui, sem lista nem alerta", async () => {
    mockar({ [`/api/portal/casa/${ENTE}/legislacao`]: { corpo: { normas: [], "normas-total": 0 } } });
    render(<LeisDaCasa ente={ENTE} filtro={SEM_FILTRO} />);
    expect(await screen.findByText("Esta Câmara ainda não publicou leis aqui.")).toBeTruthy();
    expect(screen.queryByRole("list", { name: "Leis e normas publicadas" })).toBeNull();
    expect(screen.queryByRole("alert")).toBeNull();
  });

  it("falha ao carregar: erro honesto, nunca o vazio fingido", async () => {
    mockar({ [`/api/portal/casa/${ENTE}/legislacao`]: { status: 500 } });
    render(<LeisDaCasa ente={ENTE} filtro={SEM_FILTRO} />);
    expect((await screen.findByRole("alert")).textContent).toMatch(/Não foi possível carregar as leis agora/);
    expect(screen.queryByText("Esta Câmara ainda não publicou leis aqui.")).toBeNull();
  });

  it("rede fora também vira erro", async () => {
    mockar({ [`/api/portal/casa/${ENTE}/legislacao`]: "rede" });
    render(<LeisDaCasa ente={ENTE} filtro={SEM_FILTRO} />);
    expect((await screen.findByRole("alert")).textContent).toMatch(/Não foi possível carregar/);
  });

  it("acervo maior que o teto do servidor: mostra quantas aparecem e o total, e manda filtrar — nunca trunca em silêncio", async () => {
    mockar({ [`/api/portal/casa/${ENTE}/legislacao`]: { corpo: { normas: [norma1, norma2], "normas-total": 250 } } });
    render(<LeisDaCasa ente={ENTE} filtro={SEM_FILTRO} />);
    const aviso = await screen.findByRole("status");
    expect(aviso.textContent).toMatch(/Mostrando 2 de 250 normas/);
    expect(aviso.textContent).toMatch(/filtros/);
    expect(screen.getByText("250 normas publicadas")).toBeTruthy();
  });

  it("com filtro: manda tipo/ano/número ao servidor, preenche o formulário e permite limpar", async () => {
    const fn = mockar({
      [`/api/portal/casa/${ENTE}/legislacao?tipo=lei&ano=2026&numero=12`]: { corpo: { normas: [norma1], "normas-total": 1 } },
    });
    render(<LeisDaCasa ente={ENTE} filtro={{ tipo: "lei", ano: "2026", numero: "12", ignorados: [] }} />);
    await screen.findByRole("list", { name: "Leis e normas publicadas" });
    expect(fn).toHaveBeenCalledWith(`/api/portal/casa/${ENTE}/legislacao?tipo=lei&ano=2026&numero=12`, { cache: "no-store" });
    expect((screen.getByLabelText("Tipo") as HTMLSelectElement).value).toBe("lei");
    expect((screen.getByLabelText("Ano") as HTMLInputElement).value).toBe("2026");
    expect((screen.getByLabelText("Número") as HTMLInputElement).value).toBe("12");
    expect(screen.getByRole("link", { name: "Limpar filtros" }).getAttribute("href")).toBe(`/portal/casa/${ENTE}/leis`);
  });

  it("o formulário filtra por GET na própria página, com os tipos em palavras", () => {
    mockar({});
    render(<LeisDaCasa ente={ENTE} filtro={SEM_FILTRO} />);
    const form = screen.getByRole("search", { name: "Filtrar leis e normas" });
    expect(form.getAttribute("method")).toBe("get");
    expect(form.getAttribute("action")).toBe(`/portal/casa/${ENTE}/leis`);
    const opcoes = within(screen.getByLabelText("Tipo")).getAllByRole("option").map((o) => o.textContent);
    expect(opcoes).toEqual(["Todos os tipos", "Lei", "Lei complementar", "Resolução", "Decreto legislativo", "Emenda à Lei Orgânica"]);
    expect(screen.queryByRole("link", { name: "Limpar filtros" })).toBeNull();
  });

  it("filtro sem resultado: diz que nada casou com os filtros e oferece limpar — não diz que a Câmara não publicou", async () => {
    mockar({ [`/api/portal/casa/${ENTE}/legislacao?ano=1999`]: { corpo: { normas: [], "normas-total": 0 } } });
    render(<LeisDaCasa ente={ENTE} filtro={{ tipo: "", ano: "1999", numero: "", ignorados: [] }} />);
    expect(await screen.findByText("Nenhuma norma encontrada com esses filtros.")).toBeTruthy();
    expect(screen.queryByText("Esta Câmara ainda não publicou leis aqui.")).toBeNull();
  });

  it("valor de filtro inválido na URL: avisa que foi ignorado", async () => {
    mockar({ [`/api/portal/casa/${ENTE}/legislacao`]: { corpo: { normas: [norma1], "normas-total": 1 } } });
    render(<LeisDaCasa ente={ENTE} filtro={{ tipo: "", ano: "", numero: "", ignorados: ["ano"] }} />);
    expect((await screen.findByRole("note")).textContent).toMatch(/O ano informado não é válido e foi ignorado/);
  });
});

describe("FichaDaNorma", () => {
  it("mostra título, ementa, data, veículo, identificador oficial, o texto para baixar e a matéria de origem", async () => {
    mockar({ [`/api/portal/casa/${ENTE}/legislacao/${N1}`]: { corpo: norma1 } });
    const { container } = render(<FichaDaNorma ente={ENTE} normaId={N1} />);
    expect((await screen.findByRole("heading", { level: 1 })).textContent).toBe("Lei nº 12/2026");
    expect(screen.getByText("Institui o Programa Municipal de Hortas Comunitárias.")).toBeTruthy();
    expect(screen.getByText("10/03/2026")).toBeTruthy();
    expect(screen.getByText("Diário Oficial do Município")).toBeTruthy();
    expect(screen.getByText("urn:lex:br;baturite:camara.municipal:lei:2026-03-10;12")).toBeTruthy();
    const baixar = screen.getByRole("link", { name: /Baixar o texto publicado/ });
    expect(baixar.getAttribute("href")).toBe(`/api/portal/casa/${ENTE}/legislacao/${N1}/artefato`);
    const origem = screen.getByRole("link", { name: /Ver a matéria que deu origem/ });
    expect(origem.getAttribute("href")).toBe(`/portal/casa/${ENTE}/materias/30000000-0000-0000-0000-000000000001`);
    expect(container.textContent).not.toContain(N1);
  });

  it("a trilha volta à lista de leis", async () => {
    mockar({ [`/api/portal/casa/${ENTE}/legislacao/${N1}`]: { corpo: norma1 } });
    render(<FichaDaNorma ente={ENTE} normaId={N1} />);
    await screen.findByRole("heading", { level: 1 });
    const trilha = screen.getByRole("navigation", { name: "Trilha" });
    expect(within(trilha).getByRole("link", { name: "Leis e normas" }).getAttribute("href")).toBe(`/portal/casa/${ENTE}/leis`);
  });

  it("tipo de norma em palavras na ficha também (nada de lei_complementar)", async () => {
    mockar({ [`/api/portal/casa/${ENTE}/legislacao/${N2}`]: { corpo: norma2 } });
    const { container } = render(<FichaDaNorma ente={ENTE} normaId={N2} />);
    expect((await screen.findByRole("heading", { level: 1 })).textContent).toBe("Lei complementar nº 3/2026");
    expect(container.textContent).not.toContain("lei_complementar");
  });

  it("norma que não existe nesta Câmara: 'não encontrada' com volta à lista, sem alerta de falha", async () => {
    mockar({});
    render(<FichaDaNorma ente={ENTE} normaId={N1} />);
    expect(await screen.findByText("Esta norma não foi encontrada nesta Câmara.")).toBeTruthy();
    expect(screen.getByRole("link", { name: "Ver todas as leis e normas" }).getAttribute("href")).toBe(`/portal/casa/${ENTE}/leis`);
    expect(screen.queryByRole("alert")).toBeNull();
  });

  it("falha do servidor: erro honesto, diferente de 'não encontrada'", async () => {
    mockar({ [`/api/portal/casa/${ENTE}/legislacao/${N1}`]: { status: 500 } });
    render(<FichaDaNorma ente={ENTE} normaId={N1} />);
    expect((await screen.findByRole("alert")).textContent).toMatch(/Não foi possível carregar esta norma agora/);
    expect(screen.queryByText("Esta norma não foi encontrada nesta Câmara.")).toBeNull();
  });
});
