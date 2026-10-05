import { describe, expect, it, vi } from "vitest";

const redirecionou = vi.fn((url: string) => {
  throw new Error(`redirect:${url}`);
});
vi.mock("next/navigation", () => ({ redirect: (url: string) => redirecionou(url) }));

import PaginaAssistente from "./page";

/** Para onde a página mandou: a URL inteira, não um pedaço (o `toThrow` com texto aceita substring). */
async function destino(searchParams: Record<string, string | string[] | undefined>): Promise<string> {
  const erro = await PaginaAssistente({ searchParams: Promise.resolve(searchParams) }).then(
    () => null,
    (e: Error) => e.message,
  );
  return String(erro).replace(/^redirect:/, "");
}

describe("/assistente — a tela cheia virou a Clara expandida", () => {
  it("leva à Central da Casa com a Clara expandida", async () => {
    expect(await destino({})).toBe("/inicio?clara=expandida");
  });

  it("o token de dev vai junto", async () => {
    expect(await destino({ token: "a b" })).toBe("/inicio?clara=expandida&token=a%20b");
  });

  it("token repetido na URL não é token: segue sem ele", async () => {
    expect(await destino({ token: ["a", "b"] })).toBe("/inicio?clara=expandida");
  });
});
