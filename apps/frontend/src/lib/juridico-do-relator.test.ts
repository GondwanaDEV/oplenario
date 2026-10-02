import { afterEach, describe, expect, it, vi } from "vitest";
import {
  linhasDaSituacaoJuridica,
  mensagemDeErroPedidoDoRelator,
  pedirParecerJuridicoDoRelator,
} from "./juridico-do-relator";

const pedidoFake = {
  id: "pj1",
  proposicao: { id: "obj1", ref: "PL 12/2026", ementa: "Hortas" },
  assunto: "Análise jurídica da matéria",
  prazo: null,
  estado: "pendente",
  "pedido-por": "Vereadora Ana",
  "em-nome-de": null,
  origem: "relator",
  "criado-em": "2026-09-30T12:00:00Z",
  parecer: null,
};

describe("pedido de parecer jurídico pelo relator", () => {
  afterEach(() => vi.restoreAllMocks());

  it("POST /api/meu/pareceres/:id/pedido-juridico; assunto em branco não vai (o servidor usa o padrão)", async () => {
    global.fetch = vi.fn(async () => ({ ok: true, status: 201, json: async () => pedidoFake }) as Response) as unknown as typeof fetch;
    const r = await pedirParecerJuridicoDoRelator("tok", "p1", "   ");
    expect(r.ok).toBe(true);
    const [url, init] = (global.fetch as unknown as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(url).toBe("/api/meu/pareceres/p1/pedido-juridico");
    expect(init.method).toBe("POST");
    expect(init.body).toBe("{}");
    await pedirParecerJuridicoDoRelator("tok", "p1", " Competência do Município ");
    expect((global.fetch as unknown as ReturnType<typeof vi.fn>).mock.calls[1][1].body).toBe(
      JSON.stringify({ assunto: "Competência do Município" }),
    );
  });

  it("erro: a frase é do relator (não a da secretaria); o 400 traz o que o servidor disse", async () => {
    global.fetch = vi.fn(async () => ({ ok: false, status: 404, json: async () => ({ erro: "x" }) }) as Response) as unknown as typeof fetch;
    const r = await pedirParecerJuridicoDoRelator("tok", "p1", "");
    expect(r).toEqual({ ok: false, status: 404, mensagem: mensagemDeErroPedidoDoRelator(404) });
    global.fetch = vi.fn(
      async () => ({ ok: false, status: 400, json: async () => ({ erro: "o assunto tem de 5 a 300 caracteres" }) }) as Response,
    ) as unknown as typeof fetch;
    const r2 = await pedirParecerJuridicoDoRelator("tok", "p1", "abc");
    expect(r2.ok).toBe(false);
    if (!r2.ok) expect(r2.mensagem).toBe("o assunto tem de 5 a 300 caracteres");
  });

  it("a situação da matéria: pedidos em aberto e o parecer assinado vigente (o substituído não)", () => {
    const linhas = linhasDaSituacaoJuridica({
      pedidosAbertos: [{ id: "a", assunto: "Competência", prazo: null, criadoEm: "2026-09-29T12:00:00Z" }],
      pareceres: [
        {
          id: "x",
          pedidoId: "a",
          numero: 2,
          ano: 2026,
          estado: "assinado",
          conclusao: "com_ressalvas",
          assinatura: { nome: "Paulo Bezerra", oab: "CE 12345", qualificacao: "efetivo", em: "2026-09-30T12:00:00Z" },
          substituido: false,
        },
        { id: "y", pedidoId: "a", numero: 1, ano: 2026, estado: "assinado", conclusao: "favoravel", assinatura: null, substituido: true },
      ],
    });
    expect(linhas).toEqual([
      "Pedido em aberto desde 29/09/2026: Competência",
      "Parecer jurídico nº 2/2026 (com ressalvas), assinado por Paulo Bezerra",
    ]);
    expect(linhasDaSituacaoJuridica({ pedidosAbertos: [], pareceres: [] })).toEqual([]);
  });
});
