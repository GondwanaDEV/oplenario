import { describe, expect, it } from "vitest";
import { derivarHome } from "./meu-painel-vista";
import type { MeuPainelOut, ProposicaoResumoMeuPainelOut, ParecerResumoMeuPainelOut } from "./contrato-legislativo.gen";
import type { SessaoOut } from "./contrato";

function proposicao(over: Partial<ProposicaoResumoMeuPainelOut> = {}): ProposicaoResumoMeuPainelOut {
  return {
    id: "p1",
    tipo: "projeto_lei",
    ano: 2026,
    sequencial: 1,
    urnLex: "urn:lex:fixture",
    ementa: "Ementa fixture",
    estado: "protocolada",
    atualizadoEm: "2026-07-01T00:00:00Z",
    ...over,
  };
}

function parecer(over: Partial<ParecerResumoMeuPainelOut> = {}): ParecerResumoMeuPainelOut {
  return {
    id: "pc1",
    objetoTipo: "proposicao",
    objetoId: "p1",
    comissaoId: "com1",
    estado: "aguardando_designacao",
    votoRelator: null,
    criadoEm: "2026-07-01T00:00:00Z",
    ...over,
  };
}

function sessao(over: Partial<SessaoOut> = {}): SessaoOut {
  return {
    id: "s1",
    "sessao-legislativa-id": "sl1",
    "tipo-sessao": "ordinaria",
    "numero-sequencial": 1,
    estado: "agendada",
    modalidade: "presencial",
    delibera: true,
    "transmite-publica": true,
    "gera-ata-regimental": true,
    "permite-voto-secreto": false,
    "permite-modalidade-remota": false,
    "agendada-para": "2026-08-01T14:00:00Z",
    "aberta-em": null,
    "encerrada-em": null,
    "motivo-nao-realizada": null,
    ...over,
  };
}

describe("derivarHome", () => {
  it("ordena minhas proposições mais-recente-primeiro", () => {
    const painel: MeuPainelOut = {
      proposicoes: [
        proposicao({ id: "antiga", atualizadoEm: "2026-01-01T00:00:00Z" }),
        proposicao({ id: "recente", atualizadoEm: "2026-06-01T00:00:00Z" }),
      ],
      pareceres: [],
      ciencias: [],
    };
    const r = derivarHome(painel, []);
    expect(r.minhasProposicoes.map((p) => p.id)).toEqual(["recente", "antiga"]);
  });

  it("separa pareceres por estado: aguardando vs concluído (os 4 terminais)", () => {
    const painel: MeuPainelOut = {
      proposicoes: [],
      pareceres: [
        parecer({ id: "aguardando", estado: "com_relator" }),
        parecer({ id: "aprovado", estado: "aprovado" }),
        parecer({ id: "rejeitado", estado: "rejeitado" }),
        parecer({ id: "prejudicado", estado: "prejudicado" }),
        parecer({ id: "prazo-vencido", estado: "prazo_vencido" }),
      ],
      ciencias: [],
    };
    const r = derivarHome(painel, []);
    expect(r.meusPareceres.aguardando.map((p) => p.id)).toEqual(["aguardando"]);
    expect(r.meusPareceres.concluidos.map((p) => p.id)).toEqual([
      "aprovado",
      "rejeitado",
      "prejudicado",
      "prazo-vencido",
    ]);
  });

  it("ciências pendentes passam intactas (o marcador 'para sua ciência' é a lista não-vazia)", () => {
    const painel: MeuPainelOut = {
      proposicoes: [],
      pareceres: [],
      ciencias: [
        {
          parecerId: "pc1",
          proposicaoId: "p1",
          tipo: "pl",
          ano: 2026,
          sequencial: 1,
          urnLex: "urn:lex:fixture",
          ementa: "Ementa",
        },
      ],
    };
    const r = derivarHome(painel, []);
    expect(r.ciencias).toHaveLength(1);
    expect(r.ciencias[0].parecerId).toBe("pc1");
  });

  it("próxima sessão escolhe a de menor data FUTURA", () => {
    const sessoes = [
      sessao({ id: "mais-distante", "agendada-para": "2026-12-01T14:00:00Z" }),
      sessao({ id: "mais-proxima", "agendada-para": "2026-08-01T14:00:00Z" }),
    ];
    const r = derivarHome({ proposicoes: [], pareceres: [], ciencias: [] }, sessoes);
    expect(r.proximaSessao?.id).toBe("mais-proxima");
  });

  it("próxima sessão ignora datas passadas", () => {
    const sessoes = [sessao({ id: "passada", "agendada-para": "2020-01-01T00:00:00Z" })];
    const r = derivarHome({ proposicoes: [], pareceres: [], ciencias: [] }, sessoes);
    expect(r.proximaSessao).toBeNull();
  });

  it("próxima sessão null quando não há sessão nenhuma", () => {
    const r = derivarHome({ proposicoes: [], pareceres: [], ciencias: [] }, []);
    expect(r.proximaSessao).toBeNull();
  });

  it("próxima sessão ignora sessão sem agendada-para", () => {
    const sessoes = [sessao({ id: "sem-data", "agendada-para": null })];
    const r = derivarHome({ proposicoes: [], pareceres: [], ciencias: [] }, sessoes);
    expect(r.proximaSessao).toBeNull();
  });

  it("painel/sessoes ausentes (undefined) -> estrutura vazia coerente, sem lançar", () => {
    const r = derivarHome(undefined, undefined);
    expect(r).toEqual({
      minhasProposicoes: [],
      meusPareceres: { aguardando: [], concluidos: [] },
      ciencias: [],
      proximaSessao: null,
    });
  });

  it("painel/sessoes null -> estrutura vazia coerente, sem lançar", () => {
    const r = derivarHome(null, null);
    expect(r.minhasProposicoes).toEqual([]);
    expect(r.proximaSessao).toBeNull();
  });
});
