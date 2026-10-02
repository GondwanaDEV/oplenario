import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";

// Escrever um comunicado (ADR-0020): destinatários por tipo (grupo só para quem pode), validação com foco no primeiro
// erro, a revisão com "Vai para N pessoas", o POST no formato do fio, os anexos subindo um a um DEPOIS do 201, o aviso
// de quem ficou sem acesso, e a substituição (`?substitui=`).

const nav = vi.hoisted(() => ({ busca: "", replace: vi.fn() }));
vi.mock("@/lib/auth", () => ({ useAuth: () => ({ token: "tok" }), usePapeis: () => ({ papeis: ["secretario"], estado: "pronto" }) }));
vi.mock("next/navigation", () => ({
  useSearchParams: () => new URLSearchParams(nav.busca),
  useRouter: () => ({ replace: nav.replace, push: vi.fn() }),
}));
vi.mock("../../topo", () => ({ TopoInterno: ({ area }: { area: string }) => <div data-testid="topo">{area}</div> }));

import PaginaNovoComunicado from "./page";

const destinos = (grupos = true) => ({
  "pode-enviar-a-grupos": grupos,
  setores: [{ id: "s1", nome: "Jurídico", membros: 3 }],
  comissoes: [{ id: "k1", nome: "Comissão de Finanças", membros: 5 }],
  vereadores: [{ id: "v1", nome: "Helena Past" }],
  pessoas: [{ "identidade-id": "i1", nome: "Rita Campos" }, { "identidade-id": "i2", nome: "Ana Lima" }],
});

const criado = (extra: Record<string, unknown> = {}) => ({
  comunicado: { id: "c9", protocolo: "COM-2026-000009", assunto: "Sessão", corpo: "x", destinos: [], "exige-ciencia": true, ...extra },
  destinatarios: 7,
  "sem-acesso": 1,
});

type Resp = { status?: number; corpo?: unknown };
type Chamada = { chave: string; corpo: unknown };

function mockar(rotas: Record<string, Resp | Resp[]>) {
  const chamadas: Chamada[] = [];
  const filas = Object.fromEntries(Object.entries(rotas).map(([k, v]) => [k, Array.isArray(v) ? [...v] : v]));
  global.fetch = vi.fn(async (url: string, init?: RequestInit) => {
    const chave = `${init?.method ?? "GET"} ${url}`;
    const corpo = init?.body instanceof FormData ? init.body : init?.body ? JSON.parse(String(init.body)) : undefined;
    chamadas.push({ chave, corpo });
    const f = filas[chave];
    const r = Array.isArray(f) ? (f.length > 1 ? f.shift() : f[0]) : f;
    if (!r) return { ok: false, status: 404, json: async () => ({}) } as Response;
    const status = r.status ?? 200;
    return { ok: status < 300, status, json: async () => r.corpo ?? {} } as Response;
  }) as unknown as typeof fetch;
  return chamadas;
}

function escolher(rotulo: string | RegExp, valor: string) {
  fireEvent.change(screen.getByLabelText(rotulo), { target: { value: valor } });
}

async function adicionar(tipo: string, alvo?: string) {
  escolher("Tipo de destinatário", tipo);
  if (alvo) escolher(/^(Pessoa|Vereador|Setor|Comissão)$/, alvo);
  fireEvent.click(screen.getByRole("button", { name: "Adicionar" }));
}

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  nav.busca = "";
  nav.replace.mockReset();
});

describe("escrever comunicado", () => {
  it("quem não pode enviar a grupos só vê pessoa e vereador, e a tela diz por quê", async () => {
    mockar({ "GET /api/comunicados/destinos": { corpo: destinos(false) } });
    render(<PaginaNovoComunicado />);
    const tipo = await screen.findByLabelText("Tipo de destinatário");
    expect(Array.from((tipo as HTMLSelectElement).options).map((o) => o.textContent)).toEqual(["Uma pessoa", "Um vereador"]);
    expect(screen.getByText(/é da secretaria, da administração e da Mesa/)).toBeTruthy();
  });

  it("sem acesso para enviar (403 nas opções) a tela diz em frase", async () => {
    mockar({ "GET /api/comunicados/destinos": { status: 403 } });
    render(<PaginaNovoComunicado />);
    expect((await screen.findByRole("alert")).textContent).toContain("Seu acesso não permite enviar comunicados.");
  });

  it("enviar vazio aponta o que falta e põe o foco no primeiro campo com erro", async () => {
    mockar({ "GET /api/comunicados/destinos": { corpo: destinos() } });
    render(<PaginaNovoComunicado />);
    await screen.findByLabelText("Tipo de destinatário");
    fireEvent.click(screen.getByRole("button", { name: "Revisar o envio" }));
    expect(screen.getByText("Escolha pelo menos um destinatário.")).toBeTruthy();
    expect(screen.getByText(/Escreva o assunto/)).toBeTruthy();
    expect(screen.getByText("Escreva o texto do comunicado.")).toBeTruthy();
    expect(document.activeElement?.id).toBe("com-tipo");
    expect(screen.getByLabelText("Assunto").getAttribute("aria-invalid")).toBe("true");
  });

  it("adicionar sem escolher na lista explica; repetir destino explica; remover tira", async () => {
    mockar({ "GET /api/comunicados/destinos": { corpo: destinos() } });
    render(<PaginaNovoComunicado />);
    await screen.findByLabelText("Tipo de destinatário");
    await adicionar("setor");
    expect(screen.getByText("Escolha o setor na lista antes de adicionar.")).toBeTruthy();
    await adicionar("setor", "s1");
    await adicionar("setor", "s1");
    expect(screen.getByText("setor Jurídico já está na lista.")).toBeTruthy();
    const lista = screen.getByRole("list", { name: "Destinatários escolhidos" });
    expect(within(lista).getByText("setor Jurídico · 3 pessoas")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Remover setor Jurídico" }));
    expect(screen.queryByRole("list", { name: "Destinatários escolhidos" })).toBeNull();
  });

  it("o fluxo inteiro: alcance, revisão, POST no formato do fio, anexos um a um, e quem ficou sem acesso", async () => {
    const chamadas = mockar({
      "GET /api/comunicados/destinos": { corpo: destinos() },
      "POST /api/comunicados": { status: 201, corpo: criado() },
      "POST /api/comunicados/c9/anexos": [{ status: 201, corpo: {} }, { status: 413 }],
    });
    render(<PaginaNovoComunicado />);
    await screen.findByLabelText("Tipo de destinatário");
    await adicionar("comissao", "k1");
    await adicionar("pessoa", "i1");
    expect(screen.getByText("Vai para até 6 pessoas.")).toBeTruthy();
    fireEvent.change(screen.getByLabelText("Assunto"), { target: { value: "Reunião da comissão" } });
    fireEvent.change(screen.getByLabelText("Texto"), { target: { value: "Linha 1\nLinha 2" } });
    fireEvent.click(screen.getByLabelText(/Pedir ciência/));
    fireEvent.change(screen.getByLabelText("Prazo para a ciência (opcional)"), { target: { value: "2099-10-05T18:00" } });
    const a = new File(["a"], "pauta.pdf", { type: "application/pdf" });
    const b = new File(["b"], "planilha.xlsx");
    fireEvent.change(screen.getByLabelText(/Escolher arquivos/), { target: { files: [a, b] } });
    escolher("Item", "proposicao");
    fireEvent.change(screen.getByLabelText("Link ou identificador"), { target: { value: "https://camara.local/ficha-materia/0f8fad5b-d9cb-469f-a165-70867728950e" } });

    fireEvent.click(screen.getByRole("button", { name: "Revisar o envio" }));
    const revisao = await screen.findByRole("region", { name: "Confira antes de enviar" });
    expect(document.activeElement?.textContent).toBe("Confira antes de enviar");
    expect(within(revisao).getByText("Comissão de Finanças · 5 pessoas; Rita Campos")).toBeTruthy();
    expect(within(revisao).getByText("Vai para até 6 pessoas.")).toBeTruthy();
    expect(within(revisao).getByText(/^Pedida, até 05\/10\/2099/)).toBeTruthy();
    expect(within(revisao).getByText("pauta.pdf, planilha.xlsx")).toBeTruthy();

    fireEvent.click(within(revisao).getByRole("button", { name: "Enviar comunicado" }));
    expect(await screen.findByRole("heading", { name: "Comunicado COM-2026-000009 enviado" })).toBeTruthy();
    expect(screen.getByText(/Foi para 7 pessoas/)).toBeTruthy();
    expect(screen.getByText(/1 vereador da comissão ainda não tem acesso ao sistema e não vai receber este comunicado/)).toBeTruthy();

    const post = chamadas.find((c) => c.chave === "POST /api/comunicados")!;
    expect(post.corpo).toEqual({
      assunto: "Reunião da comissão", corpo: "Linha 1\nLinha 2", "exige-ciencia": true,
      "ciencia-ate": new Date("2099-10-05T18:00").toISOString(), "substitui-id": null,
      objeto: { tipo: "proposicao", id: "0f8fad5b-d9cb-469f-a165-70867728950e" },
      destinos: [{ tipo: "comissao", "alvo-id": "k1" }, { tipo: "pessoa", "alvo-id": "i1" }],
    });

    // os anexos sobem DEPOIS do 201, um por chamada, e o que falhou pode ser tentado de novo
    await waitFor(() => expect(screen.getByText(/não foi anexado: O arquivo passa de 10 MB/)).toBeTruthy());
    const anexos = chamadas.filter((c) => c.chave === "POST /api/comunicados/c9/anexos");
    expect(anexos.map((c) => ((c.corpo as FormData).get("arquivo") as File).name)).toEqual(["pauta.pdf", "planilha.xlsx"]);
    expect(chamadas.findIndex((c) => c.chave === "POST /api/comunicados")).toBeLessThan(chamadas.findIndex((c) => c.chave === "POST /api/comunicados/c9/anexos"));
    expect(screen.getByText("anexado")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Tentar de novo o anexo planilha.xlsx" }));
    await waitFor(() => expect(chamadas.filter((c) => c.chave === "POST /api/comunicados/c9/anexos")).toHaveLength(3));

    expect(screen.getByRole("link", { name: "Abrir o comunicado" }).getAttribute("href")).toBe("/comunicados/c9?de=enviados&token=tok");
  });

  it("'todos os setores' não pede alvo e o alcance é dito por extenso", async () => {
    mockar({ "GET /api/comunicados/destinos": { corpo: destinos() } });
    render(<PaginaNovoComunicado />);
    await screen.findByLabelText("Tipo de destinatário");
    escolher("Tipo de destinatário", "todos");
    expect(screen.queryByLabelText(/^(Pessoa|Setor)$/)).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Adicionar" }));
    expect(screen.getByText("Vai para todos os servidores e a administração da Casa.")).toBeTruthy();
  });

  it("a recusa do servidor no envio fica na revisão, com a causa", async () => {
    mockar({
      "GET /api/comunicados/destinos": { corpo: destinos() },
      "POST /api/comunicados": { status: 422, corpo: { erro: "a lista de destinatários resolveu vazia" } },
    });
    render(<PaginaNovoComunicado />);
    await screen.findByLabelText("Tipo de destinatário");
    await adicionar("pessoa", "i2");
    fireEvent.change(screen.getByLabelText("Assunto"), { target: { value: "A" } });
    fireEvent.change(screen.getByLabelText("Texto"), { target: { value: "B" } });
    fireEvent.click(screen.getByRole("button", { name: "Revisar o envio" }));
    fireEvent.click(await screen.findByRole("button", { name: "Enviar comunicado" }));
    expect((await screen.findByRole("alert")).textContent).toBe("A lista de destinatários resolveu vazia.");
    fireEvent.click(screen.getByRole("button", { name: "Voltar e editar" }));
    expect((screen.getByLabelText("Assunto") as HTMLInputElement).value).toBe("A");
  });

  it("prazo no passado não passa", async () => {
    mockar({ "GET /api/comunicados/destinos": { corpo: destinos() } });
    render(<PaginaNovoComunicado />);
    await screen.findByLabelText("Tipo de destinatário");
    await adicionar("pessoa", "i2");
    fireEvent.change(screen.getByLabelText("Assunto"), { target: { value: "A" } });
    fireEvent.change(screen.getByLabelText("Texto"), { target: { value: "B" } });
    fireEvent.click(screen.getByLabelText(/Pedir ciência/));
    fireEvent.change(screen.getByLabelText("Prazo para a ciência (opcional)"), { target: { value: "2020-01-01T10:00" } });
    fireEvent.click(screen.getByRole("button", { name: "Revisar o envio" }));
    expect(screen.getByText("O prazo precisa ser depois de agora.")).toBeTruthy();
    expect(document.activeElement?.id).toBe("com-prazo");
  });
});

describe("corrigir um comunicado (?substitui=)", () => {
  it("o substituto vem preenchido do original e o POST leva substitui-id", async () => {
    nav.busca = "substitui=c1";
    const chamadas = mockar({
      "GET /api/comunicados/destinos": { corpo: destinos() },
      "GET /api/comunicados/c1": {
        corpo: { id: "c1", protocolo: "COM-2026-000001", assunto: "Sessão na sexta", corpo: "Texto antigo", "exige-ciencia": false, destinos: [{ tipo: "setor", "alvo-id": "s1", "alvo-nome": "Jurídico" }] },
      },
      "POST /api/comunicados": { status: 201, corpo: criado() },
    });
    render(<PaginaNovoComunicado />);
    expect(await screen.findByRole("heading", { level: 1, name: "Corrigir um comunicado" })).toBeTruthy();
    expect(await screen.findByText("substituir o COM-2026-000001")).toBeTruthy();
    expect((screen.getByLabelText("Assunto") as HTMLInputElement).value).toBe("Sessão na sexta");
    expect(screen.getByText("setor Jurídico · 3 pessoas")).toBeTruthy();
    fireEvent.change(screen.getByLabelText("Texto"), { target: { value: "Texto corrigido" } });
    fireEvent.click(screen.getByRole("button", { name: "Revisar o envio" }));
    expect(await screen.findByText("COM-2026-000001")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Enviar comunicado" }));
    await screen.findByRole("heading", { name: "Comunicado COM-2026-000009 enviado" });
    const post = chamadas.find((c) => c.chave === "POST /api/comunicados")!.corpo as Record<string, unknown>;
    expect(post["substitui-id"]).toBe("c1");
    expect(post.corpo).toBe("Texto corrigido");
    // "Escrever outro" depois de uma substituição tira o ?substitui da URL
    fireEvent.click(screen.getByRole("button", { name: "Escrever outro" }));
    expect(nav.replace).toHaveBeenCalledWith("/comunicados/novo?token=tok");
  });

  it("o comunicado a substituir não abre: a tela diz e não mostra formulário", async () => {
    nav.busca = "substitui=c404";
    mockar({ "GET /api/comunicados/destinos": { corpo: destinos() } });
    render(<PaginaNovoComunicado />);
    expect((await screen.findByRole("alert")).textContent).toMatch(/Não foi possível abrir o comunicado a substituir/);
    expect(screen.queryByLabelText("Assunto")).toBeNull();
  });
});
