import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { PainelTribuna } from "./painel-tribuna";
import type { ComposicaoSessaoOut, InscritoTribunaOut, TribunaOut } from "@/lib/contrato-sessoes.gen";

const inscrever = vi.fn().mockResolvedValue({ ok: true, recibo: { id: "i9", ordem: 2 } });
const desistir = vi.fn().mockResolvedValue({ ok: true });
const useTribunaMock = vi.fn();
vi.mock("@/lib/use-tribuna-mesa", () => ({
  useTribunaMesa: (...a: unknown[]) => useTribunaMock(...a),
}));

const membros = [
  { vereadorId: "v1", nomeParlamentar: "Ana Prado", cargoMesa: "Presidente" },
  { vereadorId: "v2", nomeParlamentar: "Beto Lima", cargoMesa: null },
];
const composicao: ComposicaoSessaoOut = {
  sessaoId: "s1", sessaoEstado: "aberta", dataDeComposicao: "2026-05-21",
  composicaoResolvidaEm: "2026-05-21T13:00:00Z", membros,
};
const inscrito = (over: Partial<InscritoTribunaOut>): InscritoTribunaOut => ({
  inscricaoId: "i1", vereadorId: "v1", origemInscricao: "intra_sessao_pedido",
  fase: "expediente", ordem: 1, lockVersion: 3, ...over,
});

function montar(over: { tribuna?: TribunaOut | null; composicao?: ComposicaoSessaoOut | null } = {}) {
  useTribunaMock.mockReturnValue({
    tribuna: over.tribuna ?? { sessaoId: "s1", oradorAtual: null, marcosCronometro: [], inscritos: [] },
    composicao: over.composicao ?? composicao,
    estado: "pronto",
    erro: null,
    recarregar: vi.fn(),
    inscrever,
    desistir,
  });
  return render(<PainelTribuna sessaoId="s1" token="tok" />);
}

afterEach(() => {
  cleanup();
  inscrever.mockClear();
  desistir.mockClear();
});

describe("PainelTribuna — fila", () => {
  it("fila vazia -> convite a inscrever", () => {
    montar();
    expect(screen.getByText(/Ninguém inscrito/i)).toBeTruthy();
  });
  it("lista os inscritos por nome+fase e o orador atual", () => {
    montar({
      tribuna: {
        sessaoId: "s1",
        oradorAtual: { falaId: "f", oradorId: "v1", tipoFala: "principal", fase: "expediente", iniciouEm: "x", inscricaoId: "i1", lockVersion: 0 },
        marcosCronometro: [],
        inscritos: [inscrito({})],
      },
    });
    expect(screen.getAllByText("Ana Prado").length).toBeGreaterThan(0);
    expect(screen.getByText(/Na tribuna: Ana Prado/)).toBeTruthy();
    expect(screen.getByText("Falando")).toBeTruthy();
    // o orador atual não oferece desistência
    expect(screen.queryByRole("button", { name: /desistência/i })).toBeNull();
  });
});

describe("PainelTribuna — inscrever", () => {
  it("escolher orador + fase e inscrever chama inscrever()", async () => {
    montar();
    fireEvent.change(screen.getByLabelText("Orador"), { target: { value: "v2" } });
    fireEvent.change(screen.getByLabelText("Fase"), { target: { value: "ordem_do_dia" } });
    fireEvent.click(screen.getByRole("button", { name: /^Inscrever$/ }));
    await waitFor(() => expect(inscrever).toHaveBeenCalledWith("v2", "ordem_do_dia"));
  });

  it("inscrever sem escolher orador mostra erro e não chama inscrever()", async () => {
    montar();
    fireEvent.click(screen.getByRole("button", { name: /^Inscrever$/ }));
    // texto COMPLETO do erro — o placeholder "— escolha o orador —" do <select> também casaria um regex curto
    await screen.findByText(/Escolha o orador a inscrever/i);
    expect(inscrever).not.toHaveBeenCalled();
  });

  it("todos já na fila -> sem formulário de inscrição", () => {
    montar({
      tribuna: { sessaoId: "s1", oradorAtual: null, marcosCronometro: [], inscritos: [inscrito({ vereadorId: "v1" }), inscrito({ inscricaoId: "i2", vereadorId: "v2", ordem: 2 })] },
    });
    expect(screen.getByText(/já estão na fila/i)).toBeTruthy();
    expect(screen.queryByLabelText("Orador")).toBeNull();
  });
});

describe("PainelTribuna — desistência", () => {
  it("desistir de um inscrito (não-orador) chama desistir com lock-version", async () => {
    montar({ tribuna: { sessaoId: "s1", oradorAtual: null, marcosCronometro: [], inscritos: [inscrito({ lockVersion: 5 })] } });
    fireEvent.click(screen.getByRole("button", { name: /Registrar desistência/ }));
    await waitFor(() => expect(desistir).toHaveBeenCalledWith("i1", 5));
  });
});
