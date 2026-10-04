import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { CopilotoAnalise } from "./copiloto-analise";
import type { ResultadoAnalise } from "@/lib/copiloto-analise-vista";

// Sem jest-dom (convenção do projeto): asserções por textContent e atributos crus.

const rascunho: ResultadoAnalise = {
  tipo: "rascunho",
  normas: "sem-normas",
  analise: {
    texto: "A proposição tem por objeto hortas.\n\n[confirmar: a iniciativa]",
    citacoes: [
      { fonteId: "materia:p1", rotulo: "Projeto de Lei nº 12/2026", trecho: "Institui hortas", status: "conferida" },
      { fonteId: "norma:n1#art99", rotulo: null, trecho: "inventado", status: "fonte_nao_lida" },
    ],
    paragrafosSemFonte: [1],
    pontosAConfirmar: ["a iniciativa"],
    incerteza: "revisar_com_atencao",
    modelo: "fake-1",
  },
};

describe("CopilotoAnalise", () => {
  afterEach(() => cleanup());

  it("pede o rascunho e mostra selo, avisos, pontos a confirmar e de onde veio — sem mexer no campo", async () => {
    const pedir = vi.fn(async () => rascunho);
    const aoUsar = vi.fn();
    render(<CopilotoAnalise pedir={pedir} analiseAtual="" aoUsar={aoUsar} />);
    fireEvent.click(screen.getByRole("button", { name: "Rascunhar análise de constitucionalidade e juridicidade" }));
    expect(await screen.findByText(/Rascunho da IA — revise antes de salvar\. Não é parecer/)).toBeTruthy();
    expect(pedir).toHaveBeenCalledTimes(1);
    const notas = screen.getAllByRole("note").map((n) => n.textContent);
    expect(notas.some((t) => /ainda não publicou a Lei Orgânica/.test(t ?? ""))).toBe(true);
    expect(notas.some((t) => /não conferiu/.test(t ?? ""))).toBe(true);
    expect(screen.getByText("a iniciativa")).toBeTruthy();
    expect(screen.getByLabelText("Texto do rascunho").textContent).toContain("A proposição tem por objeto hortas.");
    expect(screen.getByText("De onde veio (2)")).toBeTruthy();
    expect(screen.getByText("(não conferida)", { exact: false })).toBeTruthy();
    expect(aoUsar).not.toHaveBeenCalled();
  });

  it("com o id da execução na IA oferece 'Reportar erro' junto do rascunho (8.4); sem id, não oferece", async () => {
    for (const execucaoIa of ["e-ia-rel-1", undefined]) {
      const comId: ResultadoAnalise =
        rascunho.tipo === "rascunho" ? { ...rascunho, analise: { ...rascunho.analise, execucaoIa } } : rascunho;
      render(<CopilotoAnalise pedir={async () => comId} analiseAtual="" aoUsar={vi.fn()} token="tok" />);
      fireEvent.click(screen.getByRole("button", { name: /Rascunhar análise/ }));
      await screen.findByLabelText("Texto do rascunho");
      expect(screen.queryByRole("button", { name: "Reportar erro" }) !== null).toBe(execucaoIa !== undefined);
      cleanup();
    }
  });

  it("campo vazio: 'Usar no campo Análise' entrega o texto ao formulário e recolhe o rascunho", async () => {
    const aoUsar = vi.fn();
    render(<CopilotoAnalise pedir={async () => rascunho} analiseAtual="" aoUsar={aoUsar} />);
    fireEvent.click(screen.getByRole("button", { name: /Rascunhar análise/ }));
    fireEvent.click(await screen.findByRole("button", { name: "Usar no campo Análise" }));
    expect(aoUsar).toHaveBeenCalledWith(rascunho.tipo === "rascunho" ? rascunho.analise.texto : "");
    expect(screen.queryByLabelText("Texto do rascunho")).toBeNull();
    expect(screen.getByRole("status").textContent).toMatch(/Revise, resolva os pontos a confirmar e salve/);
  });

  it("campo com texto: substituir ou acrescentar ao fim, nunca apagar em silêncio", async () => {
    const aoUsar = vi.fn();
    render(<CopilotoAnalise pedir={async () => rascunho} analiseAtual="Minha análise." aoUsar={aoUsar} />);
    fireEvent.click(screen.getByRole("button", { name: /Rascunhar análise/ }));
    expect(await screen.findByRole("button", { name: "Substituir o texto da Análise" })).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Usar no campo Análise" })).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Acrescentar ao fim da Análise" }));
    expect(aoUsar).toHaveBeenCalledWith(
      "Minha análise.\n\nA proposição tem por objeto hortas.\n\n[confirmar: a iniciativa]",
    );
  });

  it("IA fora: a mensagem R-IA-1 e nada no campo", async () => {
    const aoUsar = vi.fn();
    render(
      <CopilotoAnalise
        pedir={async () => ({ tipo: "nada", mensagem: "O assistente está indisponível agora." })}
        analiseAtual=""
        aoUsar={aoUsar}
      />,
    );
    fireEvent.click(screen.getByRole("button", { name: /Rascunhar análise/ }));
    expect((await screen.findByRole("status")).textContent).toBe("O assistente está indisponível agora.");
    expect(aoUsar).not.toHaveBeenCalled();
  });

  it("descartar recolhe o rascunho sem tocar no campo", async () => {
    const aoUsar = vi.fn();
    render(<CopilotoAnalise pedir={async () => rascunho} analiseAtual="" aoUsar={aoUsar} />);
    fireEvent.click(screen.getByRole("button", { name: /Rascunhar análise/ }));
    fireEvent.click(await screen.findByRole("button", { name: "Descartar o rascunho" }));
    expect(screen.queryByLabelText("Texto do rascunho")).toBeNull();
    expect(aoUsar).not.toHaveBeenCalled();
  });
});
