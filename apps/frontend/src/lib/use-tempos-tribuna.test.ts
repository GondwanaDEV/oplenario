import { afterEach, describe, expect, it, vi } from "vitest";
import { act, renderHook, waitFor } from "@testing-library/react";
import { useTemposTribuna } from "./use-tempos-tribuna";

const ok = (json: unknown, status = 200) => ({ ok: true, status, json: async () => json }) as Response;
const fail = (status: number, json: unknown = {}) => ({ ok: false, status, json: async () => json }) as Response;

type Chamada = { url: string; init?: RequestInit };

function mockFetch(respostas: (c: Chamada) => Response) {
  const chamadas: Chamada[] = [];
  global.fetch = vi.fn(async (url: string, init?: RequestInit) => {
    chamadas.push({ url, init });
    return respostas({ url, init });
  }) as unknown as typeof fetch;
  return chamadas;
}

const tabela = {
  itens: [{ fase: null, "tipo-fala": "principal", segundos: 180, "referencia-normativa": "RI art. 98" }],
};

describe("useTemposTribuna", () => {
  afterEach(() => vi.restoreAllMocks());

  it("carrega a tabela da Casa (camelizada)", async () => {
    mockFetch(() => ok(tabela));
    const { result } = renderHook(() => useTemposTribuna("tok"));
    await waitFor(() => expect(result.current.estado).toBe("pronto"));
    expect(result.current.itens).toEqual([
      { fase: null, tipoFala: "principal", segundos: 180, referenciaNormativa: "RI art. 98" },
    ]);
  });

  it("salvar: PUT com a tabela inteira em kebab; o que volta substitui a lista", async () => {
    const chamadas = mockFetch((c) =>
      c.init?.method === "PUT"
        ? ok({ itens: [{ fase: "expediente", "tipo-fala": "aparte", segundos: 30, "referencia-normativa": null }] })
        : ok(tabela),
    );
    const { result } = renderHook(() => useTemposTribuna("tok"));
    await waitFor(() => expect(result.current.estado).toBe("pronto"));
    let r: unknown;
    await act(async () => {
      r = await result.current.salvar([
        { fase: "expediente", tipoFala: "aparte", segundos: 30, referenciaNormativa: null },
      ]);
    });
    expect(r).toEqual({ ok: true });
    const put = chamadas.find((c) => c.init?.method === "PUT")!;
    expect(put.url).toMatch(/\/api\/tempos-regimentais$/);
    expect(JSON.parse(put.init!.body as string)).toEqual({
      itens: [{ fase: "expediente", "tipo-fala": "aparte", segundos: 30, "referencia-normativa": null }],
    });
    expect(result.current.itens).toEqual([
      { fase: "expediente", tipoFala: "aparte", segundos: 30, referenciaNormativa: null },
    ]);
  });

  it("erro ao salvar devolve a mensagem e mantém a tabela anterior", async () => {
    mockFetch((c) => (c.init?.method === "PUT" ? fail(400, { erro: "requisicao invalida" }) : ok(tabela)));
    const { result } = renderHook(() => useTemposTribuna("tok"));
    await waitFor(() => expect(result.current.estado).toBe("pronto"));
    let r: unknown;
    await act(async () => {
      r = await result.current.salvar([]);
    });
    expect(r).toEqual({ ok: false, erro: expect.stringContaining("Não foi possível salvar") });
    expect(result.current.itens).toHaveLength(1);
  });

  it("sem permissão → estado de erro na leitura", async () => {
    mockFetch(() => fail(403));
    const { result } = renderHook(() => useTemposTribuna("tok"));
    await waitFor(() => expect(result.current.estado).toBe("erro"));
  });
});
