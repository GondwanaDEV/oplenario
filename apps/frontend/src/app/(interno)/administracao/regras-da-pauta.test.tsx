import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { RegrasDaPauta } from "./regras-da-pauta";

// ADR-0019 fatia 3: o bloco "Regras da pauta" do admin_ente — o padrão dito como padrão, as quatro opções, a
// antecedência (vazio = sem) e o PUT com as chaves do contrato.

type Chamada = { metodo: string; url: string; body: unknown };

function mockar(get: unknown, put?: { status: number; corpo: unknown }) {
  const chamadas: Chamada[] = [];
  global.fetch = vi.fn(async (url: string, init?: RequestInit) => {
    const c = { metodo: init?.method ?? "GET", url: String(url), body: init?.body ? JSON.parse(String(init.body)) : undefined };
    chamadas.push(c);
    if (c.url !== "/api/regra-da-pauta") return { ok: false, status: 404, json: async () => ({}) } as Response;
    if (c.metodo === "PUT" && put) return { ok: put.status < 300, status: put.status, json: async () => put.corpo } as Response;
    return { ok: true, status: 200, json: async () => get } as Response;
  }) as unknown as typeof fetch;
  return chamadas;
}

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe("RegrasDaPauta", () => {
  it("sem configuração, diz que vale o padrão; salva quem publica e a antecedência", async () => {
    const chamadas = mockar(
      { "quem-publica": "secretaria", "antecedencia-minima-horas": null, configurada: false },
      { status: 200, corpo: { "quem-publica": "presidente", "antecedencia-minima-horas": 48, configurada: true } },
    );
    render(<RegrasDaPauta token="tk" />);
    expect(await screen.findByText(/vale o padrão, a secretaria publica/)).toBeTruthy();
    expect((screen.getByRole("radio", { name: /A secretaria legislativa/ }) as HTMLInputElement).checked).toBe(true);
    fireEvent.click(screen.getByRole("radio", { name: /O Presidente da Câmara/ }));
    fireEvent.change(screen.getByLabelText(/Antecedência mínima/), { target: { value: "48" } });
    fireEvent.click(screen.getByRole("button", { name: "Salvar regras da pauta" }));
    expect(await screen.findByText("Regra salva: publica o presidente da câmara.")).toBeTruthy();
    expect(chamadas.find((c) => c.metodo === "PUT")?.body).toEqual({ "quem-publica": "presidente", "antecedencia-minima-horas": 48 });
  });

  it("antecedência inválida não vai ao servidor; recusa do servidor vira frase", async () => {
    const chamadas = mockar(
      { "quem-publica": "mesa", "antecedencia-minima-horas": 24, configurada: true },
      { status: 403, corpo: { erro: "autorizacao negada" } },
    );
    render(<RegrasDaPauta token="tk" />);
    const campo = (await screen.findByLabelText(/Antecedência mínima/)) as HTMLInputElement;
    expect(campo.value).toBe("24");
    fireEvent.change(campo, { target: { value: "0" } });
    fireEvent.click(screen.getByRole("button", { name: "Salvar regras da pauta" }));
    expect((await screen.findByRole("alert")).textContent).toMatch(/de 1 a 720/);
    expect(chamadas.filter((c) => c.metodo === "PUT")).toHaveLength(0);
    fireEvent.change(campo, { target: { value: "" } });
    fireEvent.click(screen.getByRole("button", { name: "Salvar regras da pauta" }));
    // a recusa do servidor chega depois: `findByRole("alert")` devolvia na hora o alerta ANTERIOR (o da validação
    // local, ainda na tela) e a asserção do texto dependia de a resposta já ter sido pintada. Espera-se o texto.
    await waitFor(() =>
      expect(screen.getByRole("alert").textContent).toBe("Só o administrador da Casa muda a regra da pauta."),
    );
    expect(chamadas.find((c) => c.metodo === "PUT")?.body).toEqual({ "quem-publica": "mesa", "antecedencia-minima-horas": null });
  });
});
