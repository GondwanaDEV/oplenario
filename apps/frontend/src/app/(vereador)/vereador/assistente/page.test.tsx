import { describe, expect, it, vi } from "vitest";

const redirecionou = vi.fn((url: string) => {
  throw new Error(`redirect:${url}`);
});
vi.mock("next/navigation", () => ({ redirect: (url: string) => redirecionou(url) }));

import PaginaAssistenteVereador from "./page";

describe("/vereador/assistente — a tela cheia virou a Clara expandida no app", () => {
  it("leva à home do vereador com a Clara expandida", async () => {
    await expect(PaginaAssistenteVereador({ searchParams: Promise.resolve({}) })).rejects.toThrow(
      "redirect:/vereador?clara=expandida",
    );
  });

  it("o token de dev vai junto", async () => {
    await expect(PaginaAssistenteVereador({ searchParams: Promise.resolve({ token: "a b" }) })).rejects.toThrow(
      "redirect:/vereador?clara=expandida&token=a%20b",
    );
  });
});
