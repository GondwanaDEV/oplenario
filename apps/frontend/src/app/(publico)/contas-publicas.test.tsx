import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, within } from "@testing-library/react";
import { ContasPublicas } from "./contas-publicas";

// ADR-0021 Parte B — as contas no portal: por exercício, o parecer prévio, a situação ou o resultado em palavras e os
// documentos do Tribunal para baixar. Público: sem token.

function mockar(status: number, corpo: unknown) {
  const urls: string[] = [];
  global.fetch = vi.fn(async (url: string) => {
    urls.push(String(url));
    return { ok: status < 300, status, json: async () => corpo } as Response;
  }) as unknown as typeof fetch;
  return urls;
}

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

const fio = {
  prestacoes: [
    { id: "m1", tipo: "gestao_camara", exercicio: 2024, responsavel: "Presidente X", estado: "acompanhamento", "situacao-tce": "em instrução", documentos: [] },
    {
      id: "pc1", tipo: "governo_prefeito", exercicio: 2023, responsavel: "José Sarto", "parecer-previo": "favoravel_com_ressalvas", estado: "julgada",
      resultado: "parecer_mantido", "julgada-em": "2026-06-10T15:00:00Z", "proposicao-rotulo": "PDL 2/2026",
      "frase-resultado": "O parecer prevalece: 12 votos pela rejeição, eram precisos 14.",
      documentos: [{ id: "d1", tipo: "parecer_previo", nome: "parecer-2023.pdf" }, { id: "d2", tipo: "relatorio_tce", nome: "relatorio-2023.pdf" }],
    },
    { id: "pc2", tipo: "governo_prefeito", exercicio: 2024, responsavel: "José Sarto", "parecer-previo": "desfavoravel", estado: "prazo_de_defesa", documentos: [] },
  ],
};

describe("ContasPublicas", () => {
  it("governo e Mesa em seções; exercício mais recente primeiro; parecer, situação e resultado em palavras", async () => {
    const urls = mockar(200, fio);
    render(<ContasPublicas ente="ce" />);
    const governo = await screen.findByRole("list", { name: "Contas de governo do Prefeito" });
    const itens = within(governo).getAllByRole("article");
    expect(itens.map((a) => a.getAttribute("aria-label"))).toEqual(["Exercício 2024", "Exercício 2023"]);
    expect(within(itens[0]).getByText("Desfavorável")).toBeTruthy();
    expect(within(itens[0]).getByText("Em análise na Câmara — prazo de defesa do responsável")).toBeTruthy();
    expect(within(itens[0]).getByText("Os documentos do Tribunal ainda não foram publicados.")).toBeTruthy();
    expect(within(itens[1]).getByText("Favorável com ressalvas")).toBeTruthy();
    expect(within(itens[1]).getByText("Julgadas: o parecer do Tribunal prevaleceu")).toBeTruthy();
    expect(within(itens[1]).getByText("O parecer prevalece: 12 votos pela rejeição, eram precisos 14.")).toBeTruthy();
    expect(within(itens[1]).getByText("10/06/2026")).toBeTruthy();
    expect(within(itens[1]).getByText("PDL 2/2026")).toBeTruthy();
    const mesa = screen.getByRole("list", { name: "Contas de gestão da Câmara" });
    expect(within(mesa).getByText("No Tribunal de Contas: em instrução")).toBeTruthy();
    expect(urls[0]).toBe("/api/portal/casa/ce/contas");
  });

  it("os documentos do Tribunal são link de download direto, pelo tipo em palavras", async () => {
    mockar(200, fio);
    render(<ContasPublicas ente="ce" />);
    const docs = await screen.findByRole("list", { name: "Documentos do Tribunal — exercício 2023" });
    const link = within(docs).getByRole("link", { name: "Baixar: Relatório do TCE" });
    expect(link.getAttribute("href")).toBe("/api/portal/casa/ce/contas/pc1/documentos/d2");
    expect(link.getAttribute("download")).toBe("relatorio-2023.pdf");
    expect(within(docs).getByRole("link", { name: "Baixar: Parecer prévio do TCE" })).toBeTruthy();
  });

  it("sem prestações: frase honesta em cada seção; falha: alerta", async () => {
    mockar(200, { prestacoes: [] });
    render(<ContasPublicas ente="ce" />);
    expect(await screen.findByText("Nenhuma prestação de contas do Prefeito registrada até agora.")).toBeTruthy();
    expect(screen.getByText("Nenhuma prestação de contas da Câmara registrada até agora.")).toBeTruthy();
    cleanup();
    mockar(500, {});
    render(<ContasPublicas ente="ce" />);
    expect((await screen.findByRole("alert")).textContent).toMatch(/Não foi possível carregar as contas/);
  });
});
