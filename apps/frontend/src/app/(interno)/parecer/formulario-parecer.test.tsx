import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { FormularioParecer } from "./formulario-parecer";

// Onda B Slice 5 — mirror da disciplina de formulario-proposicao.test.tsx: sem jest-dom (convenção do
// projeto), asserções via textContent/atributo cru.

const valorInicial = { relatorio: "", analise: "", votoRelator: "" };

describe("FormularioParecer", () => {
  afterEach(() => cleanup());

  it("renderiza as 3 seções (Relatório, Análise, Voto) com o valor inicial", () => {
    render(
      <FormularioParecer
        valorInicial={{ relatorio: "R", analise: "A", votoRelator: "" }}
        aoSalvarRascunho={vi.fn()}
        aoEmitir={vi.fn()}
        enviandoRascunho={false}
        enviandoEmissao={false}
        erro={null}
        bloqueado={false}
        mensagemStatus={null}
      />,
    );
    expect((screen.getByLabelText(/relatório/i) as HTMLTextAreaElement).value).toBe("R");
    expect((screen.getByLabelText(/análise/i) as HTMLTextAreaElement).value).toBe("A");
    expect(screen.getByRole("radiogroup", { name: /voto do relator/i })).toBeTruthy();
    expect(screen.getByRole("radio", { name: /favorável com emendas/i })).toBeTruthy();
    expect(screen.getByRole("radio", { name: /^favorável$/i })).toBeTruthy();
    expect(screen.getByRole("radio", { name: /contrário/i })).toBeTruthy();
  });

  it("clicar numa opção de voto marca o radio correspondente e desmarca os outros", () => {
    render(
      <FormularioParecer
        valorInicial={valorInicial}
        aoSalvarRascunho={vi.fn()}
        aoEmitir={vi.fn()}
        enviandoRascunho={false}
        enviandoEmissao={false}
        erro={null}
        bloqueado={false}
        mensagemStatus={null}
      />,
    );
    const favoravel = screen.getByRole("radio", { name: /^favorável$/i }) as HTMLInputElement;
    fireEvent.click(favoravel);
    expect(favoravel.checked).toBe(true);
    expect((screen.getByRole("radio", { name: /contrário/i }) as HTMLInputElement).checked).toBe(false);
  });

  it("'Salvar rascunho' chama aoSalvarRascunho com o relatório/análise atualmente digitados", () => {
    const aoSalvarRascunho = vi.fn();
    render(
      <FormularioParecer
        valorInicial={valorInicial}
        aoSalvarRascunho={aoSalvarRascunho}
        aoEmitir={vi.fn()}
        enviandoRascunho={false}
        enviandoEmissao={false}
        erro={null}
        bloqueado={false}
        mensagemStatus={null}
      />,
    );
    fireEvent.change(screen.getByLabelText(/relatório/i), { target: { value: "novo relatório" } });
    fireEvent.click(screen.getByRole("button", { name: /salvar rascunho/i }));
    expect(aoSalvarRascunho).toHaveBeenCalledWith(
      expect.objectContaining({ relatorio: "novo relatório", analise: "" }),
    );
  });

  it("'Emitir parecer' sem voto selecionado NÃO chama aoEmitir; mostra validação visível", () => {
    const aoEmitir = vi.fn();
    render(
      <FormularioParecer
        valorInicial={valorInicial}
        aoSalvarRascunho={vi.fn()}
        aoEmitir={aoEmitir}
        enviandoRascunho={false}
        enviandoEmissao={false}
        erro={null}
        bloqueado={false}
        mensagemStatus={null}
      />,
    );
    fireEvent.click(screen.getByRole("button", { name: /emitir parecer/i }));
    expect(aoEmitir).not.toHaveBeenCalled();
    expect(screen.getByRole("alert").textContent).toMatch(/voto/i);
  });

  it("'Emitir parecer' com voto selecionado chama aoEmitir com o voto escolhido", () => {
    const aoEmitir = vi.fn();
    render(
      <FormularioParecer
        valorInicial={valorInicial}
        aoSalvarRascunho={vi.fn()}
        aoEmitir={aoEmitir}
        enviandoRascunho={false}
        enviandoEmissao={false}
        erro={null}
        bloqueado={false}
        mensagemStatus={null}
      />,
    );
    fireEvent.click(screen.getByRole("radio", { name: /^favorável$/i }));
    fireEvent.click(screen.getByRole("button", { name: /emitir parecer/i }));
    expect(aoEmitir).toHaveBeenCalledWith(expect.objectContaining({ votoRelator: "favoravel" }));
  });

  it("erro do backend (prop) tem prioridade sobre a validação local e fica visível em role=alert", () => {
    render(
      <FormularioParecer
        valorInicial={valorInicial}
        aoSalvarRascunho={vi.fn()}
        aoEmitir={vi.fn()}
        enviandoRascunho={false}
        enviandoEmissao={false}
        erro="parecer foi alterado por outra pessoa"
        bloqueado={false}
        mensagemStatus={null}
      />,
    );
    expect(screen.getByRole("alert").textContent).toBe("parecer foi alterado por outra pessoa");
  });

  it("'Pré-visualizar' alterna pra uma leitura read-only sem chamar rede", () => {
    render(
      <FormularioParecer
        valorInicial={{ relatorio: "Texto do relatório", analise: "Texto da análise", votoRelator: "favoravel" }}
        aoSalvarRascunho={vi.fn()}
        aoEmitir={vi.fn()}
        enviandoRascunho={false}
        enviandoEmissao={false}
        erro={null}
        bloqueado={false}
        mensagemStatus={null}
      />,
    );
    fireEvent.click(screen.getByRole("button", { name: /pré-visualizar/i }));
    expect(screen.queryByLabelText(/relatório/i)).toBeNull();
    expect(screen.getByText("Texto do relatório")).toBeTruthy();
    expect(screen.getByText("Texto da análise")).toBeTruthy();
    expect(screen.getByText("Favorável")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: /voltar a editar/i }));
    expect(screen.getByLabelText(/relatório/i)).toBeTruthy();
  });

  it("enviandoRascunho desabilita só 'Salvar rascunho'; enviandoEmissao desabilita só 'Emitir'", () => {
    const { rerender } = render(
      <FormularioParecer
        valorInicial={valorInicial}
        aoSalvarRascunho={vi.fn()}
        aoEmitir={vi.fn()}
        enviandoRascunho
        enviandoEmissao={false}
        erro={null}
        bloqueado={false}
        mensagemStatus={null}
      />,
    );
    expect((screen.getByRole("button", { name: /salvar rascunho/i }) as HTMLButtonElement).disabled).toBe(true);
    expect((screen.getByRole("button", { name: /emitir parecer/i }) as HTMLButtonElement).disabled).toBe(false);

    rerender(
      <FormularioParecer
        valorInicial={valorInicial}
        aoSalvarRascunho={vi.fn()}
        aoEmitir={vi.fn()}
        enviandoRascunho={false}
        enviandoEmissao
        erro={null}
        bloqueado={false}
        mensagemStatus={null}
      />,
    );
    expect((screen.getByRole("button", { name: /salvar rascunho/i }) as HTMLButtonElement).disabled).toBe(false);
    expect((screen.getByRole("button", { name: /emitir parecer/i }) as HTMLButtonElement).disabled).toBe(true);
  });

  it("bloqueado (parecer num desfecho terminal) desabilita campos e some com os botões de escrita", () => {
    render(
      <FormularioParecer
        valorInicial={{ relatorio: "R", analise: "A", votoRelator: "favoravel" }}
        aoSalvarRascunho={vi.fn()}
        aoEmitir={vi.fn()}
        enviandoRascunho={false}
        enviandoEmissao={false}
        erro={null}
        bloqueado
        mensagemStatus={null}
      />,
    );
    expect((screen.getByLabelText(/relatório/i) as HTMLTextAreaElement).disabled).toBe(true);
    expect(screen.queryByRole("button", { name: /salvar rascunho/i })).toBeNull();
    expect(screen.queryByRole("button", { name: /emitir parecer/i })).toBeNull();
  });

  it("mensagemStatus aparece no contexto da barra de comando", () => {
    render(
      <FormularioParecer
        valorInicial={valorInicial}
        aoSalvarRascunho={vi.fn()}
        aoEmitir={vi.fn()}
        enviandoRascunho={false}
        enviandoEmissao={false}
        erro={null}
        bloqueado={false}
        mensagemStatus="Rascunho salvo"
      />,
    );
    expect(screen.getByText((_, node) => node?.textContent === "voto: Sem voto registrado · Rascunho salvo")).toBeTruthy();
  });
});
