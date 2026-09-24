import { describe, it, expect } from "vitest";
import type { PautaOut, SessaoOut } from "./contrato";
import { estadoInicial, type EstadoPlenario, type PlacarVotacao } from "./plenario-reducer";
import { frasesDoLetreiro, type FraseLetreiro } from "./tv-letreiro";

const sessao = { "aberta-em": "2026-09-23T16:25:00Z" } as Pick<SessaoOut, "aberta-em">;

const base = (over: Partial<EstadoPlenario> = {}): EstadoPlenario => ({
  ...estadoInicial({
    id: "s1", "sessao-legislativa-id": "sl", "tipo-sessao": "ordinaria", "numero-sequencial": 15, estado: "aberta",
    modalidade: "presencial", delibera: true, "transmite-publica": true, "gera-ata-regimental": true,
    "permite-voto-secreto": false, "permite-modalidade-remota": false, "agendada-para": null,
    "aberta-em": "2026-09-23T16:25:00Z", "encerrada-em": null, "motivo-nao-realizada": null,
  }),
  ...over,
});

const placar = (over: Partial<PlacarVotacao> = {}): PlacarVotacao => ({
  votacaoId: "v1", modalidade: "nominal", objetoTipo: "proposicao", objetoId: "p22",
  proposicao: { tipo: "projeto_lei", ano: 2026, sequencial: 22, ementa: "Energia solar" },
  encerrada: false, votosNominais: {}, votosSecretos: 0, resultado: null, totais: null, baseMembros: null, ...over,
});

const quorum = { presentesTotal: 18, presentesPlenario: 18, presentesRemoto: 0, membrosDaCasa: 21, presencasForaDoRoster: 0, semRegistroDePresenca: false };
const texto = (fs: FraseLetreiro[]) => fs.map((x) => x.antes + x.destaque + x.depois);

describe("frasesDoLetreiro — derivado do estado ATUAL", () => {
  it("votação aberta com quórum: faltam N votos + quórum", () => {
    const fs = texto(frasesDoLetreiro(sessao, base({ quorum, placar: placar({ votosNominais: { a: "sim", b: "sim", c: "nao" } }) }), null, 0));
    expect(fs.find((t) => t.startsWith("Em votação:"))).toMatch(/22\/2026 — faltam 15 votos$/);
    expect(fs).toContain("Quórum: 18 de 21 vereadores presentes");
  });

  it("sem quórum: diz quantos votaram, nunca 'faltam'", () => {
    const fs = texto(frasesDoLetreiro(sessao, base({ placar: placar({ votosNominais: { a: "sim" } }) }), null, 0));
    expect(fs.find((t) => t.startsWith("Em votação:"))).toMatch(/— 1 voto registrado$/);
    expect(fs.join(" ")).not.toContain("faltam");
  });

  it("votação encerrada vira 'Última votação', no passado, com o placar final", () => {
    const fs = texto(
      frasesDoLetreiro(sessao, base({ placar: placar({ encerrada: true, resultado: "aprovada", totais: { sim: 14, nao: 3, abstencao: 1 } }) }), null, 0),
    );
    expect(fs.some((t) => t.startsWith("Em votação:"))).toBe(false);
    expect(fs.find((t) => t.startsWith("Última votação:"))).toMatch(/22\/2026 aprovada por 14 × 3 \(1 abst\.\)$/);
  });

  it("suspensa aparece primeiro; pauta e abertura entram quando há dado", () => {
    const pauta: PautaOut = { "sessao-id": "s1", itens: [{ id: "i", fase: "expediente", "tipo-item": "leitura", ordem: 1 }] };
    const fs = texto(frasesDoLetreiro(sessao, base({ estado: "suspensa" }), pauta, 0));
    expect(fs[0]).toMatch(/^Sessão suspensa/);
    expect(fs).toContain("Pauta do dia: 1 item");
    expect(fs.some((t) => /^Sessão aberta às \d{2}:\d{2}$/.test(t))).toBe(true);
  });

  it("sessão agendada, sem dado nenhum: lista vazia (o rodapé não inventa frase)", () => {
    expect(frasesDoLetreiro({ "aberta-em": null }, base({ estado: "agendada" }), null, 0)).toEqual([]);
  });
});
