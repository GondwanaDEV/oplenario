import { afterEach, describe, expect, it, vi } from "vitest";
import { act, renderHook, waitFor } from "@testing-library/react";
import { useAtosMesa } from "./use-atos-mesa";

// docs/23 Fatia 2 — IO dos atos da Mesa: lê atos + composição, escreve decisão e incidente e recarrega.

function json(status: number, body: unknown): Response {
  return { ok: status >= 200 && status < 300, status, json: async () => body } as Response;
}

const ATOS = {
  "sessao-id": "s1",
  decisoes: [{ id: "d1", "presidente-id": "v1", questao: "Q", decisao: "D", "decidido-em": "2026-09-24T14:00:00Z" }],
  incidentes: [],
};
const COMPOSICAO = {
  "sessao-id": "s1", "sessao-estado": "aberta", "data-de-composicao": "2026-09-24", "composicao-resolvida-em": "x",
  membros: [{ "vereador-id": "v1", "nome-parlamentar": "Bruno Lima", "cargo-mesa": "Presidente" }],
};

function servidor(escrita: Response = json(201, { id: "novo" })) {
  const chamadas: { url: string; metodo: string; corpo: unknown }[] = [];
  global.fetch = vi.fn(async (input: string, init?: RequestInit) => {
    const url = String(input);
    const metodo = init?.method ?? "GET";
    chamadas.push({ url, metodo, corpo: init?.body ? JSON.parse(String(init.body)) : null });
    if (metodo === "POST") return escrita;
    if (url.endsWith("/atos-mesa")) return json(200, ATOS);
    if (url.endsWith("/composicao")) return json(200, COMPOSICAO);
    if (url.endsWith("/tribuna")) {
      return json(200, { "sessao-id": "s1", "orador-atual": { "fala-id": "f9", "orador-id": "v1" }, "marcos-cronometro": [], inscritos: [] });
    }
    return json(404, {});
  }) as unknown as typeof fetch;
  return chamadas;
}

describe("useAtosMesa", () => {
  afterEach(() => vi.restoreAllMocks());

  it("carrega os atos (camelizados) e a composição", async () => {
    servidor();
    const { result } = renderHook(() => useAtosMesa("s1", "tok"));
    await waitFor(() => expect(result.current.estado).toBe("pronto"));
    expect(result.current.atos?.decisoes[0].presidenteId).toBe("v1");
    await waitFor(() => expect(result.current.membros[0]?.nomeParlamentar).toBe("Bruno Lima"));
  });

  it("registrarDecisao: POST com presidente-id, decidido-em, fundamentação e fala só quando presentes; recarrega", async () => {
    const chamadas = servidor();
    const { result } = renderHook(() => useAtosMesa("s1", "tok"));
    await waitFor(() => expect(result.current.estado).toBe("pronto"));
    let r;
    await act(async () => {
      r = await result.current.registrarDecisao({ questao: " Cabe aparte? ", decisao: "Indeferida ", presidenteId: "v1", fundamentacao: "  ", falaId: "f9" });
    });
    expect(r).toEqual({ ok: true });
    const post = chamadas.find((c) => c.metodo === "POST")!;
    expect(post.url).toBe("/api/sessoes/s1/decisoes-mesa");
    expect(post.corpo).toMatchObject({ questao: "Cabe aparte?", decisao: "Indeferida", "presidente-id": "v1", "fala-id": "f9" });
    expect(post.corpo).not.toHaveProperty("fundamentacao");
    expect(Number.isNaN(Date.parse((post.corpo as Record<string, string>)["decidido-em"]))).toBe(false);
    expect(chamadas.filter((c) => c.url.endsWith("/atos-mesa")).length).toBe(2);
  });

  it("registrarIncidente: matéria vira objeto-tipo/objeto-id; requerente opcional", async () => {
    const chamadas = servidor();
    const { result } = renderHook(() => useAtosMesa("s1", "tok"));
    await waitFor(() => expect(result.current.estado).toBe("pronto"));
    await act(async () => {
      await result.current.registrarIncidente({ tipo: "pedido_vista", resultado: "deferido", descricao: "Vista", proposicaoId: "p22", requerenteId: "v1" });
    });
    const post = chamadas.find((c) => c.metodo === "POST")!;
    expect(post.url).toBe("/api/sessoes/s1/incidentes");
    expect(post.corpo).toMatchObject({
      tipo: "pedido_vista", resultado: "deferido", descricao: "Vista",
      "objeto-tipo": "proposicao", "objeto-id": "p22", "requerente-id": "v1",
    });
    expect(post.corpo).not.toHaveProperty("deliberacao");
  });

  it("recusa do servidor (409 presidente fora da composição) volta como mensagem, sem recarregar", async () => {
    const chamadas = servidor(json(409, { erro: "quem presidiu nao compoe a Casa na data da sessao" }));
    const { result } = renderHook(() => useAtosMesa("s1", "tok"));
    await waitFor(() => expect(result.current.estado).toBe("pronto"));
    let r;
    await act(async () => {
      r = await result.current.registrarDecisao({ questao: "Q", decisao: "D", presidenteId: "vx" });
    });
    expect(r).toEqual({ ok: false, erro: "quem presidiu nao compoe a Casa na data da sessao" });
    expect(chamadas.filter((c) => c.url.endsWith("/atos-mesa")).length).toBe(1);
    expect(result.current.enviando).toBe(false);
  });

  it("buscarFalaAtual devolve a fala em curso", async () => {
    servidor();
    const { result } = renderHook(() => useAtosMesa("s1", "tok"));
    let f;
    await act(async () => {
      f = await result.current.buscarFalaAtual();
    });
    expect(f).toEqual({ falaId: "f9", oradorId: "v1" });
  });
});
