import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { PainelTribuna } from "./painel-tribuna";
import type { ComposicaoSessaoOut, InscritoTribunaOut, TribunaOut } from "@/lib/contrato-sessoes.gen";

const inscrever = vi.fn().mockResolvedValue({ ok: true, recibo: { id: "i9", ordem: 2 } });
const desistir = vi.fn().mockResolvedValue({ ok: true });
const iniciarFala = vi.fn().mockResolvedValue({ ok: true, recibo: { falaId: "f1" } });
const registrarEventoCronometro = vi.fn().mockResolvedValue({ ok: true });
const encerrarFala = vi.fn().mockResolvedValue({ ok: true, fala: { falaId: "f1", tempoSegundos: 100 } });
const useTribunaMock = vi.fn();
vi.mock("@/lib/use-tribuna-mesa", () => ({
  useTribunaMesa: (...a: unknown[]) => useTribunaMock(...a),
}));

const membros = [
  { vereadorId: "v1", nomeParlamentar: "Ana Prado", cargoMesa: "Presidente", partido: null },
  { vereadorId: "v2", nomeParlamentar: "Beto Lima", cargoMesa: null, partido: null },
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
    iniciarFala,
    registrarEventoCronometro,
    encerrarFala,
  });
  return render(<PainelTribuna sessaoId="s1" token="tok" />);
}

const orador = (over: Partial<TribunaOut["oradorAtual"]> = {}): NonNullable<TribunaOut["oradorAtual"]> => ({
  falaId: "f1", oradorId: "v1", tipoFala: "principal", fase: "expediente",
  iniciouEm: "2026-05-21T14:00:00Z", inscricaoId: "i1", lockVersion: 4, ...over,
} as NonNullable<TribunaOut["oradorAtual"]>);

afterEach(() => {
  cleanup();
  inscrever.mockClear();
  desistir.mockClear();
  iniciarFala.mockClear();
  registrarEventoCronometro.mockClear();
  encerrarFala.mockClear();
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

describe("PainelTribuna — execução (chamar à tribuna)", () => {
  it("sem orador ativo, 'Chamar à tribuna' inicia a fala do inscrito (orador+fase+inscrição)", async () => {
    montar({ tribuna: { sessaoId: "s1", oradorAtual: null, marcosCronometro: [], inscritos: [inscrito({ vereadorId: "v2", fase: "ordem_do_dia", inscricaoId: "iZ" })] } });
    fireEvent.click(screen.getByRole("button", { name: /Chamar à tribuna/ }));
    await waitFor(() => expect(iniciarFala).toHaveBeenCalledWith("v2", "ordem_do_dia", { inscricaoId: "iZ" }));
  });

  it("com um orador ativo, 'Chamar à tribuna' não aparece nas outras linhas", () => {
    montar({
      tribuna: {
        sessaoId: "s1",
        oradorAtual: orador({ oradorId: "v1", inscricaoId: "i1" }),
        marcosCronometro: [],
        inscritos: [inscrito({ inscricaoId: "i1", vereadorId: "v1" }), inscrito({ inscricaoId: "i2", vereadorId: "v2", ordem: 2 })],
      },
    });
    expect(screen.queryByRole("button", { name: /Chamar à tribuna/ })).toBeNull();
  });
});

describe("PainelTribuna — cronômetro", () => {
  it("mostra o relógio da fala e o nome na tribuna", () => {
    montar({ tribuna: { sessaoId: "s1", oradorAtual: orador(), marcosCronometro: [], inscritos: [inscrito({})] } });
    expect(screen.getByText(/Na tribuna: Ana Prado/)).toBeTruthy();
    // relógio no formato mm:ss (ou hh:mm:ss)
    expect(screen.getByRole("group", { name: /Cronômetro da fala/ })).toBeTruthy();
  });

  it("Pausar registra o evento 'pausada'; Retomar registra 'retomada' quando já pausado", async () => {
    montar({ tribuna: { sessaoId: "s1", oradorAtual: orador(), marcosCronometro: [], inscritos: [] } });
    fireEvent.click(screen.getByRole("button", { name: /^Pausar$/ }));
    await waitFor(() => expect(registrarEventoCronometro).toHaveBeenCalledWith("f1", "pausada", undefined));
    registrarEventoCronometro.mockClear();
    cleanup();
    montar({ tribuna: { sessaoId: "s1", oradorAtual: orador(), marcosCronometro: [{ tipo: "pausada", ocorridoEm: "2026-05-21T14:01:00Z", segundosAdicionais: null }], inscritos: [] } });
    fireEvent.click(screen.getByRole("button", { name: /^Retomar$/ }));
    await waitFor(() => expect(registrarEventoCronometro).toHaveBeenCalledWith("f1", "retomada", undefined));
  });

  it("+1 min concede 60s; Aparte concede aparte", async () => {
    montar({ tribuna: { sessaoId: "s1", oradorAtual: orador(), marcosCronometro: [], inscritos: [] } });
    fireEvent.click(screen.getByRole("button", { name: /\+1 min/ }));
    await waitFor(() => expect(registrarEventoCronometro).toHaveBeenCalledWith("f1", "tempo_adicional_concedido", 60));
    fireEvent.click(screen.getByRole("button", { name: /^Aparte$/ }));
    await waitFor(() => expect(registrarEventoCronometro).toHaveBeenCalledWith("f1", "aparte_concedido", undefined));
  });

  it("Encerrar fala chama encerrarFala com o lock-version do orador", async () => {
    montar({ tribuna: { sessaoId: "s1", oradorAtual: orador({ lockVersion: 7 }), marcosCronometro: [], inscritos: [] } });
    fireEvent.click(screen.getByRole("button", { name: /Encerrar fala/ }));
    await waitFor(() => expect(encerrarFala).toHaveBeenCalledWith("f1", 7));
  });
});
