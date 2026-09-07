import { describe, expect, it, vi, afterEach } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { usePlenario } from "./use-plenario";
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

/** fetch fake: responde as rotas de dados e deixa o SSE pendurado (o stream nunca resolve sozinho). */
function fetchFake(overrides: Record<string, () => Response | Promise<Response>> = {}) {
  return vi.fn(async (url: string) => {
    for (const [frag, fn] of Object.entries(overrides)) if (url.includes(frag)) return fn();
    if (url.includes("/quorum")) return { ok: true, status: 200, json: async () => quorumCru } as Response;
    if (url.includes("/tribuna")) return { ok: true, status: 200, json: async () => tribunaCru } as Response;
    if (url.includes("/plenario")) return new Promise<Response>(() => {}); // SSE: pendurado de propósito
    return { ok: true, status: 200, json: async () => sessaoCrua } as Response;
  }) as unknown as typeof fetch;
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
  afterEach(() => vi.restoreAllMocks());

  it("O CASO DA FATIA: abrir a tela com a fala JÁ EM CURSO resolve o orador sem nenhum evento SSE", async () => {
    global.fetch = fetchFake();
    const { result } = renderHook(() => usePlenario("s1", "tok", { comQuorum: true }));
    // `.not.toBeNull()` sozinho passaria com `estado` ainda null (undefined não é null) — a condição
    // real é "o snapshot da tribuna chegou", não "o campo não é null".
    await waitFor(() => expect(result.current.estado?.oradorAtual?.falaId).toBe("f1"));
    expect(result.current.estado!.oradorAtual).toMatchObject({ falaId: "f1", oradorId: "vFalando", tipoFala: "principal" });
    expect(result.current.estado!.marcosCronometro).toEqual([{ tipo: "pausada", ocorridoEm: "2026-09-07T22:01:00.123456Z", segundosAdicionais: null }]);
    expect(result.current.estado!.inscritos).toEqual([{ inscricaoId: "i1", vereadorId: "v1", ordem: 1 }]);
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
});
