import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";

const estado = vi.hoisted(() => ({
  papeis: ["secretario"] as string[],
  params: { tipo: "esic", id: "p1" } as Record<string, string>,
  aba: null as string | null,
  token: "tk" as string | null,
}));
vi.mock("@/lib/auth", () => ({
  useAuth: () => ({ token: estado.token }),
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
    // o upload de anexo e' multipart (FormData): o corpo registrado e' o nome e o tamanho do arquivo do campo `arquivo`
    const corpo = init?.body instanceof FormData
      ? { arquivo: (init.body.get("arquivo") as File).name, tamanho: (init.body.get("arquivo") as File).size }
      : init?.body ? JSON.parse(String(init.body)) : undefined;
    const c: Chamada = { metodo: init?.method ?? "GET", url: String(url), body: corpo };
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
  estado.token = "tk";
  vi.unstubAllEnvs();
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

  // ---------------------------------------------------------------- os anexos da resposta

  const arquivo = (nome: string, tamanho = 100, tipo = "") => new File([new Uint8Array(tamanho)], nome, { type: tipo });
  const escolher = (rotulo: RegExp, arquivos: File[]) =>
    fireEvent.change(screen.getByLabelText(rotulo), { target: { files: arquivos } });
  const abertoComAnexar = (podeAnexar: boolean) =>
    detalheEsic({ acoes: { "pode-responder": true, "pode-indeferir": true, "pode-prorrogar": true, "pode-anexar": podeAnexar, "recurso-pendente-id": null } });
  const fechadoComAnexar = (podeAnexar: boolean, anexos: unknown[] = []) =>
    detalheEsic({ estado: "respondido", aberto: false, "dias-restantes": null, anexos,
      historico: [{ tipo: "resposta", em: "2026-07-03T15:00:00Z", texto: "Segue.", por: "Joana" }],
      acoes: { "pode-responder": false, "pode-indeferir": false, "pode-prorrogar": false, "pode-anexar": podeAnexar, "recurso-pendente-id": null } });
  const anexoOut = (id: string, nome: string, tamanho: number, tipo = "application/pdf", origem = "casa") =>
    ({ id, nome, "tipo-midia": tipo, bytes: tamanho, origem, "enviado-em": "2026-07-03T15:00:00Z" });
  const posts = (c: Chamada[], sufixo: string) => c.filter((x) => x.metodo === "POST" && x.url.endsWith(sufixo));

  it("responder com anexos: o seletor barra o que não pode ir; depois do ato, os arquivos sobem um a um", async () => {
    let respondido = false;
    const resolvers: (() => void)[] = [];
    const chamadas: Chamada[] = [];
    global.fetch = vi.fn(async (url: string, init?: RequestInit) => {
      const corpo = init?.body instanceof FormData
        ? { arquivo: (init.body.get("arquivo") as File).name } : init?.body ? JSON.parse(String(init.body)) : undefined;
      const c: Chamada = { metodo: init?.method ?? "GET", url: String(url), body: corpo };
      chamadas.push(c);
      const json = (status: number, b: unknown) => ({ ok: status < 300, status, json: async () => b }) as Response;
      if (c.metodo === "GET") return json(200, respondido ? fechadoComAnexar(true) : abertoComAnexar(false));
      if (c.url.endsWith("/resposta")) { respondido = true; return json(200, { "respondida-em": "2026-07-03T15:00:00Z" }); }
      await new Promise<void>((r) => resolvers.push(r));   // cada anexo so' termina quando o teste deixa
      return json(201, anexoOut(`n${resolvers.length}`, (corpo as { arquivo: string }).arquivo, 100));
    }) as unknown as typeof fetch;
    render(<PaginaProtocolo />);
    fireEvent.click(await screen.findByRole("button", { name: "Responder" }));

    // o seletor: o que vale esta escrito, e o que nao pode ir e' recusado com o motivo, ANTES de enviar
    expect(screen.getByText(/Até 5 arquivos de até 10 MB: PDF, PNG, JPEG, TXT, CSV, DOCX, XLSX, ODT e ODS\./)).toBeTruthy();
    expect((screen.getByLabelText(/Anexar arquivos à resposta/) as HTMLInputElement).accept).toBe(".pdf,.png,.jpg,.jpeg,.txt,.csv,.docx,.xlsx,.odt,.ods");
    escolher(/Anexar arquivos à resposta/, [arquivo("folha.pdf"), arquivo("dados.csv"), arquivo("programa.exe"), arquivo("enorme.pdf", 10 * 1024 * 1024 + 1)]);
    const lista = screen.getByRole("list", { name: "Arquivos escolhidos" });
    expect(within(lista).getAllByRole("listitem").map((l) => l.textContent)).toEqual([
      expect.stringContaining("folha.pdf"), expect.stringContaining("dados.csv"),
    ]);
    const recusas = screen.getByRole("alert").textContent ?? "";
    expect(recusas).toMatch(/“programa.exe” não é de um tipo aceito/);
    expect(recusas).toMatch(/“enorme.pdf” passa de 10 MB/);
    // remover um da lista
    fireEvent.click(within(lista).getByRole("button", { name: "Remover dados.csv" }));
    expect(within(lista).getAllByRole("listitem")).toHaveLength(1);
    escolher(/Anexar arquivos à resposta/, [arquivo("dados.csv")]);

    fireEvent.change(screen.getByLabelText("Resposta ao cidadão"), { target: { value: "Segue em anexo." } });
    fireEvent.click(screen.getByRole("button", { name: "Enviar a resposta" }));

    // 1o o ATO (a resposta), 2o os arquivos — um por vez: o 2o so' comeca quando o 1o termina
    await waitFor(() => expect(posts(chamadas, "/anexos")).toHaveLength(1));
    expect(chamadas.filter((x) => x.metodo === "POST").map((x) => x.url)).toEqual(["/api/esic/pedidos/p1/resposta", "/api/atendimento/esic/p1/anexos"]);
    expect(posts(chamadas, "/anexos")[0].body).toEqual({ arquivo: "folha.pdf" });
    await new Promise((r) => setTimeout(r, 30));
    expect(posts(chamadas, "/anexos")).toHaveLength(1);
    resolvers[0]();
    await waitFor(() => expect(posts(chamadas, "/anexos")).toHaveLength(2));
    expect(posts(chamadas, "/anexos")[1].body).toEqual({ arquivo: "dados.csv" });
    resolvers[1]();
    expect(await screen.findByText("2 arquivos anexados.")).toBeTruthy();
    const painel = screen.getByRole("list", { name: "Envio dos anexos" });
    expect(within(painel).getAllByText("anexado")).toHaveLength(2);
  });

  it("falha parcial: diz qual arquivo falhou e por quê, e deixa tentar de novo enquanto cabe anexar", async () => {
    let tentativas = 0;
    let respondido = false;   // o mock reflete o estado DEPOIS do ato: respondido, e a janela de anexar aberta
    const c = mockar({
      "GET /api/atendimento/esic/p1": () => ({ corpo: respondido ? fechadoComAnexar(true) : abertoComAnexar(false) }),
      "POST /api/esic/pedidos/p1/resposta": () => { respondido = true; return { corpo: { "respondida-em": "2026-07-03T15:00:00Z" } }; },
      "POST /api/atendimento/esic/p1/anexos": (ch) => {
        const nome = (ch.body as { arquivo: string }).arquivo;
        if (nome === "ruim.pdf" && ++tentativas === 1)
          return { status: 415, corpo: { erro: "Tipo de arquivo não aceito. Aceitamos PDF, PNG, JPEG, TXT, CSV, DOCX, XLSX, ODT e ODS, e a extensão do nome tem de combinar com o tipo do arquivo." } };
        return { status: 201, corpo: anexoOut(`n-${nome}`, nome, 100) };
      },
    });
    render(<PaginaProtocolo />);
    fireEvent.click(await screen.findByRole("button", { name: "Responder" }));
    escolher(/Anexar arquivos à resposta/, [arquivo("bom.pdf"), arquivo("ruim.pdf"), arquivo("outro.txt")]);
    fireEvent.change(screen.getByLabelText("Resposta ao cidadão"), { target: { value: "Segue." } });
    fireEvent.click(screen.getByRole("button", { name: "Enviar a resposta" }));

    expect(await screen.findByText("2 arquivos anexados; 1 não foi anexado.")).toBeTruthy();
    const painel = screen.getByRole("list", { name: "Envio dos anexos" });
    const itens = within(painel).getAllByRole("listitem");
    expect(itens[0].textContent).toMatch(/bom.pdf.*anexado/);
    expect(itens[1].textContent).toMatch(/ruim.pdf.*não foi anexado: Tipo de arquivo não aceito\./);
    expect(itens[2].textContent).toMatch(/outro.txt.*anexado/);
    expect(posts(c, "/anexos").map((x) => (x.body as { arquivo: string }).arquivo)).toEqual(["bom.pdf", "ruim.pdf", "outro.txt"]);

    // tentar de novo SO' o que falhou — e só enquanto o servidor diz que cabe anexar
    fireEvent.click(within(painel).getByRole("button", { name: "Tentar de novo o anexo ruim.pdf" }));
    await waitFor(() => expect(within(painel).getAllByText("anexado")).toHaveLength(3));
    expect(posts(c, "/anexos")).toHaveLength(4);
    expect(screen.getByText("3 arquivos anexados.")).toBeTruthy();
    expect(within(painel).queryByRole("button", { name: /Tentar de novo/ })).toBeNull();
  });

  it("passada a janela, o 'tentar de novo' fica desligado e a tela diz por quê", async () => {
    // o ato deu certo, mas ao recarregar o servidor ja' diz `pode-anexar` falso (janela de 10 min ou 5 anexos)
    mockar({
      "GET /api/atendimento/esic/p1": ((n: { v: number }) => () => ({ corpo: n.v++ === 0 ? abertoComAnexar(false) : fechadoComAnexar(false) }))({ v: 0 }),
      "POST /api/esic/pedidos/p1/resposta": { corpo: { "respondida-em": "2026-07-03T15:00:00Z" } },
      "POST /api/atendimento/esic/p1/anexos": { status: 409, corpo: { erro: "Os anexos vão junto com a resposta: os 10 minutos depois do último ato já passaram." } },
    });
    render(<PaginaProtocolo />);
    fireEvent.click(await screen.findByRole("button", { name: "Responder" }));
    escolher(/Anexar arquivos à resposta/, [arquivo("a.pdf")]);
    fireEvent.change(screen.getByLabelText("Resposta ao cidadão"), { target: { value: "Segue." } });
    fireEvent.click(screen.getByRole("button", { name: "Enviar a resposta" }));
    expect(await screen.findByText("O arquivo não foi anexado.")).toBeTruthy();
    const painel = screen.getByRole("list", { name: "Envio dos anexos" });
    expect(within(painel).getByText(/não foi anexado: Os anexos vão junto com a resposta: os 10 minutos/)).toBeTruthy();
    const botao = await within(painel).findByRole("button", { name: "Tentar de novo o anexo a.pdf" });
    await waitFor(() => expect((botao as HTMLButtonElement).disabled).toBe(true));
    expect(screen.getByText(/Já não cabe anexar: passaram os 10 minutos depois da resposta, ou a Casa já tem 5 anexos\./)).toBeTruthy();
  });

  it("indeferir: os arquivos só sobem depois da confirmação; decidir o recurso também tem o seletor; prorrogar e arquivar não", async () => {
    const c = mockar({
      "GET /api/atendimento/esic/p1": { corpo: abertoComAnexar(false) },
      "POST /api/esic/pedidos/p1/indeferir": { corpo: { "indeferido-em": "2026-07-03T15:00:00Z" } },
      "POST /api/atendimento/esic/p1/anexos": { status: 201, corpo: anexoOut("n1", "fundamento.pdf", 100) },
    });
    render(<PaginaProtocolo />);
    fireEvent.click(await screen.findByRole("button", { name: "Indeferir" }));
    escolher(/Anexar arquivos à resposta/, [arquivo("fundamento.pdf")]);
    fireEvent.change(screen.getByLabelText("Fundamentação do indeferimento"), { target: { value: "Dado pessoal de terceiros." } });
    fireEvent.click(screen.getByRole("button", { name: "Revisar o indeferimento" }));
    // o passo de confirmacao mostra o que vai: o texto e os arquivos; nada enviado ainda
    const confirmar = screen.getByRole("group", { name: "Confirmar o indeferimento" });
    expect(within(confirmar).getByText(/fundamento.pdf/)).toBeTruthy();
    expect(c.some((x) => x.metodo === "POST")).toBe(false);
    fireEvent.click(within(confirmar).getByRole("button", { name: "Confirmar o indeferimento" }));
    expect(await screen.findByText("1 arquivo anexado.")).toBeTruthy();
    expect(c.filter((x) => x.metodo === "POST").map((x) => x.url)).toEqual(["/api/esic/pedidos/p1/indeferir", "/api/atendimento/esic/p1/anexos"]);
  });

  it("decidir o recurso aceita anexos; prorrogar e arquivar não mostram o seletor", async () => {
    const c = mockar({
      "GET /api/atendimento/esic/p1": { corpo: detalheEsic({
        estado: "respondido",
        recurso: { id: "r1", protocolo: "REC-2026-000001", motivo: "Faltou o valor.", estado: "protocolado",
          "recebido-em": "2026-07-02T12:00:00Z", "decidido-em": null, "prazo-vigente": "2026-07-12", "dias-restantes": 9, prorrogado: false },
        acoes: { "pode-responder": false, "pode-indeferir": false, "pode-prorrogar": true, "pode-anexar": false, "recurso-pendente-id": "r1" },
      }) },
      "POST /api/esic/recursos/r1/decisao": { corpo: { "decidido-em": "2026-07-03T16:00:00Z" } },
      "POST /api/atendimento/esic/p1/anexos": { status: 201, corpo: anexoOut("n1", "decisao.pdf", 100) },
    });
    render(<PaginaProtocolo />);
    fireEvent.click(await screen.findByRole("button", { name: "Prorrogar o prazo (+10 dias)" }));
    expect(screen.queryByLabelText(/Anexar arquivos à resposta/)).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Cancelar" }));
    fireEvent.click(screen.getByRole("button", { name: "Decidir o recurso" }));
    escolher(/Anexar arquivos à resposta/, [arquivo("decisao.pdf")]);
    fireEvent.change(screen.getByLabelText("Decisão sobre o recurso"), { target: { value: "Provido." } });
    fireEvent.click(screen.getByRole("button", { name: "Registrar a decisão" }));
    expect(await screen.findByText("1 arquivo anexado.")).toBeTruthy();
    expect(c.filter((x) => x.metodo === "POST").map((x) => x.url)).toEqual(["/api/esic/recursos/r1/decisao", "/api/atendimento/esic/p1/anexos"]);
  });

  it("ouvidoria e LGPD: o upload vai para a rota da sua espécie", async () => {
    for (const [tipo, id, rotaAto, corpoAto, detalhe] of [
      ["ouvidoria", "m1", "/api/ouvidoria/manifestacoes/m1/resposta", { "respondida-em": "2026-07-03T15:00:00Z" },
        { id: "m1", protocolo: "OUV-2026-000004", tipo: "denuncia", assunto: "Obra", descricao: "Na rua A.", identificacao: "identificada",
          estado: "protocolada", historico: [], anexos: [], acoes: { "pode-responder": true, "pode-arquivar": true, "pode-prorrogar": true, "pode-anexar": false }, ...prazo(20) }],
      ["lgpd", "s1", "/api/lgpd/solicitacoes/s1/resposta", { "respondida-em": "2026-07-03T15:00:00Z" },
        { id: "s1", protocolo: "LGPD-2026-000002", tipo: "acessar", detalhe: null, estado: "protocolada",
          titular: { nome: "Titular", "cpf-mascarado": "***.111.222-**" }, historico: [], anexos: [], acoes: { "pode-responder": true, "pode-indeferir": true, "pode-anexar": false }, ...prazo(5) }],
    ] as [string, string, string, unknown, unknown][]) {
      estado.params = { tipo, id };
      const c = mockar({
        [`GET /api/atendimento/${tipo}/${id}`]: { corpo: detalhe },
        [`POST ${rotaAto}`]: { corpo: corpoAto },
        [`POST /api/atendimento/${tipo}/${id}/anexos`]: { status: 201, corpo: anexoOut("n1", "doc.pdf", 100) },
      });
      const { unmount } = render(<PaginaProtocolo />);
      fireEvent.click(await screen.findByRole("button", { name: "Responder" }));
      escolher(/Anexar arquivos à resposta/, [arquivo("doc.pdf")]);
      fireEvent.change(screen.getByLabelText("Resposta ao cidadão"), { target: { value: "Segue." } });
      fireEvent.click(screen.getByRole("button", { name: "Enviar a resposta" }));
      expect(await screen.findByText("1 arquivo anexado.")).toBeTruthy();
      expect(c.filter((x) => x.metodo === "POST").map((x) => x.url)).toEqual([rotaAto, `/api/atendimento/${tipo}/${id}/anexos`]);
      unmount();
      cleanup();
    }
  });

  it("o detalhe lista 'Anexos da resposta' com nome, tamanho e formato (nunca o tipo cru) e o download", async () => {
    mockar({ "GET /api/atendimento/esic/p1": { corpo: fechadoComAnexar(false, [
      anexoOut("a1", "folha de 2025.pdf", 1536, "application/pdf"),
      anexoOut("a2", "dados.csv", 20, "text/csv"),
    ]) } });
    render(<PaginaProtocolo />);
    const secao = await screen.findByRole("region", { name: "Anexos da resposta" });
    const itens = within(secao).getAllByRole("listitem");
    expect(itens).toHaveLength(2);
    expect(itens[0].textContent).toMatch(/folha de 2025\.pdf.*2 KB · PDF/);
    expect(itens[1].textContent).toMatch(/dados\.csv.*20 B · CSV/);
    expect(secao.textContent).not.toMatch(/application\/pdf|text\/csv/);
    // modo dev (token no header): o download sai pelos bytes, com um botao por anexo
    expect(within(itens[0]).getByRole("button", { name: "Baixar folha de 2025.pdf" })).toBeTruthy();
  });

  it("o download no modo real é um link direto para o anexo, como arquivo", async () => {
    vi.stubEnv("NEXT_PUBLIC_APP_ENV", "production");
    estado.token = null;
    mockar({ "GET /api/atendimento/esic/p1": { corpo: fechadoComAnexar(false, [anexoOut("a1", "folha.pdf", 100)]) } });
    render(<PaginaProtocolo />);
    const link = await screen.findByRole("link", { name: "Baixar folha.pdf" });
    expect(link.getAttribute("href")).toBe("/api/atendimento/esic/p1/anexos/a1");
    expect(link.hasAttribute("download")).toBe(true);
  });

  it("as duas origens ficam em listas separadas: 'Anexos do pedido' (o requerente) e 'Anexos da resposta' (a Casa), cada uma com download", async () => {
    mockar({ "GET /api/atendimento/esic/p1": { corpo: fechadoComAnexar(false, [
      anexoOut("a1", "contrato.pdf", 2048, "application/pdf", "requerente"),
      anexoOut("a2", "resposta.pdf", 1024, "application/pdf", "casa"),
      anexoOut("a3", "foto.png", 10, "image/png", "requerente"),
    ]) } });
    render(<PaginaProtocolo />);
    const doPedido = await screen.findByRole("region", { name: "Anexos do pedido" });
    expect(within(doPedido).getAllByRole("listitem").map((l) => l.textContent)).toEqual([
      expect.stringContaining("contrato.pdf"), expect.stringContaining("foto.png"),
    ]);
    expect(within(doPedido).getByRole("button", { name: "Baixar contrato.pdf" })).toBeTruthy();
    const daResposta = screen.getByRole("region", { name: "Anexos da resposta" });
    expect(within(daResposta).getAllByRole("listitem")).toHaveLength(1);
    expect(within(daResposta).getByText("resposta.pdf")).toBeTruthy();
    expect(within(doPedido).queryByText("resposta.pdf")).toBeNull();
  });

  it("só anexos do requerente: aparece 'Anexos do pedido' e nenhuma lista da resposta", async () => {
    mockar({ "GET /api/atendimento/esic/p1": { corpo: abertoComAnexar(false) } });
    const { unmount } = render(<PaginaProtocolo />);
    await screen.findByRole("heading", { name: "Contratos de 2025" });
    expect(screen.queryByRole("region", { name: /Anexos d/ })).toBeNull();
    unmount();
    mockar({ "GET /api/atendimento/esic/p1": { corpo: detalheEsic({ anexos: [anexoOut("a1", "contrato.pdf", 10, "application/pdf", "requerente")] }) } });
    render(<PaginaProtocolo />);
    expect(await screen.findByRole("region", { name: "Anexos do pedido" })).toBeTruthy();
    expect(screen.queryByRole("region", { name: "Anexos da resposta" })).toBeNull();
  });

  it("anexar depois de recarregar: enquanto `pode-anexar`, um controle avulso envia arquivos à resposta, um a um", async () => {
    const c = mockar({
      "GET /api/atendimento/esic/p1": { corpo: fechadoComAnexar(true) },
      "POST /api/atendimento/esic/p1/anexos": (ch) => ({ status: 201, corpo: anexoOut(`n-${(ch.body as { arquivo: string }).arquivo}`, (ch.body as { arquivo: string }).arquivo, 100) }),
    });
    render(<PaginaProtocolo />);
    const secao = await screen.findByRole("region", { name: "Anexar arquivo" });
    expect(within(secao).getByText(/10 minutos depois da resposta/)).toBeTruthy();
    const enviar = within(secao).getByRole("button", { name: "Anexar os arquivos escolhidos" }) as HTMLButtonElement;
    expect(enviar.disabled).toBe(true);
    // o MESMO seletor: barra o que nao pode ir, antes de enviar
    escolher(/Escolher arquivos para anexar/, [arquivo("a.pdf"), arquivo("b.csv"), arquivo("programa.exe")]);
    expect(within(secao).getByRole("alert").textContent).toMatch(/“programa.exe” não é de um tipo aceito/);
    expect(enviar.disabled).toBe(false);
    fireEvent.click(enviar);
    expect(await screen.findByText("2 arquivos anexados.")).toBeTruthy();
    expect(posts(c, "/anexos").map((x) => (x.body as { arquivo: string }).arquivo)).toEqual(["a.pdf", "b.csv"]);
    // depois de enviar, a selecao some (o controle fica pronto para a proxima) e o detalhe foi relido
    expect(within(secao).queryByRole("list", { name: "Arquivos escolhidos" })).toBeNull();
    // a releitura do detalhe é disparada depois de a mensagem aparecer: contar os GET na mesma hora dependia de
    // o pedido já ter saído. Espera-se o pedido.
    await waitFor(() => expect(c.filter((x) => x.metodo === "GET").length).toBeGreaterThanOrEqual(2));
  });

  it("o controle avulso: a falha diz qual arquivo e por quê, e deixa tentar de novo", async () => {
    let tentativas = 0;
    const c = mockar({
      "GET /api/atendimento/esic/p1": { corpo: fechadoComAnexar(true) },
      "POST /api/atendimento/esic/p1/anexos": () => ++tentativas === 1
        ? { status: 415, corpo: { erro: "Tipo de arquivo não aceito. Aceitamos PDF." } }
        : { status: 201, corpo: anexoOut("n1", "a.pdf", 100) },
    });
    render(<PaginaProtocolo />);
    const secao = await screen.findByRole("region", { name: "Anexar arquivo" });
    escolher(/Escolher arquivos para anexar/, [arquivo("a.pdf")]);
    fireEvent.click(within(secao).getByRole("button", { name: "Anexar os arquivos escolhidos" }));
    const falha = await screen.findByText(/não foi anexado: Tipo de arquivo não aceito\. Aceitamos PDF\./);
    expect(falha.closest("li")!.textContent).toMatch(/a\.pdf/);   // diz QUAL arquivo e por quê
    fireEvent.click(screen.getByRole("button", { name: "Tentar de novo o anexo a.pdf" }));
    expect(await screen.findByText("1 arquivo anexado.")).toBeTruthy();
    expect(posts(c, "/anexos")).toHaveLength(2);
  });

  it("sem `pode-anexar`, o controle avulso nem aparece", async () => {
    mockar({ "GET /api/atendimento/esic/p1": { corpo: fechadoComAnexar(false) } });
    render(<PaginaProtocolo />);
    await screen.findByText("Este protocolo está encerrado: não há o que responder.");
    expect(screen.queryByRole("region", { name: "Anexar arquivo" })).toBeNull();
    expect(screen.queryByLabelText(/Escolher arquivos para anexar/)).toBeNull();
  });

  it("sem anexo, a seção nem aparece", async () => {
    mockar({ "GET /api/atendimento/esic/p1": { corpo: fechadoComAnexar(false, []) } });
    render(<PaginaProtocolo />);
    await screen.findByText("Este protocolo está encerrado: não há o que responder.");
    expect(screen.queryByRole("region", { name: "Anexos da resposta" })).toBeNull();
  });


  // ---------------------------------------------------------------- rodada de correcoes: F1, F2, F5-F8, F10, F18 + retirar (B4)

  /** Um fetch roteavel em que o POST de anexo SO' termina quando o teste manda (para olhar a tela no meio do envio). */
  function comAnexoPendente(detalhes: { antes: unknown; depois: unknown; rotaAto?: string; corpoAto?: unknown }) {
    const rota = detalhes.rotaAto ?? "/api/esic/pedidos/p1/resposta";
    const pendentes: ((r: Response) => void)[] = [];
    let ato = false;
    const chamadas: Chamada[] = [];
    global.fetch = vi.fn(async (url: string, init?: RequestInit) => {
      const metodo = init?.method ?? "GET";
      const corpo = init?.body instanceof FormData ? { arquivo: (init.body.get("arquivo") as File).name }
        : init?.body ? JSON.parse(String(init.body)) : undefined;
      chamadas.push({ metodo, url: String(url), body: corpo });
      const json = (status: number, b: unknown) => ({ ok: status < 300, status, json: async () => b }) as Response;
      if (metodo === "GET") return json(200, ato ? detalhes.depois : detalhes.antes);
      if (String(url) === rota) { ato = true; return json(200, detalhes.corpoAto ?? { "respondida-em": "2026-07-03T15:00:00Z" }); }
      return new Promise<Response>((r) => pendentes.push(r));
    }) as unknown as typeof fetch;
    const terminar = (i: number, status = 201, b: unknown = anexoOut(`n${i}`, "a.pdf", 100)) =>
      pendentes[i]({ ok: status < 300, status, json: async () => b } as Response);
    return { chamadas, pendentes, terminar };
  }
  const responderComArquivo = async (nome = "a.pdf") => {
    fireEvent.click(await screen.findByRole("button", { name: "Responder" }));
    escolher(/Anexar arquivos à resposta/, [arquivo(nome)]);
    fireEvent.change(screen.getByLabelText("Resposta ao cidadão"), { target: { value: "Segue." } });
    fireEvent.click(screen.getByRole("button", { name: "Enviar a resposta" }));
  };

  it("F1: enquanto os anexos sobem a tela avisa, o aviso de saída está armado e 'Voltar à fila' não leva a lugar nenhum", async () => {
    const m = comAnexoPendente({ antes: abertoComAnexar(false), depois: fechadoComAnexar(true) });
    render(<PaginaProtocolo />);
    await responderComArquivo();
    await waitFor(() => expect(m.pendentes).toHaveLength(1));
    // o pedido do anexo já saiu, mas a tela ainda pode não ter pintado o aviso nem armado o `beforeunload` (que
    // é um efeito): espera-se o aviso na tela e o aviso de saída armado, não só a chamada.
    expect(await screen.findByText(/Enviando anexos, não feche esta página/)).toBeTruthy();
    const recibo = screen.getAllByRole("status").find((e) => /Resposta ao ESIC/.test(e.textContent ?? ""))!;
    expect(within(recibo).queryByRole("link", { name: "Voltar à fila" })).toBeNull();
    const inativo = within(recibo).getByText("Voltar à fila");
    expect(inativo.getAttribute("aria-disabled")).toBe("true");
    expect(inativo.hasAttribute("href")).toBe(false);
    await waitFor(() => {
      const ev = new Event("beforeunload", { cancelable: true });
      window.dispatchEvent(ev);
      expect(ev.defaultPrevented).toBe(true);
    });
    m.terminar(0);
    await waitFor(() => expect(screen.queryByText(/Enviando anexos, não feche esta página/)).toBeNull());
    expect(within(recibo).getByRole("link", { name: "Voltar à fila" }).getAttribute("href")).toBe("/atendimento?aba=esic&token=tk");
    await waitFor(() => {
      const livre = new Event("beforeunload", { cancelable: true });
      window.dispatchEvent(livre);
      expect(livre.defaultPrevented).toBe(false);
    });
  });

  it("F5: os botões de ato ficam desligados enquanto os anexos sobem (nada de segundo ato no meio)", async () => {
    const m = comAnexoPendente({ antes: abertoComAnexar(false), depois: abertoComAnexar(true) });
    render(<PaginaProtocolo />);
    await responderComArquivo();
    await waitFor(() => expect(m.pendentes).toHaveLength(1));
    const botao = await screen.findByRole("button", { name: "Indeferir" });
    expect((botao as HTMLButtonElement).disabled).toBe(true);
    expect((screen.getByRole("button", { name: "Responder" }) as HTMLButtonElement).disabled).toBe(true);
    m.terminar(0);
    await waitFor(() => expect((screen.getByRole("button", { name: "Indeferir" }) as HTMLButtonElement).disabled).toBe(false));
  });

  it("F8: depois do ato o recibo recebe o foco e a área do resultado é rolada para a vista", async () => {
    const rolar = vi.fn();
    Element.prototype.scrollIntoView = rolar;
    mockar({
      "GET /api/atendimento/esic/p1": { corpo: abertoComAnexar(false) },
      "POST /api/esic/pedidos/p1/resposta": { corpo: { "respondida-em": "2026-07-03T15:00:00Z" } },
    });
    render(<PaginaProtocolo />);
    fireEvent.click(await screen.findByRole("button", { name: "Responder" }));
    fireEvent.change(screen.getByLabelText("Resposta ao cidadão"), { target: { value: "Segue." } });
    fireEvent.click(screen.getByRole("button", { name: "Enviar a resposta" }));
    const recibo = await screen.findByRole("status");
    await waitFor(() => expect(document.activeElement).toBe(recibo));
    expect(rolar).toHaveBeenCalled();
  });

  it("F2: manifestação ANÔNIMA — a dica dos anexos não diz que quem pediu baixa: o arquivo fica só no registro da Casa", async () => {
    estado.params = { tipo: "ouvidoria", id: "m1" };
    const base = { id: "m1", protocolo: "OUV-2026-000004", tipo: "denuncia", assunto: "Obra parada", descricao: "Na rua A.",
      identificacao: "anonima", anexos: [], ...prazo(20) };
    mockar({ "GET /api/atendimento/ouvidoria/m1": { corpo: { ...base, estado: "protocolada", historico: [],
      acoes: { "pode-responder": true, "pode-arquivar": true, "pode-prorrogar": true, "pode-anexar": false } } } });
    const { unmount } = render(<PaginaProtocolo />);
    fireEvent.click(await screen.findByRole("button", { name: "Responder" }));
    const dica = screen.getByText(/Até 5 arquivos de até 10 MB/).textContent ?? "";
    expect(dica).toMatch(/só no registro da Casa/);
    expect(dica).not.toMatch(/quem pediu os baixa/);
    unmount();
    cleanup();
    // e o controle avulso (depois da resposta) diz o mesmo
    mockar({ "GET /api/atendimento/ouvidoria/m1": { corpo: { ...base, estado: "respondida", aberto: false, "dias-restantes": null,
      historico: [{ tipo: "resposta", em: "2026-07-03T15:00:00Z", texto: "Segue.", por: "Joana" }],
      acoes: { "pode-responder": false, "pode-arquivar": false, "pode-prorrogar": false, "pode-anexar": true } } } });
    render(<PaginaProtocolo />);
    const secao = await screen.findByRole("region", { name: "Anexar arquivo" });
    expect(secao.textContent).toMatch(/só no registro da Casa/);
    expect(secao.textContent).not.toMatch(/quem pediu os baixa/);
  });

  it("F6: 'não sei' não é 'não pode' — com o detalhe sem resposta (rede), o retry fica ligado e a tela diz que não deu para confirmar", async () => {
    let gets = 0;
    let anexos = 0;
    let respondido = false;
    global.fetch = vi.fn(async (url: string, init?: RequestInit) => {
      const metodo = init?.method ?? "GET";
      const json = (status: number, b: unknown) => ({ ok: status < 300, status, json: async () => b }) as Response;
      if (metodo === "GET") {
        gets++;
        if (respondido && gets === 2) throw new TypeError("rede caiu");   // a releitura depois do ato falha
        return json(200, respondido ? fechadoComAnexar(true) : abertoComAnexar(false));
      }
      if (String(url).endsWith("/resposta")) { respondido = true; return json(200, { "respondida-em": "2026-07-03T15:00:00Z" }); }
      anexos++;
      return anexos === 1 ? json(500, {}) : json(201, anexoOut("n1", "a.pdf", 100));
    }) as unknown as typeof fetch;
    render(<PaginaProtocolo />);
    await responderComArquivo();
    const botao = await screen.findByRole("button", { name: "Tentar de novo o anexo a.pdf" });
    await waitFor(() => expect(screen.getByText(/Não deu para confirmar se ainda cabe anexar/)).toBeTruthy());
    expect(screen.queryByText(/Já não cabe anexar/)).toBeNull();
    expect((botao as HTMLButtonElement).disabled).toBe(false);
    fireEvent.click(botao);
    expect(await screen.findByText("1 arquivo anexado.")).toBeTruthy();
  });

  it("F10: falha de rede ao anexar não diz 'nada foi gravado': diz que não deu para confirmar e que tentar de novo é seguro", async () => {
    let anexos = 0;
    let respondido = false;
    global.fetch = vi.fn(async (url: string, init?: RequestInit) => {
      const metodo = init?.method ?? "GET";
      const json = (status: number, b: unknown) => ({ ok: status < 300, status, json: async () => b }) as Response;
      if (metodo === "GET") return json(200, respondido ? fechadoComAnexar(true) : abertoComAnexar(false));
      if (String(url).endsWith("/resposta")) { respondido = true; return json(200, { "respondida-em": "2026-07-03T15:00:00Z" }); }
      if (++anexos === 1) throw new TypeError("rede caiu");
      return json(201, anexoOut("n1", "a.pdf", 100));
    }) as unknown as typeof fetch;
    render(<PaginaProtocolo />);
    await responderComArquivo();
    const falha = await screen.findByText(/não foi anexado:/);
    expect(falha.textContent).toMatch(/não deu para confirmar/);
    expect(falha.textContent).toMatch(/seguro/);
    expect(falha.textContent).not.toMatch(/Nada foi gravado/);
  });

  it("413 no anexo: a frase diz o limite de 10 MB e qual arquivo", async () => {
    let respondido = false;
    mockar({
      "GET /api/atendimento/esic/p1": () => ({ corpo: respondido ? fechadoComAnexar(true) : abertoComAnexar(false) }),
      "POST /api/esic/pedidos/p1/resposta": () => { respondido = true; return { corpo: { "respondida-em": "2026-07-03T15:00:00Z" } }; },
      "POST /api/atendimento/esic/p1/anexos": { status: 413, corpo: { erro: "O anexo passa de 10 MB." } },
    });
    render(<PaginaProtocolo />);
    await responderComArquivo("grande.pdf");
    const falha = await screen.findByText(/não foi anexado: O arquivo passa de 10 MB, o limite por anexo\./);
    expect(falha.closest("li")!.textContent).toMatch(/grande\.pdf/);
  });

  it("F7: indeferir — o foco vai para o título ao entrar na confirmação e ao voltar; o erro antigo some", async () => {
    let tentativa = 0;
    mockar({
      "GET /api/atendimento/esic/p1": { corpo: abertoComAnexar(false) },
      "POST /api/esic/pedidos/p1/indeferir": () => ++tentativa === 1
        ? { status: 500, corpo: {} } : { corpo: { "indeferido-em": "2026-07-03T15:00:00Z" } },
    });
    render(<PaginaProtocolo />);
    fireEvent.click(await screen.findByRole("button", { name: "Indeferir" }));
    fireEvent.change(screen.getByLabelText("Fundamentação do indeferimento"), { target: { value: "Dado pessoal de terceiros." } });
    fireEvent.click(screen.getByRole("button", { name: "Revisar o indeferimento" }));
    await waitFor(() => expect(document.activeElement).toBe(screen.getByRole("heading", { name: "Confirmar o indeferimento" })));
    fireEvent.click(screen.getByRole("button", { name: "Confirmar o indeferimento" }));
    // o servidor falhou: volta ao editor, com o erro
    expect((await screen.findByRole("alert")).textContent).toMatch(/Não foi possível|tente de novo|erro/i);
    // "Revisar" logo depois do erro, sem esperar nada: o efeito de foco do commit do erro pode ainda não ter
    // rodado, e não pode gastar o destino do foco deste passo (com `npm run test:atraso` essa ordem é a regra)
    fireEvent.click(screen.getByRole("button", { name: "Revisar o indeferimento" }));
    expect(screen.queryByRole("alert")).toBeNull();   // o erro de antes nao acompanha a confirmacao
    await waitFor(() => expect(document.activeElement).toBe(screen.getByRole("heading", { name: "Confirmar o indeferimento" })));
    fireEvent.click(screen.getByRole("button", { name: "Voltar e editar" }));
    expect(screen.queryByRole("alert")).toBeNull();
    await waitFor(() => expect(document.activeElement).toBe(screen.getByRole("heading", { name: "Indeferir o pedido" })));
  });

  // ---- B4: retirar anexo (a secretaria, com motivo obrigatório e confirmação)

  const anexoRetirado = (id: string, nome: string, origem: string, motivo = "contém dado pessoal de terceiros") =>
    ({ ...anexoOut(id, nome, 100, "application/pdf", origem), "retirado-em": "2026-07-04T10:00:00Z", "motivo-da-retirada": motivo });

  it("retirar: botão por anexo, motivo obrigatório, confirmação — e a lista se relê", async () => {
    let retirado = false;
    const c = mockar({
      "GET /api/atendimento/esic/p1": () => ({ corpo: fechadoComAnexar(false, [
        retirado ? anexoRetirado("a1", "contrato.pdf", "requerente") : anexoOut("a1", "contrato.pdf", 100, "application/pdf", "requerente"),
        anexoOut("a2", "resposta.pdf", 100),
      ]) }),
      "POST /api/atendimento/esic/p1/anexos/a1/retirar": () => { retirado = true; return { corpo: anexoRetirado("a1", "contrato.pdf", "requerente") }; },
    });
    render(<PaginaProtocolo />);
    const doPedido = await screen.findByRole("region", { name: "Anexos do pedido" });
    fireEvent.click(within(doPedido).getByRole("button", { name: "Retirar contrato.pdf" }));
    const grupo = screen.getByRole("group", { name: "Retirar o anexo contrato.pdf" });
    const confirmar = within(grupo).getByRole("button", { name: "Confirmar a retirada" }) as HTMLButtonElement;
    expect(confirmar.disabled).toBe(true);   // sem motivo, nao confirma
    expect(within(grupo).getByText(/não pode ser desfeita|definitiv/i)).toBeTruthy();
    fireEvent.change(within(grupo).getByLabelText("Motivo da retirada"), { target: { value: "   " } });
    expect(confirmar.disabled).toBe(true);
    fireEvent.change(within(grupo).getByLabelText("Motivo da retirada"), { target: { value: "  contém dado pessoal de terceiros  " } });
    expect(confirmar.disabled).toBe(false);
    fireEvent.click(confirmar);
    await waitFor(() => expect(c.some((x) => x.url.endsWith("/a1/retirar"))).toBe(true));
    expect(c.find((x) => x.url.endsWith("/a1/retirar"))!.body).toEqual({ motivo: "contém dado pessoal de terceiros" });
    // o detalhe foi relido: o anexo aparece como retirado, com a data e o motivo (so' o balcao o ve), sem download nem novo "Retirar"
    const depois = await screen.findByRole("region", { name: "Anexos do pedido" });
    await waitFor(() => expect(within(depois).getByText(/Retirado em/)).toBeTruthy());
    expect(within(depois).getByText(/contém dado pessoal de terceiros/)).toBeTruthy();
    expect(within(depois).queryByRole("button", { name: /Baixar contrato\.pdf/ })).toBeNull();
    expect(within(depois).queryByRole("link", { name: /Baixar contrato\.pdf/ })).toBeNull();
    expect(within(depois).queryByRole("button", { name: /Retirar contrato\.pdf/ })).toBeNull();
    // o outro anexo (da Casa) segue com download e com "Retirar"
    const daResposta = screen.getByRole("region", { name: "Anexos da resposta" });
    expect(within(daResposta).getByRole("button", { name: "Baixar resposta.pdf" })).toBeTruthy();
    expect(within(daResposta).getByRole("button", { name: "Retirar resposta.pdf" })).toBeTruthy();
  });

  it("retirar: cancelar não envia nada; a falha do servidor aparece e o motivo digitado fica", async () => {
    const c = mockar({
      "GET /api/atendimento/esic/p1": { corpo: fechadoComAnexar(false, [anexoOut("a2", "resposta.pdf", 100)]) },
      "POST /api/atendimento/esic/p1/anexos/a2/retirar": { status: 404, corpo: { erro: "anexo nao encontrado" } },
    });
    render(<PaginaProtocolo />);
    const secao = await screen.findByRole("region", { name: "Anexos da resposta" });
    fireEvent.click(within(secao).getByRole("button", { name: "Retirar resposta.pdf" }));
    fireEvent.click(screen.getByRole("button", { name: "Cancelar a retirada" }));
    expect(screen.queryByRole("group", { name: /Retirar o anexo/ })).toBeNull();
    expect(c.some((x) => x.metodo === "POST")).toBe(false);
    fireEvent.click(within(secao).getByRole("button", { name: "Retirar resposta.pdf" }));
    fireEvent.change(screen.getByLabelText("Motivo da retirada"), { target: { value: "arquivo errado" } });
    fireEvent.click(screen.getByRole("button", { name: "Confirmar a retirada" }));
    expect((await screen.findByRole("alert")).textContent).toMatch(/Não foi possível|não encontr|Não encontramos/i);
    expect((screen.getByLabelText("Motivo da retirada") as HTMLTextAreaElement).value).toBe("arquivo errado");
  });

  it("fila que não existe no endereço: frase, sem buscar", () => {
    estado.params = { tipo: "moderacao", id: "x" };
    const c = mockar({});
    render(<PaginaProtocolo />);
    expect(screen.getByRole("alert").textContent).toMatch(/Endereço inválido/);
    expect(c).toHaveLength(0);
  });
});
