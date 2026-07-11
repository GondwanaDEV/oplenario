import { describe, expect, it } from "vitest";
import { derivarPipeline, derivarPrazoExecutivo, formatarNumeroAutografo } from "./pos-aprovacao-vista";
import type { AutografoOut, TramitacaoExecutivaOut } from "./contrato-legislativo.gen";

const autografo: AutografoOut = {
  id: "a1",
  proposicaoId: "p1",
  numero: 22,
  ano: 2026,
  destinatarioTexto: "Prefeitura Municipal",
  enviadoEm: "2026-06-18T00:00:00Z",
  prazoRespostaEm: "2026-07-03T00:00:00Z",
};

function tramitacao(parcial: Partial<TramitacaoExecutivaOut>): TramitacaoExecutivaOut {
  return { id: "te1", autografoId: "a1", estado: "aguardando", lockVersion: 0, ...parcial };
}

describe("formatarNumeroAutografo", () => {
  it("numero em 3 dígitos + ano", () => {
    expect(formatarNumeroAutografo(22, 2026)).toBe("022/2026");
    expect(formatarNumeroAutografo(105, 2026)).toBe("105/2026");
  });
});

describe("derivarPipeline", () => {
  it("tramitacaoExecutiva nula -> trata como 'aguardando' (autógrafo feito, executivo atual, resto futuro)", () => {
    const etapas = derivarPipeline(autografo, null);
    expect(etapas).toHaveLength(5);
    expect(etapas[0]).toMatchObject({ rotulo: "Autógrafo", situacao: "feita" });
    expect(etapas[1]).toMatchObject({ rotulo: "No Executivo", situacao: "atual" });
    expect(etapas[2]).toMatchObject({ rotulo: "Sanção ou veto", situacao: "futura", detalhe: "aguarda" });
    expect(etapas[3]).toMatchObject({ rotulo: "Promulgação", situacao: "futura" });
    expect(etapas[4]).toMatchObject({ rotulo: "Publicação", situacao: "futura" });
  });

  it("estado 'aguardando' -> etapa 2 'atual', etapa 3 futura", () => {
    const etapas = derivarPipeline(autografo, tramitacao({ estado: "aguardando" }));
    expect(etapas[1].situacao).toBe("atual");
    expect(etapas[2].situacao).toBe("futura");
  });

  it("estado 'sancionado' -> etapas 1-3 feitas, rótulo do desfecho com data de resposta", () => {
    const etapas = derivarPipeline(
      autografo,
      tramitacao({ estado: "sancionado", respondidoEm: "2026-07-01T00:00:00Z" }),
    );
    expect(etapas[1].situacao).toBe("feita");
    expect(etapas[2]).toMatchObject({ situacao: "feita" });
    expect(etapas[2].detalhe).toContain("Sancionado");
    expect(etapas[2].detalhe).toContain("01/07/2026");
  });

  it("estado 'vetado' -> etapa 3 feita com rótulo 'Vetado'; passos 4/5 seguem futura mesmo assim", () => {
    const etapas = derivarPipeline(autografo, tramitacao({ estado: "vetado" }));
    expect(etapas[2]).toMatchObject({ situacao: "feita", detalhe: expect.stringContaining("Vetado") });
    expect(etapas[3].situacao).toBe("futura");
    expect(etapas[4].situacao).toBe("futura");
  });

  it("estado 'veto_derrubado' -> rótulo específico de apreciação", () => {
    const etapas = derivarPipeline(autografo, tramitacao({ estado: "veto_derrubado" }));
    expect(etapas[2].detalhe).toContain("Veto derrubado");
  });
});

describe("derivarPrazoExecutivo", () => {
  it("prazoRespostaEm ausente -> 'sem-prazo'", () => {
    expect(derivarPrazoExecutivo({ ...autografo, prazoRespostaEm: null })).toEqual({ estado: "sem-prazo" });
  });

  it("dentro do prazo -> 'em-curso' com dias restantes/total calculados de enviadoEm/prazoRespostaEm reais", () => {
    // início 18/06, fim 03/07 (15 dias de janela total), agora 24/06 -> restam 9 dias.
    const agora = new Date("2026-06-24T00:00:00Z");
    const r = derivarPrazoExecutivo(autografo, agora);
    expect(r).toMatchObject({ estado: "em-curso", diasRestantes: 9, diasTotal: 15 });
  });

  it("faltando poucos dias -> categoria 'urgente'", () => {
    const agora = new Date("2026-07-02T00:00:00Z"); // 1 dia restante
    const r = derivarPrazoExecutivo(autografo, agora);
    expect(r).toMatchObject({ estado: "em-curso", categoria: "urgente" });
  });

  it("prazo no passado -> 'vencido' com dias vencidos", () => {
    const agora = new Date("2026-07-10T00:00:00Z"); // 7 dias depois do prazo (03/07)
    const r = derivarPrazoExecutivo(autografo, agora);
    expect(r).toEqual({ estado: "vencido", diasVencidos: 7 });
  });
});
