import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { FormularioModelo } from "./formulario-modelo";

// Onda B Slice 6, fatia de escrita — mesma disciplina de formulario-preenchimento.test.tsx: sem jest-dom,
// asserções via textContent/atributo cru. Dois modos: "novo" (chave/nome/tipo/corpo editáveis) e "editar"
// (chave/tipo em exibição, nome/corpo editáveis + "Desativar").

describe("FormularioModelo — modo novo", () => {
  afterEach(() => cleanup());

  const propsBase = {
    modo: "novo" as const,
    modelo: null,
    aoCriar: vi.fn(),
    aoSalvar: vi.fn(),
    aoDesativar: vi.fn(),
    aoCancelar: vi.fn(),
    enviando: false,
    erro: null,
  };

  it("renderiza chave/nome/tipo/corpo, todos editáveis", () => {
    render(<FormularioModelo {...propsBase} />);
    expect((screen.getByLabelText(/chave/i) as HTMLInputElement).disabled).toBe(false);
    expect(screen.getByLabelText(/nome/i)).toBeTruthy();
    expect(screen.getByLabelText(/tipo de documento/i).tagName).toBe("SELECT");
    expect(screen.getByLabelText(/corpo do template/i)).toBeTruthy();
  });

  it("'Criar modelo' chama aoCriar com os 4 campos", () => {
    const aoCriar = vi.fn();
    render(<FormularioModelo {...propsBase} aoCriar={aoCriar} />);
    fireEvent.change(screen.getByLabelText(/chave/i), { target: { value: "oficio_padrao" } });
    fireEvent.change(screen.getByLabelText(/nome/i), { target: { value: "Ofício padrão" } });
    fireEvent.change(screen.getByLabelText(/corpo do template/i), { target: { value: "Prezado {{destinatario}}." } });
    fireEvent.click(screen.getByRole("button", { name: /criar modelo/i }));
    expect(aoCriar).toHaveBeenCalledWith({
      chave: "oficio_padrao", nome: "Ofício padrão", tipoDocumento: expect.any(String),
      corpoTemplate: "Prezado {{destinatario}}.",
    });
  });

  it("chave em branco -> não chama aoCriar, mostra validação visível", () => {
    const aoCriar = vi.fn();
    render(<FormularioModelo {...propsBase} aoCriar={aoCriar} />);
    fireEvent.change(screen.getByLabelText(/nome/i), { target: { value: "X" } });
    fireEvent.change(screen.getByLabelText(/corpo do template/i), { target: { value: "Y" } });
    fireEvent.click(screen.getByRole("button", { name: /criar modelo/i }));
    expect(aoCriar).not.toHaveBeenCalled();
    expect(screen.getByRole("alert").textContent).toMatch(/chave/i);
  });

  it("sem 'Desativar' (modelo ainda não existe)", () => {
    render(<FormularioModelo {...propsBase} />);
    expect(screen.queryByRole("button", { name: /desativar/i })).toBeNull();
  });

  it("'Cancelar' chama aoCancelar", () => {
    const aoCancelar = vi.fn();
    render(<FormularioModelo {...propsBase} aoCancelar={aoCancelar} />);
    fireEvent.click(screen.getByRole("button", { name: /cancelar/i }));
    expect(aoCancelar).toHaveBeenCalled();
  });

  it("erro do backend fica em role=alert", () => {
    render(<FormularioModelo {...propsBase} erro="requisicao invalida" />);
    expect(screen.getByRole("alert").textContent).toBe("requisicao invalida");
  });
});

describe("FormularioModelo — modo editar", () => {
  afterEach(() => cleanup());

  const modelo = {
    id: "m1",
    chave: "oficio_padrao",
    nome: "Ofício padrão",
    tipoDocumento: "oficio",
    corpoTemplate: "Prezado {{destinatario}}.",
    ativo: true,
    lockVersion: 0,
  };

  const propsBase = {
    modo: "editar" as const,
    modelo,
    aoCriar: vi.fn(),
    aoSalvar: vi.fn(),
    aoDesativar: vi.fn(),
    aoCancelar: vi.fn(),
    enviando: false,
    erro: null,
  };

  it("chave/tipo em exibição (desabilitados); nome/corpo editáveis e pré-preenchidos", () => {
    render(<FormularioModelo {...propsBase} />);
    expect((screen.getByLabelText(/chave/i) as HTMLInputElement).disabled).toBe(true);
    expect((screen.getByLabelText(/tipo de documento/i) as HTMLInputElement).disabled).toBe(true);
    expect((screen.getByLabelText(/nome/i) as HTMLInputElement).value).toBe("Ofício padrão");
    expect((screen.getByLabelText(/corpo do template/i) as HTMLTextAreaElement).value).toBe(
      "Prezado {{destinatario}}.",
    );
  });

  it("'Salvar' chama aoSalvar com nome/corpo atuais (sem chave/tipo)", () => {
    const aoSalvar = vi.fn();
    render(<FormularioModelo {...propsBase} aoSalvar={aoSalvar} />);
    fireEvent.change(screen.getByLabelText(/nome/i), { target: { value: "Ofício revisado" } });
    fireEvent.click(screen.getByRole("button", { name: /^salvar$/i }));
    expect(aoSalvar).toHaveBeenCalledWith({ nome: "Ofício revisado", corpoTemplate: "Prezado {{destinatario}}." });
  });

  it("'Desativar' chama aoDesativar (modelo ativo)", () => {
    const aoDesativar = vi.fn();
    render(<FormularioModelo {...propsBase} aoDesativar={aoDesativar} />);
    fireEvent.click(screen.getByRole("button", { name: /desativar/i }));
    expect(aoDesativar).toHaveBeenCalled();
  });

  it("modelo já inativo -> sem 'Desativar', mostra a tag 'Inativo'", () => {
    render(<FormularioModelo {...propsBase} modelo={{ ...modelo, ativo: false }} />);
    expect(screen.queryByRole("button", { name: /desativar/i })).toBeNull();
    expect(screen.getByText(/inativo/i)).toBeTruthy();
  });

  it("enviando desabilita 'Salvar' e 'Desativar'", () => {
    render(<FormularioModelo {...propsBase} enviando />);
    expect((screen.getByRole("button", { name: /^salvar$/i }) as HTMLButtonElement).disabled).toBe(true);
    expect((screen.getByRole("button", { name: /desativar/i }) as HTMLButtonElement).disabled).toBe(true);
  });
});
