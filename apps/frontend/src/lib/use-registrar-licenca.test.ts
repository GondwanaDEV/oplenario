import { afterEach, describe, expect, it, vi } from "vitest";
import { renderHook, act } from "@testing-library/react";

const fetchMock = vi.hoisted(() => vi.fn());
vi.mock("./api-fetch", () => ({ apiFetch: fetchMock }));

import { useRegistrarLicenca } from "./use-registrar-licenca";

afterEach(() => { fetchMock.mockReset(); });

function ok(body: unknown) {
  return { ok: true, status: 201, json: async () => body } as Response;
}
function erro(status: number, body: unknown) {
  return { ok: false, status, json: async () => body } as Response;
}

describe("useRegistrarLicenca", () => {
  it("POST /api/cadastros/vereadores/:id/licencas e devolve {id}", async () => {
    fetchMock.mockResolvedValueOnce(ok({ id: "l-novo" }));
    const { result } = renderHook(() => useRegistrarLicenca("tok", "v-1"));
    let dados: { id: string } | undefined;
    await act(async () => { dados = await result.current.registrar({ inicio: "2025-02-01" }); });
    expect(dados).toEqual({ id: "l-novo" });
    const [path, init] = fetchMock.mock.calls[0];
    expect(path).toBe("/api/cadastros/vereadores/v-1/licencas");
    expect(init.method).toBe("POST");
    expect(JSON.parse(init.body)).toEqual({ inicio: "2025-02-01" }); // fim/motivo undefined são filtrados
  });

  it("sem vereadorId -> throw sem chamar apiFetch", async () => {
    const { result } = renderHook(() => useRegistrarLicenca("tok", null));
    await act(async () => {
      await expect(result.current.registrar({ inicio: "2025-02-01" })).rejects.toThrow();
    });
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("erro do servidor -> estado 'erro' e throw (sem crash)", async () => {
    fetchMock.mockResolvedValueOnce(erro(400, { erro: "corpo invalido" }));
    const { result } = renderHook(() => useRegistrarLicenca("tok", "v-1"));
    await act(async () => {
      await expect(result.current.registrar({ inicio: "" })).rejects.toThrow();
    });
    expect(result.current.estado).toBe("erro");
  });
});
