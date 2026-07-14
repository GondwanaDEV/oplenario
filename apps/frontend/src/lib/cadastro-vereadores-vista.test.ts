import { describe, expect, it } from "vitest";
import { avatar, estadoChip, filtrar, selecaoInicial } from "./cadastro-vereadores-vista";
import type { VereadorLinhaOut } from "./contrato-cadastros.gen";

// View-model puro do Cadastro de vereadores (Task 7) — traduz VereadorLinhaOut (wire de
// GET /cadastros/vereadores, já camelizado) em pedaços de apresentação: avatar (iniciais+cor
// determinísticas), chip de estado do mandato (catch-all fail-closed — nunca lança pra um estado
// desconhecido), filtro de busca client-side e seleção inicial vinda da URL. Mesma disciplina de
// tramitacao-board-vista.ts (funções puras, sem DOM/fetch).

function linha(parcial: Partial<VereadorLinhaOut> & { id: string; nome: string }): VereadorLinhaOut {
  return {
    nomeParlamentar: null,
    partido: null,
    estadoMandato: null,
    cargoMesa: null,
    ...parcial,
  };
}

describe("avatar", () => {
  it("é determinístico: a mesma entrada produz a mesma cor em chamadas repetidas", () => {
    const a1 = avatar("Ana Maria", "id-1");
    const a2 = avatar("Ana Maria", "id-1");
    expect(a1).toEqual(a2);
  });

  it("nomes diferentes PODEM produzir cores diferentes (paleta com mais de 1 cor)", () => {
    const cores = new Set(
      ["Ana Maria", "Herculano Pereira", "Carlos Tavares", "João Silva", "Rita Melo", "Lúcia Bastos", "Fábio Duarte"].map(
        (nome, i) => avatar(nome, `id-${i}`).cor,
      ),
    );
    expect(cores.size).toBeGreaterThan(1);
  });

  it("iniciais = 1ª letra maiúscula das 2 primeiras palavras do nome", () => {
    expect(avatar("Ana Maria", "id-1").iniciais).toBe("AM");
    expect(avatar("Herculano Pereira Souza", "id-2").iniciais).toBe("HP");
  });

  it("nome de 1 palavra só -> iniciais é só a 1ª letra", () => {
    expect(avatar("Madonna", "id-3").iniciais).toBe("M");
  });

  it("cor vem da paleta fixa da tela-fonte, usável direto como CSS background", () => {
    const PALETA = ["var(--jade)", "var(--cobalto)", "var(--telha)", "var(--jade-claro)", "#7A4FA0", "var(--cobalto-fundo)", "#9C6B1E"];
    const { cor } = avatar("Qualquer Nome", "id-x");
    expect(PALETA).toContain(cor);
  });

  it("sem id -> cai no hash do nome (ainda determinístico)", () => {
    const a1 = avatar("Ana Maria");
    const a2 = avatar("Ana Maria");
    expect(a1).toEqual(a2);
  });
});

describe("estadoChip", () => {
  it("vigente -> Mandato ativo, tom ativo", () => {
    expect(estadoChip("vigente")).toEqual({ rotulo: "Mandato ativo", tom: "ativo" });
  });

  it("licenciado -> Licença, tom licenca", () => {
    expect(estadoChip("licenciado")).toEqual({ rotulo: "Licença", tom: "licenca" });
  });

  it("cassado -> rótulo neutro honesto, NÃO lança", () => {
    const chip = estadoChip("cassado");
    expect(chip.tom).toBe("neutro");
    expect(chip.rotulo).toMatch(/cassado/i);
  });

  it("renunciado/falecido/concluido -> tom neutro, rótulo não vazio", () => {
    for (const estado of ["renunciado", "falecido", "concluido"]) {
      const chip = estadoChip(estado);
      expect(chip.tom).toBe("neutro");
      expect(chip.rotulo.length).toBeGreaterThan(0);
    }
  });

  it("estado totalmente desconhecido -> tom neutro, não lança (fail-closed)", () => {
    expect(() => estadoChip("xpto-desconhecido")).not.toThrow();
    expect(estadoChip("xpto-desconhecido").tom).toBe("neutro");
  });

  it("null -> Sem mandato, tom neutro", () => {
    expect(estadoChip(null)).toEqual({ rotulo: "Sem mandato", tom: "neutro" });
  });

  it("undefined -> Sem mandato, tom neutro", () => {
    expect(estadoChip(undefined)).toEqual({ rotulo: "Sem mandato", tom: "neutro" });
  });
});

describe("filtrar", () => {
  const linhas: VereadorLinhaOut[] = [
    linha({ id: "1", nome: "Ana Maria Souza", nomeParlamentar: "Ana Souza", partido: "PT" }),
    linha({ id: "2", nome: "Herculano Pereira", nomeParlamentar: null, partido: "PSDB" }),
    linha({ id: "3", nome: "Carlos Tavares", nomeParlamentar: "Carlão", partido: "MDB" }),
  ];

  it("substring case-insensitive do nome -> encontra", () => {
    expect(filtrar(linhas, "ana maria").map((l) => l.id)).toEqual(["1"]);
    expect(filtrar(linhas, "ANA MARIA").map((l) => l.id)).toEqual(["1"]);
  });

  it("substring case-insensitive do nomeParlamentar -> encontra", () => {
    expect(filtrar(linhas, "carlão").map((l) => l.id)).toEqual(["3"]);
  });

  it("substring case-insensitive do partido -> encontra", () => {
    expect(filtrar(linhas, "psdb").map((l) => l.id)).toEqual(["2"]);
  });

  it("busca vazia ou só espaços -> retorna todas as linhas", () => {
    expect(filtrar(linhas, "")).toEqual(linhas);
    expect(filtrar(linhas, "   ")).toEqual(linhas);
  });

  it("sem correspondência -> lista vazia", () => {
    expect(filtrar(linhas, "zzz-nao-existe")).toEqual([]);
  });

  it("nomeParlamentar/partido nulos não quebram a busca", () => {
    expect(() => filtrar(linhas, "herculano")).not.toThrow();
    expect(filtrar(linhas, "herculano").map((l) => l.id)).toEqual(["2"]);
  });
});

describe("selecaoInicial", () => {
  const linhas: VereadorLinhaOut[] = [
    linha({ id: "1", nome: "Ana Maria" }),
    linha({ id: "2", nome: "Herculano Pereira" }),
  ];

  it("urlId presente na lista -> retorna o urlId", () => {
    expect(selecaoInicial(linhas, "2")).toBe("2");
  });

  it("urlId ausente na lista -> retorna o id da 1ª linha", () => {
    expect(selecaoInicial(linhas, "id-que-nao-existe")).toBe("1");
  });

  it("urlId null -> retorna o id da 1ª linha", () => {
    expect(selecaoInicial(linhas, null)).toBe("1");
  });

  it("lista vazia -> retorna null, mesmo com urlId presente", () => {
    expect(selecaoInicial([], "2")).toBeNull();
    expect(selecaoInicial([], null)).toBeNull();
  });
});
