import { existsSync } from "node:fs";
import { join } from "node:path";
import { afterEach, describe, expect, it } from "vitest";
import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { BarraInstitucional } from "./barra-institucional";
import { TemaProvider } from "@/lib/tema";

// Task 0.6 (Fatia A2.0, Portal do Cidadão) — espelha topo.test.tsx (A1): BarraInstitucional chama
// useTema(), então precisa do mesmo <TemaProvider> que (publico)/layout.tsx aplica em produção.

describe("BarraInstitucional", () => {
  afterEach(() => {
    cleanup();
  });

  it("mostra o nome da Casa (white-label) em destaque", () => {
    render(
      <TemaProvider>
        <BarraInstitucional ente="camara-fortaleza" nomeCasa="Câmara Municipal de Fortaleza" />
      </TemaProvider>,
    );
    expect(screen.getByText("Câmara Municipal de Fortaleza").textContent).toBe(
      "Câmara Municipal de Fortaleza",
    );
  });

  it("Casa suspensa (ADR-0018): a faixa de acesso restrito, sem motivo; o portal segue", () => {
    render(
      <TemaProvider>
        <BarraInstitucional ente="camara-fortaleza" nomeCasa="Câmara Municipal de Fortaleza"
          acessoRestritoDesde="2026-09-29T13:00:00Z" />
      </TemaProvider>,
    );
    const faixa = screen.getByRole("status");
    expect(faixa.textContent).toMatch(/Sistema da Câmara com acesso restrito desde 29\/09/);
    expect(faixa.textContent).not.toMatch(/motivo/);
    expect(faixa.textContent).toMatch(/pedidos de informação, de ouvidoria e de dados pessoais continuam/);
  });

  it("Casa ativa: sem faixa", () => {
    render(
      <TemaProvider>
        <BarraInstitucional ente="camara-fortaleza" nomeCasa="Câmara Municipal de Fortaleza" />
      </TemaProvider>,
    );
    expect(screen.queryByRole("status")).toBeNull();
  });

  it("nav mobile: hambúrguer abre/fecha o MESMO <ul> via aria-expanded/aria-controls (review A2.0, item 1)", () => {
    render(
      <TemaProvider>
        <BarraInstitucional ente="camara-fortaleza" nomeCasa="Câmara Municipal de Fortaleza" />
      </TemaProvider>,
    );
    const botao = screen.getByRole("button", { name: "Abrir menu" });
    const lista = document.getElementById("nav-publica-lista");
    expect(botao.getAttribute("aria-expanded")).toBe("false");
    expect(botao.getAttribute("aria-controls")).toBe("nav-publica-lista");
    expect(lista?.getAttribute("data-aberta")).toBe("false");

    fireEvent.click(botao);
    expect(screen.getByRole("button", { name: "Fechar menu" }).getAttribute("aria-expanded")).toBe(
      "true",
    );
    expect(lista?.getAttribute("data-aberta")).toBe("true");

    fireEvent.keyDown(document, { key: "Escape" });
    expect(screen.getByRole("button", { name: "Abrir menu" }).getAttribute("aria-expanded")).toBe(
      "false",
    );
  });

  it("o glifo de tema (☀/☾) é decorativo — não entra no nome acessível do botão", () => {
    render(
      <TemaProvider>
        <BarraInstitucional ente="camara-fortaleza" nomeCasa="Câmara Municipal de Fortaleza" />
      </TemaProvider>,
    );
    const botaoTema = screen.getByTitle("Alternar tema claro / escuro");
    const glifo = botaoTema.querySelector("span[aria-hidden='true']");
    expect(glifo?.textContent).toBe("☀");
  });

  it("os links de nav são absolutos à home do ente (review final A2 — funcionam também na ficha)", () => {
    render(
      <TemaProvider>
        <BarraInstitucional ente="camara-fortaleza" nomeCasa="Câmara Municipal de Fortaleza" />
      </TemaProvider>,
    );
    expect(screen.getByRole("link", { name: "Início" }).getAttribute("href")).toBe(
      "/portal/casa/camara-fortaleza",
    );
    expect(screen.getByRole("link", { name: "Proposições" }).getAttribute("href")).toBe(
      "/portal/casa/camara-fortaleza#destaque",
    );
    expect(screen.getByRole("link", { name: "Sessões" }).getAttribute("href")).toBe(
      "/portal/casa/camara-fortaleza/pautas",
    );
    expect(screen.getByRole("link", { name: "Transparência" }).getAttribute("href")).toBe(
      "/portal/casa/camara-fortaleza/dados-abertos",
    );
    expect(screen.getByRole("link", { name: "Acesso à informação" }).getAttribute("href")).toBe(
      "/portal/casa/camara-fortaleza#balcoes",
    );
    expect(screen.getByRole("link", { name: "Ouvidoria" }).getAttribute("href")).toBe(
      "/portal/casa/camara-fortaleza/ouvidoria",
    );
  });

  it("cada item do menu leva a um lugar diferente, e nenhum é a âncora genérica #civico", () => {
    render(
      <TemaProvider>
        <BarraInstitucional ente="camara-fortaleza" nomeCasa="Câmara Municipal de Fortaleza" />
      </TemaProvider>,
    );
    const hrefs = within(screen.getByRole("navigation", { name: "Seções do portal" }))
      .getAllByRole("link")
      .map((a) => a.getAttribute("href"));
    expect(new Set(hrefs).size).toBe(hrefs.length);
    expect(hrefs.some((h) => h?.endsWith("#civico"))).toBe(false);
  });

  it("todo item do menu que não é âncora da home aponta para uma página que existe", () => {
    render(
      <TemaProvider>
        <BarraInstitucional ente="camara-fortaleza" nomeCasa="Câmara Municipal de Fortaleza" />
      </TemaProvider>,
    );
    const casa = join(__dirname, "portal", "casa", "[ente]");
    const subrotas = within(screen.getByRole("navigation", { name: "Seções do portal" }))
      .getAllByRole("link")
      .map((a) => a.getAttribute("href") ?? "")
      .map((h) => h.replace("/portal/casa/camara-fortaleza", ""))
      .filter((resto) => resto.startsWith("/"));
    expect(subrotas.length).toBeGreaterThanOrEqual(3);
    for (const resto of subrotas) {
      expect(existsSync(join(casa, ...resto.split("/").filter(Boolean), "page.tsx")), `página de ${resto}`).toBe(true);
    }
  });

  it("aria-current='page' no Início quando paginaAtual='inicio' (home)", () => {
    render(
      <TemaProvider>
        <BarraInstitucional
          ente="camara-fortaleza"
          nomeCasa="Câmara Municipal de Fortaleza"
          paginaAtual="inicio"
        />
      </TemaProvider>,
    );
    expect(screen.getByRole("link", { name: "Início" }).getAttribute("aria-current")).toBe("page");
  });

  it("aria-current ausente no Início quando paginaAtual não é passado (ex.: ficha — correção do carry #2)", () => {
    render(
      <TemaProvider>
        <BarraInstitucional ente="camara-fortaleza" nomeCasa="Câmara Municipal de Fortaleza" />
      </TemaProvider>,
    );
    expect(screen.getByRole("link", { name: "Início" }).getAttribute("aria-current")).toBeNull();
  });
});
