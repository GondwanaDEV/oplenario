import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";

const estado = vi.hoisted(() => ({
  papeis: ["secretario"] as string[],
  params: { tipo: "esic", id: "p1" } as Record<string, string>,
  aba: null as string | null,
}));
vi.mock("@/lib/auth", () => ({
  useAuth: () => ({ token: "tk" }),
  usePapeis: () => ({ papeis: estado.papeis, estado: "pronto" }),
}));
vi.mock("../topo", () => ({ TopoInterno: ({ area }: { area: string }) => <div data-testid="topo">{area}</div> }));
vi.mock("../../../topo", () => ({ TopoInterno: ({ area }: { area: string }) => <div data-testid="topo">{area}</div> }));
vi.mock("next/navigation", () => ({
  useParams: () => estado.params,
  useSearchParams: () => ({ get: (k: string) => (k === "aba" ? estado.aba : null) }),
}));

import PaginaAtendimento from "./page";
import PaginaProtocolo from "./[tipo]/[id]/page";

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

// o fio é kebab-case (o boundary camelCaseia)
const prazo = (dias: number | null, extra: Record<string, unknown> = {}) => ({
  aberto: dias !== null, "recebido-em": "2026-06-25T12:00:00Z", "prazo-vigente": "2026-07-15",
  "dias-restantes": dias, prorrogado: false, ...extra,
});
const itemEsic = (id: string, protocolo: string, dias: number | null, extra: Record<string, unknown> = {}) => ({
  id, protocolo, assunto: `Assunto ${protocolo}`, estado: "protocolado", "recurso-pendente": null, ...prazo(dias), ...extra,
});
const detalheEsic = (extra: Record<string, unknown> = {}) => ({
  id: "p1", protocolo: "ESIC-2026-000007", assunto: "Contratos de 2025", descricao: "Quero a lista com valores.",
  estado: "protocolado", requerente: { nome: "Maria das Dores", "cpf-mascarado": "***.456.789-**" }, recurso: null,
  historico: [], acoes: { "pode-responder": true, "pode-prorrogar": true, "recurso-pendente-id": null }, ...prazo(12), ...extra,
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  estado.papeis = ["secretario"];
  estado.params = { tipo: "esic", id: "p1" };
  estado.aba = null;
});

describe("guard", () => {
  it("quem não é da secretaria vê 'Acesso restrito' e nada é buscado", () => {
    estado.papeis = ["vereador"];
    const c = mockar({});
    render(<PaginaAtendimento />);
    expect(screen.getByRole("heading", { name: "Acesso restrito" })).toBeTruthy();
    expect(c).toHaveLength(0);
  });

  it("o detalhe também é guardado", () => {
    estado.papeis = ["juridico"];
    const c = mockar({});
    render(<PaginaProtocolo />);
    expect(screen.getByRole("heading", { name: "Acesso restrito" })).toBeTruthy();
    expect(c).toHaveLength(0);
  });
});

describe("a fila /atendimento", () => {
  it("o e-SIC pelo prazo, com o selo de cada um, o recurso pendente e o alerta de vencidos", async () => {
    const c = mockar({
      "GET /api/atendimento/esic": { corpo: { situacao: "abertos", itens: [
        itemEsic("p2", "ESIC-2026-000002", -3),
        itemEsic("p3", "ESIC-2026-000003", 0, { estado: "respondido", "recurso-pendente": { id: "r1", protocolo: "REC-2026-000001", "recebido-em": "2026-07-02T12:00:00Z" } }),
        itemEsic("p1", "ESIC-2026-000001", 12),
      ] } },
      "GET /api/lgpd/encarregado": { status: 404, corpo: { erro: "encarregado nao definido" } },
    });
    render(<PaginaAtendimento />);
    const lista = await screen.findByRole("list", { name: "Pedidos de acesso à informação" });
    const linhas = within(lista).getAllByRole("listitem");
    expect(linhas.map((l) => within(l).getByText(/^ESIC-/).textContent)).toEqual(["ESIC-2026-000002", "ESIC-2026-000003", "ESIC-2026-000001"]);
    expect(within(linhas[0]).getByText("Venceu há 3 dias")).toBeTruthy();
    expect(within(linhas[1]).getByText("Vence hoje")).toBeTruthy();
    expect(within(linhas[1]).getByText("Recurso aguardando decisão")).toBeTruthy();
    expect(within(linhas[2]).getByText("Faltam 12 dias")).toBeTruthy();
    expect(within(linhas[0]).getByRole("link").getAttribute("href")).toBe("/atendimento/esic/p2?token=tk");
    expect(screen.getByText(/1 protocolo com o prazo vencido/)).toBeTruthy();
    expect(c[0].url).toBe("/api/atendimento/esic?situacao=abertos");
  });

  it("troca de fila e de filtro: ouvidoria só diz se é identificada; encerrados vem do servidor", async () => {
    const c = mockar({
      "GET /api/atendimento/esic": { corpo: { situacao: "abertos", itens: [] } },
      "GET /api/atendimento/ouvidoria": (ch) => ({ corpo: {
        situacao: ch.url.includes("respondidos") ? "respondidos" : "abertos",
        itens: ch.url.includes("respondidos") ? [] : [{
          id: "m1", protocolo: "OUV-2026-000004", tipo: "reclamacao", assunto: "Fila", identificacao: "anonima",
          estado: "protocolada", ...prazo(7),
        }],
      } }),
      "GET /api/lgpd/encarregado": { corpo: { nome: "Camila", rotulo: "Encarregada de Dados", email: "dados@camara.leg.br" } },
    });
    render(<PaginaAtendimento />);
    expect(await screen.findByText("Nenhum pedido de informação esperando resposta. Tudo em dia.")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Ouvidoria" }));
    const lista = await screen.findByRole("list", { name: "Manifestações de ouvidoria" });
    expect(within(lista).getByText("Reclamação: Fila")).toBeTruthy();
    expect(within(lista).getByText("Anônima")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: /^Encerrados/ }));
    expect(await screen.findByText("Nenhuma manifestação encerrada ainda.")).toBeTruthy();
    expect(c.map((x) => x.url)).toContain("/api/atendimento/ouvidoria?situacao=respondidos");
  });

  it("a aba pedida no endereço abre direto (a volta do detalhe)", async () => {
    estado.aba = "lgpd";
    const c = mockar({
      "GET /api/atendimento/lgpd": { corpo: { situacao: "abertos", itens: [] } },
      "GET /api/lgpd/encarregado": { status: 404, corpo: {} },
    });
    render(<PaginaAtendimento />);
    expect(await screen.findByText("Nenhum pedido do titular esperando resposta. Tudo em dia.")).toBeTruthy();
    expect(c[0].url).toBe("/api/atendimento/lgpd?situacao=abertos");
  });

  it("falha do servidor vira frase, nunca lista vazia", async () => {
    mockar({
      "GET /api/atendimento/esic": { status: 403, corpo: {} },
      "GET /api/lgpd/encarregado": { status: 404, corpo: {} },
    });
    render(<PaginaAtendimento />);
    expect((await screen.findAllByRole("alert"))[0].textContent).toMatch(/área é da secretaria/);
  });
});

describe("o encarregado de dados", () => {
  it("sem encarregado, avisa; informar manda o corpo exato do PUT", async () => {
    const c = mockar({
      "GET /api/atendimento/esic": { corpo: { situacao: "abertos", itens: [] } },
      "GET /api/lgpd/encarregado": { status: 404, corpo: {} },
      "PUT /api/lgpd/encarregado": (ch) => ({ corpo: ch.body }),
    });
    render(<PaginaAtendimento />);
    expect(await screen.findByText(/ainda não informou o encarregado/)).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Informar o encarregado" }));
    fireEvent.change(screen.getByLabelText("Nome"), { target: { value: " Camila Andrade " } });
    fireEvent.change(screen.getByLabelText("E-mail de contato"), { target: { value: "dados@camara.leg.br" } });
    fireEvent.click(screen.getByRole("button", { name: "Salvar o contato" }));
    expect(await screen.findByText(/Contato salvo/)).toBeTruthy();
    const put = c.find((x) => x.metodo === "PUT")!;
    expect(put.url).toBe("/api/lgpd/encarregado");
    expect(put.body).toEqual({ nome: "Camila Andrade", rotulo: "Encarregado(a) de Dados", email: "dados@camara.leg.br" });
    expect(screen.getByText("Camila Andrade")).toBeTruthy();
  });
});

describe("o protocolo /atendimento/[tipo]/[id]", () => {
  it("e-SIC: o requerente pelo nome e CPF mascarado; responder manda {corpo}, mostra o recibo e a volta à fila", async () => {
    let respondido = false;
    const c = mockar({
      "GET /api/atendimento/esic/p1": () => ({ corpo: respondido
        ? detalheEsic({ estado: "respondido", aberto: false, "dias-restantes": null,
            historico: [{ tipo: "resposta", em: "2026-07-03T15:00:00Z", texto: "Segue a planilha.", por: "Joana" }],
            acoes: { "pode-responder": false, "pode-prorrogar": false, "recurso-pendente-id": null } })
        : detalheEsic() }),
      "POST /api/esic/pedidos/p1/resposta": () => { respondido = true; return { corpo: { "respondida-em": "2026-07-03T15:00:00Z" } }; },
    });
    render(<PaginaProtocolo />);
    expect(await screen.findByRole("heading", { name: "Contratos de 2025" })).toBeTruthy();
    expect(screen.getByText("Maria das Dores")).toBeTruthy();
    expect(screen.getByText(/CPF \*\*\*\.456\.789-\*\*/)).toBeTruthy();
    expect(screen.getByText("Quero a lista com valores.")).toBeTruthy();
    expect(screen.getByRole("button", { name: "Prorrogar o prazo (+10 dias)" })).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: "Responder" }));
    const enviar = screen.getByRole("button", { name: "Enviar a resposta" }) as HTMLButtonElement;
    expect(enviar.disabled).toBe(true);
    fireEvent.change(screen.getByLabelText("Resposta ao cidadão"), { target: { value: "  Segue a planilha.  " } });
    fireEvent.click(enviar);
    const recibo = await screen.findByRole("status");
    expect(recibo.textContent).toMatch(/Resposta ao ESIC-2026-000007 registrada em/);
    expect(within(recibo).getByRole("link", { name: "Voltar à fila" }).getAttribute("href")).toBe("/atendimento?aba=esic&token=tk");
    const post = c.find((x) => x.metodo === "POST")!;
    expect(post.body).toEqual({ corpo: "Segue a planilha." });
    await waitFor(() => expect(screen.getByText("Este protocolo está encerrado: não há o que responder.")).toBeTruthy());
    expect(screen.getByText("Resposta da Casa")).toBeTruthy();
  });

  it("e-SIC com recurso pendente: decidir manda {corpo} para o recurso; prorrogar manda {justificativa}", async () => {
    const c = mockar({
      "GET /api/atendimento/esic/p1": { corpo: detalheEsic({
        estado: "respondido",
        recurso: { id: "r1", protocolo: "REC-2026-000001", motivo: "Faltou o valor.", estado: "protocolado",
          "recebido-em": "2026-07-02T12:00:00Z", "decidido-em": null, "prazo-vigente": "2026-07-12", "dias-restantes": 9, prorrogado: false },
        acoes: { "pode-responder": false, "pode-prorrogar": false, "recurso-pendente-id": "r1" },
      }) },
      "POST /api/esic/recursos/r1/decisao": { corpo: { "decidido-em": "2026-07-03T16:00:00Z" } },
    });
    render(<PaginaProtocolo />);
    expect(await screen.findByText("Faltou o valor.")).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Responder" })).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Decidir o recurso" }));
    fireEvent.change(screen.getByLabelText("Decisão sobre o recurso"), { target: { value: "Recurso provido." } });
    fireEvent.click(screen.getByRole("button", { name: "Registrar a decisão" }));
    expect((await screen.findByRole("status")).textContent).toMatch(/Decisão do recurso do ESIC-2026-000007/);
    expect(c.find((x) => x.metodo === "POST")!.body).toEqual({ corpo: "Recurso provido." });
  });

  it("prorrogar o e-SIC: {justificativa}; o 409 vira frase", async () => {
    const c = mockar({
      "GET /api/atendimento/esic/p1": { corpo: detalheEsic() },
      "POST /api/esic/pedidos/p1/prorrogar": { status: 409, corpo: { erro: "estado incompativel com a operacao" } },
    });
    render(<PaginaProtocolo />);
    fireEvent.click(await screen.findByRole("button", { name: "Prorrogar o prazo (+10 dias)" }));
    fireEvent.change(screen.getByLabelText("Justificativa da prorrogação"), { target: { value: "Busca no arquivo." } });
    fireEvent.click(screen.getByRole("button", { name: "Prorrogar por mais 10 dias" }));
    expect((await screen.findByRole("alert")).textContent).toMatch(/só cabe uma vez/);
    expect(c.find((x) => x.metodo === "POST")!.body).toEqual({ justificativa: "Busca no arquivo." });
  });

  it("ouvidoria: nunca quem; só identificada/anônima; arquivar manda {motivo}", async () => {
    estado.params = { tipo: "ouvidoria", id: "m1" };
    const c = mockar({
      "GET /api/atendimento/ouvidoria/m1": { corpo: {
        id: "m1", protocolo: "OUV-2026-000004", tipo: "denuncia", assunto: "Obra parada", descricao: "Na rua A.",
        identificacao: "identificada", estado: "protocolada", historico: [],
        acoes: { "pode-responder": true, "pode-arquivar": true, "pode-prorrogar": true }, ...prazo(20),
      } },
      "POST /api/ouvidoria/manifestacoes/m1/arquivar": { corpo: { "arquivada-em": "2026-07-03T16:00:00Z" } },
    });
    render(<PaginaProtocolo />);
    expect(await screen.findByRole("heading", { name: "Denúncia: Obra parada" })).toBeTruthy();
    expect(screen.getByText("identificada")).toBeTruthy();
    expect(screen.getByText(/protegida por lei/)).toBeTruthy();
    expect(screen.queryByText(/CPF/)).toBeNull();
    expect(screen.getByRole("button", { name: "Prorrogar o prazo (+30 dias)" })).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Arquivar sem resposta" }));
    fireEvent.change(screen.getByLabelText("Motivo do arquivamento"), { target: { value: "Fora da competência da Câmara." } });
    fireEvent.click(screen.getByRole("button", { name: "Arquivar" }));
    expect((await screen.findByRole("status")).textContent).toMatch(/OUV-2026-000004 arquivado em/);
    const post = c.find((x) => x.metodo === "POST")!;
    expect(post.url).toBe("/api/ouvidoria/manifestacoes/m1/arquivar");
    expect(post.body).toEqual({ motivo: "Fora da competência da Câmara." });
  });

  it("LGPD: o titular com CPF mascarado; responder vai para a solicitação", async () => {
    estado.params = { tipo: "lgpd", id: "s1" };
    const c = mockar({
      "GET /api/atendimento/lgpd/s1": { corpo: {
        id: "s1", protocolo: "LGPD-2026-000002", tipo: "acessar", detalhe: null, estado: "protocolada",
        titular: { nome: "Titular dos Dados", "cpf-mascarado": "***.111.222-**" }, historico: [],
        acoes: { "pode-responder": true }, ...prazo(5),
      } },
      "POST /api/lgpd/solicitacoes/s1/resposta": { corpo: { "respondida-em": "2026-07-03T16:00:00Z" } },
    });
    render(<PaginaProtocolo />);
    expect(await screen.findByRole("heading", { name: "Acesso aos dados" })).toBeTruthy();
    expect(screen.getByText("Titular dos Dados")).toBeTruthy();
    expect(screen.getByText(/não escreveu detalhes/)).toBeTruthy();
    expect(screen.queryByRole("button", { name: /Prorrogar/ })).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Responder" }));
    fireEvent.change(screen.getByLabelText("Resposta ao cidadão"), { target: { value: "Seus dados: nome e e-mail." } });
    fireEvent.click(screen.getByRole("button", { name: "Enviar a resposta" }));
    await screen.findByRole("status");
    expect(c.find((x) => x.metodo === "POST")!.url).toBe("/api/lgpd/solicitacoes/s1/resposta");
  });

  it("e-SIC: indeferir exige a fundamentação e um passo de confirmação; só então manda {fundamentacao}", async () => {
    let indeferido = false;
    const aberto = { "pode-responder": true, "pode-indeferir": true, "pode-prorrogar": true, "recurso-pendente-id": null };
    const c = mockar({
      "GET /api/atendimento/esic/p1": () => ({ corpo: indeferido
        ? detalheEsic({ estado: "indeferido", aberto: false, "dias-restantes": null,
            historico: [{ tipo: "indeferimento", em: "2026-07-03T15:00:00Z", texto: "Dado pessoal de terceiro (LAI art. 31).", por: "Joana" }],
            acoes: { "pode-responder": false, "pode-indeferir": false, "pode-prorrogar": false, "recurso-pendente-id": null } })
        : detalheEsic({ acoes: aberto }) }),
      "POST /api/esic/pedidos/p1/indeferir": () => { indeferido = true; return { corpo: { "indeferido-em": "2026-07-03T15:00:00Z" } }; },
    });
    render(<PaginaProtocolo />);
    await screen.findByRole("heading", { name: "Contratos de 2025" });
    fireEvent.click(screen.getByRole("button", { name: "Indeferir" }));
    expect(screen.getByText(/definitivo/)).toBeTruthy();

    const revisar = screen.getByRole("button", { name: "Revisar o indeferimento" }) as HTMLButtonElement;
    expect(revisar.disabled).toBe(true);
    expect(screen.getByText("Escreva a fundamentação.")).toBeTruthy();
    fireEvent.change(screen.getByLabelText("Fundamentação do indeferimento"), { target: { value: "   " } });
    expect(revisar.disabled).toBe(true);

    fireEvent.change(screen.getByLabelText("Fundamentação do indeferimento"), { target: { value: "  Dado pessoal de terceiro (LAI art. 31).  " } });
    expect(revisar.disabled).toBe(false);
    fireEvent.click(revisar);

    // o passo de confirmação: NADA foi enviado ainda; o texto que vai é o que o requerente lerá
    expect(c.some((x) => x.metodo === "POST")).toBe(false);
    const confirmar = screen.getByRole("group", { name: "Confirmar o indeferimento" });
    expect(within(confirmar).getByText("Dado pessoal de terceiro (LAI art. 31).")).toBeTruthy();
    expect(within(confirmar).getByText(/ESIC-2026-000007/)).toBeTruthy();

    // dá para voltar e editar (o texto fica)
    fireEvent.click(within(confirmar).getByRole("button", { name: "Voltar e editar" }));
    expect((screen.getByLabelText("Fundamentação do indeferimento") as HTMLTextAreaElement).value).toMatch(/Dado pessoal de terceiro/);
    expect(c.some((x) => x.metodo === "POST")).toBe(false);
    fireEvent.click(screen.getByRole("button", { name: "Revisar o indeferimento" }));

    fireEvent.click(screen.getByRole("button", { name: "Confirmar o indeferimento" }));
    const recibo = await screen.findByRole("status");
    expect(recibo.textContent).toMatch(/ESIC-2026-000007 indeferido em/);
    expect(within(recibo).getByRole("link", { name: "Voltar à fila" }).getAttribute("href")).toBe("/atendimento?aba=esic&token=tk");
    const posts = c.filter((x) => x.metodo === "POST");
    expect(posts).toHaveLength(1);
    expect(posts[0].url).toBe("/api/esic/pedidos/p1/indeferir");
    expect(posts[0].body).toEqual({ fundamentacao: "Dado pessoal de terceiro (LAI art. 31)." });

    // recarregado: encerrado, sem nenhum botão de ação, e o historico distingue o indeferimento da resposta, sem enum cru
    await waitFor(() => expect(screen.getByText("Este protocolo está encerrado: não há o que responder.")).toBeTruthy());
    expect(screen.getByText("Indeferimento (fundamentação da Casa)")).toBeTruthy();
    expect(screen.getByText("Dado pessoal de terceiro (LAI art. 31).")).toBeTruthy();
    expect(screen.getByText("Indeferido")).toBeTruthy();
    expect(screen.queryByText("indeferido")).toBeNull();
    expect(screen.queryByRole("button", { name: "Indeferir" })).toBeNull();
  });

  it("o botão Indeferir só aparece quando o servidor diz que cabe", async () => {
    // acoes SEM `pode-indeferir` (servidor antigo) ou com false: nenhum botão — a tela nao deduz regra
    mockar({ "GET /api/atendimento/esic/p1": { corpo: detalheEsic() } });
    const { unmount } = render(<PaginaProtocolo />);
    await screen.findByRole("heading", { name: "Contratos de 2025" });
    expect(screen.getByRole("button", { name: "Responder" })).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Indeferir" })).toBeNull();
    unmount();

    mockar({ "GET /api/atendimento/esic/p1": { corpo: detalheEsic({
      acoes: { "pode-responder": true, "pode-indeferir": false, "pode-prorrogar": true, "recurso-pendente-id": null } }) } });
    render(<PaginaProtocolo />);
    await screen.findByRole("heading", { name: "Contratos de 2025" });
    expect(screen.queryByRole("button", { name: "Indeferir" })).toBeNull();
  });

  it("e-SIC com recurso pendente de pedido indeferido: decide o recurso, e não oferece indeferir de novo", async () => {
    mockar({ "GET /api/atendimento/esic/p1": { corpo: detalheEsic({
      estado: "indeferido",
      recurso: { id: "r1", protocolo: "REC-2026-000001", motivo: "Só peço os valores agregados.", estado: "protocolado",
        "recebido-em": "2026-07-04T12:00:00Z", "decidido-em": null, "prazo-vigente": "2026-07-14", "dias-restantes": 9, prorrogado: false },
      acoes: { "pode-responder": false, "pode-indeferir": false, "pode-prorrogar": false, "recurso-pendente-id": "r1" },
    }) } });
    render(<PaginaProtocolo />);
    expect(await screen.findByText("Só peço os valores agregados.")).toBeTruthy();
    expect(screen.getByRole("button", { name: "Decidir o recurso" })).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Indeferir" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Responder" })).toBeNull();
  });

  it("indeferir: o 409 vira frase (já respondido ou indeferido por outra pessoa) e o ato não vira recibo", async () => {
    mockar({
      "GET /api/atendimento/esic/p1": { corpo: detalheEsic({ acoes: { "pode-responder": true, "pode-indeferir": true, "pode-prorrogar": true, "recurso-pendente-id": null } }) },
      "POST /api/esic/pedidos/p1/indeferir": { status: 409, corpo: { erro: "estado incompativel com a operacao" } },
    });
    render(<PaginaProtocolo />);
    fireEvent.click(await screen.findByRole("button", { name: "Indeferir" }));
    fireEvent.change(screen.getByLabelText("Fundamentação do indeferimento"), { target: { value: "Fora do escopo da LAI." } });
    fireEvent.click(screen.getByRole("button", { name: "Revisar o indeferimento" }));
    fireEvent.click(screen.getByRole("button", { name: "Confirmar o indeferimento" }));
    expect((await screen.findByRole("alert")).textContent).toMatch(/já foi respondido ou indeferido/);
    expect(screen.queryByRole("status")).toBeNull();
    // o erro leva de volta ao texto (nada se perde): a pessoa pode copiar a fundamentação antes de recarregar
    expect((screen.getByLabelText("Fundamentação do indeferimento") as HTMLTextAreaElement).value).toBe("Fora do escopo da LAI.");
  });

  it("LGPD: indeferir vai para a solicitação, com a fundamentação confirmada", async () => {
    estado.params = { tipo: "lgpd", id: "s1" };
    const c = mockar({
      "GET /api/atendimento/lgpd/s1": { corpo: {
        id: "s1", protocolo: "LGPD-2026-000002", tipo: "eliminar", detalhe: "Apaguem meus dados.", estado: "protocolada",
        titular: { nome: "Titular dos Dados", "cpf-mascarado": "***.111.222-**" }, historico: [],
        acoes: { "pode-responder": true, "pode-indeferir": true }, ...prazo(5),
      } },
      "POST /api/lgpd/solicitacoes/s1/indeferir": { corpo: { "indeferida-em": "2026-07-03T16:00:00Z" } },
    });
    render(<PaginaProtocolo />);
    expect(await screen.findByRole("heading", { name: "Eliminação de dados" })).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Indeferir" }));
    fireEvent.change(screen.getByLabelText("Fundamentação do indeferimento"), { target: { value: "Dados mantidos por obrigação legal (LGPD art. 16, I)." } });
    fireEvent.click(screen.getByRole("button", { name: "Revisar o indeferimento" }));
    expect(c.some((x) => x.metodo === "POST")).toBe(false);
    fireEvent.click(screen.getByRole("button", { name: "Confirmar o indeferimento" }));
    expect((await screen.findByRole("status")).textContent).toMatch(/LGPD-2026-000002 indeferido em/);
    const post = c.find((x) => x.metodo === "POST")!;
    expect(post.url).toBe("/api/lgpd/solicitacoes/s1/indeferir");
    expect(post.body).toEqual({ fundamentacao: "Dados mantidos por obrigação legal (LGPD art. 16, I)." });
  });

  it("prorrogar avisa, no formulário, que a justificativa é mostrada ao requerente (e-SIC)", async () => {
    mockar({ "GET /api/atendimento/esic/p1": { corpo: detalheEsic() } });
    render(<PaginaProtocolo />);
    fireEvent.click(await screen.findByRole("button", { name: "Prorrogar o prazo (+10 dias)" }));
    expect(screen.getByText(/exige justificativa\. A justificativa é mostrada ao requerente/)).toBeTruthy();
    // só texto: o fluxo é o mesmo (rótulo, botão de enviar desabilitado até escrever)
    expect((screen.getByRole("button", { name: "Prorrogar por mais 10 dias" }) as HTMLButtonElement).disabled).toBe(true);
    expect(screen.getByLabelText("Justificativa da prorrogação")).toBeTruthy();
  });

  it("ouvidoria: identificada vê o aviso do manifestante; anônima é avisada de que a justificativa não tem a quem ser mostrada", async () => {
    estado.params = { tipo: "ouvidoria", id: "m1" };
    const ouv = (identificacao: string) => ({
      id: "m1", protocolo: "OUV-2026-000004", tipo: "denuncia", assunto: "Obra parada", descricao: "Na rua A.",
      identificacao, estado: "protocolada", historico: [],
      acoes: { "pode-responder": true, "pode-arquivar": true, "pode-prorrogar": true }, ...prazo(20),
    });
    mockar({ "GET /api/atendimento/ouvidoria/m1": { corpo: ouv("identificada") } });
    const { unmount } = render(<PaginaProtocolo />);
    fireEvent.click(await screen.findByRole("button", { name: "Prorrogar o prazo (+30 dias)" }));
    expect(screen.getByText(/A justificativa é mostrada ao manifestante/)).toBeTruthy();
    unmount();

    mockar({ "GET /api/atendimento/ouvidoria/m1": { corpo: ouv("anonima") } });
    render(<PaginaProtocolo />);
    fireEvent.click(await screen.findByRole("button", { name: "Prorrogar o prazo (+30 dias)" }));
    expect(screen.getByText(/manifestação é anônima/)).toBeTruthy();
    expect(screen.queryByText(/é mostrada ao manifestante/)).toBeNull();
  });

  it("ouvidoria não ganha Indeferir (ela já tem Arquivar)", async () => {
    estado.params = { tipo: "ouvidoria", id: "m1" };
    mockar({ "GET /api/atendimento/ouvidoria/m1": { corpo: {
      id: "m1", protocolo: "OUV-2026-000004", tipo: "denuncia", assunto: "Obra parada", descricao: "Na rua A.",
      identificacao: "identificada", estado: "protocolada", historico: [],
      acoes: { "pode-responder": true, "pode-arquivar": true, "pode-prorrogar": true }, ...prazo(20),
    } } });
    render(<PaginaProtocolo />);
    await screen.findByRole("heading", { name: "Denúncia: Obra parada" });
    expect(screen.queryByRole("button", { name: "Indeferir" })).toBeNull();
    expect(screen.getByRole("button", { name: "Arquivar sem resposta" })).toBeTruthy();
  });

  it("fila que não existe no endereço: frase, sem buscar", () => {
    estado.params = { tipo: "moderacao", id: "x" };
    const c = mockar({});
    render(<PaginaProtocolo />);
    expect(screen.getByRole("alert").textContent).toMatch(/Endereço inválido/);
    expect(c).toHaveLength(0);
  });
});
