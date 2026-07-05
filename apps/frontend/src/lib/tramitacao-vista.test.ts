import { describe, expect, it } from "vitest";
import { derivarTramitacao, descreverFaixa } from "./tramitacao-vista";

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

// Task de review A2.1 (item 3, a11y) — `descreverFaixa` monta o rótulo ARIA completo da AzulejoFaixa.
// `role="img"` esconde os <text> por-estágio do SVG dos leitores de tela; sem isto, AT perde a
// progressão concluído/atual/pendente que usuários videntes veem no grafismo. Helper puro, testado
// isoladamente e reusado pelo caller (DestaqueTramitacao) em vez de string-building inline.
describe("descreverFaixa", () => {
  it("estado intermediário -> lista concluídos, nomeia o atual, lista os pendentes", () => {
    const estagios = derivarTramitacao("segundo_turno").estagios;
    expect(descreverFaixa("PL 042/2026", estagios)).toBe(
      "Tramitação de PL 042/2026: concluídos Protocolo, Comissões, 1º turno; atual 2º turno; pendente Sanção.",
    );
  });

  it("protocolada (início) -> sem concluídos, só atual + pendentes", () => {
    const estagios = derivarTramitacao("protocolada").estagios;
    expect(descreverFaixa("PL 001/2026", estagios)).toBe(
      "Tramitação de PL 001/2026: atual Protocolo; pendente Comissões, 1º turno, 2º turno, Sanção.",
    );
  });

  it("aprovada -> tudo concluído, sem cláusula de atual nem de pendente", () => {
    const estagios = derivarTramitacao("aprovada").estagios;
    expect(descreverFaixa("PL 007/2026", estagios)).toBe(
      "Tramitação de PL 007/2026: concluídos Protocolo, Comissões, 1º turno, 2º turno, Sanção.",
    );
  });

  it("fail-closed (faixa mínima, estado desconhecido) -> só o estágio único + sua situação, sem throw", () => {
    const estagios = derivarTramitacao("xpto-desconhecido").estagios;
    expect(() => descreverFaixa("PL 099/2026", estagios)).not.toThrow();
    expect(descreverFaixa("PL 099/2026", estagios)).toBe("Tramitação de PL 099/2026: atual Protocolo.");
  });
});
