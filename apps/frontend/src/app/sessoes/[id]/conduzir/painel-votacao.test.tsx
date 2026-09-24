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
  emApreciacaoItemId?: string | null;
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
  return render(
    <PainelVotacao
      sessaoId="s1"
      token="tok"
      sessaoEstado={over.sessaoEstado ?? "aberta"}
      emApreciacaoItemId={over.emApreciacaoItemId ?? null}
    />,
  );
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

  it("o seletor nomeia a matéria pela sigla quando a pauta traz o resumo", () => {
    montar({
      votacaoAberta: null,
      itens: [{ id: "it3", tipoItem: "proposicao", proposicaoId: "p22", fase: "ordem_do_dia", ordem: 3, proposicao: { tipo: "projeto_lei", ano: 2026, sequencial: 22, ementa: "Energia solar" } }],
    });
    expect(screen.getByRole("option", { name: "PL 22/2026 · Ordem do Dia" })).toBeTruthy();
  });

  it("a matéria anunciada (em apreciação) vem pré-escolhida — Abrir votação direto", async () => {
    montar({
      votacaoAberta: null,
      itens: [
        { id: "it1", tipoItem: "proposicao", proposicaoId: "p1", fase: "ordem_do_dia", ordem: 1 },
        { id: "it3", tipoItem: "proposicao", proposicaoId: "p22", fase: "ordem_do_dia", ordem: 3 },
      ],
      emApreciacaoItemId: "it3",
    });
    expect((screen.getByLabelText(/Objeto da votação/) as HTMLSelectElement).value).toBe("p22");
    fireEvent.click(screen.getByRole("button", { name: /Abrir votação/ }));
    await waitFor(() => expect(abrir).toHaveBeenCalledWith(expect.objectContaining({ objetoId: "p22", pautaItemId: "it3" })));
  });

  it("a Mesa pode trocar a matéria pré-escolhida", async () => {
    montar({ votacaoAberta: null, emApreciacaoItemId: "it1", itens: [...itens, { id: "it9", tipoItem: "proposicao", proposicaoId: "p9", fase: "ordem_do_dia", ordem: 9 }] });
    fireEvent.change(screen.getByLabelText(/Objeto da votação/), { target: { value: "p9" } });
    fireEvent.click(screen.getByRole("button", { name: /Abrir votação/ }));
    await waitFor(() => expect(abrir).toHaveBeenCalledWith(expect.objectContaining({ objetoId: "p9", pautaItemId: "it9" })));
  });

  it("um anúncio novo volta a pré-escolher, mesmo depois de a Mesa ter mexido no seletor", () => {
    const doisItens: ItemPautaVotacao[] = [
      { id: "it1", tipoItem: "proposicao", proposicaoId: "p1", fase: "ordem_do_dia", ordem: 1 },
      { id: "it3", tipoItem: "proposicao", proposicaoId: "p22", fase: "ordem_do_dia", ordem: 3 },
    ];
    const { rerender } = montar({ votacaoAberta: null, itens: doisItens, emApreciacaoItemId: "it1" });
    const sel = () => screen.getByLabelText(/Objeto da votação/) as HTMLSelectElement;
    fireEvent.change(sel(), { target: { value: "" } });
    expect(sel().value).toBe("");
    rerender(<PainelVotacao sessaoId="s1" token="tok" sessaoEstado="aberta" emApreciacaoItemId="it3" />);
    expect(sel().value).toBe("p22");
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

  it("o objeto em curso aparece pela sigla, nunca pelo tipo cru do fio", () => {
    montar({ votacaoAberta: { ...vaNominal, proposicao: { tipo: "projeto_lei", ano: 2026, sequencial: 22, ementa: "Energia solar" } } });
    expect(screen.getByText("PL 22/2026")).toBeTruthy();
    expect(screen.queryByText(/projeto_lei/)).toBeNull();
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
