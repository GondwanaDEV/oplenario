import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
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
});
