import { describe, expect, it, vi, afterEach } from "vitest";
import { renderHook, act, waitFor } from "@testing-library/react";
import { useTribunaMesa } from "./use-tribuna-mesa";

const ok = (json: unknown) => ({ ok: true, status: 200, json: async () => json }) as Response;
const fail = (status: number, json: unknown = {}) => ({ ok: false, status, json: async () => json }) as Response;

const tribunaJson = (inscritos: unknown[]) => ({
  "sessao-id": "s1",
  "orador-atual": null,
  "marcos-cronometro": [],
  inscritos,
});
const composicaoJson = {
  "sessao-id": "s1",
  "sessao-estado": "aberta",
  "data-de-composicao": "2026-05-21",
  "composicao-resolvida-em": "2026-05-21T13:00:00Z",
  membros: [{ "vereador-id": "v1", "nome-parlamentar": "Ana Prado", "cargo-mesa": null }],
};
const inscritoJson = (over: Record<string, unknown> = {}) => ({
  "inscricao-id": "i1", "vereador-id": "v1", "origem-inscricao": "intra_sessao_pedido",
  fase: "expediente", ordem: 1, "lock-version": 2, ...over,
});

function roteador(h: {
  tribuna: () => Response;
  composicao?: () => Response;
  inscrever?: (body: Record<string, unknown>) => Response;
  desistir?: (body: Record<string, unknown>) => Response;
}) {
  return vi.fn(async (url: string, init?: RequestInit) => {
    const metodo = (init?.method ?? "GET").toUpperCase();
    const body = init?.body ? (JSON.parse(init.body as string) as Record<string, unknown>) : {};
    if (metodo === "POST" && /\/desistir$/.test(url)) return h.desistir!(body);
    if (metodo === "POST" && /\/inscricoes$/.test(url)) return h.inscrever!(body);
    if (/\/tribuna$/.test(url)) return h.tribuna();
    if (/\/composicao$/.test(url)) return (h.composicao ?? (() => ok(composicaoJson)))();
    throw new Error(`rota inesperada: ${metodo} ${url}`);
  }) as unknown as typeof fetch;
}

describe("useTribunaMesa", () => {
  afterEach(() => vi.restoreAllMocks());

  it("carrega tribuna + composição camelizadas", async () => {
    global.fetch = roteador({ tribuna: () => ok(tribunaJson([inscritoJson()])) });
    const { result } = renderHook(() => useTribunaMesa("s1", "tok"));
    await waitFor(() => expect(result.current.estado).toBe("pronto"));
    expect(result.current.tribuna?.inscritos[0].vereadorId).toBe("v1");
    await waitFor(() => expect(result.current.composicao?.membros[0].nomeParlamentar).toBe("Ana Prado"));
  });

  it("inscrever POSTa {vereador-id, origem-inscricao, fase} e recarrega", async () => {
    let corpo: Record<string, unknown> = {};
    let inscritos: unknown[] = [];
    global.fetch = roteador({
      tribuna: () => ok(tribunaJson(inscritos)),
      inscrever: (body) => {
        corpo = body;
        inscritos = [inscritoJson()];
        return ok({ id: "i1", ordem: 1 });
      },
    });
    const { result } = renderHook(() => useTribunaMesa("s1", "tok"));
    await waitFor(() => expect(result.current.estado).toBe("pronto"));
    let res;
    await act(async () => {
      res = await result.current.inscrever("v1", "expediente");
    });
    expect((res as { ok: boolean }).ok).toBe(true);
    expect(corpo).toMatchObject({ "vereador-id": "v1", "origem-inscricao": "intra_sessao_pedido", fase: "expediente" });
    await waitFor(() => expect(result.current.tribuna?.inscritos.length).toBe(1));
  });

  it("inscrever sem orador/fase -> erro local, sem tocar a rede", async () => {
    global.fetch = roteador({ tribuna: () => ok(tribunaJson([])) });
    const { result } = renderHook(() => useTribunaMesa("s1", "tok"));
    await waitFor(() => expect(result.current.estado).toBe("pronto"));
    let res;
    await act(async () => {
      res = await result.current.inscrever("", "expediente");
    });
    expect((res as { ok: boolean }).ok).toBe(false);
  });

  it("desistir envia lock-version e recarrega", async () => {
    let corpo: Record<string, unknown> = {};
    let inscritos: unknown[] = [inscritoJson()];
    global.fetch = roteador({
      tribuna: () => ok(tribunaJson(inscritos)),
      desistir: (body) => {
        corpo = body;
        inscritos = [];
        return ok({ "inscricao-id": "i1", de: "inscrita", para: "desistencia" });
      },
    });
    const { result } = renderHook(() => useTribunaMesa("s1", "tok"));
    await waitFor(() => expect(result.current.tribuna?.inscritos.length).toBe(1));
    let res;
    await act(async () => {
      res = await result.current.desistir("i1", 2);
    });
    expect((res as { ok: boolean }).ok).toBe(true);
    expect(corpo).toMatchObject({ "lock-version": 2 });
    await waitFor(() => expect(result.current.tribuna?.inscritos.length).toBe(0));
  });

  it("desistir 409 -> conflito=true", async () => {
    global.fetch = roteador({
      tribuna: () => ok(tribunaJson([inscritoJson()])),
      desistir: () => fail(409, { erro: "lock" }),
    });
    const { result } = renderHook(() => useTribunaMesa("s1", "tok"));
    await waitFor(() => expect(result.current.tribuna?.inscritos.length).toBe(1));
    let res;
    await act(async () => {
      res = await result.current.desistir("i1", 2);
    });
    expect((res as { ok: boolean; conflito: boolean }).ok).toBe(false);
    expect((res as { conflito: boolean }).conflito).toBe(true);
  });
});
