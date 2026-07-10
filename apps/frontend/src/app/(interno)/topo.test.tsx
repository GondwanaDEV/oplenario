import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import { TopoInterno } from "./topo";
import { TemaProvider } from "@/lib/tema";
import { AuthProvider } from "@/lib/auth";

// TopoInterno chama useTema() (src/lib/tema.tsx) e, desde a Task 14, useAuth() (src/lib/auth.tsx) — como
// useTema(), o contexto lança fora do seu Provider (mesmo contrato de composição documentado em
// src/lib/auth.tsx). O draft do brief renderizava <TopoInterno /> sozinho; rodando de verdade isso lança
// "useAuth fora de AuthProvider" — o teste real precisa do MESMO wrapper que layout.tsx aplica em produção
// (mirror do padrão em auth.test.tsx / proposicoes/page.test.tsx).
describe("TopoInterno", () => {
  it("mostra o rótulo da área e o nome do ator", () => {
    render(
      <AuthProvider tokenQuery="abc123">
        <TemaProvider>
          <TopoInterno area="Painéis da Mesa" ator={{ nome: "Sérgio Lopes", papel: "Presidente da Mesa" }} />
        </TemaProvider>
      </AuthProvider>
    );
    // selector: ".area-tag" desambigua da Task 12 — o novo <nav> (abaixo) também renderiza o texto
    // "Painéis da Mesa" no link ativo; sem o selector, getByText acha 2 elementos e lança.
    expect(screen.getByText("Painéis da Mesa", { selector: ".area-tag" })).toBeTruthy();
    expect(screen.getByText("Sérgio Lopes")).toBeTruthy();
    expect(screen.getByText("Presidente da Mesa")).toBeTruthy();
    expect(screen.getByRole("link", { name: "Painéis da Mesa" })).toBeTruthy();
    expect(screen.getByRole("link", { name: "Proposições" })).toBeTruthy();
    expect(screen.getByRole("link", { name: "Painéis da Mesa" }).getAttribute("aria-current")).toBe("page");
    // Task 14 — comToken deve preservar o ?token= dev na navegação interna via <Link>.
    expect(screen.getByRole("link", { name: "Proposições" }).getAttribute("href")).toBe("/proposicoes?token=abc123");
  });
});
