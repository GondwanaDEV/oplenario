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
  aprovada: false,
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

  it("aguardando_pauta (chave real do rito da demo): a faixa não volta ao Protocolo e o chip diz 'Aguardando pauta'", () => {
    render(<FichaCabecalho proposicao={{ ...proposicao, estado: "aguardando_pauta" }} />);
    expect(screen.getByText("Aguardando pauta")).toBeTruthy();
    const faixa = screen.getByRole("img", { name: /Tramitação/ }).getAttribute("aria-label") ?? "";
    expect(faixa).toContain("concluídos Protocolo, Comissões");
    expect(faixa).not.toContain("atual Protocolo");
  });

  it("estado desconhecido da Casa: faixa neutra 'Em tramitação', sem marcar etapa do rito", () => {
    render(<FichaCabecalho proposicao={{ ...proposicao, estado: "xpto_da_casa" }} />);
    const faixa = screen.getByRole("img", { name: /Tramitação/ }).getAttribute("aria-label") ?? "";
    expect(faixa).toContain("atual Em tramitação");
    expect(faixa).not.toContain("Protocolo");
  });

  it("sem autoria informada -> honesto, não lança", () => {
    render(<FichaCabecalho proposicao={{ ...proposicao, autorTexto: null }} />);
    expect(screen.getByText(/não informada/)).toBeTruthy();
  });

  it("fatia 2c: requerimento coletivo mostra os coautores que assinaram", () => {
    render(
      <FichaCabecalho
        proposicao={proposicao}
        coautores={[
          { nome: "Bia Lima", assinadoEm: "2026-09-26T12:00:00Z" },
          { nome: "Caio Reis", assinadoEm: "2026-09-26T12:05:00Z" },
        ]}
      />,
    );
    expect(screen.getByText("Bia Lima, Caio Reis")).toBeTruthy();
    expect(screen.getByText(/Coautores/)).toBeTruthy();
  });
});
