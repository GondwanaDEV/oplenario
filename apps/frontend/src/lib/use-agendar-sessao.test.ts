import { describe, expect, it, vi, afterEach } from "vitest";
import { renderHook, act } from "@testing-library/react";
import { useAgendarSessao } from "./use-agendar-sessao";

const ok = (json: unknown) => ({ ok: true, status: 200, json: async () => json }) as Response;
const fail = (status: number, json: unknown = {}) => ({ ok: false, status, json: async () => json }) as Response;

describe("useAgendarSessao", () => {
  afterEach(() => vi.restoreAllMocks());

  it("POSTa o corpo kebab (só campos preenchidos) e devolve o SessaoOut camelizado", async () => {
    let corpo: Record<string, unknown> = {};
    global.fetch = vi.fn(async (_url: string, init?: RequestInit) => {
      corpo = init?.body ? JSON.parse(init.body as string) : {};
      return ok({ id: "s-nova", "sessao-legislativa-id": "sl-A", "tipo-sessao": "ordinaria", estado: "agendada", "lock-version": 0 });
    }) as unknown as typeof fetch;

    const { result } = renderHook(() => useAgendarSessao("tok"));
    let res;
    await act(async () => {
      res = await result.current.agendar({
        sessaoLegislativaId: "sl-A",
        tipoSessao: "ordinaria",
        agendadaPara: "2026-05-21T17:00:00.000Z",
      });
    });
    expect((res as { ok: boolean }).ok).toBe(true);
    expect((res as { sessao: { id: string } }).sessao.id).toBe("s-nova");
    expect(corpo).toMatchObject({
      "sessao-legislativa-id": "sl-A",
      "tipo-sessao": "ordinaria",
      "agendada-para": "2026-05-21T17:00:00.000Z",
    });
    expect(corpo.modalidade).toBeUndefined(); // não preenchido -> chave ausente
    expect(result.current.estado).toBe("ocioso");
  });

  it("inclui modalidade quando informada", async () => {
    let corpo: Record<string, unknown> = {};
    global.fetch = vi.fn(async (_url: string, init?: RequestInit) => {
      corpo = init?.body ? JSON.parse(init.body as string) : {};
      return ok({ id: "s2", "sessao-legislativa-id": "sl", "tipo-sessao": "solene", estado: "agendada", "lock-version": 0 });
    }) as unknown as typeof fetch;
    const { result } = renderHook(() => useAgendarSessao("tok"));
    await act(async () => {
      await result.current.agendar({ sessaoLegislativaId: "sl", tipoSessao: "solene", modalidade: "hibrida" });
    });
    expect(corpo).toMatchObject({ modalidade: "hibrida" });
  });

  it("erro do servidor -> estado erro e resultado ok:false com a mensagem", async () => {
    global.fetch = vi.fn(async () => fail(400, { erro: "sessao-legislativa-id invalido" })) as unknown as typeof fetch;
    const { result } = renderHook(() => useAgendarSessao("tok"));
    let res;
    await act(async () => {
      res = await result.current.agendar({ sessaoLegislativaId: "x", tipoSessao: "ordinaria" });
    });
    expect((res as { ok: boolean }).ok).toBe(false);
    expect((res as { erro: string }).erro).toMatch(/invalido/);
    expect(result.current.estado).toBe("erro");
  });

  it("sem token -> ok:false, sem chamar fetch", async () => {
    global.fetch = vi.fn() as unknown as typeof fetch;
    const { result } = renderHook(() => useAgendarSessao(null));
    let res;
    await act(async () => {
      res = await result.current.agendar({ sessaoLegislativaId: "sl", tipoSessao: "ordinaria" });
    });
    expect((res as { ok: boolean }).ok).toBe(false);
    expect(global.fetch).not.toHaveBeenCalled();
  });
});
