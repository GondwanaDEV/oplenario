import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { FormAgendarSessao } from "./form-agendar-sessao";
import type { SessaoOut } from "@/lib/contrato-sessoes.gen";

const agendar = vi.fn();
const useSessoesMock = vi.fn();
const useAgendarMock = vi.fn();

vi.mock("@/lib/use-sessoes", () => ({ useSessoes: (...a: unknown[]) => useSessoesMock(...a) }));
vi.mock("@/lib/use-agendar-sessao", () => ({ useAgendarSessao: (...a: unknown[]) => useAgendarMock(...a) }));
vi.mock("@/lib/use-comissoes", () => ({
  useComissoes: () => ({ fase: "pronto", comissoes: [{ id: "c1", nome: "Comissão de Meio Ambiente" }] }),
}));
vi.mock("@/lib/use-proposicoes", () => ({ useProposicoes: () => ({ dados: { itens: [] }, estado: "pronto" }) }));

function sessao(sessaoLegislativaId: string, n: number): SessaoOut {
  return {
    id: `s-${n}`, sessaoLegislativaId, tipoSessao: "ordinaria", numeroSequencial: n, estado: "encerrada",
    modalidade: "presencial", delibera: true, transmitePublica: true, geraAtaRegimental: true,
    permiteVotoSecreto: false, permiteModalidadeRemota: false, lockVersion: 0,
  };
}

function montar(sessoes: SessaoOut[]) {
  useSessoesMock.mockReturnValue({ sessoes, estado: "pronto", recarregar: vi.fn() });
  useAgendarMock.mockReturnValue({ agendar, estado: "ocioso", erro: null });
  return render(<FormAgendarSessao token="tok" />);
}

afterEach(() => {
  cleanup();
  agendar.mockReset();
});

describe("FormAgendarSessao", () => {
  it("com uma única sessão legislativa, agenda usando-a (auto-selecionada) sem pedir o período", async () => {
    agendar.mockResolvedValue({ ok: true, sessao: sessao("sl-A", 15) });
    montar([sessao("sl-A", 14)]);
    // não há seletor de período quando só há um
    expect(screen.queryByLabelText(/Sessão legislativa/)).toBeNull();
    fireEvent.change(screen.getByLabelText("Tipo de sessão"), { target: { value: "solene" } });
    fireEvent.change(screen.getByLabelText(/Data e hora/), { target: { value: "2026-05-21T14:00" } });
    fireEvent.click(screen.getByRole("button", { name: /Agendar sessão/ }));
    await waitFor(() =>
      expect(agendar).toHaveBeenCalledWith(
        expect.objectContaining({ sessaoLegislativaId: "sl-A", tipoSessao: "solene" }),
      ),
    );
    const args = agendar.mock.calls[0][0];
    expect(args.agendadaPara).toMatch(/^2026-05-21T/); // ISO derivado do datetime-local
  });

  it("no sucesso mostra a sessão criada com link para conduzir", async () => {
    agendar.mockResolvedValue({ ok: true, sessao: sessao("sl-A", 15) });
    montar([sessao("sl-A", 14)]);
    fireEvent.click(screen.getByRole("button", { name: /Agendar sessão/ }));
    await screen.findByText(/nº 15/);
    const link = screen.getByRole("link", { name: /Conduzir a sessão/ });
    expect(link.getAttribute("href")).toMatch(/\/sessoes\/s-15\/conduzir/);
  });

  it("com várias sessões legislativas, exige a escolha do período", async () => {
    agendar.mockResolvedValue({ ok: true, sessao: sessao("sl-A", 1) });
    montar([sessao("sl-A", 1), sessao("sl-B", 1)]);
    // sem escolher período -> erro, sem chamar agendar
    fireEvent.click(screen.getByRole("button", { name: /Agendar sessão/ }));
    await screen.findByText(/Escolha a sessão legislativa/i);
    expect(agendar).not.toHaveBeenCalled();
    // escolhe e agenda
    fireEvent.change(screen.getByLabelText(/Sessão legislativa/), { target: { value: "sl-B" } });
    fireEvent.click(screen.getByRole("button", { name: /Agendar sessão/ }));
    await waitFor(() => expect(agendar).toHaveBeenCalledWith(expect.objectContaining({ sessaoLegislativaId: "sl-B" })));
  });

  it("audiência pública (ADR-0021 A1): o bloco aparece só com esse tipo e vai no corpo", async () => {
    agendar.mockResolvedValue({ ok: true, sessao: { ...sessao("sl-A", 3), tipoSessao: "audiencia_publica" } });
    montar([sessao("sl-A", 14)]);
    expect(screen.queryByLabelText("Comissão que promove")).toBeNull();
    fireEvent.change(screen.getByLabelText("Tipo de sessão"), { target: { value: "audiencia_publica" } });
    fireEvent.change(screen.getByLabelText("Comissão que promove"), { target: { value: "c1" } });
    fireEvent.change(screen.getByLabelText("Tema"), { target: { value: "Mobilidade urbana" } });
    fireEvent.change(screen.getByLabelText("Finalidade"), { target: { value: "metas_fiscais" } });
    // metas fiscais pede o quadrimestre, com os meses explicados
    expect(screen.getByText(/1º quadrimestre: jan–abr/)).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: /Agendar sessão/ }));
    await screen.findByText(/informe o ano.*quadrimestre/i);
    expect(agendar).not.toHaveBeenCalled();
    fireEvent.change(screen.getByLabelText("Ano"), { target: { value: "2026" } });
    fireEvent.change(screen.getByLabelText("Quadrimestre"), { target: { value: "Q1" } });
    fireEvent.change(screen.getByLabelText("Tempo de fala (minutos)"), { target: { value: "3" } });
    fireEvent.click(screen.getByRole("button", { name: /Agendar sessão/ }));
    await waitFor(() => expect(agendar).toHaveBeenCalled());
    expect(agendar.mock.calls[0][0]).toMatchObject({
      tipoSessao: "audiencia_publica",
      audiencia: { "comissao-id": "c1", tema: "Mobilidade urbana", finalidade: "metas_fiscais", referencia: "2026-Q1", "tempo-fala-segundos": 180 },
    });
    const mesa = await screen.findByRole("link", { name: /Mesa da audiência/ });
    expect(mesa.getAttribute("href")).toMatch(/\/sessoes\/s-3\/audiencia/);
  });

  it("sem comissão, a audiência não vai ao servidor", async () => {
    montar([sessao("sl-A", 14)]);
    fireEvent.change(screen.getByLabelText("Tipo de sessão"), { target: { value: "audiencia_publica" } });
    fireEvent.click(screen.getByRole("button", { name: /Agendar sessão/ }));
    await screen.findByText(/Escolha a comissão/);
    expect(agendar).not.toHaveBeenCalled();
  });

  it("outros tipos não levam o bloco `audiencia`", async () => {
    agendar.mockResolvedValue({ ok: true, sessao: sessao("sl-A", 15) });
    montar([sessao("sl-A", 14)]);
    fireEvent.click(screen.getByRole("button", { name: /Agendar sessão/ }));
    await waitFor(() => expect(agendar).toHaveBeenCalled());
    expect(agendar.mock.calls[0][0].audiencia).toBeNull();
  });

  it("Casa sem sessões -> nota honesta e botão desabilitado", () => {
    montar([]);
    expect(screen.getByText(/Nenhuma sessão legislativa encontrada/i)).toBeTruthy();
    expect(screen.getByRole("button", { name: /Agendar sessão/ })).toHaveProperty("disabled", true);
  });
});
