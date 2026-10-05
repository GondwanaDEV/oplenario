import { Suspense } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
vi.mock("next/navigation", () => ({ useRouter: () => ({ push: vi.fn() }), usePathname: () => "/editor-proposicao/p1", useSearchParams: () => new URLSearchParams() }));
vi.mock("../../topo", () => ({ TopoInterno: () => null }));

import PaginaEditarProposicao from "./page";
import { AuthProvider } from "@/lib/auth";
import { TemaProvider } from "@/lib/tema";

// Item 3 do lote 05/10: a edição passa o `autorId` da matéria ao formulário, então o vereador que já é o
// autor vem selecionado (e o vínculo com o cadastro segue no envio) em vez de aparecer "sem ligação".

const detalhe = {
  id: "p1", tipo: "projeto_lei", ano: 2026, sequencial: 7, "urn-lex": "urn:x",
  ementa: "Dispõe sobre hortas comunitárias", "autor-tipo": "vereador", "autor-id": "v-helena",
  "autor-texto": "Helena Matos", estado: "protocolada", aprovada: false, "lock-version": 2,
  "atualizado-em": "2026-05-21T10:00:00Z", texto: "## Art. 1º ...",
};

// `use(params)` lê direto uma promessa já cumprida (status/value, o contrato que o React usa), sem suspender.
const paramsResolvido = Object.assign(Promise.resolve({ id: "p1" }), {
  status: "fulfilled" as const,
  value: { id: "p1" },
});

describe("PaginaEditarProposicao — autoria", () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it("o vereador autor ligado ao cadastro vem selecionado", async () => {
    global.fetch = vi.fn(async (url: string) => {
      if (url === "/api/cadastros/vereadores") {
        return {
          ok: true, status: 200,
          json: async () => ({ vereadores: [
            { id: "v-helena", nome: "Helena Matos Lima", "nome-parlamentar": "Helena Matos", "estado-mandato": "vigente", "com-acesso": true },
            { id: "v-caio", nome: "Caio Reis", "estado-mandato": "vigente", "com-acesso": false },
          ] }),
        } as Response;
      }
      if (url.startsWith("/api/legislativo/proposicoes/p1")) {
        return { ok: true, status: 200, json: async () => detalhe } as Response;
      }
      return { ok: false, status: 404, json: async () => ({}) } as Response;
    }) as unknown as typeof fetch;

    render(
      <AuthProvider tokenQuery="tok-de-teste">
        <TemaProvider>
          <Suspense fallback={<p>carregando</p>}>
            <PaginaEditarProposicao params={paramsResolvido} />
          </Suspense>
        </TemaProvider>
      </AuthProvider>,
    );

    const select = (await screen.findByLabelText("Vereador autor")) as HTMLSelectElement;
    await waitFor(() => expect(select.options.length).toBeGreaterThan(1));
    expect(select.value).toBe("v-helena");
    expect(screen.queryByText(/sem ligação com o cadastro/)).toBeNull();
  });
});
