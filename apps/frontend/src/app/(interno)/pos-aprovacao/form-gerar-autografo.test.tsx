import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { FormGerarAutografo } from "./form-gerar-autografo";

const aoGerar = vi.fn();

function montar(over: { enviando?: boolean; erro?: string | null } = {}) {
  return render(
    <FormGerarAutografo
      aoGerar={aoGerar}
      enviando={over.enviando ?? false}
      erro={over.erro ?? null}
      hoje="2026-10-05"
    />,
  );
}

const campo = () => screen.getByLabelText(/prazo de sanção ou veto/i) as HTMLInputElement;
const botao = () => screen.getByRole("button", { name: /gerar autógrafo e enviar ao executivo/i });

afterEach(() => {
  cleanup();
  aoGerar.mockClear();
});

describe("FormGerarAutografo", () => {
  it("o campo de data existe, é opcional e não deixa escolher dia que já passou", () => {
    montar();
    expect(campo().type).toBe("date");
    expect(campo().required).toBe(false);
    expect(campo().min).toBe("2026-10-05");
    expect(campo().value).toBe("");
  });

  it("não inventa prazo: o campo nasce vazio e a tela diz que o prazo é o da Lei Orgânica", () => {
    montar();
    expect(campo().value).toBe("");
    expect(screen.getByText(/Lei Orgânica do Município/i)).toBeTruthy();
  });

  it("sem data -> gera sem prazo e avisa que o sistema não vai acompanhar o vencimento", () => {
    montar();
    expect(screen.getByText(/sem prazo informado/i)).toBeTruthy();
    fireEvent.click(botao());
    expect(aoGerar).toHaveBeenCalledWith(undefined);
  });

  it("com data -> mostra por extenso até quando o Executivo pode responder e avisa que não se corrige", () => {
    montar();
    fireEvent.change(campo(), { target: { value: "2026-10-20" } });
    expect(screen.getByText("O Executivo tem até 20/10/2026 para sancionar ou vetar.")).toBeTruthy();
    expect(screen.getByText(/depois de gerado, o prazo não pode ser alterado/i)).toBeTruthy();
  });

  it("com data -> envia o fim do dia no fuso da Casa", () => {
    montar();
    fireEvent.change(campo(), { target: { value: "2026-10-20" } });
    fireEvent.click(botao());
    expect(aoGerar).toHaveBeenCalledWith("2026-10-20T23:59:59-03:00");
  });

  it("data que já passou -> frase de erro, foco no alerta e nada é enviado", () => {
    montar();
    fireEvent.change(campo(), { target: { value: "2026-10-04" } });
    fireEvent.click(botao());
    expect(screen.getByRole("alert").textContent).toBe("O prazo não pode ser uma data que já passou.");
    expect(aoGerar).not.toHaveBeenCalled();
  });

  it("o erro devolvido pelo servidor aparece como frase em role=alert", () => {
    montar({ erro: "O pedido não foi aceito. Confira a data do prazo." });
    expect(screen.getByRole("alert").textContent).toMatch(/Confira a data do prazo/);
  });

  it("enviando -> botão e campo ficam inacessíveis", () => {
    montar({ enviando: true });
    expect((botao() as HTMLButtonElement).disabled).toBe(true);
    expect(campo().disabled).toBe(true);
  });
});
