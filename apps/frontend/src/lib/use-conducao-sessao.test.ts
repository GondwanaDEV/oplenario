import { describe, expect, it, vi, afterEach } from "vitest";
import { renderHook, act, waitFor } from "@testing-library/react";
import { useConducaoSessao } from "./use-conducao-sessao";
import type { SessaoOut } from "./contrato-sessoes.gen";

function sessaoJson(estado: SessaoOut["estado"], lockVersion: number): Record<string, unknown> {
  // corpo cru do servidor (kebab-case) — o hook cameliza na entrada
  return {
    id: "s1",
    "sessao-legislativa-id": "sl1",
    "tipo-sessao": "ordinaria",
    "numero-sequencial": 14,
    estado,
    modalidade: "presencial",
    delibera: true,
    "transmite-publica": true,
    "gera-ata-regimental": true,
    "permite-voto-secreto": false,
    "permite-modalidade-remota": false,
    "lock-version": lockVersion,
  };
}

const ok = (json: unknown) => ({ ok: true, status: 200, json: async () => json }) as Response;
const fail = (status: number, json: unknown = {}) => ({ ok: false, status, json: async () => json }) as Response;

/** Roteia o fetch por (url, método) — GET carrega a sessão, POST /transicao é a escrita. */
function roteador(handlers: {
  get?: () => Response;
  post?: (body: Record<string, unknown>) => Response;
}) {
  return vi.fn(async (url: string, init?: RequestInit) => {
    const metodo = (init?.method ?? "GET").toUpperCase();
    if (metodo === "POST" && /\/transicao$/.test(url)) {
      const body = init?.body ? (JSON.parse(init.body as string) as Record<string, unknown>) : {};
      return handlers.post!(body);
    }
    return handlers.get!();
  }) as unknown as typeof fetch;
}

describe("useConducaoSessao", () => {
  afterEach(() => vi.restoreAllMocks());

  it("carrega o SessaoOut (camelizado) e fica 'pronto'", async () => {
    global.fetch = roteador({ get: () => ok(sessaoJson("agendada", 3)) });
    const { result } = renderHook(() => useConducaoSessao("s1", "tok"));
    await waitFor(() => expect(result.current.estado).toBe("pronto"));
    expect(result.current.sessao?.estado).toBe("agendada");
    expect(result.current.sessao?.lockVersion).toBe(3);
  });

  it("transicionar envia {para, lock-version} do último snapshot e recarrega no sucesso", async () => {
    let estadoServidor: SessaoOut["estado"] = "agendada";
    let lock = 3;
    global.fetch = roteador({
      get: () => ok(sessaoJson(estadoServidor, lock)),
      post: (body) => {
        expect(body).toMatchObject({ para: "aberta", "lock-version": 3 });
        expect(body.motivo).toBeUndefined(); // sem motivo -> chave ausente
        estadoServidor = "aberta";
        lock = 4;
        return ok({ "sessao-id": "s1", de: "agendada", para: "aberta" });
      },
    });
    const { result } = renderHook(() => useConducaoSessao("s1", "tok"));
    await waitFor(() => expect(result.current.estado).toBe("pronto"));

    let res: { ok: boolean; conflito?: boolean; erro?: string } | undefined;
    await act(async () => {
      res = await result.current.transicionar("aberta");
    });
    expect(res!.ok).toBe(true);
    await waitFor(() => expect(result.current.sessao?.estado).toBe("aberta"));
    expect(result.current.sessao?.lockVersion).toBe(4);
  });

  it("inclui motivo (aparado) no corpo quando passado", async () => {
    let visto: Record<string, unknown> = {};
    global.fetch = roteador({
      get: () => ok(sessaoJson("agendada", 1)),
      post: (body) => {
        visto = body;
        return ok({ "sessao-id": "s1", de: "agendada", para: "nao_realizada" });
      },
    });
    const { result } = renderHook(() => useConducaoSessao("s1", "tok"));
    await waitFor(() => expect(result.current.estado).toBe("pronto"));
    await act(async () => {
      await result.current.transicionar("nao_realizada", "  falta de quórum  ");
    });
    expect(visto).toMatchObject({ para: "nao_realizada", motivo: "falta de quórum" });
  });

  it("409 -> conflito=true, mensagem acionável, e recarrega o estado", async () => {
    let gets = 0;
    global.fetch = roteador({
      get: () => {
        gets += 1;
        return ok(sessaoJson("aberta", 9)); // servidor já está noutro estado/versão
      },
      post: () => fail(409, { erro: "transicao invalida" }),
    });
    const { result } = renderHook(() => useConducaoSessao("s1", "tok"));
    await waitFor(() => expect(result.current.estado).toBe("pronto"));
    const getsAntes = gets;

    let res: { ok: boolean; conflito?: boolean; erro?: string } | undefined;
    await act(async () => {
      res = await result.current.transicionar("encerrada");
    });
    expect(res!.ok).toBe(false);
    expect(res!.conflito).toBe(true);
    expect(res!.erro).toMatch(/desatualizada|recarregou/i);
    expect(gets).toBeGreaterThan(getsAntes); // re-buscou após o 409
  });

  it("sem token -> estado de erro, nunca chama fetch", async () => {
    global.fetch = vi.fn() as unknown as typeof fetch;
    const { result } = renderHook(() => useConducaoSessao("s1", null));
    expect(result.current.estado).toBe("erro");
    let res: { ok: boolean; conflito?: boolean; erro?: string } | undefined;
    await act(async () => {
      res = await result.current.transicionar("aberta");
    });
    expect(res!.ok).toBe(false);
    expect(global.fetch).not.toHaveBeenCalled();
  });
});
