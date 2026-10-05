import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import {
  ProvedorDaDica,
  dicaDaAta,
  dicaDaMateria,
  dicaDaMateriaPeloNumero,
  dicaDaPauta,
  dicaDaSessao,
  rotuloDaSessao,
  useDicaAtual,
  useDicaDaClara,
  type DicaDaClara,
} from "./dica";

afterEach(cleanup);

describe("dicaDaMateria", () => {
  it("o número da matéria, no formato da ficha", () => {
    expect(dicaDaMateria("projeto_lei", 42, 2026)).toEqual({
      rotulo: "PL 42/2026",
      inicio: "Sobre o PL 42/2026, ",
      acao: "Perguntar sobre esta matéria",
    });
  });

  it("sem sequencial ou sem ano, nada", () => {
    expect(dicaDaMateria("projeto_lei", null, 2026)).toBeNull();
    expect(dicaDaMateria("projeto_lei", 42, undefined)).toBeNull();
  });
});

describe("dicaDaMateriaPeloNumero", () => {
  it("o número que o servidor já formatou (o `ref` do pedido jurídico)", () => {
    expect(dicaDaMateriaPeloNumero("REQ 230/2026")).toEqual({
      rotulo: "REQ 230/2026",
      inicio: "Sobre o REQ 230/2026, ",
      acao: "Perguntar sobre esta matéria",
    });
  });

  it("sem número (consulta avulsa), nada", () => {
    expect(dicaDaMateriaPeloNumero(null)).toBeNull();
    expect(dicaDaMateriaPeloNumero(undefined)).toBeNull();
    expect(dicaDaMateriaPeloNumero("  ")).toBeNull();
  });
});

describe("rotuloDaSessao", () => {
  it("com número: o título que a pauta mostra", () => {
    expect(rotuloDaSessao({ tipoSessao: "ordinaria", numeroSequencial: 15 })).toBe("15ª Sessão Ordinária");
    expect(rotuloDaSessao({ tipoSessao: "extraordinaria", numeroSequencial: 3 })).toBe("3ª Sessão Extraordinária");
  });

  it("audiência pública com número: o que a Mesa da audiência mostra", () => {
    expect(rotuloDaSessao({ tipoSessao: "audiencia_publica", numeroSequencial: 2 })).toBe("Audiência pública nº 2");
  });

  it("sem número: a data marcada, sem inventar número", () => {
    expect(rotuloDaSessao({ tipoSessao: "ordinaria", numeroSequencial: null, agendadaPara: "2026-10-05" })).toBe(
      "Sessão ordinária de 05/10/2026",
    );
    expect(rotuloDaSessao({ tipoSessao: "audiencia_publica", agendadaPara: "2026-10-05" })).toBe(
      "Audiência pública de 05/10/2026",
    );
  });

  it("sem número nem data, ou sem sessão: nada", () => {
    expect(rotuloDaSessao({ tipoSessao: "ordinaria", numeroSequencial: null, agendadaPara: null })).toBeNull();
    expect(rotuloDaSessao(null)).toBeNull();
    expect(rotuloDaSessao(undefined)).toBeNull();
  });
});

describe("dicaDaSessao", () => {
  it("rótulo, começo da pergunta e o botão", () => {
    expect(dicaDaSessao({ tipoSessao: "ordinaria", numeroSequencial: 15 })).toEqual({
      rotulo: "15ª Sessão Ordinária",
      inicio: "Sobre a 15ª Sessão Ordinária, ",
      acao: "Perguntar sobre esta sessão",
    });
  });

  it("sem nome possível, nada", () => {
    expect(dicaDaSessao(null)).toBeNull();
    expect(dicaDaSessao({ tipoSessao: "ordinaria" })).toBeNull();
  });
});

describe("dicaDaPauta", () => {
  it("rótulo, começo da pergunta e o botão", () => {
    expect(dicaDaPauta({ tipoSessao: "ordinaria", numeroSequencial: 15 })).toEqual({
      rotulo: "Pauta da 15ª Sessão Ordinária",
      inicio: "Sobre a pauta da 15ª Sessão Ordinária, ",
      acao: "Perguntar sobre esta pauta",
    });
  });

  it("sem nome possível, nada", () => {
    expect(dicaDaPauta(undefined)).toBeNull();
    expect(dicaDaPauta({ tipoSessao: "ordinaria", numeroSequencial: 0, agendadaPara: null })).toBeNull();
  });
});

describe("dicaDaAta", () => {
  it("rótulo, começo da pergunta e o botão", () => {
    expect(dicaDaAta({ tipoSessao: "ordinaria", numeroSequencial: 15 })).toEqual({
      rotulo: "Ata da 15ª Sessão Ordinária",
      inicio: "Sobre a ata da 15ª Sessão Ordinária, ",
      acao: "Perguntar sobre esta ata",
    });
  });

  it("sem número, pela data marcada", () => {
    expect(dicaDaAta({ tipoSessao: "extraordinaria", agendadaPara: "2026-09-30" })?.rotulo).toBe(
      "Ata da Sessão extraordinária de 30/09/2026",
    );
  });

  it("sem nome possível, nada", () => {
    expect(dicaDaAta(null)).toBeNull();
    expect(dicaDaAta({ tipoSessao: "ordinaria", numeroSequencial: 0, agendadaPara: null })).toBeNull();
  });
});

describe("useDicaDaClara", () => {
  function Sonda() {
    const dica = useDicaAtual();
    return <p data-testid="sonda">{dica ? `${dica.rotulo} | ${dica.inicio} | ${dica.acao}` : "sem dica"}</p>;
  }
  function Tela({ dica }: { dica: DicaDaClara | null }) {
    useDicaDaClara(dica);
    return null;
  }

  it("a tela publica enquanto está montada e some ao sair", () => {
    const { rerender } = render(
      <ProvedorDaDica>
        <Tela dica={dicaDaSessao({ tipoSessao: "ordinaria", numeroSequencial: 15 })} />
        <Sonda />
      </ProvedorDaDica>,
    );
    expect(screen.getByTestId("sonda").textContent).toBe(
      "15ª Sessão Ordinária | Sobre a 15ª Sessão Ordinária,  | Perguntar sobre esta sessão",
    );
    rerender(
      <ProvedorDaDica>
        <Sonda />
      </ProvedorDaDica>,
    );
    expect(screen.getByTestId("sonda").textContent).toBe("sem dica");
  });

  it("fora da moldura da Clara, não faz nada (e não quebra)", () => {
    render(<Tela dica={dicaDaPauta({ tipoSessao: "ordinaria", numeroSequencial: 15 })} />);
  });
});
