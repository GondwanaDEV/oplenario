import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, within } from "@testing-library/react";
import { VotacoesPublicas } from "./votacoes-publicas";

// O portal de votações: o que foi votado em sessão pública, o resultado em palavras e, se nominal, o voto de cada
// vereador. Sem percentual nem ranking; voto secreto e simbólico não mostram voto por vereador.

const ENTE = "10000000-0000-0000-0000-000000000001";
const T = "2026-09-10T17:30:00Z";
const SESSAO = { "sessao-id": "s1", "tipo-sessao": "ordinaria", "numero-sequencial": 12, data: "2026-09-10T12:00:00Z" };

function mockar(rotas: Record<string, unknown>) {
  global.fetch = vi.fn(async (url: string) => {
    const corpo = rotas[String(url)];
    if (corpo === undefined) return { ok: false, status: 404, json: async () => ({}) } as Response;
    return { ok: true, status: 200, json: async () => corpo } as Response;
  }) as unknown as typeof fetch;
}

const nominal = {
  "votacao-id": "v1", "encerrada-em": T, sessao: SESSAO, "objeto-tipo": "proposicao", modalidade: "nominal",
  "quorum-tipo": "maioria_simples", resultado: "aprovada",
  materia: { "proposicao-id": "p1", tipo: "projeto_lei", sequencial: 7, ano: 2026, ementa: "Institui o Programa de Hortas" },
  placar: { sim: 2, nao: 1, abstencoes: 0, "base-membros": 13 },
};
const secreta = {
  "votacao-id": "v2", "encerrada-em": T, sessao: SESSAO, "objeto-tipo": "proposicao", modalidade: "secreta",
  "quorum-tipo": "maioria_absoluta", resultado: "rejeitada", placar: { sim: 3, nao: 9, abstencoes: 0, "base-membros": 13 },
};
const simbolica = {
  "votacao-id": "v3", "encerrada-em": T, sessao: SESSAO, "objeto-tipo": "parecer", modalidade: "simbolica",
  "quorum-tipo": "maioria_simples", resultado: "aprovada",
};
const lista = { votacoes: [nominal, secreta, simbolica], total: 41, pagina: 1, "por-pagina": 20 };
const URL_LISTA = `/api/portal/casa/${ENTE}/votacoes`;

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe("VotacoesPublicas", () => {
  it("lista: matéria com a ementa, resultado em palavras, sessão e tipo de votação — sem chave crua de backend", async () => {
    mockar({ [URL_LISTA]: lista });
    render(<VotacoesPublicas ente={ENTE} votacao={null} pagina={1} />);
    const itens = within(await screen.findByRole("list", { name: "Votações encerradas" })).getAllByRole("listitem");
    expect(itens).toHaveLength(3);
    expect(itens[0].textContent).toContain("PL 7/2026");
    expect(itens[0].textContent).toContain("Institui o Programa de Hortas");
    expect(itens[0].textContent).toContain("Aprovada: 2 votos a favor, 1 voto contra e 0 abstenções");
    expect(itens[0].textContent).toMatch(/12ª Sessão Ordinária · votação nominal/);
    expect(itens[1].textContent).toContain("Rejeitada: 3 votos a favor, 9 votos contra e 0 abstenções");
    expect(itens[2].textContent).toContain("Parecer de comissão");
    expect(itens[2].textContent).toContain("Aprovada por votação simbólica, sem contagem de votos");
    expect(document.body.textContent).not.toMatch(/maioria_|proposicao|nominal"|ordinaria/);
    expect(itens[0].querySelector("a")?.getAttribute("href")).toBe(`/portal/casa/${ENTE}/votacoes?votacao=v1`);
  });

  it("diz o total e a página: nada é cortado em silêncio", async () => {
    mockar({ [URL_LISTA]: lista });
    render(<VotacoesPublicas ente={ENTE} votacao={null} pagina={1} />);
    const nav = await screen.findByRole("navigation", { name: "Páginas de votações" });
    expect(nav.textContent).toContain("Mostrando 1 a 3 de 41");
    expect(nav.textContent).toContain("página 1 de 3");
    expect(within(nav).getByRole("link", { name: "Próxima página" }).getAttribute("href")).toBe(
      `/portal/casa/${ENTE}/votacoes?pagina=2`,
    );
    expect(within(nav).queryByRole("link", { name: "Página anterior" })).toBeNull();
  });

  it("página 2: pede ?pagina=2 e oferece a anterior (que volta à primeira sem query)", async () => {
    mockar({ [`${URL_LISTA}?pagina=2`]: { ...lista, pagina: 2, votacoes: [nominal] } });
    render(<VotacoesPublicas ente={ENTE} votacao={null} pagina={2} />);
    const nav = await screen.findByRole("navigation", { name: "Páginas de votações" });
    expect(nav.textContent).toContain("Mostrando 21 a 21 de 41");
    expect(within(nav).getByRole("link", { name: "Página anterior" }).getAttribute("href")).toBe(`/portal/casa/${ENTE}/votacoes`);
  });

  it("votação nominal aberta: o voto de cada vereador pelo nome, o quórum e o link da ficha da matéria", async () => {
    mockar({
      [URL_LISTA]: lista,
      [`${URL_LISTA}/v1`]: {
        ...nominal,
        votos: [
          { "vereador-id": "a", vereador: "Ana Lima", voto: "sim" },
          { "vereador-id": "b", vereador: "Rui Nogueira", voto: "nao" },
          { "vereador-id": "c", vereador: "Zeca Prado", voto: "abstencao" },
        ],
      },
    });
    render(<VotacoesPublicas ente={ENTE} votacao="v1" pagina={1} />);
    const secao = await screen.findByRole("region", { name: "Votação de PL 7/2026" });
    expect(within(secao).getByText(/Para aprovar: maioria simples, sobre 13 membros\./)).toBeTruthy();
    expect(within(secao).getByRole("link", { name: "Ver a matéria" }).getAttribute("href")).toBe(
      `/portal/casa/${ENTE}/materias/p1`,
    );
    const linhas = within(within(secao).getByRole("table")).getAllByRole("row").slice(1);
    expect(linhas.map((l) => l.textContent)).toEqual(["Ana LimaA favor", "Rui NogueiraContra", "Zeca PradoAbstenção"]);
    expect(secao.textContent).not.toMatch(/%|fidelidade|ranking/i);
  });

  it("votação secreta aberta: só o resultado, e diz por que não há voto por vereador", async () => {
    mockar({ [URL_LISTA]: lista, [`${URL_LISTA}/v2`]: { ...secreta, votos: [] } });
    render(<VotacoesPublicas ente={ENTE} votacao="v2" pagina={1} />);
    const secao = await screen.findByRole("region", { name: "Votação de Matéria" });
    expect(within(secao).getByText("Votação secreta: o resultado é público, o voto de cada vereador não.")).toBeTruthy();
    expect(within(secao).queryByRole("table")).toBeNull();
  });

  it("votação simbólica aberta: avisa que não há voto individual", async () => {
    mockar({ [URL_LISTA]: lista, [`${URL_LISTA}/v3`]: { ...simbolica, votos: [] } });
    render(<VotacoesPublicas ente={ENTE} votacao="v3" pagina={1} />);
    expect(await screen.findByText("Votação simbólica: não há registro do voto de cada vereador.")).toBeTruthy();
  });

  it("votação pedida que não existe (ou é de sessão secreta): 'não encontrada', a lista segue", async () => {
    mockar({ [URL_LISTA]: lista });
    render(<VotacoesPublicas ente={ENTE} votacao="fantasma" pagina={1} />);
    expect(await screen.findByText("Votação não encontrada.")).toBeTruthy();
    expect(await screen.findByRole("list", { name: "Votações encerradas" })).toBeTruthy();
  });

  it("Casa sem votação: diz que não há, nunca página em branco", async () => {
    mockar({ [URL_LISTA]: { votacoes: [], total: 0, pagina: 1, "por-pagina": 20 } });
    render(<VotacoesPublicas ente={ENTE} votacao={null} pagina={1} />);
    expect(await screen.findByText("Nenhuma votação encerrada em sessão pública por enquanto.")).toBeTruthy();
    expect(screen.queryByRole("navigation", { name: "Páginas de votações" })).toBeNull();
  });

  it("falha na lista: erro honesto, nunca lista vazia fingida", async () => {
    mockar({});
    render(<VotacoesPublicas ente={ENTE} votacao={null} pagina={1} />);
    expect((await screen.findByRole("alert")).textContent).toMatch(/Não foi possível carregar as votações/);
  });
});
