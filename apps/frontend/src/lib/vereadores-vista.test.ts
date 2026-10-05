import { describe, expect, it } from "vitest";
import type { VereadoresOut } from "./contrato-portal.gen";
import { derivarLista, hrefDoPerfil, resumoDaLista } from "./vereadores-vista";

const v = (over: Partial<VereadoresOut["vereadores"][number]>): VereadoresOut["vereadores"][number] => ({
  vereadorId: "id",
  nomeParlamentar: null,
  nomeCivil: "Fulano de Tal",
  partido: null,
  cargoMesa: null,
  ...over,
});

describe("derivarLista", () => {
  it("ordena pelo nome EXIBIDO (apelido, ou civil), sem diferenciar acento nem caixa; o id desempata homônimos", () => {
    const { vereadores } = derivarLista(
      {
        vereadores: [
          v({ vereadorId: "3", nomeCivil: "Zeca" }),
          v({ vereadorId: "2", nomeParlamentar: "Ágata", nomeCivil: "Zélia Souza" }),
          v({ vereadorId: "1", nomeParlamentar: "Ágata", nomeCivil: "Outra Pessoa" }),
          v({ vereadorId: "4", nomeParlamentar: "bruno", nomeCivil: "Bruno Lima" }),
        ],
      },
      "e",
    );
    expect(vereadores.map((x) => x.vereadorId)).toEqual(["1", "2", "4", "3"]);
  });

  it("apelido em branco é ausente; o civil só aparece por baixo quando difere", () => {
    const [a, b, c] = derivarLista(
      {
        vereadores: [
          v({ vereadorId: "a", nomeParlamentar: "  ", nomeCivil: "Ana Prado" }),
          v({ vereadorId: "b", nomeParlamentar: "Bia", nomeCivil: "Beatriz Alves" }),
          v({ vereadorId: "c", nomeParlamentar: "Caio Reis", nomeCivil: "Caio Reis" }),
        ],
      },
      "e",
    ).vereadores;
    expect([a.nome, a.nomeSecundario]).toEqual(["Ana Prado", null]);
    expect([b.nome, b.nomeSecundario]).toEqual(["Bia", "Beatriz Alves"]);
    expect([c.nome, c.nomeSecundario]).toEqual(["Caio Reis", null]);
  });

  it("as iniciais são do nome exibido, nunca do id", () => {
    const [x] = derivarLista({ vereadores: [v({ vereadorId: "7f3c2a10-uuid", nomeParlamentar: "Helena Past" })] }, "e").vereadores;
    expect(x.iniciais).toBe("HP");
  });

  it("o resumo é o número da lista mostrada, no singular e no plural", () => {
    expect(resumoDaLista(0)).toBe("Nenhum vereador em exercício");
    expect(resumoDaLista(1)).toBe("1 vereador em exercício");
    expect(resumoDaLista(21)).toBe("21 vereadores em exercício");
    expect(derivarLista({ vereadores: [v({ vereadorId: "1" }), v({ vereadorId: "2" })] }, "e").resumo).toBe("2 vereadores em exercício");
  });
});

describe("hrefDoPerfil", () => {
  it("codifica o ente e o id: um `../` nunca resolve para fora do portal", () => {
    expect(hrefDoPerfil("casa-1", "abc")).toBe("/portal/casa/casa-1/vereadores/abc");
    expect(hrefDoPerfil("../x", "..")).toBe("/portal/casa/..%2Fx/vereadores/%2E%2E");
  });
});
