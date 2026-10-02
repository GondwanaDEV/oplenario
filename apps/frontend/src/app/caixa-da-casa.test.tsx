import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { CaixaDaCasa } from "./caixa-da-casa";

// A caixa unificada (ADR-0020, Eixo 7): comunicados + avisos do sistema numa lista só, com os quatro filtros, a faixa
// de ciência (fatia 3) e cada fonte falhando sozinha. fetch mockado por rota; os hooks reais rodam.

vi.mock("@/lib/auth", () => ({ useAuth: () => ({ token: "tok" }) }));

const MIN = 60_000;
const DIA = 24 * 60 * MIN;
const iso = (deltaMs: number) => new Date(Date.now() + deltaMs).toISOString();
const DIA_MES = new Intl.DateTimeFormat("pt-BR", { timeZone: "America/Fortaleza", day: "2-digit", month: "2-digit" });
const diaMes = (s: string) => DIA_MES.format(Date.parse(s));

function comunicado(id: string, extra: Record<string, unknown> = {}) {
  return {
    id, protocolo: `COM-2026-00000${id.slice(-1)}`, assunto: `Comunicado ${id}`, "remetente-nome": "Rita Campos", "enviado-em": iso(-5 * MIN),
    "exige-ciencia": false, "ciencia-ate": null, vencido: false, "recebido-em": iso(-1 * MIN), "lido-em": null, "ciente-em": null, ...extra,
  };
}

function aviso(id: string, extra: Record<string, unknown> = {}) {
  return {
    id, categoria: "norma_publicada", assunto: `Aviso ${id}`, corpo: "Ementa: …", "objeto-tipo": "proposicao", "objeto-id": `p-${id}`,
    "criado-em": iso(-10 * MIN), "lida-em": null, ...extra,
  };
}

type Resp = { status: number; corpo: unknown };
const ok = (corpo: unknown): Resp => ({ status: 200, corpo });

function servindo(caixa: Resp | (() => Resp), avisos: Resp | (() => Resp)) {
  const chamadas: string[] = [];
  global.fetch = vi.fn(async (url: string, init?: RequestInit) => {
    chamadas.push(`${init?.method ?? "GET"} ${url}`);
    if (init?.method === "POST") return { ok: true, status: 200, json: async () => ({ id: "a1", "lida-em": iso(0) }) } as Response;
    const r = url === "/api/meu/comunicados" ? caixa : url === "/api/meu/notificacoes" ? avisos : { status: 404, corpo: {} };
    const { status, corpo } = typeof r === "function" ? r() : r;
    return { ok: status < 300, status, json: async () => corpo } as Response;
  }) as unknown as typeof fetch;
  return chamadas;
}

const caixaCom = (itens: unknown[], extra: Record<string, unknown> = {}) => ok({ itens, "nao-lidos": 0, "pendentes-ciencia": 0, "proxima-ciencia-ate": null, ...extra });
const avisosCom = (notificacoes: unknown[], extra: Record<string, unknown> = {}) => ok({ notificacoes, "nao-lidas": 0, "notificacoes-total": notificacoes.length, ...extra });

const montar = (superficie: "interno" | "vereador" = "interno") =>
  render(<CaixaDaCasa superficie={superficie} hrefComunicado={(id) => `/comunicados/${id}`} />);

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe("CaixaDaCasa — a lista", () => {
  it("junta comunicados e avisos por data (mais recentes primeiro) e diz a origem de cada um", async () => {
    servindo(
      caixaCom([comunicado("c1", { "enviado-em": iso(-2 * MIN) }), comunicado("c2", { "enviado-em": iso(-30 * MIN) })], { "nao-lidos": 2 }),
      avisosCom([aviso("a1", { "criado-em": iso(-10 * MIN) })], { "nao-lidas": 1 }),
    );
    montar();
    await screen.findByText("Aviso a1");
    const titulos = screen.getAllByRole("heading", { level: 3 }).map((h) => h.textContent);
    expect(titulos).toEqual(["Comunicado c1", "Aviso a1", "Comunicado c2"]);
    expect(screen.getAllByText("Comunicado").length).toBe(2);
    expect(screen.getByText("Aviso do sistema")).toBeTruthy();
    expect(screen.getByRole("heading", { level: 1 }).textContent).toBe("Caixa3, 3 por ler");
    // o comunicado é lido ABRINDO-O: o título é o link; não há "marcar como lido" de comunicado
    expect(screen.getByRole("link", { name: "Comunicado c1" }).getAttribute("href")).toBe("/comunicados/c1?token=tok");
    const art = screen.getByText("Comunicado c1").closest("article")!;
    expect(within(art).queryByRole("button", { name: /Marcar como lido/ })).toBeNull();
    expect(within(art).getByRole("img", { name: "Não lido" })).toBeTruthy();
  });

  it("os quatro filtros, com a contagem, e cada um recorta o que diz", async () => {
    servindo(
      caixaCom([
        comunicado("c1", { "exige-ciencia": true, "ciencia-ate": iso(3 * DIA) }),
        comunicado("c2", { "lido-em": iso(-1 * MIN) }),
        comunicado("c3", { "exige-ciencia": true, "lido-em": iso(-1 * MIN), "ciente-em": iso(-1 * MIN) }),
      ]),
      avisosCom([aviso("a1"), aviso("a2", { "lida-em": iso(-1 * MIN) })]),
    );
    montar();
    await screen.findByText("Aviso a1");
    const barra = screen.getByRole("group", { name: "Filtrar a caixa" });
    expect(Array.from(barra.querySelectorAll("button")).map((b) => b.textContent)).toEqual(["Tudo5", "Não lidos2", "Para ciência1", "Do sistema2"]);

    fireEvent.click(within(barra).getByRole("button", { name: /^Para ciência/ }));
    expect(screen.getByText("Comunicado c1")).toBeTruthy();
    expect(screen.queryByText("Comunicado c3")).toBeNull(); // já ciente: não está "para ciência"
    expect(screen.queryByText("Aviso a1")).toBeNull();
    expect(within(barra).getByRole("button", { name: /^Para ciência/ }).getAttribute("aria-pressed")).toBe("true");

    fireEvent.click(within(barra).getByRole("button", { name: /^Do sistema/ }));
    expect(screen.getByText("Aviso a1")).toBeTruthy();
    expect(screen.getByText("Aviso a2")).toBeTruthy();
    expect(screen.queryByText("Comunicado c1")).toBeNull();

    fireEvent.click(within(barra).getByRole("button", { name: /^Não lidos/ }));
    expect(screen.getByText("Comunicado c1")).toBeTruthy();
    expect(screen.getByText("Aviso a1")).toBeTruthy();
    expect(screen.queryByText("Comunicado c2")).toBeNull();
  });

  it("o filtro vazio diz que é o FILTRO, não a caixa", async () => {
    servindo(caixaCom([comunicado("c1", { "lido-em": iso(-1 * MIN) })]), avisosCom([]));
    montar();
    await screen.findByText("Comunicado c1");
    fireEvent.click(screen.getByRole("button", { name: /^Para ciência/ }));
    expect(screen.getByText("Nenhum comunicado aguardando a sua ciência.")).toBeTruthy();
    expect(screen.queryByText(/Sua caixa está vazia/)).toBeNull();
  });

  it("o chip da ciência diz o estado em texto: até, vencida, ciente", async () => {
    const ate = iso(3 * DIA);
    const venceu = iso(-2 * DIA);
    servindo(
      caixaCom([
        comunicado("c1", { "exige-ciencia": true, "ciencia-ate": ate }),
        comunicado("c2", { "exige-ciencia": true, "ciencia-ate": venceu, vencido: true }),
        comunicado("c3", { "exige-ciencia": true, "ciente-em": iso(-1 * MIN), "lido-em": iso(-1 * MIN) }),
      ]),
      avisosCom([]),
    );
    montar();
    await screen.findByText("Comunicado c1");
    expect(screen.getByText(`Ciência até ${diaMes(ate)}`)).toBeTruthy();
    expect(screen.getByText(`Ciência vencida em ${diaMes(venceu)}`)).toBeTruthy();
    expect(screen.getByText("Ciente")).toBeTruthy();
    expect(screen.getByText("Comunicado c2").closest("article")!.className).toContain("cx-vencido");
  });
});

describe("CaixaDaCasa — a faixa de ciência (fatia 3)", () => {
  it("diz quantos aguardam, o prazo mais próximo e os já vencidos, e leva ao filtro", async () => {
    const proxima = iso(3 * DIA);
    servindo(
      caixaCom(
        [
          comunicado("c1", { "exige-ciencia": true, "ciencia-ate": proxima }),
          comunicado("c2", { "exige-ciencia": true, "ciencia-ate": iso(-DIA), vencido: true }),
          comunicado("c3"),
        ],
        { "pendentes-ciencia": 2, "proxima-ciencia-ate": proxima },
      ),
      avisosCom([]),
    );
    montar();
    const faixa = await screen.findByRole("note");
    expect(faixa.textContent).toContain(`Você tem 2 comunicados aguardando ciência — o mais próximo vence em ${diaMes(proxima)}; 1 já passou do prazo.`);
    fireEvent.click(within(faixa).getByRole("button", { name: "Ver só os que pedem ciência" }));
    expect(screen.queryByText("Comunicado c3")).toBeNull();
    expect(screen.getByRole("button", { name: /^Para ciência/ }).getAttribute("aria-pressed")).toBe("true");
  });

  it("sem pendência, sem faixa", async () => {
    servindo(caixaCom([comunicado("c1")]), avisosCom([]));
    montar();
    await screen.findByText("Comunicado c1");
    expect(screen.queryByRole("note")).toBeNull();
  });
});

describe("CaixaDaCasa — cada fonte falha sozinha", () => {
  it("comunicados fora: mostra os avisos e diz o que faltou", async () => {
    servindo({ status: 500, corpo: {} }, avisosCom([aviso("a1")]));
    montar();
    expect(await screen.findByText("Aviso a1")).toBeTruthy();
    expect(screen.getByRole("alert").textContent).toMatch(/Os comunicados não carregaram/);
  });

  it("avisos fora: mostra os comunicados e diz o que faltou", async () => {
    servindo(caixaCom([comunicado("c1")]), { status: 500, corpo: {} });
    montar();
    expect(await screen.findByText("Comunicado c1")).toBeTruthy();
    expect(screen.getByRole("alert").textContent).toMatch(/Os avisos do sistema não carregaram/);
  });

  it("403 nos avisos é caixa de avisos VAZIA, não erro (quem não tem a projeção não tem aviso)", async () => {
    servindo(caixaCom([comunicado("c1")]), { status: 403, corpo: { erro: "proibido" } });
    montar();
    expect(await screen.findByText("Comunicado c1")).toBeTruthy();
    expect(screen.queryByRole("alert")).toBeNull();
  });

  it("as duas fora: uma frase com a causa e 'Tentar de novo' que refaz as duas", async () => {
    let falhar = true;
    const chamadas = servindo(
      () => (falhar ? { status: 403, corpo: {} } : caixaCom([comunicado("c1")])),
      () => (falhar ? { status: 500, corpo: {} } : avisosCom([])),
    );
    montar();
    const alerta = await screen.findByRole("alert");
    expect(alerta.textContent).toContain("Não foi possível carregar a sua caixa.");
    expect(alerta.textContent).toContain("Sua sessão não dá acesso à caixa da Casa.");
    falhar = false;
    fireEvent.click(within(alerta).getByRole("button", { name: "Tentar de novo" }));
    expect(await screen.findByText("Comunicado c1")).toBeTruthy();
    expect(chamadas.filter((c) => c === "GET /api/meu/comunicados").length).toBe(2);
  });
});

describe("CaixaDaCasa — as portas", () => {
  it("escrever e enviados levam às telas do comunicado (com o token de dev preservado)", async () => {
    servindo(caixaCom([]), avisosCom([]));
    montar("vereador");
    await screen.findByText(/Sua caixa está vazia/);
    expect(screen.getByRole("link", { name: "Escrever comunicado" }).getAttribute("href")).toBe("/comunicados/novo?token=tok");
    expect(screen.getByRole("link", { name: "Enviados" }).getAttribute("href")).toBe("/comunicados/enviados?token=tok");
  });
});

describe("CaixaDaCasa — o contador do topo fica sabendo", () => {
  it("marcar um aviso como lido dispara o evento que refaz o número do topo", async () => {
    servindo(caixaCom([]), avisosCom([aviso("a1")], { "nao-lidas": 1 }));
    const ouvinte = vi.fn();
    window.addEventListener("oplenario:caixa-mudou", ouvinte);
    montar();
    fireEvent.click(await screen.findByRole("button", { name: /Marcar como lido/ }));
    await waitFor(() => expect(ouvinte).toHaveBeenCalled());
    window.removeEventListener("oplenario:caixa-mudou", ouvinte);
  });
});
