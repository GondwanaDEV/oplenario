import { describe, expect, it } from "vitest";
import {
  deslocarMes,
  dinheiro,
  linhaDeRevisao,
  nomeDaOperacao,
  percentual,
  porCapacidade,
  rotuloDoMes,
  situacaoDaCota,
} from "./ia-casa-vista";
import type { PainelIAOut } from "./contrato-paineis.gen";

const base: PainelIAOut = {
  mes: "2026-09", consumoDisponivel: true, estado: "normal", gasto: "40", moeda: "USD", parcial: false, execucoes: 3,
  orcamento: { mensal: "100.0000", tetoDuro: "120.0000", moeda: "USD", definidoEm: "2026-09-01T00:00:00Z" },
  porCapacidade: [], notasTecnicas: { pendentes: 0, aproveitadas: 0, descartadas: 0 },
  propostas: { aguardando: 0, confirmadas: 0, recusadas: 0, expiradas: 0 },
};

const op = (operacao: string, custo: string, extra: Partial<PainelIAOut["porCapacidade"][number]> = {}) => ({
  operacao, execucoes: 1, indisponiveis: 0, custo, aprovados: 0, editados: 0, descartados: 0, errosReportados: 0,
  ...extra,
});

describe("ia-casa-vista", () => {
  it("agrupa pelo nome que a Casa conhece, mais caro primeiro", () => {
    const l = porCapacidade([op("agente.planejar", "1"), op("agente.responder", "2"), op("ata.redigir", "10"),
      op("conferencia.redigir", "0.5", { indisponiveis: 1 })]);
    expect(l.map((x) => x.nome)).toEqual(["Rascunho da ata", "Assistente da Casa", "Conferência das proposições"]);
    expect(l[1]).toMatchObject({ execucoes: 2, custo: 3 });
    expect(l[2]).toMatchObject({ segundoPlano: true, naoRodaram: 1 });
  });

  it("percentual do orçamento", () => {
    expect(percentual(base)).toBe(40);
    expect(percentual({ ...base, orcamento: null })).toBeNull();
    expect(percentual({ ...base, gasto: null })).toBeNull();
    expect(percentual({ ...base, orcamento: { ...base.orcamento!, mensal: "0" }, gasto: "0" })).toBe(0);
  });

  it("a frase de cada estado", () => {
    expect(situacaoDaCota(base).tom).toBe("ok");
    expect(situacaoDaCota({ ...base, estado: "aviso" }).detalhe).toMatch(/teto de US\$\s?120,00/);
    expect(situacaoDaCota({ ...base, estado: "segundo_plano_pausado" }).detalhe).toMatch(/Resumo cidadão|resumo cidadão/);
    expect(situacaoDaCota({ ...base, estado: "esgotada" }).tom).toBe("alerta");
    expect(situacaoDaCota({ ...base, estado: "sem_orcamento" }).titulo).toBe("Sem orçamento definido");
    expect(situacaoDaCota({ ...base, consumoDisponivel: false }).titulo).toBe("Consumo indisponível agora");
  });

  it("meses", () => {
    expect(rotuloDoMes("2026-09")).toBe("setembro de 2026");
    expect(deslocarMes("2026-01", -1)).toBe("2025-12");
    expect(deslocarMes("2026-12", 1)).toBe("2027-01");
  });

  it("dinheiro e revisão", () => {
    expect(dinheiro("85.5", "USD")).toMatch(/US\$\s?85,50/);
    expect(linhaDeRevisao({ nome: "x", segundoPlano: false, execucoes: 4, naoRodaram: 0, custo: 0, revisados: 4,
      aprovados: 1, editados: 2, descartados: 1, errosReportados: 0 })).toBe(
      "75% aproveitado (1 aprovado como veio, 2 editados, 1 descartado)");
    expect(linhaDeRevisao({ nome: "x", segundoPlano: false, execucoes: 1, naoRodaram: 0, custo: 0, revisados: 0,
      aprovados: 0, editados: 0, descartados: 0, errosReportados: 0 })).toBeNull();
  });

  it("o copiloto do relator (ADR-0019 fatia 2) tem nome na Casa e no console do operador", () => {
    expect(nomeDaOperacao("relator.analisar")).toBe("Copiloto do relator");
  });
});
