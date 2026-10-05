import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { CardAutografo } from "./card-autografo";
import type { AutografoOut } from "@/lib/contrato-legislativo.gen";

const autografo: AutografoOut = {
  id: "a1",
  proposicaoId: "p1",
  numero: 22,
  ano: 2026,
  destinatarioTexto: "Prefeitura Municipal",
  enviadoEm: "2026-06-18T09:00:00-03:00",
  prazoRespostaEm: "2026-07-03T09:00:00-03:00",
};

describe("CardAutografo", () => {
  afterEach(() => cleanup());

  it("renderiza número, data de envio, destinatário e prazo reais", () => {
    render(<CardAutografo autografo={autografo} />);
    expect(screen.getByText("Autógrafo")).toBeTruthy();
    expect(screen.getByText("022/2026")).toBeTruthy();
    expect(screen.getByText("18/06/2026")).toBeTruthy();
    expect(screen.getByText("Prefeitura Municipal")).toBeTruthy();
    expect(screen.getByText("03/07/2026")).toBeTruthy();
    expect(screen.queryByText(/GAP/)).toBeNull();
  });

  it("prazoRespostaEm ausente -> 'não informado' + nota GAP honesta", () => {
    render(<CardAutografo autografo={{ ...autografo, prazoRespostaEm: null }} />);
    expect(screen.getByText("não informado")).toBeTruthy();
    expect(screen.getByText("GAP")).toBeTruthy();
  });

  it("aguardando resposta -> diz até quando o Executivo pode sancionar ou vetar", () => {
    render(<CardAutografo autografo={autografo} aguardandoResposta />);
    expect(screen.getByText("O Executivo tem até 03/07/2026 para sancionar ou vetar.")).toBeTruthy();
  });

  it("o dia do prazo é o da Casa mesmo quando o servidor devolve em UTC", () => {
    // 20/10 23h59 em Fortaleza chega como 21/10 02h59 em UTC
    render(<CardAutografo autografo={{ ...autografo, prazoRespostaEm: "2026-10-21T02:59:59Z" }} aguardandoResposta />);
    expect(screen.getByText("20/10/2026")).toBeTruthy();
    expect(screen.getByText("O Executivo tem até 20/10/2026 para sancionar ou vetar.")).toBeTruthy();
  });

  it("já respondido -> mostra o prazo na lista, mas não diz que o Executivo ainda tem prazo", () => {
    render(<CardAutografo autografo={autografo} aguardandoResposta={false} />);
    expect(screen.getByText("03/07/2026")).toBeTruthy();
    expect(screen.queryByText(/para sancionar ou vetar/)).toBeNull();
  });

  it("não inventa um link de PDF (sem contraparte real no modelo)", () => {
    render(<CardAutografo autografo={autografo} />);
    expect(screen.queryByRole("link")).toBeNull();
  });
});
