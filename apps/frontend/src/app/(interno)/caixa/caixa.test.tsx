import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";

// A rota /caixa (interno): a mesma caixa do vereador, com o topo na área "Caixa" e o comunicado abrindo em
// /comunicados/:id. O comportamento da caixa está em src/app/caixa-da-casa.test.tsx.

vi.mock("@/lib/auth", () => ({ useAuth: () => ({ token: "tok" }) }));
vi.mock("../topo", () => ({ TopoInterno: ({ area }: { area: string }) => <div data-testid="topo">{area}</div> }));

import PaginaCaixa from "./page";

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe("/caixa", () => {
  it("topo na área Caixa e o comunicado abre em /comunicados/:id", async () => {
    global.fetch = vi.fn(async (url: string) => {
      if (url === "/api/comunicados/caixa") {
        return {
          ok: true, status: 200,
          json: async () => ({
            itens: [{ id: "c1", protocolo: "COM-2026-000001", assunto: "Reunião do setor", "remetente-nome": "Rita", "enviado-em": new Date().toISOString(),
              "exige-ciencia": false, "ciencia-ate": null, vencido: false, "recebido-em": null, "lido-em": null, "ciente-em": null }],
            "nao-lidos": 1, "pendentes-ciencia": 0, "proxima-ciencia-ate": null,
          }),
        } as Response;
      }
      return { ok: false, status: 403, json: async () => ({}) } as Response;
    }) as unknown as typeof fetch;
    render(<PaginaCaixa />);
    expect(screen.getByTestId("topo").textContent).toBe("Caixa");
    expect((await screen.findByRole("link", { name: "Reunião do setor" })).getAttribute("href")).toBe("/comunicados/c1?token=tok");
    expect(screen.getByRole("heading", { level: 1 }).textContent).toContain("Caixa");
  });
});
