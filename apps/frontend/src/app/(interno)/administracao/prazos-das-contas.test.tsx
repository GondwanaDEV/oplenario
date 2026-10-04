import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { PrazosDasContas, lerDias } from "./prazos-das-contas";

// ADR-0021 B3/B4: o bloco "Prazos das contas" do admin_ente — os padrões ditos como padrões a conferir na LOM, os
// limites conferidos antes de enviar e o PUT com as chaves do contrato.

type Chamada = { metodo: string; url: string; body: unknown };

function mockar(get: { status?: number; corpo: unknown }, put?: { status: number; corpo: unknown }) {
  const chamadas: Chamada[] = [];
  global.fetch = vi.fn(async (url: string, init?: RequestInit) => {
    const c = { metodo: init?.method ?? "GET", url: String(url), body: init?.body ? JSON.parse(String(init.body)) : undefined };
    chamadas.push(c);
    if (c.url !== "/api/parametros-de-contas") return { ok: false, status: 404, json: async () => ({}) } as Response;
    const r = c.metodo === "PUT" && put ? put : get;
    const status = r.status ?? 200;
    return { ok: status < 300, status, json: async () => r.corpo } as Response;
  }) as unknown as typeof fetch;
  return chamadas;
}

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe("PrazosDasContas", () => {
  it("sem configuração, diz que valem os padrões a conferir na LOM; salva os dois prazos", async () => {
    const c = mockar(
      { corpo: { "prazo-defesa-dias": 15, "prazo-julgamento-dias": 60, padrao: true } },
      { status: 200, corpo: { "prazo-defesa-dias": 10, "prazo-julgamento-dias": 90, padrao: false } },
    );
    render(<PrazosDasContas token="tk" />);
    expect(await screen.findByText(/valem os padrões, a conferir na Lei Orgânica/)).toBeTruthy();
    expect(screen.getByText(/confira na Lei Orgânica do Município/)).toBeTruthy();
    const defesa = screen.getByLabelText(/Prazo de defesa/) as HTMLInputElement;
    expect(defesa.value).toBe("15");
    fireEvent.change(defesa, { target: { value: "10" } });
    fireEvent.change(screen.getByLabelText(/Prazo para julgar/), { target: { value: "90" } });
    fireEvent.click(screen.getByRole("button", { name: "Salvar prazos das contas" }));
    expect(await screen.findByText("Prazos salvos: defesa em 10 dias, julgamento em 90 dias.")).toBeTruthy();
    expect(c.find((x) => x.metodo === "PUT")?.body).toEqual({ "prazo-defesa-dias": 10, "prazo-julgamento-dias": 90 });
    expect(screen.queryByText(/valem os padrões, a conferir/)).toBeNull();
  });

  it("fora dos limites não vai ao servidor; recusa do servidor vira frase", async () => {
    const c = mockar({ corpo: { "prazo-defesa-dias": 15, "prazo-julgamento-dias": 60 } }, { status: 403, corpo: {} });
    render(<PrazosDasContas token="tk" />);
    const defesa = await screen.findByLabelText(/Prazo de defesa/);
    fireEvent.change(defesa, { target: { value: "0" } });
    fireEvent.click(screen.getByRole("button", { name: "Salvar prazos das contas" }));
    expect((await screen.findByRole("alert")).textContent).toMatch(/de 1 a 120/);
    fireEvent.change(defesa, { target: { value: "15" } });
    fireEvent.change(screen.getByLabelText(/Prazo para julgar/), { target: { value: "400" } });
    fireEvent.click(screen.getByRole("button", { name: "Salvar prazos das contas" }));
    expect((await screen.findByRole("alert")).textContent).toMatch(/de 1 a 365/);
    expect(c.filter((x) => x.metodo === "PUT")).toHaveLength(0);
    fireEvent.change(screen.getByLabelText(/Prazo para julgar/), { target: { value: "60" } });
    fireEvent.click(screen.getByRole("button", { name: "Salvar prazos das contas" }));
    // a recusa do servidor chega depois: `findByRole("alert")` devolvia na hora o alerta ANTERIOR (o da validação
    // local, ainda na tela) e o teste falhava sob carga no CI. Espera-se o texto, não a existência de um alerta.
    await waitFor(() =>
      expect(screen.getByRole("alert").textContent).toBe("Só o administrador da Casa muda os prazos das contas."),
    );
  });

  it("lerDias: inteiro dentro dos limites", () => {
    expect(lerDias(" 15 ", { min: 1, max: 120 })).toBe(15);
    expect(lerDias("1.5", { min: 1, max: 120 })).toBeNull();
    expect(lerDias("", { min: 1, max: 120 })).toBeNull();
  });
});
