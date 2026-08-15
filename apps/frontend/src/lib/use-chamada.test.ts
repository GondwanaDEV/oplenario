import { describe, expect, it, vi, afterEach } from "vitest";
import { renderHook, act, waitFor } from "@testing-library/react";
import { useChamada, mensagemDeErro } from "./use-chamada";

// Teste do hook de IO da CHAMADA (Etapa 3B fatia 2).
//
// O QUE ESTE ARQUIVO EXISTE PARA IMPEDIR: a Etapa 4 foi REPROVADA numa revisão adversarial com 21 achados
// (3 críticos) por uma raiz ÚNICA — fundir "base opaca do snapshot" com "delta do SSE". O conserto foi
// estrutural: o numerador vem SÓ do servidor, re-buscado. O teste `o frame NUNCA muta dados por incremento`
// abaixo é a asserção que faltava quando aquilo passou; os outros protegem os 3 gatilhos de re-hidratação,
// o piso de 3s e a degradação honesta.
//
// FIXTURES EM KEBAB-CASE, DE PROPÓSITO — é como o wire emite (`adapters/out`), e o hook chama
// `camelizarChaves`. Fixture em camelCase deixaria a suíte VERDE com o `camelizarChaves` removido e a tela
// imprimindo `undefined`: é a armadilha "fixture com vocabulário fictício" já catalogada neste repo, e a
// mesma razão pela qual `use-plenario.test.ts` também usa o corpo cru.

// ---------- fixtures (corpo CRU do wire) ----------

function linhaCrua(over: Record<string, unknown> = {}) {
  return {
    "vereador-id": "v1",
    nome: "Sergio Lopes",
    "nome-parlamentar": "Ver. Sérgio Lopes",
    partido: "PDT",
    "cargo-mesa": "presidente",
    estado: "ausente",
    "inconsistencia-cadastro": false,
    "sem-assento": false,
    desde: null,
    fonte: null,
    "registrado-em": null,
    justificativa: null,
    ...over,
  };
}

function chamadaCrua(over: { linhas?: unknown[]; presentesTotal?: number } = {}) {
  return {
    "sessao-id": "s1",
    "sessao-estado": "aberta",
    instante: "2026-05-21T17:03:00Z",
    "data-de-composicao": "2026-05-21",
    "composicao-resolvida-em": "2026-05-21T17:03:00Z",
    "sem-registro-de-presenca": false,
    linhas: over.linhas ?? [linhaCrua(), linhaCrua({ "vereador-id": "v2", "cargo-mesa": null })],
    quorum: {
      "presentes-plenario": over.presentesTotal ?? 0,
      "presentes-remoto": 0,
      "presentes-total": over.presentesTotal ?? 0,
      "membros-da-casa": 21,
      "presencas-fora-do-roster": 0,
    },
    "chamadas-conduzidas": [],
  };
}

const justificativasCruas = {
  "sessao-id": "s1",
  justificativas: [
    {
      id: "j1",
      "vereador-id": "v2",
      estado: "pendente",
      motivo: "Missão oficial",
      "decidido-por": null,
      "decidido-em": null,
      "lock-version": 3,
    },
  ],
};

/** Stream SSE controlável — o teste irmão (`use-plenario.test.ts`) PENDURA o stream; aqui preciso dirigi-lo
 * para exercitar frame, queda e reconexão. `ReadableStream` real, porque `consumirSse` usa `body.getReader()`. */
function sseControlado() {
  let ctrl!: ReadableStreamDefaultController<Uint8Array>;
  const body = new ReadableStream<Uint8Array>({ start: (c) => (ctrl = c) });
  const enc = new TextEncoder();
  return {
    body,
    enviar(evento: string, dados: unknown) {
      ctrl.enqueue(enc.encode(`event: ${evento}\ndata: ${JSON.stringify(dados)}\n\n`));
    },
    quebrar() {
      ctrl.error(new Error("stream caiu"));
    },
  };
}

interface Opcoes {
  chamadas?: Array<Record<string, unknown>>; // respostas sucessivas de GET /chamada
  chamadaFalha?: number[]; // índices (0-based) das buscas de /chamada que devem falhar
  sse?: ReturnType<typeof sseControlado> | "pendurado";
  respostas?: Record<string, () => Response | Promise<Response>>;
}

function montarFetch(opts: Opcoes = {}) {
  const corpos = opts.chamadas ?? [chamadaCrua()];
  let iChamada = 0;
  const falhas = new Set(opts.chamadaFalha ?? []);

  const f = vi.fn(async (url: string, init?: RequestInit) => {
    const metodo = (init?.method ?? "GET").toUpperCase();

    for (const [frag, fn] of Object.entries(opts.respostas ?? {})) {
      if (url.includes(frag) && !(frag === "/chamada" && metodo === "GET")) return fn();
    }

    if (url.includes("/plenario")) {
      if (opts.sse === "pendurado" || opts.sse === undefined) return new Promise<Response>(() => {});
      return { ok: true, status: 200, body: opts.sse.body } as unknown as Response;
    }
    if (url.includes("/justificativas") && metodo === "GET") {
      return { ok: true, status: 200, json: async () => justificativasCruas } as Response;
    }
    if (url.includes("/chamada") && metodo === "GET") {
      const i = iChamada++;
      if (falhas.has(i)) return { ok: false, status: 500, json: async () => ({}) } as Response;
      return { ok: true, status: 200, json: async () => corpos[Math.min(i, corpos.length - 1)] } as Response;
    }
    // escritas: sucesso por default
    return { ok: true, status: 200, json: async () => ({}) } as Response;
  });

  global.fetch = f as unknown as typeof fetch;
  return f;
}

/** Espera uma condição SOB FAKE TIMERS. O `waitFor` da testing-library pendura aqui (ele mesmo espera em
 * timer, que está falso), e um `advanceTimersByTimeAsync` único não basta: a carga inicial é uma cadeia de
 * promessas + setState do React. Este laço avança o relógio E drena microtasks, dentro de `act`. */
async function ateQue(cond: () => boolean, passo = 20, tentativas = 200) {
  for (let i = 0; i < tentativas; i++) {
    if (cond()) return;
    await act(async () => {
      await vi.advanceTimersByTimeAsync(passo);
    });
  }
  throw new Error("condição não atingida dentro do orçamento de tempo falso");
}

const urls = (f: ReturnType<typeof vi.fn>) =>
  (f as unknown as { mock: { calls: unknown[][] } }).mock.calls.map((c) => String(c[0]));
const getsDeChamada = (f: ReturnType<typeof vi.fn>) =>
  (f as unknown as { mock: { calls: unknown[][] } }).mock.calls.filter((c) => {
    const init = c[1] as RequestInit | undefined;
    return String(c[0]).includes("/chamada") && (init?.method ?? "GET").toUpperCase() === "GET";
  }).length;

describe("useChamada — a costura de IO da chamada", () => {
  afterEach(() => {
    vi.restoreAllMocks();
    vi.useRealTimers();
  });

  it("carga inicial: o corpo CRU em kebab-case vira `dados` camelizado e `estado` fica pronto", async () => {
    montarFetch();
    const { result } = renderHook(() => useChamada("s1", "tok"));
    await waitFor(() => expect(result.current.estado).toBe("pronto"));
    // se `camelizarChaves` sumisse do hook, estas duas linhas quebrariam — é por isso que a fixture é crua
    expect(result.current.dados?.quorum.membrosDaCasa).toBe(21);
    expect(result.current.dados?.linhas[0].vereadorId).toBe("v1");
    await waitFor(() => expect(result.current.justificativas?.[0].lockVersion).toBe(3));
  });

  it("falha na carga inicial vira estado de erro com mensagem, sem derrubar o hook", async () => {
    montarFetch({ chamadaFalha: [0] });
    const { result } = renderHook(() => useChamada("s1", "tok"));
    await waitFor(() => expect(result.current.estado).toBe("erro"));
    expect(result.current.erro).toBeTruthy();
    expect(result.current.dados).toBeNull();
  });

  it("id de sessão fora do formato não vai à rede", () => {
    const f = montarFetch();
    const { result } = renderHook(() => useChamada("../etc/passwd", "tok"));
    expect(result.current.estado).toBe("erro");
    expect(urls(f)).toHaveLength(0);
  });

  // ---------- A REGRA QUE A ETAPA 4 REPROVOU ----------

  it("o frame de SSE NUNCA muta `dados` por incremento — ele só PEDE re-busca, e o valor vem do servidor", async () => {
    vi.useFakeTimers();
    const sse = sseControlado();
    // 1ª busca: ninguém presente. 2ª busca (a re-hidratação): o SERVIDOR diz 7.
    const f = montarFetch({
      sse,
      chamadas: [chamadaCrua({ presentesTotal: 0 }), chamadaCrua({ presentesTotal: 7 })],
    });

    const { result } = renderHook(() => useChamada("s1", "tok"));
    await ateQue(() => result.current.estado === "pronto");
    expect(result.current.dados?.quorum.presentesTotal).toBe(0);

    // chega um evento que, se fosse APLICADO como delta, mudaria a contagem por conta própria
    await act(async () => {
      sse.enviar("presenca.registrada", { "vereador-id": "v1", tipo: "entrada" });
      await vi.advanceTimersByTimeAsync(50);
    });
    // ainda 0: o frame não é a fonte do número, e o piso de 3s ainda não passou
    expect(result.current.dados?.quorum.presentesTotal).toBe(0);
    expect(getsDeChamada(f)).toBe(1);

    await act(async () => {
      await vi.advanceTimersByTimeAsync(3600);
    });
    // agora mudou — e mudou para o que o SERVIDOR devolveu, não para algo derivado do frame
    expect(result.current.dados?.quorum.presentesTotal).toBe(7);
    expect(getsDeChamada(f)).toBe(2);
  });

  it("piso de 3s: uma rajada de frames não vira uma rajada de requests", async () => {
    vi.useFakeTimers();
    const sse = sseControlado();
    const f = montarFetch({ sse });
    const { result } = renderHook(() => useChamada("s1", "tok"));
    await ateQue(() => result.current.estado === "pronto");
    expect(getsDeChamada(f)).toBe(1); // a carga inicial

    // a chamada nominal de uma Casa inteira: ~21 eventos em poucos segundos
    await act(async () => {
      for (let i = 0; i < 21; i++) sse.enviar("presenca.registrada", { "vereador-id": `v${i}` });
      await vi.advanceTimersByTimeAsync(3600);
    });
    expect(getsDeChamada(f)).toBe(2); // UMA re-busca coalescida, não 21
  });

  it("re-hidratação PERIÓDICA (30s) acontece mesmo sem nenhum evento — a auto-cura da retenção do canal", async () => {
    vi.useFakeTimers();
    const f = montarFetch({ sse: sseControlado() });
    const { result } = renderHook(() => useChamada("s1", "tok"));
    await ateQue(() => result.current.estado === "pronto");
    expect(getsDeChamada(f)).toBe(1);

    await act(async () => {
      await vi.advanceTimersByTimeAsync(31000);
    });
    expect(getsDeChamada(f)).toBeGreaterThanOrEqual(2);
  });

  it("queda do stream: canal vira `reconectando` e a reconexão re-hidrata", async () => {
    vi.useFakeTimers();
    const sse = sseControlado();
    const f = montarFetch({ sse });
    const { result } = renderHook(() => useChamada("s1", "tok"));
    await ateQue(() => result.current.estado === "pronto");
    await ateQue(() => result.current.canal === "ao-vivo");
    const antes = getsDeChamada(f);

    sse.quebrar();
    await ateQue(() => result.current.canal === "reconectando");
    expect(result.current.canal).toBe("reconectando"); // nunca "ao vivo" sobre dado que pode estar defasado
    expect(getsDeChamada(f)).toBeGreaterThan(antes); // a reconexão re-hidrata sem esperar o piso
  });

  it("re-busca que falha NÃO apaga o último snapshot bom da tela (degrada, não zera)", async () => {
    vi.useFakeTimers();
    const sse = sseControlado();
    montarFetch({ sse, chamadas: [chamadaCrua({ presentesTotal: 5 })], chamadaFalha: [1] });
    const { result } = renderHook(() => useChamada("s1", "tok"));
    await ateQue(() => result.current.estado === "pronto");
    expect(result.current.dados?.quorum.presentesTotal).toBe(5);

    await act(async () => {
      sse.enviar("presenca.registrada", { "vereador-id": "v1" });
      await vi.advanceTimersByTimeAsync(3600);
    });
    expect(result.current.dados?.quorum.presentesTotal).toBe(5); // a falha não virou tela vazia
    expect(result.current.estado).toBe("pronto");
  });

  // ---------- escritas ----------

  it("marcarLinha aplica OTIMISTA na hora e faz ROLLBACK exato quando o servidor recusa", async () => {
    const f = montarFetch({
      respostas: {
        "/presenca": () =>
          ({ ok: false, status: 409, json: async () => ({ erro: "sessao nao aceita mais registro de presenca" }) }) as Response,
      },
    });
    const { result } = renderHook(() => useChamada("s1", "tok"));
    await waitFor(() => expect(result.current.estado).toBe("pronto"));
    expect(result.current.dados?.linhas[0].estado).toBe("ausente");

    await expect(
      act(async () => {
        await result.current.marcarLinha("v1", "presente-plenario");
      }),
    ).rejects.toThrow(/encerrada/i);

    // voltou ao estado EXATO anterior, não a um valor fabricado
    expect(result.current.dados?.linhas[0].estado).toBe("ausente");
    expect(urls(f).some((u) => u.includes("/presenca"))).toBe(true);
  });

  it("registrarChamada: se o LOTE falha, o ATO não acontece", async () => {
    const f = montarFetch({
      respostas: {
        "/presenca/lote": () =>
          ({ ok: false, status: 409, json: async () => ({ erro: "sessao sem data marcada" }) }) as Response,
      },
    });
    const { result } = renderHook(() => useChamada("s1", "tok"));
    await waitFor(() => expect(result.current.estado).toBe("pronto"));

    let saida: { ok: boolean; erro?: string } = { ok: true };
    await act(async () => {
      saida = await result.current.registrarChamada({
        v1: { estadoAlvo: "presente-plenario", desde: "2026-05-21T17:05:00Z" },
      });
    });

    expect(saida.ok).toBe(false);
    expect(saida.erro).toMatch(/não tem data/i); // causa + correção, nunca "erro ao salvar"
    // a prova: nenhum POST em /chamada — a Mesa não pode achar que conduziu uma chamada que não gravou
    const postsDeAto = (f as unknown as { mock: { calls: unknown[][] } }).mock.calls.filter((c) => {
      const init = c[1] as RequestInit | undefined;
      return String(c[0]).endsWith("/chamada") && (init?.method ?? "GET").toUpperCase() === "POST";
    });
    expect(postsDeAto).toHaveLength(0);
  });

  it("registrarChamada no caminho feliz: lote -> ato -> re-busca", async () => {
    const f = montarFetch();
    const { result } = renderHook(() => useChamada("s1", "tok"));
    await waitFor(() => expect(result.current.estado).toBe("pronto"));
    const antes = getsDeChamada(f);

    await act(async () => {
      await result.current.registrarChamada({
        v1: { estadoAlvo: "presente-plenario", desde: "2026-05-21T17:05:00Z" },
      });
    });

    const chamadas = (f as unknown as { mock: { calls: unknown[][] } }).mock.calls;
    const iLote = chamadas.findIndex((c) => String(c[0]).includes("/presenca/lote"));
    const iAto = chamadas.findIndex((c) => {
      const init = c[1] as RequestInit | undefined;
      return String(c[0]).endsWith("/chamada") && (init?.method ?? "GET").toUpperCase() === "POST";
    });
    expect(iLote).toBeGreaterThanOrEqual(0);
    expect(iAto).toBeGreaterThan(iLote); // o ato vem DEPOIS do lote, nunca antes
    expect(getsDeChamada(f)).toBeGreaterThan(antes); // e a tela re-busca o que o servidor de fato gravou
  });

  it("decidirJustificativa: 409 é conflito de concorrência, com causa + correção", async () => {
    montarFetch({
      respostas: {
        "/decisao": () => ({ ok: false, status: 409, json: async () => ({ erro: "conflito" }) }) as Response,
      },
    });
    const { result } = renderHook(() => useChamada("s1", "tok"));
    await waitFor(() => expect(result.current.estado).toBe("pronto"));

    let saida: { ok: boolean; conflito?: boolean; erro?: string } = { ok: true };
    await act(async () => {
      saida = await result.current.decidirJustificativa("j1", "aprovada", 3);
    });

    expect(saida.ok).toBe(false);
    expect(saida.conflito).toBe(true);
    expect(saida.erro).toMatch(/outra pessoa/i);
    expect(saida.erro).toMatch(/recarregue/i); // a correção, não só a causa
  });

  // ---------- a tradução de erro, testável direto ----------

  it("mensagemDeErro traduz os 2 padrões de 409 e nunca devolve genérico quando o domínio falou", () => {
    expect(mensagemDeErro("sessao sem data marcada", 409)).toMatch(/informe a data da sessão/i);

    const comHora = mensagemDeErro("sessao nao aceita mais registro de presenca", 409, "2026-05-21T21:14:00Z");
    expect(comHora).toMatch(/pela ata/i);
    expect(comHora).toMatch(/21h14|\d\dh\d\d/); // a hora entra quando o chamador a tem

    // sem a hora, a frase sai sem ela — NUNCA uma hora inventada
    const semHora = mensagemDeErro("sessao nao aceita mais registro de presenca", 409);
    expect(semHora).toMatch(/A sessão foi encerrada\./);

    // texto de domínio desconhecido é REPASSADO (já é acionável), não trocado por "erro ao salvar"
    expect(mensagemDeErro("teto de 50 chamadas conduzidas atingido", 409)).toBe(
      "teto de 50 chamadas conduzidas atingido",
    );
  });
});
