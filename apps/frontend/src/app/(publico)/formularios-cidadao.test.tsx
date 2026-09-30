import { afterEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { FormEsic, FormLgpd, FormOuvidoria } from "./formularios-cidadao";

const ENTE = "10000000-0000-0000-0000-000000000001";
const cidada = { estado: "cidada" as const, token: "tok" };

function respondeCom(status: number, corpo: unknown) {
  global.fetch = vi.fn(async () => ({ ok: status < 300, status, json: async () => corpo }) as Response) as unknown as typeof fetch;
}
const corpoEnviado = () => JSON.parse(String(vi.mocked(global.fetch).mock.calls[0][1]?.body));

describe("formulários do cidadão", () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it("anônima: no lugar do formulário, o convite do gov.br que volta a este formulário", () => {
    render(<FormEsic ente={ENTE} sessao={{ estado: "anonima", token: null }} />);
    const link = screen.getByRole("link", { name: /entrar com gov\.br/i });
    expect(decodeURIComponent(link.getAttribute("href")!)).toContain(`redirect=/portal/casa/${ENTE}/esic/novo`);
    expect(screen.queryByLabelText(/assunto/i)).toBeNull();
  });

  it("e-SIC: envia assunto e descrição e mostra o recibo com o protocolo", async () => {
    respondeCom(201, { protocolo: "ESIC-2026-000007", "recibo-em": "2026-07-03T12:00:00Z" });
    render(<FormEsic ente={ENTE} sessao={cidada} />);
    fireEvent.change(screen.getByLabelText(/assunto/i), { target: { value: "Contratos de 2025" } });
    fireEvent.change(screen.getByLabelText(/o que você quer saber/i), { target: { value: "A lista dos contratos." } });
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: /enviar pedido/i }));
    });
    await waitFor(() => expect(screen.getByText("ESIC-2026-000007")).toBeTruthy());
    expect(corpoEnviado()).toEqual({ assunto: "Contratos de 2025", descricao: "A lista dos contratos." });
    expect(vi.mocked(global.fetch).mock.calls[0][0]).toBe("/api/portal/esic/pedidos");
    expect(screen.getByRole("link", { name: /meus protocolos/i }).getAttribute("href")).toBe("/meus-protocolos");
  });

  it("Casa com o sistema restrito (ADR-0018): o recibo diz, e o pedido vale", async () => {
    respondeCom(201, { protocolo: "ESIC-2026-000008", "recibo-em": "2026-09-30T12:00:00Z",
      "acesso-restrito-desde": "2026-09-29T13:00:00Z" });
    render(<FormEsic ente={ENTE} sessao={cidada} />);
    fireEvent.change(screen.getByLabelText(/assunto/i), { target: { value: "Contratos de 2025" } });
    fireEvent.change(screen.getByLabelText(/o que você quer saber/i), { target: { value: "A lista dos contratos." } });
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: /enviar pedido/i }));
    });
    await waitFor(() => expect(screen.getByText("ESIC-2026-000008")).toBeTruthy());
    expect(screen.getByText(/acesso restrito desde 29\/09\. Seu pedido foi recebido normalmente e o prazo legal/)).toBeTruthy();
  });

  it("Casa ativa: o recibo não fala de restrição", async () => {
    respondeCom(201, { protocolo: "ESIC-2026-000009", "recibo-em": "2026-09-30T12:00:00Z" });
    render(<FormEsic ente={ENTE} sessao={cidada} />);
    fireEvent.change(screen.getByLabelText(/assunto/i), { target: { value: "Contratos de 2025" } });
    fireEvent.change(screen.getByLabelText(/o que você quer saber/i), { target: { value: "A lista dos contratos." } });
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: /enviar pedido/i }));
    });
    await waitFor(() => expect(screen.getByText("ESIC-2026-000009")).toBeTruthy());
    expect(screen.queryByText(/acesso restrito/)).toBeNull();
  });

  it("e-SIC: campo vazio não vai ao backend e diz o que falta", async () => {
    respondeCom(201, {});
    render(<FormEsic ente={ENTE} sessao={cidada} />);
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: /enviar pedido/i }));
    });
    expect(screen.getByRole("alert").textContent).toMatch(/assunto/i);
    expect(global.fetch).not.toHaveBeenCalled();
  });

  it("LGPD: o direito que veio do balcão já vem marcado", async () => {
    respondeCom(201, { protocolo: "LGPD-2026-000001", "recibo-em": "2026-07-03T12:00:00Z" });
    render(<FormLgpd ente={ENTE} sessao={cidada} tipoInicial="eliminar" />);
    expect((screen.getByLabelText(/eliminar meus dados/i) as HTMLInputElement).checked).toBe(true);
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: /enviar pedido/i }));
    });
    await waitFor(() => expect(screen.getByText("LGPD-2026-000001")).toBeTruthy());
    expect(corpoEnviado()).toEqual({ tipo: "eliminar" });
  });

  it("ouvidoria: sem se identificar vai anonima=true e o recibo avisa que só o protocolo acompanha", async () => {
    respondeCom(201, { protocolo: "OUV-2026-000003", "recibo-em": "2026-07-03T12:00:00Z" });
    render(<FormOuvidoria ente={ENTE} sessao={cidada} />);
    fireEvent.click(screen.getByLabelText(/sugestão/i));
    fireEvent.change(screen.getByLabelText(/^assunto/i), { target: { value: "Horário" } });
    fireEvent.change(screen.getByLabelText(/descrição/i), { target: { value: "Abrir mais cedo." } });
    fireEvent.click(screen.getByLabelText(/manifestar sem me identificar/i));
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: /enviar manifestação/i }));
    });
    await waitFor(() => expect(screen.getByText("OUV-2026-000003")).toBeTruthy());
    expect(corpoEnviado()).toEqual({ tipo: "sugestao", assunto: "Horário", descricao: "Abrir mais cedo.", anonima: true });
    expect(screen.getByText(/guarde este número/i)).toBeTruthy();
    expect(screen.queryByRole("link", { name: /meus protocolos/i })).toBeNull();
  });

  it("ouvidoria: o trilho acompanha pelo protocolo (rota pública)", async () => {
    respondeCom(200, { protocolo: "OUV-2026-000003", estado: "em_analise", "dias-restantes": 12 });
    render(<FormOuvidoria ente={ENTE} sessao={{ estado: "anonima", token: null }} />);
    fireEvent.change(screen.getByLabelText(/número do protocolo/i), { target: { value: "OUV-2026-000003" } });
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: /^ver$/i }));
    });
    await waitFor(() => expect(screen.getByText(/em análise/i)).toBeTruthy());
    expect(vi.mocked(global.fetch).mock.calls[0][0]).toBe(`/api/portal/casa/${ENTE}/ouvidoria/acompanhar/OUV-2026-000003`);
  });
});
