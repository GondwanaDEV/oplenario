import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen, fireEvent } from "@testing-library/react";
import { FichaMateriaTabs } from "./ficha-materia-tabs";
import type { FichaMateriaOut } from "@/lib/contrato-legislativo.gen";

const ficha: FichaMateriaOut = {
  proposicao: {
    id: "1",
    tipo: "projeto_lei",
    ano: 2026,
    sequencial: 42,
    urnLex: "urn:x",
    ementa: "Cria o Programa Municipal de Hortas Comunitárias",
    estado: "em_comissoes",
    lockVersion: 3,
    atualizadoEm: "2026-05-12T10:00:00Z",
    texto: "Art. 1º Fica instituído o Programa.",
  },
  tramitacao: [
    { deEstado: "protocolada", paraEstado: "em_comissoes", gatilho: "distribuir", ocorridoEm: "2026-04-08T09:00:00Z" },
  ],
  apensadas: [],
  emendas: [
    { id: "e1", numeroLocal: 1, tipoEmenda: "modificativa", momentoApresentacao: "no_prazo", autorTexto: "Ver.ª Carla Souza", estado: "aprovada" },
  ],
  pareceres: [
    { id: "p1", comissaoId: "CCJ", relatorId: "r1", votoRelator: "favorável", estado: "aprovado" },
  ],
};

describe("FichaMateriaTabs", () => {
  afterEach(() => cleanup());

  it("abre com a aba 'Texto vigente' selecionada; demais painéis escondidos", () => {
    render(<FichaMateriaTabs ficha={ficha} />);
    const abaTexto = screen.getByRole("tab", { name: /texto vigente/i });
    expect(abaTexto.getAttribute("aria-selected")).toBe("true");
    expect(abaTexto.getAttribute("tabindex")).toBe("0");
    expect(screen.getByRole("tab", { name: /tramitação/i }).getAttribute("tabindex")).toBe("-1");
    expect(screen.getByText(/Art\. 1º/)).toBeTruthy();
  });

  it("clique numa aba troca a seleção e o painel visível", () => {
    render(<FichaMateriaTabs ficha={ficha} />);
    fireEvent.click(screen.getByRole("tab", { name: /pareceres/i }));
    expect(screen.getByRole("tab", { name: /pareceres/i }).getAttribute("aria-selected")).toBe("true");
    expect(screen.getByRole("tab", { name: /texto vigente/i }).getAttribute("aria-selected")).toBe("false");
    expect(screen.getByText("CCJ")).toBeTruthy();
  });

  it("clique numa aba move o foco do DOM pra ela (roving tabindex não pode desincronizar do foco real)", () => {
    render(<FichaMateriaTabs ficha={ficha} />);
    const abaPareceres = screen.getByRole("tab", { name: /pareceres/i });
    fireEvent.click(abaPareceres);
    expect(document.activeElement).toBe(abaPareceres);
    expect(abaPareceres.getAttribute("tabindex")).toBe("0");
  });

  it("ArrowRight/ArrowLeft navegam com roving tabindex (com wraparound)", () => {
    render(<FichaMateriaTabs ficha={ficha} />);
    const abaTexto = screen.getByRole("tab", { name: /texto vigente/i });
    fireEvent.keyDown(abaTexto, { key: "ArrowRight" });
    expect(screen.getByRole("tab", { name: /tramitação/i }).getAttribute("aria-selected")).toBe("true");

    fireEvent.keyDown(screen.getByRole("tab", { name: /tramitação/i }), { key: "ArrowLeft" });
    expect(screen.getByRole("tab", { name: /texto vigente/i }).getAttribute("aria-selected")).toBe("true");

    // wraparound pra trás: da primeira aba, ArrowLeft vai pra última (Anexos)
    fireEvent.keyDown(screen.getByRole("tab", { name: /texto vigente/i }), { key: "ArrowLeft" });
    expect(screen.getByRole("tab", { name: /anexos/i }).getAttribute("aria-selected")).toBe("true");
  });

  it("aba Emendas mostra TODOS os estados (chip por linha), não só os ativos", () => {
    render(<FichaMateriaTabs ficha={ficha} />);
    fireEvent.click(screen.getByRole("tab", { name: /emendas/i }));
    expect(screen.getByText("Modificativa")).toBeTruthy();
    expect(screen.getByText(/Aprovada/)).toBeTruthy();
  });

  it("aba Anexos mostra EmBreve honesto", () => {
    render(<FichaMateriaTabs ficha={ficha} />);
    fireEvent.click(screen.getByRole("tab", { name: /anexos/i }));
    expect(screen.getByRole("status")).toBeTruthy();
  });

  it("tem um h2 rotulando a região de conteúdo (não pula de h1 pra h3)", () => {
    render(<FichaMateriaTabs ficha={ficha} />);
    expect(screen.getByRole("heading", { level: 2, name: /conteúdo da matéria/i })).toBeTruthy();
  });

  it("sem texto vigente registrado -> honesto, sem lançar", () => {
    render(<FichaMateriaTabs ficha={{ ...ficha, proposicao: { ...ficha.proposicao, texto: null } }} />);
    expect(screen.getByText(/nenhum texto vigente/i)).toBeTruthy();
  });
});
