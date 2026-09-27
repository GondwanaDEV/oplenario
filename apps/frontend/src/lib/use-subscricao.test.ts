import { afterEach, describe, expect, it, vi } from "vitest";
import { act, renderHook, waitFor } from "@testing-library/react";
import { useColegas, usePropostaRequerimento, useSubscricoesHome } from "./use-subscricao";

type Chamada = { url: string; init?: RequestInit };
const resp = (status: number, json: unknown) => ({ ok: status < 300, status, json: async () => json }) as Response;

function mockFetch(f: (c: Chamada) => Response) {
  const chamadas: Chamada[] = [];
  global.fetch = vi.fn(async (url: string, init?: RequestInit) => {
    chamadas.push({ url, init });
    return f({ url, init });
  }) as unknown as typeof fetch;
  return chamadas;
}

const proposta = {
  id: "p-1", ementa: "E", "tipo-requerimento": "T", texto: "X", "autor-nome": "Ana", estado: "aguardando_subscricoes",
  "proposicao-id": null, "criada-em": "2026-09-26T12:00:00Z", "sou-autor": false, "minha-subscricao": "pendente",
  subscricoes: [{ "vereador-nome": "Bia", estado: "pendente", "respondida-em": null }],
};

describe("use-subscricao", () => {
  afterEach(() => vi.restoreAllMocks());

  it("useColegas lê /meu/colegas", async () => {
    mockFetch(() => resp(200, { itens: [{ id: "v1", nome: "Bia", partido: null }] }));
    const { result } = renderHook(() => useColegas("tok"));
    await waitFor(() => expect(result.current.estado).toBe("pronto"));
    expect(result.current.colegas).toEqual([{ id: "v1", nome: "Bia", partido: null }]);
  });

  it("useSubscricoesHome junta convites e propostas; erro vira lista vazia", async () => {
    mockFetch((c) =>
      c.url.endsWith("/meu/subscricoes")
        ? resp(200, { itens: [{ "proposta-id": "p-1", ementa: "E", "tipo-requerimento": "T", "autor-nome": "Ana", "convidada-em": "x" }] })
        : resp(500, {}),
    );
    const { result } = renderHook(() => useSubscricoesHome("tok"));
    await waitFor(() => expect(result.current.convites).toHaveLength(1));
    expect(result.current.convites[0].propostaId).toBe("p-1");
    expect(result.current.propostas).toEqual([]);
  });

  it("responder: POST {acao} e relê a proposta", async () => {
    const chamadas = mockFetch((c) => (c.init?.method === "POST" ? resp(200, { estado: "confirmada" }) : resp(200, proposta)));
    const { result } = renderHook(() => usePropostaRequerimento("tok", "p-1"));
    await waitFor(() => expect(result.current.estado).toBe("pronto"));
    let r: unknown;
    await act(async () => {
      r = await result.current.responder("confirmar");
    });
    expect(r).toEqual({ ok: true, dados: { estado: "confirmada" } });
    const post = chamadas.find((c) => c.init?.method === "POST")!;
    expect(post.url).toMatch(/\/api\/meu\/requerimentos\/propostas\/p-1\/resposta$/);
    expect(JSON.parse(post.init!.body as string)).toEqual({ acao: "confirmar" });
    expect(chamadas.filter((c) => !c.init?.method).length).toBe(2);
  });

  it("protocolar: 409 devolve a mensagem do servidor e relê", async () => {
    const chamadas = mockFetch((c) =>
      c.init?.method === "POST" ? resp(409, { motivo: "proposta-protocolada", erro: "ja foi protocolado" }) : resp(200, proposta),
    );
    const { result } = renderHook(() => usePropostaRequerimento("tok", "p-1"));
    await waitFor(() => expect(result.current.estado).toBe("pronto"));
    let r: unknown;
    await act(async () => {
      r = await result.current.protocolar();
    });
    expect(r).toEqual({ ok: false, erro: "ja foi protocolado" });
    expect(chamadas.find((c) => c.init?.method === "POST")!.url).toMatch(/\/protocolo$/);
    expect(chamadas.filter((c) => !c.init?.method).length).toBe(2);
  });
});
