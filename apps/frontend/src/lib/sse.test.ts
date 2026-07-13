import { describe, it, expect, vi, afterEach } from "vitest";
import { extrairFrames, consumirSse } from "./sse";

describe("extrairFrames — parser de frames SSE sobre buffer de texto", () => {
  it("extrai um frame completo (event/data/id) e não deixa resto", () => {
    const { frames, resto } = extrairFrames('event: presenca.registrada\ndata: {"vereador-id":"v1"}\nid: 7\n\n');
    expect(frames).toEqual([{ event: "presenca.registrada", data: '{"vereador-id":"v1"}', id: "7" }]);
    expect(resto).toBe("");
  });

  it("guarda um frame parcial como resto (chegou cortado no meio do chunk)", () => {
    const { frames, resto } = extrairFrames("event: fala.iniciada\ndata: {\"fala");
    expect(frames).toEqual([]);
    expect(resto).toBe('event: fala.iniciada\ndata: {"fala');
  });

  it("extrai múltiplos frames de um chunk e preserva o parcial final como resto", () => {
    const { frames, resto } = extrairFrames("event: a\ndata: 1\n\nevent: b\ndata: 2\n\nevent: c\ndata: 3");
    expect(frames.map((f) => f.event)).toEqual(["a", "b"]);
    expect(resto).toBe("event: c\ndata: 3");
  });

  it("concatena linhas data: múltiplas com \\n (spec SSE)", () => {
    const { frames } = extrairFrames("data: linha1\ndata: linha2\n\n");
    expect(frames[0].data).toBe("linha1\nlinha2");
  });

  it("tolera o espaço opcional após os dois-pontos (com e sem)", () => {
    const { frames } = extrairFrames("event:semespaco\ndata:x\n\n");
    expect(frames[0]).toMatchObject({ event: "semespaco", data: "x" });
  });

  it("ignora linha de comentário (começa com :) — usada como heartbeat", () => {
    const { frames, resto } = extrairFrames(": keep-alive\n\n");
    expect(frames).toEqual([]);
    expect(resto).toBe("");
  });
});

// corpo de stream com um frame único, p/ provar o pipeline fetch -> reader -> extrairFrames -> aoFrame.
const corpoStream = () =>
  new ReadableStream<Uint8Array>({
    start(c) {
      c.enqueue(new TextEncoder().encode("event: ping\ndata: 1\n\n"));
      c.close();
    },
  });

type FetchFn = (url: string | URL | Request, init?: RequestInit) => Promise<Response>;
const fetchMock = () => vi.fn<FetchFn>(async () => new Response(corpoStream(), { status: 200 }));

describe("consumirSse — IO roteado pelo boundary apiFetch (single boundary de auth)", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("modo dev (com token): a requisição carrega Authorization: Bearer <token>", async () => {
    const fetchImpl = fetchMock();
    vi.stubGlobal("fetch", fetchImpl);

    await consumirSse("/api/sessoes/s1/plenario", { token: "tok-123", aoFrame: () => {} });

    expect(fetchImpl).toHaveBeenCalledOnce();
    const [, init] = fetchImpl.mock.calls[0]!;
    const h = new Headers(init!.headers);
    expect(h.get("authorization")).toBe("Bearer tok-123");
  });

  it("modo real (sem token): NÃO carrega Authorization e usa credentials same-origin (cookie rideia)", async () => {
    const fetchImpl = fetchMock();
    vi.stubGlobal("fetch", fetchImpl);

    await consumirSse("/api/sessoes/s1/plenario", { token: null, aoFrame: () => {} });

    expect(fetchImpl).toHaveBeenCalledOnce();
    const [, init] = fetchImpl.mock.calls[0]!;
    const h = new Headers(init!.headers);
    expect(h.has("authorization")).toBe(false);
    expect(init!.credentials).toBe("same-origin");
  });

  it("entrega o frame recebido a aoFrame (prova o pipeline getReader/decode/extrairFrames)", async () => {
    const fetchImpl = fetchMock();
    vi.stubGlobal("fetch", fetchImpl);

    const recebidos: string[] = [];
    await consumirSse("/api/sessoes/s1/plenario", { token: "tok-123", aoFrame: (f) => recebidos.push(f.event ?? "") });

    expect(recebidos).toEqual(["ping"]);
  });
});
