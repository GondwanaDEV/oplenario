import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

// ADR-0025 — as telas da entrada pelo CPF. Server Components: chamados como função e o JSX renderizado.

const cookieStore = { valor: undefined as string | undefined };
vi.mock("next/headers", () => ({
  cookies: async () => ({ get: (nome: string) => (nome === "entrar_escolha" && cookieStore.valor ? { value: cookieStore.valor } : undefined) }),
}));
const redirecionou = vi.fn((url: string) => {
  throw new Error(`redirect:${url}`);
});
vi.mock("next/navigation", () => ({ redirect: (url: string) => redirecionou(url) }));

import PaginaEntrar from "./page";
import PaginaEntrarComEnte from "./[ente]/page";
import PaginaEscolherCamara from "./escolher/page";

afterEach(cleanup);

const A = "10000000-0000-0000-0000-000000000001";
const B = "20000000-0000-0000-0000-000000000002";

describe("/entrar — a porta de servidores e vereadores", () => {
  it("pede o CPF num formulário que posta para o BFF (o CPF nunca vai na URL)", async () => {
    const { container } = render(await PaginaEntrar({ searchParams: Promise.resolve({}) }));
    const form = container.querySelector("form")!;
    expect(form.getAttribute("method")).toBe("post");
    expect(form.getAttribute("action")).toBe("/api/auth/entrar");
    expect(screen.getByLabelText("CPF").getAttribute("name")).toBe("cpf");
    expect(screen.getByRole("button", { name: "Continuar" })).toBeTruthy();
    expect(container.textContent).not.toMatch(/URL da sua Câmara/);
  });

  it("leva o destino pedido pelo middleware escondido no formulário", async () => {
    const { container } = render(await PaginaEntrar({ searchParams: Promise.resolve({ redirect: "/tramitacao" }) }));
    expect(container.querySelector('input[name="redirect"]')?.getAttribute("value")).toBe("/tramitacao");
  });

  it("mostra o motivo quando volta com erro", async () => {
    render(await PaginaEntrar({ searchParams: Promise.resolve({ erro: "sem-acesso" }) }));
    expect(screen.getByRole("alert").textContent).toMatch(/Não encontramos acesso/);
  });
});

describe("/entrar/[ente] — o link que a Câmara divulga", () => {
  beforeEach(() => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => new Response(JSON.stringify({ "ente-id": A, "nome-curto": "Câmara de Fortaleza" }), { status: 200 })),
    );
  });
  afterEach(() => vi.unstubAllGlobals());

  it("pede o CPF desta Câmara: o ente vai escondido no formulário", async () => {
    const { container } = render(
      await PaginaEntrarComEnte({ params: Promise.resolve({ ente: A }), searchParams: Promise.resolve({}) }),
    );
    expect(screen.getByRole("heading", { level: 1 }).textContent).toBe("Entrar em Câmara de Fortaleza");
    expect(container.querySelector('input[name="ente"]')?.getAttribute("value")).toBe(A);
    expect(screen.getByLabelText("CPF")).toBeTruthy();
  });

  it("CPF sem acesso a esta Câmara volta com a explicação", async () => {
    render(
      await PaginaEntrarComEnte({ params: Promise.resolve({ ente: A }), searchParams: Promise.resolve({ erro: "sem-acesso-nesta" }) }),
    );
    expect(screen.getByRole("alert").textContent).toMatch(/não tem acesso a esta Câmara/);
  });
});

describe("/entrar/escolher — quem tem acesso a mais de uma Câmara", () => {
  afterEach(() => {
    cookieStore.valor = undefined;
    redirecionou.mockClear();
  });

  it("lista as Câmaras do cookie, cada uma levando ao login dela", async () => {
    cookieStore.valor = JSON.stringify({
      hint: "0eabd6df-d0cc-40bb-a0ca-043027cb3a1f",
      redirectPath: null,
      casas: [
        { enteId: A, nome: "Câmara Municipal de Baturité" },
        { enteId: B, nome: "Câmara Municipal de Russas" },
      ],
    });
    render(await PaginaEscolherCamara());
    expect(screen.getByRole("link", { name: /Baturité/ }).getAttribute("href")).toBe(`/api/auth/login?ente=${A}`);
    expect(screen.getByRole("link", { name: /Russas/ }).getAttribute("href")).toBe(`/api/auth/login?ente=${B}`);
    expect(document.body.textContent).not.toContain("0eabd6df");
  });

  it("sem cookie (venceu ou abriram a URL direto): volta para digitar o CPF", async () => {
    await expect(PaginaEscolherCamara()).rejects.toThrow("redirect:/entrar?erro=escolha");
  });
});
