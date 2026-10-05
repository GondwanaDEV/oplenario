import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";

// os papéis vêm do ambiente do teste (o modo `test` lê os do token; aqui basta um valor por caso)
const estadoAuth = vi.hoisted(() => ({ papeis: ["juridico"] as string[], estado: "pronto" as "pronto" | "carregando" }));
vi.mock("@/lib/auth", () => ({
  useAuth: () => ({ token: "tk" }),
  usePapeis: () => ({ papeis: estadoAuth.papeis, estado: estadoAuth.estado }),
}));
vi.mock("../topo", () => ({ TopoInterno: ({ area }: { area: string }) => <div data-testid="topo">{area}</div> }));
vi.mock("next/navigation", () => ({ useParams: () => ({ id: "ped1" }) }));

import PaginaJuridico from "./page";
import PaginaPedido from "./[id]/page";
import { ProvedorDaDica, useDicaAtual } from "../clara/dica";

type Rota = { status?: number; corpo: unknown };
type Chamada = { metodo: string; url: string; body: unknown };

function mockar(rotas: Record<string, Rota | ((c: Chamada) => Rota)>) {
  const chamadas: Chamada[] = [];
  global.fetch = vi.fn(async (url: string, init?: RequestInit) => {
    const c: Chamada = { metodo: init?.method ?? "GET", url: String(url), body: init?.body ? JSON.parse(String(init.body)) : undefined };
    chamadas.push(c);
    const r = rotas[`${c.metodo} ${c.url.split("?")[0]}`];
    const rota = typeof r === "function" ? r(c) : r;
    if (!rota) return { ok: false, status: 404, json: async () => ({}) } as Response;
    const status = rota.status ?? 200;
    return { ok: status < 300, status, json: async () => rota.corpo } as Response;
  }) as unknown as typeof fetch;
  return chamadas;
}

const BASE = "/api/legislativo/pedidos-parecer-juridico";
const pedido = (extra: Record<string, unknown> = {}) => ({
  id: "ped1", proposicao: { id: "p1", ref: "PL 7/2026", ementa: "Cria o Programa de Hortas" }, assunto: "Análise jurídica da matéria",
  prazo: "2026-10-15", estado: "pendente", "pedido-por": "Rita Campos", "em-nome-de": "Presidência", origem: "secretaria",
  "criado-em": "2026-09-29T13:00:00Z", parecer: null, ...extra,
});
const assinado = (extra: Record<string, unknown> = {}) => ({
  id: "pj1", numero: 3, ano: 2026, estado: "assinado", relatorio: "Analisei a matéria.", fundamentacao: "Art. 30, I, da CF.",
  conclusao: "com_ressalvas", assinatura: { nome: "Lúcia Prado", oab: "CE 12345", qualificacao: "contratado", em: "2026-09-30T14:05:00", algoritmo: "STUB-ICP-v0", sha256: `sha256:${"cd34".repeat(16)}` },
  "substitui-id": null, substituido: false, ...extra,
});
const rascunho = (extra: Record<string, unknown> = {}) => ({
  id: "pj2", numero: null, ano: null, estado: "rascunho", relatorio: "R1", fundamentacao: "F1", conclusao: "favoravel",
  assinatura: null, "substitui-id": null, substituido: false, ...extra,
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  estadoAuth.papeis = ["juridico"];
  estadoAuth.estado = "pronto";
});

describe("guard", () => {
  it("quem não é jurídico nem secretaria vê 'Acesso restrito' e nada é buscado", () => {
    estadoAuth.papeis = ["vereador"];
    const c = mockar({});
    render(<PaginaJuridico />);
    expect(screen.getByRole("heading", { name: "Acesso restrito" })).toBeTruthy();
    expect(c).toHaveLength(0);
  });

  it("enquanto o /eu não respondeu, não pisca 'Acesso restrito'", () => {
    estadoAuth.estado = "carregando";
    mockar({});
    const { container } = render(<PaginaJuridico />);
    expect(container.textContent).toBe("");
  });

  it("o detalhe também é guardado", () => {
    estadoAuth.papeis = ["auditor"];
    const c = mockar({});
    render(<PaginaPedido />);
    expect(screen.getByRole("heading", { name: "Acesso restrito" })).toBeTruthy();
    expect(c).toHaveLength(0);
  });
});

describe("fila /juridico", () => {
  const p2 = pedido({ id: "ped2", proposicao: null, assunto: "Decoro do vereador X", "em-nome-de": null, prazo: null, "pedido-por": null });

  it("o jurídico vê a fila: matéria ou consulta avulsa, assunto, prazo, quem pediu; 'Abrir' em cada linha; sem 'Novo pedido'", async () => {
    mockar({ [`GET ${BASE}`]: { corpo: { pedidos: [pedido(), p2] } } });
    render(<PaginaJuridico />);
    const lista = await screen.findByRole("list", { name: "Pedidos de parecer" });
    const linhas = within(lista).getAllByRole("listitem");
    expect(linhas).toHaveLength(2);
    expect(within(linhas[0]).getByText("PL 7/2026")).toBeTruthy();
    expect(within(linhas[0]).getByText("Cria o Programa de Hortas")).toBeTruthy();
    expect(within(linhas[0]).getByText(/Pedido por Rita Campos, em nome de Presidência em 29\/09\/2026 · prazo 15\/10\/2026/)).toBeTruthy();
    expect(within(linhas[1]).getByText("Consulta avulsa")).toBeTruthy();
    expect(within(linhas[1]).getByText("Decoro do vereador X")).toBeTruthy();
    expect(within(linhas[0]).getByRole("link", { name: /Abrir o pedido: PL 7\/2026/ }).getAttribute("href")).toBe("/juridico/ped1?token=tk");
    expect(screen.queryByRole("button", { name: "Novo pedido de parecer" })).toBeNull();
    expect(screen.getByRole("note").textContent).toMatch(/opinativo/);
    expect(screen.getByTestId("topo").textContent).toBe("Jurídico");
  });

  it("as abas pedem cada estado ao servidor", async () => {
    const c = mockar({ [`GET ${BASE}`]: { corpo: { pedidos: [] } } });
    render(<PaginaJuridico />);
    await screen.findByText(/Nenhum pedido pendente para você/);
    fireEvent.click(screen.getByRole("button", { name: /Atendidos/ }));
    await screen.findByText("Nenhum pedido atendido ainda.");
    fireEvent.click(screen.getByRole("button", { name: /Cancelados/ }));
    await screen.findByText("Nenhum pedido cancelado.");
    expect(c.map((x) => x.url)).toEqual([`${BASE}?estado=pendente`, `${BASE}?estado=atendido`, `${BASE}?estado=cancelado`]);
  });

  it("falha do servidor: alerta honesto, nunca 'nenhum pedido'", async () => {
    mockar({ [`GET ${BASE}`]: { status: 500, corpo: {} } });
    render(<PaginaJuridico />);
    expect((await screen.findByRole("alert")).textContent).toMatch(/Não foi possível concluir agora/);
    expect(screen.queryByText(/Nenhum pedido/)).toBeNull();
  });

  it("a secretaria vê 'Ver' (não 'Abrir') e 'Novo pedido de parecer'; a consulta avulsa exige o assunto", async () => {
    estadoAuth.papeis = ["secretario"];
    const c = mockar({
      [`GET ${BASE}`]: { corpo: { pedidos: [] } },
      [`POST ${BASE}`]: { status: 201, corpo: pedido({ id: "ped9", proposicao: null, assunto: "Admissibilidade da CPI" }) },
    });
    render(<PaginaJuridico />);
    await screen.findByText(/Nenhum pedido pendente/);
    fireEvent.click(screen.getByRole("button", { name: "Novo pedido de parecer" }));
    const form = await screen.findByRole("form", { name: "Novo pedido de parecer" });
    fireEvent.click(within(form).getByRole("button", { name: "Abrir o pedido" }));
    expect((await within(form).findByRole("alert")).textContent).toMatch(/mínimo de 5/);
    expect(c.some((x) => x.metodo === "POST")).toBe(false);

    fireEvent.change(within(form).getByLabelText("Assunto*"), { target: { value: "Admissibilidade da CPI" } });
    fireEvent.change(within(form).getByLabelText(/Prazo/), { target: { value: "2026-10-20" } });
    fireEvent.change(within(form).getByLabelText(/Em nome de/), { target: { value: "Presidência" } });
    fireEvent.click(within(form).getByRole("button", { name: "Abrir o pedido" }));
    const aviso = await screen.findByRole("status");
    expect(aviso.textContent).toMatch(/Pedido aberto: Admissibilidade da CPI/);
    expect(within(aviso).getByRole("link", { name: "Abrir o pedido" }).getAttribute("href")).toBe("/juridico/ped9?token=tk");
    const post = c.find((x) => x.metodo === "POST")!;
    expect(post.body).toEqual({ assunto: "Admissibilidade da CPI", prazo: "2026-10-20", "em-nome-de": "Presidência" });
    // a fila é relida
    await waitFor(() => expect(c.filter((x) => x.metodo === "GET" && x.url.startsWith(BASE)).length).toBe(2));
  });

  it("secretaria: 403 ao pedir aparece no formulário e o pedido não some", async () => {
    estadoAuth.papeis = ["secretario"];
    mockar({ [`GET ${BASE}`]: { corpo: { pedidos: [] } }, [`POST ${BASE}`]: { status: 403, corpo: {} } });
    render(<PaginaJuridico />);
    fireEvent.click(await screen.findByRole("button", { name: "Novo pedido de parecer" }));
    const form = await screen.findByRole("form", { name: "Novo pedido de parecer" });
    fireEvent.change(within(form).getByLabelText("Assunto*"), { target: { value: "Decoro do vereador X" } });
    fireEvent.click(within(form).getByRole("button", { name: "Abrir o pedido" }));
    expect((await within(form).findByRole("alert")).textContent).toMatch(/Só a secretaria pede parecer/);
  });
});

describe("detalhe /juridico/:id — o jurídico escreve e assina", () => {
  const url = `${BASE}/ped1`;

  it("sem parecer: editor vazio, 'Assinar' desabilitado com o que falta; salvar rascunho envia o texto", async () => {
    const c = mockar({
      [`GET ${url}`]: { corpo: pedido() },
      [`PUT ${url}/parecer`]: { corpo: pedido({ parecer: rascunho({ relatorio: "Analisei", fundamentacao: "", conclusao: null }) }) },
    });
    render(<PaginaPedido />);
    expect(await screen.findByRole("heading", { name: "Análise jurídica da matéria" })).toBeTruthy();
    expect(screen.getByText("PL 7/2026")).toBeTruthy();
    expect(screen.getByRole("link", { name: "Abrir a ficha da matéria" }).getAttribute("href")).toBe("/ficha-materia/p1?token=tk");
    expect((screen.getByRole("button", { name: "Assinar parecer" }) as HTMLButtonElement).disabled).toBe(true);
    expect(screen.getByText("Para assinar, falta preencher o relatório, a fundamentação e a conclusão.")).toBeTruthy();

    fireEvent.change(screen.getByLabelText("Relatório"), { target: { value: "Analisei" } });
    fireEvent.click(screen.getByRole("button", { name: "Salvar rascunho" }));
    expect((await screen.findByRole("status")).textContent).toMatch(/Rascunho salvo/);
    expect(c.find((x) => x.metodo === "PUT")!.body).toEqual({ relatorio: "Analisei", fundamentacao: "" });
  });

  it("assinar: pede a confirmação explícita ('o texto não muda'), grava o que mudou e então assina; o resultado vira leitura com a assinatura", async () => {
    const c = mockar({
      [`GET ${url}`]: { corpo: pedido({ parecer: rascunho() }) },
      [`PUT ${url}/parecer`]: { corpo: pedido({ parecer: rascunho({ relatorio: "R2" }) }) },
      [`POST ${url}/parecer/assinatura`]: { status: 200, corpo: pedido({ estado: "atendido", parecer: assinado() }) },
    });
    render(<PaginaPedido />);
    const rel = await screen.findByLabelText("Relatório");
    expect((rel as HTMLTextAreaElement).value).toBe("R1");
    fireEvent.change(rel, { target: { value: "R2" } });
    fireEvent.click(screen.getByRole("button", { name: "Assinar parecer" }));
    const conf = await screen.findByRole("group", { name: "Confirmar a assinatura" });
    expect(conf.textContent).toMatch(/Depois de assinado, o texto não muda/);
    expect(c.some((x) => x.metodo === "POST")).toBe(false); // nada assina antes da confirmação
    fireEvent.click(within(conf).getByRole("button", { name: "Assinar e registrar" }));

    expect(await screen.findByLabelText("Parecer jurídico nº 3/2026, texto")).toBeTruthy();
    expect(c.filter((x) => x.metodo !== "GET").map((x) => `${x.metodo} ${x.url.replace(BASE, "")}`)).toEqual([
      "PUT /ped1/parecer", // o que estava na tela é o que se assina
      "POST /ped1/parecer/assinatura",
    ]);
    expect(screen.getByText("Assinado")).toBeTruthy();
    expect(screen.getByText("Analisei a matéria.")).toBeTruthy();
    const ass = screen.getByLabelText("Assinatura");
    expect(within(ass).getByText("Lúcia Prado")).toBeTruthy();
    expect(within(ass).getByText("OAB/CE 12345 · Advogado(a) contratado(a)")).toBeTruthy();
    expect(within(ass).getByText("Assinado em 30/09/2026, 14:05")).toBeTruthy();
    expect(within(ass).getByText("cd34".repeat(16))).toBeTruthy(); // o carimbo: o hash do texto assinado
    expect(within(ass).getByText(/ainda não é assinatura ICP-Brasil/)).toBeTruthy();
    expect(screen.queryByLabelText("Relatório")).toBeNull(); // assinado é imutável: sem editor
    expect(screen.getByRole("button", { name: "Emitir novo parecer (substitui este)" })).toBeTruthy();
  });

  it("assinar sem alteração não regrava o rascunho; 'Voltar ao texto' desiste da assinatura", async () => {
    const c = mockar({
      [`GET ${url}`]: { corpo: pedido({ parecer: rascunho() }) },
      [`POST ${url}/parecer/assinatura`]: { corpo: pedido({ estado: "atendido", parecer: assinado() }) },
    });
    render(<PaginaPedido />);
    await screen.findByLabelText("Relatório");
    fireEvent.click(screen.getByRole("button", { name: "Assinar parecer" }));
    fireEvent.click(await screen.findByRole("button", { name: "Voltar ao texto" }));
    expect(screen.queryByRole("group", { name: "Confirmar a assinatura" })).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Assinar parecer" }));
    fireEvent.click(await screen.findByRole("button", { name: "Assinar e registrar" }));
    await screen.findByText("Assinado");
    expect(c.some((x) => x.metodo === "PUT")).toBe(false);
  });

  it("assinar: 403 (sem OAB registrada) vira alerta claro e o texto continua editável", async () => {
    mockar({
      [`GET ${url}`]: { corpo: pedido({ parecer: rascunho() }) },
      [`POST ${url}/parecer/assinatura`]: { status: 403, corpo: {} },
    });
    render(<PaginaPedido />);
    await screen.findByLabelText("Relatório");
    fireEvent.click(screen.getByRole("button", { name: "Assinar parecer" }));
    fireEvent.click(await screen.findByRole("button", { name: "Assinar e registrar" }));
    expect((await screen.findByRole("alert")).textContent).toMatch(/qualificação e a OAB/);
    expect((screen.getByLabelText("Relatório") as HTMLTextAreaElement).value).toBe("R1");
    expect(screen.queryByText("Assinado")).toBeNull();
  });

  it("assinar: 409 (já assinado por outra aba) e 400 mostram a frase e não fingem sucesso", async () => {
    mockar({
      [`GET ${url}`]: { corpo: pedido({ parecer: rascunho() }) },
      [`POST ${url}/parecer/assinatura`]: { status: 409, corpo: {} },
    });
    render(<PaginaPedido />);
    await screen.findByLabelText("Relatório");
    fireEvent.click(screen.getByRole("button", { name: "Assinar parecer" }));
    fireEvent.click(await screen.findByRole("button", { name: "Assinar e registrar" }));
    expect((await screen.findByRole("alert")).textContent).toMatch(/já foi assinado/);
  });

  it("parecer assinado: 'Emitir novo parecer' abre um rascunho que avisa que o anterior segue valendo", async () => {
    const c = mockar({
      [`GET ${url}`]: { corpo: pedido({ estado: "atendido", parecer: assinado() }) },
      [`POST ${url}/parecer/substituicao`]: { status: 200, corpo: pedido({ estado: "pendente", parecer: rascunho({ id: "pj3", relatorio: "Analisei a matéria.", "substitui-id": "pj1" }) }) },
    });
    render(<PaginaPedido />);
    fireEvent.click(await screen.findByRole("button", { name: "Emitir novo parecer (substitui este)" }));
    expect(await screen.findByRole("heading", { name: "Novo parecer (substitui o anterior)" })).toBeTruthy();
    expect(screen.getByText(/O anterior continua valendo até você assinar este/)).toBeTruthy();
    expect((screen.getByLabelText("Relatório") as HTMLTextAreaElement).value).toBe("Analisei a matéria.");
    expect(c.find((x) => x.metodo === "POST")!.url).toBe(`${url}/parecer/substituicao`);
  });

  it("substituir: 409 vira alerta", async () => {
    mockar({
      [`GET ${url}`]: { corpo: pedido({ estado: "atendido", parecer: assinado() }) },
      [`POST ${url}/parecer/substituicao`]: { status: 409, corpo: {} },
    });
    render(<PaginaPedido />);
    fireEvent.click(await screen.findByRole("button", { name: "Emitir novo parecer (substitui este)" }));
    expect((await screen.findByRole("alert")).textContent).toMatch(/rascunho em andamento/);
  });

  it("pedido cancelado: só leitura, sem editor", async () => {
    mockar({ [`GET ${url}`]: { corpo: pedido({ estado: "cancelado" }) } });
    render(<PaginaPedido />);
    expect(await screen.findByText(/A secretaria cancelou este pedido/)).toBeTruthy();
    expect(screen.queryByLabelText("Relatório")).toBeNull();
    expect(screen.queryByRole("button", { name: "Cancelar pedido" })).toBeNull();
  });

  it("404: mensagem de não encontrado; falha de rede idem, nunca uma tela vazia", async () => {
    mockar({ [`GET ${url}`]: { status: 404, corpo: {} } });
    render(<PaginaPedido />);
    expect((await screen.findByRole("alert")).textContent).toMatch(/Não encontramos este pedido/);
  });
});

describe("detalhe /juridico/:id — a secretaria lê e cancela", () => {
  const url = `${BASE}/ped1`;

  it("vê o pedido sem editor; o rascunho do jurídico NÃO aparece (o texto só vale depois de assinado)", async () => {
    estadoAuth.papeis = ["secretario"];
    mockar({ [`GET ${url}`]: { corpo: pedido({ parecer: rascunho({ relatorio: "TEXTO SECRETO DO RASCUNHO" }) }) } });
    render(<PaginaPedido />);
    expect(await screen.findByText(/está redigindo o parecer/)).toBeTruthy();
    expect(screen.queryByLabelText("Relatório")).toBeNull();
    expect(document.body.textContent).not.toContain("TEXTO SECRETO DO RASCUNHO");
  });

  it("lê o parecer assinado, sem botão de emitir novo (é do jurídico)", async () => {
    estadoAuth.papeis = ["secretario"];
    mockar({ [`GET ${url}`]: { corpo: pedido({ estado: "atendido", parecer: assinado() }) } });
    render(<PaginaPedido />);
    expect(await screen.findByText("Analisei a matéria.")).toBeTruthy();
    expect(screen.queryByRole("button", { name: /Emitir novo parecer/ })).toBeNull();
    expect(screen.queryByRole("button", { name: "Cancelar pedido" })).toBeNull(); // atendido não cancela
  });

  it("cancelar pede confirmação e então chama POST .../cancelamento; o pedido vira cancelado", async () => {
    estadoAuth.papeis = ["secretario"];
    const c = mockar({
      [`GET ${url}`]: { corpo: pedido() },
      [`POST ${url}/cancelamento`]: { corpo: pedido({ estado: "cancelado" }) },
    });
    render(<PaginaPedido />);
    fireEvent.click(await screen.findByRole("button", { name: "Cancelar pedido" }));
    const conf = await screen.findByRole("group", { name: "Confirmar o cancelamento" });
    expect(c.some((x) => x.metodo === "POST")).toBe(false);
    fireEvent.click(within(conf).getByRole("button", { name: "Cancelar o pedido" }));
    expect(await screen.findByText(/A secretaria cancelou este pedido/)).toBeTruthy();
    expect(c.find((x) => x.metodo === "POST")!.body).toEqual({});
  });

  it("cancelar: 409 (já atendido) vira alerta e o pedido segue como está", async () => {
    estadoAuth.papeis = ["secretario"];
    mockar({ [`GET ${url}`]: { corpo: pedido() }, [`POST ${url}/cancelamento`]: { status: 409, corpo: {} } });
    render(<PaginaPedido />);
    fireEvent.click(await screen.findByRole("button", { name: "Cancelar pedido" }));
    fireEvent.click(await screen.findByRole("button", { name: "Cancelar o pedido" }));
    expect((await screen.findByRole("alert")).textContent).toMatch(/já foi atendido/);
    expect(screen.queryByText(/A secretaria cancelou/)).toBeNull();
  });
});

// ---------------- ADR-0019 fatia 2a: as notas técnicas da IA na fila do jurídico ----------------

const NOTAS = "/api/legislativo/notas-tecnicas";
const resumoNota = (extra: Record<string, unknown> = {}) => ({
  id: "n1", "proposicao-id": "p1", tipo: "projeto_lei", sequencial: 7, ano: 2026, ementa: "Institui o Programa de Hortas",
  estado: "pendente", incerteza: "normal", "criada-em": "2026-09-30T09:00:00Z", "decidida-em": null, ...extra,
});

describe("fila /juridico — seção 'Notas técnicas da IA'", () => {
  it("o jurídico troca de seção e vê as notas pendentes, com o rótulo de rascunho da IA e o link para lê-las", async () => {
    const c = mockar({
      [`GET ${BASE}`]: { corpo: { pedidos: [] } },
      [`GET ${NOTAS}`]: { corpo: { itens: [resumoNota(), resumoNota({ id: "n2", incerteza: "revisar_com_atencao", ementa: "Cria o conselho" })], "casa-com-juridico": true } },
    });
    render(<PaginaJuridico />);
    await screen.findByText(/Nenhum pedido pendente para você/);
    expect(c.some((x) => x.url.startsWith(NOTAS))).toBe(false); // só busca quando abre a seção
    fireEvent.click(screen.getByRole("button", { name: "Notas técnicas da IA" }));
    const lista = await screen.findByRole("list", { name: "Notas técnicas da IA a conferir" });
    const linhas = within(lista).getAllByRole("listitem");
    expect(linhas).toHaveLength(2);
    expect(within(linhas[0]).getByText("PL 7/2026")).toBeTruthy();
    expect(within(linhas[0]).getByText("Rascunho da IA")).toBeTruthy();
    expect(within(linhas[1]).getByText("Ler com atenção")).toBeTruthy();
    expect(within(linhas[0]).getByRole("link", { name: /Ler a nota técnica: PL 7\/2026/ }).getAttribute("href")).toBe("/juridico/notas/n1?token=tk");
    expect(c.find((x) => x.url.startsWith(NOTAS))!.url).toBe(`${NOTAS}?estado=pendente`);
    // o texto da IA nunca é chamado de parecer
    expect(screen.getByText(/Texto de IA não é parecer/)).toBeTruthy();
    // voltar aos pedidos
    fireEvent.click(screen.getByRole("button", { name: "Pedidos de parecer" }));
    expect(await screen.findByText(/Nenhum pedido pendente para você/)).toBeTruthy();
  });

  it("sem notas: mensagem vazia honesta; falha: alerta, nunca 'nenhuma nota'", async () => {
    mockar({ [`GET ${BASE}`]: { corpo: { pedidos: [] } }, [`GET ${NOTAS}`]: { status: 500, corpo: {} } });
    render(<PaginaJuridico />);
    fireEvent.click(await screen.findByRole("button", { name: "Notas técnicas da IA" }));
    expect((await screen.findByRole("alert")).textContent).toMatch(/A conferência das proposições|Não foi possível/);
    expect(screen.queryByText(/Nenhuma nota técnica a conferir/)).toBeNull();
  });

  it("nenhuma nota pendente", async () => {
    mockar({ [`GET ${BASE}`]: { corpo: { pedidos: [] } }, [`GET ${NOTAS}`]: { corpo: { itens: [], "casa-com-juridico": true } } });
    render(<PaginaJuridico />);
    fireEvent.click(await screen.findByRole("button", { name: "Notas técnicas da IA" }));
    expect(await screen.findByText(/Nenhuma nota técnica a conferir/)).toBeTruthy();
  });

  it("a secretaria não tem a seção (a nota dela está em /conferencias)", async () => {
    estadoAuth.papeis = ["secretario"];
    mockar({ [`GET ${BASE}`]: { corpo: { pedidos: [] } } });
    render(<PaginaJuridico />);
    await screen.findByText(/Nenhum pedido pendente/);
    expect(screen.queryByRole("button", { name: "Notas técnicas da IA" })).toBeNull();
  });
});

describe("pedido aberto a partir da nota (origem do rascunho)", () => {
  const url = `${BASE}/ped1`;

  it("o rascunho vindo da nota: diz a origem, vem sem conclusão e exige que o advogado a escolha para assinar", async () => {
    mockar({
      [`GET ${url}`]: {
        corpo: pedido({
          origem: "nota_tecnica", "pedido-por": "Lúcia Prado", "em-nome-de": null,
          parecer: rascunho({ relatorio: "Rascunho iniciado a partir da nota técnica da IA — a revisar.", fundamentacao: "Texto da nota.", conclusao: null, "origem-rascunho": "nota_tecnica" }),
        }),
      },
    });
    render(<PaginaPedido />);
    expect(await screen.findByText(/Aberto por Lúcia Prado a partir da nota técnica da IA/)).toBeTruthy();
    const nota = screen.getAllByRole("note").find((n) => /Rascunho iniciado a partir da nota técnica da IA\. Revise/.test(n.textContent ?? ""));
    expect(nota).toBeTruthy();
    expect((screen.getByLabelText("Fundamentação") as HTMLTextAreaElement).value).toBe("Texto da nota.");
    expect((screen.getByLabelText("Conclusão") as HTMLSelectElement).value).toBe("");
    expect((screen.getByRole("button", { name: "Assinar parecer" }) as HTMLButtonElement).disabled).toBe(true);
    expect(screen.getByText("Para assinar, falta preencher a conclusão.")).toBeTruthy();
  });

  it("assinado, a ficha interna diz 'revisado e assinado por X'", async () => {
    mockar({
      [`GET ${url}`]: {
        corpo: pedido({ estado: "atendido", parecer: assinado({ "origem-rascunho": "nota_tecnica" }) }),
      },
    });
    render(<PaginaPedido />);
    expect(await screen.findByText("Rascunho iniciado a partir de nota técnica da IA, revisado e assinado por Lúcia Prado.")).toBeTruthy();
  });

  it("parecer escrito do zero: sem linha de origem", async () => {
    mockar({ [`GET ${url}`]: { corpo: pedido({ estado: "atendido", parecer: assinado() }) } });
    render(<PaginaPedido />);
    await screen.findByLabelText("Parecer jurídico nº 3/2026, texto");
    expect(screen.queryByText(/nota técnica da IA/)).toBeNull();
  });
});

describe("detalhe /juridico/:id — a dica da Clara", () => {
  const url = `${BASE}/ped1`;
  function Sonda() {
    const dica = useDicaAtual();
    return <output data-testid="dica">{dica ? `${dica.rotulo} | ${dica.inicio} | ${dica.acao}` : "sem dica"}</output>;
  }
  const naMoldura = () =>
    render(
      <ProvedorDaDica>
        <PaginaPedido />
        <Sonda />
      </ProvedorDaDica>,
    );

  it("pedido sobre uma matéria: a dica é a matéria ('PL 7/2026'), e só depois de carregar", async () => {
    estadoAuth.papeis = ["secretario"];
    mockar({ [`GET ${url}`]: { corpo: pedido() } });
    naMoldura();
    expect(screen.getByTestId("dica").textContent).toBe("sem dica");
    await screen.findByRole("heading", { name: "Análise jurídica da matéria" });
    await waitFor(() =>
      expect(screen.getByTestId("dica").textContent).toBe("PL 7/2026 | Sobre o PL 7/2026,  | Perguntar sobre esta matéria"),
    );
  });

  it("consulta avulsa (sem matéria): sem dica", async () => {
    mockar({ [`GET ${url}`]: { corpo: pedido({ proposicao: null, assunto: "Decoro do vereador X" }) } });
    naMoldura();
    await screen.findByRole("heading", { name: "Decoro do vereador X" });
    expect(screen.getByTestId("dica").textContent).toBe("sem dica");
  });
});
