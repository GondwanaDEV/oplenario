import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { PainelBusca } from "./painel-busca";

const respostaIA = {
  modo: "ia",
  resultados: [
    {
      tipo: "proposicao",
      score: 0.9,
      trecho: "indexado",
      proposicao: { id: "p1", tipo: "projeto_lei", ano: 2026, sequencial: 7, ementa: "Dispõe sobre a merenda escolar.", "autor-texto": "Ver. Ana" },
    },
    {
      tipo: "transcricao",
      score: 0.5,
      trecho: "A merenda da escola do bairro chegou atrasada.",
      sessao: { id: "s1", tipo: "ordinaria", numero: 12, data: "2026-09-20T18:00:00Z" },
      "transcricao-id": "t1",
      inicio: 125.5,
      orador: "Ver. Bia",
    },
  ],
};

function mockar(corpo: unknown, status = 200) {
  const fetchMock = vi.fn(async () => ({ ok: status === 200, status, json: async () => corpo }) as Response);
  global.fetch = fetchMock as unknown as typeof fetch;
  return fetchMock;
}

function buscarPor(texto: string) {
  fireEvent.change(screen.getByLabelText("O que você procura?"), { target: { value: texto } });
  fireEvent.click(screen.getByRole("button", { name: "Buscar" }));
}

describe("PainelBusca", () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it("busca no envio e mostra proposição e fala, com a palavra destacada e os links", async () => {
    const fetchMock = mockar(respostaIA);
    render(<PainelBusca token="tok" />);
    buscarPor("  merenda escolar ");

    expect(await screen.findByText("2 resultados")).toBeTruthy();
    expect(fetchMock).toHaveBeenCalledWith("/api/busca?q=merenda+escolar&tipos=proposicao%2Ctranscricao", expect.anything());
    expect(screen.getByText("Autoria: Ver. Ana")).toBeTruthy();
    expect(screen.getByText("Ver. Bia · aos 2:05 da gravação")).toBeTruthy();
    expect(screen.getAllByText("merenda").every((el) => el.tagName === "MARK")).toBe(true);
    expect(screen.getByRole("link", { name: /7\/2026/ }).getAttribute("href")).toContain("/ficha-materia/p1");
    expect(screen.getByRole("link", { name: /Sessão .+ nº 12/ }).getAttribute("href")).toContain("/sessoes/s1/transcricao");
  });

  it("o filtro refaz a busca só com o tipo escolhido", async () => {
    const fetchMock = mockar(respostaIA);
    render(<PainelBusca token="tok" />);
    buscarPor("merenda");
    await screen.findByText("2 resultados");
    fireEvent.click(screen.getByRole("button", { name: "Falas em plenário" }));
    await waitFor(() => expect(fetchMock).toHaveBeenLastCalledWith("/api/busca?q=merenda&tipos=transcricao", expect.anything()));
  });

  it("IA fora: mostra o aviso e o que a busca pela ementa achou", async () => {
    mockar({
      modo: "sem-ia",
      aviso: "A busca por sentido está indisponível agora.",
      resultados: [{ tipo: "proposicao", proposicao: { id: "p1", tipo: "projeto_lei", ano: 2026, sequencial: 7, ementa: "Merenda." } }],
    });
    render(<PainelBusca token="tok" />);
    buscarPor("merenda");
    expect(await screen.findByText("A busca por sentido está indisponível agora.")).toBeTruthy();
    expect(screen.getByText("1 resultado")).toBeTruthy();
  });

  it("nada achado e falha dizem isso, sem quebrar", async () => {
    mockar({ modo: "ia", resultados: [] });
    render(<PainelBusca token="tok" />);
    buscarPor("zzz");
    expect(await screen.findByText("Nada encontrado.")).toBeTruthy();
    cleanup();
    mockar({}, 500);
    render(<PainelBusca token="tok" />);
    buscarPor("zzz");
    expect((await screen.findByRole("alert")).textContent).toBe("Não foi possível buscar agora. Tente de novo.");
  });

  it("não busca com menos de 2 caracteres", () => {
    const fetchMock = mockar(respostaIA);
    render(<PainelBusca token="tok" />);
    fireEvent.change(screen.getByLabelText("O que você procura?"), { target: { value: "a" } });
    expect((screen.getByRole("button", { name: "Buscar" }) as HTMLButtonElement).disabled).toBe(true);
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
