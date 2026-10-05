import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { AcessosConcedidos } from "./acessos-concedidos";

// "Quem tem acesso" (ADR-0005, adendo "Revogar acesso"): a lista do que o administrador concedeu, com o botão de revogar
// ao lado de cada acesso, motivo obrigatório, o estado revogado visível e o caminho de dar o acesso de novo.

type Chamada = { metodo: string; url: string; corpo: unknown };

const rui = { "identidade-id": "i1", nome: "Rui Vereador", papel: "vereador", "concedido-em": "2026-08-01T12:00:00Z",
  "revogado-em": null, "revogado-por-nome": null, motivo: null };
const lia = { "identidade-id": "i2", nome: "Lia Controle", papel: "auditor", "concedido-em": "2026-08-02T12:00:00Z",
  "revogado-em": null, "revogado-por-nome": null, motivo: null };
const mauro = { "identidade-id": "i3", nome: "Mauro Antigo", papel: "vereador", "concedido-em": "2026-07-01T12:00:00Z",
  "revogado-em": "2026-09-15T15:00:00Z", "revogado-por-nome": "Ana Moreira", motivo: "Mandato encerrado em 31/12" };
const dora = { "identidade-id": "i4", nome: "Dora Jurídica", papel: "juridico", "concedido-em": "2026-07-01T12:00:00Z",
  "revogado-em": "2026-09-20T15:00:00Z", "revogado-por-nome": "Ana Moreira", motivo: "Contrato acabou" };

function mockar(acessos: unknown[], respostas: Record<string, { status: number; corpo?: unknown }> = {}) {
  const chamadas: Chamada[] = [];
  global.fetch = vi.fn(async (url: string, init?: RequestInit) => {
    const c = { metodo: init?.method ?? "GET", url: String(url), corpo: init?.body ? JSON.parse(String(init.body)) : undefined };
    chamadas.push(c);
    const r = respostas[`${c.metodo} ${c.url}`];
    if (r) return { ok: r.status < 300, status: r.status, json: async () => r.corpo ?? {} } as Response;
    if (c.metodo === "GET" && c.url === "/api/identidade/acessos") {
      return { ok: true, status: 200, json: async () => ({ acessos }) } as Response;
    }
    return { ok: false, status: 404, json: async () => ({}) } as Response;
  }) as unknown as typeof fetch;
  return chamadas;
}

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe("Quem tem acesso", () => {
  it("lista os acessos (ativos primeiro) e mostra o revogado com a data, quem revogou e o motivo", async () => {
    mockar([mauro, rui, lia]);
    render(<AcessosConcedidos token="tok" />);
    const lista = await screen.findByRole("list", { name: "Acessos concedidos" });
    const itens = within(lista).getAllByRole("listitem");
    expect(itens).toHaveLength(3);
    expect(within(itens[0]).getByText("Lia Controle")).toBeTruthy();
    expect(within(itens[0]).getByText(/Controle interno · Acesso ativo desde/)).toBeTruthy();
    expect(within(itens[1]).getByText("Rui Vereador")).toBeTruthy();
    expect(within(itens[2]).getByText(/Vereador\(a\) · Revogado em .* por Ana Moreira/)).toBeTruthy();
    expect(within(itens[2]).getByText("Motivo: Mandato encerrado em 31/12")).toBeTruthy();
    expect(within(itens[2]).queryByRole("button", { name: /revogar acesso/i })).toBeNull();
    expect(screen.getByRole("button", { name: "Revogar acesso de Rui Vereador (Vereador(a))" })).toBeTruthy();
  });

  it("revogar pede o motivo: sem ele não envia nada", async () => {
    const chamadas = mockar([rui]);
    render(<AcessosConcedidos token="tok" />);
    fireEvent.click(await screen.findByRole("button", { name: /Revogar acesso de Rui Vereador/ }));
    const form = await screen.findByRole("form", { name: "Revogar acesso de Rui Vereador" });
    expect(within(form).getByText(/perde o acesso de vereador\(a\) na próxima ação/)).toBeTruthy();
    fireEvent.change(within(form).getByLabelText(/Motivo da revogação/), { target: { value: "  " } });
    fireEvent.submit(form);
    expect(await within(form).findByText("Escreva o motivo da revogação.")).toBeTruthy();
    expect(chamadas.some((c) => c.metodo === "POST")).toBe(false);
  });

  it("confirma a revogação com papel e motivo, avisa o efeito e recarrega a lista", async () => {
    const chamadas = mockar([rui], { "POST /api/identidade/acessos/i1/revogacao": { status: 200, corpo: { revogado: true, "vinculo-encerrado": true } } });
    render(<AcessosConcedidos token="tok" />);
    fireEvent.click(await screen.findByRole("button", { name: /Revogar acesso de Rui Vereador/ }));
    const form = await screen.findByRole("form", { name: "Revogar acesso de Rui Vereador" });
    fireEvent.change(within(form).getByLabelText(/Motivo da revogação/), { target: { value: " Renunciou ao mandato " } });
    fireEvent.submit(form);
    const aviso = await screen.findByRole("status");
    expect(aviso.textContent).toMatch(/Acesso de Rui Vereador \(Vereador\(a\)\) revogado\. A pessoa já não consegue entrar no sistema\./);
    const post = chamadas.find((c) => c.metodo === "POST")!;
    expect(post.url).toBe("/api/identidade/acessos/i1/revogacao");
    expect(post.corpo).toEqual({ papel: "vereador", motivo: "Renunciou ao mandato" });
    await waitFor(() => expect(chamadas.filter((c) => c.metodo === "GET" && c.url === "/api/identidade/acessos")).toHaveLength(2));
  });

  it("quando a pessoa mantém outro acesso, diz isso", async () => {
    mockar([lia], { "POST /api/identidade/acessos/i2/revogacao": { status: 200, corpo: { revogado: true, "vinculo-encerrado": false } } });
    render(<AcessosConcedidos token="tok" />);
    fireEvent.click(await screen.findByRole("button", { name: /Revogar acesso de Lia Controle/ }));
    const form = await screen.findByRole("form", { name: "Revogar acesso de Lia Controle" });
    fireEvent.change(within(form).getByLabelText(/Motivo da revogação/), { target: { value: "Saiu do controle interno" } });
    fireEvent.submit(form);
    expect((await screen.findByRole("status")).textContent).toMatch(/A pessoa mantém os outros acessos que tinha\./);
  });

  it("cancelar fecha o painel sem enviar", async () => {
    const chamadas = mockar([rui]);
    render(<AcessosConcedidos token="tok" />);
    fireEvent.click(await screen.findByRole("button", { name: /Revogar acesso de Rui Vereador/ }));
    fireEvent.click(await screen.findByRole("button", { name: "Cancelar" }));
    expect(screen.queryByRole("form")).toBeNull();
    expect(chamadas.some((c) => c.metodo === "POST")).toBe(false);
  });

  it("acesso que já não está ativo (404) vira frase para o servidor, não código", async () => {
    mockar([rui], { "POST /api/identidade/acessos/i1/revogacao": { status: 404, corpo: { erro: "acesso nao encontrado" } } });
    render(<AcessosConcedidos token="tok" />);
    fireEvent.click(await screen.findByRole("button", { name: /Revogar acesso de Rui Vereador/ }));
    const form = await screen.findByRole("form", { name: "Revogar acesso de Rui Vereador" });
    fireEvent.change(within(form).getByLabelText(/Motivo da revogação/), { target: { value: "Saiu" } });
    fireEvent.submit(form);
    expect((await screen.findByRole("alert")).textContent).toMatch(/Esse acesso já não está ativo/);
  });

  it("dá o acesso de novo ao vereador revogado: e-mail do convite e o mesmo POST de sempre", async () => {
    const chamadas = mockar([mauro], { "POST /api/identidade/acessos": { status: 201, corpo: { "vinculo-id": "v", convite: "enviado" } } });
    render(<AcessosConcedidos token="tok" />);
    fireEvent.click(await screen.findByRole("button", { name: /Dar o acesso de novo a Mauro Antigo/ }));
    const form = await screen.findByRole("form", { name: "Dar o acesso de novo a Mauro Antigo" });
    fireEvent.change(within(form).getByLabelText(/E-mail institucional/), { target: { value: "mauro@camara.local" } });
    fireEvent.submit(form);
    expect((await screen.findByRole("status")).textContent).toMatch(/concedido de novo\. O convite foi enviado/);
    expect(chamadas.find((c) => c.metodo === "POST")!.corpo).toEqual({
      "identidade-id": "i3", tipo: "vereador", papeis: ["vereador"], email: "mauro@camara.local",
    });
  });

  it("jurídico revogado não tem botão aqui: aponta o formulário que confirma qualificação e OAB", async () => {
    mockar([dora]);
    render(<AcessosConcedidos token="tok" />);
    expect(await screen.findByText(/use “Dar acesso ao jurídico” mais abaixo/)).toBeTruthy();
    expect(screen.queryByRole("button", { name: /de novo/i })).toBeNull();
  });

  it("se a lista não carregar, diz isso — nunca uma lista vazia fingindo que ninguém tem acesso", async () => {
    global.fetch = vi.fn(async () => ({ ok: false, status: 500, json: async () => ({}) }) as Response) as unknown as typeof fetch;
    render(<AcessosConcedidos token="tok" />);
    expect(await screen.findByText(/Não foi possível carregar os acessos/)).toBeTruthy();
    expect(screen.queryByText(/Nenhum acesso concedido ainda/)).toBeNull();
  });

  it("sem acesso concedido, diz isso", async () => {
    mockar([]);
    render(<AcessosConcedidos token="tok" />);
    expect(await screen.findByText("Nenhum acesso concedido ainda.")).toBeTruthy();
  });
});
