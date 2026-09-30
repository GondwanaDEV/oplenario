import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { FaixaAcessoRestritoDaSessao, diaMes, textoDaFaixa } from "./faixa-acesso-restrito";

// ADR-0018 — a faixa "Sistema da Câmara com acesso restrito desde DD/MM": o interno vê o motivo, a cidadã não (o
// backend nem o manda a ela); sem restrição, nada.

function json(corpo: unknown, status = 200) {
  return new Response(JSON.stringify(corpo), { status, headers: { "content-type": "application/json" } });
}

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe("faixa de acesso restrito", () => {
  it("DD/MM no fuso da Casa (não em UTC)", () => {
    expect(diaMes("2026-09-30T02:00:00Z")).toBe("29/09");
    expect(diaMes("2026-09-30T13:00:00Z")).toBe("30/09");
  });

  it("o texto: com motivo para o interno, sem para o público", () => {
    expect(textoDaFaixa({ desde: "2026-09-29T13:00:00Z", motivo: "inadimplencia" }))
      .toBe("Sistema da Câmara com acesso restrito desde 29/09 — motivo: inadimplência do contrato");
    expect(textoDaFaixa({ desde: "2026-09-29T13:00:00Z" })).toBe("Sistema da Câmara com acesso restrito desde 29/09");
  });

  it("na sessão interna: lê /eu e mostra o motivo e o que segue funcionando", async () => {
    const f = vi.fn().mockResolvedValue(json({ ator: {}, "acesso-restrito": { desde: "2026-09-29T13:00:00Z", motivo: "ordem_judicial" } }));
    vi.stubGlobal("fetch", f);
    render(<FaixaAcessoRestritoDaSessao token={null} />);
    const faixa = await screen.findByRole("status");
    expect(faixa.textContent).toMatch(/desde 29\/09 — motivo: ordem judicial/);
    expect(faixa.textContent).toMatch(/respondendo os pedidos do cidadão/);
    expect(f.mock.calls[0][0]).toBe("/api/eu");
  });

  it("a cidadã: sem motivo (o servidor não manda)", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(json({ ator: {}, "acesso-restrito": { desde: "2026-09-29T13:00:00Z" } })));
    render(<FaixaAcessoRestritoDaSessao token={null} />);
    expect((await screen.findByRole("status")).textContent).not.toMatch(/motivo/);
  });

  it("Casa ativa, ou /eu falhou: sem faixa", async () => {
    const f = vi.fn().mockResolvedValue(json({ ator: {} }));
    vi.stubGlobal("fetch", f);
    render(<FaixaAcessoRestritoDaSessao token={null} />);
    await vi.waitFor(() => expect(f).toHaveBeenCalled());
    expect(screen.queryByRole("status")).toBeNull();
    cleanup();
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new Error("rede")));
    render(<FaixaAcessoRestritoDaSessao token={null} />);
    expect(screen.queryByRole("status")).toBeNull();
  });
});
