import { describe, it, expect } from "vitest";
import {
  derivarFila,
  membrosInscriveis,
  nomeDoMembro,
  rotuloOradorAtual,
  FASES_TRIBUNA,
} from "./tribuna-mesa-vista";
import type { ComposicaoMembroOut, InscritoTribunaOut, OradorAtualOut } from "./contrato-sessoes.gen";

const membros: ComposicaoMembroOut[] = [
  { vereadorId: "v1", nomeParlamentar: "Ana Prado", cargoMesa: "Presidente", partido: null },
  { vereadorId: "v2", nomeParlamentar: "Beto Lima", cargoMesa: null, partido: null },
  { vereadorId: "v3", nomeParlamentar: null, cargoMesa: null, partido: null },
];

const inscrito = (over: Partial<InscritoTribunaOut>): InscritoTribunaOut => ({
  inscricaoId: "i1",
  vereadorId: "v1",
  origemInscricao: "intra_sessao_pedido",
  fase: "expediente",
  ordem: 1,
  lockVersion: 0,
  ...over,
});

describe("derivarFila", () => {
  it("ordena por ordem, resolve nome e marca o orador atual", () => {
    const inscritos = [
      inscrito({ inscricaoId: "iB", vereadorId: "v2", ordem: 2, fase: "ordem_do_dia" }),
      inscrito({ inscricaoId: "iA", vereadorId: "v1", ordem: 1 }),
    ];
    const orador: OradorAtualOut = {
      falaId: "f1", oradorId: "v1", tipoFala: "principal", fase: "expediente",
      iniciouEm: "2026-05-21T14:00:00Z", inscricaoId: "iA", tempoConcedidoSegundos: null, lockVersion: 0,
    };
    const fila = derivarFila(inscritos, membros, orador);
    expect(fila.map((l) => l.inscricaoId)).toEqual(["iA", "iB"]);
    expect(fila[0]).toMatchObject({ nome: "Ana Prado", faseRotulo: "Expediente", ehOrador: true });
    expect(fila[1]).toMatchObject({ nome: "Beto Lima", faseRotulo: "Ordem do Dia", ehOrador: false });
  });
  it("sem orador atual -> ninguém marcado", () => {
    const fila = derivarFila([inscrito({})], membros, null);
    expect(fila[0].ehOrador).toBe(false);
  });
  it("membro sem nome-parlamentar cai num rótulo honesto, nunca vazio", () => {
    const fila = derivarFila([inscrito({ vereadorId: "v3" })], membros, null);
    expect(fila[0].nome).toMatch(/Vereador/);
  });
});

describe("membrosInscriveis", () => {
  it("remove quem já está na fila", () => {
    const disp = membrosInscriveis(membros, [inscrito({ vereadorId: "v1" })]);
    expect(disp.map((m) => m.vereadorId)).toEqual(["v2", "v3"]);
  });
});

describe("rotuloOradorAtual / nomeDoMembro / FASES", () => {
  it("rotula o orador atual pelo nome, null quando ninguém fala", () => {
    expect(rotuloOradorAtual(null, membros)).toBeNull();
    const orador: OradorAtualOut = {
      falaId: "f", oradorId: "v2", tipoFala: "principal", fase: "expediente",
      iniciouEm: "x", inscricaoId: null, tempoConcedidoSegundos: null, lockVersion: 0,
    };
    expect(rotuloOradorAtual(orador, membros)).toBe("Beto Lima");
  });
  it("nomeDoMembro resolve ou cai no fallback", () => {
    expect(nomeDoMembro("v1", membros)).toBe("Ana Prado");
    expect(nomeDoMembro("zzz", membros)).toMatch(/Vereador/);
  });
  it("FASES_TRIBUNA cobre as 5 fases da pauta", () => {
    expect(FASES_TRIBUNA.map((f) => f.valor)).toEqual([
      "expediente",
      "grande_expediente",
      "ordem_do_dia",
      "explicacoes_pessoais",
      "tribuna_livre_cidadao",
    ]);
  });
});
