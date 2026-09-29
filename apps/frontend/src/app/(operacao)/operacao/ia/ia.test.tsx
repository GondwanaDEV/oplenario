import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";

vi.mock("next/navigation", () => ({ usePathname: () => "/operacao/ia" }));
vi.mock("@/lib/auth", () => ({ useAuth: () => ({ token: null }) }));

import ObservabilidadeDaIA from "./page";
import { NavOperacao } from "../../topo-operacao";

function json(corpo: unknown, status = 200) {
  return new Response(JSON.stringify(corpo), { status, headers: { "content-type": "application/json" } });
}

const AG = { execucoes: 8, indisponiveis: 2, "latencia-p50-ms": 300, "latencia-p95-ms": 1900, custo: "6.5", parcial: true };

const OBS = {
  disponivel: true, horas: 24, desde: "2026-09-28T13:00:00Z", ate: "2026-09-29T13:25:00Z", casas: 3, moeda: "USD",
  total: AG,
  "por-operacao": [{ ...AG, operacao: "ata.redigir", execucoes: 5, indisponiveis: 0, parcial: false }],
  "por-fornecedor": [
    { ...AG, vendor: "anthropic", modelo: "m", execucoes: 6, indisponiveis: 0 },
    { ...AG, vendor: "fake", modelo: null, execucoes: 2, indisponiveis: 2 },
  ],
  "motivos-indisponivel": [{ motivo: "fornecedor_fora", execucoes: 2 }],
  "por-hora": [
    { inicio: "2026-09-28T13:00:00Z", execucoes: 5, indisponiveis: 0 },
    { inicio: "2026-09-28T14:00:00Z", execucoes: 3, indisponiveis: 2 },
  ],
};

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe("Observabilidade da IA", () => {
  it("mostra as métricas, os recursos, os fornecedores e por que não rodaram", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(json(OBS)));
    render(<ObservabilidadeDaIA />);
    const p95 = (await screen.findByText("Tempo de resposta p95")).parentElement!;
    expect(p95.textContent).toMatch(/1,9 s/);
    expect(p95.textContent).toMatch(/mediana 300 ms/);
    expect(screen.getByText("Não rodaram").parentElement!.textContent).toMatch(/25%.*2 de 8/);
    expect(screen.getByText("Câmaras que usaram").parentElement!.textContent).toMatch(/3/);
    expect(screen.getByText("parcial: modelo sem preço")).toBeTruthy();
    expect(screen.getByText("Rascunho da ata")).toBeTruthy();
    const fake = screen.getByText("fake").closest(".op-prov")! as HTMLElement;
    expect(within(fake).getByText("2 não rodaram")).toBeTruthy();
    expect(screen.getByText("Fornecedor fora do ar")).toBeTruthy();
    expect(screen.getByRole("list", { name: /Execuções por hora/ }).querySelectorAll("li")).toHaveLength(2);
  });

  it("troca a janela para 7 dias pedindo ?horas=168", async () => {
    const f = vi.fn().mockResolvedValue(json(OBS));
    vi.stubGlobal("fetch", f);
    render(<ObservabilidadeDaIA />);
    await screen.findByText("Rascunho da ata");
    expect(f.mock.calls[0][0]).toBe("/api/operacao/ia?horas=24");
    fireEvent.click(screen.getByRole("button", { name: "7 dias" }));
    await waitFor(() => expect(f.mock.calls.at(-1)![0]).toBe("/api/operacao/ia?horas=168"));
    expect(screen.getByRole("button", { name: "7 dias" }).getAttribute("aria-pressed")).toBe("true");
  });

  it("IA fora: avisa e não inventa número", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(json({
      disponivel: false, horas: 24, desde: null, ate: null, casas: null, moeda: null, total: null,
      "por-operacao": [], "por-fornecedor": [], "motivos-indisponivel": [], "por-hora": [],
    })));
    render(<ObservabilidadeDaIA />);
    expect((await screen.findByRole("status")).textContent).toMatch(/não respondeu agora/);
    expect(screen.getByText("Câmaras que usaram").parentElement!.textContent).toMatch(/—/);
    expect(screen.queryByText("Por recurso")).toBeNull();
  });

  it("janela sem execução diz isso; sessão vencida manda entrar", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(json({
      ...OBS, casas: 0, total: { ...AG, execucoes: 0, indisponiveis: 0, "latencia-p50-ms": null, "latencia-p95-ms": null },
      "por-operacao": [], "por-fornecedor": [], "motivos-indisponivel": [],
    })));
    render(<ObservabilidadeDaIA />);
    expect((await screen.findByRole("status")).textContent).toMatch(/Nenhuma execução da IA nas últimas 24 horas/);
    cleanup();
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(json({}, 401)));
    render(<ObservabilidadeDaIA />);
    expect((await screen.findByRole("alert")).textContent).toMatch(/sessão do console expirou/i);
  });

  it("a navegação do console marca a área atual", () => {
    render(<NavOperacao />);
    expect(screen.getByRole("link", { name: "Observabilidade da IA" }).getAttribute("aria-current")).toBe("page");
    expect(screen.getByRole("link", { name: "Câmaras" }).getAttribute("aria-current")).toBeNull();
  });
});
