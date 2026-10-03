import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, renderHook, waitFor } from "@testing-library/react";
import {
  buscarContasDaProposicao,
  enviarDocumento,
  mensagemDeErroContas,
  pedirContas,
  useContasDaProposicao,
  usePrestacao,
} from "./use-contas";

type Chamada = { url: string; metodo: string; init?: RequestInit };
type Resp = { status?: number; corpo?: unknown; lanca?: boolean };

function mockar(rotas: Record<string, Resp>) {
  const chamadas: Chamada[] = [];
  global.fetch = vi.fn(async (url: string, init?: RequestInit) => {
    const c = { url: String(url), metodo: init?.method ?? "GET", init };
    chamadas.push(c);
    const r = rotas[`${c.metodo} ${c.url}`];
    if (!r) return { ok: false, status: 404, json: async () => ({}) } as Response;
    if (r.lanca) throw new TypeError("rede");
    const status = r.status ?? 200;
    return { ok: status < 300, status, json: async () => r.corpo ?? {} } as Response;
  }) as unknown as typeof fetch;
  return chamadas;
}

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

const prestacaoFio = {
  id: "pc1", tipo: "governo_prefeito", exercicio: 2024, responsavel: "José Sarto", estado: "pronta_para_pauta", "recebida-em": "2026-10-02",
  quorum: { "base-membros": 21, "necessarios-para-rejeitar": 14 }, pautavel: true, documentos: [],
};

describe("pedirContas", () => {
  it("cada falha vira frase: recusa por ação, motivo do servidor no 409, suspensão, rede, forma", async () => {
    mockar({
      "POST /api/a": { status: 403 },
      "POST /api/b": { status: 409, corpo: { erro: "o prazo de defesa vai até 20/10/2026" } },
      "POST /api/c": { status: 423 },
      "GET /api/d": { lanca: true },
      "GET /api/e": { corpo: { nada: true } },
    });
    expect(await pedirContas("t", "/api/a", "registrar", { method: "POST", corpo: {} })).toMatchObject({ status: 403, mensagem: "Só a secretaria registra a prestação de contas." });
    expect(await pedirContas("t", "/api/b", "notificar", { method: "POST", corpo: {} })).toMatchObject({ status: 409, mensagem: "O prazo de defesa vai até 20/10/2026." });
    expect(await pedirContas("t", "/api/c", "documento", { method: "POST", corpo: {} })).toMatchObject({ mensagem: expect.stringMatching(/acesso restrito/) });
    expect(await pedirContas("t", "/api/d", "lista")).toMatchObject({ status: 0, mensagem: expect.stringMatching(/Sem conexão/) });
    expect(await pedirContas("t", "/api/e", "lista", {}, () => false)).toMatchObject({ ok: false, status: 500 });
    expect(mensagemDeErroContas(409, "registrar")).toMatch(/Já existe uma prestação deste tipo/);
  });

  it("documento: multipart com o campo 'arquivo', sem Content-Type, na rota com ?tipo=", async () => {
    const c = mockar({ "POST /api/contas/pc1/documentos?tipo=relatorio_tce": { status: 201 } });
    const r = await enviarDocumento("tk", "pc1", "relatorio_tce", new File(["x"], "rel.pdf", { type: "application/pdf" }));
    expect(r.ok).toBe(true);
    expect(((c[0].init?.body as FormData).get("arquivo") as File).name).toBe("rel.pdf");
    expect(new Headers(c[0].init?.headers).get("Content-Type")).toBeNull();
  });
});

describe("a matéria é de contas?", () => {
  it("200 → a prestação camelizada; 404 → null (matéria comum); outra falha → erro", async () => {
    mockar({ "GET /api/contas-da-proposicao/p1": { corpo: prestacaoFio }, "GET /api/contas-da-proposicao/p3": { status: 500 } });
    const r1 = await buscarContasDaProposicao("tk", "p1");
    expect(r1.ok && r1.dado?.quorum).toEqual({ baseMembros: 21, necessariosParaRejeitar: 14 });
    expect(await buscarContasDaProposicao("tk", "p2")).toEqual({ ok: true, dado: null });
    expect((await buscarContasDaProposicao("tk", "p3")).ok).toBe(false);
  });

  it("o hook: nenhuma → carregando → contas | comum", async () => {
    mockar({ "GET /api/contas-da-proposicao/p1": { corpo: prestacaoFio } });
    const { result, rerender } = renderHook(({ id }) => useContasDaProposicao("tk", id), { initialProps: { id: null as string | null } });
    expect(result.current.fase).toBe("nenhuma");
    rerender({ id: "p1" });
    expect(result.current.fase).toBe("carregando");
    await waitFor(() => expect(result.current.fase).toBe("contas"));
    rerender({ id: "p2" });
    await waitFor(() => expect(result.current.fase).toBe("comum"));
  });
});

describe("usePrestacao", () => {
  it("carrega e aplica o acerto do fio (pautável ausente = false)", async () => {
    const semPautavel: Record<string, unknown> = { ...prestacaoFio };
    delete semPautavel.pautavel;
    mockar({ "GET /api/contas/pc1": { corpo: semPautavel } });
    const { result } = renderHook(() => usePrestacao("tk", "pc1"));
    await waitFor(() => expect(result.current.estado.fase).toBe("pronto"));
    const e = result.current.estado;
    expect(e.fase === "pronto" && e.dado.pautavel).toBe(false);
  });
});
