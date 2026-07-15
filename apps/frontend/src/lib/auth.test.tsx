import { describe, expect, it, vi, afterEach } from "vitest";
import { render, screen, cleanup } from "@testing-library/react";
import { AuthProvider, useAuth } from "./auth";

function Sonda() {
  const { token } = useAuth();
  return <div data-testid="token">{token ?? "sem-token"}</div>;
}

function SondaPapeis() {
  const { papeis } = useAuth();
  return <div data-testid="papeis">{papeis.join(",")}</div>;
}

// O modo de auth vem de NEXT_PUBLIC_APP_ENV (fonte única, ver modo.ts) — NÃO mais do NODE_ENV. A suíte
// roda com "test" (vitest.config.ts) = modo dev; os casos de modo real sobrepõem por teste. Isto elimina o
// helper setNodeEnv/Object.defineProperty que existia aqui: vi.stubEnv basta p/ uma env comum.
describe("AuthProvider/useAuth", () => {
  afterEach(() => {
    // sem `test.globals: true` no vitest.config.ts, o auto-cleanup embutido do @testing-library/react
    // (que depende de `afterEach` global) não se registra sozinho — cleanup() manual evita vazar render
    // entre os casos (o "sem-token" veria 2 nós <div data-testid="token"> sem isso).
    cleanup();
    vi.unstubAllEnvs();
  });

  it("lê o token da querystring no modo dev", () => {
    render(
      <AuthProvider tokenQuery='{"sub":"u"}'>
        <Sonda />
      </AuthProvider>
    );
    expect(screen.getByTestId("token").textContent).toBe('{"sub":"u"}');
  });

  it("bloqueia token via querystring no modo real (lança)", () => {
    vi.stubEnv("NEXT_PUBLIC_APP_ENV", "production");
    expect(() =>
      render(
        <AuthProvider tokenQuery='{"sub":"u"}'>
          <Sonda />
        </AuthProvider>
      )
    ).toThrow(/modo real/);
  });

  it("bloqueia token via querystring quando a env de modo está AUSENTE (default seguro)", () => {
    // a MESMA fonte de verdade de modoReal(): sem NEXT_PUBLIC_APP_ENV o guard tem de fechar, mesmo com
    // NODE_ENV="development" (é o caso do `next dev` apontado p/ um backend em APP_ENV=production).
    vi.stubEnv("NODE_ENV", "development");
    vi.stubEnv("NEXT_PUBLIC_APP_ENV", undefined);
    expect(() =>
      render(
        <AuthProvider tokenQuery='{"sub":"u"}'>
          <Sonda />
        </AuthProvider>
      )
    ).toThrow(/modo real/);
  });

  it("no modo real sem tokenQuery -> token null (o cookie decide, nem NEXT_PUBLIC_DEV_TOKEN vale)", () => {
    vi.stubEnv("NEXT_PUBLIC_APP_ENV", "production");
    vi.stubEnv("NEXT_PUBLIC_DEV_TOKEN", '{"sub":"forjado"}');
    render(
      <AuthProvider tokenQuery={null}>
        <Sonda />
      </AuthProvider>
    );
    expect(screen.getByTestId("token").textContent).toBe("sem-token");
  });

  it("sem token nenhum -> null", () => {
    render(
      <AuthProvider tokenQuery={null}>
        <Sonda />
      </AuthProvider>
    );
    expect(screen.getByTestId("token").textContent).toBe("sem-token");
  });

  it("papeis vem do campo 'papeis' do JSON do token", () => {
    render(
      <AuthProvider tokenQuery='{"sub":"u","papeis":["vereador"]}'>
        <SondaPapeis />
      </AuthProvider>
    );
    expect(screen.getByTestId("papeis").textContent).toBe("vereador");
  });

  it("papeis vazio quando o token nao tem o campo", () => {
    render(
      <AuthProvider tokenQuery='{"sub":"u"}'>
        <SondaPapeis />
      </AuthProvider>
    );
    expect(screen.getByTestId("papeis").textContent).toBe("");
  });

  it("papeis vazio quando nao ha token nenhum", () => {
    render(
      <AuthProvider tokenQuery={null}>
        <SondaPapeis />
      </AuthProvider>
    );
    expect(screen.getByTestId("papeis").textContent).toBe("");
  });
});
