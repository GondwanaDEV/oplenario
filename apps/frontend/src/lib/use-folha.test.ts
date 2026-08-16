import { afterEach, describe, expect, it, vi } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { useFolha } from "./use-folha";

// Teste do hook de IO da FOLHA (Etapa 5 fatia 6) — mirror de use-chamada.test.ts na disciplina de fixture:
// corpo CRU em kebab-case (é como o wire emite; `camelizarChaves` roda no hook), e mock de `global.fetch`
// (que `apiFetch` usa por baixo, sem precisar de `fetchImpl` — mesma convenção de use-chamada.test.ts).
//
// O QUE ESTE ARQUIVO EXISTE PARA IMPEDIR: uma tela que mostra "erro ao salvar" genérico pra qualquer um dos
// 4 códigos que as rotas da folha podem devolver (409 duas formas, 413, 503, 403, 404 duas formas) — cada
// um tem de virar a mensagem certa (`mensagemDeErroFolha`, já testado em isolamento em folha-vista.test.ts;
// aqui a prova é que o HOOK a invoca com o status/corpo/Retry-After corretos).

function folhaCrua(over: Record<string, unknown> = {}) {
  return {
    id: "f1",
    versao: 1,
    "spec-versao": "folha-sessao-v1",
    "html-hash": "sha256:aaaa",
    "pdf-hash": "sha256:bbbb",
    "gerada-por": "u1",
    "gerada-em": "2026-08-15T14:00:00Z",
    ...over,
  };
}

interface Opcoes {
  folhas?: Array<Record<string, unknown>>;
  respostas?: Record<string, (init?: RequestInit) => Response | Promise<Response>>;
}

function montarFetch(opts: Opcoes = {}) {
  const folhas = opts.folhas ?? [folhaCrua()];
  const f = vi.fn(async (url: string, init?: RequestInit) => {
    const metodo = (init?.method ?? "GET").toUpperCase();
    for (const [frag, fn] of Object.entries(opts.respostas ?? {})) {
      if (url.includes(frag)) return fn(init);
    }
    if (url.includes("/folhas") && metodo === "GET" && !/\/folhas\/\d/.test(url)) {
      return {
        ok: true,
        status: 200,
        json: async () => ({ "sessao-id": "s1", folhas }),
      } as Response;
    }
    return { ok: true, status: 200, json: async () => ({}) } as Response;
  });
  global.fetch = f as unknown as typeof fetch;
  return f;
}

afterEach(() => {
  vi.restoreAllMocks();
});

describe("useFolha — carga inicial", () => {
  it("busca a lista de versões e camelizacaso as chaves", async () => {
    montarFetch({ folhas: [folhaCrua({ versao: 2 }), folhaCrua({ versao: 1 })] });
    const { result } = renderHook(() => useFolha("s1", "tok"));
    await waitFor(() => expect(result.current.estado).toBe("pronto"));
    expect(result.current.versoes).toHaveLength(2);
    expect(result.current.versoes?.[0]).toMatchObject({ versao: 2, specVersao: "folha-sessao-v1", htmlHash: "sha256:aaaa" });
  });

  it("lista vazia (sessão nunca congelada) é estado PRONTO com versoes=[] — nunca erro", async () => {
    montarFetch({ folhas: [] });
    const { result } = renderHook(() => useFolha("s1", "tok"));
    await waitFor(() => expect(result.current.estado).toBe("pronto"));
    expect(result.current.versoes).toEqual([]);
  });

  it("falha na busca inicial (404 de sessão) vira estado erro com mensagem traduzida", async () => {
    montarFetch({
      respostas: {
        "/folhas": async () =>
          ({ ok: false, status: 404, headers: new Headers(), json: async () => ({ erro: "sessao nao encontrada" }) } as Response),
      },
    });
    const { result } = renderHook(() => useFolha("s1", "tok"));
    await waitFor(() => expect(result.current.estado).toBe("erro"));
    expect(result.current.erro).toMatch(/sessão não encontrada/i);
  });
});

describe("useFolha — gerar (POST)", () => {
  it("sucesso (201) devolve a folha e recarrega a lista", async () => {
    let listaChamada = 0;
    montarFetch({
      respostas: {
        "/folha": async (init) => {
          if ((init?.method ?? "GET").toUpperCase() === "POST") {
            return { ok: true, status: 201, json: async () => folhaCrua({ versao: 3 }) } as Response;
          }
          listaChamada++;
          return { ok: true, status: 200, json: async () => ({ "sessao-id": "s1", folhas: [folhaCrua({ versao: 3 })] }) } as Response;
        },
      },
    });
    const { result } = renderHook(() => useFolha("s1", "tok"));
    await waitFor(() => expect(result.current.estado).toBe("pronto"));

    const r = await result.current.gerar();
    expect(r.ok).toBe(true);
    if (r.ok) expect(r.folha.versao).toBe(3);
    expect(listaChamada).toBeGreaterThan(0);
  });

  it("409 de sessão aberta (D6) devolve erro com a mensagem traduzida — nunca lança", async () => {
    montarFetch({
      respostas: {
        "/folha": async (init) => {
          if ((init?.method ?? "GET").toUpperCase() === "POST") {
            return {
              ok: false, status: 409, headers: new Headers(),
              json: async () => ({ erro: "so' sessao FECHADA tem folha de presenca (D6)" }),
            } as Response;
          }
          return { ok: true, status: 200, json: async () => ({ "sessao-id": "s1", folhas: [] }) } as Response;
        },
      },
    });
    const { result } = renderHook(() => useFolha("s1", "tok"));
    await waitFor(() => expect(result.current.estado).toBe("pronto"));

    const r = await result.current.gerar();
    expect(r.ok).toBe(false);
    if (!r.ok) expect(r.erro).toMatch(/encerrada|fechada/i);
  });

  it("503 de renderizador saturado repassa o Retry-After na mensagem", async () => {
    montarFetch({
      respostas: {
        "/folha": async (init) => {
          if ((init?.method ?? "GET").toUpperCase() === "POST") {
            return {
              ok: false,
              status: 503,
              headers: new Headers({ "Retry-After": "5" }),
              json: async () => ({ erro: "renderizador de PDF ocupado — tente novamente em instantes" }),
            } as unknown as Response;
          }
          return { ok: true, status: 200, json: async () => ({ "sessao-id": "s1", folhas: [] }) } as Response;
        },
      },
    });
    const { result } = renderHook(() => useFolha("s1", "tok"));
    await waitFor(() => expect(result.current.estado).toBe("pronto"));

    const r = await result.current.gerar();
    expect(r.ok).toBe(false);
    if (!r.ok) expect(r.erro).toMatch(/5s/);
  });
});

describe("useFolha — buscarHtml", () => {
  it("sucesso devolve o HTML CRU (texto, nunca JSON.parse sobre ele)", async () => {
    montarFetch({
      respostas: {
        "/folhas/2": async () => ({ ok: true, status: 200, text: async () => "<html>folha v2</html>" } as unknown as Response),
      },
    });
    const { result } = renderHook(() => useFolha("s1", "tok"));
    await waitFor(() => expect(result.current.estado).toBe("pronto"));

    const r = await result.current.buscarHtml(2);
    expect(r.ok).toBe(true);
    if (r.ok) expect(r.html).toBe("<html>folha v2</html>");
  });

  it("404 de versão específica devolve mensagem distinta de 404 de sessão", async () => {
    montarFetch({
      respostas: {
        "/folhas/9": async () =>
          ({ ok: false, status: 404, headers: new Headers(), json: async () => ({ erro: "versao da folha nao encontrada" }) } as Response),
      },
    });
    const { result } = renderHook(() => useFolha("s1", "tok"));
    await waitFor(() => expect(result.current.estado).toBe("pronto"));

    const r = await result.current.buscarHtml(9);
    expect(r.ok).toBe(false);
    if (!r.ok) expect(r.erro).toMatch(/versão/i);
  });
});

describe("useFolha — baixarPdf", () => {
  it("sucesso devolve ok:true (o hook busca os bytes via apiFetch, não um <a href> cru)", async () => {
    const criarObjectURL = vi.fn(() => "blob:mock");
    const revogarObjectURL = vi.fn();
    vi.stubGlobal("URL", { ...URL, createObjectURL: criarObjectURL, revokeObjectURL: revogarObjectURL });
    montarFetch({
      respostas: {
        "/folhas/1/pdf": async () => ({ ok: true, status: 200, blob: async () => new Blob(["%PDF-1.4"]) } as unknown as Response),
      },
    });
    const { result } = renderHook(() => useFolha("s1", "tok"));
    await waitFor(() => expect(result.current.estado).toBe("pronto"));

    const r = await result.current.baixarPdf(1);
    expect(r.ok).toBe(true);
    expect(criarObjectURL).toHaveBeenCalled();
  });

  it("503 do renderizador devolve erro traduzido em vez de baixar um blob de erro JSON", async () => {
    montarFetch({
      respostas: {
        "/folhas/1/pdf": async () => ({
          ok: false,
          status: 503,
          headers: new Headers({ "Retry-After": "5" }),
          json: async () => ({ erro: "renderizador de PDF ocupado — tente novamente em instantes" }),
        } as unknown as Response),
      },
    });
    const { result } = renderHook(() => useFolha("s1", "tok"));
    await waitFor(() => expect(result.current.estado).toBe("pronto"));

    const r = await result.current.baixarPdf(1);
    expect(r.ok).toBe(false);
    if (!r.ok) expect(r.erro).toMatch(/5s/);
  });
});

// ---- correção da revisão adversarial da fatia 6 (achado 1) ----
// O tipo de retorno das três funções (`Promise<ResultadoGerar|ResultadoHtml|ResultadoDownload>`) é um
// CONTRATO: elas resolvem `{ok:false, erro}`, nunca rejeitam. A versão anterior só tratava `!r.ok` (erro
// HTTP com corpo) — `fetch` lança `TypeError` em falha de REDE (offline, DNS, proxy sem prazo), e essa
// rejeição atravessava o hook inteiro. Quem chamava (`page.tsx`) ficava com o `setCarregando(false)`
// pendurado depois do `await` que rejeitou, e a tela travava em "Carregando…"/"Gerando…" para sempre.
// Nenhum teste do commit exercitava `fetch` REJEITANDO — só respostas `ok:false` — por isso o gate verde
// não via este caminho.
describe("useFolha — falha de REDE (fetch REJEITA, não responde !ok)", () => {
  it("gerar: rejeição de rede vira {ok:false} com mensagem de rede — nunca propaga a rejeição", async () => {
    montarFetch({
      respostas: {
        "/folha": async (init) => {
          if ((init?.method ?? "GET").toUpperCase() === "POST") throw new TypeError("Failed to fetch");
          return { ok: true, status: 200, json: async () => ({ "sessao-id": "s1", folhas: [] }) } as Response;
        },
      },
    });
    const { result } = renderHook(() => useFolha("s1", "tok"));
    await waitFor(() => expect(result.current.estado).toBe("pronto"));

    const r = await result.current.gerar();
    expect(r.ok).toBe(false);
    if (!r.ok) expect(r.erro).toMatch(/conex|rede/i);
  });

  it("buscarHtml: rejeição de rede vira {ok:false} — nunca propaga a rejeição", async () => {
    montarFetch({
      respostas: {
        "/folhas/2": async () => {
          throw new TypeError("Failed to fetch");
        },
      },
    });
    const { result } = renderHook(() => useFolha("s1", "tok"));
    await waitFor(() => expect(result.current.estado).toBe("pronto"));

    const r = await result.current.buscarHtml(2);
    expect(r.ok).toBe(false);
    if (!r.ok) expect(r.erro).toMatch(/conex|rede/i);
  });

  it("baixarPdf: rejeição de rede vira {ok:false} — nunca propaga a rejeição", async () => {
    montarFetch({
      respostas: {
        "/folhas/1/pdf": async () => {
          throw new TypeError("Failed to fetch");
        },
      },
    });
    const { result } = renderHook(() => useFolha("s1", "tok"));
    await waitFor(() => expect(result.current.estado).toBe("pronto"));

    const r = await result.current.baixarPdf(1);
    expect(r.ok).toBe(false);
    if (!r.ok) expect(r.erro).toMatch(/conex|rede/i);
  });

  // GUARDA (não reprovava antes): o efeito de carga inicial JÁ tinha try/catch — este teste existe para
  // impedir que a disciplina se perca, não para provar a correção.
  it("GUARDA — carga inicial que rejeita já virava estado erro (comportamento preexistente)", async () => {
    montarFetch({
      respostas: {
        "/folhas": async () => {
          throw new TypeError("Failed to fetch");
        },
      },
    });
    const { result } = renderHook(() => useFolha("s1", "tok"));
    await waitFor(() => expect(result.current.estado).toBe("erro"));
    expect(result.current.erro).toBeTruthy();
  });
});
