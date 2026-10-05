import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import { ListaVereadores } from "./lista-vereadores";

// A lista pública dos vereadores em exercício (GET /portal/casa/{ente}/vereadores): a porta do perfil, que só abria
// por UUID. Anônima: sem token, e o que sai da tela é só o que o contrato publica.

function mockar(status: number, corpo: unknown) {
  const urls: string[] = [];
  global.fetch = vi.fn(async (url: string) => {
    urls.push(String(url));
    return { ok: status < 300, status, json: async () => corpo } as Response;
  }) as unknown as typeof fetch;
  return urls;
}

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

// o fio do backend: chaves kebab, como o servidor serializa (o `buscarPublico` camelizou).
const fio = {
  vereadores: [
    { "vereador-id": "id-bruno", "nome-parlamentar": null, "nome-civil": "Bruno Lima", partido: "PT", "cargo-mesa": null },
    { "vereador-id": "id-ana", "nome-parlamentar": "Ana Prado", "nome-civil": "Ana Maria Prado", partido: "PSB", "cargo-mesa": "1_secretario" },
    { "vereador-id": "id-cida", "nome-parlamentar": "  ", "nome-civil": "Cida Nogueira", partido: null, "cargo-mesa": null },
  ],
};

describe("ListaVereadores", () => {
  it("lista os vereadores em exercício em ordem alfabética, cada um levando ao perfil", async () => {
    const urls = mockar(200, fio);
    render(<ListaVereadores ente="casa-1" />);
    const lista = await screen.findByRole("list", { name: "Vereadores em exercício" });
    const cartoes = within(lista).getAllByRole("link");
    expect(cartoes.map((a) => a.querySelector(".lv-nome")?.textContent)).toEqual(["Ana Prado", "Bruno Lima", "Cida Nogueira"]);
    expect(cartoes.map((a) => a.getAttribute("href"))).toEqual([
      "/portal/casa/casa-1/vereadores/id-ana",
      "/portal/casa/casa-1/vereadores/id-bruno",
      "/portal/casa/casa-1/vereadores/id-cida",
    ]);
    expect(urls).toEqual(["/api/portal/casa/casa-1/vereadores"]);
    expect(screen.getByText("3 vereadores em exercício")).toBeTruthy();
  });

  it("nome parlamentar em destaque e o civil por baixo, só quando difere; partido e cargo na Mesa como etiquetas", async () => {
    mockar(200, fio);
    render(<ListaVereadores ente="casa-1" />);
    const lista = await screen.findByRole("list", { name: "Vereadores em exercício" });
    const [ana, bruno, cida] = within(lista).getAllByRole("link");
    expect(ana.querySelector(".lv-civil")?.textContent).toBe("Ana Maria Prado");
    expect([...ana.querySelectorAll(".lv-tag")].map((t) => t.textContent)).toEqual(["1ª Secretaria", "PSB"]);
    expect(bruno.querySelector(".lv-civil")).toBeNull();
    expect(bruno.querySelector(".lv-nome")?.textContent).toBe("Bruno Lima");
    expect([...bruno.querySelectorAll(".lv-tag")].map((t) => t.textContent)).toEqual(["PT"]);
    // apelido em branco conta como ausente: nome civil, sem repetir; sem partido nem cargo, sem etiquetas vazias
    expect(cida.querySelector(".lv-nome")?.textContent).toBe("Cida Nogueira");
    expect(cida.querySelector(".lv-civil")).toBeNull();
    expect(cida.querySelector(".lv-tags")).toBeNull();
  });

  it("nenhum UUID vira texto: as iniciais saem do nome, e o id só aparece no endereço", async () => {
    mockar(200, { vereadores: [{ "vereador-id": "7f3c2a10-aaaa-bbbb-cccc-0123456789ab", "nome-parlamentar": "Ana Prado", "nome-civil": "Ana Prado", partido: null, "cargo-mesa": null }] });
    const { container } = render(<ListaVereadores ente="casa-1" />);
    await screen.findByRole("list", { name: "Vereadores em exercício" });
    expect(container.textContent).not.toContain("7f3c2a10");
    expect(container.querySelector(".lv-foto")?.textContent).toBe("AP");
    expect(screen.getByText("1 vereador em exercício")).toBeTruthy();
  });

  it("o ente e o id são codificados no endereço do perfil", async () => {
    mockar(200, { vereadores: [{ "vereador-id": "a/b", "nome-parlamentar": "Ana", "nome-civil": "Ana", partido: null, "cargo-mesa": null }] });
    render(<ListaVereadores ente="casa/estranha" />);
    const lista = await screen.findByRole("list", { name: "Vereadores em exercício" });
    expect(within(lista).getByRole("link").getAttribute("href")).toBe("/portal/casa/casa%2Festranha/vereadores/a%2Fb");
  });

  it("Casa sem vereador em exercício: aviso em palavras, sem lista vazia fingindo dado", async () => {
    mockar(200, { vereadores: [] });
    render(<ListaVereadores ente="casa-1" />);
    expect((await screen.findByText("Nenhum vereador em exercício registrado até agora.")).getAttribute("role")).toBe("status");
    expect(screen.queryByRole("list", { name: "Vereadores em exercício" })).toBeNull();
    expect(screen.queryByRole("alert")).toBeNull();
  });

  it("falha (rota fora do ar, ou Casa que não existe): aviso de erro, nunca lista", async () => {
    mockar(404, {});
    render(<ListaVereadores ente="casa-x" />);
    const aviso = await screen.findByRole("alert");
    expect(aviso.textContent).toMatch(/Não foi possível carregar a lista de vereadores/);
    expect(screen.queryByRole("list", { name: "Vereadores em exercício" })).toBeNull();
  });

  it("resposta fora da forma (sem a lista) também é erro, não tela vazia", async () => {
    mockar(200, { algo: "outro" });
    render(<ListaVereadores ente="casa-1" />);
    expect(await screen.findByRole("alert")).toBeTruthy();
  });

  it("enquanto carrega, uma região viva com o texto", () => {
    global.fetch = vi.fn(() => new Promise(() => {})) as unknown as typeof fetch;
    render(<ListaVereadores ente="casa-1" />);
    expect(screen.getByRole("status").textContent).toBe("Carregando os vereadores…");
  });

  it("trocar de Câmara nunca mostra a lista da anterior", async () => {
    mockar(200, fio);
    const { rerender } = render(<ListaVereadores ente="casa-1" />);
    await screen.findByRole("list", { name: "Vereadores em exercício" });
    global.fetch = vi.fn(() => new Promise(() => {})) as unknown as typeof fetch;
    rerender(<ListaVereadores ente="casa-2" />);
    await waitFor(() => expect(screen.queryByRole("list", { name: "Vereadores em exercício" })).toBeNull());
    expect(screen.getByRole("status").textContent).toBe("Carregando os vereadores…");
  });
});
