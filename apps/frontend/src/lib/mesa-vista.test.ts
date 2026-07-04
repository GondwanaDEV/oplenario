import { describe, expect, it } from "vitest";
import { derivarMesaVista } from "./mesa-vista";

const mesaBase = {
  complianceTce: { resumo: { pendente: 2, cumprida: 9, vencida: 0, dispensada: 1, cancelada: 0 }, emAberto: [], remessasRecentes: [] },
  tramitacao: { total: 47, porEstado: [{ estado: "protocolada", n: 12 }] },
  pendencias: { abertas: 8, vencidas: 1, pendentes: 7 },
  sessoes: { emCurso: 0, naoRealizadas: 1, porSituacao: [] },
  presencaResumo: { mediaPercentual: 78, sessoesConsideradas: 10, membrosDaCasa: 43 },
  esicCumprimento: { totalEncerrados: 49, cumpridosNoPrazo: 47, percentual: 96 },
  relatoresPendentes: { itens: [] },
  lacunas: ["ciencia_convocacao", "assinatura_autografo", "incidente_grant_lgpd"],
};

describe("derivarMesaVista", () => {
  it("compliance indisponivel -> saude.estado = indisponivel", () => {
    const v = derivarMesaVista({
      mesa: { ...mesaBase, complianceTce: { indisponivel: true } },
      tramitacaoItens: [], pendenciasItens: [], sliSessoes: [], relatoresPendentes: [],
    });
    expect(v.saude.estado).toBe("indisponivel");
  });

  it("compliance ok -> saude.estado = disponivel com o resumo", () => {
    const v = derivarMesaVista({ mesa: mesaBase, tramitacaoItens: [], pendenciasItens: [], sliSessoes: [], relatoresPendentes: [] });
    expect(v.saude.estado).toBe("disponivel");
    expect(v.saude.resumo?.cumprida).toBe(9);
  });

  it("tramitacaoItens null (chamada de detalhe falhou) -> pipeline degrada pra só-contagem", () => {
    const v = derivarMesaVista({ mesa: mesaBase, tramitacaoItens: null, pendenciasItens: [], sliSessoes: [], relatoresPendentes: [] });
    expect(v.pipeline.estado).toBe("disponivel");
    expect(v.pipeline.comItens).toBe(false);
    expect(v.pipeline.porEstado).toEqual(mesaBase.tramitacao.porEstado);
  });

  it("tramitacaoItens presente -> pipeline com itens reais", () => {
    const itens = [{ proposicaoId: "1", tipo: "pl", ano: 2026, sequencial: 1, urnLex: "u", ementa: "e", estado: "protocolada", transicionouEm: "2026-07-01T00:00:00Z" }];
    const v = derivarMesaVista({ mesa: mesaBase, tramitacaoItens: itens, pendenciasItens: [], sliSessoes: [], relatoresPendentes: [] });
    expect(v.pipeline.comItens).toBe(true);
    expect(v.pipeline.itens).toHaveLength(1);
  });

  it("as 3 secoes caras ficam em-breve por construção (nunca dependem de dado)", () => {
    const v = derivarMesaVista({ mesa: mesaBase, tramitacaoItens: [], pendenciasItens: [], sliSessoes: [], relatoresPendentes: [] });
    expect(v.proximaSessaoCiencia.estado).toBe("em-breve");
    expect(v.despachos.autografo.estado).toBe("em-breve");
    expect(v.lenteJuridico.estado).toBe("em-breve");
  });

  it("orgulho combina presenca+esic+total-tramitacao; transmissao ao vivo em-breve", () => {
    const v = derivarMesaVista({ mesa: mesaBase, tramitacaoItens: [], pendenciasItens: [], sliSessoes: [], relatoresPendentes: [] });
    expect(v.orgulho.presencaMedia).toBe(78);
    expect(v.orgulho.esicPercentual).toBe(96);
    expect(v.orgulho.totalTramitacao).toBe(47);
    expect(v.orgulho.transmissaoAoVivo.estado).toBe("em-breve");
  });

  it("despachos.relator com itens reais quando relatoresPendentes vem preenchido", () => {
    const v = derivarMesaVista({
      mesa: mesaBase, tramitacaoItens: [], pendenciasItens: [], sliSessoes: [],
      relatoresPendentes: [{ id: "1", proposicaoId: "p1", tipo: "pl", ano: 2026, sequencial: 51, urnLex: "u", ementa: "Arborização", criadoEm: "2026-07-01T00:00:00Z" }],
    });
    expect(v.despachos.relator.estado).toBe("disponivel");
    expect(v.despachos.relator.itens).toHaveLength(1);
  });

  it("oQueVence une compliance.emAberto + pendenciasItens e ordena por venceEm crescente", () => {
    const mesaComEmAberto = {
      ...mesaBase,
      complianceTce: {
        ...mesaBase.complianceTce,
        emAberto: [{ id: "ob-1", templateChave: "remessa-mensal-pessoal", venceEm: "2026-08-01" }],
      },
    };
    const pendenciasItens = [
      { objetoTipo: "pedido_esic", objetoId: "p1", protocolo: "ESIC-1", venceEm: "2026-07-10", estado: "pendente" },
    ];
    const v = derivarMesaVista({
      mesa: mesaComEmAberto, tramitacaoItens: [], pendenciasItens, sliSessoes: [], relatoresPendentes: [],
    });
    expect(v.oQueVence.estado).toBe("disponivel");
    expect(v.oQueVence.itens).toHaveLength(2);
    // pendencia (10/07) vence antes da obrigação de compliance (01/08) -> vem primeiro
    expect(v.oQueVence.itens[0]).toMatchObject({ origem: "pendencia", venceEm: "2026-07-10" });
    expect(v.oQueVence.itens[1]).toMatchObject({ origem: "compliance", venceEm: "2026-08-01" });
  });
});
