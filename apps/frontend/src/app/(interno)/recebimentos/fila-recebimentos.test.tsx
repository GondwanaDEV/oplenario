import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { FilaRecebimentos } from "./fila-recebimentos";

const item = (id: string, over: Record<string, unknown> = {}) => ({
  "proposicao-id": `p-${id}`,
  tipo: "projeto_lei",
  sequencial: 7,
  ano: 2026,
  ementa: `Dispõe sobre ${id}`,
  estado: "em_comissoes",
  "estado-nome": "Em Comissões",
  "movimentacao-id": `m-${id}`,
  "de-estado": "protocolada",
  desde: "2026-09-24T12:00:00Z",
  restrito: false,
  ...over,
});

describe("FilaRecebimentos", () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it("lista as cargas com número, ementa e link para a ficha; receber assina e recarrega a fila", async () => {
    let fila = [item("a"), item("b")];
    const fetchMock = vi.fn(async (_url: string, opts?: { method?: string; body?: string }) => {
      if (opts?.method === "POST") {
        fila = fila.filter((i) => `m-${i["proposicao-id"].slice(2)}` !== JSON.parse(opts.body!)["movimentacao-id"]);
        return { ok: true, status: 201, json: async () => ({}) } as Response;
      }
      return { ok: true, status: 200, json: async () => ({ itens: fila }) } as Response;
    });
    global.fetch = fetchMock as unknown as typeof fetch;

    render(<FilaRecebimentos token="tok" />);

    expect(await screen.findByText("2 matérias esperando recebimento")).toBeTruthy();
    expect(screen.getByText("Dispõe sobre a")).toBeTruthy();
    expect(screen.getAllByRole("link", { name: "Abrir a ficha" })[0].getAttribute("href")).toContain("/ficha-materia/p-a");

    fireEvent.click(screen.getAllByRole("button", { name: "Receber e assinar" })[0]);
    fireEvent.click(await screen.findByRole("button", { name: "Assinar recebimento" }));

    await waitFor(() =>
      expect(fetchMock).toHaveBeenCalledWith(
        "/api/legislativo/proposicoes/p-a/recebimento",
        expect.objectContaining({ method: "POST", body: JSON.stringify({ "movimentacao-id": "m-a" }) }),
      ),
    );
    expect(await screen.findByText("1 matéria esperando recebimento")).toBeTruthy();
    expect(screen.queryByText("Dispõe sobre a")).toBeNull();
  });

  it("fila vazia → mensagem honesta, sem botões", async () => {
    global.fetch = vi.fn(async () => ({ ok: true, status: 200, json: async () => ({ itens: [] }) }) as Response) as unknown as typeof fetch;
    render(<FilaRecebimentos token="tok" />);
    expect(await screen.findByText("Nenhuma carga esperando recebimento.")).toBeTruthy();
    expect(screen.queryByRole("button")).toBeNull();
  });
});
