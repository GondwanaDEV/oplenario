import { describe, expect, it } from "vitest";
import {
  nomeModalidade,
  nomeObjeto,
  nomeQuorum,
  nomeVoto,
  placarEmPalavras,
  resultadoEmPalavras,
} from "./votacoes-publicas-vista";

describe("vocabulário do portal de votações", () => {
  it("o placar por extenso concorda no singular e no plural", () => {
    expect(placarEmPalavras({ sim: 2, nao: 1, abstencoes: 0 })).toBe("2 votos a favor, 1 voto contra e 0 abstenções");
    expect(placarEmPalavras({ sim: 1, nao: 0, abstencoes: 1 })).toBe("1 voto a favor, 0 votos contra e 1 abstenção");
  });

  it("o resultado diz a contagem, ou que foi simbólica quando não há contagem", () => {
    expect(
      resultadoEmPalavras({ resultado: "rejeitada", modalidade: "nominal", placar: { sim: 3, nao: 9, abstencoes: 0 } }),
    ).toBe("Rejeitada: 3 votos a favor, 9 votos contra e 0 abstenções");
    expect(resultadoEmPalavras({ resultado: "aprovada", modalidade: "simbolica", placar: null })).toBe(
      "Aprovada por votação simbólica, sem contagem de votos",
    );
  });

  it("nenhuma chave de backend chega crua ao leitor", () => {
    expect(nomeQuorum("maioria_qualificada_2_3")).toBe("dois terços dos membros");
    expect(nomeObjeto("parecer")).toBe("Parecer de comissão");
    expect(nomeModalidade("secreta")).toBe("votação secreta");
    expect([nomeVoto("sim"), nomeVoto("nao"), nomeVoto("abstencao")]).toEqual(["A favor", "Contra", "Abstenção"]);
  });
});
