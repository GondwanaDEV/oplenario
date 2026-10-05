import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { TemaProvider } from "@/lib/tema";
import PaginaListaVereadores from "./page";

// A página /portal/casa/[ente]/vereadores: o nome da Casa vem do servidor (resolverCasa), a lista do cliente. Um só
// `fetch` simulado atende os dois (o servidor chama o backend direto; o cliente chama /api/portal/...).

function backend(lista: { status: number; corpo: unknown }) {
  global.fetch = vi.fn(async (url: string) => {
    const u = String(url);
    if (u.includes("/api/portal/casa/")) return { ok: lista.status < 300, status: lista.status, json: async () => lista.corpo } as Response;
    return { ok: true, status: 200, json: async () => ({ "nome-oficial": "Câmara Municipal de Baturité" }) } as Response;
  }) as unknown as typeof fetch;
}

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

async function abrir(ente: string) {
  render(<TemaProvider>{await PaginaListaVereadores({ params: Promise.resolve({ ente }) })}</TemaProvider>);
}

describe("página da lista de vereadores", () => {
  it("barra com o nome real da Casa, a lista e o rodapé; o link de pular vai ao conteúdo", async () => {
    backend({ status: 200, corpo: { vereadores: [{ "vereador-id": "v1", "nome-parlamentar": "Ana Prado", "nome-civil": "Ana Prado", partido: "PSB", "cargo-mesa": null }] } });
    await abrir("casa-1");
    expect((await screen.findAllByText("Câmara Municipal de Baturité")).length).toBeGreaterThan(0);
    expect(screen.getByRole("heading", { level: 1, name: "Vereadores em exercício" })).toBeTruthy();
    expect((await screen.findByRole("link", { name: /Ana Prado/ })).getAttribute("href")).toBe("/portal/casa/casa-1/vereadores/v1");
    expect(screen.getByRole("link", { name: "Pular para o conteúdo" }).getAttribute("href")).toBe("#conteudo");
    expect(document.querySelector("main#conteudo")).toBeTruthy();
  });

  it("lista vazia: a página abre e diz, em palavras, que não há vereador registrado", async () => {
    backend({ status: 200, corpo: { vereadores: [] } });
    await abrir("casa-1");
    expect(await screen.findByText("Nenhum vereador em exercício registrado até agora.")).toBeTruthy();
  });

  it("erro da rota: a página abre mesmo assim, com o aviso — nunca uma página em branco", async () => {
    backend({ status: 500, corpo: {} });
    await abrir("casa-1");
    expect(await screen.findByRole("alert")).toBeTruthy();
    expect(screen.getByRole("heading", { level: 1, name: "Vereadores em exercício" })).toBeTruthy();
  });
});
