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

  it("a aba 'Avisos' da tabbar aponta para /notificacoes (Onda E fatia 1 — gap declarado na Task 11)", () => {
    // Sem esta asserção, trocar o href de "Avisos" no array TABS não deixa nada vermelho.
    render(
      <AuthProvider tokenQuery='{"sub":"u","papeis":["vereador"]}'>
        <TemaProvider>
          <GuardVereador>
            <div data-testid="conteudo">home do vereador</div>
          </GuardVereador>
        </TemaProvider>
      </AuthProvider>
    );
    const link = screen.getByText("Avisos").closest("a");
    expect(link).not.toBeNull();
    expect(link?.getAttribute("href")).toMatch(/^\/notificacoes/);
  });
});

describe("GuardVereador — a aba Avisos é a caixa (ADR-0020)", () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it("leva o número do que está por ler (comunicados + avisos), com a frase para leitor de tela", async () => {
    global.fetch = vi.fn(async (url: string) => {
      if (url === "/api/meu/comunicados/contagem") {
        return { ok: true, json: async () => ({ itens: [], "nao-lidos": 1, "pendentes-ciencia": 0, "proxima-ciencia-ate": null }) } as Response;
      }
      if (url === "/api/meu/notificacoes") {
        return { ok: true, json: async () => ({ notificacoes: [], "nao-lidas": 1, "notificacoes-total": 0 }) } as Response;
      }
      return { ok: false, status: 404, json: async () => ({}) } as Response;
    }) as unknown as typeof fetch;
    render(
      <AuthProvider tokenQuery='{"sub":"u","papeis":["vereador"]}'>
        <TemaProvider>
          <GuardVereador>
            <div data-testid="conteudo">home do vereador</div>
          </GuardVereador>
        </TemaProvider>
      </AuthProvider>
    );
    const aba = await screen.findByRole("link", { name: "Avisos, 2 por ler" });
    expect(aba.querySelector(".tab-contagem")?.textContent).toBe("2");
  });
});

describe("GuardVereador — aba Perfil", () => {
  afterEach(cleanup);

  it("a aba 'Perfil' aponta para a Minha atuação (/vereador/atuacao)", () => {
    render(
      <AuthProvider tokenQuery='{"sub":"u","papeis":["vereador"]}'>
        <TemaProvider>
          <GuardVereador>
            <div data-testid="conteudo">home do vereador</div>
          </GuardVereador>
        </TemaProvider>
      </AuthProvider>
    );
    const link = screen.getByText("Perfil").closest("a");
    expect(link).not.toBeNull();
    expect(link?.getAttribute("href")).toMatch(/^\/vereador\/atuacao/);
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
