import { afterEach, describe, expect, it, vi } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { useRecebimentosPendentes } from "./use-recebimentos-pendentes";

const item = {
  "proposicao-id": "p1",
  tipo: "projeto_lei",
  sequencial: 7,
  ano: 2026,
  ementa: "Dispõe sobre X",
  estado: "em_comissoes",
  "estado-nome": "Em Comissões",
  "movimentacao-id": "m1",
  "de-estado": "protocolada",
  desde: "2026-09-24T12:00:00Z",
  restrito: false,
};

describe("useRecebimentosPendentes", () => {
  afterEach(() => vi.restoreAllMocks());

  it("carrega a fila da Casa (camelizada)", async () => {
    global.fetch = vi.fn(async () => ({ ok: true, status: 200, json: async () => ({ itens: [item] }) }) as Response) as unknown as typeof fetch;
    const { result } = renderHook(() => useRecebimentosPendentes("tok"));
    await waitFor(() => expect(result.current.estado).toBe("pronto"));
    expect(result.current.itens[0]).toMatchObject({ proposicaoId: "p1", movimentacaoId: "m1", estadoNome: "Em Comissões" });
  });

  it("sem permissão → erro", async () => {
    global.fetch = vi.fn(async () => ({ ok: false, status: 403, json: async () => ({}) }) as Response) as unknown as typeof fetch;
    const { result } = renderHook(() => useRecebimentosPendentes("tok"));
    await waitFor(() => expect(result.current.estado).toBe("erro"));
  });
});
