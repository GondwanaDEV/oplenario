import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import LayoutDaCasa from "./layout";

// ADR-0018 fatia 2: toda página do portal de uma Câmara encerrada (o backend responde 410) vira o aviso — uma só
// decisão, no layout; as outras respostas seguem para a página.

function backend(status: number, corpo: unknown) {
  global.fetch = vi.fn(async () => ({ ok: status < 300, status, json: async () => corpo })) as unknown as typeof fetch;
}

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe("o portal de uma Câmara", () => {
  it("encerrada (410): a página inteira vira 'não usa mais O Plenário', com o destino do acervo", async () => {
    backend(410, { nome: "Câmara Municipal de Baturité", "encerrada-em": "2027-01-05T15:00:00Z",
      "destino-acervo-url": "https://camarabaturite.ce.gov.br/acervo" });
    render(await LayoutDaCasa({ children: <p>conteúdo da página</p>, params: Promise.resolve({ ente: "e1" }) }));
    expect(screen.getByText("Câmara Municipal de Baturité não usa mais O Plenário")).toBeTruthy();
    expect(screen.getByRole("link", { name: "https://camarabaturite.ce.gov.br/acervo" })).toBeTruthy();
    expect(screen.queryByText("conteúdo da página")).toBeNull();
  });

  it("no ar (ou inexistente, ou fora do ar por um instante): a página decide", async () => {
    for (const [status, corpo] of [[200, { "nome-oficial": "Câmara" }], [404, {}], [503, {}]] as const) {
      backend(status, corpo);
      render(await LayoutDaCasa({ children: <p>conteúdo da página</p>, params: Promise.resolve({ ente: "e1" }) }));
      expect(screen.getByText("conteúdo da página")).toBeTruthy();
      cleanup();
    }
  });
});
