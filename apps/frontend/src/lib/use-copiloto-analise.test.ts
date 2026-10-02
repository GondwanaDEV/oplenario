import { afterEach, describe, expect, it, vi } from "vitest";
import { pedirAnalise } from "./use-copiloto-analise";

describe("pedirAnalise (copiloto do relator)", () => {
  afterEach(() => vi.restoreAllMocks());

  it("secretaria: POST /api/legislativo/pareceres/:id/copiloto, camelizado", async () => {
    global.fetch = vi.fn(
      async () =>
        ({
          ok: true,
          status: 200,
          json: async () => ({
            analise: {
              texto: "T",
              citacoes: [{ "fonte-id": "norma:n1#art11", rotulo: "LOM, art. 11", trecho: "x", status: "conferida" }],
              "paragrafos-sem-fonte": [],
              "pontos-a-confirmar": ["p"],
              incerteza: "normal",
              modelo: "fake-1",
            },
            normas: "citadas",
            indisponivel: null,
          }),
        }) as Response,
    ) as unknown as typeof fetch;
    const r = await pedirAnalise("tok", "legislativo", "p 1");
    const [url, init] = (global.fetch as unknown as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(url).toBe("/api/legislativo/pareceres/p%201/copiloto");
    expect(init.method).toBe("POST");
    expect(r.tipo).toBe("rascunho");
    if (r.tipo === "rascunho") {
      expect(r.analise.pontosAConfirmar).toEqual(["p"]);
      expect(r.analise.citacoes[0].fonteId).toBe("norma:n1#art11");
    }
  });

  it("relator: borda /meu; 404 e falha de rede viram mensagem, nunca exceção", async () => {
    global.fetch = vi.fn(async () => ({ ok: false, status: 404, json: async () => ({}) }) as Response) as unknown as typeof fetch;
    const r = await pedirAnalise("tok", "meu", "p1");
    expect((global.fetch as unknown as ReturnType<typeof vi.fn>).mock.calls[0][0]).toBe("/api/meu/pareceres/p1/copiloto");
    expect(r).toEqual({ tipo: "nada", mensagem: expect.stringMatching(/não está com você como relator/) });
    global.fetch = vi.fn(async () => {
      throw new Error("rede");
    }) as unknown as typeof fetch;
    expect((await pedirAnalise("tok", "meu", "p1")).tipo).toBe("nada");
  });
});
