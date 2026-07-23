import { afterEach, describe, expect, it, vi } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { usePerfilVereador } from "./use-perfil-vereador";

// Onda E fatia 2 (Task 5) — hook cliente do perfil público do vereador: busca
// GET /api/portal/casa/{ente}/vereadores/{vereadorId}, mesmo DESVIO de SSR já documentado em
// use-ficha.ts/use-materias.ts (fetch relativo não resolve em Server Component; o rewrite same-origin de
// next.config.ts só existe para requests do browser).
//
// UMA chamada só, e é correto que a página inteira dependa dela: o perfil é um recurso único — não há
// "degradação por seção" a fazer. 404 e falha de rede colapsam no mesmo `null` de `buscarPublico`, e a
// borda NÃO PODE afirmar qual dos dois ocorreu: o 404 do backend é fail-closed e colapsa deliberadamente
// "não existe" com "é de outra Casa" (separá-los vazaria filiação cross-Casa numa rota anônima).
//
// Fixtures em kebab-case de propósito: é o dialeto REAL do wire — quem cameliza é `buscarPublico`, e é
// exatamente essa costura que estes testes precisam exercitar (um `undefined` na tela é bug de boundary,
// não de backend).

const perfilWire = {
  "vereador-id": "v1",
  "nome-parlamentar": "Helena Past",
  "nome-civil": "Helena Pastore Matos",
  legislatura: { numero: 19, "ano-inicio": 2025, "ano-fim": 2028 },
  "cargo-mesa": null,
  comissoes: [],
  materias: [],
  "materias-total": 0,
  "normas-de-autoria": 0,
  votos: [],
  "votos-total": 0,
  presenca: {
    "sessoes-presente": 8,
    "sessoes-com-chamada": 12,
    "janela-de-exercicio-conhecida": true,
    "janela-anterior-a-projecao": false,
  },
  "acervo-com-elo-de-autoria-desde": "2026-07-20",
  "presenca-projetada-desde": "2026-07-20",
};

// A assinatura genérica é explícita para que `mock.calls[0][0]` seja a URL TIPADA — o teste da rota
// precisa ler o argumento, e um `vi.fn(async () => ...)` sem parâmetros tipa as chamadas como tupla vazia.
function mockFetch(resposta: { ok: boolean; json?: () => Promise<unknown> }) {
  const f = vi.fn<(url: string) => Promise<{ ok: boolean; json: () => Promise<unknown> }>>(
    async () => ({ ok: resposta.ok, json: resposta.json ?? (async () => ({})) }),
  );
  global.fetch = f as unknown as typeof fetch;
  return f;
}

describe("usePerfilVereador", () => {
  afterEach(() => vi.restoreAllMocks());

  it("200 -> estado 'pronto' com o perfil camelizado (kebab do wire não vaza)", async () => {
    mockFetch({ ok: true, json: async () => perfilWire });

    const { result } = renderHook(() => usePerfilVereador("fortaleza", "v1"));
    await waitFor(() => expect(result.current.estado).toBe("pronto"));
    expect(result.current.perfil?.nomeParlamentar).toBe("Helena Past");
    expect(result.current.perfil?.presenca.janelaDeExercicioConhecida).toBe(true);
    expect(result.current.perfil?.presenca.sessoesComChamada).toBe(12);
    expect(result.current.perfil?.acervoComEloDeAutoriaDesde).toBe("2026-07-20");
    expect(result.current.perfil?.legislatura?.anoFim).toBe(2028);
  });

  it("nomeParlamentar null NUNCA vira erro: é apelido ausente, NULL de primeira classe", async () => {
    mockFetch({ ok: true, json: async () => ({ ...perfilWire, "nome-parlamentar": null }) });

    const { result } = renderHook(() => usePerfilVereador("fortaleza", "v1"));
    await waitFor(() => expect(result.current.estado).toBe("pronto"));
    expect(result.current.perfil?.nomeParlamentar).toBeNull();
    expect(result.current.perfil?.nomeCivil).toBe("Helena Pastore Matos");
  });

  it("404 -> estado 'erro' e perfil null (buscarPublico colapsa 404 e rede no mesmo null)", async () => {
    mockFetch({ ok: false });

    const { result } = renderHook(() => usePerfilVereador("fortaleza", "v1"));
    await waitFor(() => expect(result.current.estado).toBe("erro"));
    expect(result.current.perfil).toBeNull();
  });

  it("falha de rede -> estado 'erro', sem lançar", async () => {
    global.fetch = vi.fn(async () => {
      throw new Error("network down");
    }) as unknown as typeof fetch;

    const { result } = renderHook(() => usePerfilVereador("fortaleza", "v1"));
    await waitFor(() => expect(result.current.estado).toBe("erro"));
    expect(result.current.perfil).toBeNull();
  });

  it("a URL chamada é /api/portal/casa/{ente}/vereadores/{id}", async () => {
    const f = mockFetch({ ok: true, json: async () => perfilWire });

    renderHook(() => usePerfilVereador("fortaleza", "v1"));
    await waitFor(() => expect(f).toHaveBeenCalledTimes(1));
    expect(f.mock.calls[0][0]).toBe("/api/portal/casa/fortaleza/vereadores/v1");
  });

  it("troca de vereadorId (mesmo ente) -> reseta pra 'carregando' no render, sem mostrar o perfil anterior", async () => {
    mockFetch({ ok: true, json: async () => perfilWire });

    const { result, rerender } = renderHook(({ ente, id }) => usePerfilVereador(ente, id), {
      initialProps: { ente: "fortaleza", id: "v1" },
    });
    await waitFor(() => expect(result.current.estado).toBe("pronto"));

    let liberar: () => void = () => {};
    const pendente = new Promise<{ ok: boolean; json: () => Promise<unknown> }>((res) => {
      liberar = () => res({ ok: true, json: async () => perfilWire });
    });
    global.fetch = vi.fn(() => pendente) as unknown as typeof fetch;

    rerender({ ente: "fortaleza", id: "v2" });
    expect(result.current.estado).toBe("carregando");
    expect(result.current.perfil).toBeNull();

    liberar();
    await waitFor(() => expect(result.current.estado).toBe("pronto"));
  });

  it("troca de ente -> mesmo reset (dois estados-anterior independentes, chave concatenada colidiria)", async () => {
    mockFetch({ ok: true, json: async () => perfilWire });

    const { result, rerender } = renderHook(({ ente, id }) => usePerfilVereador(ente, id), {
      initialProps: { ente: "fortaleza", id: "v1" },
    });
    await waitFor(() => expect(result.current.estado).toBe("pronto"));

    let liberar: () => void = () => {};
    const pendente = new Promise<{ ok: boolean; json: () => Promise<unknown> }>((res) => {
      liberar = () => res({ ok: true, json: async () => perfilWire });
    });
    global.fetch = vi.fn(() => pendente) as unknown as typeof fetch;

    rerender({ ente: "sobral", id: "v1" });
    expect(result.current.estado).toBe("carregando");
    expect(result.current.perfil).toBeNull();

    liberar();
    await waitFor(() => expect(result.current.estado).toBe("pronto"));
  });

  it("resposta fora de ordem: a do vereador ANTERIOR chega depois e NÃO sobrescreve o perfil atual", async () => {
    // Os dois testes de troca de parâmetro acima só provam o RESET SÍNCRONO do render — em ambos a
    // primeira resposta já resolveu antes da troca. A guarda `if (!vivo) return` do efeito, que existe
    // para impedir escrita fora de ordem, não tinha teste nenhum: removê-la passava verde. É ela que
    // impede "presença, autoria e votos de v1 exibidos sob a URL e o nome de v2".
    // (Hoje a rota pública não tem navegação soft — todo link do portal é âncora crua, então a troca de
    // parâmetro sem remontagem não ocorre em produção. O teste guarda a GUARDA, para que ela não seja
    // apagada por parecer supérflua no dia em que um <Link> entrar aqui.)
    let liberarV1: () => void = () => {};
    const respostaV1 = new Promise<{ ok: boolean; json: () => Promise<unknown> }>((res) => {
      liberarV1 = () =>
        res({ ok: true, json: async () => ({ ...perfilWire, "nome-parlamentar": "ANTIGO V1" }) });
    });
    let chamada = 0;
    global.fetch = vi.fn(() => {
      chamada += 1;
      return chamada === 1
        ? respostaV1
        : Promise.resolve({
            ok: true,
            json: async () => ({ ...perfilWire, "nome-parlamentar": "NOVO V2" }),
          });
    }) as unknown as typeof fetch;

    const { result, rerender } = renderHook(({ id }) => usePerfilVereador("fortaleza", id), {
      initialProps: { id: "v1" },
    });
    rerender({ id: "v2" }); // troca ANTES de v1 responder
    await waitFor(() => expect(result.current.perfil?.nomeParlamentar).toBe("NOVO V2"));

    liberarV1(); // a resposta lenta do vereador anterior chega agora
    await new Promise((r) => setTimeout(r, 0));
    expect(result.current.perfil?.nomeParlamentar).toBe("NOVO V2");
    expect(result.current.estado).toBe("pronto");
  });
});
