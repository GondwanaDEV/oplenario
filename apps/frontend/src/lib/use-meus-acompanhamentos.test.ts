import { describe, expect, it, vi, afterEach } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { useMeusAcompanhamentos } from "./use-meus-acompanhamentos";

const fixture = {
  acompanhamentos: [
    {
      "proposicao-id": "p1", tipo: "projeto_lei", ano: 2026, sequencial: 12, "urn-lex": "urn:lex:1",
      ementa: "Altera a Lei Orgânica quanto à composição da Mesa Diretora.", estado: "em_pauta",
      "seguido-em": "2026-09-01T00:00:00Z", indisponivel: false,
    },
    {
      "proposicao-id": "p2", tipo: null, ano: null, sequencial: null, "urn-lex": null, ementa: null,
      estado: null, "seguido-em": "2026-09-02T00:00:00Z", indisponivel: true,
    },
  ],
  "acompanhamentos-total": 2,
};

describe("useMeusAcompanhamentos", () => {
  afterEach(() => vi.restoreAllMocks());

  it("busca /api/portal/acompanhamentos, cameliza e preserva o par lista+total", async () => {
    global.fetch = vi.fn(async () => ({ ok: true, json: async () => fixture }) as Response) as unknown as typeof fetch;
    const { result } = renderHook(() => useMeusAcompanhamentos("tok"));
    expect(result.current.estado).toBe("carregando");
    await waitFor(() => expect(result.current.estado).toBe("pronto"));
    expect(result.current.dados?.acompanhamentosTotal).toBe(2);
    expect(result.current.dados?.acompanhamentos).toHaveLength(2);
    expect(result.current.dados?.acompanhamentos[0].ementa).toBe("Altera a Lei Orgânica quanto à composição da Mesa Diretora.");
    expect(result.current.dados?.acompanhamentos[1].indisponivel).toBe(true);
    expect(result.current.dados?.acompanhamentos[1].ementa).toBeNull();
    const chamada = vi.mocked(global.fetch).mock.calls[0];
    expect(chamada[0]).toBe("/api/portal/acompanhamentos");
  });

  it("sem token (modo dev) -> 'erro' sem chamar fetch", () => {
    global.fetch = vi.fn() as unknown as typeof fetch;
    const { result } = renderHook(() => useMeusAcompanhamentos(null));
    expect(result.current.estado).toBe("erro");
    expect(global.fetch).not.toHaveBeenCalled();
  });

  it("resposta não-ok -> 'erro'", async () => {
    global.fetch = vi.fn(async () => ({ ok: false, status: 401 }) as Response) as unknown as typeof fetch;
    const { result } = renderHook(() => useMeusAcompanhamentos("tok"));
    await waitFor(() => expect(result.current.estado).toBe("erro"));
    expect(result.current.dados).toBeNull();
  });

  it("corpo sem o total (regressão do par lista+total) -> 'erro', nunca assume 0", async () => {
    global.fetch = vi.fn(
      async () => ({ ok: true, json: async () => ({ acompanhamentos: [] }) }) as Response,
    ) as unknown as typeof fetch;
    const { result } = renderHook(() => useMeusAcompanhamentos("tok"));
    await waitFor(() => expect(result.current.estado).toBe("erro"));
  });
});
