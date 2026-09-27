import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { AuthProvider } from "@/lib/auth";
import { TemaProvider } from "@/lib/tema";

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
  usePathname: () => "/paineis/ia",
  useSearchParams: () => new URLSearchParams(),
}));

import PaginaIaDaCasa from "./page";

const ADMIN = '{"sub":"u","papeis":["secretario","admin_ente"]}';
const SECRETARIA = '{"sub":"u","papeis":["secretario"]}';

const painel = (extra: Record<string, unknown> = {}) => ({
  mes: "2026-09", "consumo-disponivel": true, estado: "aviso", gasto: "85.5", moeda: "USD", parcial: false, execucoes: 3,
  orcamento: { mensal: "100.0000", "teto-duro": "120.0000", moeda: "USD", "definido-em": "2026-09-01T00:00:00Z" },
  "por-capacidade": [
    { operacao: "ata.redigir", execucoes: 2, indisponiveis: 0, custo: "85.5", aprovados: 1, editados: 1, descartados: 0,
      "erros-reportados": 1 },
    { operacao: "conferencia.redigir", execucoes: 1, indisponiveis: 1, custo: "0", aprovados: 0, editados: 0,
      descartados: 0, "erros-reportados": 0 },
  ],
  "notas-tecnicas": { pendentes: 1, aproveitadas: 2, descartadas: 0 },
  propostas: { aguardando: 0, confirmadas: 3, recusadas: 1, expiradas: 0 },
  ...extra,
});

function mockar(corpos: unknown[]) {
  const fila = [...corpos];
  const f = vi.fn(async (url: string) => {
    if (String(url).includes("/meu/identidade")) return { ok: true, status: 200, json: async () => ({ nome: "A", papeis: ["admin_ente"] }) } as Response;
    const corpo = fila.length > 1 ? fila.shift() : fila[0];
    return { ok: true, status: 200, json: async () => corpo } as Response;
  });
  global.fetch = f as unknown as typeof fetch;
  return f;
}

function montar(token: string) {
  return render(
    <AuthProvider tokenQuery={token}>
      <TemaProvider>
        <PaginaIaDaCasa />
      </TemaProvider>
    </AuthProvider>,
  );
}

describe("IA da Casa", () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it("consumo × orçamento, o que cada capacidade fez e os desfechos", async () => {
    mockar([painel()]);
    montar(ADMIN);
    expect(await screen.findByRole("heading", { name: "Passou de 80% do orçamento" })).toBeTruthy();
    expect(screen.getByText(/US\$\s?85,50/, { selector: "b" })).toBeTruthy();
    expect(screen.getByRole("img", { name: "86% do orçamento do mês usado" })).toBeTruthy();
    expect(screen.getByText("Rascunho da ata")).toBeTruthy();
    expect(screen.getByText("100% aproveitado (1 aprovado como veio, 1 editado)")).toBeTruthy();
    expect(screen.getByText("1 erro reportado")).toBeTruthy();
    expect(screen.getByText(/1 não rodou · a IA faz sozinha/)).toBeTruthy();
    expect(screen.getByText("2 aproveitadas · 0 descartadas · 1 na fila")).toBeTruthy();
    expect(screen.getByText("3 confirmadas · 1 recusada · 0 expiraram · 0 esperando")).toBeTruthy();
  });

  it("IA fora: diz que o consumo não está disponível, sem inventar gasto", async () => {
    mockar([painel({ "consumo-disponivel": false, estado: null, gasto: null, "por-capacidade": [] })]);
    montar(ADMIN);
    expect(await screen.findByRole("heading", { name: "Consumo indisponível agora" })).toBeTruthy();
    expect(screen.queryByRole("img", { name: /do orçamento do mês usado/ })).toBeNull();
    expect(screen.getByText("Aparece quando a IA voltar.")).toBeTruthy();
  });

  it("mês anterior pede o mês certo", async () => {
    const f = mockar([painel(), painel({ mes: "2026-08", estado: "normal", gasto: "10" })]);
    montar(ADMIN);
    fireEvent.click(await screen.findByRole("button", { name: "Mês anterior" }));
    await waitFor(() => expect(screen.getByText("agosto de 2026")).toBeTruthy());
    expect(f.mock.calls.some(([u]) => String(u).endsWith("/api/paineis/ia?mes=2026-08"))).toBe(true);
  });

  it("é só do administrador da Casa", async () => {
    mockar([painel()]);
    montar(SECRETARIA);
    expect(await screen.findByText("O painel da IA é do administrador da Casa.")).toBeTruthy();
  });
});
