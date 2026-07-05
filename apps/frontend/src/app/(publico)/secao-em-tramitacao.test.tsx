import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { SecaoEmTramitacao } from "./secao-em-tramitacao";

// Task 1.3 (Fatia A2.1, Portal do Cidadão) — wrapper CLIENTE que busca /materias (useMaterias, 1.3) e
// escolhe destaque/mais-tramitação (materia-vista, 1.1). Arquivo NOVO fora do file-list literal do plano
// (que previa o fetch em page.tsx/Server Component) — ver DESVIO documentado em use-materias.ts. Cobre
// os 3 estados + vazio: cada um degrada SÓ esta seção, nunca derruba a página (Global Constraints).

const materiaFake = {
  "proposicao-id": "abc-123",
  tipo: "projeto_lei",
  ano: 2026,
  sequencial: 42,
  "urn-lex": "urn:lex:x",
  ementa: "Cria o Programa Municipal de Hortas Comunitárias.",
  "autor-texto": "Ver.ª Helena Matos",
  estado: "segundo_turno",
};

describe("SecaoEmTramitacao", () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it("carregando -> não quebra, não mostra dado", () => {
    global.fetch = vi.fn(() => new Promise(() => {})) as unknown as typeof fetch;
    render(<SecaoEmTramitacao ente="fortaleza" />);
    expect(screen.getByRole("heading", { name: "Em tramitação agora" })).toBeTruthy();
  });

  it("fetch falho -> estado honesto em-breve, nunca 500 global", async () => {
    global.fetch = vi.fn(async () => ({ ok: false, status: 500 })) as unknown as typeof fetch;
    render(<SecaoEmTramitacao ente="fortaleza" />);
    await waitFor(() => expect(screen.getByRole("status")).toBeTruthy());
    expect(screen.getByRole("status").textContent).toMatch(/em breve/i);
  });

  it("lista vazia -> estado honesto 'nenhuma matéria em tramitação'", async () => {
    global.fetch = vi.fn(async () => ({ ok: true, json: async () => [] })) as unknown as typeof fetch;
    render(<SecaoEmTramitacao ente="fortaleza" />);
    await waitFor(() => expect(screen.getByRole("status")).toBeTruthy());
    expect(screen.getByRole("status").textContent).toMatch(/nenhuma matéria/i);
  });

  it("dado real -> renderiza o destaque (1º item)", async () => {
    global.fetch = vi.fn(async () => ({ ok: true, json: async () => [materiaFake] })) as unknown as typeof fetch;
    render(<SecaoEmTramitacao ente="fortaleza" />);
    await waitFor(() => expect(screen.getByText("PL 042/2026")).toBeTruthy());
    expect(
      screen.getByText("Cria o Programa Municipal de Hortas Comunitárias.").textContent,
    ).toBe("Cria o Programa Municipal de Hortas Comunitárias.");
  });
});
