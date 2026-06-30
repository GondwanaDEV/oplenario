import { describe, it, expect, vi } from "vitest";
import { proxiarPlenario } from "./sse-proxy";

// Um corpo de stream qualquer p/ provar que o handler devolve o MESMO body upstream (sem bufferizar).
const corpoStream = () =>
  new ReadableStream<Uint8Array>({
    start(c) {
      c.enqueue(new TextEncoder().encode("event: ping\ndata: 1\n\n"));
      c.close();
    },
  });

const req = (headers: Record<string, string> = {}) =>
  new Request("http://front.local/api/sessoes/s1/plenario", { headers });

type FetchFn = (url: string | URL | Request, init?: RequestInit) => Promise<Response>;
const fetchMock = (impl: FetchFn) => vi.fn<FetchFn>(impl);

describe("proxiarPlenario — Route Handler que faz proxy de SSE com stream real", () => {
  it("rejeita id malformado com 400 e NÃO chama o upstream (defesa em profundidade)", async () => {
    const fetchImpl = vi.fn();
    const resp = await proxiarPlenario(req(), "../etc/passwd", { fetchImpl });
    expect(resp.status).toBe(400);
    expect(fetchImpl).not.toHaveBeenCalled();
  });

  it("encaminha Authorization/Last-Event-ID, força Accept-Encoding: identity e bate na URL upstream certa", async () => {
    const fetchImpl = fetchMock(async () => new Response(corpoStream(), {
      status: 200, headers: { "content-type": "text/event-stream" },
    }));
    await proxiarPlenario(
      req({ authorization: "Bearer tok", "last-event-id": "42" }),
      "sessao-abc",
      { backend: "http://back:8888", fetchImpl },
    );
    expect(fetchImpl).toHaveBeenCalledOnce();
    const [url, init] = fetchImpl.mock.calls[0]!;
    expect(url).toBe("http://back:8888/sessoes/sessao-abc/plenario");
    const h = new Headers(init!.headers);
    expect(h.get("authorization")).toBe("Bearer tok");
    expect(h.get("last-event-id")).toBe("42");
    expect(h.get("accept")).toBe("text/event-stream");
    expect(h.get("accept-encoding")).toBe("identity"); // o elo do bug: sem gzip, sem buffering de bloco
  });

  it("NÃO inventa Authorization quando o cliente não manda (deixa o upstream decidir 401)", async () => {
    const fetchImpl = fetchMock(async () => new Response(corpoStream(), { status: 200 }));
    await proxiarPlenario(req(), "s1", { fetchImpl });
    const h = new Headers(fetchImpl.mock.calls[0]![1]!.headers);
    expect(h.has("authorization")).toBe(false);
  });

  it("propaga o status do upstream quando não-ok (401/403/404) sem corpo de stream", async () => {
    const fetchImpl = fetchMock(async () => new Response(null, { status: 403 }));
    const resp = await proxiarPlenario(req({ authorization: "Bearer x" }), "s1", { fetchImpl });
    expect(resp.status).toBe(403);
  });

  it("no caso feliz devolve 200 com o MESMO body do upstream e cabeçalhos anti-buffering de SSE", async () => {
    const body = corpoStream();
    const fetchImpl = fetchMock(async () => new Response(body, {
      status: 200, headers: { "content-type": "text/event-stream" },
    }));
    const resp = await proxiarPlenario(req({ authorization: "Bearer x" }), "s1", { fetchImpl });
    expect(resp.status).toBe(200);
    expect(resp.body).toBe(body); // o body atravessa por referência — não é lido/bufferizado no handler
    expect(resp.headers.get("content-type")).toBe("text/event-stream");
    expect(resp.headers.get("cache-control")).toMatch(/no-transform/);
    expect(resp.headers.get("x-accel-buffering")).toBe("no");
  });

  it("repassa o AbortSignal do request ao upstream (cliente desconecta -> upstream aborta)", async () => {
    const fetchImpl = fetchMock(async () => new Response(corpoStream(), { status: 200 }));
    const r = req({ authorization: "Bearer x" });
    await proxiarPlenario(r, "s1", { fetchImpl });
    expect(fetchImpl.mock.calls[0]![1]!.signal).toBe(r.signal);
  });
});

describe("proxiarPlenario — robustez (reviews ecc: erro de rede, abort, headers, SSRF)", () => {
  it("backend inacessível (fetch lança erro de rede) -> 503 JSON, não 500 HTML", async () => {
    const fetchImpl = fetchMock(async () => { throw new Error("ECONNREFUSED"); });
    const resp = await proxiarPlenario(req({ authorization: "Bearer x" }), "s1", { fetchImpl });
    expect(resp.status).toBe(503);
    expect(resp.headers.get("content-type")).toMatch(/json/);
  });

  it("cliente desconecta (fetch lança AbortError) -> 499, encerramento esperado de SSE", async () => {
    const fetchImpl = fetchMock(async () => {
      throw Object.assign(new Error("aborted"), { name: "AbortError" });
    });
    const resp = await proxiarPlenario(req({ authorization: "Bearer x" }), "s1", { fetchImpl });
    expect(resp.status).toBe(499);
  });

  it("dropa Last-Event-ID malformado (não numérico) — não o repassa ao upstream", async () => {
    const fetchImpl = fetchMock(async () => new Response(corpoStream(), { status: 200 }));
    await proxiarPlenario(req({ authorization: "Bearer x", "last-event-id": "abc; rm -rf" }), "s1", { fetchImpl });
    const h = new Headers(fetchImpl.mock.calls[0]![1]!.headers);
    expect(h.has("last-event-id")).toBe(false);
  });

  it("repassa Last-Event-ID numérico válido (o seq monotônico do backend)", async () => {
    const fetchImpl = fetchMock(async () => new Response(corpoStream(), { status: 200 }));
    await proxiarPlenario(req({ authorization: "Bearer x", "last-event-id": "42" }), "s1", { fetchImpl });
    const h = new Headers(fetchImpl.mock.calls[0]![1]!.headers);
    expect(h.get("last-event-id")).toBe("42");
  });

  it("não devolve o header hop-by-hop Connection (inválido em HTTP/2)", async () => {
    const fetchImpl = fetchMock(async () => new Response(corpoStream(), {
      status: 200, headers: { "content-type": "text/event-stream" },
    }));
    const resp = await proxiarPlenario(req({ authorization: "Bearer x" }), "s1", { fetchImpl });
    expect(resp.headers.has("connection")).toBe(false);
  });

  it("proíbe opts.backend fora de teste (superfície de SSRF) — lança em produção", async () => {
    const orig = process.env.NODE_ENV;
    try {
      vi.stubEnv("NODE_ENV", "production");
      await expect(
        proxiarPlenario(req({ authorization: "Bearer x" }), "s1", { backend: "http://evil" }),
      ).rejects.toThrow();
    } finally {
      vi.unstubAllEnvs();
      expect(process.env.NODE_ENV).toBe(orig);
    }
  });
});
