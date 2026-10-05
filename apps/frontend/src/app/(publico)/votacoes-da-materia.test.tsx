import { afterEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, render, screen, waitFor } from "@testing-library/react";
import type { VotacaoPublicaOut } from "@/lib/contrato-portal.gen";
import { UltimaVotacaoEmPlenario, VotacoesDaMateria } from "./votacoes-da-materia";

// "Votações desta matéria" na ficha pública: só existe quando o servidor diz que há votação pública encerrada dela.

const ENTE = "10000000-0000-0000-0000-000000000001";
const MATERIA = "30000000-0000-0000-0000-000000000001";
const URL_VOTACOES = `/api/portal/casa/${ENTE}/votacoes?materia=${MATERIA}`;

function mockar(resposta: { status?: number; corpo?: unknown } | "rede") {
  const fn = vi.fn(async () => {
    if (resposta === "rede") throw new Error("rede fora");
    const status = resposta.status ?? 200;
    return { ok: status < 400, status, json: async () => resposta.corpo ?? {} } as Response;
  });
  global.fetch = fn as unknown as typeof fetch;
  return fn;
}

const votacao = { "votacao-id": "v1" };

// deixa a busca terminar e o estado assentar: sem isto, "não apareceu" valeria também para "ainda não respondeu"
async function assentar(fn: ReturnType<typeof vi.fn>) {
  await waitFor(() => expect(fn).toHaveBeenCalled());
  await act(async () => {
    await new Promise((r) => setTimeout(r, 30));
  });
}

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe("VotacoesDaMateria", () => {
  it("com votação encerrada: pergunta ao servidor só por esta matéria e liga à lista filtrada", async () => {
    const fn = mockar({ corpo: { votacoes: [votacao], total: 1, pagina: 1, "por-pagina": 20 } });
    render(<VotacoesDaMateria ente={ENTE} proposicaoId={MATERIA} />);
    const link = await screen.findByRole("link", { name: "Ver as votações desta matéria" });
    expect(link.getAttribute("href")).toBe(`/portal/casa/${ENTE}/votacoes?materia=${MATERIA}`);
    expect(fn).toHaveBeenCalledWith(URL_VOTACOES, { cache: "no-store" });
    expect(link.className).toContain("btn");
    expect(screen.getByText(/votou esta matéria em sessão pública 1 vez/)).toBeTruthy();
  });

  it("a contagem é a do servidor (total), não a do que veio na página", async () => {
    mockar({ corpo: { votacoes: [votacao], total: 3, pagina: 1, "por-pagina": 20 } });
    render(<VotacoesDaMateria ente={ENTE} proposicaoId={MATERIA} />);
    expect(await screen.findByText(/votou esta matéria em sessão pública 3 vezes/)).toBeTruthy();
  });

  it("sem votação pública (total 0): a seção não existe — nunca um link para uma lista vazia", async () => {
    const fn = mockar({ corpo: { votacoes: [], total: 0, pagina: 1, "por-pagina": 20 } });
    const { container } = render(<VotacoesDaMateria ente={ENTE} proposicaoId={MATERIA} />);
    await assentar(fn);
    expect(container.querySelector("section")).toBeNull();
    expect(screen.queryByRole("link")).toBeNull();
  });

  it("falha do servidor, rede fora ou resposta estranha: a seção some, sem alerta nem quebra", async () => {
    for (const resposta of [{ status: 500 }, "rede", { corpo: { qualquer: "coisa" } }] as const) {
      cleanup();
      const fn = mockar(resposta);
      const { container } = render(<VotacoesDaMateria ente={ENTE} proposicaoId={MATERIA} />);
      await assentar(fn);
      expect(container.querySelector("section")).toBeNull();
      expect(screen.queryByRole("alert")).toBeNull();
    }
  });

  it("trocar de matéria esconde o resultado da anterior até a nova responder", async () => {
    mockar({ corpo: { votacoes: [votacao], total: 1, pagina: 1, "por-pagina": 20 } });
    const { rerender } = render(<VotacoesDaMateria ente={ENTE} proposicaoId={MATERIA} />);
    await screen.findByRole("link", { name: "Ver as votações desta matéria" });
    mockar({ corpo: { votacoes: [], total: 0, pagina: 1, "por-pagina": 20 } });
    rerender(<VotacoesDaMateria ente={ENTE} proposicaoId="30000000-0000-0000-0000-000000000002" />);
    await waitFor(() => expect(screen.queryByRole("link", { name: "Ver as votações desta matéria" })).toBeNull());
  });
});

describe("UltimaVotacaoEmPlenario", () => {
  const base: VotacaoPublicaOut = {
    votacaoId: "v1",
    encerradaEm: "2026-10-05T15:00:00Z",
    sessao: { sessaoId: "s1", tipoSessao: "ordinaria", numeroSequencial: 3 },
    objetoTipo: "proposicao",
    modalidade: "nominal",
    quorumTipo: "maioria_qualificada_2_3",
    resultado: "aprovada",
  };

  it("emenda à Lei Orgânica aprovada só no 1º turno: a frase diz o turno, não que a matéria foi aprovada", () => {
    render(<UltimaVotacaoEmPlenario ente={ENTE} ultima={{ ...base, turno: 1 }} />);
    expect(screen.getByRole("status").textContent).toContain("a matéria foi aprovada em 1º turno em 05/10/2026");
  });

  it("matéria de um turno: a frase de sempre", () => {
    render(<UltimaVotacaoEmPlenario ente={ENTE} ultima={base} />);
    expect(screen.getByRole("status").textContent).toContain("a matéria foi aprovada em 05/10/2026");
  });
});
