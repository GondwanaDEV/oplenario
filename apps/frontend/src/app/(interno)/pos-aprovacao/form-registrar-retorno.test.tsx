import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { FormRegistrarRetorno } from "./form-registrar-retorno";

// Mirror da disciplina de parecer/formulario-parecer.test.tsx: sem jest-dom, validação de campo
// obrigatório em JS (role=alert focável), campos condicionais por resultado.

describe("FormRegistrarRetorno", () => {
  afterEach(() => cleanup());

  it("renderiza as 3 opções de resultado; campos de veto ficam ocultos por padrão", () => {
    render(<FormRegistrarRetorno aoRegistrar={vi.fn()} aoCancelar={vi.fn()} enviando={false} erro={null} />);
    expect(screen.getByRole("radiogroup", { name: /resultado do executivo/i })).toBeTruthy();
    expect(screen.getByRole("radio", { name: "Sancionado" })).toBeTruthy();
    expect(screen.getByRole("radio", { name: "Sanção tácita" })).toBeTruthy();
    expect(screen.getByRole("radio", { name: "Vetado" })).toBeTruthy();
    expect(screen.queryByRole("radiogroup", { name: /tipo do veto/i })).toBeNull();
    expect(screen.queryByLabelText(/razões do veto/i)).toBeNull();
  });

  it("selecionar 'Vetado' revela tipo do veto + razões; outro resultado não revela", () => {
    render(<FormRegistrarRetorno aoRegistrar={vi.fn()} aoCancelar={vi.fn()} enviando={false} erro={null} />);
    fireEvent.click(screen.getByRole("radio", { name: "Vetado" }));
    expect(screen.getByRole("radiogroup", { name: /tipo do veto/i })).toBeTruthy();
    expect(screen.getByLabelText(/razões do veto/i)).toBeTruthy();

    fireEvent.click(screen.getByRole("radio", { name: "Sancionado" }));
    expect(screen.queryByRole("radiogroup", { name: /tipo do veto/i })).toBeNull();
  });

  it("'Registrar retorno' sem resultado selecionado -> não chama aoRegistrar; mostra validação", () => {
    const aoRegistrar = vi.fn();
    render(<FormRegistrarRetorno aoRegistrar={aoRegistrar} aoCancelar={vi.fn()} enviando={false} erro={null} />);
    fireEvent.click(screen.getByRole("button", { name: /registrar retorno/i }));
    expect(aoRegistrar).not.toHaveBeenCalled();
    expect(screen.getByRole("alert").textContent).toMatch(/resultado/i);
  });

  it("'Vetado' sem tipo de veto selecionado -> não chama aoRegistrar; mostra validação específica", () => {
    const aoRegistrar = vi.fn();
    render(<FormRegistrarRetorno aoRegistrar={aoRegistrar} aoCancelar={vi.fn()} enviando={false} erro={null} />);
    fireEvent.click(screen.getByRole("radio", { name: "Vetado" }));
    fireEvent.click(screen.getByRole("button", { name: /registrar retorno/i }));
    expect(aoRegistrar).not.toHaveBeenCalled();
    expect(screen.getByRole("alert").textContent).toMatch(/tipo do veto/i);
  });

  it("resultado 'sancionado' chama aoRegistrar sem campos de veto", () => {
    const aoRegistrar = vi.fn();
    render(<FormRegistrarRetorno aoRegistrar={aoRegistrar} aoCancelar={vi.fn()} enviando={false} erro={null} />);
    fireEvent.click(screen.getByRole("radio", { name: "Sancionado" }));
    fireEvent.click(screen.getByRole("button", { name: /registrar retorno/i }));
    expect(aoRegistrar).toHaveBeenCalledWith({ resultado: "sancionado", vetoTipo: undefined, vetoRazoes: undefined });
  });

  it("resultado 'vetado' com tipo+razões preenchidos chama aoRegistrar com o payload completo", () => {
    const aoRegistrar = vi.fn();
    render(<FormRegistrarRetorno aoRegistrar={aoRegistrar} aoCancelar={vi.fn()} enviando={false} erro={null} />);
    fireEvent.click(screen.getByRole("radio", { name: "Vetado" }));
    fireEvent.click(screen.getByRole("radio", { name: "Total" }));
    fireEvent.change(screen.getByLabelText(/razões do veto/i), { target: { value: "Inconstitucional." } });
    fireEvent.click(screen.getByRole("button", { name: /registrar retorno/i }));
    expect(aoRegistrar).toHaveBeenCalledWith({
      resultado: "vetado",
      vetoTipo: "total",
      vetoRazoes: "Inconstitucional.",
    });
  });

  it("erro do backend (prop) fica visível em role=alert", () => {
    render(
      <FormRegistrarRetorno
        aoRegistrar={vi.fn()}
        aoCancelar={vi.fn()}
        enviando={false}
        erro="tramitacao executiva nao encontrada"
      />,
    );
    expect(screen.getByRole("alert").textContent).toBe("tramitacao executiva nao encontrada");
  });

  it("'Cancelar' chama aoCancelar", () => {
    const aoCancelar = vi.fn();
    render(<FormRegistrarRetorno aoRegistrar={vi.fn()} aoCancelar={aoCancelar} enviando={false} erro={null} />);
    fireEvent.click(screen.getByRole("button", { name: /cancelar/i }));
    expect(aoCancelar).toHaveBeenCalled();
  });

  it("enviando -> desabilita os 2 botões", () => {
    render(<FormRegistrarRetorno aoRegistrar={vi.fn()} aoCancelar={vi.fn()} enviando erro={null} />);
    expect((screen.getByRole("button", { name: /cancelar/i }) as HTMLButtonElement).disabled).toBe(true);
    expect((screen.getByRole("button", { name: /registrar retorno/i }) as HTMLButtonElement).disabled).toBe(true);
  });
});
