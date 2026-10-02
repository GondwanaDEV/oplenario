import { describe, expect, it } from "vitest";
import {
  codigoCurto,
  codigoEmGrupos,
  etapasDoEncerramento,
  exportacaoParaConfirmar,
  pedidoDeApagamento,
  tabelasDoResumo,
  tamanho,
} from "./encerramento-vista";
import type { Casa, Encerramento, Exportacao, Pedido } from "./use-operacao";

// ADR-0018 (fatia 2) — a lógica de tela do encerramento, pura: as etapas em ordem com a atual marcada, o tamanho e o
// código em português, qual exportação espera a confirmação.

const casa = (estado: Casa["estado"]): Casa => ({
  enteId: "e1", nome: "Câmara", nomeCurto: null, uf: "CE", municipio: null, estado, criadaEm: null,
  conviteEnviadoEm: null, ativadaEm: null,
});

const exp = (p: Partial<Exportacao>): Exportacao => ({
  id: "x", estado: "pronta", solicitadaEm: null, solicitadaPor: "operador", concluidaEm: null, sha256: "a".repeat(64),
  bytes: 10, manifesto: null, erro: null, confirmadaEm: null, confirmadaPor: null, oficio: null, ...p,
});

const enc = (p: Partial<Encerramento>): Encerramento => ({
  emCurso: true, desde: null, exportacoes: [], confirmacao: null, apagamentoPossivelEm: null, podePedirApagamento: false,
  exportacaoDisponivel: true, apagamentoDisponivel: true, apagamentoPendente: null, destinoAcervoUrl: null,
  encerradaEm: null, apagamento: null, ...p,
});

const estados = (e: ReturnType<typeof etapasDoEncerramento>) => Object.fromEntries(e.map((x) => [x.chave, x.estado]));
const agora = new Date("2026-10-02T12:00:00Z");

describe("etapasDoEncerramento", () => {
  it("sem nada: a exportação é a atual", () => {
    expect(estados(etapasDoEncerramento(casa("suspenso"), enc({}), agora))).toEqual({
      exportacao: "atual", confirmacao: "a-seguir", guarda: "a-seguir", destino: "a-seguir", apagamento: "a-seguir",
      encerrada: "a-seguir",
    });
  });

  it("exportada e confirmada, guarda correndo: a guarda é a atual; o destino informado conta como feito", () => {
    const c = exp({ confirmadaEm: "2026-10-01T00:00:00Z" });
    const e = etapasDoEncerramento(casa("suspenso"),
      enc({ exportacoes: [c], confirmacao: c, apagamentoPossivelEm: "2026-12-30T00:00:00Z", destinoAcervoUrl: "https://x" }), agora);
    expect(estados(e)).toMatchObject({ exportacao: "feita", confirmacao: "feita", guarda: "atual", destino: "feita" });
  });

  it("guarda cumprida: o apagamento é o atual (o destino, opcional, nunca segura o caminho)", () => {
    const c = exp({ confirmadaEm: "2026-06-01T00:00:00Z" });
    const e = etapasDoEncerramento(casa("suspenso"),
      enc({ exportacoes: [c], confirmacao: c, apagamentoPossivelEm: "2026-08-30T00:00:00Z" }), agora);
    expect(estados(e)).toMatchObject({ guarda: "feita", destino: "a-seguir", apagamento: "atual" });
  });

  it("encerrada: tudo concluído (menos o destino não informado)", () => {
    const e = etapasDoEncerramento(casa("encerrado"), enc({ emCurso: false }), agora);
    expect(estados(e)).toEqual({
      exportacao: "feita", confirmacao: "feita", guarda: "feita", destino: "a-seguir", apagamento: "feita", encerrada: "feita",
    });
  });
});

describe("formatos", () => {
  it("tamanho em português, base 1024", () => {
    expect(tamanho(512)).toBe("512 B");
    expect(tamanho(13_000_000)).toBe("12,4 MB");
    expect(tamanho(3 * 1024 ** 3)).toBe("3 GB");
    expect(tamanho(null)).toBe("—");
  });

  it("o código curto e em grupos", () => {
    const sha = "0123456789abcdef".repeat(4);
    expect(codigoCurto(sha)).toBe("01234567…89abcdef");
    expect(codigoCurto(null)).toBe("—");
    expect(codigoEmGrupos(sha).split(" ")).toHaveLength(8);
  });
});

describe("o que espera a pessoa", () => {
  it("a exportação pronta e não confirmada mais recente", () => {
    expect(exportacaoParaConfirmar([exp({ id: "a", estado: "gerando" }), exp({ id: "b" }), exp({ id: "c" })])?.id).toBe("b");
    expect(exportacaoParaConfirmar([exp({ confirmadaEm: "2026-10-01T00:00:00Z" })])).toBeNull();
  });

  it("só o pedido de APAGAR vai para a etapa do apagamento", () => {
    const p = { acao: "apagar" } as Pedido;
    expect(pedidoDeApagamento(p)).toBe(p);
    expect(pedidoDeApagamento({ acao: "suspender" } as Pedido)).toBeNull();
    expect(pedidoDeApagamento(null)).toBeNull();
  });

  it("as tabelas do resumo, maior primeiro", () => {
    expect(tabelasDoResumo({ "a.x": 2, "b.y": 10, "a.a": 2 })).toEqual([["b.y", 10], ["a.a", 2], ["a.x", 2]]);
    expect(tabelasDoResumo(undefined)).toEqual([]);
  });
});
