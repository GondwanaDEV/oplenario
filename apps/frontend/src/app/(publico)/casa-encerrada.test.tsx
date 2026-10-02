import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { CasaEncerrada, dataPorExtenso } from "./casa-encerrada";

// ADR-0018 fatia 2 (Eixo 4.4 a + c): a Câmara que não usa mais O Plenário — a data e o caminho para os documentos,
// sóbrio, sem o motivo.

afterEach(cleanup);

describe("CasaEncerrada", () => {
  it("diz desde quando e aponta para onde foi o acervo público", () => {
    render(<CasaEncerrada casa={{ estado: "encerrada", nome: "Câmara Municipal de Baturité",
      encerradaEm: "2027-01-05T15:00:00Z", destinoAcervoUrl: "https://camarabaturite.ce.gov.br/acervo" }} />);
    expect(screen.getByText("Câmara Municipal de Baturité não usa mais O Plenário")).toBeTruthy();
    expect(screen.getByText(/Desde 5 de janeiro de 2027/)).toBeTruthy();
    expect(screen.getByRole("link", { name: "https://camarabaturite.ce.gov.br/acervo" }).getAttribute("href"))
      .toBe("https://camarabaturite.ce.gov.br/acervo");
    expect(screen.queryByText(/inadimpl|contrato/i)).toBeNull();
  });

  it("sem destino informado, manda procurar a própria Câmara", () => {
    render(<CasaEncerrada casa={{ estado: "encerrada", nome: null, encerradaEm: null, destinoAcervoUrl: null }} />);
    expect(screen.getByText("Esta Câmara não usa mais O Plenário")).toBeTruthy();
    expect(screen.getByText(/procure diretamente a Câmara/)).toBeTruthy();
    expect(screen.queryByRole("link")).toBeNull();
  });

  it("a data por extenso, no fuso da Casa", () => {
    expect(dataPorExtenso("2027-01-01T02:00:00Z")).toBe("31 de dezembro de 2026");
    expect(dataPorExtenso(null)).toBeNull();
    expect(dataPorExtenso("nao-e-data")).toBeNull();
  });
});
