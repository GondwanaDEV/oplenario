import { afterEach, describe, expect, it, vi } from "vitest";
import { renderHook, act } from "@testing-library/react";

const fetchMock = vi.hoisted(() => vi.fn());
vi.mock("./api-fetch", () => ({ apiFetch: fetchMock }));

import { useRegistrarMandato } from "./use-registrar-mandato";

afterEach(() => { fetchMock.mockReset(); });

function ok(body: unknown) {
  return { ok: true, status: 201, json: async () => body } as Response;
}
function erro(status: number, body: unknown) {
  return { ok: false, status, json: async () => body } as Response;
}

describe("useRegistrarMandato", () => {
  it("POST /api/cadastros/vereadores/:id/mandatos e devolve {id}", async () => {
    fetchMock.mockResolvedValueOnce(ok({ id: "m-novo" }));
    const { result } = renderHook(() => useRegistrarMandato("tok", "v-1"));
    let dados: { id: string } | undefined;
    await act(async () => {
      dados = await result.current.registrar({
        legislaturaId: "leg-1",
        natureza: "titular",
        vigenciaInicio: "2025-01-01",
      });
    });
    expect(dados).toEqual({ id: "m-novo" });
    const [path, init] = fetchMock.mock.calls[0];
    expect(path).toBe("/api/cadastros/vereadores/v-1/mandatos");
    expect(init.method).toBe("POST");
    expect(JSON.parse(init.body)).toEqual({
      "legislatura-id": "leg-1",
      natureza: "titular",
      "vigencia-inicio": "2025-01-01",
    }); // partido e vigenciaFim undefined são filtrados
  });

  it("sem vereadorId -> throw sem chamar apiFetch", async () => {
    const { result } = renderHook(() => useRegistrarMandato("tok", null));
    await act(async () => {
      await expect(
        result.current.registrar({ legislaturaId: "leg-1", natureza: "titular", vigenciaInicio: "2025-01-01" }),
      ).rejects.toThrow();
    });
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("erro do servidor -> estado 'erro' e throw (sem crash)", async () => {
    fetchMock.mockResolvedValueOnce(erro(400, { erro: "corpo invalido" }));
    const { result } = renderHook(() => useRegistrarMandato("tok", "v-1"));
    await act(async () => {
      await expect(
        result.current.registrar({ legislaturaId: "", natureza: "", vigenciaInicio: "" }),
      ).rejects.toThrow();
    });
    expect(result.current.estado).toBe("erro");
  });
});
