import { describe, expect, it, vi, afterEach } from "vitest";
import { render, screen, cleanup } from "@testing-library/react";
import { AuthProvider, useAuth } from "./auth";

function Sonda() {
  const { token } = useAuth();
  return <div data-testid="token">{token ?? "sem-token"}</div>;
}

// Node 22 (ERR_INVALID_OBJECT_DEFINE_PROPERTY): process.env exige descriptor com writable+enumerable+
// configurable TODOS true — o rascunho do brief só passava `configurable`, o que quebra nesta versão de
// Node (writable/enumerable ficam false por default no Object.defineProperty). Helper local fixa isso
// preservando a mesma intenção do teste (forçar NODE_ENV por caso, restaurar no afterEach).
function setNodeEnv(value: string | undefined) {
  Object.defineProperty(process.env, "NODE_ENV", { value, writable: true, enumerable: true, configurable: true });
}

describe("AuthProvider/useAuth", () => {
  const originalEnv = process.env.NODE_ENV;
  afterEach(() => {
    // sem `test.globals: true` no vitest.config.ts, o auto-cleanup embutido do @testing-library/react
    // (que depende de `afterEach` global) não se registra sozinho — cleanup() manual evita vazar render
    // entre os 3 casos (o "sem-token" veria 2 nós <div data-testid="token"> sem isso).
    cleanup();
    setNodeEnv(originalEnv);
    vi.unstubAllEnvs();
  });

  it("lê o token da querystring fora de produção", () => {
    setNodeEnv("test");
    render(
      <AuthProvider tokenQuery='{"sub":"u"}'>
        <Sonda />
      </AuthProvider>
    );
    expect(screen.getByTestId("token").textContent).toBe('{"sub":"u"}');
  });

  it("bloqueia token via querystring em produção (lança)", () => {
    setNodeEnv("production");
    expect(() =>
      render(
        <AuthProvider tokenQuery='{"sub":"u"}'>
          <Sonda />
        </AuthProvider>
      )
    ).toThrow(/produção/);
  });

  it("sem token nenhum -> null", () => {
    setNodeEnv("test");
    render(
      <AuthProvider tokenQuery={null}>
        <Sonda />
      </AuthProvider>
    );
    expect(screen.getByTestId("token").textContent).toBe("sem-token");
  });
});
