import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { CardPrazoExecutivo } from "./card-prazo-executivo";
import type { AutografoOut } from "@/lib/contrato-legislativo.gen";

const autografo: AutografoOut = {
  id: "a1",
  proposicaoId: "p1",
  numero: 22,
  ano: 2026,
  destinatarioTexto: "Prefeitura Municipal",
  enviadoEm: "2026-06-18T00:00:00Z",
  prazoRespostaEm: "2026-07-03T00:00:00Z",
};

describe("CardPrazoExecutivo", () => {
  afterEach(() => cleanup());

  it("prazo em curso -> mostra o anel com os dias restantes reais + a nota [LOM]", () => {
    render(<CardPrazoExecutivo autografo={autografo} agora={new Date("2026-06-24T00:00:00Z")} />);
    expect(screen.getByText("Prazo do Executivo")).toBeTruthy();
    expect(screen.getByRole("img", { name: /faltam 9 dias/i })).toBeTruthy();
    expect(screen.getByText("LOM")).toBeTruthy();
  });

  it("prazo não informado -> texto honesto, sem anel", () => {
    render(<CardPrazoExecutivo autografo={{ ...autografo, prazoRespostaEm: null }} />);
    expect(screen.getByText(/prazo de resposta não informado/i)).toBeTruthy();
    expect(screen.queryByRole("img")).toBeNull();
  });

  it("prazo vencido -> mensagem de vencido com dias corretos", () => {
    render(<CardPrazoExecutivo autografo={autografo} agora={new Date("2026-07-10T00:00:00Z")} />);
    expect(screen.getByText(/prazo vencido há 7 dia/i)).toBeTruthy();
  });
});
