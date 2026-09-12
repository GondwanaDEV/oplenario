import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { DadosMateriaCard } from "./dados-materia-card";

describe("DadosMateriaCard", () => {
  afterEach(() => cleanup());

  it("renderiza situação, apensados e datas formatadas (pt-BR)", () => {
    render(
      <DadosMateriaCard
        dados={{
          situacao: "Em comissões",
          apensadosTotal: 2,
          apensadasTruncado: false,
          apresentadaEm: "2026-04-08T09:00:00Z",
          ultimaAcaoEm: "2026-05-12T10:00:00Z",
        }}
      />,
    );
    expect(screen.getByText("Dados da matéria")).toBeTruthy();
    expect(screen.getByText("Em comissões")).toBeTruthy();
    expect(screen.getByText("2")).toBeTruthy();
    expect(screen.getByText("08/04/2026")).toBeTruthy();
    expect(screen.getByText("12/05/2026")).toBeTruthy();
  });

  it("sem apensados -> mostra 'nenhum' honesto (não '0' cru)", () => {
    render(
      <DadosMateriaCard
        dados={{
          situacao: "Protocolado",
          apensadosTotal: 0,
          apensadasTruncado: false,
          apresentadaEm: "2026-01-01T00:00:00Z",
          ultimaAcaoEm: "2026-01-01T00:00:00Z",
        }}
      />,
    );
    expect(screen.getByText("nenhum")).toBeTruthy();
  });

  // ---------- fatia "truncamento-familia": apensadosTotal pode não ser o total de verdade ----------

  it("apensadasTruncado=true -> '+' honesto ao lado da contagem (há mais que o exibido)", () => {
    render(
      <DadosMateriaCard
        dados={{
          situacao: "Em comissões",
          apensadosTotal: 50,
          apensadasTruncado: true,
          apresentadaEm: "2026-01-01T00:00:00Z",
          ultimaAcaoEm: "2026-01-01T00:00:00Z",
        }}
      />,
    );
    expect(screen.getByText("50")).toBeTruthy();
    expect(screen.getByText("+")).toBeTruthy();
  });

  it("apensadasTruncado=true mas zero apensados -> continua 'nenhum', sem '+' (rule 4: nunca inventa corte de uma lista vazia)", () => {
    // Cenário defensivo: o servidor nunca deveria mandar truncado=true com lista vazia, mas se mandasse,
    // a UI não pode escrever "nenhum+" — não faz sentido dizer "há mais" de uma lista que mostra zero.
    render(
      <DadosMateriaCard
        dados={{
          situacao: "Protocolado",
          apensadosTotal: 0,
          apensadasTruncado: true,
          apresentadaEm: "2026-01-01T00:00:00Z",
          ultimaAcaoEm: "2026-01-01T00:00:00Z",
        }}
      />,
    );
    expect(screen.getByText("nenhum")).toBeTruthy();
    expect(screen.queryByText("+")).toBeNull();
  });
});
