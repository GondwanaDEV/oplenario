import { afterEach, describe, expect, it, vi } from "vitest";
import { renderHook, act } from "@testing-library/react";

const fetchMock = vi.hoisted(() => vi.fn());
vi.mock("./api-fetch", () => ({ apiFetch: fetchMock }));

import { useEditarVereador } from "./use-editar-vereador";

afterEach(() => { fetchMock.mockReset(); });

function ok(body: unknown) {
  return { ok: true, status: 200, json: async () => body } as Response;
}
function erro(status: number, body: unknown) {
  return { ok: false, status, json: async () => body } as Response;
}

describe("useEditarVereador", () => {
  it("PATCH /api/cadastros/vereadores/:id e devolve {id}", async () => {
    fetchMock.mockResolvedValueOnce(ok({ id: "v-1" }));
    const { result } = renderHook(() => useEditarVereador("tok", "v-1"));
    let dados: { id: string } | undefined;
    await act(async () => { dados = await result.current.editar({ nome: "Helena Souza" }); });
    expect(dados).toEqual({ id: "v-1" });
    const [path, init] = fetchMock.mock.calls[0];
    expect(path).toBe("/api/cadastros/vereadores/v-1");
    expect(init.method).toBe("PATCH");
    expect(JSON.parse(init.body)).toEqual({ nome: "Helena Souza" }); // nomeParlamentar undefined é filtrado
  });

  it("sem vereadorId -> throw sem chamar apiFetch", async () => {
    const { result } = renderHook(() => useEditarVereador("tok", null));
    await act(async () => {
      await expect(result.current.editar({ nome: "x" })).rejects.toThrow();
    });
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("erro do servidor -> estado 'erro' e throw (sem crash)", async () => {
    fetchMock.mockResolvedValueOnce(erro(400, { erro: "corpo invalido" }));
    const { result } = renderHook(() => useEditarVereador("tok", "v-1"));
    await act(async () => {
      await expect(result.current.editar({ nome: "" })).rejects.toThrow();
    });
    expect(result.current.estado).toBe("erro");
  });
});
