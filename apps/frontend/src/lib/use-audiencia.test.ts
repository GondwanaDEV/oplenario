import { afterEach, describe, expect, it, vi } from "vitest";
import { encerrarFala, mensagemDeErroAudiencia, pedirAudiencia } from "./use-audiencia";

afterEach(() => vi.restoreAllMocks());

describe("mensagemDeErroAudiencia — cada recusa em palavras", () => {
  it("authz, conflito e Casa restrita", () => {
    expect(mensagemDeErroAudiencia(403, "chamar")).toMatch(/Só a secretaria/);
    expect(mensagemDeErroAudiencia(404, "carregar")).toMatch(/não é uma audiência pública/);
    expect(mensagemDeErroAudiencia(409, "chamar")).toMatch(/sessão precisa estar aberta/);
    expect(mensagemDeErroAudiencia(409, "chamar", "ja ha uma fala em curso")).toBe("Ja ha uma fala em curso.");
    expect(mensagemDeErroAudiencia(423, "inscrever")).toMatch(/acesso restrito/);
    expect(mensagemDeErroAudiencia(0, "encerrar")).toMatch(/Nada foi gravado/);
  });
});

describe("pedirAudiencia", () => {
  it("forma que não bate vira erro, nunca meio-dado", async () => {
    global.fetch = vi.fn(async () => ({ ok: true, status: 200, json: async () => ({ x: 1 }) }) as Response) as unknown as typeof fetch;
    const r = await pedirAudiencia("tok", "/api/x", "carregar", {}, () => false);
    expect(r.ok).toBe(false);
  });

  it("encerrar arredonda o tempo usado e nunca manda negativo", async () => {
    const corpos: unknown[] = [];
    global.fetch = vi.fn(async (_u: string, init?: RequestInit) => {
      corpos.push(JSON.parse(String(init?.body)));
      return { ok: true, status: 200, json: async () => ({}) } as Response;
    }) as unknown as typeof fetch;
    await encerrarFala("tok", "s1", "i1", 61.6);
    await encerrarFala("tok", "s1", "i1", -3);
    expect(corpos).toEqual([{ "tempo-usado-segundos": 62 }, { "tempo-usado-segundos": 0 }]);
  });
});
