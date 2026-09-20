import { describe, expect, it, vi, afterEach } from "vitest";
import { render, screen, waitFor, fireEvent, cleanup } from "@testing-library/react";
import { FilaModeracao } from "./fila-moderacao";

function itemFake(id: string, over: Record<string, unknown> = {}) {
  return {
    id,
    "proposicao-id": "prop-1",
    "autor-identidade-id": "ident-1",
    corpo: "comentário " + id,
    denunciado: false,
    "criado-em": "2026-01-02T10:00:00Z",
    ...over,
  };
}

describe("FilaModeracao", () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it("aprovar dispara POST {acao:'aprovado'} e recarrega a fila", async () => {
    const fetchMock = vi.fn(async (_url: string, opts?: { method?: string }) => {
      if (opts?.method === "POST") return { ok: true, json: async () => ({ id: "c1", estado: "aprovado" }) } as Response;
      return { ok: true, json: async () => [itemFake("c1")] } as Response;
    });
    global.fetch = fetchMock as unknown as typeof fetch;

    render(<FilaModeracao token="tok" />);
    const aprovar = await screen.findByRole("button", { name: "Aprovar" });
    fireEvent.click(aprovar);

    await waitFor(() =>
      expect(fetchMock).toHaveBeenCalledWith(
        "/api/comentarios/c1/moderar",
        expect.objectContaining({ method: "POST", body: JSON.stringify({ acao: "aprovado" }) }),
      ),
    );
  });

  it("rejeitar abre o motivo e confirma com POST {acao:'rejeitado', motivo-rejeicao}", async () => {
    const fetchMock = vi.fn(async (_url: string, opts?: { method?: string }) => {
      if (opts?.method === "POST") return { ok: true, json: async () => ({ id: "c1", estado: "rejeitado" }) } as Response;
      return { ok: true, json: async () => [itemFake("c1", { denunciado: true })] } as Response;
    });
    global.fetch = fetchMock as unknown as typeof fetch;

    render(<FilaModeracao token="tok" />);
    fireEvent.click(await screen.findByRole("button", { name: "Rejeitar" }));
    fireEvent.change(await screen.findByLabelText(/Motivo da rejeição/i), { target: { value: "fora do tema" } });
    fireEvent.click(screen.getByRole("button", { name: "Confirmar rejeição" }));

    await waitFor(() =>
      expect(fetchMock).toHaveBeenCalledWith(
        "/api/comentarios/c1/moderar",
        expect.objectContaining({
          method: "POST",
          body: JSON.stringify({ acao: "rejeitado", "motivo-rejeicao": "fora do tema" }),
        }),
      ),
    );
  });

  it("fila vazia → mensagem honesta, sem itens", async () => {
    global.fetch = vi.fn(async () => ({ ok: true, json: async () => [] }) as Response) as unknown as typeof fetch;
    render(<FilaModeracao token="tok" />);
    expect(await screen.findByText("Nenhum comentário na fila.")).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Aprovar" })).toBeNull();
  });
});
