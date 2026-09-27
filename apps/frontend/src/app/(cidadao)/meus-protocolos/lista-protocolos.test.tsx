import { afterEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { ListaProtocolos } from "./lista-protocolos";
import type { MeusProtocolos } from "@/lib/use-meus-protocolos";

const base = { reciboEm: "2026-07-03T12:00:00Z", venceEm: "2026-07-23", resposta: null };
const DADOS: MeusProtocolos = {
  pedidosEsic: [
    { ...base, id: "p1", protocolo: "ESIC-2026-000001", assunto: "Contratos", estado: "protocolado", diasRestantes: 12 },
    {
      ...base,
      id: "p2",
      protocolo: "ESIC-2026-000002",
      assunto: "Diárias",
      estado: "respondido",
      diasRestantes: 5,
      resposta: { corpo: "Segue a planilha.", respondidaEm: "2026-07-10T15:00:00Z" },
    },
  ],
  solicitacoesLgpd: [{ ...base, id: "s1", protocolo: "LGPD-2026-000001", tipo: "acessar", estado: "em_analise", diasRestantes: -2 }],
  manifestacoes: [],
};

describe("ListaProtocolos — o que a cidadã protocolou", () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it("mostra cada protocolo com estado e prazo; aberto em atraso diz que venceu", () => {
    render(<ListaProtocolos dados={DADOS} token="tok" aoMudar={() => {}} />);
    const p1 = screen.getByText("ESIC-2026-000001").closest("li")!;
    expect(within(p1).getByText("Protocolado")).toBeTruthy();
    expect(within(p1).getByText(/12 dias para a resposta/)).toBeTruthy();
    const s1 = screen.getByText("LGPD-2026-000001").closest("li")!;
    expect(within(s1).getByText(/Acessar meus dados/)).toBeTruthy();
    expect(within(s1).getByText(/prazo venceu há 2 dias/i)).toBeTruthy();
    expect(screen.getByText(/nenhuma manifestação identificada/i)).toBeTruthy();
  });

  it("a resposta da Câmara aparece, e só o e-SIC respondido oferece recurso", () => {
    render(<ListaProtocolos dados={DADOS} token="tok" aoMudar={() => {}} />);
    const p2 = screen.getByText("ESIC-2026-000002").closest("li")!;
    expect(within(p2).getByText("Segue a planilha.")).toBeTruthy();
    expect(within(p2).getByRole("button", { name: /recorrer/i })).toBeTruthy();
    const p1 = screen.getByText("ESIC-2026-000001").closest("li")!;
    expect(within(p1).queryByRole("button", { name: /recorrer/i })).toBeNull();
  });

  it("recorrer: envia o motivo e mostra o protocolo do recurso", async () => {
    global.fetch = vi.fn(async () =>
      ({ ok: true, status: 201, json: async () => ({ protocolo: "REC-2026-000001", "recibo-em": "2026-07-11T12:00:00Z" }) }) as Response,
    ) as unknown as typeof fetch;
    const aoMudar = vi.fn();
    render(<ListaProtocolos dados={DADOS} token="tok" aoMudar={aoMudar} />);
    const p2 = screen.getByText("ESIC-2026-000002").closest("li")!;
    fireEvent.click(within(p2).getByRole("button", { name: /recorrer/i }));
    fireEvent.change(within(p2).getByLabelText(/por que você não concordou/i), { target: { value: "Faltaram os valores." } });
    await act(async () => {
      fireEvent.click(within(p2).getByRole("button", { name: /enviar recurso/i }));
    });
    await waitFor(() => expect(within(p2).getByText(/REC-2026-000001/)).toBeTruthy());
    const [url, init] = vi.mocked(global.fetch).mock.calls[0];
    expect(url).toBe("/api/portal/esic/pedidos/p2/recursos");
    expect(JSON.parse(String(init?.body))).toEqual({ motivo: "Faltaram os valores." });
  });
});
