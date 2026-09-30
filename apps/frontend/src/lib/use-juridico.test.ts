import { afterEach, describe, expect, it, vi } from "vitest";
import { act, renderHook, waitFor } from "@testing-library/react";
import {
  assinarParecer,
  cancelarPedido,
  criarPedido,
  pedirJuridico,
  salvarParametrosParecerJuridico,
  salvarRascunho,
  substituirParecer,
  usarNotaComoRascunho,
  useParametrosParecerJuridico,
  usePedidoJuridico,
  usePedidosJuridicos,
} from "./use-juridico";

type Chamada = { url: string; method: string; body: unknown; auth: string | null };

function mockar(resposta: (c: Chamada) => { status: number; corpo?: unknown } | "rede") {
  const chamadas: Chamada[] = [];
  global.fetch = vi.fn(async (url: string, init?: RequestInit) => {
    const c: Chamada = {
      url: String(url),
      method: init?.method ?? "GET",
      body: init?.body ? JSON.parse(String(init.body)) : undefined,
      auth: new Headers(init?.headers).get("Authorization"),
    };
    chamadas.push(c);
    const r = resposta(c);
    if (r === "rede") throw new TypeError("network");
    return { ok: r.status < 300, status: r.status, json: async () => r.corpo ?? {} } as Response;
  }) as unknown as typeof fetch;
  return chamadas;
}

const PEDIDO = { id: "ped1", proposicao: null, assunto: "Decoro", prazo: null, estado: "pendente", "pedido-por": "Rita", "em-nome-de": null, origem: "secretaria", "criado-em": "2026-09-29T13:00:00Z", parecer: null };

afterEach(() => vi.restoreAllMocks());

describe("pedirJuridico — cada status vira frase honesta", () => {
  it("ok: cameliza as chaves do fio", async () => {
    mockar(() => ({ status: 200, corpo: PEDIDO }));
    const r = await pedirJuridico<{ pedidoPor: string; emNomeDe: null }>("tk", "/api/x", "abrir");
    expect(r.ok && r.dado.pedidoPor).toBe("Rita");
  });

  it.each([
    [401, /sessão expirou/],
    [403, /jurídico e a secretaria/],
    [404, /Não encontramos este pedido/],
    [409, /Conflito/],
    [400, /Confira os campos/],
    [500, /Não foi possível concluir/],
  ])("status %i", async (status, esperado) => {
    mockar(() => ({ status, corpo: {} }));
    const r = await pedirJuridico("tk", "/api/x", status === 409 ? "listar" : status === 400 ? "pedir" : "abrir");
    expect(r.ok).toBe(false);
    if (!r.ok) {
      expect(r.status).toBe(status);
      expect(r.mensagem).toMatch(esperado);
    }
  });

  it("falha de rede: status 0 e a frase de 'nada foi gravado'", async () => {
    mockar(() => "rede");
    const r = await pedirJuridico("tk", "/api/x", "salvar");
    expect(r).toMatchObject({ ok: false, status: 0 });
    expect(!r.ok && r.mensagem).toMatch(/Nada foi gravado/);
  });

  it("resposta ok mas de forma inesperada é erro, nunca dado", async () => {
    mockar(() => ({ status: 200, corpo: { outra: "coisa" } }));
    const r = await pedirJuridico("tk", "/api/x", "abrir", {}, (d) => Array.isArray((d as { pedidos?: unknown }).pedidos));
    expect(r.ok).toBe(false);
  });

  it("sem credencial no modo dev: 401 sem chamar a rede", async () => {
    const chamadas = mockar(() => ({ status: 200, corpo: PEDIDO }));
    const r = await pedirJuridico(null, "/api/x", "abrir");
    expect(r).toMatchObject({ ok: false, status: 401 });
    expect(chamadas).toHaveLength(0);
  });
});

describe("mutações — rota, método, corpo e token", () => {
  it("criar pedido: POST com chaves do fio", async () => {
    const c = mockar(() => ({ status: 201, corpo: PEDIDO }));
    const r = await criarPedido("tk", { assunto: "Decoro do vereador", prazo: "2026-10-15", emNomeDe: "Presidência" }, "p1");
    expect(r.ok).toBe(true);
    expect(c[0]).toMatchObject({ url: "/api/legislativo/pedidos-parecer-juridico", method: "POST", auth: "Bearer tk" });
    expect(c[0].body).toEqual({ "proposicao-id": "p1", assunto: "Decoro do vereador", prazo: "2026-10-15", "em-nome-de": "Presidência" });
  });

  it("cancelar, assinar e substituir: POST {} nas rotas certas; salvar: PUT com o texto", async () => {
    const c = mockar(() => ({ status: 200, corpo: PEDIDO }));
    await cancelarPedido("tk", "ped 1");
    await assinarParecer("tk", "ped1");
    await substituirParecer("tk", "ped1");
    await salvarRascunho("tk", "ped1", { relatorio: "R", fundamentacao: "F", conclusao: "" });
    expect(c.map((x) => `${x.method} ${x.url}`)).toEqual([
      "POST /api/legislativo/pedidos-parecer-juridico/ped%201/cancelamento",
      "POST /api/legislativo/pedidos-parecer-juridico/ped1/parecer/assinatura",
      "POST /api/legislativo/pedidos-parecer-juridico/ped1/parecer/substituicao",
      "PUT /api/legislativo/pedidos-parecer-juridico/ped1/parecer",
    ]);
    expect(c[0].body).toEqual({});
    expect(c[3].body).toEqual({ relatorio: "R", fundamentacao: "F" });
  });

  it("assinar: 403 explica a falta de qualificação e OAB; 400 mostra o que o servidor pediu", async () => {
    mockar(() => ({ status: 403 }));
    const a = await assinarParecer("tk", "ped1");
    expect(!a.ok && a.mensagem).toMatch(/qualificação e a OAB/);
    mockar(() => ({ status: 400, corpo: { erro: "falta a conclusão" } }));
    const b = await assinarParecer("tk", "ped1");
    expect(!b.ok && b.mensagem).toBe("falta a conclusão");
  });

  it("cancelar: 409 diz que o pedido já foi atendido", async () => {
    mockar(() => ({ status: 409 }));
    const r = await cancelarPedido("tk", "ped1");
    expect(!r.ok && r.mensagem).toMatch(/já foi atendido/);
  });
});

describe("hooks de leitura", () => {
  it("a fila busca por estado e troca de aba mostra 'carregando' até chegar a nova", async () => {
    const c = mockar((x) => ({ status: 200, corpo: { pedidos: x.url.includes("atendido") ? [] : [PEDIDO] } }));
    const { result, rerender } = renderHook(({ e }: { e: "pendente" | "atendido" }) => usePedidosJuridicos("tk", e), { initialProps: { e: "pendente" } });
    await waitFor(() => expect(result.current.estado.fase).toBe("pronto"));
    rerender({ e: "atendido" });
    expect(result.current.estado.fase).toBe("carregando");
    await waitFor(() => expect(result.current.estado.fase).toBe("pronto"));
    expect(c.map((x) => x.url)).toEqual([
      "/api/legislativo/pedidos-parecer-juridico?estado=pendente",
      "/api/legislativo/pedidos-parecer-juridico?estado=atendido",
    ]);
  });

  it("falha da fila vira erro com a frase — nunca lista vazia", async () => {
    mockar(() => ({ status: 500 }));
    const { result } = renderHook(() => usePedidosJuridicos("tk", "pendente"));
    await waitFor(() => expect(result.current.estado.fase).toBe("erro"));
    expect(result.current.estado).toMatchObject({ fase: "erro", status: 500 });
  });

  it("recarregar refaz a busca SEM piscar (mantém o que está na tela)", async () => {
    let n = 0;
    const c = mockar(() => ({ status: 200, corpo: { pedidos: n++ === 0 ? [PEDIDO] : [PEDIDO, { ...PEDIDO, id: "ped2" }] } }));
    const { result } = renderHook(() => usePedidosJuridicos("tk", "pendente"));
    await waitFor(() => expect(result.current.estado.fase).toBe("pronto"));
    act(() => result.current.recarregar());
    expect(result.current.estado.fase).toBe("pronto");
    await waitFor(() => expect(c.length).toBe(2));
    await waitFor(() => {
      const e = result.current.estado;
      expect(e.fase === "pronto" && e.dado.pedidos.length).toBe(2);
    });
  });

  it("um pedido: 404 vira erro com a frase de 'não encontramos'", async () => {
    mockar(() => ({ status: 404 }));
    const { result } = renderHook(() => usePedidoJuridico("tk", "nao-existe"));
    await waitFor(() => expect(result.current.estado.fase).toBe("erro"));
    const e = result.current.estado;
    expect(e.fase === "erro" && e.mensagem).toMatch(/Não encontramos este pedido/);
  });
});

describe("ADR-0019 fatia 2a — usar a nota como rascunho e o parâmetro do portal", () => {
  it("usarNotaComoRascunho: POST na rota da nota, devolve o pedido com o rascunho", async () => {
    const c = mockar(() => ({ status: 201, corpo: { ...PEDIDO, origem: "nota_tecnica", parecer: { id: "pj1", estado: "rascunho", "origem-rascunho": "nota_tecnica", substituido: false } } }));
    const r = await usarNotaComoRascunho("tk", "nota 1");
    expect(c[0]).toMatchObject({ url: "/api/legislativo/notas-tecnicas/nota%201/rascunho-juridico", method: "POST", auth: "Bearer tk" });
    expect(r.ok && r.dado.origem).toBe("nota_tecnica");
    expect(r.ok && r.dado.parecer?.origemRascunho).toBe("nota_tecnica");
  });

  it.each([
    [404, /Não encontramos esta nota técnica/],
    [409, /Abra o pedido na fila/],
    [403, /Só o jurídico da Casa/],
  ])("usarNotaComoRascunho: %i vira a frase da tela", async (status, frase) => {
    mockar(() => ({ status, corpo: { erro: "interno" } }));
    const r = await usarNotaComoRascunho("tk", "n1");
    expect(!r.ok && r.status).toBe(status);
    expect(!r.ok && r.mensagem).toMatch(frase);
  });

  it("sem sessão nem chama o servidor", async () => {
    const c = mockar(() => ({ status: 201, corpo: PEDIDO }));
    const r = await usarNotaComoRascunho(null, "n1");
    expect(c).toHaveLength(0);
    expect(!r.ok && r.status).toBe(401);
  });

  it("o parâmetro: lê e salva com a chave do fio", async () => {
    const c = mockar((x) => ({ status: 200, corpo: { "publicar-ao-assinar": x.method === "PUT" } }));
    const { result } = renderHook(() => useParametrosParecerJuridico("tk"));
    await waitFor(() => expect(result.current.estado.fase).toBe("pronto"));
    expect(c[0]).toMatchObject({ url: "/api/legislativo/parametros-parecer-juridico", method: "GET" });
    const r = await salvarParametrosParecerJuridico("tk", true);
    expect(c[1]).toMatchObject({ url: "/api/legislativo/parametros-parecer-juridico", method: "PUT", body: { "publicar-ao-assinar": true } });
    expect(r.ok && r.dado.publicarAoAssinar).toBe(true);
  });

  it("o parâmetro: resposta sem o campo é erro, não 'desligado' de mentira", async () => {
    mockar(() => ({ status: 200, corpo: {} }));
    const r = await salvarParametrosParecerJuridico("tk", false);
    expect(r.ok).toBe(false);
  });
});
