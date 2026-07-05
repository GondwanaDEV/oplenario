import { describe, expect, it } from "vitest";
import { categorizarSituacao, derivarProposicoesVista } from "./proposicoes-vista";
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

  it("expõe a categoria do chip de status junto de rótulo/estágios", () => {
    const [linha] = derivarProposicoesVista([base]);
    expect(linha.situacao.categoria).toBe("tram");
  });
});

describe("categorizarSituacao", () => {
  it("estado em andamento (nenhum terminal, nenhuma espera de pauta) categoriza como 'tram'", () => {
    expect(categorizarSituacao("protocolada")).toBe("tram");
    expect(categorizarSituacao("em_comissoes")).toBe("tram");
    expect(categorizarSituacao("primeiro_turno")).toBe("tram");
  });

  it("estado de espera de pauta categoriza como 'aguarda'", () => {
    expect(categorizarSituacao("em_pauta")).toBe("aguarda");
    expect(categorizarSituacao("aguardando_pauta")).toBe("aguarda");
  });

  it("estado terminal de sucesso categoriza como 'aprovada'", () => {
    expect(categorizarSituacao("aprovada")).toBe("aprovada");
  });

  it("estados terminais do ciclo do Executivo (legislativo/logic.clj) categorizam como 'aprovada'", () => {
    expect(categorizarSituacao("sancionado")).toBe("aprovada");
    expect(categorizarSituacao("sancao_tacita")).toBe("aprovada");
    expect(categorizarSituacao("veto_derrubado")).toBe("aprovada");
  });

  it("estado terminal de arquivamento categoriza como 'arquivada'", () => {
    expect(categorizarSituacao("arquivada")).toBe("arquivada");
  });

  it("estados terminais-negativos (rejeição/votação) categorizam como 'arquivada'", () => {
    expect(categorizarSituacao("rejeitada")).toBe("arquivada");
    expect(categorizarSituacao("prejudicada")).toBe("arquivada");
    expect(categorizarSituacao("retirada")).toBe("arquivada");
  });

  it("estado desconhecido (vocabulário livre do tenant) degrada fail-closed para 'tram', nunca lança", () => {
    expect(() => categorizarSituacao("estado_customizado_do_tenant")).not.toThrow();
    expect(categorizarSituacao("estado_customizado_do_tenant")).toBe("tram");
  });
});
