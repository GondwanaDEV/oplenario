import { describe, expect, it } from "vitest";
import { derivarMeuVoto } from "./meu-voto-vista";
import { estadoInicial, type EstadoPlenario } from "./plenario-reducer";
import type { SessaoOut } from "./contrato";

const sessao = (over: Partial<SessaoOut> = {}): SessaoOut => ({
  id: "s1",
  "sessao-legislativa-id": "sl1",
  "tipo-sessao": "ordinaria",
  "numero-sequencial": 14,
  estado: "aberta",
  modalidade: "presencial",
  delibera: true,
  "transmite-publica": true,
  "gera-ata-regimental": true,
  "permite-voto-secreto": false,
  "permite-modalidade-remota": false,
  "agendada-para": "2026-05-21T22:00:00Z",
  "aberta-em": null,
  "encerrada-em": null,
  "motivo-nao-realizada": null,
  ...over,
});

const BASE: EstadoPlenario = estadoInicial(sessao());

describe("derivarMeuVoto", () => {
  it("sem votação corrente → sem-votacao", () => {
    const v = derivarMeuVoto(BASE, "v1");
    expect(v.ciclo).toBe("sem-votacao");
  });

  it("votação nominal aberta, presente, sem voto ainda → pode-votar", () => {
    const estado: EstadoPlenario = {
      ...BASE,
      presentes: ["v1"],
      placar: {
        votacaoId: "vot1", modalidade: "nominal", objetoTipo: "proposicao", encerrada: false,
        votosNominais: {}, votosSecretos: 0, resultado: null, totais: null, baseMembros: null,
      },
    };
    expect(derivarMeuVoto(estado, "v1").ciclo).toBe("pode-votar");
  });

  it("votação nominal aberta, mas AUSENTE → sem-presenca (nunca oferece o botão)", () => {
    const estado: EstadoPlenario = {
      ...BASE,
      presentes: [],
      placar: {
        votacaoId: "vot1", modalidade: "nominal", objetoTipo: "proposicao", encerrada: false,
        votosNominais: {}, votosSecretos: 0, resultado: null, totais: null, baseMembros: null,
      },
    };
    expect(derivarMeuVoto(estado, "v1").ciclo).toBe("sem-presenca");
  });

  it("já votou (o placar já ecoou o próprio voto) → ja-votou, com o voto certo", () => {
    const estado: EstadoPlenario = {
      ...BASE,
      presentes: ["v1"],
      placar: {
        votacaoId: "vot1", modalidade: "nominal", objetoTipo: "proposicao", encerrada: false,
        votosNominais: { v1: "sim" }, votosSecretos: 0, resultado: null, totais: null, baseMembros: null,
      },
    };
    const v = derivarMeuVoto(estado, "v1");
    expect(v.ciclo).toBe("ja-votou");
    expect(v.meuVoto).toBe("sim");
  });

  it("modalidade secreta (ou não provada) → nunca oferece o botão", () => {
    const estado: EstadoPlenario = {
      ...BASE,
      placar: {
        votacaoId: "vot1", modalidade: "secreta", objetoTipo: "proposicao", encerrada: false,
        votosNominais: {}, votosSecretos: 3, resultado: null, totais: null, baseMembros: null,
      },
    };
    expect(derivarMeuVoto(estado, "v1").ciclo).toBe("secreta");
  });

  it("secreta E encerrada → 'encerrada' vence (a checagem de estado roda ANTES da de modalidade)", () => {
    // review LOW (revisao final de branch): esta combinação nunca tinha teste dedicado — confirma que a
    // ordem de precedência (encerrada primeiro) não se inverte silenciosamente no futuro.
    const estado: EstadoPlenario = {
      ...BASE,
      placar: {
        votacaoId: "vot1", modalidade: "secreta", objetoTipo: "proposicao", encerrada: true,
        votosNominais: {}, votosSecretos: 3, resultado: "aprovada",
        totais: { sim: 6, nao: 3, abstencao: 1 }, baseMembros: 11,
      },
    };
    expect(derivarMeuVoto(estado, "v1").ciclo).toBe("encerrada");
  });

  it("votação encerrada → encerrada (mesmo se antes desse voto)", () => {
    const estado: EstadoPlenario = {
      ...BASE,
      placar: {
        votacaoId: "vot1", modalidade: "nominal", objetoTipo: "proposicao", encerrada: true,
        votosNominais: { v1: "sim" }, votosSecretos: 0, resultado: "aprovada",
        totais: { sim: 6, nao: 3, abstencao: 1 }, baseMembros: 11,
      },
    };
    expect(derivarMeuVoto(estado, "v1").ciclo).toBe("encerrada");
  });

  it("meuVereadorId null → nunca 'ja-votou' mesmo com votosNominais preenchido, presente sempre false", () => {
    const estado: EstadoPlenario = {
      ...BASE,
      presentes: ["v1"],
      placar: {
        votacaoId: "vot1", modalidade: "nominal", objetoTipo: "proposicao", encerrada: false,
        votosNominais: { v1: "sim" }, votosSecretos: 0, resultado: null, totais: null, baseMembros: null,
      },
    };
    const v = derivarMeuVoto(estado, null);
    expect(v.presente).toBe(false);
    expect(v.ciclo).not.toBe("ja-votou");
  });
});
