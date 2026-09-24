import { describe, expect, it, vi, afterEach } from "vitest";
import { renderHook, act } from "@testing-library/react";
import { MSG_CONFLITO_PAUTA, MSG_TROCA_PARCIAL, useEditarPauta } from "./use-editar-pauta";

// docs/23 Fatia 1 — escritas da pauta: POST/PATCH/DELETE /api/sessoes/:id/pauta/itens[/:item-id].

function resposta(status: number, corpo: unknown = {}): Response {
  return { ok: status >= 200 && status < 300, status, json: async () => corpo } as Response;
}

function mockFetch(...respostas: Response[]) {
  const fila = [...respostas];
  const fn = vi.fn(async () => fila.shift() ?? resposta(500));
  global.fetch = fn as unknown as typeof fetch;
  return fn;
}

function corpoDa(fn: ReturnType<typeof vi.fn>, i: number) {
  const init = fn.mock.calls[i][1] as RequestInit;
  return { url: fn.mock.calls[i][0], method: init.method, corpo: JSON.parse(init.body as string) };
}

const A = { id: "i1", ordem: 3, lockVersion: 0 };
const B = { id: "i2", ordem: 5, lockVersion: 2 };

describe("useEditarPauta", () => {
  afterEach(() => vi.restoreAllMocks());

  it("incluir proposição: POST com fase, tipo-item e proposicao-id em kebab-case", async () => {
    const fn = mockFetch(resposta(201, { id: "novo", ordem: 7 }));
    const { result } = renderHook(() => useEditarPauta("s1", "tok"));
    let r;
    await act(async () => {
      r = await result.current.incluir({ fase: "ordem_do_dia", tipoItem: "proposicao", proposicaoId: "p9" });
    });
    expect(r).toEqual({ ok: true });
    expect(corpoDa(fn, 0)).toEqual({
      url: "/api/sessoes/s1/pauta/itens",
      method: "POST",
      corpo: { fase: "ordem_do_dia", "tipo-item": "proposicao", "proposicao-id": "p9" },
    });
  });

  it("incluir item de texto: manda texto-descricao aparado, nunca proposicao-id", async () => {
    const fn = mockFetch(resposta(201, { id: "novo", ordem: 1 }));
    const { result } = renderHook(() => useEditarPauta("s1", "tok"));
    await act(async () => {
      await result.current.incluir({ fase: "expediente", tipoItem: "leitura", textoDescricao: "  Leitura da ata  " });
    });
    expect(corpoDa(fn, 0).corpo).toEqual({ fase: "expediente", "tipo-item": "leitura", "texto-descricao": "Leitura da ata" });
  });

  it("incluir texto vazio: recusa sem chamar a API", async () => {
    const fn = mockFetch();
    const { result } = renderHook(() => useEditarPauta("s1", "tok"));
    let r;
    await act(async () => {
      r = await result.current.incluir({ fase: "expediente", tipoItem: "comunicado", textoDescricao: "   " });
    });
    expect(r).toEqual({ ok: false, conflito: false, erro: "Descreva o item antes de incluir." });
    expect(fn).not.toHaveBeenCalled();
  });

  it("mover: troca as ordens com dois PATCHes, cada um com o próprio lock-version", async () => {
    const fn = mockFetch(resposta(200, { id: "i1", de: 3, para: 5 }), resposta(200, { id: "i2", de: 5, para: 3 }));
    const { result } = renderHook(() => useEditarPauta("s1", "tok"));
    let r;
    await act(async () => {
      r = await result.current.mover(A, B, "abaixo");
    });
    expect(r).toEqual({ ok: true });
    expect(corpoDa(fn, 0)).toEqual({ url: "/api/sessoes/s1/pauta/itens/i1", method: "PATCH", corpo: { "nova-ordem": 5, "lock-version": 0 } });
    expect(corpoDa(fn, 1)).toEqual({ url: "/api/sessoes/s1/pauta/itens/i2", method: "PATCH", corpo: { "nova-ordem": 3, "lock-version": 2 } });
  });

  it("mover com empate de ordem: um PATCH só, deslocando o item em 1 na direção pedida", async () => {
    const fn = mockFetch(resposta(200, {}));
    const { result } = renderHook(() => useEditarPauta("s1", "tok"));
    await act(async () => {
      await result.current.mover({ ...A, ordem: 4 }, { ...B, ordem: 4 }, "acima");
    });
    expect(fn).toHaveBeenCalledTimes(1);
    expect(corpoDa(fn, 0).corpo).toEqual({ "nova-ordem": 3, "lock-version": 0 });
  });

  it("mover: 409 no primeiro PATCH vira conflito e não tenta o segundo", async () => {
    const fn = mockFetch(resposta(409, { erro: "item removido ou lock-version desatualizado" }));
    const { result } = renderHook(() => useEditarPauta("s1", "tok"));
    let r;
    await act(async () => {
      r = await result.current.mover(A, B, "abaixo");
    });
    expect(r).toEqual({ ok: false, conflito: true, erro: MSG_CONFLITO_PAUTA });
    expect(fn).toHaveBeenCalledTimes(1);
  });

  it("mover: falha no segundo PATCH avisa a troca parcial", async () => {
    mockFetch(resposta(200, {}), resposta(409, {}));
    const { result } = renderHook(() => useEditarPauta("s1", "tok"));
    let r;
    await act(async () => {
      r = await result.current.mover(A, B, "abaixo");
    });
    expect(r).toEqual({ ok: false, conflito: true, erro: MSG_TROCA_PARCIAL });
  });

  it("retirar: DELETE com tipo, lock-version e justificativa só quando preenchida", async () => {
    const fn = mockFetch(resposta(200, { id: "i1" }), resposta(200, { id: "i2" }));
    const { result } = renderHook(() => useEditarPauta("s1", "tok"));
    await act(async () => {
      await result.current.retirar(A, "retirada_pedido_autor", "  Pedido do autor em plenário ");
      await result.current.retirar(B, "exclusao", "   ");
    });
    expect(corpoDa(fn, 0)).toEqual({
      url: "/api/sessoes/s1/pauta/itens/i1",
      method: "DELETE",
      corpo: { tipo: "retirada_pedido_autor", "lock-version": 0, justificativa: "Pedido do autor em plenário" },
    });
    expect(corpoDa(fn, 1).corpo).toEqual({ tipo: "exclusao", "lock-version": 2 });
  });

  it("erro não-409 devolve a mensagem do servidor, sem marcar conflito", async () => {
    mockFetch(resposta(400, { erro: "corpo de adicionar item invalido" }));
    const { result } = renderHook(() => useEditarPauta("s1", "tok"));
    let r;
    await act(async () => {
      r = await result.current.incluir({ fase: "ordem_do_dia", tipoItem: "proposicao", proposicaoId: "p1" });
    });
    expect(r).toEqual({ ok: false, conflito: false, erro: "corpo de adicionar item invalido" });
  });

  it("falha de rede vira mensagem, nunca exceção", async () => {
    global.fetch = vi.fn(async () => {
      throw new TypeError("rede");
    }) as unknown as typeof fetch;
    const { result } = renderHook(() => useEditarPauta("s1", "tok"));
    let r;
    await act(async () => {
      r = await result.current.retirar(A, "exclusao");
    });
    expect(r).toEqual({ ok: false, conflito: false, erro: "Falha de rede — tente novamente." });
    expect(result.current.enviando).toBe(false);
  });

  it("sem sessão: recusa sem chamar a API", async () => {
    const fn = mockFetch();
    const { result } = renderHook(() => useEditarPauta(null, "tok"));
    let r;
    await act(async () => {
      r = await result.current.retirar(A, "exclusao");
    });
    expect(r).toMatchObject({ ok: false });
    expect(fn).not.toHaveBeenCalled();
  });
});
