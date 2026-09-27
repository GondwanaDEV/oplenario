import { afterEach, describe, expect, it, vi } from "vitest";
import { act, renderHook } from "@testing-library/react";
import { useReceber } from "./use-receber";

type Chamada = { url: string; init?: RequestInit };

function mockFetch(resposta: Response) {
  const chamadas: Chamada[] = [];
  global.fetch = vi.fn(async (url: string, init?: RequestInit) => {
    chamadas.push({ url, init });
    return resposta;
  }) as unknown as typeof fetch;
  return chamadas;
}

const resp = (status: number, json: unknown = {}) =>
  ({ ok: status >= 200 && status < 300, status, json: async () => json }) as Response;

async function receber(token: string | null = "tok") {
  const { result } = renderHook(() => useReceber(token));
  let r: unknown;
  await act(async () => {
    r = await result.current.receber("p1", "m1");
  });
  return r;
}

describe("useReceber", () => {
  afterEach(() => vi.restoreAllMocks());

  it("POST com a movimentação vista, em kebab; 201 → ok", async () => {
    const chamadas = mockFetch(resp(201, { id: "r1" }));
    expect(await receber()).toEqual({ ok: true });
    expect(chamadas[0].url).toMatch(/\/api\/legislativo\/proposicoes\/p1\/recebimento$/);
    expect(chamadas[0].init?.method).toBe("POST");
    expect(JSON.parse(chamadas[0].init!.body as string)).toEqual({ "movimentacao-id": "m1" });
  });

  it("409 movimentação divergente → pede para recarregar e conferir", async () => {
    mockFetch(resp(409, { motivo: "movimentacao-divergente", erro: "x" }));
    expect(await receber()).toEqual({ ok: false, erro: expect.stringMatching(/se movimentou/), recarregar: true });
  });

  it("409 já recebida → recarrega", async () => {
    mockFetch(resp(409, { motivo: "sem-recebimento-pendente" }));
    expect(await receber()).toEqual({ ok: false, erro: expect.stringMatching(/já foi recebida/), recarregar: true });
  });

  it("403 → fora da regra de quem recebe, sem recarregar", async () => {
    mockFetch(resp(403, { erro: "x" }));
    expect(await receber()).toEqual({ ok: false, erro: expect.stringMatching(/autoriza a receber/), recarregar: false });
  });

  it("sem credencial não chama o servidor", async () => {
    const chamadas = mockFetch(resp(201));
    expect(await receber(null)).toMatchObject({ ok: false });
    expect(chamadas).toHaveLength(0);
  });
});
