import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { FormularioPreenchimento } from "./formulario-preenchimento";

// Onda B Slice 6 — mirror da disciplina de formulario-parecer.test.tsx: sem jest-dom, asserções via
// textContent/atributo cru. Duas fases: `documento === null` (composição: assunto + dados dinâmicos +
// "Gerar documento") e `documento` existente (edição: assunto + corpo + "Salvar rascunho"/"Protocolar").

describe("FormularioPreenchimento — fase de composição (documento ainda não existe)", () => {
  afterEach(() => cleanup());

  const propsBase = {
    documento: null,
    modeloSelecionadoId: "m1",
    aoGerar: vi.fn(),
    aoSalvarRascunho: vi.fn(),
    aoProtocolar: vi.fn(),
    enviandoGeracao: false,
    enviandoRascunho: false,
    enviandoProtocolo: false,
    erro: null,
    mensagemStatus: null,
  };

  it("renderiza assunto + uma linha de dados vazia por padrão", () => {
    render(<FormularioPreenchimento {...propsBase} aoGerar={vi.fn()} />);
    expect(screen.getByLabelText(/assunto/i)).toBeTruthy();
    expect(screen.getByLabelText(/campo 1/i)).toBeTruthy();
  });

  it("'+ Adicionar campo' acrescenta uma nova linha de dados", () => {
    render(<FormularioPreenchimento {...propsBase} />);
    fireEvent.click(screen.getByRole("button", { name: /adicionar campo/i }));
    expect(screen.getByLabelText(/campo 2/i)).toBeTruthy();
  });

  it("sem modelo selecionado -> 'Gerar documento' fica desabilitado", () => {
    render(<FormularioPreenchimento {...propsBase} modeloSelecionadoId={null} />);
    expect((screen.getByRole("button", { name: /gerar documento/i }) as HTMLButtonElement).disabled).toBe(true);
  });

  it("'Gerar documento' chama aoGerar com assunto + o mapa de dados preenchido", () => {
    const aoGerar = vi.fn();
    render(<FormularioPreenchimento {...propsBase} aoGerar={aoGerar} />);
    fireEvent.change(screen.getByLabelText(/assunto/i), { target: { value: "Convite" } });
    fireEvent.change(screen.getByLabelText(/campo 1/i), { target: { value: "destinatario" } });
    fireEvent.change(screen.getByLabelText(/valor 1/i), { target: { value: "Prefeito" } });
    fireEvent.click(screen.getByRole("button", { name: /^gerar documento$/i }));
    expect(aoGerar).toHaveBeenCalledWith({ assunto: "Convite", dados: { destinatario: "Prefeito" } });
  });

  it("assunto em branco -> não chama aoGerar, mostra validação visível", () => {
    const aoGerar = vi.fn();
    render(<FormularioPreenchimento {...propsBase} aoGerar={aoGerar} />);
    fireEvent.click(screen.getByRole("button", { name: /^gerar documento$/i }));
    expect(aoGerar).not.toHaveBeenCalled();
    expect(screen.getByRole("alert").textContent).toMatch(/assunto/i);
  });

  it("erro do backend tem prioridade e fica em role=alert", () => {
    render(<FormularioPreenchimento {...propsBase} erro="modelo de documento nao encontrado" />);
    expect(screen.getByRole("alert").textContent).toBe("modelo de documento nao encontrado");
  });

  it("enviandoGeracao desabilita 'Gerar documento'", () => {
    render(<FormularioPreenchimento {...propsBase} enviandoGeracao />);
    expect((screen.getByRole("button", { name: /gerar documento/i }) as HTMLButtonElement).disabled).toBe(true);
  });
});

describe("FormularioPreenchimento — fase de edição (documento já existe, rascunho)", () => {
  afterEach(() => cleanup());

  const documento = {
    id: "d1",
    modeloId: "m1",
    tipoDocumento: "oficio",
    assunto: "Convite",
    corpo: "Ao Prefeito.",
    estado: "rascunho",
    lockVersion: 0,
    criadoEm: "2026-01-01T00:00:00Z",
  };

  const propsBase = {
    documento,
    modeloSelecionadoId: "m1",
    aoGerar: vi.fn(),
    aoSalvarRascunho: vi.fn(),
    aoProtocolar: vi.fn(),
    enviandoGeracao: false,
    enviandoRascunho: false,
    enviandoProtocolo: false,
    erro: null,
    mensagemStatus: null,
  };

  it("renderiza assunto + corpo já preenchidos, sem os campos de dados dinâmicos", () => {
    render(<FormularioPreenchimento {...propsBase} />);
    expect((screen.getByLabelText(/assunto/i) as HTMLInputElement).value).toBe("Convite");
    expect((screen.getByLabelText(/corpo/i) as HTMLTextAreaElement).value).toBe("Ao Prefeito.");
    expect(screen.queryByLabelText(/campo 1/i)).toBeNull();
    expect(screen.queryByRole("button", { name: /^gerar documento$/i })).toBeNull();
  });

  it("'Salvar rascunho' chama aoSalvarRascunho com assunto/corpo atuais", () => {
    const aoSalvarRascunho = vi.fn();
    render(<FormularioPreenchimento {...propsBase} aoSalvarRascunho={aoSalvarRascunho} />);
    fireEvent.change(screen.getByLabelText(/corpo/i), { target: { value: "Ao Vice-Prefeito." } });
    fireEvent.click(screen.getByRole("button", { name: /salvar rascunho/i }));
    expect(aoSalvarRascunho).toHaveBeenCalledWith(
      expect.objectContaining({ assunto: "Convite", corpo: "Ao Vice-Prefeito." }),
    );
  });

  it("'Protocolar e numerar' chama aoProtocolar", () => {
    const aoProtocolar = vi.fn();
    render(<FormularioPreenchimento {...propsBase} aoProtocolar={aoProtocolar} />);
    fireEvent.click(screen.getByRole("button", { name: /protocolar e numerar/i }));
    expect(aoProtocolar).toHaveBeenCalled();
  });

  it("'Gerar PDF' fica sempre desabilitado (rota ainda não existe nesta fatia)", () => {
    render(<FormularioPreenchimento {...propsBase} />);
    expect((screen.getByRole("button", { name: /gerar pdf/i }) as HTMLButtonElement).disabled).toBe(true);
  });

  it("documento emitido (terminal) -> bloqueado: campos desabilitados, sem botões de escrita", () => {
    render(<FormularioPreenchimento {...propsBase} documento={{ ...documento, estado: "emitido" }} />);
    expect((screen.getByLabelText(/corpo/i) as HTMLTextAreaElement).disabled).toBe(true);
    expect(screen.queryByRole("button", { name: /salvar rascunho/i })).toBeNull();
    expect(screen.queryByRole("button", { name: /protocolar e numerar/i })).toBeNull();
  });

  it("mensagemStatus aparece no contexto da barra de ações", () => {
    render(<FormularioPreenchimento {...propsBase} mensagemStatus="Rascunho salvo" />);
    expect(screen.getByText((_, node) => node?.textContent === "Convite — Rascunho salvo")).toBeTruthy();
  });
});
