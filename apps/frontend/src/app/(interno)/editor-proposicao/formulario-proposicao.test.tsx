import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { FormularioProposicao } from "./formulario-proposicao";

// Nota de adaptação ao brief (Task 17): o brief usa matchers de @testing-library/jest-dom
// (toBeInTheDocument/toBeDisabled/toHaveTextContent), mas este projeto NÃO tem jest-dom instalado
// (convenção documentada em em-breve.test.tsx / azulejo-faixa.test.tsx / capa.test.tsx: asserções via
// textContent/atributo cru). Mesma intenção de cada asserção do brief, reescrita sem o pacote ausente.

describe("FormularioProposicao", () => {
  // sem `test.globals: true` no vitest.config.ts, o auto-cleanup embutido do @testing-library/react
  // não se registra sozinho (mesmo padrão de src/lib/auth.test.tsx) — cleanup() manual evita vazar
  // render entre os `it` deste describe (achado real: getMultipleElementsFoundError sem isto).
  afterEach(() => {
    cleanup();
  });

  it("renderiza os campos base e o botao com o rotulo passado", () => {
    render(<FormularioProposicao aoSubmeter={vi.fn()} enviando={false} erro={null} rotuloAcaoPrimaria="Protocolar" />);
    expect(screen.getByLabelText(/espécie/i)).toBeTruthy();
    expect(screen.getByLabelText(/ementa/i)).toBeTruthy();
    expect(screen.getByRole("button", { name: "Protocolar" })).toBeTruthy();
  });

  it("mostra o campo condicional 'objeto da indicação' so' quando a especie e' indicacao", () => {
    render(<FormularioProposicao aoSubmeter={vi.fn()} enviando={false} erro={null} rotuloAcaoPrimaria="Protocolar" />);
    expect(screen.queryByLabelText(/objeto da indicação/i)).toBeNull();
    fireEvent.change(screen.getByLabelText(/espécie/i), { target: { value: "indicacao" } });
    expect(screen.getByLabelText(/objeto da indicação/i)).toBeTruthy();
  });

  it("helper de inserção 'Art. Nº' insere o snippet no textarea", () => {
    render(<FormularioProposicao aoSubmeter={vi.fn()} enviando={false} erro={null} rotuloAcaoPrimaria="Protocolar" />);
    const textarea = screen.getByLabelText(/texto da proposição/i) as HTMLTextAreaElement;
    fireEvent.click(screen.getByRole("button", { name: /inserir artigo/i }));
    expect(textarea.value).toContain("Art. ");
  });

  it("desabilita o botao primario quando 'enviando'", () => {
    render(<FormularioProposicao aoSubmeter={vi.fn()} enviando erro={null} rotuloAcaoPrimaria="Protocolar" />);
    const botao = screen.getByRole("button", { name: "Protocolar" }) as HTMLButtonElement;
    expect(botao.disabled).toBe(true);
  });

  it("bloquearIdentidade desabilita Espécie e Ano (imutáveis pós-protocolo)", () => {
    render(<FormularioProposicao aoSubmeter={vi.fn()} enviando={false} erro={null} rotuloAcaoPrimaria="Salvar alterações" bloquearIdentidade />);
    const especie = screen.getByLabelText(/espécie/i) as HTMLSelectElement;
    const ano = screen.getByLabelText(/^ano$/i) as HTMLInputElement;
    expect(especie.disabled).toBe(true);
    expect(ano.disabled).toBe(true);
  });

  it("mostra a mensagem de erro quando presente", () => {
    render(<FormularioProposicao aoSubmeter={vi.fn()} enviando={false} erro="falha ao protocolar" rotuloAcaoPrimaria="Protocolar" />);
    expect(screen.getByRole("alert").textContent).toBe("falha ao protocolar");
  });

  it("chama aoSubmeter com o corpo preenchido", () => {
    const aoSubmeter = vi.fn();
    render(<FormularioProposicao aoSubmeter={aoSubmeter} enviando={false} erro={null} rotuloAcaoPrimaria="Protocolar" />);
    fireEvent.change(screen.getByLabelText(/ementa/i), { target: { value: "Cria o Programa X" } });
    fireEvent.click(screen.getByRole("button", { name: "Protocolar" }));
    expect(aoSubmeter).toHaveBeenCalledWith(expect.objectContaining({ ementa: "Cria o Programa X" }));
  });
});
