import { describe, expect, it, vi, afterEach } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { useCalendario } from "./use-calendario";

const sessoesFake = {
  sessoes: [
    {
      id: "s1",
      "sessao-legislativa-id": "sl-1",
      "tipo-sessao": "ordinaria",
      "numero-sequencial": 15,
      estado: "agendada",
      modalidade: "presencial",
      delibera: true,
      "transmite-publica": true,
      "gera-ata-regimental": true,
      "permite-voto-secreto": false,
      "permite-modalidade-remota": false,
      "agendada-para": "2026-06-24T17:00:00Z",
      "aberta-em": null,
      "encerrada-em": null,
      "motivo-nao-realizada": null,
      "lock-version": 1,
    },
  ],
};

const painelFake = {
  resumo: { pendente: 1, cumprida: 0, vencida: 0, dispensada: 0, cancelada: 0 },
  "em-aberto": [
    {
      id: "o1",
      "template-chave": "remessa_mensal_sim",
      "objeto-tipo": "ente",
      "objeto-id": "e-1",
      "vence-em": "2026-06-29",
      estado: "pendente",
    },
  ],
  "remessas-recentes": [],
};

/** Um único mock para as DUAS rotas — o hook compõe `useSessoes` (que faz o próprio fetch) com a busca
 *  do painel de compliance, então despachar por caminho é o que prova que ambas foram chamadas. */
function mockarRotas(resposta: (path: string) => { ok: boolean; body?: unknown }) {
  global.fetch = vi.fn(async (path: string) => {
    const r = resposta(String(path));
    return { ok: r.ok, status: r.ok ? 200 : 403, json: async () => r.body } as Response;
  }) as unknown as typeof fetch;
}

describe("useCalendario", () => {
  afterEach(() => vi.restoreAllMocks());

  it("busca /api/sessoes e /api/compliance/painel e cameliza os dois", async () => {
    mockarRotas((p) =>
      p === "/api/sessoes" ? { ok: true, body: sessoesFake } : { ok: true, body: painelFake },
    );
    const { result } = renderHook(() => useCalendario("tok"));
    await waitFor(() => expect(result.current.estadoSessoes).toBe("pronto"));
    await waitFor(() => expect(result.current.estadoPrazos).toBe("pronto"));

    expect(result.current.sessoes?.[0].numeroSequencial).toBe(15);
    expect(result.current.sessoes?.[0].agendadaPara).toBe("2026-06-24T17:00:00Z");
    expect(result.current.obrigacoes?.[0].templateChave).toBe("remessa_mensal_sim");
    expect(result.current.obrigacoes?.[0].venceEm).toBe("2026-06-29");
    // placar (1 pendente + 0 vencida) BATE com a lista (1 item): não há corte a anunciar.
    expect(result.current.truncamentoPrazos).toBeNull();

    const chamados = vi.mocked(global.fetch).mock.calls.map((c) => String(c[0]));
    expect(chamados).toContain("/api/sessoes");
    expect(chamados).toContain("/api/compliance/painel");
    const chamadaPainel = vi.mocked(global.fetch).mock.calls.find((c) => String(c[0]).includes("compliance"));
    expect(new Headers(chamadaPainel?.[1]?.headers).get("Authorization")).toBe("Bearer tok");
  });

  it("403 no painel (papel sem 'secretario') NÃO derruba as sessões — as duas fontes falham separado", async () => {
    mockarRotas((p) => (p === "/api/sessoes" ? { ok: true, body: sessoesFake } : { ok: false }));
    const { result } = renderHook(() => useCalendario("tok"));
    await waitFor(() => expect(result.current.estadoPrazos).toBe("erro"));
    expect(result.current.estadoSessoes).toBe("pronto");
    expect(result.current.sessoes?.[0].id).toBe("s1");
    expect(result.current.obrigacoes).toBeNull();
  });

  it("erro nas sessões não apaga os prazos já carregados", async () => {
    mockarRotas((p) => (p === "/api/sessoes" ? { ok: false } : { ok: true, body: painelFake }));
    const { result } = renderHook(() => useCalendario("tok"));
    await waitFor(() => expect(result.current.estadoSessoes).toBe("erro"));
    await waitFor(() => expect(result.current.estadoPrazos).toBe("pronto"));
    expect(result.current.obrigacoes?.[0].id).toBe("o1");
  });

  it("sem token -> os dois estados em 'erro', sem chamar fetch", () => {
    global.fetch = vi.fn() as unknown as typeof fetch;
    const { result } = renderHook(() => useCalendario(null));
    expect(result.current.estadoSessoes).toBe("erro");
    expect(result.current.estadoPrazos).toBe("erro");
    expect(global.fetch).not.toHaveBeenCalled();
  });

  it("painel sem o bloco em-aberto devolve lista vazia, nunca `undefined` vazando pra vista", async () => {
    mockarRotas((p) =>
      p === "/api/sessoes" ? { ok: true, body: sessoesFake } : { ok: true, body: { resumo: {} } },
    );
    const { result } = renderHook(() => useCalendario("tok"));
    await waitFor(() => expect(result.current.estadoPrazos).toBe("pronto"));
    expect(result.current.obrigacoes).toEqual([]);
    // sem placar não há como AFIRMAR corte — e a tela não inventa um aviso.
    expect(result.current.truncamentoPrazos).toBeNull();
  });

  // O CRÍTICO da fatia. Medido na fonte do backend, não suposto:
  // `compliance/components/repositorio.clj` (`painel`) tem `:or {limite-em-aberto 100}`, o controller
  // (`compliance/controllers.clj`) chama `(repo/painel repo (:ente-id ator) {})` — o default vale SEMPRE —
  // e `compliance/db/obrigacao.clj` (`listar-em-aberto`) aplica `:limit limite` com
  // `:order-by [[:vence_em :asc] [:id :asc]]`. `PainelOut` é `{:closed true}` com só
  // resumo/em-aberto/remessas-recentes: NÃO existe flag de truncamento. Como `vencida` tem `vence_em` no
  // PASSADO e entra no mesmo filtro, o backlog ocupa os primeiros slots e a remessa FUTURA do TCE cai fora.
  // `ResumoOut` conta por fase SEM teto (`resumo-por-estado`, GROUP BY sobre a mesma tabela e o mesmo
  // tenant), então `pendente + vencida` é o único sinal de corte que vem no payload.
  it("lista cortada em silêncio pelo backend é DETECTADA pelo placar sem teto do mesmo payload", async () => {
    const painelCortado = {
      resumo: { pendente: 5, cumprida: 12, vencida: 3, dispensada: 0, cancelada: 0 },
      "em-aberto": [painelFake["em-aberto"][0]], // 1 item para 8 em aberto: a página é menor que o conjunto
      "remessas-recentes": [],
    };
    mockarRotas((p) =>
      p === "/api/sessoes" ? { ok: true, body: sessoesFake } : { ok: true, body: painelCortado },
    );
    const { result } = renderHook(() => useCalendario("tok"));
    await waitFor(() => expect(result.current.estadoPrazos).toBe("pronto"));
    expect(result.current.truncamentoPrazos).toEqual({ exibidos: 1, total: 8 });
    // e o que veio continua utilizável: detectar corte não é apagar a lista
    expect(result.current.obrigacoes).toHaveLength(1);
  });

  it("lista MAIOR que o placar não é corte — a tela não afirma truncamento por incoerência de payload", async () => {
    const incoerente = {
      resumo: { pendente: 0, cumprida: 0, vencida: 0, dispensada: 0, cancelada: 0 },
      "em-aberto": [painelFake["em-aberto"][0]],
      "remessas-recentes": [],
    };
    mockarRotas((p) =>
      p === "/api/sessoes" ? { ok: true, body: sessoesFake } : { ok: true, body: incoerente },
    );
    const { result } = renderHook(() => useCalendario("tok"));
    await waitFor(() => expect(result.current.estadoPrazos).toBe("pronto"));
    expect(result.current.truncamentoPrazos).toBeNull();
  });
});
