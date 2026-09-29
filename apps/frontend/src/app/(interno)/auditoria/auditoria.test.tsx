import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";

vi.mock("@/lib/auth", () => ({ useAuth: () => ({ token: "tk" }) }));
vi.mock("../topo", () => ({ TopoInterno: () => null }));

import PaginaAuditoria from "./page";

function json(corpo: unknown, status = 200) {
  return new Response(JSON.stringify(corpo), { status, headers: { "content-type": "application/json" } });
}

const REG = {
  seq: 2, em: "2026-09-29T15:32:07Z", acao: "legislativo/despachar", classe: "escrita", decisao: "permitido",
  ator: { tipo: "pessoa", nome: "Maria Secretária", papeis: ["secretario"], via: null },
  recurso: { tipo: "proposicao", id: "p1", rotulo: "PL 7/2026" }, campos: ["relator"], canal: "web",
  ip: "189.45.x.x", selo: "a7f39c21ffff", "selo-anterior": "b2e84d70eeee",
};
const NEGADO = { ...REG, seq: 3, classe: "negacao", decisao: "negado", acao: "legislativo/so-secretaria", recurso: null,
  ator: { tipo: "pessoa", nome: "Rui Vereador", papeis: ["vereador"], via: null }, campos: [] };

const DA_CASA = {
  escopo: "casa", total: 2, "total-da-casa": 57, registros: [NEGADO, REG], proximo: null,
  operacao: [{ em: "2026-09-27T12:00:00Z", acao: "casa-provisionada", operador: "Rafaela", selo: "cc11dd22" }],
};

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

function rotas(trilha: unknown, integridade: unknown = { integra: true, total: 57, cabeca: "x", "quebra-em": null, "selos-do-dia": [] }) {
  return vi.fn(async (url: string) => {
    if (url.startsWith("/api/auditoria/integridade")) return json(integridade);
    if (url.startsWith("/api/auditoria/exportar.csv")) return new Response("seq\r\n", { status: 200 });
    if (url.startsWith("/api/auditoria")) return json(trilha);
    return json({}, 404);
  });
}

describe("Trilha de auditoria (/auditoria)", () => {
  it("o auditor vê a Casa inteira: o lacre conferido, os eventos e a Operação à parte", async () => {
    vi.stubGlobal("fetch", rotas(DA_CASA));
    render(<PaginaAuditoria />);
    const lista = await screen.findByRole("list", { name: "Eventos de auditoria" });
    const linhas = within(lista).getAllByRole("listitem");
    expect(linhas).toHaveLength(2);
    expect(within(linhas[0]).getByText("Negado")).toBeTruthy();
    expect(within(linhas[1]).getByText("PL 7/2026")).toBeTruthy();
    expect(within(linhas[1]).getByText("Aprovou")).toBeTruthy();
    expect(within(linhas[1]).getByText("selo a7f3·9c21")).toBeTruthy();
    const lacre = await screen.findByRole("status", { name: "Estado da cadeia de integridade" });
    await waitFor(() => expect(lacre.textContent).toMatch(/Cadeia íntegra/));
    expect(lacre.textContent).toMatch(/57/);
    expect(screen.getByRole("list", { name: "Atuação da Operação" }).textContent).toMatch(/Rafaela/);
    expect(screen.getByRole("button", { name: /Exportar trilha/ })).toBeTruthy();
  });

  it("o detalhe mostra o que o registro tem — os campos, a decisão e o selo encadeado, nunca conteúdo", async () => {
    vi.stubGlobal("fetch", rotas(DA_CASA));
    render(<PaginaAuditoria />);
    fireEvent.click(await screen.findByRole("button", { name: "Ver detalhes do evento nº 2" }));
    const det = screen.getByText("Campos alterados").closest("dl")!;
    expect(det.textContent).toMatch(/relator/);
    expect(det.textContent).toMatch(/encadeado a b2e8·4d70/);
  });

  it("filtrar pede ao servidor, com o vocabulário dele", async () => {
    const f = rotas(DA_CASA);
    vi.stubGlobal("fetch", f);
    render(<PaginaAuditoria />);
    await screen.findByRole("list", { name: "Eventos de auditoria" });
    fireEvent.change(screen.getByLabelText("Ator"), { target: { value: "cidadao" } });
    await waitFor(() => expect(f.mock.calls.some(([u]) => /\/api\/auditoria\?.*ator=cidadao/.test(String(u)))).toBe(true));
  });

  it("quem só vê a própria trilha é avisado do recorte, sem lacre e sem exportar", async () => {
    const f = rotas({ ...DA_CASA, escopo: "propria", "total-da-casa": null, operacao: null, registros: [REG], total: 1 });
    vi.stubGlobal("fetch", f);
    render(<PaginaAuditoria />);
    expect(await screen.findByText(/Você vê a sua própria trilha/)).toBeTruthy();
    expect(screen.queryByRole("status", { name: "Estado da cadeia de integridade" })).toBeNull();
    expect(screen.queryByRole("button", { name: /Exportar/ })).toBeNull();
    expect(f.mock.calls.some(([u]) => String(u).includes("/integridade"))).toBe(false);
  });

  it("cadeia quebrada é dita com o número do registro; sessão vencida manda entrar", async () => {
    vi.stubGlobal("fetch", rotas(DA_CASA, { integra: false, total: 3, cabeca: "x", "quebra-em": 4, "selos-do-dia": [] }));
    render(<PaginaAuditoria />);
    const lacre = await screen.findByRole("status", { name: "Estado da cadeia de integridade" });
    await waitFor(() => expect(lacre.textContent).toMatch(/Cadeia quebrada.*nº 4/));
    cleanup();
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(json({}, 401)));
    render(<PaginaAuditoria />);
    expect((await screen.findByRole("alert")).textContent).toMatch(/sessão expirou/);
  });
});
