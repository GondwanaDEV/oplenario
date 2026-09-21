import { describe, expect, it, vi, afterEach, assert } from "vitest";
import { renderHook, act, waitFor } from "@testing-library/react";
import { useVotacaoMesa, type ResultadoAbrir, type ResultadoEncerrar } from "./use-votacao-mesa";

// Nota de tipagem (gate `tsc --noEmit`): `res` e' atribuido DENTRO do callback de `act`, entao o TS nao
// enxerga a atribuicao e o tipo permanecia `undefined`. Dai os casts `as { ok: boolean }` que havia aqui:
// existiam para calar o compilador e, de quebra, desligavam a checagem do payload — um `.sessao`/`.erro`
// errado passava batido. Agora a uniao e' anotada de verdade e o discriminante e' estreitado com `assert`
// (assinatura `asserts`), entao o acesso ao payload e' VERIFICADO: se o contrato do hook mudar, quebra aqui.

const ok = (json: unknown) => ({ ok: true, status: 200, json: async () => json }) as Response;
const notFound = () => ({ ok: false, status: 404, json: async () => ({}) }) as Response;
const fail = (status: number, json: unknown = {}) => ({ ok: false, status, json: async () => json }) as Response;

const pautaJson = {
  "sessao-id": "s1",
  itens: [
    { id: "it1", fase: "ordem_do_dia", "tipo-item": "proposicao", "proposicao-id": "p1", ordem: 1, "lock-version": 0 },
    { id: "it2", fase: "expediente", "tipo-item": "comunicado", "proposicao-id": null, ordem: 2, "lock-version": 0 },
  ],
};

/** Roteia por (url, método): GET votacao-aberta, GET pauta, POST votacoes (abrir), POST encerramento. */
function roteador(h: {
  votacaoAberta: () => Response;
  pauta?: () => Response;
  abrir?: (body: Record<string, unknown>) => Response;
  encerrar?: (body: Record<string, unknown>) => Response;
}) {
  return vi.fn(async (url: string, init?: RequestInit) => {
    const metodo = (init?.method ?? "GET").toUpperCase();
    const body = init?.body ? (JSON.parse(init.body as string) as Record<string, unknown>) : {};
    if (metodo === "POST" && /\/encerramento$/.test(url)) return h.encerrar!(body);
    if (metodo === "POST" && /\/votacoes$/.test(url)) return h.abrir!(body);
    if (/\/votacao-aberta$/.test(url)) return h.votacaoAberta();
    if (/\/pauta$/.test(url)) return (h.pauta ?? (() => ok(pautaJson)))();
    throw new Error(`rota inesperada: ${metodo} ${url}`);
  }) as unknown as typeof fetch;
}

describe("useVotacaoMesa", () => {
  afterEach(() => vi.restoreAllMocks());

  it("sem votação aberta (404) -> votacaoAberta null; pauta camelizada expõe itens", async () => {
    global.fetch = roteador({ votacaoAberta: notFound });
    const { result } = renderHook(() => useVotacaoMesa("s1", "tok"));
    await waitFor(() => expect(result.current.estado).toBe("pronto"));
    expect(result.current.votacaoAberta).toBeNull();
    await waitFor(() => expect(result.current.itens.length).toBe(2));
    expect(result.current.itens[0].proposicaoId).toBe("p1"); // camelizado
  });

  it("com votação aberta -> resumo camelizado", async () => {
    global.fetch = roteador({
      votacaoAberta: () =>
        ok({ "votacao-id": "vt9", modalidade: "nominal", "objeto-tipo": "proposicao", "objeto-id": "p1", proposicao: null, votos: [] }),
    });
    const { result } = renderHook(() => useVotacaoMesa("s1", "tok"));
    await waitFor(() => expect(result.current.votacaoAberta?.votacaoId).toBe("vt9"));
    expect(result.current.votacaoAberta?.objetoTipo).toBe("proposicao");
  });

  it("abrir POSTa o corpo kebab e recarrega", async () => {
    let vaOpen = false;
    global.fetch = roteador({
      votacaoAberta: () =>
        vaOpen
          ? ok({ "votacao-id": "vtX", modalidade: "simbolica", "objeto-tipo": "proposicao", "objeto-id": "p1", proposicao: null })
          : notFound(),
      abrir: (body) => {
        expect(body).toMatchObject({
          "objeto-tipo": "proposicao",
          "objeto-id": "p1",
          modalidade: "simbolica",
          "quorum-tipo": "maioria_absoluta",
        });
        vaOpen = true;
        return ok({ id: "vtX", estado: "aberta", "lock-version": 0 });
      },
    });
    const { result } = renderHook(() => useVotacaoMesa("s1", "tok"));
    await waitFor(() => expect(result.current.estado).toBe("pronto"));

    let res: ResultadoAbrir | undefined;
    await act(async () => {
      res = await result.current.abrir({
        objetoTipo: "proposicao",
        objetoId: "p1",
        modalidade: "simbolica",
        quorumTipo: "maioria_absoluta",
      });
    });
    expect(res?.ok).toBe(true);
    await waitFor(() => expect(result.current.votacaoAberta?.votacaoId).toBe("vtX"));
  });

  it("encerrar envia lock-version 0 do AberturaOut guardado e o resultado (simbólica)", async () => {
    let vaOpen = false;
    let corpoEncerrar: Record<string, unknown> = {};
    global.fetch = roteador({
      votacaoAberta: () =>
        vaOpen
          ? ok({ "votacao-id": "vtX", modalidade: "simbolica", "objeto-tipo": "proposicao", "objeto-id": "p1", proposicao: null })
          : notFound(),
      abrir: () => {
        vaOpen = true;
        return ok({ id: "vtX", estado: "aberta", "lock-version": 0 });
      },
      encerrar: (body) => {
        corpoEncerrar = body;
        vaOpen = false;
        return ok({ id: "vtX", estado: "encerrada", resultado: "aprovada" });
      },
    });
    const { result } = renderHook(() => useVotacaoMesa("s1", "tok"));
    await waitFor(() => expect(result.current.estado).toBe("pronto"));
    await act(async () => {
      await result.current.abrir({ objetoTipo: "proposicao", objetoId: "p1", modalidade: "simbolica", quorumTipo: "maioria_simples" });
    });
    await waitFor(() => expect(result.current.votacaoAberta?.votacaoId).toBe("vtX"));

    let res: ResultadoEncerrar | undefined;
    await act(async () => {
      res = await result.current.encerrar("aprovada");
    });
    expect(res?.ok).toBe(true);
    expect(corpoEncerrar).toMatchObject({ "lock-version": 0, resultado: "aprovada" });
    await waitFor(() => expect(result.current.votacaoAberta).toBeNull());
  });

  it("encerrar 409 -> conflito=true e recarrega", async () => {
    global.fetch = roteador({
      votacaoAberta: () =>
        ok({ "votacao-id": "vtX", modalidade: "nominal", "objeto-tipo": "proposicao", "objeto-id": "p1", proposicao: null, votos: [] }),
      encerrar: () => fail(409, { erro: "votacao terminal" }),
    });
    const { result } = renderHook(() => useVotacaoMesa("s1", "tok"));
    await waitFor(() => expect(result.current.votacaoAberta?.votacaoId).toBe("vtX"));

    let res: ResultadoEncerrar | undefined;
    await act(async () => {
      res = await result.current.encerrar();
    });
    assert(res?.ok === false, "esperava recusa por conflito de lock");
    expect(res.conflito).toBe(true);
  });

  it("abrir sem objeto -> erro local, sem tocar a rede de escrita", async () => {
    const rot = roteador({ votacaoAberta: notFound });
    global.fetch = rot;
    const { result } = renderHook(() => useVotacaoMesa("s1", "tok"));
    await waitFor(() => expect(result.current.estado).toBe("pronto"));
    let res: ResultadoAbrir | undefined;
    await act(async () => {
      res = await result.current.abrir({ objetoTipo: "proposicao", objetoId: "", modalidade: "nominal", quorumTipo: "maioria_simples" });
    });
    expect(res?.ok).toBe(false);
  });
});
