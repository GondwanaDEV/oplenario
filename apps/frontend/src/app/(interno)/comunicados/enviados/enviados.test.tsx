import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";

// Os enviados (ADR-0020): "lidos x de y", os cientes, os pendentes vencidos em destaque, e a alternância "Meus / Da
// Casa" só para a secretaria e a administração.

const auth = vi.hoisted(() => ({ papeis: ["secretario"] as string[] }));
vi.mock("@/lib/auth", () => ({ useAuth: () => ({ token: "tok" }), usePapeis: () => ({ papeis: auth.papeis, estado: "pronto" }) }));
vi.mock("../../topo", () => ({ TopoInterno: ({ area }: { area: string }) => <div data-testid="topo">{area}</div> }));

import PaginaEnviados from "./page";

const item = (id: string, extra: Record<string, unknown> = {}) => ({
  id, protocolo: `COM-2026-00001${id.slice(-1)}`, assunto: `Assunto ${id}`, "enviado-em": "2026-10-02T13:00:00Z", "remetente-nome": "Rita Campos",
  "exige-ciencia": false, "ciencia-ate": null, destinatarios: 15, recebidos: 14, lidos: 12, cientes: 0, "pendentes-vencidos": 0, ...extra,
});

function mockar(rotas: Record<string, { status?: number; corpo?: unknown }>) {
  const chamadas: string[] = [];
  global.fetch = vi.fn(async (url: string) => {
    chamadas.push(String(url));
    const r = rotas[String(url)];
    if (!r) return { ok: false, status: 404, json: async () => ({}) } as Response;
    const status = r.status ?? 200;
    return { ok: status < 300, status, json: async () => r.corpo ?? {} } as Response;
  }) as unknown as typeof fetch;
  return chamadas;
}

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  auth.papeis = ["secretario"];
});

describe("enviados", () => {
  it("cada comunicado com lidos x de y, cientes e os vencidos em destaque", async () => {
    mockar({
      "/api/meu/comunicados/enviados": {
        corpo: { itens: [item("c1", { "exige-ciencia": true, cientes: 9, "pendentes-vencidos": 2 }), item("c2")] },
      },
    });
    render(<PaginaEnviados />);
    const lista = await screen.findByRole("list", { name: "Comunicados enviados" });
    const [um, dois] = within(lista).getAllByRole("listitem");
    expect(within(um).getByText("12 de 15 leram")).toBeTruthy();
    expect(within(um).getByText("9 de 15 cientes")).toBeTruthy();
    expect(within(um).getByText("2 pendentes vencidos")).toBeTruthy();
    expect(um.className).toContain("com-enviado-vencido");
    expect(within(dois).queryByText(/cientes/)).toBeNull();
    expect(within(um).getByRole("link", { name: "Assunto c1" }).getAttribute("href")).toBe("/comunicados/c1?de=enviados&token=tok");
  });

  it("secretaria alterna para 'Da Casa' (?escopo=casa) e vê quem enviou", async () => {
    const chamadas = mockar({
      "/api/meu/comunicados/enviados": { corpo: { itens: [] } },
      "/api/meu/comunicados/enviados?escopo=casa": { corpo: { itens: [item("c3", { "remetente-nome": "Sérgio Lopes" })] } },
    });
    render(<PaginaEnviados />);
    expect(await screen.findByText(/Você ainda não enviou comunicados/)).toBeTruthy();
    const grupo = screen.getByRole("group", { name: "De quem" });
    fireEvent.click(within(grupo).getByRole("button", { name: "Da Casa" }));
    expect(await screen.findByText("Enviado por Sérgio Lopes")).toBeTruthy();
    expect(within(grupo).getByRole("button", { name: "Da Casa" }).getAttribute("aria-pressed")).toBe("true");
    expect(chamadas).toContain("/api/meu/comunicados/enviados?escopo=casa");
  });

  it("o vereador não vê a alternância (só os dele)", async () => {
    auth.papeis = ["vereador"];
    const chamadas = mockar({ "/api/meu/comunicados/enviados": { corpo: { itens: [item("c1")] } } });
    render(<PaginaEnviados />);
    await screen.findByText("Assunto c1");
    expect(screen.queryByRole("group", { name: "De quem" })).toBeNull();
    expect(chamadas.every((u) => !u.includes("escopo"))).toBe(true);
  });

  it("falha vira frase com 'Tentar de novo'", async () => {
    const chamadas = mockar({ "/api/meu/comunicados/enviados": { status: 500 } });
    render(<PaginaEnviados />);
    const alerta = await screen.findByRole("alert");
    fireEvent.click(within(alerta).getByRole("button", { name: "Tentar de novo" }));
    await waitFor(() => expect(chamadas.filter((u) => u === "/api/meu/comunicados/enviados")).toHaveLength(2));
  });
});
