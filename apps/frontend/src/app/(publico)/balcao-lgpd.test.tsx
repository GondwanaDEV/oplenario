import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { BalcaoLgpd } from "./balcao-lgpd";

// Task 2.2 (Fatia A2.2, Portal do Cidadão) — porte de portal-cidadao.html:586-618. Os 5 "direitos" são
// fluxos de escrita autenticados (deferidos, EmBreve); o bloco do Encarregado/DPO é dado REAL
// (useEncarregado) e degrada isolado se o fetch falhar.

describe("BalcaoLgpd", () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it("mostra o eyebrow, título e a fronteira LAI×LGPD", () => {
    global.fetch = vi.fn(async () => ({ ok: false })) as unknown as typeof fetch;
    render(<BalcaoLgpd ente="fortaleza" />);
    expect(screen.getByText("LGPD · Lei Geral de Proteção de Dados").textContent).toBe(
      "LGPD · Lei Geral de Proteção de Dados",
    );
    expect(screen.getByText(/aqui é só sobre os/i)).toBeTruthy();
  });

  it("os 5 direitos aparecem como botões inertes (disabled) + em-breve honesto", () => {
    global.fetch = vi.fn(async () => ({ ok: false })) as unknown as typeof fetch;
    render(<BalcaoLgpd ente="fortaleza" />);
    const botao = screen.getByRole("button", { name: "Acessar meus dados" }) as HTMLButtonElement;
    expect(botao.disabled).toBe(true);
    expect(screen.getByText(/exige identificação formal/i)).toBeTruthy();
  });

  it("não crava um número de dias para o prazo LGPD (sem dado fabricado)", () => {
    global.fetch = vi.fn(async () => ({ ok: false })) as unknown as typeof fetch;
    render(<BalcaoLgpd ente="fortaleza" />);
    expect(screen.getByText(/dentro do prazo legal/i)).toBeTruthy();
  });

  it("carrega e mostra o Encarregado/DPO real", async () => {
    global.fetch = vi.fn(async () => ({
      ok: true,
      json: async () => ({ nome: "Mariana Couto", rotulo: "Encarregada de Dados (DPO)", email: "encarregado.dados@cmfor.ce.gov.br" }),
    })) as unknown as typeof fetch;

    render(<BalcaoLgpd ente="fortaleza" />);
    await waitFor(() => expect(screen.getByText("Mariana Couto").textContent).toBe("Mariana Couto"));
    expect(screen.getByRole("link", { name: "encarregado.dados@cmfor.ce.gov.br" }).getAttribute("href")).toBe(
      "mailto:encarregado.dados@cmfor.ce.gov.br",
    );
  });

  it("fetch do encarregado falho -> degrada isolado (em-breve local), não derruba o balcão", async () => {
    global.fetch = vi.fn(async () => ({ ok: false, status: 500 })) as unknown as typeof fetch;
    render(<BalcaoLgpd ente="fortaleza" />);
    await waitFor(() => expect(screen.getByText(/não foi possível carregar o contato/i)).toBeTruthy());
    expect(screen.getByText("Os seus dados pessoais")).toBeTruthy();
  });
});
