import { describe, expect, it } from "vitest";
import { esperaDesde, textoRecebimento, vistaCarga } from "./recebimento-vista";

describe("textoRecebimento", () => {
  it("com nome: quem recebeu, quando, e que foi assinada", () => {
    const t = textoRecebimento({
      recebidoPorNome: "Marina Alencar Freire",
      recebidoEm: "2026-09-26T14:30:00",
      assinaturaAlgoritmo: "STUB-ICP-v0",
    });
    expect(t).toBe("Recebida por Marina Alencar Freire em 26/09/2026, 14:30 · assinada");
  });

  it("sem nome resolvido: diz que foi recebida, sem inventar nome", () => {
    const t = textoRecebimento({ recebidoPorNome: null, recebidoEm: "2026-09-26T14:30:00", assinaturaAlgoritmo: "x" });
    expect(t).toBe("Recebida em 26/09/2026, 14:30 · assinada");
  });

  it("movimentação sem recibo → null", () => {
    expect(textoRecebimento(null)).toBeNull();
    expect(textoRecebimento(undefined)).toBeNull();
  });
});

describe("esperaDesde", () => {
  const agora = new Date("2026-09-26T12:00:00Z");
  it("minutos, horas e dias", () => {
    expect(esperaDesde("2026-09-26T11:30:00Z", agora)).toBe("agora há pouco");
    expect(esperaDesde("2026-09-26T09:00:00Z", agora)).toBe("há 3 h");
    expect(esperaDesde("2026-09-25T11:00:00Z", agora)).toBe("há 1 dia");
    expect(esperaDesde("2026-09-20T12:00:00Z", agora)).toBe("há 6 dias");
  });
  it("data inválida não inventa espera", () => {
    expect(esperaDesde("ontem", agora)).toBe("");
  });
});

describe("vistaCarga", () => {
  it("projeta a carga pendente para a tela", () => {
    const v = vistaCarga(
      {
        movimentacaoId: "m1",
        deEstado: "protocolada",
        estado: "em_comissoes",
        estadoNome: "Em Comissões",
        desde: "2026-09-24T12:00:00",
        restrito: true,
      },
      new Date("2026-09-26T12:00:00"),
    );
    expect(v).toEqual({
      movimentacaoId: "m1",
      estadoNome: "Em Comissões",
      chegouEm: "24/09/2026, 12:00",
      espera: "há 2 dias",
      restrito: true,
    });
  });
});
