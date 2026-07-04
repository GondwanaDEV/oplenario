import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import { TopoInterno } from "./topo";
import { TemaProvider } from "@/lib/tema";

// TopoInterno chama useTema() (src/lib/tema.tsx) — como useAuth(), o contexto lança fora do seu Provider
// (mesmo contrato de composição documentado em src/lib/auth.tsx). O draft do brief renderizava
// <TopoInterno /> sozinho; rodando de verdade isso lança "useTema fora de TemaProvider" — o teste real
// precisa do MESMO wrapper que layout.tsx aplica em produção (mirror do padrão em auth.test.tsx).
describe("TopoInterno", () => {
  it("mostra o rótulo da área e o nome do ator", () => {
    render(
      <TemaProvider>
        <TopoInterno area="Painéis da Mesa" ator={{ nome: "Sérgio Lopes", papel: "Presidente da Mesa" }} />
      </TemaProvider>
    );
    expect(screen.getByText("Painéis da Mesa")).toBeTruthy();
    expect(screen.getByText("Sérgio Lopes")).toBeTruthy();
    expect(screen.getByText("Presidente da Mesa")).toBeTruthy();
  });
});
