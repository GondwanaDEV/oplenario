import { afterEach, describe, expect, it, vi } from "vitest";
import { renderHook, act } from "@testing-library/react";

const fetchMock = vi.hoisted(() => vi.fn());
vi.mock("./api-fetch", () => ({ apiFetch: fetchMock }));

import { useCriarVereador } from "./use-criar-vereador";

afterEach(() => { fetchMock.mockReset(); });

function ok(body: unknown) {
  return { ok: true, status: 201, json: async () => body } as Response;
}
function erro(status: number, body: unknown) {
  return { ok: false, status, json: async () => body } as Response;
}

describe("useCriarVereador", () => {
  it("POST /api/cadastros/vereadores e devolve {id}", async () => {
    fetchMock.mockResolvedValueOnce(ok({ id: "v-novo" }));
    const { result } = renderHook(() => useCriarVereador("tok"));
    let dados: { id: string } | undefined;
    await act(async () => { dados = await result.current.criar({ nome: "Helena" }); });
    expect(dados).toEqual({ id: "v-novo" });
    const [path, init] = fetchMock.mock.calls[0];
    expect(path).toBe("/api/cadastros/vereadores");
    expect(init.method).toBe("POST");
    expect(JSON.parse(init.body)).toEqual({ nome: "Helena" }); // nomeParlamentar undefined é filtrado
  });

  it("erro do servidor -> estado 'erro' e throw (sem crash)", async () => {
    fetchMock.mockResolvedValueOnce(erro(400, { erro: "corpo invalido" }));
    const { result } = renderHook(() => useCriarVereador("tok"));
    await act(async () => {
      await expect(result.current.criar({ nome: "" })).rejects.toThrow();
    });
    expect(result.current.estado).toBe("erro");
  });
});
