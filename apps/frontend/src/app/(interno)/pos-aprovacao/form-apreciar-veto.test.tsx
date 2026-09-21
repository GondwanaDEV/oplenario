import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { FormApreciarVeto } from "./form-apreciar-veto";

const aoApreciar = vi.fn();
const aoCancelar = vi.fn();

function montar(over: { enviando?: boolean; erro?: string | null } = {}) {
  return render(
    <FormApreciarVeto
      aoApreciar={aoApreciar}
      aoCancelar={aoCancelar}
      enviando={over.enviando ?? false}
      erro={over.erro ?? null}
    />,
  );
}

afterEach(() => {
  cleanup();
  aoApreciar.mockClear();
  aoCancelar.mockClear();
});

describe("FormApreciarVeto", () => {
  it("sem resultado -> valida e não chama aoApreciar", () => {
    montar();
    fireEvent.click(screen.getByRole("button", { name: /Registrar apreciação/ }));
    expect(screen.getByRole("alert").textContent).toMatch(/Selecione o resultado/i);
    expect(aoApreciar).not.toHaveBeenCalled();
  });

  it("resultado sem ID de votação -> valida o campo obrigatório", () => {
    montar();
    fireEvent.click(screen.getByLabelText(/Veto derrubado/));
    fireEvent.click(screen.getByRole("button", { name: /Registrar apreciação/ }));
    expect(screen.getByRole("alert").textContent).toMatch(/ID da votação/i);
    expect(aoApreciar).not.toHaveBeenCalled();
  });

  it("resultado + votação -> chama aoApreciar com o id aparado", () => {
    montar();
    fireEvent.click(screen.getByLabelText(/Veto mantido/));
    fireEvent.change(screen.getByLabelText(/ID da votação/), { target: { value: "  vt-123  " } });
    fireEvent.click(screen.getByRole("button", { name: /Registrar apreciação/ }));
    expect(aoApreciar).toHaveBeenCalledWith({ resultado: "veto_mantido", vetoVotacaoId: "vt-123" });
  });

  it("mostra o erro vindo do servidor e cancela", () => {
    montar({ erro: "tramitacao ja apreciada" });
    expect(screen.getByRole("alert").textContent).toMatch(/ja apreciada/);
    fireEvent.click(screen.getByRole("button", { name: /Cancelar/ }));
    expect(aoCancelar).toHaveBeenCalled();
  });
});
