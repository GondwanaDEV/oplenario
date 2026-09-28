import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, within } from "@testing-library/react";
import { SecaoDadosAbertos } from "./secao-dados-abertos";

const catalogo = {
  datasets: [
    {
      chave: "proposicoes", titulo: "Proposições e tramitação", descricao: "Todas as proposições publicadas.",
      arquivo: "proposicoes.csv", formato: "csv", linhas: 1234, "atualizado-em": "2026-09-10T17:00:00Z",
      colunas: [{ nome: "ementa", descricao: "Ementa da proposição." }],
    },
    {
      chave: "votos-nominais", titulo: "Votações nominais", descricao: "Cada voto nominal.",
      arquivo: "votos-nominais.csv", formato: "csv", linhas: 0, "atualizado-em": null, colunas: [],
    },
  ],
};

function mockFetch(resposta: { ok: boolean; body?: unknown }) {
  const f = vi.fn(async () => ({ ok: resposta.ok, status: resposta.ok ? 200 : 404, json: async () => resposta.body }) as Response);
  global.fetch = f as unknown as typeof fetch;
  return f;
}

describe("SecaoDadosAbertos", () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it("um cartão por dataset, com volume, atualização, download e dicionário", async () => {
    const f = mockFetch({ ok: true, body: catalogo });
    render(<SecaoDadosAbertos ente="casa-1" />);
    const lista = await screen.findByRole("list", { name: "Conjuntos de dados" });
    const [prop, votos] = within(lista).getAllByRole("listitem").filter((li) => li.classList.contains("ds"));
    expect(within(prop).getByText("1.234 registros")).toBeTruthy();
    expect(within(prop).getByText(/atualizado em \d{2}\/09\/2026/)).toBeTruthy();
    expect(within(prop).getByRole("link", { name: "Baixar Proposições e tramitação (CSV)" }).getAttribute("href")).toBe(
      "/api/portal/casa/casa-1/dados-abertos/proposicoes.csv",
    );
    expect(within(prop).getByText("Ementa da proposição.")).toBeTruthy();
    expect(within(votos).getByText("nenhum registro ainda")).toBeTruthy();
    expect((f.mock.calls[0] as unknown[])[0]).toBe("/api/portal/casa/casa-1/dados-abertos");
  });

  it("despesas e presença aparecem como em-breve, com o motivo — nunca como arquivo", async () => {
    mockFetch({ ok: true, body: catalogo });
    render(<SecaoDadosAbertos ente="casa-1" />);
    await screen.findByRole("list", { name: "Conjuntos de dados" });
    expect(screen.getByRole("status", { name: "Despesas e empenhos: em breve" })).toBeTruthy();
    expect(screen.getByRole("status", { name: "Presença em sessões: em breve" })).toBeTruthy();
    expect(screen.queryByRole("link", { name: /despesas/i })).toBeNull();
  });

  it("falha do catálogo (Casa inexistente ou rede) vira aviso, sem lista vazia fingindo dado", async () => {
    mockFetch({ ok: false });
    render(<SecaoDadosAbertos ente="casa-x" />);
    expect(await screen.findByRole("alert")).toBeTruthy();
    expect(screen.queryByRole("list", { name: "Conjuntos de dados" })).toBeNull();
  });
});
