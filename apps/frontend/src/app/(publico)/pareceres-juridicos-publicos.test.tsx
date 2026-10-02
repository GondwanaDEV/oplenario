import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import { PareceresJuridicosPublicos } from "./pareceres-juridicos-publicos";
import { SecaoFicha } from "./secao-ficha";

// ADR-0019, Eixo 4: o parecer jurídico vai ao portal SÓ depois da deliberação (a lista vem vazia antes) — e a seção só
// existe quando há o que mostrar. Falha de rede ou resposta estranha: a seção some, a ficha não cai.

const ENTE = "10000000-0000-0000-0000-000000000001";
const URL_PARECERES = `/api/portal/casa/${ENTE}/materias/p1/pareceres-juridicos`;

const parecer = (extra = {}) => ({
  numero: 3, ano: 2026, conclusao: "com_ressalvas", relatorio: "Analisei o projeto.", fundamentacao: "O art. 30 da CF dá competência ao Município.",
  assinatura: { nome: "Lúcia Prado", oab: "CE 12345", qualificacao: "efetivo", em: "2026-09-30T14:05:00" }, ...extra,
});

function mockar(resposta: { status: number; corpo?: unknown } | "rede") {
  const f = vi.fn(async (url: string) => {
    if (String(url) !== URL_PARECERES) return { ok: false, status: 404, json: async () => ({}) } as Response;
    if (resposta === "rede") throw new TypeError("network");
    return { ok: resposta.status < 300, status: resposta.status, json: async () => resposta.corpo } as Response;
  });
  global.fetch = f as unknown as typeof fetch;
  return f;
}

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe("PareceresJuridicosPublicos", () => {
  it("com parecer assinado: mostra conclusão, relatório, fundamentação e a assinatura, e diz que é opinativo", async () => {
    mockar({ status: 200, corpo: { pareceres: [parecer()] } });
    render(<PareceresJuridicosPublicos ente={ENTE} proposicaoId="p1" />);
    const secao = await screen.findByRole("region", { name: "Pareceres jurídicos" });
    expect(within(secao).getByText(/opinativo/)).toBeTruthy();
    expect(within(secao).getByRole("heading", { name: "Parecer jurídico nº 3/2026" })).toBeTruthy();
    expect(within(secao).getByText("Com ressalvas")).toBeTruthy();
    expect(within(secao).getByText("Analisei o projeto.")).toBeTruthy();
    expect(within(secao).getByText("O art. 30 da CF dá competência ao Município.")).toBeTruthy();
    expect(within(secao).getByText("Lúcia Prado")).toBeTruthy();
    expect(within(secao).getByText("OAB/CE 12345 · Procurador(a) efetivo(a)")).toBeTruthy();
    expect(within(secao).getByText("Assinado em 30/09/2026, 14:05")).toBeTruthy();
  });

  it("mostra o carimbo (SHA-256 do texto) com o aviso do stub; sem carimbo, nada é inventado", async () => {
    const hex = "ab12".repeat(16);
    mockar({
      status: 200,
      corpo: {
        pareceres: [
          parecer({ assinatura: { nome: "Lúcia Prado", oab: "CE 12345", qualificacao: "efetivo", em: "2026-09-30T14:05:00", algoritmo: "STUB-ICP-v0", sha256: `sha256:${hex}` } }),
          parecer({ numero: 2, assinatura: { nome: "Lúcia Prado", oab: "CE 12345", qualificacao: "efetivo", em: "2026-09-30T14:05:00", algoritmo: null, sha256: null } }),
        ],
      },
    });
    render(<PareceresJuridicosPublicos ente={ENTE} proposicaoId="p1" />);
    await screen.findByRole("heading", { name: "Parecer jurídico nº 3/2026" });
    expect(screen.getByText(hex)).toBeTruthy();
    expect(screen.getByText(/ainda não é assinatura ICP-Brasil/)).toBeTruthy();
    expect(screen.getAllByText(/SHA-256 do texto assinado/)).toHaveLength(1); // o parecer sem carimbo não mostra linha nenhuma
  });

  it("vários pareceres: um item por parecer", async () => {
    mockar({ status: 200, corpo: { pareceres: [parecer(), parecer({ numero: 4, conclusao: "contrario" })] } });
    render(<PareceresJuridicosPublicos ente={ENTE} proposicaoId="p1" />);
    await screen.findByRole("heading", { name: "Parecer jurídico nº 4/2026" });
    expect(screen.getAllByRole("article")).toHaveLength(2);
    expect(screen.getByText("Contrário")).toBeTruthy();
  });

  it("lista vazia (matéria ainda não deliberada): a seção não aparece — nem um 'ainda não há'", async () => {
    const f = mockar({ status: 200, corpo: { pareceres: [] } });
    const { container } = render(<PareceresJuridicosPublicos ente={ENTE} proposicaoId="p1" />);
    await waitFor(() => expect(f).toHaveBeenCalled());
    expect(container.textContent).toBe("");
  });

  it.each([
    ["falha do servidor", { status: 500, corpo: {} }],
    ["404", { status: 404, corpo: {} }],
    ["falha de rede", "rede"],
    ["resposta de forma inesperada", { status: 200, corpo: { outra: "coisa" } }],
  ] as const)("%s: a seção some, sem quebrar", async (_nome, resposta) => {
    const f = mockar(resposta as Parameters<typeof mockar>[0]);
    const { container } = render(<PareceresJuridicosPublicos ente={ENTE} proposicaoId="p1" />);
    await waitFor(() => expect(f).toHaveBeenCalled());
    expect(container.textContent).toBe("");
  });

  it("busca a rota pública da matéria (sem credencial)", async () => {
    const f = mockar({ status: 200, corpo: { pareceres: [] } });
    render(<PareceresJuridicosPublicos ente={ENTE} proposicaoId="p1" />);
    await waitFor(() => expect(f).toHaveBeenCalledTimes(1));
    expect(f.mock.calls[0][0]).toBe(URL_PARECERES);
    expect((f.mock.calls[0] as unknown[])[1]).toEqual({ cache: "no-store" });
  });
});

describe("SecaoFicha com pareceres jurídicos", () => {
  const ficha = {
    "proposicao-id": "p1", tipo: "projeto_lei", ano: 2026, sequencial: 42,
    "urn-lex": "urn:lex:br;ce;fortaleza:camara.municipal:projeto.lei:2026;042",
    ementa: "Cria o Programa Municipal de Hortas Comunitárias.", "autor-texto": "Ver.ª Helena Matos", estado: "aprovada",
  };

  it("aparece na ficha pública quando a Casa deliberou e há parecer; a ficha segue inteira", async () => {
    global.fetch = vi.fn(async (url: string) => {
      const u = String(url);
      const corpo = u.endsWith("/pareceres-juridicos") ? { pareceres: [parecer()] } : u.endsWith("/comentarios") ? [] : ficha;
      return { ok: true, status: 200, json: async () => corpo } as Response;
    }) as unknown as typeof fetch;
    render(<SecaoFicha ente={ENTE} proposicaoId="p1" />);
    expect(await screen.findByRole("region", { name: "Pareceres jurídicos" })).toBeTruthy();
    expect(screen.getByRole("heading", { level: 1 }).textContent).toBe("Cria o Programa Municipal de Hortas Comunitárias.");
  });

  it("se a rota de pareceres falha, só a seção some", async () => {
    global.fetch = vi.fn(async (url: string) => {
      const u = String(url);
      if (u.endsWith("/pareceres-juridicos")) return { ok: false, status: 500, json: async () => ({}) } as Response;
      return { ok: true, status: 200, json: async () => (u.endsWith("/comentarios") ? [] : ficha) } as Response;
    }) as unknown as typeof fetch;
    render(<SecaoFicha ente={ENTE} proposicaoId="p1" />);
    await screen.findByRole("heading", { level: 1 });
    expect(screen.queryByRole("region", { name: "Pareceres jurídicos" })).toBeNull();
  });
});
