import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, within } from "@testing-library/react";
import { PautasOficiais } from "./pautas-oficiais";

// ADR-0019 fatia 3: o portal mostra a pauta OFICIAL (a versão publicada, com nº e data) e diz honestamente quando a
// pauta de uma sessão ainda não foi publicada.

const ENTE = "10000000-0000-0000-0000-000000000001";
const T = "2026-10-06T17:30:00Z";

function mockar(rotas: Record<string, unknown>) {
  global.fetch = vi.fn(async (url: string) => {
    const corpo = rotas[String(url)];
    if (corpo === undefined) return { ok: false, status: 404, json: async () => ({}) } as Response;
    return { ok: true, status: 200, json: async () => corpo } as Response;
  }) as unknown as typeof fetch;
}

const lista = {
  sessoes: [
    { "sessao-id": "s2", "tipo-sessao": "ordinaria", "numero-sequencial": 13, estado: "agendada", "agendada-para": "2026-10-14T12:00:00Z" },
    { "sessao-id": "s1", "tipo-sessao": "ordinaria", "numero-sequencial": 12, estado: "agendada", "agendada-para": "2026-10-07T12:00:00Z", "pauta-oficial": { versao: 2, "publicada-em": T, itens: 2 } },
  ],
};

const oficial = {
  sessao: lista.sessoes[1],
  vigente: {
    versao: 2, "tipo-versao": "republicacao", "publicada-em": T, justificativa: "Incluída a leitura do ofício",
    itens: [
      { id: "i1", fase: "ordem_do_dia", "tipo-item": "proposicao", "proposicao-id": "p1", proposicao: { tipo: "projeto_lei", ano: 2026, sequencial: 7, ementa: "Institui o Programa de Hortas" }, ordem: 1 },
      { id: "i2", fase: "expediente", "tipo-item": "leitura", "texto-descricao": "Leitura do ofício da Prefeitura", ordem: 2 },
    ],
  },
  versoes: [
    { versao: 2, "tipo-versao": "republicacao", "publicada-em": T, justificativa: "Incluída a leitura do ofício" },
    { versao: 1, "tipo-versao": "publicacao_inicial", "publicada-em": T },
  ],
};

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe("PautasOficiais", () => {
  it("a pauta publicada numera cada fase a partir do 1, pelo que foi congelado", async () => {
    const congelada = {
      ...oficial,
      vigente: {
        ...oficial.vigente,
        itens: [
          { id: "a", fase: "expediente", "tipo-item": "leitura", "texto-descricao": "Leitura da ata", ordem: 1 },
          { id: "b", fase: "expediente", "tipo-item": "leitura", "texto-descricao": "Leitura de ofício", ordem: 2 },
          { id: "c", fase: "ordem_do_dia", "tipo-item": "leitura", "texto-descricao": "Leitura do parecer", ordem: 6 },
          { id: "d", fase: "ordem_do_dia", "tipo-item": "leitura", "texto-descricao": "Leitura da emenda", ordem: 7 },
        ],
      },
    };
    mockar({ [`/api/portal/casa/${ENTE}/pautas`]: lista, [`/api/portal/casa/${ENTE}/pautas/s1`]: congelada });
    render(<PautasOficiais ente={ENTE} sessao={null} />);
    const secao = await screen.findByRole("region", { name: "Pauta da 12ª Sessão Ordinária" });
    const itens = [...secao.querySelectorAll(".po-itens > li")];
    expect(itens.map((li) => (li as HTMLLIElement).value)).toEqual([1, 2, 1, 2]);
  });

  it("agrupa por fase na ordem da sessão, com o nome da fase uma vez só", async () => {
    const foraDeOrdem = {
      ...oficial,
      vigente: {
        ...oficial.vigente,
        itens: [
          { id: "c", fase: "ordem_do_dia", "tipo-item": "leitura", "texto-descricao": "Leitura do parecer", ordem: 3 },
          { id: "d", fase: "ordem_do_dia", "tipo-item": "leitura", "texto-descricao": "Leitura da emenda", ordem: 4 },
          { id: "a", fase: "expediente", "tipo-item": "leitura", "texto-descricao": "Leitura da ata", ordem: 5 },
        ],
      },
    };
    mockar({ [`/api/portal/casa/${ENTE}/pautas`]: lista, [`/api/portal/casa/${ENTE}/pautas/s1`]: foraDeOrdem });
    render(<PautasOficiais ente={ENTE} sessao={null} />);
    const secao = await screen.findByRole("region", { name: "Pauta da 12ª Sessão Ordinária" });
    expect(within(secao).getAllByRole("heading", { level: 3 }).map((h) => h.textContent)).toEqual(["Expediente", "Ordem do Dia"]);
    expect(within(within(secao).getByRole("region", { name: "Ordem do Dia" })).getAllByRole("listitem").map((li) => li.textContent)).toEqual([
      "Leitura do parecer",
      "Leitura da emenda",
    ]);
  });

  it("abre a última pauta publicada: versão, data, os itens com link à matéria e as publicações anteriores", async () => {
    mockar({ [`/api/portal/casa/${ENTE}/pautas`]: lista, [`/api/portal/casa/${ENTE}/pautas/s1`]: oficial });
    render(<PautasOficiais ente={ENTE} sessao={null} />);
    const secao = await screen.findByRole("region", { name: "Pauta da 12ª Sessão Ordinária" });
    expect(within(secao).getByText(/Pauta oficial · versão 2, publicada em .* · Incluída a leitura do ofício/)).toBeTruthy();
    const [materia] = within(secao).getAllByRole("link");
    expect(materia.textContent).toBe("PL 7/2026 — Institui o Programa de Hortas");
    expect(materia.getAttribute("href")).toBe(`/portal/casa/${ENTE}/materias/p1`);
    expect(within(secao).getByText("Leitura do ofício da Prefeitura")).toBeTruthy();
    expect(within(secao).getByText("Publicações anteriores (1)")).toBeTruthy();
    const itens = within(screen.getByRole("list", { name: "Sessões e suas pautas" })).getAllByRole("listitem");
    expect(itens[0].textContent).toMatch(/13ª Sessão Ordinária.*Pauta ainda não publicada/);
    expect(itens[1].textContent).toMatch(/Publicada v2 em .* · 2 itens/);
  });

  it("sessão pedida sem publicação: diz que a pauta ainda não foi publicada", async () => {
    mockar({
      [`/api/portal/casa/${ENTE}/pautas`]: lista,
      [`/api/portal/casa/${ENTE}/pautas/s2`]: { sessao: lista.sessoes[0], versoes: [] },
    });
    render(<PautasOficiais ente={ENTE} sessao="s2" />);
    expect(await screen.findByText("A pauta desta sessão ainda não foi publicada.")).toBeTruthy();
  });

  it("falha na lista: erro honesto, nunca lista vazia fingida", async () => {
    mockar({});
    render(<PautasOficiais ente={ENTE} sessao={null} />);
    expect((await screen.findByRole("alert")).textContent).toMatch(/Não foi possível carregar as pautas/);
  });
});
