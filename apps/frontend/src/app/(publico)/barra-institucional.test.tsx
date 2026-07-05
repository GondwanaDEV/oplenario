import { afterEach, describe, expect, it } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
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
        <BarraInstitucional nomeCasa="Câmara Municipal de Fortaleza" />
      </TemaProvider>,
    );
    expect(screen.getByText("Câmara Municipal de Fortaleza").textContent).toBe(
      "Câmara Municipal de Fortaleza",
    );
  });

  it("nav mobile: hambúrguer abre/fecha o MESMO <ul> via aria-expanded/aria-controls (review A2.0, item 1)", () => {
    render(
      <TemaProvider>
        <BarraInstitucional nomeCasa="Câmara Municipal de Fortaleza" />
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
        <BarraInstitucional nomeCasa="Câmara Municipal de Fortaleza" />
      </TemaProvider>,
    );
    const botaoTema = screen.getByTitle("Alternar tema claro / escuro");
    const glifo = botaoTema.querySelector("span[aria-hidden='true']");
    expect(glifo?.textContent).toBe("☀");
  });
});
