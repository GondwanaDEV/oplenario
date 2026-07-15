import { describe, expect, it, vi, afterEach } from "vitest";
import { render, screen, cleanup, waitFor } from "@testing-library/react";
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

describe("GuardVereador (modo real — papéis via /eu)", () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    vi.unstubAllEnvs();
  });

  it("renderiza os children quando /eu devolve o papel 'vereador'", async () => {
    vi.stubEnv("NEXT_PUBLIC_APP_ENV", "production");
    global.fetch = vi.fn(
      async () => ({ ok: true, json: async () => ({ ator: { papeis: ["vereador"] } }) }) as Response
    ) as unknown as typeof fetch;

    render(
      <AuthProvider tokenQuery={null}>
        <TemaProvider>
          <GuardVereador>
            <div data-testid="conteudo">home do vereador</div>
          </GuardVereador>
        </TemaProvider>
      </AuthProvider>
    );

    await waitFor(() => expect(screen.getByTestId("conteudo")).toBeTruthy());
  });

  it("bloqueia quando /eu devolve papéis sem 'vereador'", async () => {
    vi.stubEnv("NEXT_PUBLIC_APP_ENV", "production");
    global.fetch = vi.fn(
      async () => ({ ok: true, json: async () => ({ ator: { papeis: ["secretario"] } }) }) as Response
    ) as unknown as typeof fetch;

    render(
      <AuthProvider tokenQuery={null}>
        <GuardVereador>
          <div data-testid="conteudo">home do vereador</div>
        </GuardVereador>
      </AuthProvider>
    );

    await waitFor(() => expect(screen.getByText("Acesso restrito")).toBeTruthy());
    expect(screen.queryByTestId("conteudo")).toBeNull();
  });

  it("enquanto /eu ainda não respondeu, não renderiza children NEM 'Acesso restrito' (sem flash)", () => {
    vi.stubEnv("NEXT_PUBLIC_APP_ENV", "production");
    const resposta: Response = { ok: true, json: async () => ({ ator: { papeis: ["vereador"] } }) } as Response;
    const resolveFns: Array<(r: Response) => void> = [];
    const pendente = new Promise<Response>((resolve) => resolveFns.push(resolve));
    global.fetch = vi.fn(() => pendente) as unknown as typeof fetch;

    render(
      <AuthProvider tokenQuery={null}>
        <GuardVereador>
          <div data-testid="conteudo">home do vereador</div>
        </GuardVereador>
      </AuthProvider>
    );

    expect(screen.queryByTestId("conteudo")).toBeNull();
    expect(screen.queryByText("Acesso restrito")).toBeNull();
    // limpa a promise pendente pra não vazar entre testes
    resolveFns.forEach((resolve) => resolve(resposta));
  });
});
