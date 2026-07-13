import { describe, it, expect, vi } from "vitest";
import { apiFetch } from "./api-fetch";

type FetchFn = (url: string | URL | Request, init?: RequestInit) => Promise<Response>;
const fetchMock = () => vi.fn<FetchFn>(async () => new Response(null, { status: 200 }));

describe("apiFetch — boundary único de auth (Onda D Slice 2, Fase 3)", () => {
  it("modo real (sem token): NÃO injeta Authorization", async () => {
    const fetchImpl = fetchMock();
    await apiFetch("/api/legislativo/proposicoes", {}, { fetchImpl });
    const [, init] = fetchImpl.mock.calls[0]!;
    const h = new Headers(init!.headers);
    expect(h.has("authorization")).toBe(false);
  });

  it("modo dev (com token): injeta Authorization: Bearer <token>", async () => {
    const fetchImpl = fetchMock();
    await apiFetch("/api/legislativo/proposicoes", { token: "tok-123" }, { fetchImpl });
    const [, init] = fetchImpl.mock.calls[0]!;
    const h = new Headers(init!.headers);
    expect(h.get("authorization")).toBe("Bearer tok-123");
  });

  it("credentials é 'same-origin' no modo real (cookie `sessao` viaja automático)", async () => {
    const fetchImpl = fetchMock();
    await apiFetch("/api/legislativo/proposicoes", {}, { fetchImpl });
    const [, init] = fetchImpl.mock.calls[0]!;
    expect(init!.credentials).toBe("same-origin");
  });

  it("credentials é 'same-origin' também no modo dev (token presente)", async () => {
    const fetchImpl = fetchMock();
    await apiFetch("/api/legislativo/proposicoes", { token: "tok-123" }, { fetchImpl });
    const [, init] = fetchImpl.mock.calls[0]!;
    expect(init!.credentials).toBe("same-origin");
  });

  it("preserva o path e repassa method/body do init do caller", async () => {
    const fetchImpl = fetchMock();
    await apiFetch("/api/legislativo/proposicoes/123", { method: "PATCH", body: JSON.stringify({ x: 1 }) }, { fetchImpl });
    const [url, init] = fetchImpl.mock.calls[0]!;
    expect(url).toBe("/api/legislativo/proposicoes/123");
    expect(init!.method).toBe("PATCH");
    expect(init!.body).toBe(JSON.stringify({ x: 1 }));
  });

  it("mescla headers do caller (ex.: Content-Type) com o Authorization injetado, sem perder nenhum", async () => {
    const fetchImpl = fetchMock();
    await apiFetch(
      "/api/legislativo/proposicoes",
      { token: "tok-123", headers: { "Content-Type": "application/json" } },
      { fetchImpl },
    );
    const [, init] = fetchImpl.mock.calls[0]!;
    const h = new Headers(init!.headers);
    expect(h.get("content-type")).toBe("application/json");
    expect(h.get("authorization")).toBe("Bearer tok-123");
  });

  it("modo real (sem token): descarta Authorization forjado pelo caller (o boundary é a única autoridade)", async () => {
    const fetchImpl = fetchMock();
    await apiFetch(
      "/api/legislativo/proposicoes",
      { headers: { Authorization: "Bearer leaked" } },
      { fetchImpl },
    );
    const [, init] = fetchImpl.mock.calls[0]!;
    const h = new Headers(init!.headers);
    expect(h.has("authorization")).toBe(false);
  });

  it("devolve a Response do fetch injetado (o caller consome normalmente)", async () => {
    const fetchImpl = vi.fn<FetchFn>(async () => new Response(JSON.stringify({ ok: true }), { status: 201 }));
    const resp = await apiFetch("/api/x", {}, { fetchImpl });
    expect(resp.status).toBe(201);
    expect(await resp.json()).toEqual({ ok: true });
  });
});
