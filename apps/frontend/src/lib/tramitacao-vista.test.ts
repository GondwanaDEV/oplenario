import { describe, expect, it } from "vitest";
import { derivarTramitacao } from "./tramitacao-vista";

// Task 0.5 (Fatia A2.0, Portal do Cidadão). Vocabulário real confirmado por grep — ver o mapa documentado
// no topo de tramitacao-vista.ts: `estado` de proposicao é :string LIVRE, template-driven POR CÂMARA
// (F3.3), não um enum fechado em código. Os nomes usados aqui (protocolada/em_comissoes/segundo_turno/
// aprovada) são o rito ILUSTRATIVO que aparece nos fixtures reais de teste do backend
// (tramitacao_db_test.clj, portal_test.clj) + a faixa de 5 estágios do design-system. Fail-closed é o
// contrato para qualquer estado fora deste mapa (tenant real terá vocabulário próprio via template).

describe("derivarTramitacao", () => {
  it("protocolada -> estágio Protocolo ativo, resto pendente", () => {
    const r = derivarTramitacao("protocolada");
    expect(r.estagios).toEqual([
      { rotulo: "Protocolo", situacao: "ativo" },
      { rotulo: "Comissões", situacao: "pendente" },
      { rotulo: "1º turno", situacao: "pendente" },
      { rotulo: "2º turno", situacao: "pendente" },
      { rotulo: "Sanção", situacao: "pendente" },
    ]);
  });

  it("estado intermediário (2º turno) -> Protocolo/Comissões/1º concluídos, 2º ativo, Sanção pendente", () => {
    const r = derivarTramitacao("segundo_turno");
    expect(r.estagios).toEqual([
      { rotulo: "Protocolo", situacao: "concluido" },
      { rotulo: "Comissões", situacao: "concluido" },
      { rotulo: "1º turno", situacao: "concluido" },
      { rotulo: "2º turno", situacao: "ativo" },
      { rotulo: "Sanção", situacao: "pendente" },
    ]);
  });

  it("aprovada -> todos concluídos, rotuloSituacao 'Aprovado'", () => {
    const r = derivarTramitacao("aprovada");
    expect(r.estagios.every((e) => e.situacao === "concluido")).toBe(true);
    expect(r.rotuloSituacao).toBe("Aprovado");
  });

  it("fail-closed: estado desconhecido -> faixa mínima (só Protocolo) + rotuloSituacao = estado cru, sem throw", () => {
    expect(() => derivarTramitacao("xpto-desconhecido")).not.toThrow();
    const r = derivarTramitacao("xpto-desconhecido");
    expect(r.estagios).toEqual([{ rotulo: "Protocolo", situacao: "ativo" }]);
    expect(r.rotuloSituacao).toBe("xpto-desconhecido");
  });
});
