import { describe, expect, it, vi, afterEach } from "vitest";
import { renderHook, waitFor, act } from "@testing-library/react";
import { useSessoes } from "./use-sessoes";

// Teste do hook do defeito #16 (MATA): GET /api/sessoes. Mirror de use-meu-painel.test.ts na disciplina de
// fixture (corpo CRU em kebab-case — `camelizarChaves` roda dentro do hook) e no formato dos casos. O caso
// que este arquivo existe pra travar: `estado` tem de ser "erro" (nunca "pronto" com lista vazia) quando o
// fetch falha — é exatamente a distinção que faltava para a home parar de tratar "não sei" como "não há".

const sessoesFake = {
  sessoes: [
    {
      id: "s1", "sessao-legislativa-id": "sl1", "tipo-sessao": "ordinaria", "numero-sequencial": 1,
      estado: "aberta", modalidade: "presencial", delibera: true, "transmite-publica": true,
      "gera-ata-regimental": true, "permite-voto-secreto": false, "permite-modalidade-remota": false,
      "agendada-para": null, "aberta-em": "2026-09-07T10:00:00Z", "encerrada-em": null,
      "motivo-nao-realizada": null,
    },
  ],
};

describe("useSessoes", () => {
  afterEach(() => vi.restoreAllMocks());

  it("busca /api/sessoes e cameliza", async () => {
    global.fetch = vi.fn(async () => ({ ok: true, json: async () => sessoesFake }) as Response) as unknown as typeof fetch;
    const { result } = renderHook(() => useSessoes("tok"));
    expect(result.current.estado).toBe("carregando");
    await waitFor(() => expect(result.current.estado).toBe("pronto"));
    expect(result.current.sessoes?.[0].tipoSessao).toBe("ordinaria");
    expect(result.current.sessoes?.[0].estado).toBe("aberta");
    const chamada = vi.mocked(global.fetch).mock.calls[0];
    expect(chamada[0]).toBe("/api/sessoes");
    expect(new Headers(chamada[1]?.headers).get("Authorization")).toBe("Bearer tok");
  });

  it("sem token -> 'erro' sem chamar fetch", () => {
    global.fetch = vi.fn() as unknown as typeof fetch;
    const { result } = renderHook(() => useSessoes(null));
    expect(result.current.estado).toBe("erro");
    expect(global.fetch).not.toHaveBeenCalled();
  });

  // A reprova que importa: 422 (teto de linhas) ou qualquer outro !ok NÃO pode virar `sessoes: []` com
  // `estado: "pronto"` — isso é o que faria a home voltar a confundir "a listagem falhou" com "não há
  // sessão". Uma implementação errada que fizesse `catch { setSessoes([]); setEstado("pronto") }` reprova
  // aqui.
  it("resposta não-ok (422 teto de linhas) -> estado 'erro', nunca lista vazia 'pronta'", async () => {
    global.fetch = vi.fn(async () => ({ ok: false, status: 422 }) as Response) as unknown as typeof fetch;
    const { result } = renderHook(() => useSessoes("tok"));
    await waitFor(() => expect(result.current.estado).toBe("erro"));
    expect(result.current.sessoes).toBeNull();
  });

  it("falha de rede (fetch rejeita) -> estado 'erro'", async () => {
    global.fetch = vi.fn(async () => {
      throw new Error("offline");
    }) as unknown as typeof fetch;
    const { result } = renderHook(() => useSessoes("tok"));
    await waitFor(() => expect(result.current.estado).toBe("erro"));
  });

  it("recarregar() refaz o GET e substitui os dados", async () => {
    global.fetch = vi.fn(async () => ({ ok: true, json: async () => sessoesFake }) as Response) as unknown as typeof fetch;
    const { result } = renderHook(() => useSessoes("tok"));
    await waitFor(() => expect(result.current.estado).toBe("pronto"));

    global.fetch = vi.fn(async () => ({ ok: true, json: async () => ({ sessoes: [] }) }) as Response) as unknown as typeof fetch;
    await act(async () => {
      await result.current.recarregar();
    });
    expect(global.fetch).toHaveBeenCalledWith("/api/sessoes", expect.anything());
    expect(result.current.estado).toBe("pronto");
    expect(result.current.sessoes).toEqual([]);
  });
});
