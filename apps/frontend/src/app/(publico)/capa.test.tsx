import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { Capa } from "./capa";

// Task 1.2 (Fatia A2.1, Portal do Cidadão) — porte de portal-cidadao.html:358-403 (a capa/hero). Busca e
// "ao vivo agora" NÃO têm rota pública real nesta fatia (sem backend de busca; sem rota de sessão
// pública) — ambos honestos via <EmBreve>, nunca fingidos (Global Constraints do plano). Convenção do
// projeto: sem @testing-library/jest-dom — asserções via textContent/getAttribute cru.

describe("Capa", () => {
  afterEach(() => cleanup());

  it("renderiza a manchete", () => {
    render(<Capa />);
    expect(screen.getByRole("heading", { level: 1 }).textContent).toContain("Câmara");
  });

  it("'ao vivo agora' é em-breve honesto (sem rota pública de sessão nesta fatia)", () => {
    render(<Capa />);
    const status = screen.getByRole("status", { name: /sessão ao vivo: em breve/i });
    expect(status.textContent).toContain("Em breve");
  });

  it("busca é em-breve honesto (sem backend de busca nesta fatia)", () => {
    render(<Capa />);
    const emBreves = screen.getAllByRole("status");
    // duas superfícies em-breve nesta tela: "ao vivo agora" + "busca no portal".
    expect(emBreves.length).toBe(2);
    expect(emBreves.some((el) => /busca/i.test(el.textContent ?? ""))).toBe(true);
  });

  it("os chips levam a âncoras reais da página (#destaque, #civico, #balcoes)", () => {
    render(<Capa />);
    const links = screen.getAllByRole("link");
    const hrefs = links.map((a) => a.getAttribute("href"));
    expect(hrefs).toContain("#destaque");
    expect(hrefs.every((h) => h?.startsWith("#"))).toBe(true);
  });

  it("a grade da capa tem a classe .envelope (margem lateral) — sem ela o conteúdo cola nas bordas da viewport", () => {
    // Reprodução de bug real (review visual): a .capa-grade sozinha é só grid (sem max-width/padding);
    // quem centraliza e dá a margem lateral é .envelope (chassi.css). O porte original de
    // portal-cidadao.html:360 é <div class="envelope capa-grade"> — perder a classe faz o título e o
    // selo cívico esticarem de ponta a ponta da tela.
    const { container } = render(<Capa />);
    const grade = container.querySelector(".capa-grade");
    expect(grade?.classList.contains("envelope")).toBe(true);
  });
});
