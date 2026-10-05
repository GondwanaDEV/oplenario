import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";

const acesso = { papeis: ["auditor"] as string[], estado: "pronto" as "pronto" | "carregando" | "erro" };
vi.mock("@/lib/auth", () => ({
  useAuth: () => ({ token: "tk" }),
  usePapeis: () => ({ papeis: acesso.papeis, estado: acesso.estado }),
}));
vi.mock("../../topo", () => ({ TopoInterno: () => null }));

import PaginaConversasDaClara from "./page";

function json(corpo: unknown, status = 200) {
  return new Response(JSON.stringify(corpo), { status, headers: { "content-type": "application/json" } });
}

// minutos atrás: as duas caem no "Hoje" do fuso da Casa (como no teste do painel da Clara)
const ha = (min: number) => new Date(Date.now() - min * 60_000).toISOString();

const DA_CASA = () => ({
  interacoes: [
    { id: "i-1", "conversa-id": "c-1", "identidade-id": "p-rita", nome: "Rita Campos", pergunta: "Qual a situação do PL 11/2026?",
      desfecho: "resposta", "ocorrido-em": ha(1), "n-fontes": 3, "n-propostas": 0 },
    { id: "i-9", "conversa-id": "c-9", "identidade-id": "p-joao", nome: "João Abreu", pergunta: "Quem é o relator do PL 3/2026?",
      desfecho: "indisponivel", "ocorrido-em": ha(2), "n-fontes": 0, "n-propostas": 0 },
  ],
  mais: true,
  antes: "2026-10-01T12:00:00Z",
});

const ANTERIORES = {
  interacoes: [
    { id: "i-0", "conversa-id": "c-0", "identidade-id": "p-rita", nome: "Rita Campos", pergunta: "O que vai ser votado amanhã?",
      desfecho: "resposta", "ocorrido-em": "2026-09-20T13:00:00Z", "n-fontes": 1, "n-propostas": 0 },
  ],
  mais: false,
  antes: null,
};

const CONVERSA = {
  "conversa-id": "c-1",
  "identidade-id": "p-rita",
  nome: "Rita Campos",
  interacoes: [
    {
      id: "i-1", pergunta: "Qual a situação do PL 11/2026?", desfecho: "resposta",
      resposta: { texto: "Está nas comissões. [[ferramenta:situacao_da_materia#1 | estado: comissoes]]",
        citacoes: [{ "fonte-id": "ferramenta:situacao_da_materia#1", trecho: "estado: comissoes", status: "conferida" }],
        "paragrafos-sem-fonte": [], incerteza: "normal", contaminado: false },
      passos: [{ ferramenta: "situacao_da_materia", argumentos: { tipo: "projeto_lei", sequencial: 11, ano: 2026 }, ok: true }],
      propostas: [], modelo: "openai/gpt-oss-120b", "execucao-ia": "ia-7", "ocorrido-em": "2026-10-05T13:42:00Z",
      "conteudo-sha256": "a".repeat(64), integra: true,
    },
    {
      id: "i-2", pergunta: "E o relator?", desfecho: "indisponivel", resposta: null, passos: [], propostas: [], modelo: null,
      "execucao-ia": null, "ocorrido-em": "2026-10-05T13:44:00Z", "conteudo-sha256": "b".repeat(64), integra: true,
    },
  ],
};

/** `historico(url)` decide a resposta da lista pela query pedida. */
function rotas(historico: (url: string) => Response = () => json(DA_CASA())) {
  return vi.fn(async (url: string) => {
    if (url.startsWith("/api/agente/historico")) return historico(url);
    if (url.startsWith("/api/agente/conversas/")) return json(CONVERSA);
    return json({}, 404);
  });
}

const pedidos = (f: ReturnType<typeof rotas>) => f.mock.calls.map(([u]) => String(u));

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  acesso.papeis = ["auditor"];
  acesso.estado = "pronto";
});

describe("Conversas com a Clara (/auditoria/clara)", () => {
  it("quem não é do controle interno não vê nada da Casa — e a tela nem pede o histórico", async () => {
    acesso.papeis = ["secretario"];
    const f = rotas();
    vi.stubGlobal("fetch", f);
    render(<PaginaConversasDaClara />);
    expect(screen.getByText("Esta tela é do controle interno da Casa.")).toBeTruthy();
    expect(f).not.toHaveBeenCalled();
  });

  it("enquanto o acesso é conferido, a tela fica vazia (sem piscar 'acesso restrito')", () => {
    acesso.estado = "carregando";
    acesso.papeis = [];
    vi.stubGlobal("fetch", rotas());
    const { container } = render(<PaginaConversasDaClara />);
    expect(container.textContent).toBe("");
  });

  it("o auditor lê as perguntas da Casa, por dia, avisado de que a leitura vai à trilha", async () => {
    const f = rotas();
    vi.stubGlobal("fetch", f);
    render(<PaginaConversasDaClara />);
    expect(screen.getByRole("heading", { level: 1, name: "Conversas com a Clara" })).toBeTruthy();
    expect(screen.getByText("Cada conversa que você abre aqui fica registrada na trilha de auditoria.")).toBeTruthy();
    expect(screen.getByText(/ainda está em definição/)).toBeTruthy();
    const dia = await screen.findByRole("list", { name: "Hoje" });
    const linhas = within(dia).getAllByRole("listitem");
    expect(linhas).toHaveLength(2);
    expect(within(linhas[0]).getByRole("button", { name: /Qual a situação do PL 11\/2026\?/ })).toBeTruthy();
    expect(linhas[0].textContent).toContain("3 fontes");
    expect(linhas[1].textContent).toMatch(/sem resposta/);
    expect(pedidos(f)).toEqual(["/api/agente/historico?escopo=casa"]);
  });

  it("o nome filtra pela pessoa; o chip devolve a Casa inteira", async () => {
    const f = rotas((u) => json(u.includes("pessoa=") ? { interacoes: [DA_CASA().interacoes[0]], mais: false, antes: null } : DA_CASA()));
    vi.stubGlobal("fetch", f);
    render(<PaginaConversasDaClara />);
    fireEvent.click(await screen.findByRole("button", { name: /^Rita Campos: ver só/ }));
    const chip = await screen.findByRole("button", { name: "Tirar o filtro: só de Rita Campos" });
    expect(chip.textContent).toBe("Só de Rita Campos");
    await waitFor(() => expect(pedidos(f)).toContain("/api/agente/historico?pessoa=p-rita"));
    await waitFor(() => expect(within(screen.getByRole("list", { name: "Hoje" })).getAllByRole("listitem")).toHaveLength(1));
    fireEvent.click(chip);
    await waitFor(() => expect(within(screen.getByRole("list", { name: "Hoje" })).getAllByRole("listitem")).toHaveLength(2));
    expect(pedidos(f).filter((u) => u === "/api/agente/historico?escopo=casa")).toHaveLength(2);
  });

  it("a busca pede 2 letras, procura na pergunta e na resposta e diz quando não acha", async () => {
    const f = rotas((u) => json(u.includes("q=") ? { interacoes: [], mais: false, antes: null } : DA_CASA()));
    vi.stubGlobal("fetch", f);
    render(<PaginaConversasDaClara />);
    await screen.findByRole("list", { name: "Hoje" });
    const campo = screen.getByLabelText("Buscar nas conversas");
    fireEvent.change(campo, { target: { value: " v " } });
    fireEvent.click(screen.getByRole("button", { name: "Buscar" }));
    expect(screen.getByRole("alert").textContent).toBe("Escreva pelo menos 2 letras para buscar.");
    expect(pedidos(f)).toHaveLength(1);
    fireEvent.change(campo, { target: { value: "orçamento" } });
    fireEvent.click(screen.getByRole("button", { name: "Buscar" }));
    expect(await screen.findByText("Nenhuma pergunta com “orçamento” na pergunta ou na resposta.")).toBeTruthy();
    expect(pedidos(f)).toContain("/api/agente/historico?escopo=casa&q=or%C3%A7amento");
    // tirar a busca volta à lista inteira
    fireEvent.click(screen.getByRole("button", { name: "Tirar a busca por “orçamento”" }));
    expect(await screen.findByRole("list", { name: "Hoje" })).toBeTruthy();
    expect((campo as HTMLInputElement).value).toBe("");
  });

  it("abrir a pergunta mostra a conversa inteira, o registro e o link de cada pergunta na trilha", async () => {
    const f = rotas();
    vi.stubGlobal("fetch", f);
    render(<PaginaConversasDaClara />);
    fireEvent.click(await screen.findByRole("button", { name: /Qual a situação do PL 11\/2026\?/ }));
    expect(await screen.findByRole("heading", { name: "Conversa de Rita Campos" })).toBeTruthy();
    expect(pedidos(f)).toContain("/api/agente/conversas/c-1");
    expect(screen.getByRole("group", { name: "Registro desta conversa" }).textContent).toMatch(/openai\/gpt-oss-120b/);
    const links = screen.getAllByRole("link", { name: "Ver esta pergunta na trilha" });
    expect(links.map((l) => l.getAttribute("href"))).toEqual([
      "/auditoria?recurso-tipo=interacao_assistente&recurso-id=i-1&token=tk",
      "/auditoria?recurso-tipo=interacao_assistente&recurso-id=i-2&token=tk",
    ]);
    expect(screen.getAllByText(/^Rita Campos · /)).toHaveLength(2);
    expect(screen.getByText("Está nas comissões.")).toBeTruthy();
    expect(screen.getByText(/Ficou sem resposta/)).toBeTruthy();
    // "Voltar à lista" (o caminho do celular) fecha a conversa e devolve o foco à linha
    fireEvent.click(screen.getByRole("button", { name: "Voltar à lista" }));
    expect(screen.getByRole("heading", { name: "Nenhuma conversa aberta" })).toBeTruthy();
    await waitFor(() => expect(document.activeElement?.textContent).toMatch(/Qual a situação do PL 11\/2026\?/));
  });

  it("'Ver conversas anteriores' continua de onde a lista parou", async () => {
    const f = rotas((u) => json(u.includes("antes=") ? ANTERIORES : DA_CASA()));
    vi.stubGlobal("fetch", f);
    render(<PaginaConversasDaClara />);
    fireEvent.click(await screen.findByRole("button", { name: "Ver conversas anteriores" }));
    expect(await screen.findByRole("button", { name: /O que vai ser votado amanhã\?/ })).toBeTruthy();
    expect(pedidos(f)).toContain("/api/agente/historico?escopo=casa&antes=2026-10-01T12%3A00%3A00Z");
    expect(screen.queryByRole("button", { name: "Ver conversas anteriores" })).toBeNull();
  });

  it("falha vira frase, sem código, com 'Tentar de novo'", async () => {
    let n = 0;
    const f = rotas(() => (n++ === 0 ? json({}, 503) : json(DA_CASA())));
    vi.stubGlobal("fetch", f);
    render(<PaginaConversasDaClara />);
    const erro = await screen.findByRole("alert");
    expect(erro.textContent).toMatch(/Não foi possível abrir o histórico agora/);
    expect(erro.textContent).not.toMatch(/503/);
    fireEvent.click(within(erro).getByRole("button", { name: "Tentar de novo" }));
    expect(await screen.findByRole("list", { name: "Hoje" })).toBeTruthy();
  });
});
