import { afterEach, describe, expect, it, vi } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { designarRelator, encaminharAsComissoes, mensagemDeErroComissao, resumoDoEncaminhamento, useComissoes } from "./use-comissoes";

function mockar(resposta: (url: string, init?: RequestInit) => { status: number; corpo?: unknown }) {
  const chamadas: Array<{ url: string; method: string; body: unknown }> = [];
  global.fetch = vi.fn(async (url: string, init?: RequestInit) => {
    chamadas.push({ url: String(url), method: init?.method ?? "GET", body: init?.body ? JSON.parse(String(init.body)) : undefined });
    const r = resposta(String(url), init);
    return { ok: r.status < 300, status: r.status, json: async () => r.corpo ?? {} } as Response;
  }) as unknown as typeof fetch;
  return chamadas;
}

afterEach(() => vi.restoreAllMocks());

describe("useComissoes", () => {
  it("só busca quando ativo", async () => {
    const c = mockar(() => ({ status: 200, corpo: { comissoes: [{ id: "c1", nome: "Justiça" }] } }));
    const { result, rerender } = renderHook(({ ativo }: { ativo: boolean }) => useComissoes("tk", ativo), { initialProps: { ativo: false } });
    expect(c).toHaveLength(0);
    rerender({ ativo: true });
    await waitFor(() => expect(result.current.fase).toBe("pronto"));
    expect(result.current).toEqual({ fase: "pronto", comissoes: [{ id: "c1", nome: "Justiça" }] });
    expect(c[0].url).toBe("/api/legislativo/comissoes");
  });

  it("erro do servidor: fase erro com a frase, nunca lista vazia", async () => {
    mockar(() => ({ status: 500 }));
    const { result } = renderHook(() => useComissoes("tk", true));
    await waitFor(() => expect(result.current.fase).toBe("erro"));
    expect(result.current).toMatchObject({ fase: "erro", mensagem: "Não foi possível carregar as comissões." });
  });
});

describe("encaminhar e designar", () => {
  it("encaminha: POST com comissao-id e relator-id (null quando sem relator)", async () => {
    const c = mockar(() => ({ status: 201, corpo: { pareceres: [{ id: "pa1", "comissao-id": "c1", "comissao-nome": "Justiça", "relator-id": null, "relator-nome": null, estado: "x", "ja-existia": true }] } }));
    const r = await encaminharAsComissoes("tk", "p1", [{ comissaoId: "c1", relatorId: null }, { comissaoId: "c2", relatorId: "v1" }]);
    expect(r.ok && r.dado.pareceres[0].jaExistia).toBe(true);
    expect(c[0]).toMatchObject({ url: "/api/legislativo/proposicoes/p1/pareceres-de-comissao", method: "POST" });
    expect(c[0].body).toEqual({ comissoes: [{ "comissao-id": "c1", "relator-id": null }, { "comissao-id": "c2", "relator-id": "v1" }] });
  });

  it("designa: POST .../relator", async () => {
    const c = mockar(() => ({ status: 200, corpo: { id: "pa1", "relator-id": "v1", "relator-nome": "Helena" } }));
    const r = await designarRelator("tk", "pa1", "v1");
    expect(r.ok && r.dado.relatorNome).toBe("Helena");
    expect(c[0]).toMatchObject({ url: "/api/legislativo/pareceres/pa1/relator", method: "POST", body: { "relator-id": "v1" } });
  });

  it.each([
    [401, "encaminhar", /sessão expirou/],
    [403, "encaminhar", /Só a secretaria/],
    [404, "relator", /parecer ou o vereador/],
    [404, "encaminhar", /matéria/],
    [409, "encaminhar", /estado da matéria mudou/],
    [400, "encaminhar", /Confira as comissões/],
    [0, "relator", /Nada foi gravado/],
  ] as const)("status %i (%s)", (status, acao, esperado) => {
    expect(mensagemDeErroComissao(status, acao)).toMatch(esperado);
  });

  it("400 usa o erro do servidor", () => {
    expect(mensagemDeErroComissao(400, "encaminhar", "mais de 10 comissões")).toBe("mais de 10 comissões");
  });

  it("resumo: separa o que abriu do que já existia", () => {
    const p = (nome: string, jaExistia: boolean) => ({ id: nome, comissaoId: nome, comissaoNome: nome, relatorId: null, relatorNome: null, estado: "x", jaExistia });
    expect(resumoDoEncaminhamento([p("Justiça", false), p("Obras", false)])).toBe("Parecer aberto em Justiça, Obras.");
    expect(resumoDoEncaminhamento([p("Justiça", true)])).toBe("Justiça já tinha parecer em andamento — mantido, nada foi duplicado.");
    expect(resumoDoEncaminhamento([p("A", true), p("B", true)])).toMatch(/A, B já tinham/);
  });
});
