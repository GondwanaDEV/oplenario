import { describe, it, expect } from "vitest";
import { derivarPlacar } from "./placar-vista";
import type { PlacarVotacao } from "./plenario-reducer";

// Base de um placar nominal aberto (sem votos), para os testes mutarem.
function nominalAberto(over: Partial<PlacarVotacao> = {}): PlacarVotacao {
  return {
    votacaoId: "v1",
    modalidade: "nominal",
    objetoTipo: "proposicao",
    objetoId: "p1",
    encerrada: false,
    votosNominais: {},
    votosSecretos: 0,
    resultado: null,
    totais: null,
    baseMembros: null,
    ...over,
  };
}

describe("derivarPlacar — view-model do placar (§22.6 sigilo no cliente)", () => {
  it("sem placar → 'nenhuma'", () => {
    expect(derivarPlacar(null)).toEqual({ kind: "nenhuma" });
  });

  it("nominal aberta: deriva a contagem dos votos por vereador", () => {
    const v = derivarPlacar(nominalAberto({ votosNominais: { a: "sim", b: "nao", c: "sim", d: "abstencao" } }));
    expect(v.kind).toBe("nominal");
    if (v.kind !== "nominal") throw new Error("kind");
    expect(v.sim).toBe(2);
    expect(v.nao).toBe(1);
    expect(v.abstencao).toBe(1);
    expect(v.votos).toHaveLength(4);
    expect(v.encerrada).toBe(false);
  });

  it("nominal: 'faltam' = base − votos quando há base; null sem base", () => {
    const comBase = derivarPlacar(nominalAberto({ votosNominais: { a: "sim", b: "nao" }, baseMembros: 9 }));
    if (comBase.kind !== "nominal") throw new Error("kind");
    expect(comBase.faltam).toBe(7);
    const semBase = derivarPlacar(nominalAberto({ votosNominais: { a: "sim" } }));
    if (semBase.kind !== "nominal") throw new Error("kind");
    expect(semBase.faltam).toBeNull();
  });

  it("nominal encerrada: os TOTAIS do agregado mandam sobre a contagem local", () => {
    // local tem 1 voto, mas o agregado oficial diz 5/3/1 (a fonte de verdade no encerramento).
    const v = derivarPlacar(
      nominalAberto({
        votosNominais: { a: "sim" },
        encerrada: true,
        resultado: "aprovada",
        totais: { sim: 5, nao: 3, abstencao: 1 },
        baseMembros: 9,
      }),
    );
    if (v.kind !== "nominal") throw new Error("kind");
    expect(v.sim).toBe(5);
    expect(v.nao).toBe(3);
    expect(v.abstencao).toBe(1);
    expect(v.resultado).toBe("aprovada");
    expect(v.faltam).toBe(0); // 9 − (5+3+1)
    // a grade local tem só 1 voto, mas o agregado diz 9 → marca como PARCIAL (reconexão), sem mentir no Tally.
    expect(v.votos).toHaveLength(1);
    expect(v.votosParciais).toBe(true);
  });

  it("nominal encerrada com grade completa NÃO marca parcial", () => {
    const v = derivarPlacar(
      nominalAberto({
        votosNominais: { a: "sim", b: "nao" },
        encerrada: true,
        resultado: "aprovada",
        totais: { sim: 1, nao: 1, abstencao: 0 },
        baseMembros: 2,
      }),
    );
    if (v.kind !== "nominal") throw new Error("kind");
    expect(v.votosParciais).toBe(false);
  });

  it("resultado fora do conjunto permitido vira null (não pode virar classe CSS injetada)", () => {
    const v = derivarPlacar(nominalAberto({ encerrada: true, resultado: "aprovada extra-classe" }));
    if (v.kind !== "nominal") throw new Error("kind");
    expect(v.resultado).toBeNull();
  });

  it("SECRETA aberta: só o contador anônimo — NUNCA voto por vereador", () => {
    const v = derivarPlacar(nominalAberto({ modalidade: "secreta", votosSecretos: 7 }));
    expect(v.kind).toBe("secreta");
    if (v.kind !== "secreta") throw new Error("kind");
    expect(v.registrados).toBe(7);
    expect(v.totais).toBeNull();
    // SIGILO §22.6: a vista secreta NÃO carrega array de votos individuais, estruturalmente.
    expect("votos" in v).toBe(false);
  });

  it("SECRETA encerrada: o agregado vira público (totais), mas segue sem nominal", () => {
    const v = derivarPlacar(
      nominalAberto({
        modalidade: "secreta",
        votosSecretos: 9,
        encerrada: true,
        resultado: "rejeitada",
        totais: { sim: 4, nao: 5, abstencao: 0 },
        baseMembros: 9,
      }),
    );
    if (v.kind !== "secreta") throw new Error("kind");
    expect(v.totais).toEqual({ sim: 4, nao: 5, abstencao: 0 });
    expect(v.resultado).toBe("rejeitada");
    expect("votos" in v).toBe(false);
  });

  it("encerramento sem modalidade conhecida (reconexão): trata como secreta-agregada, nunca inventa nominal", () => {
    // placar construído só do encerramento (modalidade ""), sem ter visto a abertura.
    const v = derivarPlacar(
      nominalAberto({
        modalidade: "",
        encerrada: true,
        resultado: "aprovada",
        totais: { sim: 6, nao: 1, abstencao: 0 },
        baseMembros: 7,
      }),
    );
    // sem prova de que é nominal, o cliente NÃO expõe grade nominal (fail-closed p/ sigilo).
    expect(v.kind).toBe("secreta");
  });
});

describe("avisoLacuna — sinal sintético repassado do reducer (frente truncamento-familia, sítio d)", () => {
  it("default false quando o chamador não passa o parâmetro", () => {
    const v = derivarPlacar(nominalAberto());
    expect(v.kind).toBe("nominal");
    expect((v as { avisoLacuna: boolean }).avisoLacuna).toBe(false);
  });

  it("nominal EM CURSO repassa avisoLacuna=true — o placar pode estar incompleto sem que a UI minta por omissão", () => {
    const v = derivarPlacar(nominalAberto({ votosNominais: { a: "sim" } }), true);
    expect(v.kind).toBe("nominal");
    expect((v as { avisoLacuna: boolean }).avisoLacuna).toBe(true);
  });

  it("secreta também repassa avisoLacuna — o contador pode ter perdido um tick sem ninguém saber", () => {
    const v = derivarPlacar(nominalAberto({ modalidade: "secreta", votosSecretos: 3 }), true);
    expect(v.kind).toBe("secreta");
    expect((v as { avisoLacuna: boolean }).avisoLacuna).toBe(true);
  });

  it("sem placar nenhum: nada a avisar (não há votação para desconfiar)", () => {
    expect(derivarPlacar(null, true)).toEqual({ kind: "nenhuma" });
  });
});
