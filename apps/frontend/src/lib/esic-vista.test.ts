import { describe, expect, it } from "vitest";
import { derivarStatusEsic, DIAS_TOTAL_LAI } from "./esic-vista";

// Task 2.1 (Fatia A2.2, Portal do Cidadão) — view-model do status público do e-SIC. Rito de 3 estágios
// (Protocolado/Em análise/Respondido, portal-cidadao.html:545-563) a partir do enum fechado de
// AcompanhamentoEsicOut.

describe("DIAS_TOTAL_LAI", () => {
  it("é a constante legal da LAI (20 dias)", () => {
    expect(DIAS_TOTAL_LAI).toBe(20);
  });
});

describe("derivarStatusEsic", () => {
  it("protocolado -> 1º estágio ativo, resto pendente", () => {
    const r = derivarStatusEsic({ estado: "protocolado", diasRestantes: 20 });
    expect(r.estagios).toEqual([
      { rotulo: "Protocolado", situacao: "ativo" },
      { rotulo: "Em análise", situacao: "pendente" },
      { rotulo: "Respondido", situacao: "pendente" },
    ]);
    expect(r.rotuloSituacao).toBe("Protocolado");
    expect(r.diasRestantes).toBe(20);
  });

  it("em_analise -> Protocolado concluído, Em análise ativo, Respondido pendente", () => {
    const r = derivarStatusEsic({ estado: "em_analise", diasRestantes: 9 });
    expect(r.estagios).toEqual([
      { rotulo: "Protocolado", situacao: "concluido" },
      { rotulo: "Em análise", situacao: "ativo" },
      { rotulo: "Respondido", situacao: "pendente" },
    ]);
    expect(r.rotuloSituacao).toBe("Em análise");
    expect(r.diasRestantes).toBe(9);
  });

  it("respondido -> todos os estágios concluídos", () => {
    const r = derivarStatusEsic({ estado: "respondido", diasRestantes: null });
    expect(r.estagios).toEqual([
      { rotulo: "Protocolado", situacao: "concluido" },
      { rotulo: "Em análise", situacao: "concluido" },
      { rotulo: "Respondido", situacao: "concluido" },
    ]);
    expect(r.rotuloSituacao).toBe("Respondido");
    expect(r.diasRestantes).toBeNull();
  });

  it("indeferido -> faixa completa (percorreu o ciclo), rótulo honesto sobre o desfecho negativo", () => {
    const r = derivarStatusEsic({ estado: "indeferido", diasRestantes: null });
    expect(r.estagios.every((e) => e.situacao === "concluido")).toBe(true);
    expect(r.rotuloSituacao).toBe("Indeferido");
  });

  it("fail-closed: estado fora do enum conhecido -> faixa mínima, rótulo = estado cru, nunca lança", () => {
    const r = derivarStatusEsic({ estado: "xpto-desconhecido", diasRestantes: 3 });
    expect(r.estagios).toEqual([{ rotulo: "Protocolado", situacao: "ativo" }]);
    expect(r.rotuloSituacao).toBe("xpto-desconhecido");
    expect(r.diasRestantes).toBe(3);
  });

  it("diasRestantes undefined-ish (null) é repassado sem fabricar número", () => {
    const r = derivarStatusEsic({ estado: "protocolado", diasRestantes: null });
    expect(r.diasRestantes).toBeNull();
  });
});
