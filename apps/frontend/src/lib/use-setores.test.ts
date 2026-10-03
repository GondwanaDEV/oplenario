import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, renderHook, waitFor } from "@testing-library/react";
import { criarSetor, definirMembros, salvarSetor, useSetores } from "./use-setores";

type Chamada = { url: string; metodo: string; corpo: unknown };

function mockar(rotas: Record<string, { status?: number; corpo?: unknown }>) {
  const chamadas: Chamada[] = [];
  global.fetch = vi.fn(async (url: string, init?: RequestInit) => {
    const c = { url: String(url), metodo: init?.method ?? "GET", corpo: init?.body ? JSON.parse(String(init.body)) : undefined };
    chamadas.push(c);
    const r = rotas[`${c.metodo} ${c.url}`];
    if (!r) return { ok: false, status: 404, json: async () => ({}) } as Response;
    const status = r.status ?? 200;
    return { ok: status < 300, status, json: async () => r.corpo ?? {} } as Response;
  }) as unknown as typeof fetch;
  return chamadas;
}

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe("setores (ADR-0020)", () => {
  it("lista os setores com os membros, camelizados", async () => {
    mockar({
      "GET /api/administracao/setores": {
        corpo: { setores: [{ id: "s1", nome: "Jurídico", ativo: true, membros: [{ "identidade-id": "i1", nome: "Lúcia Prado" }] }] },
      },
    });
    const { result } = renderHook(() => useSetores("tok"));
    await waitFor(() => expect(result.current.estado.fase).toBe("pronto"));
    expect(result.current.estado).toEqual({
      fase: "pronto",
      dado: { setores: [{ id: "s1", nome: "Jurídico", ativo: true, membros: [{ identidadeId: "i1", nome: "Lúcia Prado" }] }] },
    });
  });

  it("403 vira a frase da administração", async () => {
    mockar({ "GET /api/administracao/setores": { status: 403 } });
    const { result } = renderHook(() => useSetores("tok"));
    await waitFor(() => expect(result.current.estado).toMatchObject({ fase: "erro", mensagem: "Os setores são mantidos pelo administrador da Casa." }));
  });

  it("criar (nome aparado), renomear/desativar e trocar a lotação inteira, pelas rotas do contrato", async () => {
    const c = mockar({
      "POST /api/administracao/setores": { status: 201, corpo: { setor: { id: "s9" } } },
      "PUT /api/administracao/setores/s9": { corpo: {} },
      "PUT /api/administracao/setores/s9/membros": { corpo: {} },
    });
    expect((await criarSetor("tok", "  Protocolo ")).ok).toBe(true);
    expect((await salvarSetor("tok", "s9", { nome: " Protocolo Geral ", ativo: false })).ok).toBe(true);
    expect((await definirMembros("tok", "s9", ["i1", "i2"])).ok).toBe(true);
    expect(c.map((x) => [x.metodo, x.url, x.corpo])).toEqual([
      ["POST", "/api/administracao/setores", { nome: "Protocolo" }],
      ["PUT", "/api/administracao/setores/s9", { nome: "Protocolo Geral", ativo: false }],
      ["PUT", "/api/administracao/setores/s9/membros", { identidades: ["i1", "i2"] }],
    ]);
  });

  it("nome repetido (409) é dito em português", async () => {
    mockar({ "POST /api/administracao/setores": { status: 409, corpo: {} } });
    expect(await criarSetor("tok", "Jurídico")).toMatchObject({ ok: false, mensagem: "Já existe um setor com esse nome nesta Casa." });
  });
});
