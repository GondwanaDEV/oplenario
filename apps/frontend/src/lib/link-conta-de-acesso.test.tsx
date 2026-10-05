import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { LinkTrocarEmail } from "./link-conta-de-acesso";

// O link que leva a pessoa à página de conta do Keycloak da Casa (trocar o PRÓPRIO e-mail de acesso). Aparece só
// onde a conta existe: sessão real (cookie) de quem entrou pelo login da Casa. Em modo dev (token de dev) não há
// Keycloak; a sessão do gov.br é a de 'cidadao' e a conta dela é a do gov.br, não a do realm.

function eu(ator: Record<string, unknown>) {
  global.fetch = vi.fn(async () => ({ ok: true, json: async () => ({ ator }) }) as Response) as unknown as typeof fetch;
}

describe("LinkTrocarEmail", () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    vi.unstubAllEnvs();
  });

  it("modo real, servidor da Casa: o link abre a página de conta em nova aba, dizendo que é em inglês", async () => {
    vi.stubEnv("NEXT_PUBLIC_APP_ENV", "production");
    eu({ papeis: ["secretario"], "tipo-vinculo": "servidor" });
    render(<LinkTrocarEmail token={null} />);
    const link = await screen.findByRole("link", { name: /Trocar meu e-mail de acesso/ });
    expect(link.getAttribute("href")).toBe("/api/auth/conta");
    expect(link.getAttribute("target")).toBe("_blank");
    expect(link.getAttribute("rel")).toBe("noopener noreferrer");
    expect(link.textContent).toMatch(/abre a página de conta, em inglês/);
  });

  it("modo dev (token de dev, sem Keycloak): o link NÃO aparece e nada é buscado", async () => {
    global.fetch = vi.fn() as unknown as typeof fetch;
    render(<LinkTrocarEmail token="dev-token" />);
    expect(screen.queryByRole("link")).toBeNull();
    expect(global.fetch).not.toHaveBeenCalled();
  });

  it("sessão do gov.br (vínculo de cidadão): o link NÃO aparece", async () => {
    vi.stubEnv("NEXT_PUBLIC_APP_ENV", "production");
    eu({ papeis: [], "tipo-vinculo": "cidadao" });
    render(<LinkTrocarEmail token={null} />);
    await waitFor(() => expect(global.fetch).toHaveBeenCalled());
    await new Promise((r) => setTimeout(r, 20));
    expect(screen.queryByRole("link")).toBeNull();
  });

  it("sem saber o tipo do vínculo (resposta sem o campo) ou com erro: o link NÃO aparece (fail-closed)", async () => {
    vi.stubEnv("NEXT_PUBLIC_APP_ENV", "production");
    eu({ papeis: ["secretario"] });
    render(<LinkTrocarEmail token={null} />);
    await waitFor(() => expect(global.fetch).toHaveBeenCalled());
    await new Promise((r) => setTimeout(r, 20));
    expect(screen.queryByRole("link")).toBeNull();
    cleanup();

    global.fetch = vi.fn(async () => ({ ok: false, status: 500 }) as Response) as unknown as typeof fetch;
    render(<LinkTrocarEmail token={null} />);
    await new Promise((r) => setTimeout(r, 20));
    expect(screen.queryByRole("link")).toBeNull();
  });

  it("enquanto /eu não respondeu, o link não aparece", () => {
    vi.stubEnv("NEXT_PUBLIC_APP_ENV", "production");
    global.fetch = vi.fn(() => new Promise(() => {})) as unknown as typeof fetch;
    render(<LinkTrocarEmail token={null} />);
    expect(screen.queryByRole("link")).toBeNull();
  });
});
