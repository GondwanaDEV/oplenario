import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";

// ADR-0021 Parte B — as telas internas das contas: a lista, o registro (que protocola o PDL e leva à ficha) e a ficha
// (o parecer, "N de M", os prazos, a notificação, a defesa, os documentos e o "Incluir em pauta" habilitado só quando o
// servidor diz `pautavel`). O fio é kebab-case, como o backend fala.

const estadoAuth = vi.hoisted(() => ({ papeis: ["secretario"] as string[], estado: "pronto" as "pronto" | "carregando" }));
const nav = vi.hoisted(() => ({ push: vi.fn(), params: new URLSearchParams() }));
vi.mock("@/lib/auth", () => ({
  useAuth: () => ({ token: "tk" }),
  usePapeis: () => ({ papeis: estadoAuth.papeis, estado: estadoAuth.estado }),
}));
vi.mock("../topo", () => ({ TopoInterno: ({ area }: { area: string }) => <div data-testid="topo">{area}</div> }));
vi.mock("../../topo", () => ({ TopoInterno: ({ area }: { area: string }) => <div data-testid="topo">{area}</div> }));
vi.mock("next/navigation", () => ({
  useParams: () => ({ id: "pc1" }),
  useSearchParams: () => nav.params,
  useRouter: () => ({ push: nav.push }),
}));

import PaginaContas from "./page";
import PaginaNovaPrestacao from "./nova/page";
import PaginaPrestacao from "./[id]/page";

type Rota = { status?: number; corpo: unknown };
type Chamada = { metodo: string; url: string; body: unknown };

function mockar(rotas: Record<string, Rota | ((c: Chamada) => Rota)>) {
  const chamadas: Chamada[] = [];
  global.fetch = vi.fn(async (url: string, init?: RequestInit) => {
    const b = init?.body;
    const c: Chamada = { metodo: init?.method ?? "GET", url: String(url), body: b instanceof FormData ? b : b ? JSON.parse(String(b)) : undefined };
    chamadas.push(c);
    const r = rotas[`${c.metodo} ${c.url}`] ?? rotas[`${c.metodo} ${c.url.split("?")[0]}`];
    const rota = typeof r === "function" ? r(c) : r;
    if (!rota) return { ok: false, status: 404, json: async () => ({}) } as Response;
    const status = rota.status ?? 200;
    return { ok: status < 300, status, json: async () => rota.corpo } as Response;
  }) as unknown as typeof fetch;
  return chamadas;
}

const prestacao = (extra: Record<string, unknown> = {}) => ({
  id: "pc1", tipo: "governo_prefeito", exercicio: 2024, responsavel: "José Sarto", "parecer-previo": "favoravel_com_ressalvas",
  estado: "aguardando_notificacao", resultado: null, "prazo-julgamento-ate": "2026-12-01", "recebida-em": "2026-10-02",
  "processo-tce": "12345/2025", proposicao: { id: "p9", rotulo: "PDL 3/2026", estado: "em_comissao" },
  "notificado-em": null, "notificacao-meio": null, "prazo-defesa-ate": null, "defesa-juntada-em": null,
  quorum: { "base-membros": 21, "necessarios-para-rejeitar": 14 }, votacao: null, "frase-resultado": null,
  pautavel: false, "motivo-nao-pautavel": "falta notificar o responsável",
  documentos: [{ id: "d1", tipo: "relatorio_tce", nome: "relatorio.pdf", "tamanho-bytes": 2048, "criado-em": "2026-10-02T12:00:00Z" }],
  ...extra,
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  estadoAuth.papeis = ["secretario"];
  estadoAuth.estado = "pronto";
  nav.push.mockReset();
  nav.params = new URLSearchParams();
});

describe("guard", () => {
  it("quem só administra vê 'Acesso restrito' e nada é buscado", () => {
    estadoAuth.papeis = ["admin_ente"];
    const c = mockar({});
    render(<PaginaContas />);
    expect(screen.getByRole("heading", { name: "Acesso restrito" })).toBeTruthy();
    render(<PaginaPrestacao />);
    expect(c).toHaveLength(0);
  });

  it("o registro é só da secretaria", () => {
    estadoAuth.papeis = ["vereador"];
    mockar({});
    render(<PaginaNovaPrestacao />);
    expect(screen.getByRole("heading", { name: "Acesso restrito" })).toBeTruthy();
  });
});

describe("lista /contas", () => {
  const lista = {
    prestacoes: [
      { id: "m1", tipo: "gestao_camara", exercicio: 2025, responsavel: "Presidente X", estado: "acompanhamento" },
      { id: "pc1", tipo: "governo_prefeito", exercicio: 2024, responsavel: "José Sarto", "parecer-previo": "desfavoravel", estado: "pronta_para_pauta", "prazo-julgamento-ate": "2026-12-01" },
    ],
  };

  it("secretaria: governo primeiro, estado e parecer em palavras, 'Registrar prestação'", async () => {
    mockar({ "GET /api/contas": { corpo: lista } });
    render(<PaginaContas />);
    const ul = await screen.findByRole("list", { name: "Prestações de contas" });
    const linhas = within(ul).getAllByRole("listitem");
    expect(linhas).toHaveLength(2);
    expect(within(linhas[0]).getByText("Contas de governo do Prefeito · exercício 2024")).toBeTruthy();
    expect(within(linhas[0]).getByText("Pronta para pauta")).toBeTruthy();
    expect(within(linhas[0]).getByText("Parecer prévio do TCE: Desfavorável")).toBeTruthy();
    expect(within(linhas[0]).getByText(/julgar até 01\/12\/2026/)).toBeTruthy();
    expect(within(linhas[1]).getByText("Acompanhamento")).toBeTruthy();
    expect(screen.getByRole("link", { name: "Registrar prestação" }).getAttribute("href")).toBe("/contas/nova?token=tk");
    expect(within(linhas[0]).getByRole("link", { name: /Abrir/ }).getAttribute("href")).toBe("/contas/pc1?token=tk");
  });

  it("vereador lê, sem 'Registrar prestação'; erro vira frase", async () => {
    estadoAuth.papeis = ["vereador"];
    mockar({ "GET /api/contas": { corpo: { prestacoes: [] } } });
    render(<PaginaContas />);
    expect(await screen.findByText("Nenhuma prestação de contas registrada nesta Casa.")).toBeTruthy();
    expect(screen.queryByRole("link", { name: "Registrar prestação" })).toBeNull();
    cleanup();
    mockar({ "GET /api/contas": { status: 403, corpo: {} } });
    render(<PaginaContas />);
    expect((await screen.findByRole("alert")).textContent).toMatch(/secretaria, pelos vereadores e pelo jurídico/);
  });
});

describe("registrar /contas/nova", () => {
  it("governo: confere antes, envia o corpo kebab com a comissão autora e leva à ficha", async () => {
    const c = mockar({
      "GET /api/legislativo/comissoes": { corpo: { comissoes: [{ id: "c1", nome: "Comissão de Finanças" }] } },
      "POST /api/contas": { status: 201, corpo: prestacao() },
    });
    render(<PaginaNovaPrestacao />);
    expect(screen.getByRole("note").textContent).toMatch(/protocola o Projeto de Decreto Legislativo/);
    fireEvent.click(screen.getByRole("button", { name: "Registrar e protocolar o PDL" }));
    expect(await screen.findByText("Escolha o parecer prévio do TCE.")).toBeTruthy();
    expect(c.filter((x) => x.metodo === "POST")).toHaveLength(0);

    fireEvent.change(screen.getByLabelText("Exercício"), { target: { value: "2024" } });
    fireEvent.change(screen.getByLabelText("Recebida pela Câmara em"), { target: { value: "2026-10-02" } });
    fireEvent.change(screen.getByLabelText("Prefeito responsável pelo exercício"), { target: { value: "José Sarto" } });
    fireEvent.click(screen.getByRole("radio", { name: "Favorável com ressalvas" }));
    await screen.findByRole("option", { name: "Comissão de Finanças" });
    fireEvent.change(screen.getByLabelText("Comissão autora do Projeto de Decreto Legislativo"), { target: { value: "c1" } });
    fireEvent.click(screen.getByRole("button", { name: "Registrar e protocolar o PDL" }));
    await waitFor(() => expect(nav.push).toHaveBeenCalledWith("/contas/pc1?registrada=1&token=tk"));
    expect(c.find((x) => x.metodo === "POST")?.body).toEqual({
      tipo: "governo_prefeito", exercicio: 2024, responsavel: "José Sarto", "recebida-em": "2026-10-02", "processo-tce": null,
      "parecer-previo": "favoravel_com_ressalvas", "comissao-autora-id": "c1",
    });
  });

  it("Mesa: sem parecer nem comissão; situação no TCE; recusa do servidor vira frase", async () => {
    const c = mockar({ "POST /api/contas": { status: 409, corpo: { erro: "já existe prestação deste tipo para o exercício 2024" } } });
    render(<PaginaNovaPrestacao />);
    fireEvent.click(screen.getByRole("radio", { name: /Contas de gestão da Câmara/ }));
    expect(screen.queryByLabelText("Comissão autora do Projeto de Decreto Legislativo")).toBeNull();
    fireEvent.change(screen.getByLabelText("Exercício"), { target: { value: "2024" } });
    fireEvent.change(screen.getByLabelText("Recebida pela Câmara em"), { target: { value: "2026-10-02" } });
    fireEvent.change(screen.getByLabelText("Presidente da Câmara no exercício"), { target: { value: "Presidente X" } });
    fireEvent.change(screen.getByLabelText("Situação no TCE (opcional)"), { target: { value: "Em instrução" } });
    fireEvent.click(screen.getByRole("button", { name: "Registrar prestação" }));
    expect((await screen.findByRole("alert")).textContent).toBe("Já existe prestação deste tipo para o exercício 2024.");
    expect(c.find((x) => x.metodo === "POST")?.body).toMatchObject({ tipo: "gestao_camara", "situacao-tce": "Em instrução" });
    expect(nav.push).not.toHaveBeenCalled();
  });
});

describe("ficha /contas/:id", () => {
  it("o parecer, 'N de M' do quórum, a CF art. 31 §2, o [GAP] honesto e o PDL", async () => {
    mockar({ "GET /api/contas/pc1": { corpo: prestacao() } });
    render(<PaginaPrestacao />);
    expect(await screen.findByRole("heading", { name: "Julgamento das contas do Prefeito" })).toBeTruthy();
    expect(screen.getByText("Favorável à aprovação")).toBeTruthy();
    expect(screen.getByText("com ressalvas e recomendações")).toBeTruthy();
    expect(screen.getByRole("img", { name: /14 votos de 21 membros/ })).toBeTruthy();
    expect(screen.getByText(/14 dos 21/)).toBeTruthy();
    expect(screen.getByText(/só deixará de prevalecer por decisão de dois terços dos membros da Câmara/)).toBeTruthy();
    expect(screen.getByText(/não são reproduzidos nesta tela/)).toBeTruthy();
    expect(screen.getByRole("link", { name: "PDL 3/2026" }).getAttribute("href")).toBe("/ficha-materia/p9?token=tk");
    expect(screen.getByRole("button", { name: "Abrir o relatório do TCE: relatorio.pdf" })).toBeTruthy();
  });

  it("não pautável: 'Incluir em pauta' desabilitado, com o motivo em palavras ligado ao botão", async () => {
    mockar({
      "GET /api/contas/pc1": {
        corpo: prestacao({ estado: "prazo_de_defesa", "notificado-em": "2026-10-03", "prazo-defesa-ate": "2026-10-18", "motivo-nao-pautavel": "o prazo de defesa vai até 18/10/2026" }),
      },
    });
    render(<PaginaPrestacao />);
    const botao = (await screen.findByRole("button", { name: "Incluir em pauta" })) as HTMLButtonElement;
    expect(botao.disabled).toBe(true);
    const motivo = document.getElementById(botao.getAttribute("aria-describedby")!)!;
    expect(motivo.textContent).toBe("O prazo de defesa vai até 18/10/2026.");
    expect(screen.getAllByText(/até 18\/10\/2026/).length).toBeGreaterThan(0);
    expect(screen.queryByRole("link", { name: "Incluir em pauta" })).toBeNull();
  });

  it("pautável: 'Incluir em pauta' leva à montagem da pauta", async () => {
    mockar({ "GET /api/contas/pc1": { corpo: prestacao({ estado: "pronta_para_pauta", pautavel: true, "motivo-nao-pautavel": null }) } });
    render(<PaginaPrestacao />);
    const link = await screen.findByRole("link", { name: "Incluir em pauta" });
    expect(link.getAttribute("href")).toBe("/pauta-convocacao?token=tk");
    expect(screen.queryByRole("button", { name: "Incluir em pauta" })).toBeNull();
  });

  it("vereador lê o motivo, sem botão de pauta nem formulários", async () => {
    estadoAuth.papeis = ["vereador"];
    mockar({ "GET /api/contas/pc1": { corpo: prestacao() } });
    render(<PaginaPrestacao />);
    expect(await screen.findByText("Falta notificar o responsável.")).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Incluir em pauta" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Registrar notificação" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Juntar documento" })).toBeNull();
  });

  it("registrar a notificação: data + meio, depois recarrega", async () => {
    let n = 0;
    const c = mockar({
      "GET /api/contas/pc1": () => ({ corpo: n++ === 0 ? prestacao() : prestacao({ estado: "prazo_de_defesa", "notificado-em": "2026-10-03", "notificacao-meio": "Edital", "prazo-defesa-ate": "2026-10-18" }) }),
      "POST /api/contas/pc1/notificacao": { corpo: {} },
    });
    render(<PaginaPrestacao />);
    fireEvent.click(await screen.findByRole("button", { name: "Registrar notificação" }));
    expect((await screen.findByRole("alert")).textContent).toBe("Informe a data da notificação.");
    fireEvent.change(screen.getByLabelText("Notificado em"), { target: { value: "2026-10-03" } });
    fireEvent.change(screen.getByLabelText("Meio"), { target: { value: "Edital" } });
    fireEvent.click(screen.getByRole("button", { name: "Registrar notificação" }));
    expect(await screen.findByText(/Notificado em/)).toBeTruthy();
    expect(c.find((x) => x.metodo === "POST")?.body).toEqual({ "notificado-em": "2026-10-03", meio: "Edital" });
    expect(await screen.findByText(/O prazo de defesa vai até 18\/10\/2026\./)).toBeTruthy();
  });

  it("juntar a defesa: multipart com o campo 'arquivo' e tipo=defesa", async () => {
    const c = mockar({
      "GET /api/contas/pc1": { corpo: prestacao({ estado: "prazo_de_defesa", "notificado-em": "2026-10-03", "prazo-defesa-ate": "2026-10-18" }) },
      "POST /api/contas/pc1/documentos?tipo=defesa": { status: 201, corpo: {} },
    });
    render(<PaginaPrestacao />);
    const input = await screen.findByLabelText("Arquivo da defesa escrita (até 10 MB)");
    fireEvent.change(input, { target: { files: [new File(["x"], "defesa.pdf", { type: "application/pdf" })] } });
    fireEvent.click(screen.getByRole("button", { name: "Juntar a defesa" }));
    expect(await screen.findByText("Defesa juntada. A matéria pode ir à pauta.")).toBeTruthy();
    const post = c.find((x) => x.metodo === "POST")!;
    expect(post.url).toBe("/api/contas/pc1/documentos?tipo=defesa");
    expect(((post.body as FormData).get("arquivo") as File).name).toBe("defesa.pdf");
  });

  it("julgada: a frase do resultado em destaque, sem 'Incluir em pauta'", async () => {
    mockar({
      "GET /api/contas/pc1": {
        corpo: prestacao({ estado: "julgada", resultado: "parecer_mantido", votacao: { id: "v1", sim: 12, nao: 8, abstencao: 1 }, "julgada-em": "2026-11-20T15:00:00Z",
          "frase-resultado": "O parecer prevalece: 12 votos pela rejeição, eram precisos 14." }),
      },
    });
    render(<PaginaPrestacao />);
    expect(await screen.findByText("O parecer prevalece: 12 votos pela rejeição, eram precisos 14.")).toBeTruthy();
    expect(screen.getByText(/12 pela rejeição \(Sim\) · 8 contra \(Não\) · 1 abstenção/)).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Incluir em pauta" })).toBeNull();
    expect(screen.queryByRole("link", { name: "Incluir em pauta" })).toBeNull();
  });

  it("recém-registrada: diz que o PDL foi protocolado", async () => {
    nav.params = new URLSearchParams("registrada=1");
    mockar({ "GET /api/contas/pc1": { corpo: prestacao() } });
    render(<PaginaPrestacao />);
    expect((await screen.findByText(/foi protocolado e já tramita/)).textContent).toMatch(/PDL 3\/2026 foi protocolado/);
  });

  it("contas da Mesa: acompanhamento, situação editável por PATCH, sem pauta nem quórum", async () => {
    const c = mockar({
      "GET /api/contas/pc1": { corpo: prestacao({ tipo: "gestao_camara", estado: "acompanhamento", "parecer-previo": null, proposicao: null, quorum: null, "situacao-tce": "Em instrução" }) },
      "PATCH /api/contas/pc1": { corpo: {} },
    });
    render(<PaginaPrestacao />);
    expect(await screen.findByRole("heading", { name: "Contas de gestão da Câmara" })).toBeTruthy();
    expect(screen.getByText("Em instrução")).toBeTruthy();
    expect(screen.queryByText(/Como a Câmara decide/)).toBeNull();
    expect(screen.queryByRole("button", { name: "Incluir em pauta" })).toBeNull();
    fireEvent.change(screen.getByLabelText("Situação no TCE"), { target: { value: "Julgada regular" } });
    fireEvent.click(screen.getByRole("button", { name: "Salvar" }));
    expect(await screen.findByText("Atualizado.")).toBeTruthy();
    expect(c.find((x) => x.metodo === "PATCH")?.body).toEqual({ "processo-tce": "12345/2025", "situacao-tce": "Julgada regular" });
  });
});
