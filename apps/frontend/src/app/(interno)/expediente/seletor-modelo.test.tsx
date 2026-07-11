import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { SeletorModelo } from "./seletor-modelo";

const modelos = [
  { id: "m1", chave: "oficio", nome: "Ofício padrão", tipoDocumento: "oficio" },
  { id: "m2", chave: "certidao", nome: "Certidão padrão", tipoDocumento: "certidao" },
  { id: "m3", chave: "req_adm", nome: "Requerimento adm.", tipoDocumento: "requerimento_administrativo" },
];

describe("SeletorModelo", () => {
  afterEach(() => cleanup());

  it("renderiza um botão por modelo ativo + a opção Mala-direta sempre desabilitada", () => {
    render(<SeletorModelo modelos={modelos} selecionadoId={null} aoSelecionar={vi.fn()} />);
    expect(screen.getByRole("button", { name: /ofício padrão/i })).toBeTruthy();
    expect(screen.getByRole("button", { name: /certidão padrão/i })).toBeTruthy();
    expect(screen.getByRole("button", { name: /requerimento adm\./i })).toBeTruthy();
    const mala = screen.getByRole("button", { name: /mala-direta/i }) as HTMLButtonElement;
    expect(mala.disabled).toBe(true);
  });

  it("aria-pressed reflete o modelo selecionado", () => {
    render(<SeletorModelo modelos={modelos} selecionadoId="m2" aoSelecionar={vi.fn()} />);
    expect(screen.getByRole("button", { name: /ofício padrão/i }).getAttribute("aria-pressed")).toBe("false");
    expect(screen.getByRole("button", { name: /certidão padrão/i }).getAttribute("aria-pressed")).toBe("true");
  });

  it("clicar num modelo chama aoSelecionar com o id", () => {
    const aoSelecionar = vi.fn();
    render(<SeletorModelo modelos={modelos} selecionadoId={null} aoSelecionar={aoSelecionar} />);
    fireEvent.click(screen.getByRole("button", { name: /certidão padrão/i }));
    expect(aoSelecionar).toHaveBeenCalledWith("m2");
  });

  it("desabilitado desliga todos os botões reais (mas Mala-direta já era desabilitada)", () => {
    render(<SeletorModelo modelos={modelos} selecionadoId="m1" aoSelecionar={vi.fn()} desabilitado />);
    expect((screen.getByRole("button", { name: /ofício padrão/i }) as HTMLButtonElement).disabled).toBe(true);
  });

  it("lista vazia -> nenhum botão de modelo real, só a Mala-direta desabilitada", () => {
    render(<SeletorModelo modelos={[]} selecionadoId={null} aoSelecionar={vi.fn()} />);
    expect(screen.queryAllByRole("button").length).toBe(1);
  });
});
