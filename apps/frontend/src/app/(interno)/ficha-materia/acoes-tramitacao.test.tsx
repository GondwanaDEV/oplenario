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
});
