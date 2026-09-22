import { describe, expect, it, vi, afterEach, assert } from "vitest";
import { renderHook, act } from "@testing-library/react";
import { useAgendarSessao, type ResultadoAgendar } from "./use-agendar-sessao";

// Nota de tipagem (gate `tsc --noEmit`): `res` e' atribuido DENTRO do callback de `act`, entao o TS nao
// enxerga a atribuicao e o tipo permanecia `undefined`. Dai os casts `as { ok: boolean }` que havia aqui:
// existiam para calar o compilador e, de quebra, desligavam a checagem do payload — um `.sessao`/`.erro`
// errado passava batido. Agora a uniao e' anotada de verdade e o discriminante e' estreitado com `assert`
// (assinatura `asserts`), entao o acesso ao payload e' VERIFICADO: se o contrato do hook mudar, quebra aqui.

const ok = (json: unknown) => ({ ok: true, status: 200, json: async () => json }) as Response;
const fail = (status: number, json: unknown = {}) => ({ ok: false, status, json: async () => json }) as Response;

describe("useAgendarSessao", () => {
  afterEach(() => vi.restoreAllMocks());

  it("POSTa o corpo kebab (só campos preenchidos) e devolve o SessaoOut camelizado", async () => {
    let corpo: Record<string, unknown> = {};
    global.fetch = vi.fn(async (_url: string, init?: RequestInit) => {
      corpo = init?.body ? JSON.parse(init.body as string) : {};
      return ok({ id: "s-nova", "sessao-legislativa-id": "sl-A", "tipo-sessao": "ordinaria", estado: "agendada", "lock-version": 0 });
    }) as unknown as typeof fetch;

    const { result } = renderHook(() => useAgendarSessao("tok"));
    let res: ResultadoAgendar | undefined;
    await act(async () => {
      res = await result.current.agendar({
        sessaoLegislativaId: "sl-A",
        tipoSessao: "ordinaria",
        agendadaPara: "2026-05-21T17:00:00.000Z",
      });
    });
    assert(res?.ok === true, "esperava agendar com sucesso");
    expect(res.sessao.id).toBe("s-nova");
    expect(corpo).toMatchObject({
      "sessao-legislativa-id": "sl-A",
      "tipo-sessao": "ordinaria",
      "agendada-para": "2026-05-21T17:00:00.000Z",
    });
    expect(corpo.modalidade).toBeUndefined(); // não preenchido -> chave ausente
    expect(result.current.estado).toBe("ocioso");
  });

  it("inclui modalidade quando informada", async () => {
    let corpo: Record<string, unknown> = {};
    global.fetch = vi.fn(async (_url: string, init?: RequestInit) => {
      corpo = init?.body ? JSON.parse(init.body as string) : {};
      return ok({ id: "s2", "sessao-legislativa-id": "sl", "tipo-sessao": "solene", estado: "agendada", "lock-version": 0 });
    }) as unknown as typeof fetch;
    const { result } = renderHook(() => useAgendarSessao("tok"));
    await act(async () => {
      await result.current.agendar({ sessaoLegislativaId: "sl", tipoSessao: "solene", modalidade: "hibrida" });
    });
    expect(corpo).toMatchObject({ modalidade: "hibrida" });
  });

  it("erro do servidor -> estado erro e resultado ok:false com a mensagem", async () => {
    global.fetch = vi.fn(async () => fail(400, { erro: "sessao-legislativa-id invalido" })) as unknown as typeof fetch;
    const { result } = renderHook(() => useAgendarSessao("tok"));
    let res: ResultadoAgendar | undefined;
    await act(async () => {
      res = await result.current.agendar({ sessaoLegislativaId: "x", tipoSessao: "ordinaria" });
    });
    assert(res?.ok === false, "esperava a falha vinda do servidor");
    expect(res.erro).toMatch(/invalido/);
    expect(result.current.estado).toBe("erro");
  });

  it("sem token -> ok:false, sem chamar fetch", async () => {
    global.fetch = vi.fn() as unknown as typeof fetch;
    const { result } = renderHook(() => useAgendarSessao(null));
    let res: ResultadoAgendar | undefined;
    await act(async () => {
      res = await result.current.agendar({ sessaoLegislativaId: "sl", tipoSessao: "ordinaria" });
    });
    expect(res?.ok).toBe(false);
    expect(global.fetch).not.toHaveBeenCalled();
  });
});
