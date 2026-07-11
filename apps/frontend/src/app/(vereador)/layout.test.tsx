import { describe, expect, it, afterEach } from "vitest";
import { render, screen, cleanup } from "@testing-library/react";
import { AuthProvider } from "@/lib/auth";
import { TemaProvider } from "@/lib/tema";
import { GuardVereador } from "./layout";

describe("GuardVereador", () => {
  afterEach(cleanup);

  it("renderiza os children quando o ator tem papel 'vereador'", () => {
    // TemaProvider aninhado: autorizado, GuardVereador monta o shell (topo usa useTema) — mesmo contrato
    // de composição de (interno)/layout.tsx (AuthProvider > TemaProvider > guard/shell).
    render(
      <AuthProvider tokenQuery='{"sub":"u","papeis":["vereador"]}'>
        <TemaProvider>
          <GuardVereador>
            <div data-testid="conteudo">home do vereador</div>
          </GuardVereador>
        </TemaProvider>
      </AuthProvider>
    );
    expect(screen.getByTestId("conteudo")).toBeTruthy();
  });

  it("bloqueia (nega render dos children) sem o papel 'vereador'", () => {
    render(
      <AuthProvider tokenQuery='{"sub":"u","papeis":["secretario"]}'>
        <GuardVereador>
          <div data-testid="conteudo">home do vereador</div>
        </GuardVereador>
      </AuthProvider>
    );
    expect(screen.queryByTestId("conteudo")).toBeNull();
    expect(screen.getByText("Acesso restrito")).toBeTruthy();
  });

  it("bloqueia sem papeis nenhum (token sem o campo)", () => {
    render(
      <AuthProvider tokenQuery='{"sub":"u"}'>
        <GuardVereador>
          <div data-testid="conteudo">home do vereador</div>
        </GuardVereador>
      </AuthProvider>
    );
    expect(screen.queryByTestId("conteudo")).toBeNull();
  });
});
