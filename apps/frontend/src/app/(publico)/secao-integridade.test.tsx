import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { SecaoIntegridade } from "./secao-integridade";

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

function json(corpo: unknown, status = 200) {
  return new Response(JSON.stringify(corpo), { status, headers: { "content-type": "application/json" } });
}

describe("a âncora pública da trilha (selos do dia)", () => {
  it("publica o selo de cada dia, sem nenhum registro", async () => {
    const f = vi.fn().mockResolvedValue(json({ "selos-do-dia": [{ dia: "2026-09-28", seq: 1204, selo: "a7f39c21ffff" }] }));
    vi.stubGlobal("fetch", f);
    render(<SecaoIntegridade ente="fortaleza" />);
    const lista = await screen.findByRole("list", { name: "Selos do dia" });
    expect(lista.textContent).toMatch(/28\/09\/2026/);
    expect(lista.textContent).toMatch(/1\.204 registros/);
    expect(lista.textContent).toMatch(/a7f3·9c21/);
    expect(f.mock.calls[0][0]).toBe("/api/portal/casa/fortaleza/integridade");
  });

  it("antes do primeiro dia fechado, diz quando o selo sai", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(json({ "selos-do-dia": [] })));
    render(<SecaoIntegridade ente="fortaleza" />);
    expect(await screen.findByText(/primeiro selo sai ao fim do primeiro dia/)).toBeTruthy();
  });
});
