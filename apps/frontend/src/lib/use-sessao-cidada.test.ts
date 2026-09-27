import { afterEach, describe, expect, it, vi } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { useSessaoCidada } from "./use-sessao-cidada";

const ENTE = "10000000-0000-0000-0000-000000000001";
const TOKEN = JSON.stringify({ "identidade-id": "i1", "ente-id": ENTE });

function naUrl(query: string) {
  window.history.pushState({}, "", `/portal/casa/${ENTE}${query}`);
}

describe("useSessaoCidada — quem está logado no portal (formulários do cidadão)", () => {
  afterEach(() => {
    vi.restoreAllMocks();
    naUrl("");
  });

  it("sem credencial (dev sem ?token=) é anônima e nem pergunta ao backend", async () => {
    global.fetch = vi.fn() as unknown as typeof fetch;
    const { result } = renderHook(() => useSessaoCidada(ENTE));
    await waitFor(() => expect(result.current.estado).toBe("anonima"));
    expect(global.fetch).not.toHaveBeenCalled();
  });

  it("logada nesta Casa: 'cidada', com o token de dev para as escritas", async () => {
    naUrl(`?token=${encodeURIComponent(TOKEN)}`);
    global.fetch = vi.fn(async () =>
      ({ ok: true, status: 200, json: async () => ({ ator: { "ente-id": ENTE, "tipo-vinculo": "cidadao", papeis: [] } }) }) as Response,
    ) as unknown as typeof fetch;
    const { result } = renderHook(() => useSessaoCidada(ENTE));
    await waitFor(() => expect(result.current.estado).toBe("cidada"));
    expect(result.current.token).toBe(TOKEN);
    expect(vi.mocked(global.fetch).mock.calls[0][0]).toBe("/api/eu");
  });

  it("logada em OUTRA Casa: não escreve aqui (o backend gravaria na Casa da sessão)", async () => {
    naUrl(`?token=${encodeURIComponent(TOKEN)}`);
    global.fetch = vi.fn(async () =>
      ({ ok: true, status: 200, json: async () => ({ ator: { "ente-id": "outra", papeis: [] } }) }) as Response,
    ) as unknown as typeof fetch;
    const { result } = renderHook(() => useSessaoCidada(ENTE));
    await waitFor(() => expect(result.current.estado).toBe("outra-casa"));
  });

  it("401 é anônima; outra falha é erro", async () => {
    naUrl(`?token=${encodeURIComponent(TOKEN)}`);
    global.fetch = vi.fn(async () => ({ ok: false, status: 401, json: async () => ({}) }) as Response) as unknown as typeof fetch;
    const { result } = renderHook(() => useSessaoCidada(ENTE));
    await waitFor(() => expect(result.current.estado).toBe("anonima"));

    global.fetch = vi.fn(async () => ({ ok: false, status: 500, json: async () => ({}) }) as Response) as unknown as typeof fetch;
    const r2 = renderHook(() => useSessaoCidada(ENTE));
    await waitFor(() => expect(r2.result.current.estado).toBe("erro"));
  });
});
