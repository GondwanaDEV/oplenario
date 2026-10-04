import { afterEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { CATEGORIAS_REPORTE, ReportarErroIa } from "./reportar-erro-ia";

const EID = "5b0c1c9e-2f4e-4d7a-9d43-0f6f3c2a7e11";

function mockar(status: number, corpo: unknown = {}) {
  const f = vi.fn(async () => ({ ok: status < 300, status, json: async () => corpo }) as Response);
  global.fetch = f as unknown as typeof fetch;
  return f;
}

async function reportar(rotulo: RegExp) {
  fireEvent.click(screen.getByRole("button", { name: /reportar erro/i }));
  fireEvent.click(screen.getByRole("radio", { name: rotulo }));
  await act(async () => {
    fireEvent.click(screen.getByRole("button", { name: /^enviar$/i }));
  });
}

describe("ReportarErroIa (feature 8.4)", () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it("o vocabulário é o do registro da IA — sem texto livre", () => {
    expect(CATEGORIAS_REPORTE.map((c) => c.valor)).toEqual(["fato_errado", "citacao_errada", "omissao", "linguagem", "outro"]);
  });

  it("discreto até o clique: só o botão", () => {
    render(<ReportarErroIa execucaoId={EID} token="tok" />);
    expect(screen.getByRole("button", { name: /reportar erro/i })).toBeTruthy();
    expect(screen.queryByRole("radio")).toBeNull();
  });

  it("escolhe o que está errado, envia ao core e agradece", async () => {
    const f = mockar(200, { reportado: true });
    render(<ReportarErroIa execucaoId={EID} token="tok" />);
    await reportar(/a fonte não diz isso/i);
    await waitFor(() => expect(screen.getByRole("status").textContent).toBe("Obrigado — isso entra na revisão da IA."));
    const [url, init] = f.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe(`/api/ia/execucoes/${EID}/reportes`);
    expect(init.method).toBe("POST");
    expect(JSON.parse(String(init.body))).toEqual({ categoria: "citacao_errada" });
    expect(new Headers(init.headers).get("Authorization")).toBe("Bearer tok");
  });

  it("sem escolha não envia", () => {
    const f = mockar(200);
    render(<ReportarErroIa execucaoId={EID} token="tok" />);
    fireEvent.click(screen.getByRole("button", { name: /reportar erro/i }));
    expect((screen.getByRole("button", { name: /^enviar$/i }) as HTMLButtonElement).disabled).toBe(true);
    expect(f).not.toHaveBeenCalled();
  });

  it("cancelar fecha sem enviar", () => {
    const f = mockar(200);
    render(<ReportarErroIa execucaoId={EID} token="tok" />);
    fireEvent.click(screen.getByRole("button", { name: /reportar erro/i }));
    fireEvent.click(screen.getByRole("button", { name: /cancelar/i }));
    expect(screen.queryByRole("radio")).toBeNull();
    expect(f).not.toHaveBeenCalled();
  });

  it("IA fora (503): mensagem honesta e dá para tentar de novo", async () => {
    mockar(503, { erro: "x" });
    render(<ReportarErroIa execucaoId={EID} token="tok" />);
    await reportar(/informação errada/i);
    expect((await screen.findByRole("alert")).textContent).toMatch(/Tente de novo em instantes/);
    expect(screen.getByRole("button", { name: /^enviar$/i })).toBeTruthy();
  });

  it("rede caída não quebra a tela", async () => {
    global.fetch = vi.fn(async () => {
      throw new Error("rede");
    }) as unknown as typeof fetch;
    render(<ReportarErroIa execucaoId={EID} token="tok" />);
    await reportar(/outro problema/i);
    expect((await screen.findByRole("alert")).textContent).toMatch(/Tente de novo/);
  });
});
