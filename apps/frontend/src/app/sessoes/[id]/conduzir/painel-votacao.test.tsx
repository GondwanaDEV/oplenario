import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { PainelVotacao } from "./painel-votacao";
import type { ItemPautaVotacao, VotacaoAbertaResumo } from "@/lib/use-votacao-mesa";

const abrir = vi.fn().mockResolvedValue({ ok: true, abertura: { id: "vtX", estado: "aberta", lockVersion: 0 } });
const encerrar = vi.fn().mockResolvedValue({ ok: true, encerramento: { id: "vtX", estado: "encerrada" } });
const useVotacaoMock = vi.fn();
vi.mock("@/lib/use-votacao-mesa", () => ({
  useVotacaoMesa: (...a: unknown[]) => useVotacaoMock(...a),
}));

const itens: ItemPautaVotacao[] = [
  { id: "it1", tipoItem: "proposicao", proposicaoId: "p1", fase: "ordem_do_dia", ordem: 1 },
  { id: "it2", tipoItem: "comunicado", proposicaoId: null, fase: "expediente", ordem: 2 },
];

function montar(over: {
  votacaoAberta?: VotacaoAbertaResumo | null;
  itens?: ItemPautaVotacao[];
  estado?: string;
  sessaoEstado?: string;
}) {
  useVotacaoMock.mockReturnValue({
    votacaoAberta: over.votacaoAberta ?? null,
    itens: over.itens ?? itens,
    estado: over.estado ?? "pronto",
    erro: null,
    recarregar: vi.fn(),
    abrir,
    encerrar,
  });
  return render(<PainelVotacao sessaoId="s1" token="tok" sessaoEstado={over.sessaoEstado ?? "aberta"} />);
}

afterEach(() => {
  cleanup();
  abrir.mockClear();
  encerrar.mockClear();
});

describe("PainelVotacao — abrir", () => {
  it("com pauta, escolher matéria + modalidade + quórum e abrir chama abrir() com kebab-args", async () => {
    montar({ votacaoAberta: null });
    fireEvent.change(screen.getByLabelText(/Objeto da votação/), { target: { value: "p1" } });
    fireEvent.click(screen.getByLabelText("Simbólica"));
    fireEvent.change(screen.getByLabelText(/Quórum exigido/), { target: { value: "maioria_absoluta" } });
    fireEvent.click(screen.getByRole("button", { name: /Abrir votação/ }));
    await waitFor(() =>
      expect(abrir).toHaveBeenCalledWith(
        expect.objectContaining({
          objetoTipo: "proposicao",
          objetoId: "p1",
          modalidade: "simbolica",
          quorumTipo: "maioria_absoluta",
          pautaItemId: "it1",
        }),
      ),
    );
  });

  it("abrir sem escolher objeto mostra erro e não chama abrir()", async () => {
    montar({ votacaoAberta: null });
    fireEvent.click(screen.getByRole("button", { name: /Abrir votação/ }));
    await screen.findByText(/Escolha o objeto/i);
    expect(abrir).not.toHaveBeenCalled();
  });

  it("pauta sem proposição -> nota honesta, sem seletor", () => {
    montar({ votacaoAberta: null, itens: [{ id: "it2", tipoItem: "comunicado", proposicaoId: null, fase: "expediente", ordem: 2 }] });
    expect(screen.getByText(/Nenhuma proposição na pauta/i)).toBeTruthy();
    expect(screen.queryByRole("button", { name: /Abrir votação/ })).toBeNull();
  });
});

describe("PainelVotacao — encerrar", () => {
  const vaNominal: VotacaoAbertaResumo = {
    votacaoId: "vt1",
    modalidade: "nominal",
    objetoTipo: "proposicao",
    objetoId: "p1",
    proposicao: { tipo: "PL", ano: 2026, sequencial: 7, ementa: "Cria o programa X" },
  };

  it("votação nominal em curso: encerrar direto, sem pedir resultado", async () => {
    montar({ votacaoAberta: vaNominal });
    expect(screen.getByText(/Em curso/)).toBeTruthy();
    expect(screen.getByText(/PL 7\/2026/)).toBeTruthy();
    expect(screen.queryByText(/Resultado declarado/i)).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: /Encerrar votação/ }));
    await waitFor(() => expect(encerrar).toHaveBeenCalledWith(undefined));
  });

  it("votação simbólica: exige resultado — vazio bloqueia, escolhido dispara", async () => {
    montar({ votacaoAberta: { ...vaNominal, modalidade: "simbolica" } });
    fireEvent.click(screen.getByRole("button", { name: /Encerrar votação/ }));
    await screen.findByText(/declare o resultado/i);
    expect(encerrar).not.toHaveBeenCalled();
    fireEvent.click(screen.getByLabelText("Aprovada"));
    fireEvent.click(screen.getByRole("button", { name: /Encerrar votação/ }));
    await waitFor(() => expect(encerrar).toHaveBeenCalledWith("aprovada"));
  });
});

describe("PainelVotacao — indisponível", () => {
  it("sessão não aberta -> nota, sem formulário", () => {
    montar({ sessaoEstado: "suspensa" });
    expect(screen.getByText(/só é conduzida com a sessão aberta/i)).toBeTruthy();
    expect(screen.queryByRole("button", { name: /Abrir votação|Encerrar votação/ })).toBeNull();
  });
});
