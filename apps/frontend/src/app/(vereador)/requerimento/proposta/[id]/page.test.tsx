import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import PaginaPropostaRequerimento from "./page";

vi.mock("next/navigation", () => ({ useParams: () => ({ id: "p-1" }), useRouter: () => ({ back: vi.fn() }) }));
vi.mock("@/lib/auth", () => ({ useAuth: () => ({ token: "tok" }) }));

const responder = vi.fn();
const protocolar = vi.fn();
const useProposta = vi.fn();
vi.mock("@/lib/use-subscricao", () => ({ usePropostaRequerimento: (...a: unknown[]) => useProposta(...a) }));

const base = {
  id: "p-1",
  ementa: "Informações sobre a obra X",
  tipoRequerimento: "Requerimento de informação",
  texto: "TEXTO CONGELADO",
  autorNome: "Ana Prado",
  estado: "aguardando_subscricoes",
  proposicaoId: null,
  criadaEm: "2026-09-26T12:00:00Z",
  souAutor: true,
  minhaSubscricao: null,
  subscricoes: [
    { vereadorNome: "Bia Lima", estado: "confirmada", respondidaEm: "2026-09-26T13:00:00Z" },
    { vereadorNome: "Caio Reis", estado: "pendente", respondidaEm: null },
  ],
};

function montar(proposta: Record<string, unknown> = {}) {
  useProposta.mockReturnValue({ proposta: { ...base, ...proposta }, estado: "pronto", enviando: false, responder, protocolar });
  return render(<PaginaPropostaRequerimento />);
}

afterEach(() => {
  cleanup();
  responder.mockReset();
  protocolar.mockReset();
});

describe("Proposta de requerimento coletivo — autor", () => {
  it("mostra o texto, quem confirmou e quem aguarda", () => {
    montar();
    expect(screen.getByText("TEXTO CONGELADO")).toBeTruthy();
    expect(screen.getByText("Subscrição confirmada")).toBeTruthy();
    expect(screen.getByText("Aguarda confirmação")).toBeTruthy();
    expect(screen.getByText(/1 de 2 coautores confirmou/)).toBeTruthy();
  });

  it("protocolar em 2 toques, avisando quem não constará; o recibo lista os coautores", async () => {
    protocolar.mockResolvedValue({
      ok: true,
      dados: { proposicaoId: "x", ano: 2026, sequencial: 9, urnLex: "u", estado: "protocolada", assinaturaAlgoritmo: "STUB-ICP-v0", coautores: ["Bia Lima"] },
    });
    montar();
    fireEvent.click(screen.getByRole("button", { name: "Revisar e protocolar" }));
    expect(protocolar).not.toHaveBeenCalled();
    expect(screen.getByText("Caio Reis ainda não respondeu e não constará.")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Confirmar e protocolar" }));
    expect(await screen.findByText("Requerimento nº 9/2026 protocolado")).toBeTruthy();
    expect(screen.getByText(/Com o coautor Bia Lima/)).toBeTruthy();
  });

  it("já protocolada: sem ações, diz que a lista fechou", () => {
    montar({ estado: "protocolada", proposicaoId: "x" });
    expect(screen.queryByRole("button", { name: "Revisar e protocolar" })).toBeNull();
    expect(screen.getByText(/A lista de coautores fechou/)).toBeTruthy();
  });
});

describe("Proposta de requerimento coletivo — coautor convidado", () => {
  it("confirmar em 2 toques assina a subscrição", async () => {
    responder.mockResolvedValue({ ok: true, dados: { estado: "confirmada" } });
    montar({ souAutor: false, minhaSubscricao: "pendente" });
    expect(screen.getByRole("heading", { name: "Pedido de subscrição" })).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Revisar e assinar a subscrição" }));
    expect(responder).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "Confirmar e assinar" }));
    await waitFor(() => expect(responder).toHaveBeenCalledWith("confirmar"));
    expect(await screen.findByText(/Sua assinatura já consta/)).toBeTruthy();
  });

  it("recusar", async () => {
    responder.mockResolvedValue({ ok: true, dados: { estado: "recusada" } });
    montar({ souAutor: false, minhaSubscricao: "pendente" });
    fireEvent.click(screen.getByRole("button", { name: "Recusar convite" }));
    await waitFor(() => expect(responder).toHaveBeenCalledWith("recusar"));
  });

  it("erro do servidor (ex.: o autor protocolou antes) aparece na folha", async () => {
    responder.mockResolvedValue({ ok: false, erro: "este requerimento ja' foi protocolado" });
    montar({ souAutor: false, minhaSubscricao: "pendente" });
    fireEvent.click(screen.getByRole("button", { name: "Revisar e assinar a subscrição" }));
    fireEvent.click(screen.getByRole("button", { name: "Confirmar e assinar" }));
    expect(await screen.findByText(/ja' foi protocolado/)).toBeTruthy();
  });

  it("convite já respondido: sem botões", () => {
    montar({ souAutor: false, minhaSubscricao: "confirmada" });
    expect(screen.queryByRole("button", { name: /assinar a subscrição/ })).toBeNull();
  });
});
