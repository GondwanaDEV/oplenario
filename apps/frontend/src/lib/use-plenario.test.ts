import { describe, expect, it, vi, afterEach } from "vitest";
import { renderHook, act, waitFor } from "@testing-library/react";
import { usePlenario, TIMEOUT_REBUSCA_MS } from "./use-plenario";
import { numeroDoTelao } from "./plenario-reducer";

// O ÚNICO hook de `lib/` que não tinha teste — e é o que carrega a costura de BORDA do quórum: o fetch em
// paralelo ao SSE, o `camelizarChaves`, o cast sem validação, o guard de unmount e o tri-estado da falha.
//
// A lacuna era concreta, não teórica: os testes do reducer montam fixtures JÁ em camelCase, então nunca
// veem a chave CRUA do backend. Remover `camelizarChaves` do hook deixava a suíte inteira verde e o telão
// imprimindo "NaN de undefined" em produção — o mesmo mecanismo já registrado neste repo como "fixture com
// vocabulário fictício": o teste passa porque ele mesmo semeou a forma que procura. Por isso os corpos
// abaixo são KEBAB-CASE, exatamente como o wire os emite.

const sessaoCrua = {
  id: "s1",
  "sessao-legislativa-id": "sl1",
  "tipo-sessao": "ordinaria",
  "numero-sequencial": 14,
  estado: "aberta",
  modalidade: "presencial",
  delibera: true,
  "transmite-publica": true,
  "gera-ata-regimental": true,
  "permite-voto-secreto": false,
  "permite-modalidade-remota": false,
  "agendada-para": "2026-05-21T22:00:00Z",
  "aberta-em": "2026-05-21T22:00:00Z",
  "encerrada-em": null,
  "motivo-nao-realizada": null,
};

// O CORPO REAL da rota, em kebab-case, como `adapters/out/presenca/quorum-sessao->wire` o emite.
const quorumCru = {
  "sessao-id": "s1",
  "sessao-estado": "aberta",
  instante: "2026-05-21T22:05:00.472913Z",
  "data-de-composicao": "2026-05-21",
  "composicao-resolvida-em": "2026-05-21T22:05:00.472913Z",
  "sem-registro-de-presenca": false,
  quorum: {
    "presentes-plenario": 7,
    "presentes-remoto": 2,
    "presentes-total": 9,
    "membros-da-casa": 21,
    "presencas-fora-do-roster": 0,
  },
};

// O CORPO REAL da rota, em kebab-case, como `adapters/out/tribuna->tribuna-sessao->wire` o emite
// (`apps/backend/src/oplenario/sessoes/adapters/out/tribuna.clj`) — conferido campo a campo contra a
// fonte, não redigitado de memória (ver `oplenario-fixture-vocabulario-ficticio`).
const tribunaCru = {
  "sessao-id": "s1",
  "orador-atual": {
    "fala-id": "f1",
    "orador-id": "vFalando",
    "tipo-fala": "principal",
    fase: "ordem_do_dia",
    "iniciou-em": "2026-09-07T22:00:00.123456Z",
    "inscricao-id": null,
  },
  "marcos-cronometro": [{ tipo: "pausada", "ocorrido-em": "2026-09-07T22:01:00.123456Z", "segundos-adicionais": null }],
  inscritos: [{ "inscricao-id": "i1", "vereador-id": "v1", "origem-inscricao": "pre_sessao_app", fase: "ordem_do_dia", ordem: 1 }],
};

/** Promessa controlável de fora — usada pelos testes do Fix round 1 (I2/I3) para abrir uma JANELA exata
 * em que uma resposta HTTP fica em voo enquanto um evento SSE é injetado (ou fica pendurada pra sempre). */
function deferido<T>() {
  let resolve!: (v: T) => void;
  const promise = new Promise<T>((res) => {
    resolve = res;
  });
  return { promise, resolve };
}

/** Stream de SSE controlável de fora — mesmo molde de `use-chamada.test.ts` (`ReadableStream` real,
 * porque `consumirSse` usa `body.getReader()`). */
function sseControlado() {
  let ctrl!: ReadableStreamDefaultController<Uint8Array>;
  const body = new ReadableStream<Uint8Array>({ start: (c) => (ctrl = c) });
  const enc = new TextEncoder();
  return {
    body,
    enviar(evento: string, seq: number, dados: unknown) {
      ctrl.enqueue(enc.encode(`event: ${evento}\nid: ${seq}\ndata: ${JSON.stringify(dados)}\n\n`));
    },
  };
}

/** Espera uma condição SOB FAKE TIMERS, avançando o relógio E drenando microtasks dentro de `act` — ver
 * a mesma necessidade documentada em `use-chamada.test.ts` (`waitFor` sozinho pendura sob fake timers). */
async function ateQue(cond: () => boolean, passo = 20, tentativas = 200) {
  for (let i = 0; i < tentativas; i++) {
    if (cond()) return;
    await act(async () => {
      await vi.advanceTimersByTimeAsync(passo);
    });
  }
  throw new Error("condição não atingida dentro do orçamento de tempo falso");
}

const contarChamadas = (f: typeof fetch, frag: string) =>
  (f as unknown as { mock: { calls: unknown[][] } }).mock.calls.filter((c) => String(c[0]).includes(frag)).length;

/** fetch fake: responde as rotas de dados e deixa o SSE pendurado (o stream nunca resolve sozinho).
 * Fix round 2 (A3): repassa `options?.signal` ao override — sem isto, testar o timeout de
 * `sinalComTimeout` era impossível (o fake nunca reagia a um abort). */
function fetchFake(overrides: Record<string, (signal?: AbortSignal) => Response | Promise<Response>> = {}) {
  return vi.fn(async (url: string, options?: RequestInit) => {
    for (const [frag, fn] of Object.entries(overrides)) if (url.includes(frag)) return fn(options?.signal ?? undefined);
    if (url.includes("/quorum")) return { ok: true, status: 200, json: async () => quorumCru } as Response;
    if (url.includes("/tribuna")) return { ok: true, status: 200, json: async () => tribunaCru } as Response;
    if (url.includes("/plenario")) return new Promise<Response>(() => {}); // SSE: pendurado de propósito
    return { ok: true, status: 200, json: async () => sessaoCrua } as Response;
  }) as unknown as typeof fetch;
}

/** Promessa que HONRA o `AbortSignal`: nunca resolve sozinha, mas REJEITA assim que `signal` abortar —
 * o comportamento real de um `fetch` pendurado quando o timeout (`sinalComTimeout`) dispara. Fix round 2
 * (A3): sem isto o `fetchFake` anterior (que ignorava o signal) tornava o timeout INTESTÁVEL — a suíte
 * ficava verde mesmo removendo `sinalComTimeout()` inteiramente. */
function penduradoAteAbortar(signal?: AbortSignal): Promise<Response> {
  return new Promise((_, reject) => {
    if (!signal) return; // sem signal: hang genuíno (usado onde o abort não é o que se testa)
    if (signal.aborted) { reject(signal.reason ?? new DOMException("Aborted", "AbortError")); return; }
    signal.addEventListener("abort", () => reject(signal.reason ?? new DOMException("Aborted", "AbortError")), { once: true });
  });
}

describe("usePlenario — a costura de borda do quórum", () => {
  afterEach(() => vi.restoreAllMocks());

  it("com `comQuorum`, o corpo CRU em kebab-case vira numerador e denominador na tela", async () => {
    global.fetch = fetchFake();
    const { result } = renderHook(() => usePlenario("s1", "tok", { comQuorum: true }));
    await waitFor(() => expect(result.current.estado?.quorumStatus).toBe("ok"));
    expect(numeroDoTelao(result.current.estado!)).toBe(9); // `presentes-total`, não a soma de dois campos
    expect(result.current.estado!.quorum?.membrosDaCasa).toBe(21);
  });

  it("SEM `comQuorum` (o cockpit do vereador) a rota de quórum não é chamada", async () => {
    // Estrutural: o cockpit lê só `presentes` de forma nominal e nunca mostrou quórum. Mantê-lo fora deste
    // caminho impede que uma evolução da hidratação volte a regredir a tela de votar, e tira ~21 clientes
    // por Casa do polling da leitura mais cara do módulo.
    const f = fetchFake();
    global.fetch = f;
    const { result } = renderHook(() => usePlenario("s1", "tok"));
    await waitFor(() => expect(result.current.estado).not.toBeNull());
    const chamadas = (f as unknown as { mock: { calls: unknown[][] } }).mock.calls.map((c) => String(c[0]));
    expect(chamadas.some((u) => u.includes("/quorum"))).toBe(false);
  });

  it("403 na rota de quórum -> tri-estado `indisponivel`, sem número e sem derrubar a página", async () => {
    global.fetch = fetchFake({ "/quorum": () => ({ ok: false, status: 403, json: async () => ({}) }) as Response });
    const { result } = renderHook(() => usePlenario("s1", "tok", { comQuorum: true }));
    await waitFor(() => expect(result.current.estado?.quorumStatus).toBe("indisponivel"));
    expect(numeroDoTelao(result.current.estado!)).toBeNull(); // NUNCA "0 presentes" quando é "não sei"
    expect(result.current.conexao).not.toBe("erro"); // a sessão carregou: quórum ausente não é erro de PÁGINA
  });

  it("corpo de forma inesperada não lança dentro do updater de estado (nem imprime NaN)", async () => {
    global.fetch = fetchFake({
      "/quorum": () => ({ ok: true, status: 200, json: async () => ({ instante: "x" }) }) as Response,
    });
    const { result } = renderHook(() => usePlenario("s1", "tok", { comQuorum: true }));
    await waitFor(() => expect(result.current.estado?.quorumStatus).toBe("indisponivel"));
    expect(numeroDoTelao(result.current.estado!)).toBeNull();
  });

  it("rede caída na rota de quórum é absorvida (a sessão continua de pé)", async () => {
    global.fetch = fetchFake({ "/quorum": () => Promise.reject(new Error("offline")) });
    const { result } = renderHook(() => usePlenario("s1", "tok", { comQuorum: true }));
    await waitFor(() => expect(result.current.estado?.quorumStatus).toBe("indisponivel"));
    expect(result.current.sessao).not.toBeNull();
  });

  it("id de sessão fora do formato não vai à rede", () => {
    const f = fetchFake();
    global.fetch = f;
    const { result } = renderHook(() => usePlenario("../etc/passwd", "tok", { comQuorum: true }));
    expect(result.current.conexao).toBe("erro");
    expect((f as unknown as { mock: { calls: unknown[][] } }).mock.calls).toHaveLength(0);
  });
});

describe("usePlenario — a costura de borda da TRIBUNA (#7 do ledger de prontidão)", () => {
  afterEach(() => {
    vi.restoreAllMocks();
    vi.useRealTimers();
  });

  it("O CASO DA FATIA: abrir a tela com a fala JÁ EM CURSO resolve o orador sem nenhum evento SSE", async () => {
    global.fetch = fetchFake();
    const { result } = renderHook(() => usePlenario("s1", "tok", { comQuorum: true }));
    // `.not.toBeNull()` sozinho passaria com `estado` ainda null (undefined não é null) — a condição
    // real é "o snapshot da tribuna chegou", não "o campo não é null".
    await waitFor(() => expect(result.current.estado?.oradorAtual?.falaId).toBe("f1"));
    expect(result.current.estado!.oradorAtual).toMatchObject({ falaId: "f1", oradorId: "vFalando", tipoFala: "principal" });
    expect(result.current.estado!.marcosCronometro).toEqual([{ tipo: "pausada", ocorridoEm: "2026-09-07T22:01:00.123456Z", segundosAdicionais: null }]);
    expect(result.current.estado!.inscritos).toEqual([{ inscricaoId: "i1", vereadorId: "v1", fase: "ordem_do_dia", ordem: 1 }]);
  });

  it("SEM `comQuorum` (o cockpit do vereador) a rota de tribuna também não é chamada — mesmo gate do quórum", async () => {
    const f = fetchFake();
    global.fetch = f;
    const { result } = renderHook(() => usePlenario("s1", "tok"));
    await waitFor(() => expect(result.current.estado).not.toBeNull());
    const chamadas = (f as unknown as { mock: { calls: unknown[][] } }).mock.calls.map((c) => String(c[0]));
    expect(chamadas.some((u) => u.includes("/tribuna"))).toBe(false);
  });

  it("403 na rota de tribuna não derruba a página nem trava o quórum (que é independente)", async () => {
    // O corpo do 403 é um TribunaOut VÁLIDO de propósito: se a implementação esquecesse de checar
    // `resp.ok` antes de hidratar, este teste reprovaria com `oradorAtual` = "vFalando" em vez de null —
    // é a diferença entre um teste que só verifica "não lançou" e um que prova que o STATUS foi checado.
    global.fetch = fetchFake({ "/tribuna": () => ({ ok: false, status: 403, json: async () => tribunaCru }) as Response });
    const { result } = renderHook(() => usePlenario("s1", "tok", { comQuorum: true }));
    await waitFor(() => expect(result.current.estado?.quorumStatus).toBe("ok")); // o quórum hidrata normalmente
    expect(result.current.estado!.oradorAtual).toBeNull(); // tribuna simplesmente não avançou
    expect(result.current.conexao).not.toBe("erro");
  });

  it("rede caída na rota de tribuna é absorvida (a sessão e o quórum continuam de pé)", async () => {
    global.fetch = fetchFake({ "/tribuna": () => Promise.reject(new Error("offline")) });
    const { result } = renderHook(() => usePlenario("s1", "tok", { comQuorum: true }));
    await waitFor(() => expect(result.current.estado?.quorumStatus).toBe("ok"));
    expect(result.current.sessao).not.toBeNull();
    expect(result.current.estado!.oradorAtual).toBeNull();
  });

  it("corpo de forma inesperada na tribuna não lança dentro do updater de estado", async () => {
    global.fetch = fetchFake({ "/tribuna": () => ({ ok: true, status: 200, json: async () => ({ "sessao-id": "s1" }) }) as Response });
    const { result } = renderHook(() => usePlenario("s1", "tok", { comQuorum: true }));
    await waitFor(() => expect(result.current.estado?.quorumStatus).toBe("ok"));
    expect(result.current.estado!.oradorAtual).toBeNull();
    expect(result.current.conexao).not.toBe("erro");
  });

  it("Fix round 1 (I2) — um `inscricao.registrada` alheio chegado ENQUANTO o GET /tribuna está em voo não apaga o orador", async () => {
    // Reproduz o cenário do achado: F5 no meio de uma fala; a resposta de `/tribuna` ainda não voltou
    // quando um vereador se inscreve pelo SSE. Antes do fix, o contador ÚNICO descartava o snapshot
    // INTEIRO (orador incluso) — "Ninguém com a palavra" por até 30s com alguém de fato falando.
    vi.useFakeTimers();
    const sse = sseControlado();
    const tribunaEmVoo = deferido<Response>();
    const f = fetchFake({
      "/plenario": () => ({ ok: true, status: 200, body: sse.body }) as unknown as Response,
      "/tribuna": () => tribunaEmVoo.promise,
    });
    global.fetch = f;

    const { result } = renderHook(() => usePlenario("s1", "tok", { comQuorum: true }));
    await ateQue(() => result.current.conexao === "ao-vivo"); // SSE conectado; GET /tribuna já disparou e está em voo

    // chega, pelo SSE, uma inscrição de OUTRO vereador — não mexe em `oradorAtual`
    await act(async () => {
      sse.enviar("inscricao.registrada", 1, {
        "inscricao-id": "iNova",
        "sessao-id": "s1",
        "vereador-id": "v9",
        "origem-inscricao": "pre_sessao_app",
        fase: "ordem_do_dia",
        ordem: 3,
      });
      await vi.advanceTimersByTimeAsync(10);
    });
    expect(result.current.estado!.inscritos).toEqual([{ inscricaoId: "iNova", vereadorId: "v9", fase: "ordem_do_dia", ordem: 3 }]);

    // AGORA a resposta de T0 chega — um snapshot com `vFalando` na tribuna, mas SEM a inscrição nova
    await act(async () => {
      tribunaEmVoo.resolve({ ok: true, status: 200, json: async () => tribunaCru } as Response);
      await vi.advanceTimersByTimeAsync(10);
    });

    // o orador SOBREVIVE: nenhum evento de FALA chegou no meio, só um de INSCRIÇÃO
    await ateQue(() => result.current.estado?.oradorAtual?.oradorId === "vFalando");
    // e a fila de inscritos não regride para a do snapshot velho (o SSE já sabe mais)
    expect(result.current.estado!.inscritos).toEqual([{ inscricaoId: "iNova", vereadorId: "v9", fase: "ordem_do_dia", ordem: 3 }]);
  });

  it("Fix round 1 (I3) — uma `/tribuna` pendurada PARA SEMPRE não trava a re-busca do quórum nos ciclos seguintes", async () => {
    // Antes do fix, um `rebuscando` ÚNICO só liberava depois que as DUAS rotas resolvessem — uma
    // `/tribuna` que nunca responde travava também o quórum, e o numerador do telão congelava com o
    // badge dizendo "Ao vivo" pelo resto da sessão.
    vi.useFakeTimers();
    const f = fetchFake({ "/tribuna": () => new Promise<Response>(() => {}) }); // pendura pra sempre
    global.fetch = f;

    const { result } = renderHook(() => usePlenario("s1", "tok", { comQuorum: true }));
    await ateQue(() => result.current.estado?.quorumStatus === "ok");
    const antes = contarChamadas(f, "/quorum");

    await act(async () => {
      await vi.advanceTimersByTimeAsync(31000); // passa a periódica de 30s
    });
    // o quórum foi re-buscado de novo, mesmo com a tribuna pendurada desde o primeiro disparo
    expect(contarChamadas(f, "/quorum")).toBeGreaterThan(antes);
  });

  it("A3 — uma `/quorum` pendurada indefinidamente é ABORTADA em 8s (TIMEOUT_REBUSCA_MS): sem o timeout, o guard nunca libera e o status fica preso em 'carregando' para sempre", async () => {
    // Furo de cobertura do round anterior: o `fetchFake` ignorava o `AbortSignal`, então trocar
    // `sinalComTimeout()` de volta por `controller.signal` puro deixava a suíte inteira verde — o
    // timeout era decorativo do ponto de vista dos testes. `penduradoAteAbortar` fecha o furo: reage
    // ao abort de verdade, como um `fetch` pendurado reagiria.
    vi.useFakeTimers();
    const f = fetchFake({ "/quorum": (signal) => penduradoAteAbortar(signal) });
    global.fetch = f;

    const { result } = renderHook(() => usePlenario("s1", "tok", { comQuorum: true }));
    await ateQue(() => result.current.estado !== null); // sessão carregou; `/quorum` já disparou e está em voo
    expect(result.current.estado!.quorumStatus).toBe("carregando"); // ainda em voo — nenhum tempo decorrido

    // SEM o timeout, esta promessa nunca resolve nem rejeita (só reage a abort, e nada aborta): o
    // `ateQue` abaixo estouraria o orçamento e reprovaria com "condição não atingida" — é a prova RED.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(TIMEOUT_REBUSCA_MS);
    });
    await ateQue(() => result.current.estado?.quorumStatus === "indisponivel");
  });

  it("A3 — rede lenta PERSISTENTE (RTT > 8s sustentado): 1 tentativa a cada 30s, cada uma abortada aos 8s, número velho congelado — decisão: manter sem backoff", async () => {
    // Decisão do round 2 (ver a docstring de `TIMEOUT_REBUSCA_MS` em use-plenario.ts): sem backoff nem
    // sinalização extra. Este teste PROVA a cadência declarada, não só afirma a decisão em comentário.
    vi.useFakeTimers();
    let travar = false;
    const f = fetchFake({
      "/quorum": (signal) => (travar ? penduradoAteAbortar(signal) : ({ ok: true, status: 200, json: async () => quorumCru }) as Response),
    });
    global.fetch = f;

    const { result } = renderHook(() => usePlenario("s1", "tok", { comQuorum: true }));
    await ateQue(() => result.current.estado?.quorumStatus === "ok");
    expect(numeroDoTelao(result.current.estado!)).toBe(9);

    travar = true; // a partir de agora toda /quorum trava até o timeout abortar
    const antes = contarChamadas(f, "/quorum");

    // a periódica de 30s dispara uma tentativa; ela é abortada 8s depois (TIMEOUT_REBUSCA_MS)
    await act(async () => {
      await vi.advanceTimersByTimeAsync(30000 + TIMEOUT_REBUSCA_MS + 500);
    });
    expect(contarChamadas(f, "/quorum")).toBe(antes + 1); // exatamente 1 tentativa nova, abortada — sem retry mais cedo
    expect(result.current.estado!.quorumStatus).toBe("ok"); // degrada, não zera (falharQuorum preserva o snapshot bom)
    expect(numeroDoTelao(result.current.estado!)).toBe(9); // número velho FICA — não há backoff nem folga

    // uma segunda janela de 30s repete a MESMA cadência — não converge sozinho enquanto a rede seguir lenta
    await act(async () => {
      await vi.advanceTimersByTimeAsync(30000 + TIMEOUT_REBUSCA_MS + 500);
    });
    expect(contarChamadas(f, "/quorum")).toBe(antes + 2);
    expect(numeroDoTelao(result.current.estado!)).toBe(9);
  });
});

// Fatia "demo-tres-consertos" #2b — achado ao vivo (Daouda, 12/09/2026): a Casa recém-semeada tinha uma
// votação 'aberta' no banco, mas o canal Valkey (retenção MINID ~5min) estava vazio — o cockpit do
// vereador mostrava "Nenhuma votação aberta no momento" com uma votação de verdade aberta. `comVotacao`
// liga `GET /sessoes/:id/votacao-aberta`, a recuperação de estado; mesmo racional de teste da TRIBUNA
// acima (T1/I2 espelhados aqui como o CASO DA FATIA / PRECEDÊNCIA).
describe("usePlenario — a costura de borda da RECUPERAÇÃO de votação (fatia 'demo-tres-consertos' #2b)", () => {
  afterEach(() => {
    vi.restoreAllMocks();
    vi.useRealTimers();
  });

  // O CORPO REAL de GET /sessoes/:id/votacao-aberta, em kebab-case, como o adapter o emite.
  const votacaoAbertaCrua = {
    "votacao-id": "vt1",
    modalidade: "nominal",
    "objeto-tipo": "proposicao",
    "objeto-id": "p1",
    votos: [{ "vereador-id": "v1", voto: "sim" }],
  };

  it("T1 — o CASO DA FATIA: com `comVotacao`, o cliente descobre a votação aberta sem NENHUM evento SSE", async () => {
    global.fetch = fetchFake({
      "/votacao-aberta": () => ({ ok: true, status: 200, json: async () => votacaoAbertaCrua }) as Response,
    });
    const { result } = renderHook(() => usePlenario("s1", "tok", { comVotacao: true }));
    await waitFor(() => expect(result.current.estado?.placar?.votacaoId).toBe("vt1"));
    expect(result.current.estado!.placar).toEqual({
      votacaoId: "vt1", modalidade: "nominal", objetoTipo: "proposicao", objetoId: "p1", proposicao: null, encerrada: false,
      votosNominais: { v1: "sim" }, votosSecretos: 0, resultado: null, totais: null, baseMembros: null,
    });
  });

  it("SEM `comVotacao` a rota de recuperação NÃO é chamada", async () => {
    const f = fetchFake({
      "/votacao-aberta": () => ({ ok: true, status: 200, json: async () => votacaoAbertaCrua }) as Response,
      "/quorum": () => ({ ok: true, status: 200, json: async () => quorumCru }) as Response,
    });
    global.fetch = f;
    const { result } = renderHook(() => usePlenario("s1", "tok", { comQuorum: true }));
    await waitFor(() => expect(result.current.estado?.quorumStatus).toBe("ok"));
    expect(contarChamadas(f, "/votacao-aberta")).toBe(0);
  });

  it("404 (nenhuma votação aberta) é estado LEGÍTIMO — placar continua null, conexão segue ao vivo, sem erro", async () => {
    global.fetch = fetchFake({ "/votacao-aberta": () => ({ ok: false, status: 404, json: async () => ({}) }) as Response });
    const { result } = renderHook(() => usePlenario("s1", "tok", { comVotacao: true }));
    await waitFor(() => expect(result.current.conexao).toBe("ao-vivo"));
    expect(result.current.estado?.placar).toBeNull();
    expect(result.current.conexao).not.toBe("erro");
  });

  it("rede caída na recuperação de votação é absorvida (a sessão continua de pé, placar continua null)", async () => {
    global.fetch = fetchFake({ "/votacao-aberta": () => Promise.reject(new Error("offline")) });
    const { result } = renderHook(() => usePlenario("s1", "tok", { comVotacao: true }));
    await waitFor(() => expect(result.current.conexao).toBe("ao-vivo"));
    expect(result.current.sessao).not.toBeNull();
    expect(result.current.estado?.placar).toBeNull();
  });

  it("corpo de forma inesperada na recuperação não lança e não inventa placar", async () => {
    global.fetch = fetchFake({ "/votacao-aberta": () => ({ ok: true, status: 200, json: async () => ({ foo: "bar" }) }) as Response });
    const { result } = renderHook(() => usePlenario("s1", "tok", { comVotacao: true }));
    await waitFor(() => expect(result.current.conexao).toBe("ao-vivo"));
    expect(result.current.estado?.placar).toBeNull();
  });

  it("§22.6 sigilo — votação SECRETA hidrata só a contagem, NUNCA vereador-id", async () => {
    global.fetch = fetchFake({
      "/votacao-aberta": () =>
        ({
          ok: true, status: 200,
          json: async () => ({ "votacao-id": "vt1", modalidade: "secreta", "objeto-tipo": "proposicao", "objeto-id": "p1", "votos-registrados": 4 }),
        }) as Response,
    });
    const { result } = renderHook(() => usePlenario("s1", "tok", { comVotacao: true }));
    await waitFor(() => expect(result.current.estado?.placar?.votosSecretos).toBe(4));
    expect(result.current.estado!.placar!.votosNominais).toEqual({});
  });

  it("PRECEDÊNCIA — um `votacao.aberta` chegado ENQUANTO GET /votacao-aberta está em voo não é sobrescrito pelo snapshot atrasado", async () => {
    // Anatomia do ruling (mesma de Fix round 1 I2, tribuna): T0 dispara o GET (nenhuma votação vista
    // ainda); entre T0 e a resposta, o SSE entrega `votacao.aberta` de uma votação DIFERENTE (vt2). Se a
    // hidratação aplicasse o snapshot de T0 (vt1), o placar RETROCEDERIA pra uma votação que já não é a
    // corrente — na prática, sobre um voto real que o vereador já vê na tela.
    vi.useFakeTimers();
    const sse = sseControlado();
    const votacaoEmVoo = deferido<Response>();
    const f = fetchFake({
      "/plenario": () => ({ ok: true, status: 200, body: sse.body }) as unknown as Response,
      "/votacao-aberta": () => votacaoEmVoo.promise,
    });
    global.fetch = f;

    const { result } = renderHook(() => usePlenario("s1", "tok", { comVotacao: true }));
    await ateQue(() => result.current.conexao === "ao-vivo"); // SSE conectado; GET /votacao-aberta já disparou e está em voo

    // chega, pelo SSE, a abertura de OUTRA votação
    await act(async () => {
      sse.enviar("votacao.aberta", 1, {
        "votacao-id": "vt2", "sessao-id": "s1", "objeto-tipo": "proposicao", "objeto-id": "p2",
        modalidade: "nominal", "quorum-tipo": "maioria_simples",
      });
      await vi.advanceTimersByTimeAsync(10);
    });
    expect(result.current.estado!.placar!.votacaoId).toBe("vt2");

    // AGORA a resposta de T0 chega — o snapshot da vt1, desatualizado
    await act(async () => {
      votacaoEmVoo.resolve({ ok: true, status: 200, json: async () => votacaoAbertaCrua } as Response);
      await vi.advanceTimersByTimeAsync(10);
    });

    // a vt2 (ao vivo) sobrevive — não é sobrescrita pelo snapshot velho da vt1
    expect(result.current.estado!.placar!.votacaoId).toBe("vt2");
  });
});
