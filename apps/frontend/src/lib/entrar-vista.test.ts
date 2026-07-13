import { describe, expect, it } from "vitest";
import { derivarVistaEntrada } from "./entrar-vista";

// Task 12 (Onda D Slice 2) — view-model puro de /entrar/[ente]: mapeia o resultado bruto de
// GET /auth/descoberta/:ente (backend T3) para o estado de exibição da tela (ok / câmara-não-encontrada /
// erro). Ver phase2-shared-decisions.md §3: 200 -> {ente-id, realm, base-url, client-id} (SEM nome); 400
// uuid malformado; 404 ente desconhecido.

describe("derivarVistaEntrada", () => {
  it("200 com corpo válido (forma real do backend hoje, sem nome) -> ok, extrai ente-id", () => {
    const vista = derivarVistaEntrada({
      status: 200,
      corpo: { "ente-id": "abc-123", realm: "ente-abc-123", "base-url": "http://x", "client-id": "oplenario-web" },
    });
    expect(vista).toEqual({ estado: "ok", enteId: "abc-123", nome: null });
  });

  it("200 com `nome` (forward-compat — campo que o backend pode vir a adicionar) -> usa o nome", () => {
    const vista = derivarVistaEntrada({
      status: 200,
      corpo: { "ente-id": "abc-123", nome: "Câmara de Exemplo" },
    });
    expect(vista.nome).toBe("Câmara de Exemplo");
  });

  it("200 com `nome-camara` (variante de chave) -> usa como fallback do nome", () => {
    const vista = derivarVistaEntrada({
      status: 200,
      corpo: { "ente-id": "abc-123", "nome-camara": "Câmara X" },
    });
    expect(vista.nome).toBe("Câmara X");
  });

  it("404 -> câmara não encontrada (nunca oferece o botão Entrar)", () => {
    expect(derivarVistaEntrada({ status: 404, corpo: null })).toEqual({
      estado: "nao-encontrada",
      enteId: null,
      nome: null,
    });
  });

  it("400 (uuid malformado) -> erro genérico, NÃO confundido com 404", () => {
    expect(derivarVistaEntrada({ status: 400, corpo: null })).toEqual({
      estado: "erro",
      enteId: null,
      nome: null,
    });
  });

  it("status 0 (falha de rede/exceção no fetch) -> erro, fail-closed", () => {
    expect(derivarVistaEntrada({ status: 0, corpo: null })).toEqual({
      estado: "erro",
      enteId: null,
      nome: null,
    });
  });

  it("200 mas corpo nulo (resposta malformada) -> erro, fail-closed", () => {
    expect(derivarVistaEntrada({ status: 200, corpo: null })).toEqual({
      estado: "erro",
      enteId: null,
      nome: null,
    });
  });

  it("ente-id ausente ou não-string no corpo -> enteId null (não quebra, não inventa)", () => {
    const vista = derivarVistaEntrada({ status: 200, corpo: { realm: "x" } });
    expect(vista).toEqual({ estado: "ok", enteId: null, nome: null });
  });
});
