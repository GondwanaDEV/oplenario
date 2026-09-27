import { describe, it, expect } from "vitest";
import { derivarAcoesTramitacao, humanizarGatilho } from "./tramitacao-acoes-vista";
import type { TramitacaoOut } from "./use-tramitacao";

function base(over: Partial<TramitacaoOut> = {}): TramitacaoOut {
  return {
    proposicaoId: "p1",
    estadoAtual: "protocolada",
    templateId: "t1",
    estadoTerminal: false,
    historico: [],
    historicoTruncado: false,
    gatilhosPossiveis: [],
    nota: null,
    recebimentoPendente: null,
    ...over,
  };
}

describe("humanizarGatilho", () => {
  it("troca separadores por espaço e capitaliza, sem alterar o gatilho-verbo", () => {
    expect(humanizarGatilho("distribuir_comissao")).toBe("Distribuir comissao");
    expect(humanizarGatilho("enviar-ao-plenario")).toBe("Enviar ao plenario");
  });
});

describe("derivarAcoesTramitacao", () => {
  it("com gatilhos → tipo com-atos, preservando o gatilho-verbo e as dicas", () => {
    const v = derivarAcoesTramitacao(
      base({
        estadoAtual: "em_comissoes",
        gatilhosPossiveis: [
          { gatilho: "aprovar_parecer", destinosPossiveis: ["aprovada"], podeSerRecusado: true, exigeAutorizacao: false },
          { gatilho: "arquivar", destinosPossiveis: ["arquivada"], podeSerRecusado: false, exigeAutorizacao: true },
        ],
      }),
    );
    expect(v.tipo).toBe("com-atos");
    if (v.tipo !== "com-atos") return;
    expect(v.estadoAtual).toBe("em_comissoes");
    expect(v.atos).toHaveLength(2);
    expect(v.atos[0]).toMatchObject({ gatilho: "aprovar_parecer", rotulo: "Aprovar parecer", condicional: true, exigeAutorizacao: false });
    expect(v.atos[1]).toMatchObject({ gatilho: "arquivar", condicional: false, exigeAutorizacao: true, destinos: ["arquivada"] });
  });

  it("sem gatilhos → tipo sem-atos com a nota do servidor", () => {
    const v = derivarAcoesTramitacao(base({ estadoAtual: "arquivada", nota: "Estado terminal: fim de processo." }));
    expect(v.tipo).toBe("sem-atos");
    if (v.tipo !== "sem-atos") return;
    expect(v.nota).toBe("Estado terminal: fim de processo.");
  });

  it("sem gatilhos e sem nota do servidor → nota padrão", () => {
    const v = derivarAcoesTramitacao(base({ nota: null }));
    if (v.tipo !== "sem-atos") throw new Error("esperava sem-atos");
    expect(v.nota).toMatch(/nenhum ato dispon/i);
  });

  it("carga pendente → tipo carga, sem atos (o backend recusaria todos até alguém receber)", () => {
    const v = derivarAcoesTramitacao(
      base({
        estadoAtual: "em_comissoes",
        gatilhosPossiveis: [
          { gatilho: "concluir", destinosPossiveis: ["em_pauta"], podeSerRecusado: false, exigeAutorizacao: false },
        ],
        recebimentoPendente: {
          movimentacaoId: "m1",
          deEstado: "protocolada",
          estado: "em_comissoes",
          estadoNome: "Em Comissões",
          desde: "2026-09-26T10:00:00",
          restrito: false,
        },
      }),
      new Date("2026-09-26T11:00:00"),
    );
    expect(v.tipo).toBe("carga");
    if (v.tipo !== "carga") return;
    expect(v.carga).toMatchObject({ movimentacaoId: "m1", estadoNome: "Em Comissões", restrito: false });
  });
});
