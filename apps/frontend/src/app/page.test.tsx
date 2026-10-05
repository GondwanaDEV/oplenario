import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { existsSync, readdirSync } from "node:fs";
import { join } from "node:path";
import Home from "./page";

// A raiz `/`: o nome, a tagline, o que o produto é e os caminhos que EXISTEM. Nada de "em construção", de lista de
// clientes, preço ou contato inventados.

afterEach(() => cleanup());

/** Todas as páginas em disco, como rotas (grupos de rota `(x)` não entram na URL). */
function rotasEmDisco(dir = join(__dirname), prefixo = ""): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((e) => {
    if (e.isFile()) return e.name === "page.tsx" ? [prefixo || "/"] : [];
    if (!e.isDirectory() || e.name === "api") return [];
    const segmento = /^\(.*\)$/.test(e.name) ? "" : `/${e.name}`;
    return rotasEmDisco(join(dir, e.name), prefixo + segmento);
  });
}

describe("a raiz /", () => {
  it("diz o nome, a tagline e o que o produto é — e não diz mais 'em construção'", () => {
    const { container } = render(<Home />);
    expect(screen.getByRole("heading", { level: 1, name: "O Plenário" })).toBeTruthy();
    expect(screen.getByText("Onde a câmara acontece.")).toBeTruthy();
    expect(screen.getByText(/Plataforma de gestão para câmaras municipais/)).toBeTruthy();
    expect(container.textContent).not.toMatch(/constru[çc][ãa]o|front-end|track FE/i);
  });

  it("só oferece os caminhos que existem: entrar e o status", () => {
    render(<Home />);
    const hrefs = screen.getAllByRole("link").map((a) => a.getAttribute("href"));
    expect(hrefs).toEqual(["/entrar", "/status"]);
    expect(screen.getByRole("link", { name: "Entrar na sua Câmara" })).toBeTruthy();
    expect(screen.getByRole("link", { name: "Status da plataforma" })).toBeTruthy();
  });

  it("cada link aponta para uma página que existe no disco (um link para o nada reprova)", () => {
    const rotas = rotasEmDisco();
    render(<Home />);
    for (const a of screen.getAllByRole("link")) {
      expect(rotas, `${a.getAttribute("href")} não é página`).toContain(a.getAttribute("href"));
    }
    expect(existsSync(join(__dirname, "(publico)", "entrar", "page.tsx"))).toBe(true);
  });

  it("não inventa lista de clientes, preço nem contato", () => {
    const { container } = render(<Home />);
    expect(container.textContent).not.toMatch(/R\$|pre[çc]o|plano|contrat|clientes|whatsapp|@/i);
    expect(container.querySelectorAll("a[href^='mailto:'], a[href^='tel:'], a[href^='http']")).toHaveLength(0);
  });
});
