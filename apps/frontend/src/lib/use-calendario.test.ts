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
  "em-aberto-total": 1,
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
    // sem `em-aberto-total` não há como AFIRMAR corte — e a tela não inventa um aviso.
    expect(result.current.truncamentoPrazos).toBeNull();
  });

  // O CRÍTICO da fatia. `compliance/wire/out/painel.clj` agora emite `em-aberto-total` — o par
  // lista+total, mesmo racional de `transparencia/wire/out/parlamentar` — contando TODAS as obrigações
  // em aberto (pendente+vencida) da Casa, sem o teto de 100 que `listar-em-aberto` aplica. É o substituto
  // AUTORITATIVO do que antes era deduzido somando `resumo.pendente + resumo.vencida`.
  it("lista cortada em silêncio pelo backend é DETECTADA pelo total autoritativo do servidor", async () => {
    const painelCortado = {
      resumo: { pendente: 5, cumprida: 12, vencida: 3, dispensada: 0, cancelada: 0 },
      "em-aberto": [painelFake["em-aberto"][0]], // 1 item para 8 em aberto: a página é menor que o conjunto
      "em-aberto-total": 8,
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

  it("total autoritativo igual ao exibido não é corte — mesmo com resumo incoerente", async () => {
    const semCorte = {
      resumo: { pendente: 0, cumprida: 0, vencida: 0, dispensada: 0, cancelada: 0 },
      "em-aberto": [painelFake["em-aberto"][0]],
      "em-aberto-total": 1,
      "remessas-recentes": [],
    };
    mockarRotas((p) =>
      p === "/api/sessoes" ? { ok: true, body: sessoesFake } : { ok: true, body: semCorte },
    );
    const { result } = renderHook(() => useCalendario("tok"));
    await waitFor(() => expect(result.current.estadoPrazos).toBe("pronto"));
    expect(result.current.truncamentoPrazos).toBeNull();
  });

  // A asserção que MATA a heurística antiga: monta um payload em que `resumo.pendente+vencida` (o sinal
  // velho) e `em-aberto-total` (o campo novo) DISCORDAM nos dois sentidos, e prova que a tela segue
  // SEMPRE o servidor, nunca a soma do resumo. Sem este teste, ninguém notaria se a heurística antiga
  // tivesse ficado viva por engano ao lado do campo novo.
  it("segue o total do servidor, não a soma do resumo, quando os dois discordam", async () => {
    // resumo soma 1 (bateria com a lista de 1 item -> heurística antiga diria SEM corte);
    // em-aberto-total diz 8 (o servidor sabe que há corte). A tela tem de acusar o corte.
    const servidorDizCorte = {
      resumo: { pendente: 1, cumprida: 0, vencida: 0, dispensada: 0, cancelada: 0 },
      "em-aberto": [painelFake["em-aberto"][0]],
      "em-aberto-total": 8,
      "remessas-recentes": [],
    };
    mockarRotas((p) =>
      p === "/api/sessoes" ? { ok: true, body: sessoesFake } : { ok: true, body: servidorDizCorte },
    );
    const { result: r1 } = renderHook(() => useCalendario("tok"));
    await waitFor(() => expect(r1.current.estadoPrazos).toBe("pronto"));
    expect(r1.current.truncamentoPrazos).toEqual({ exibidos: 1, total: 8 });

    // resumo soma 8 (heurística antiga diria CORTE); em-aberto-total diz 1, igual ao exibido (o servidor
    // sabe que NÃO há corte — os 8 do resumo incluem fases fora do filtro de em-aberto, p.ex.). A tela
    // não pode acusar corte que o servidor nega.
    const servidorDizSemCorte = {
      resumo: { pendente: 5, cumprida: 0, vencida: 3, dispensada: 0, cancelada: 0 },
      "em-aberto": [painelFake["em-aberto"][0]],
      "em-aberto-total": 1,
      "remessas-recentes": [],
    };
    mockarRotas((p) =>
      p === "/api/sessoes" ? { ok: true, body: sessoesFake } : { ok: true, body: servidorDizSemCorte },
    );
    const { result: r2 } = renderHook(() => useCalendario("tok"));
    await waitFor(() => expect(r2.current.estadoPrazos).toBe("pronto"));
    expect(r2.current.truncamentoPrazos).toBeNull();
  });
});
