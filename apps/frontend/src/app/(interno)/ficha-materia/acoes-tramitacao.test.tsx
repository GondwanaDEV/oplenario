import { describe, expect, it, vi, afterEach } from "vitest";
import { render, screen, waitFor, fireEvent, cleanup } from "@testing-library/react";
import { AcoesTramitacao } from "./acoes-tramitacao";

function tramitacaoFake(over: Record<string, unknown> = {}) {
  return {
    "proposicao-id": "p1",
    "estado-atual": "em_comissoes",
    "template-id": "t1",
    "estado-terminal": false,
    "historico-truncado": false,
    "gatilhos-possiveis": [
      { gatilho: "aprovar_parecer", "destinos-possiveis": ["aprovada"], "pode-ser-recusado": true, "exige-autorizacao": false },
    ],
    nota: null,
    "recebimento-pendente": null,
    ...over,
  };
}

describe("AcoesTramitacao", () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it("mostra estado atual e os atos; ao clicar dispara POST {gatilho}, recarrega e chama onTramitou", async () => {
    const onTramitou = vi.fn();
    const fetchMock = vi.fn(async (_url: string, opts?: { method?: string }) => {
      if (opts?.method === "POST") return { ok: true, json: async () => ({}) } as Response;
      return { ok: true, json: async () => tramitacaoFake() } as Response;
    });
    global.fetch = fetchMock as unknown as typeof fetch;

    render(<AcoesTramitacao proposicaoId="p1" token="tok" onTramitou={onTramitou} />);

    // estado atual + botão do ato (rótulo humanizado, gatilho preservado no disparo)
    expect(await screen.findByText(/estado atual: em_comissoes/i)).toBeTruthy();
    const botao = await screen.findByRole("button", { name: "Aprovar parecer" });

    fireEvent.click(botao);

    await waitFor(() =>
      expect(fetchMock).toHaveBeenCalledWith(
        "/api/legislativo/proposicoes/p1/tramitacao",
        expect.objectContaining({ method: "POST", body: JSON.stringify({ gatilho: "aprovar_parecer" }) }),
      ),
    );
    await waitFor(() => expect(onTramitou).toHaveBeenCalled());
  });

  it("sem atos → mostra a nota do servidor, sem botões de ato", async () => {
    global.fetch = vi.fn(async () =>
      ({ ok: true, json: async () => tramitacaoFake({ "gatilhos-possiveis": [], nota: "Estado terminal." }) }) as Response,
    ) as unknown as typeof fetch;

    render(<AcoesTramitacao proposicaoId="p1" token="tok" />);

    expect(await screen.findByText("Estado terminal.")).toBeTruthy();
    expect(screen.queryByRole("button")).toBeNull();
  });

  it("erro do backend no disparo vira alerta na tela", async () => {
    const fetchMock = vi.fn(async (_url: string, opts?: { method?: string }) => {
      if (opts?.method === "POST") return { ok: false, status: 409, json: async () => ({ erro: "o rito recusou o ato" }) } as Response;
      return { ok: true, json: async () => tramitacaoFake() } as Response;
    });
    global.fetch = fetchMock as unknown as typeof fetch;

    render(<AcoesTramitacao proposicaoId="p1" token="tok" />);
    const botao = await screen.findByRole("button", { name: "Aprovar parecer" });
    fireEvent.click(botao);

    const alerta = await screen.findByRole("alert");
    expect(alerta.textContent).toContain("o rito recusou o ato");
  });

  it("carga pendente: esconde os atos; receber pede 2 toques e o 2º assina a movimentação vista", async () => {
    const onTramitou = vi.fn();
    let recebida = false;
    const fetchMock = vi.fn(async (_url: string, opts?: { method?: string }) => {
      if (opts?.method === "POST") {
        recebida = true;
        return { ok: true, status: 201, json: async () => ({ id: "r1" }) } as Response;
      }
      return {
        ok: true,
        json: async () =>
          tramitacaoFake({
            "recebimento-pendente": recebida
              ? null
              : {
                  "movimentacao-id": "m1",
                  "de-estado": "protocolada",
                  estado: "em_comissoes",
                  "estado-nome": "Em Comissões",
                  desde: "2026-09-26T10:00:00",
                  restrito: false,
                },
          }),
      } as Response;
    });
    global.fetch = fetchMock as unknown as typeof fetch;

    render(<AcoesTramitacao proposicaoId="p1" token="tok" onTramitou={onTramitou} />);

    expect(await screen.findByText(/Aguardando recebimento/i)).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Aprovar parecer" })).toBeNull();

    fireEvent.click(screen.getByRole("button", { name: "Receber e assinar" }));
    expect(fetchMock).not.toHaveBeenCalledWith(expect.stringContaining("/recebimento"), expect.anything());
    fireEvent.click(await screen.findByRole("button", { name: "Assinar recebimento" }));

    await waitFor(() =>
      expect(fetchMock).toHaveBeenCalledWith(
        "/api/legislativo/proposicoes/p1/recebimento",
        expect.objectContaining({ method: "POST", body: JSON.stringify({ "movimentacao-id": "m1" }) }),
      ),
    );
    // recebida: o painel recarrega, os atos voltam, e a ficha é avisada (o histórico ganha o recibo)
    expect(await screen.findByRole("button", { name: "Aprovar parecer" })).toBeTruthy();
    await waitFor(() => expect(onTramitou).toHaveBeenCalled());
  });

  it("carga: a matéria andou depois que a tela carregou → alerta e recarrega", async () => {
    const fetchMock = vi.fn(async (_url: string, opts?: { method?: string }) => {
      if (opts?.method === "POST")
        return { ok: false, status: 409, json: async () => ({ motivo: "movimentacao-divergente" }) } as Response;
      return {
        ok: true,
        json: async () =>
          tramitacaoFake({
            "recebimento-pendente": {
              "movimentacao-id": "m1",
              "de-estado": null,
              estado: "em_comissoes",
              "estado-nome": "Em Comissões",
              desde: "2026-09-26T10:00:00",
              restrito: true,
            },
          }),
      } as Response;
    });
    global.fetch = fetchMock as unknown as typeof fetch;

    render(<AcoesTramitacao proposicaoId="p1" token="tok" />);
    expect(await screen.findByText(/pode ser recusado/i)).toBeTruthy();
    fireEvent.click(await screen.findByRole("button", { name: "Receber e assinar" }));
    fireEvent.click(await screen.findByRole("button", { name: "Assinar recebimento" }));
    expect((await screen.findByRole("alert")).textContent).toMatch(/se movimentou/);
    await waitFor(() => expect(fetchMock.mock.calls.filter((c) => !c[1]?.method).length).toBeGreaterThan(1));
  });
});
