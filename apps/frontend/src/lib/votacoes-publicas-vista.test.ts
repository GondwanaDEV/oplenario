import { describe, expect, it } from "vitest";
import {
  atoDaVotacao,
  nomeModalidade,
  nomeObjeto,
  nomeQuorum,
  nomeResultado,
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

  it("a votação de turno (emenda à Lei Orgânica, dois turnos) diz o turno: o 1º aprovado não é a matéria aprovada", () => {
    expect(nomeResultado("aprovada", 1)).toBe("Aprovada em 1º turno");
    expect(nomeResultado("rejeitada", 2)).toBe("Rejeitada em 2º turno");
    expect(nomeResultado("aprovada")).toBe("Aprovada");
    expect(nomeResultado("aprovada", null)).toBe("Aprovada");
    expect(
      resultadoEmPalavras({ resultado: "aprovada", modalidade: "nominal", turno: 1, placar: { sim: 7, nao: 2, abstencoes: 0 } }),
    ).toBe("Aprovada em 1º turno: 7 votos a favor, 2 votos contra e 0 abstenções");
    expect(atoDaVotacao({ objetoTipo: "proposicao", resultado: "aprovada", turno: 1 })).toBe(
      "a matéria foi aprovada em 1º turno",
    );
    expect(atoDaVotacao({ objetoTipo: "proposicao", resultado: "aprovada", turno: 2 })).toBe(
      "a matéria foi aprovada em 2º turno",
    );
    expect(atoDaVotacao({ objetoTipo: "proposicao", resultado: "aprovada" })).toBe("a matéria foi aprovada");
    expect(atoDaVotacao({ objetoTipo: "redacao_final", resultado: "rejeitada" })).toBe("a redação final foi rejeitada");
  });

  it("nenhuma chave de backend chega crua ao leitor", () => {
    expect(nomeQuorum("maioria_qualificada_2_3")).toBe("dois terços dos membros");
    expect(nomeObjeto("parecer")).toBe("Parecer de comissão");
    expect(nomeModalidade("secreta")).toBe("votação secreta");
    expect([nomeVoto("sim"), nomeVoto("nao"), nomeVoto("abstencao")]).toEqual(["A favor", "Contra", "Abstenção"]);
  });
});
