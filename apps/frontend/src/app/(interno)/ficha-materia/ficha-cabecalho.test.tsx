import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { FichaCabecalho } from "./ficha-cabecalho";
import type { ProposicaoDetalheOut } from "@/lib/contrato-legislativo.gen";

const proposicao: ProposicaoDetalheOut = {
  id: "1",
  tipo: "projeto_lei",
  ano: 2026,
  sequencial: 42,
  urnLex: "urn:x",
  ementa: "Cria o Programa Municipal de Hortas Comunitárias",
  autorTexto: "Ver.ª Helena Matos",
  estado: "em_comissoes",
  lockVersion: 3,
  atualizadoEm: "2026-05-12T10:00:00Z",
};

describe("FichaCabecalho", () => {
  afterEach(() => cleanup());

  it("renderiza número, espécie, chip de situação, título e a faixa de azulejo", () => {
    render(<FichaCabecalho proposicao={proposicao} />);
    expect(screen.getByText("PL 42/2026")).toBeTruthy();
    expect(screen.getByText("Projeto de Lei")).toBeTruthy();
    expect(screen.getByText("Em comissões")).toBeTruthy();
    expect(screen.getByText("Cria o Programa Municipal de Hortas Comunitárias")).toBeTruthy();
    expect(screen.getByText(/Ver\.ª Helena Matos/)).toBeTruthy();
    expect(screen.getByRole("img", { name: /Tramitação/ })).toBeTruthy();
  });

  it("sem autoria informada -> honesto, não lança", () => {
    render(<FichaCabecalho proposicao={{ ...proposicao, autorTexto: null }} />);
    expect(screen.getByText(/não informada/)).toBeTruthy();
  });
});
