import { afterEach, describe, expect, it, vi } from "vitest";

// A raiz `/` não tem tela: leva direto ao login, ou ao início de quem já entrou (a mesma regra do middleware).

const sessao = { tem: false };
vi.mock("next/headers", () => ({ cookies: async () => ({ has: (nome: string) => nome === "sessao" && sessao.tem }) }));
const redirecionou = vi.fn((url: string) => {
  throw new Error(`redirect:${url}`);
});
vi.mock("next/navigation", () => ({ redirect: (url: string) => redirecionou(url) }));

import Raiz from "./page";

afterEach(() => {
  sessao.tem = false;
  redirecionou.mockClear();
});

describe("a raiz /", () => {
  it("sem sessão vai direto à entrada pelo CPF", async () => {
    await expect(Raiz()).rejects.toThrow("redirect:/entrar");
  });

  it("com sessão vai ao início", async () => {
    sessao.tem = true;
    await expect(Raiz()).rejects.toThrow("redirect:/inicio");
  });
});
