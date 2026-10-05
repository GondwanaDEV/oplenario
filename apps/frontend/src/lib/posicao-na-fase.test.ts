import { describe, expect, it } from "vitest";
import { posicoesNaFase } from "./posicao-na-fase";

describe("posicoesNaFase — o número que se lê é a posição dentro da fase", () => {
  it("o primeiro item da Ordem do Dia é o 1, mesmo com quatro no Expediente antes", () => {
    const itens = [
      { id: "e1", fase: "expediente", ordem: 1 },
      { id: "e2", fase: "expediente", ordem: 2 },
      { id: "e3", fase: "expediente", ordem: 3 },
      { id: "e4", fase: "expediente", ordem: 4 },
      { id: "o1", fase: "ordem_do_dia", ordem: 5 },
      { id: "o2", fase: "ordem_do_dia", ordem: 6 },
    ];
    const p = posicoesNaFase(itens);
    expect([p.get("e1"), p.get("e4")]).toEqual([1, 4]);
    expect([p.get("o1"), p.get("o2")]).toEqual([1, 2]);
  });

  it("fases intercaladas na ordem global numeram cada uma por conta própria", () => {
    const p = posicoesNaFase([
      { id: "a", fase: "ordem_do_dia", ordem: 1 },
      { id: "b", fase: "expediente", ordem: 2 },
      { id: "c", fase: "ordem_do_dia", ordem: 3 },
      { id: "d", fase: "expediente", ordem: 4 },
    ]);
    expect([p.get("a"), p.get("c")]).toEqual([1, 2]);
    expect([p.get("b"), p.get("d")]).toEqual([1, 2]);
  });

  it("buraco na ordem (item retirado) não vira buraco na numeração", () => {
    const p = posicoesNaFase([
      { id: "a", fase: "ordem_do_dia", ordem: 3 },
      { id: "b", fase: "ordem_do_dia", ordem: 9 },
    ]);
    expect([p.get("a"), p.get("b")]).toEqual([1, 2]);
  });

  it("não depende da ordem em que os itens chegam: vale a ordem crescente de `ordem`", () => {
    const p = posicoesNaFase([
      { id: "b", fase: "ordem_do_dia", ordem: 7 },
      { id: "a", fase: "ordem_do_dia", ordem: 5 },
    ]);
    expect([p.get("a"), p.get("b")]).toEqual([1, 2]);
  });

  it("empate de ordem (pauta antiga com ordens repetidas) mantém a ordem de chegada e não repete número", () => {
    const p = posicoesNaFase([
      { id: "a", fase: "ordem_do_dia", ordem: 2 },
      { id: "b", fase: "ordem_do_dia", ordem: 2 },
    ]);
    expect([p.get("a"), p.get("b")]).toEqual([1, 2]);
  });

  it("pauta vazia não numera nada e não lança", () => {
    expect(posicoesNaFase([]).size).toBe(0);
  });
});
