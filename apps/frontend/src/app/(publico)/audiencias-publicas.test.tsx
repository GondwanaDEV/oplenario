import { afterEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { Audiencia, ListaAudiencias } from "./audiencias-publicas";

vi.mock("@/lib/tema", () => ({ useTema: () => ({ tema: "claro", alternar: vi.fn() }) }));

const ENTE = "10000000-0000-0000-0000-000000000001";
const cidada = { estado: "cidada" as const, token: "tok" };
const anonima = { estado: "anonima" as const, token: null };

const resumo = (p: Record<string, unknown>) => ({
  "sessao-id": "s1", tema: "Mobilidade urbana", "comissao-nome": "Comissão de Meio Ambiente",
  "agendada-para": new Date(2026, 5, 11, 9, 0).toISOString(), estado: "agendada", local: "Plenário", finalidade: "tematica", ...p,
});

function publica(p: Record<string, unknown> = {}) {
  return {
    ...resumo({}), modalidade: "presencial", "tempo-fala-segundos": 300, "inscricoes-abertas": true, inscritos: 3,
    "ata-publicada": false, falaram: [], proposicao: { id: "p29", rotulo: "PL 29/2026", ementa: "Arborização e mobilidade" },
    ...p,
  };
}

const lista = {
  proximas: [resumo({}), resumo({ "sessao-id": "s2", tema: "Saúde da família", "comissao-nome": "Comissão de Saúde" })],
  realizadas: [resumo({ "sessao-id": "s0", tema: "LDO 2027", estado: "encerrada", finalidade: "ldo" })],
};

type Chamada = { url: string; metodo: string; corpo: unknown };
let chamadas: Chamada[] = [];

function servidor(rotas: Record<string, { status: number; corpo: unknown }>) {
  chamadas = [];
  global.fetch = vi.fn(async (url: string, init?: RequestInit) => {
    const metodo = init?.method ?? "GET";
    chamadas.push({ url, metodo, corpo: init?.body ? JSON.parse(String(init.body)) : undefined });
    const r = rotas[`${metodo} ${url}`] ?? { status: 404, corpo: {} };
    return { ok: r.status < 300, status: r.status, json: async () => r.corpo } as Response;
  }) as unknown as typeof fetch;
}

const GET_LISTA = `GET /api/portal/casa/${ENTE}/audiencias`;
const GET_S1 = `GET /api/portal/casa/${ENTE}/audiencias/s1`;
const POST_INSC = "POST /api/portal/audiencias/s1/inscricoes";

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe("lista de audiências públicas", () => {
  it("próximas e realizadas, cada uma levando à sua página", async () => {
    servidor({ [GET_LISTA]: { status: 200, corpo: lista } });
    render(<ListaAudiencias ente={ENTE} />);
    const proximas = await screen.findByRole("region", { name: "Próximas" });
    const link = within(proximas).getByRole("link", { name: "Mobilidade urbana" });
    expect(link.getAttribute("href")).toBe(`/portal/casa/${ENTE}/audiencias/s1`);
    expect(within(proximas).getByText(/Comissão de Meio Ambiente · Quinta, 11\/06\/2026 · 9h · Plenário/)).toBeTruthy();
    const realizadas = screen.getByRole("region", { name: "Realizadas" });
    expect(within(realizadas).getByText("LDO 2027")).toBeTruthy();
    expect(within(realizadas).getByText("Realizada")).toBeTruthy();
    expect(within(realizadas).getByText(/Lei de Diretrizes Orçamentárias/)).toBeTruthy();
  });

  it("vazia: diz que não há, sem inventar", async () => {
    servidor({ [GET_LISTA]: { status: 200, corpo: { proximas: [], realizadas: [] } } });
    render(<ListaAudiencias ente={ENTE} />);
    expect(await screen.findByText("Nenhuma audiência pública marcada por enquanto.")).toBeTruthy();
    expect(screen.getByText("Nenhuma audiência realizada ainda.")).toBeTruthy();
  });

  it("falha: erro na tela, nunca lista vazia fingindo", async () => {
    servidor({ [GET_LISTA]: { status: 500, corpo: {} } });
    render(<ListaAudiencias ente={ENTE} />);
    expect((await screen.findByRole("alert")).textContent).toMatch(/Não foi possível carregar as audiências/);
  });
});

describe("página da audiência", () => {
  it("sobre a audiência, a matéria relacionada e as próximas (sem a atual)", async () => {
    servidor({ [GET_S1]: { status: 200, corpo: publica() }, [GET_LISTA]: { status: 200, corpo: lista } });
    render(<Audiencia ente={ENTE} sessaoId="s1" sessao={anonima} />);
    expect(await screen.findByRole("heading", { name: "Audiência pública: Mobilidade urbana" })).toBeTruthy();
    expect(screen.getByText("Quinta, 11/06/2026 · 9h", { selector: "dd" })).toBeTruthy();
    expect(screen.getByRole("link", { name: /PL 29\/2026 — Arborização e mobilidade/ }).getAttribute("href")).toBe(
      `/portal/casa/${ENTE}/materias/p29`,
    );
    expect(screen.getByText("5 min por pessoa")).toBeTruthy();
    expect(screen.getByText("3 pessoas")).toBeTruthy();
    const trilho = await screen.findByRole("complementary", { name: "Outras audiências" });
    await within(trilho).findByText("Saúde da família");
    expect(within(trilho).queryByText("Mobilidade urbana")).toBeNull();
  });

  it("sem sessão: o convite do gov.br volta a esta audiência; nenhum campo de nome", async () => {
    servidor({ [GET_S1]: { status: 200, corpo: publica() }, [GET_LISTA]: { status: 200, corpo: lista } });
    render(<Audiencia ente={ENTE} sessaoId="s1" sessao={anonima} />);
    const govbr = await screen.findByRole("link", { name: /entrar com gov\.br/i });
    expect(decodeURIComponent(govbr.getAttribute("href")!)).toContain(`redirect=/portal/casa/${ENTE}/audiencias/s1`);
    expect(screen.getByRole("heading", { name: "Quero falar na audiência" })).toBeTruthy();
    expect(screen.queryByRole("button", { name: /Inscrever-me/ })).toBeNull();
  });

  it("inscrições fechadas: mensagem clara, sem formulário", async () => {
    servidor({ [GET_S1]: { status: 200, corpo: publica({ "inscricoes-abertas": false }) }, [GET_LISTA]: { status: 200, corpo: lista } });
    render(<Audiencia ente={ENTE} sessaoId="s1" sessao={cidada} />);
    expect(await screen.findByRole("heading", { name: "Inscrições para falar encerradas" })).toBeTruthy();
    expect(screen.queryByRole("button", { name: /Inscrever-me/ })).toBeNull();
  });

  it("realizada: quem falou e o link para a ata no livro de atas", async () => {
    servidor({
      [GET_S1]: {
        status: 200,
        corpo: publica({
          estado: "encerrada", "inscricoes-abertas": false, "ata-publicada": true,
          falaram: [{ nome: "Ana Souza", "fala-como": "individual" }, { nome: "José Lima", "fala-como": "entidade", entidade: "Associação do Ellery" }],
        }),
      },
      [GET_LISTA]: { status: 200, corpo: lista },
    });
    render(<Audiencia ente={ENTE} sessaoId="s1" sessao={cidada} />);
    expect(await screen.findByRole("heading", { name: "Quem falou" })).toBeTruthy();
    expect(screen.getByText("Ana Souza")).toBeTruthy();
    expect(screen.getByText("Representante de entidade · Associação do Ellery")).toBeTruthy();
    expect(screen.getByRole("link", { name: "Ler a ata no livro de atas" }).getAttribute("href")).toBe(`/portal/casa/${ENTE}/atas?sessao=s1`);
    expect(screen.queryByText("5 min por pessoa")).toBeNull();
  });

  it("realizada sem ata ainda: diz que não foi publicada, sem link morto", async () => {
    servidor({ [GET_S1]: { status: 200, corpo: publica({ estado: "encerrada", "inscricoes-abertas": false }) }, [GET_LISTA]: { status: 200, corpo: lista } });
    render(<Audiencia ente={ENTE} sessaoId="s1" sessao={cidada} />);
    expect(await screen.findByText(/ainda não foi publicada/)).toBeTruthy();
    expect(screen.queryByRole("link", { name: /Ler a ata/ })).toBeNull();
  });

  it("audiência que não existe ou não é pública", async () => {
    servidor({ [GET_LISTA]: { status: 200, corpo: lista } });
    render(<Audiencia ente={ENTE} sessaoId="s1" sessao={cidada} />);
    expect((await screen.findByRole("alert")).textContent).toMatch(/Audiência não encontrada/);
  });
});

describe("Quero falar na audiência", () => {
  async function abrirFormulario(rotasExtra: Record<string, { status: number; corpo: unknown }> = {}) {
    servidor({ [GET_S1]: { status: 200, corpo: publica() }, [GET_LISTA]: { status: 200, corpo: lista }, ...rotasExtra });
    render(<Audiencia ente={ENTE} sessaoId="s1" sessao={cidada} />);
    await screen.findByRole("heading", { name: "Quero falar na audiência" });
  }
  const enviar = async () => {
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: "Inscrever-me para falar" }));
    });
  };
  const posts = () => chamadas.filter((c) => c.metodo === "POST");

  it("o nome não é digitado: vem do gov.br", async () => {
    await abrirFormulario();
    expect(screen.getByText(/Seu nome vem do gov\.br/)).toBeTruthy();
    expect(screen.queryByRole("textbox", { name: /nome/i })).toBeNull();
  });

  it("sem o ciente, nada vai ao servidor", async () => {
    await abrirFormulario();
    fireEvent.change(screen.getByLabelText(/sobre o que pretende falar/), { target: { value: "Ciclovias na Beira-Mar" } });
    await enviar();
    expect(screen.getByRole("alert").textContent).toMatch(/ciente de que a fala é pública/);
    expect(posts()).toEqual([]);
  });

  it("representante: a entidade é obrigatória", async () => {
    await abrirFormulario();
    fireEvent.click(screen.getByLabelText(/Representante de entidade/));
    fireEvent.change(screen.getByLabelText(/sobre o que pretende falar/), { target: { value: "Ônibus" } });
    fireEvent.click(screen.getByRole("checkbox"));
    await enviar();
    expect(screen.getByRole("alert").textContent).toMatch(/qual entidade/i);
    expect(posts()).toEqual([]);
  });

  it("válida: envia fala-como, tema e o ciente, e mostra o recibo com o protocolo e a ordem", async () => {
    await abrirFormulario({ [POST_INSC]: { status: 201, corpo: { protocolo: "AUD-2026-000004", "recibo-em": "2026-06-01T12:00:00Z", ordem: 4 } } });
    fireEvent.click(screen.getByLabelText(/Conselho ou movimento/));
    fireEvent.change(screen.getByLabelText(/Qual entidade/), { target: { value: "Conselho Municipal de Saúde" } });
    fireEvent.change(screen.getByLabelText(/sobre o que pretende falar/), { target: { value: "  Postos de saúde  " } });
    fireEvent.click(screen.getByRole("checkbox", { name: /Estou ciente de que a fala é pública e entra na ata e na transmissão/ }));
    await enviar();
    await waitFor(() => expect(screen.getByText("AUD-2026-000004")).toBeTruthy());
    expect(screen.getByText("4ª pessoa")).toBeTruthy();
    expect(posts()).toEqual([
      {
        url: "/api/portal/audiencias/s1/inscricoes",
        metodo: "POST",
        corpo: { "fala-como": "conselho_movimento", entidade: "Conselho Municipal de Saúde", tema: "Postos de saúde", "ciente-publicidade": true },
      },
    ]);
    expect(screen.getByRole("link", { name: /Meus protocolos/ }).getAttribute("href")).toBe("/meus-protocolos");
  });

  it("409: já inscrita ou inscrições fechadas, em palavras", async () => {
    await abrirFormulario({ [POST_INSC]: { status: 409, corpo: { erro: "ja inscrito" } } });
    fireEvent.change(screen.getByLabelText(/sobre o que pretende falar/), { target: { value: "Ciclovias" } });
    fireEvent.click(screen.getByRole("checkbox"));
    await enviar();
    await waitFor(() => expect(screen.getByRole("alert").textContent).toMatch(/já tem inscrição nesta audiência/));
  });

  it("Casa com acesso restrito (423): diz que as inscrições estão suspensas", async () => {
    await abrirFormulario({ [POST_INSC]: { status: 423, corpo: {} } });
    fireEvent.change(screen.getByLabelText(/sobre o que pretende falar/), { target: { value: "Ciclovias" } });
    fireEvent.click(screen.getByRole("checkbox"));
    await enviar();
    await waitFor(() => expect(screen.getByRole("alert").textContent).toMatch(/acesso restrito/));
  });
});
