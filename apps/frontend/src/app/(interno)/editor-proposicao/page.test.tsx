import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";

vi.mock("next/navigation", () => ({ useRouter: () => ({ push: vi.fn() }), usePathname: () => "/editor-proposicao", useSearchParams: () => new URLSearchParams() }));
vi.mock("../topo", () => ({ TopoInterno: () => null }));

import PaginaCriarProposicao from "./page";
import { AuthProvider } from "@/lib/auth";
import { TemaProvider } from "@/lib/tema";

// Item 3 do lote 05/10: o POST de nova proposição leva `autor-id` (UUID do cadastro) junto de `autor-tipo` e
// `autor-texto` quando a autoria é de um vereador — é o que o backend exige na mesma escrita
// (legislativo/controllers.clj `validar-autor!`).

describe("PaginaCriarProposicao — autoria por vereador", () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it("o corpo enviado traz autor-tipo, autor-id e autor-texto do vereador escolhido", async () => {
    const posts: unknown[] = [];
    global.fetch = vi.fn(async (url: string, init?: RequestInit) => {
      if (url === "/api/cadastros/vereadores") {
        return {
          ok: true, status: 200,
          json: async () => ({ vereadores: [
            { id: "11111111-1111-1111-1111-111111111111", nome: "Helena Matos Lima", "nome-parlamentar": "Helena Matos", "estado-mandato": "vigente", "com-acesso": true },
          ] }),
        } as Response;
      }
      if (url === "/api/legislativo/proposicoes" && init?.method === "POST") {
        posts.push(JSON.parse(String(init.body)));
        return { ok: true, status: 201, json: async () => ({ id: "p1" }) } as Response;
      }
      return { ok: false, status: 404, json: async () => ({}) } as Response;
    }) as unknown as typeof fetch;

    render(
      <AuthProvider tokenQuery="tok-de-teste">
        <TemaProvider>
          <PaginaCriarProposicao />
        </TemaProvider>
      </AuthProvider>,
    );

    fireEvent.change(screen.getByLabelText(/ementa/i), { target: { value: "Dispõe sobre hortas comunitárias" } });
    fireEvent.change(screen.getByLabelText("Autor"), { target: { value: "vereador" } });
    const select = (await screen.findByLabelText("Vereador autor")) as HTMLSelectElement;
    await waitFor(() => expect(select.options.length).toBeGreaterThan(1));
    fireEvent.change(select, { target: { value: "11111111-1111-1111-1111-111111111111" } });
    fireEvent.click(screen.getByRole("button", { name: "Protocolar" }));

    await waitFor(() => expect(posts).toHaveLength(1));
    expect(posts[0]).toMatchObject({
      "autor-tipo": "vereador",
      "autor-id": "11111111-1111-1111-1111-111111111111",
      "autor-texto": "Helena Matos",
    });
  });
});
