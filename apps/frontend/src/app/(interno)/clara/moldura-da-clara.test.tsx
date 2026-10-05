import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";

const estado = vi.hoisted(() => ({ papeis: [] as string[], fase: "pronto" as "carregando" | "pronto" | "erro", caminho: "/inicio" }));

vi.mock("@/lib/auth", () => ({
  useAuth: () => ({ token: "tok", papeis: estado.papeis }),
  usePapeis: () => ({ papeis: estado.papeis, estado: estado.fase }),
}));
vi.mock("next/navigation", () => ({ usePathname: () => estado.caminho }));

import { MolduraDaClara } from "./moldura-da-clara";

const fetchMock = vi.fn(async () => ({ ok: true, status: 200, text: async () => "", json: async () => ({}) }) as Response);

function montar() {
  render(
    <MolduraDaClara>
      <main>a página</main>
    </MolduraDaClara>,
  );
}

describe("MolduraDaClara", () => {
  afterEach(() => {
    cleanup();
    delete document.documentElement.dataset.clara;
  });

  it("a secretaria e o vereador têm a Clara em toda tela interna", () => {
    for (const papeis of [["secretario"], ["vereador"], ["secretario", "admin_ente"]]) {
      Object.assign(estado, { papeis, fase: "pronto", caminho: "/proposicoes" });
      montar();
      expect(screen.getByRole("button", { name: /Pergunte à Clara/ })).toBeTruthy();
      expect(document.querySelector(".clara-moldura")?.hasAttribute("data-clara-ativa")).toBe(true);
      cleanup();
    }
  });

  it("quem não pergunta (só administra, audita ou dá parecer) não vê a Clara; a página continua na moldura", () => {
    for (const papeis of [["admin_ente"], ["auditor"], ["juridico"], []]) {
      Object.assign(estado, { papeis, fase: "pronto", caminho: "/caixa" });
      montar();
      expect(screen.queryByRole("button", { name: /Pergunte à Clara/ })).toBeNull();
      expect(screen.getByText("a página").closest(".clara-moldura")?.hasAttribute("data-clara-ativa")).toBe(false);
      cleanup();
    }
  });

  it("enquanto os papéis carregam, e na tela cheia do assistente, nada de Clara", () => {
    Object.assign(estado, { papeis: ["secretario"], fase: "carregando", caminho: "/inicio" });
    montar();
    expect(screen.queryByRole("button", { name: /Pergunte à Clara/ })).toBeNull();
    cleanup();
    Object.assign(estado, { papeis: ["secretario"], fase: "pronto", caminho: "/assistente" });
    montar();
    expect(screen.queryByRole("button", { name: /Pergunte à Clara/ })).toBeNull();
  });

  it("no app do vereador: pergunta com o conjunto do vereador mesmo para quem também é secretaria; fora da tela cheia", async () => {
    global.fetch = fetchMock as unknown as typeof fetch;
    Object.assign(estado, { papeis: ["secretario", "vereador"], fase: "pronto", caminho: "/vereador" });
    render(
      <MolduraDaClara publico="vereador">
        <main>o app</main>
      </MolduraDaClara>,
    );
    screen.getByRole("button", { name: /Pergunte à Clara/ }).click();
    const campo = await screen.findByLabelText("Sua pergunta");
    const { fireEvent } = await import("@testing-library/react");
    fireEvent.change(campo, { target: { value: "oi, tudo bem?" } });
    fireEvent.click(screen.getByRole("button", { name: "Perguntar" }));
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalled());
    const [, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect(JSON.parse(init.body as string)).toEqual({ pergunta: "oi, tudo bem?", publico: "vereador" });
    cleanup();
    Object.assign(estado, { caminho: "/vereador/assistente" });
    render(
      <MolduraDaClara publico="vereador">
        <main>o app</main>
      </MolduraDaClara>,
    );
    expect(screen.queryByRole("button", { name: /Pergunte à Clara/ })).toBeNull();
  });
});
