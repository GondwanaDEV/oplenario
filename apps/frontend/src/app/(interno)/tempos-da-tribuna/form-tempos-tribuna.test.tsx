import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { FormTemposTribuna } from "./form-tempos-tribuna";

const salvar = vi.fn();
const useTemposMock = vi.fn();
vi.mock("@/lib/use-tempos-tribuna", () => ({ useTemposTribuna: (...a: unknown[]) => useTemposMock(...a) }));

const itens = [
  { fase: null, tipoFala: "principal", segundos: 180, referenciaNormativa: "RI art. 98" },
  { fase: "ordem_do_dia", tipoFala: "principal", segundos: 600, referenciaNormativa: null },
];

function montar(over: Record<string, unknown> = {}) {
  useTemposMock.mockReturnValue({ itens, estado: "pronto", salvar, ...over });
  return render(<FormTemposTribuna token="tok" />);
}

afterEach(() => {
  cleanup();
  salvar.mockReset();
});

function linha(nome: string) {
  return screen.getByRole("group", { name: nome });
}

describe("FormTemposTribuna", () => {
  it("mostra os seis tipos de fala com o que a Casa já configurou", () => {
    montar();
    for (const nome of ["Fala principal", "Aparte", "Pela ordem", "Questão de ordem", "Explicação pessoal", "Comunicado"]) {
      expect(linha(nome)).toBeTruthy();
    }
    const principal = linha("Fala principal");
    expect((within(principal).getByLabelText("Tempo") as HTMLInputElement).value).toBe("3:00");
    expect((within(principal).getByLabelText(/Referência/) as HTMLInputElement).value).toBe("RI art. 98");
    expect((within(linha("Aparte")).getByLabelText("Tempo") as HTMLInputElement).value).toBe("");
  });

  it("o tempo por fase já configurado aparece aberto; nos outros tipos fica recolhido", () => {
    montar();
    expect((within(linha("Fala principal")).getByLabelText("Ordem do Dia") as HTMLInputElement).value).toBe("10:00");
    expect(within(linha("Aparte")).queryByLabelText("Ordem do Dia")).toBeNull();
    fireEvent.click(within(linha("Aparte")).getByRole("button", { name: /Tempo diferente por fase/ }));
    expect(within(linha("Aparte")).getByLabelText("Ordem do Dia")).toBeTruthy();
  });

  it("sem mudança não há o que salvar; editar mostra a barra e salva a tabela inteira", async () => {
    salvar.mockResolvedValue({ ok: true });
    montar();
    expect(screen.queryByRole("button", { name: "Salvar tempos" })).toBeNull();
    fireEvent.change(within(linha("Aparte")).getByLabelText("Tempo"), { target: { value: "1" } });
    expect(screen.getByText(/Alterações não salvas/)).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Salvar tempos" }));
    await waitFor(() =>
      expect(salvar).toHaveBeenCalledWith(
        expect.arrayContaining([
          { fase: null, tipoFala: "principal", segundos: 180, referenciaNormativa: "RI art. 98" },
          { fase: "ordem_do_dia", tipoFala: "principal", segundos: 600, referenciaNormativa: null },
          { fase: null, tipoFala: "aparte", segundos: 60, referenciaNormativa: null },
        ]),
      ),
    );
    expect(salvar.mock.calls[0][0]).toHaveLength(3);
    expect(await screen.findByText(/Tempos salvos/)).toBeTruthy();
  });

  it("tempo inválido mostra o erro no campo, foca nele e não chama o servidor", async () => {
    montar();
    const campo = within(linha("Aparte")).getByLabelText("Tempo");
    fireEvent.change(campo, { target: { value: "3:99" } });
    fireEvent.click(screen.getByRole("button", { name: "Salvar tempos" }));
    expect(salvar).not.toHaveBeenCalled();
    expect(campo.getAttribute("aria-invalid")).toBe("true");
    await waitFor(() => expect(document.activeElement).toBe(campo)); // leva a pessoa ao campo errado
    expect(within(linha("Aparte")).getByText(/segundos/)).toBeTruthy();
  });

  it("descartar volta ao que está salvo", () => {
    montar();
    const campo = within(linha("Fala principal")).getByLabelText("Tempo") as HTMLInputElement;
    fireEvent.change(campo, { target: { value: "5" } });
    fireEvent.click(screen.getByRole("button", { name: "Descartar" }));
    expect(campo.value).toBe("3:00");
    expect(screen.queryByText(/Alterações não salvas/)).toBeNull();
  });

  it("erro do servidor aparece na barra", async () => {
    salvar.mockResolvedValue({ ok: false, erro: "Não foi possível salvar os tempos (status 500)." });
    montar();
    fireEvent.change(within(linha("Aparte")).getByLabelText("Tempo"), { target: { value: "1" } });
    fireEvent.click(screen.getByRole("button", { name: "Salvar tempos" }));
    expect(await screen.findByText(/Não foi possível salvar os tempos/)).toBeTruthy();
  });

  it("carregando e erro de leitura", () => {
    montar({ estado: "carregando", itens: [] });
    expect(screen.getByText(/Carregando/)).toBeTruthy();
    cleanup();
    montar({ estado: "erro", itens: [] });
    expect(screen.getByText(/Não foi possível carregar os tempos/)).toBeTruthy();
  });
});
