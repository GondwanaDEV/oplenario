import { describe, expect, it } from "vitest";
import { derivarProposicoesVista } from "./proposicoes-vista";
import type { ProposicaoResumoOut } from "./contrato-legislativo.gen";

const base: ProposicaoResumoOut = {
  id: "11111111-1111-1111-1111-111111111111",
  tipo: "projeto_lei",
  ano: 2026,
  sequencial: 42,
  urnLex: "urn:lex:br:camara.municipal.fortaleza:projeto.lei:2026;42",
  ementa: "Cria o Programa Municipal de Hortas Comunitárias",
  autorTipo: "vereador",
  autorTexto: "Helena Matos",
  estado: "em_comissoes",
  atualizadoEm: "2026-05-21T10:00:00Z",
};

describe("derivarProposicoesVista", () => {
  it("monta o número no formato SIGLA sequencial/ano", () => {
    const [linha] = derivarProposicoesVista([base]);
    expect(linha.numero).toBe("PL 42/2026");
  });

  it("mapeia a espécie para um rótulo legível", () => {
    const [linha] = derivarProposicoesVista([{ ...base, tipo: "requerimento" }]);
    expect(linha.especie).toBe("Requerimento");
  });

  it("autor ausente vira travessão, não string vazia/undefined", () => {
    const [linha] = derivarProposicoesVista([{ ...base, autorTexto: undefined, autorTipo: undefined }]);
    expect(linha.autor).toBe("—");
  });

  it("reaproveita derivarTramitacao para a situação (estado conhecido)", () => {
    const [linha] = derivarProposicoesVista([base]);
    expect(linha.situacao.rotulo).toBe("Em comissões");
    expect(linha.situacao.estagios.length).toBeGreaterThan(0);
  });

  it("estado desconhecido degrada honesto (fail-closed), nunca lança", () => {
    const [linha] = derivarProposicoesVista([{ ...base, estado: "estado_customizado_do_tenant" }]);
    expect(linha.situacao.rotulo).toBe("estado_customizado_do_tenant");
  });

  it("lista vazia vira lista vazia", () => {
    expect(derivarProposicoesVista([])).toEqual([]);
  });
});
