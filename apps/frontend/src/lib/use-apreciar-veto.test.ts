import { describe, expect, it, vi, afterEach } from "vitest";
import { renderHook, act } from "@testing-library/react";
import { useApreciarVeto } from "./use-apreciar-veto";

const ok = (json: unknown) => ({ ok: true, status: 200, json: async () => json }) as Response;
const fail = (status: number, json: unknown = {}) => ({ ok: false, status, json: async () => json }) as Response;

const tramExecJson = (estado: string) => ({
  id: "te1",
  "autografo-id": "a1",
  estado,
  "veto-tipo": "total",
  "veto-votacao-id": "vt1",
  "respondido-em": "2026-05-21T14:00:00Z",
  "apreciado-em": estado === "vetado" ? null : "2026-05-22T10:00:00Z",
  "lock-version": 3,
});

describe("useApreciarVeto", () => {
  afterEach(() => vi.restoreAllMocks());

  it("POSTa {lock-version, resultado, veto-votacao-id} kebab e devolve o TramitacaoExecutivaOut camelizado", async () => {
    let url = "";
    let corpo: Record<string, unknown> = {};
    global.fetch = vi.fn(async (u: string, init?: RequestInit) => {
      url = u;
      corpo = init?.body ? JSON.parse(init.body as string) : {};
      return ok(tramExecJson("veto_derrubado"));
    }) as unknown as typeof fetch;

    const { result } = renderHook(() => useApreciarVeto("tok", "te1"));
    let res;
    await act(async () => {
      res = await result.current.apreciar({ lockVersion: 3, resultado: "veto_derrubado", vetoVotacaoId: "vt-9" });
    });
    expect(url).toBe("/api/legislativo/tramitacoes-executivas/te1/apreciacao");
    expect(corpo).toEqual({ "lock-version": 3, resultado: "veto_derrubado", "veto-votacao-id": "vt-9" });
    expect((res as { estado: string }).estado).toBe("veto_derrubado");
    expect((res as { lockVersion: number }).lockVersion).toBe(3);
    expect(result.current.estado).toBe("ocioso");
  });

  it("409 (conflito de estado/lock) -> estado erro, mensagem do servidor propagada", async () => {
    global.fetch = vi.fn(async () => fail(409, { erro: "tramitacao ja apreciada" })) as unknown as typeof fetch;
    const { result } = renderHook(() => useApreciarVeto("tok", "te1"));
    await act(async () => {
      await expect(result.current.apreciar({ lockVersion: 1, resultado: "veto_mantido", vetoVotacaoId: "vt" })).rejects.toThrow(/ja apreciada/);
    });
    expect(result.current.estado).toBe("erro");
    expect(result.current.erro).toMatch(/ja apreciada/);
  });

  it("400 (votação inexistente) -> erro propagado", async () => {
    global.fetch = vi.fn(async () => fail(400, { erro: "votacao-inexistente" })) as unknown as typeof fetch;
    const { result } = renderHook(() => useApreciarVeto("tok", "te1"));
    await act(async () => {
      await expect(result.current.apreciar({ lockVersion: 1, resultado: "veto_mantido", vetoVotacaoId: "nao-existe" })).rejects.toThrow(/inexistente/);
    });
    expect(result.current.estado).toBe("erro");
  });

  it("sem token -> lança sem chamar fetch", async () => {
    global.fetch = vi.fn() as unknown as typeof fetch;
    const { result } = renderHook(() => useApreciarVeto(null, "te1"));
    await expect(result.current.apreciar({ lockVersion: 1, resultado: "veto_mantido", vetoVotacaoId: "vt" })).rejects.toThrow();
    expect(global.fetch).not.toHaveBeenCalled();
  });

  it("sem tramitação executiva -> lança sem chamar fetch", async () => {
    global.fetch = vi.fn() as unknown as typeof fetch;
    const { result } = renderHook(() => useApreciarVeto("tok", null));
    await expect(result.current.apreciar({ lockVersion: 1, resultado: "veto_mantido", vetoVotacaoId: "vt" })).rejects.toThrow();
    expect(global.fetch).not.toHaveBeenCalled();
  });
});
