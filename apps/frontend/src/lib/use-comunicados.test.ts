import { afterEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, renderHook, waitFor } from "@testing-library/react";
import {
  avisarCaixaMudou,
  enviarAnexo,
  enviarComunicado,
  mensagemDeErroComunicacao,
  pedirComunicacao,
  registrarCiencia,
  useAvisosDoSistema,
  useCaixaDeComunicados,
  useComunicado,
  useContagemDaCaixa,
  useEnviados,
  useLeituraDoComunicado,
} from "./use-comunicados";

type Chamada = { url: string; metodo: string; init?: RequestInit };
type Resp = { status?: number; corpo?: unknown; lanca?: boolean };

function mockar(rotas: Record<string, Resp | ((c: Chamada) => Resp)>) {
  const chamadas: Chamada[] = [];
  global.fetch = vi.fn(async (url: string, init?: RequestInit) => {
    const c = { url: String(url), metodo: init?.method ?? "GET", init };
    chamadas.push(c);
    const r0 = rotas[`${c.metodo} ${c.url}`];
    const r = typeof r0 === "function" ? r0(c) : r0;
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

const caixaFio = { itens: [{ id: "c1", protocolo: "COM-2026-000001", assunto: "A", remetente: { "identidade-id": "i9", nome: "Rita" }, via: "direto", "enviado-em": "2026-10-02T14:00:00Z",
  "exige-ciencia": true, "ciencia-ate": null, vencido: false, "recebido-em": null, "lido-em": null, "ciente-em": null }],
  "nao-lidos": 1, "pendentes-ciencia": 1, "proxima-ciencia-ate": null };

describe("pedirComunicacao", () => {
  it("JSON: Content-Type, corpo e o token de dev no header", async () => {
    const c = mockar({ "POST /api/x": { corpo: { "ciente-em": "t" } } });
    const r = await pedirComunicacao("tok", "/api/x", "ciencia", { method: "POST", corpo: { a: 1 } });
    expect(r).toEqual({ ok: true, dado: { cienteEm: "t" } });
    const h = new Headers(c[0].init?.headers);
    expect(h.get("Content-Type")).toBe("application/json");
    expect(h.get("Authorization")).toBe("Bearer tok");
    expect(c[0].init?.body).toBe('{"a":1}');
  });

  it("arquivo: multipart com o campo 'arquivo' e SEM Content-Type (o navegador põe o boundary)", async () => {
    const c = mockar({ "POST /api/comunicados/c1/anexos": { status: 201, corpo: {} } });
    const arquivo = new File(["oi"], "pauta.pdf", { type: "application/pdf" });
    const r = await enviarAnexo("tok", "c1", arquivo);
    expect(r.ok).toBe(true);
    const corpo = c[0].init?.body as FormData;
    expect(corpo).toBeInstanceOf(FormData);
    expect((corpo.get("arquivo") as File).name).toBe("pauta.pdf");
    expect(new Headers(c[0].init?.headers).get("Content-Type")).toBeNull();
  });

  it("cada falha vira frase: recusa por ação, erro do servidor no 422, suspensão, rede, corpo fora de forma", async () => {
    mockar({
      "GET /api/a": { status: 403 },
      "POST /api/b": { status: 422, corpo: { erro: "a lista de destinatários resolveu vazia" } },
      "POST /api/c": { status: 423, corpo: { erro: "acesso restrito" } },
      "GET /api/d": { lanca: true },
      "GET /api/e": { corpo: { nada: true } },
    });
    expect(await pedirComunicacao("t", "/api/a", "leitura")).toMatchObject({ ok: false, status: 403, mensagem: expect.stringMatching(/só é visível para quem o enviou/) });
    expect(await pedirComunicacao("t", "/api/b", "enviar", { method: "POST", corpo: {} })).toMatchObject({ status: 422, mensagem: "A lista de destinatários resolveu vazia." });
    expect(await pedirComunicacao("t", "/api/c", "enviar", { method: "POST", corpo: {} })).toMatchObject({ status: 423, mensagem: expect.stringMatching(/acesso restrito: enviar comunicados está suspenso/) });
    expect(await pedirComunicacao("t", "/api/d", "caixa")).toMatchObject({ status: 0, mensagem: expect.stringMatching(/Sem conexão/) });
    expect(await pedirComunicacao("t", "/api/e", "caixa", {}, () => false)).toMatchObject({ ok: false, status: 500 });
  });

  it("modo dev sem token: não busca, diz que a sessão expirou", async () => {
    const c = mockar({});
    expect(await pedirComunicacao(null, "/api/x", "caixa")).toMatchObject({ ok: false, status: 401 });
    expect(c).toHaveLength(0);
  });

  it("a tabela de frases cobre o 409 do setor e o 410 da Casa encerrada", () => {
    expect(mensagemDeErroComunicacao(409, "criar-setor")).toBe("Já existe um setor com esse nome nesta Casa.");
    expect(mensagemDeErroComunicacao(410, "caixa")).toMatch(/encerrou o uso do sistema/);
    expect(mensagemDeErroComunicacao(422, "enviar")).toMatch(/lista de destinatários ficou vazia/);
    expect(mensagemDeErroComunicacao(413, "anexar")).toMatch(/10 MB/);
  });
});

describe("as leituras", () => {
  it("a caixa chega camelizada, e recarregar refaz sem piscar", async () => {
    const c = mockar({ "GET /api/meu/comunicados": { corpo: caixaFio } });
    const { result } = renderHook(() => useCaixaDeComunicados("tok"));
    expect(result.current.estado.fase).toBe("carregando");
    await waitFor(() => expect(result.current.estado.fase).toBe("pronto"));
    const e = result.current.estado as { fase: "pronto"; dado: { naoLidos: number; itens: { remetenteNome: string }[] } };
    expect(e.dado.naoLidos).toBe(1);
    expect(e.dado.itens[0].remetenteNome).toBe("Rita");
    act(() => result.current.recarregar());
    expect(result.current.estado.fase).toBe("pronto"); // não volta a "carregando" enquanto refaz
    await waitFor(() => expect(c).toHaveLength(2));
  });

  it("caixa fora de forma vira erro, nunca meio-dado", async () => {
    mockar({ "GET /api/meu/comunicados": { corpo: { itens: "nao" } } });
    const { result } = renderHook(() => useCaixaDeComunicados("tok"));
    await waitFor(() => expect(result.current.estado.fase).toBe("erro"));
  });

  it("avisos: 403 é caixa de avisos vazia; 500 é erro", async () => {
    mockar({ "GET /api/meu/notificacoes": { status: 403 } });
    const a = renderHook(() => useAvisosDoSistema("tok"));
    await waitFor(() => expect(a.result.current.estado).toEqual({ fase: "pronto", dado: { notificacoes: [], naoLidas: 0, notificacoesTotal: 0 } }));
    cleanup();
    mockar({ "GET /api/meu/notificacoes": { status: 500 } });
    const b = renderHook(() => useAvisosDoSistema("tok"));
    await waitFor(() => expect(b.result.current.estado.fase).toBe("erro"));
  });

  it("um comunicado e a leitura dele pelas rotas do contrato; id nulo não busca", async () => {
    const c = mockar({
      "GET /api/comunicados/c%201": { corpo: { id: "c 1", protocolo: "P", assunto: "A", destinos: [] } },
      "GET /api/comunicados/c%201/leitura": { corpo: { comunicado: {}, totais: {}, linhas: [] } },
    });
    const um = renderHook(() => useComunicado("tok", "c 1"));
    const lei = renderHook(() => useLeituraDoComunicado("tok", "c 1"));
    const nada = renderHook(() => useLeituraDoComunicado("tok", null));
    await waitFor(() => expect(um.result.current.estado.fase).toBe("pronto"));
    await waitFor(() => expect(lei.result.current.estado.fase).toBe("pronto"));
    expect(nada.result.current.estado.fase).toBe("carregando");
    expect(c.map((x) => x.url).sort()).toEqual(["/api/comunicados/c%201", "/api/comunicados/c%201/leitura"]);
  });

  it("enviados: o escopo da Casa vai na querystring", async () => {
    const c = mockar({
      "GET /api/meu/comunicados/enviados": { corpo: { itens: [] } },
      "GET /api/meu/comunicados/enviados?escopo=casa": { corpo: { itens: [] } },
    });
    const { result, rerender } = renderHook(({ e }: { e: "meus" | "casa" }) => useEnviados("tok", e), { initialProps: { e: "meus" } });
    await waitFor(() => expect(result.current.estado.fase).toBe("pronto"));
    rerender({ e: "casa" });
    await waitFor(() => expect(c.map((x) => x.url)).toContain("/api/meu/comunicados/enviados?escopo=casa"));
  });
});

describe("as escritas", () => {
  it("enviar: POST /comunicados com o corpo kebab; o 201 chega camelizado", async () => {
    const c = mockar({
      "POST /api/comunicados": { status: 201, corpo: { comunicado: { id: "c1", protocolo: "COM-2026-000001", assunto: "A", destinos: [] }, destinatarios: 15, "sem-acesso": 1 } },
    });
    const r = await enviarComunicado("tok", {
      assunto: " A ", corpo: "B", exigeCiencia: false, cienciaAte: "2026-10-05T21:00:00Z", substituiId: null, objeto: null,
      destinos: [{ tipo: "setor", alvoId: "s1" }],
    });
    expect(r).toMatchObject({ ok: true, dado: { destinatarios: 15, semAcesso: 1 } });
    expect(JSON.parse(String(c[0].init?.body))).toEqual({
      assunto: "A", corpo: "B", "exige-ciencia": false, "ciencia-ate": null, "substitui-id": null, objeto: null,
      destinos: [{ tipo: "setor", "alvo-id": "s1" }],
    });
  });

  it("ciência: POST /comunicados/:id/ciencia devolve a marca", async () => {
    // o fio real: {id, protocolo, minhas-marcas}; `doFio.ciencia` expõe o `cienteEm`
    mockar({
      "POST /api/comunicados/c1/ciencia": {
        corpo: { id: "c1", protocolo: "COM-2026-000001", "minhas-marcas": { "recebido-em": "x", "lido-em": "x", "ciente-em": "2026-10-02T15:00:00Z", vencido: false } },
      },
    });
    expect(await registrarCiencia("tok", "c1")).toMatchObject({ ok: true, dado: { cienteEm: "2026-10-02T15:00:00Z" } });
  });
});

describe("useContagemDaCaixa — o número do topo", () => {
  it("soma comunicados e avisos não lidos e se refaz quando a caixa avisa", async () => {
    let n = 2;
    mockar({
      "GET /api/meu/comunicados/contagem": () => ({ corpo: { ...caixaFio, "nao-lidos": n } }),
      "GET /api/meu/notificacoes": { corpo: { notificacoes: [], "nao-lidas": 1, "notificacoes-total": 0 } },
    });
    const { result } = renderHook(() => useContagemDaCaixa("tok"));
    await waitFor(() => expect(result.current).toBe(3));
    n = 0;
    act(() => avisarCaixaMudou());
    await waitFor(() => expect(result.current).toBe(1));
  });

  it("nenhuma fonte respondendo: null (o topo não mostra número), nunca zero", async () => {
    mockar({});
    const { result } = renderHook(() => useContagemDaCaixa("tok"));
    await new Promise((r) => setTimeout(r, 20));
    expect(result.current).toBeNull();
  });
});
