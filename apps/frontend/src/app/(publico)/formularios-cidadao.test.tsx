import { afterEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { FormEsic, FormLgpd, FormOuvidoria } from "./formularios-cidadao";

const ENTE = "10000000-0000-0000-0000-000000000001";
const cidada = { estado: "cidada" as const, token: "tok" };

function respondeCom(status: number, corpo: unknown) {
  global.fetch = vi.fn(async () => ({ ok: status < 300, status, json: async () => corpo }) as Response) as unknown as typeof fetch;
}
const corpoEnviado = () => JSON.parse(String(vi.mocked(global.fetch).mock.calls[0][1]?.body));

describe("formulários do cidadão", () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it("anônima: no lugar do formulário, o convite do gov.br que volta a este formulário", () => {
    render(<FormEsic ente={ENTE} sessao={{ estado: "anonima", token: null }} />);
    const link = screen.getByRole("link", { name: /entrar com gov\.br/i });
    expect(decodeURIComponent(link.getAttribute("href")!)).toContain(`redirect=/portal/casa/${ENTE}/esic/novo`);
    expect(screen.queryByLabelText(/assunto/i)).toBeNull();
  });

  it("e-SIC: envia assunto e descrição e mostra o recibo com o protocolo", async () => {
    respondeCom(201, { protocolo: "ESIC-2026-000007", "recibo-em": "2026-07-03T12:00:00Z" });
    render(<FormEsic ente={ENTE} sessao={cidada} />);
    fireEvent.change(screen.getByLabelText(/assunto/i), { target: { value: "Contratos de 2025" } });
    fireEvent.change(screen.getByLabelText(/o que você quer saber/i), { target: { value: "A lista dos contratos." } });
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: /enviar pedido/i }));
    });
    await waitFor(() => expect(screen.getByText("ESIC-2026-000007")).toBeTruthy());
    expect(corpoEnviado()).toEqual({ assunto: "Contratos de 2025", descricao: "A lista dos contratos." });
    expect(vi.mocked(global.fetch).mock.calls[0][0]).toBe("/api/portal/esic/pedidos");
    expect(screen.getByRole("link", { name: /meus protocolos/i }).getAttribute("href")).toBe("/meus-protocolos");
  });

  it("Casa com o sistema restrito (ADR-0018): o recibo diz, e o pedido vale", async () => {
    respondeCom(201, { protocolo: "ESIC-2026-000008", "recibo-em": "2026-09-30T12:00:00Z",
      "acesso-restrito-desde": "2026-09-29T13:00:00Z" });
    render(<FormEsic ente={ENTE} sessao={cidada} />);
    fireEvent.change(screen.getByLabelText(/assunto/i), { target: { value: "Contratos de 2025" } });
    fireEvent.change(screen.getByLabelText(/o que você quer saber/i), { target: { value: "A lista dos contratos." } });
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: /enviar pedido/i }));
    });
    await waitFor(() => expect(screen.getByText("ESIC-2026-000008")).toBeTruthy());
    expect(screen.getByText(/acesso restrito desde 29\/09\. Seu pedido foi recebido normalmente e o prazo legal/)).toBeTruthy();
  });

  it("Casa ativa: o recibo não fala de restrição", async () => {
    respondeCom(201, { protocolo: "ESIC-2026-000009", "recibo-em": "2026-09-30T12:00:00Z" });
    render(<FormEsic ente={ENTE} sessao={cidada} />);
    fireEvent.change(screen.getByLabelText(/assunto/i), { target: { value: "Contratos de 2025" } });
    fireEvent.change(screen.getByLabelText(/o que você quer saber/i), { target: { value: "A lista dos contratos." } });
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: /enviar pedido/i }));
    });
    await waitFor(() => expect(screen.getByText("ESIC-2026-000009")).toBeTruthy());
    expect(screen.queryByText(/acesso restrito/)).toBeNull();
  });

  it("e-SIC: campo vazio não vai ao backend e diz o que falta", async () => {
    respondeCom(201, {});
    render(<FormEsic ente={ENTE} sessao={cidada} />);
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: /enviar pedido/i }));
    });
    expect(screen.getByRole("alert").textContent).toMatch(/assunto/i);
    expect(global.fetch).not.toHaveBeenCalled();
  });

  it("LGPD: o direito que veio do balcão já vem marcado", async () => {
    respondeCom(201, { protocolo: "LGPD-2026-000001", "recibo-em": "2026-07-03T12:00:00Z" });
    render(<FormLgpd ente={ENTE} sessao={cidada} tipoInicial="eliminar" />);
    expect((screen.getByLabelText(/eliminar meus dados/i) as HTMLInputElement).checked).toBe(true);
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: /enviar pedido/i }));
    });
    await waitFor(() => expect(screen.getByText("LGPD-2026-000001")).toBeTruthy());
    expect(corpoEnviado()).toEqual({ tipo: "eliminar" });
  });

  it("ouvidoria: sem se identificar vai anonima=true e o recibo avisa que só o protocolo acompanha", async () => {
    respondeCom(201, { protocolo: "OUV-2026-000003", "recibo-em": "2026-07-03T12:00:00Z" });
    render(<FormOuvidoria ente={ENTE} sessao={cidada} />);
    fireEvent.click(screen.getByLabelText(/sugestão/i));
    fireEvent.change(screen.getByLabelText(/^assunto/i), { target: { value: "Horário" } });
    fireEvent.change(screen.getByLabelText(/descrição/i), { target: { value: "Abrir mais cedo." } });
    fireEvent.click(screen.getByLabelText(/manifestar sem me identificar/i));
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: /enviar manifestação/i }));
    });
    await waitFor(() => expect(screen.getByText("OUV-2026-000003")).toBeTruthy());
    expect(corpoEnviado()).toEqual({ tipo: "sugestao", assunto: "Horário", descricao: "Abrir mais cedo.", anonima: true });
    expect(screen.getByText(/guarde este número/i)).toBeTruthy();
    expect(screen.queryByRole("link", { name: /meus protocolos/i })).toBeNull();
  });

  it("ouvidoria: o trilho acompanha pelo protocolo (rota pública)", async () => {
    respondeCom(200, { protocolo: "OUV-2026-000003", estado: "em_analise", "dias-restantes": 12 });
    render(<FormOuvidoria ente={ENTE} sessao={{ estado: "anonima", token: null }} />);
    fireEvent.change(screen.getByLabelText(/número do protocolo/i), { target: { value: "OUV-2026-000003" } });
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: /^ver$/i }));
    });
    await waitFor(() => expect(screen.getByText(/em análise/i)).toBeTruthy());
    expect(vi.mocked(global.fetch).mock.calls[0][0]).toBe(`/api/portal/casa/${ENTE}/ouvidoria/acompanhar/OUV-2026-000003`);
  });
});

// ---------------------------------------------------------------- os anexos do pedido (o requerente anexa ao proprio pedido)

type Rota = { status?: number; corpo: unknown };
function roteiro(rotas: Record<string, Rota | ((n: number) => Rota)>) {
  const chamadas: { metodo: string; url: string; arquivo?: string; json?: unknown }[] = [];
  const contagem: Record<string, number> = {};
  global.fetch = vi.fn(async (url: string, init?: RequestInit) => {
    const metodo = init?.method ?? "GET";
    const chave = `${metodo} ${String(url)}`;
    contagem[chave] = (contagem[chave] ?? 0) + 1;
    chamadas.push({
      metodo, url: String(url),
      arquivo: init?.body instanceof FormData ? (init.body.get("arquivo") as File).name : undefined,
      json: typeof init?.body === "string" ? JSON.parse(init.body) : undefined,
    });
    const r = rotas[chave];
    const rota = typeof r === "function" ? r(contagem[chave]) : r;
    if (!rota) return { ok: false, status: 404, json: async () => ({}) } as Response;
    const status = rota.status ?? 200;
    return { ok: status < 300, status, json: async () => rota.corpo } as Response;
  }) as unknown as typeof fetch;
  return chamadas;
}
const arquivo = (nome: string, tamanho = 10) => new File([new Uint8Array(tamanho)], nome);
const itemDoProtocolo = (id: string, protocolo: string) => ({ id, protocolo, estado: "protocolado", "recibo-em": "2026-07-03T12:00:00Z",
  "vence-em": "2026-07-23", "dias-restantes": 20, resposta: null, anexos: [], "pode-anexar": true });
const ANEXO_OK = { id: "n1", nome: "x", "tipo-midia": "application/pdf", bytes: 10, origem: "requerente", "enviado-em": "2026-07-03T12:00:00Z" };

describe("formulários do cidadão — anexos do pedido", () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it("e-SIC: depois do recibo, acha o protocolo em Meus protocolos e envia os arquivos um a um, com o resultado junto do recibo", async () => {
    const c = roteiro({
      "POST /api/portal/esic/pedidos": { status: 201, corpo: { protocolo: "ESIC-2026-000007", "recibo-em": "2026-07-03T12:00:00Z" } },
      "GET /api/portal/meus-protocolos": { corpo: { "pedidos-esic": [itemDoProtocolo("p0", "ESIC-2026-000001"), itemDoProtocolo("p77", "ESIC-2026-000007")],
        "solicitacoes-lgpd": [], manifestacoes: [] } },
      "POST /api/portal/meus-protocolos/esic/p77/anexos": { status: 201, corpo: ANEXO_OK },
    });
    render(<FormEsic ente={ENTE} sessao={cidada} />);
    expect(screen.getByText(/Até 5 arquivos de até 10 MB: PDF, PNG, JPEG, TXT, CSV, DOCX, XLSX, ODT e ODS/)).toBeTruthy();
    expect(screen.getByText(/depois de 10 minutos não dá mais para anexar/)).toBeTruthy();
    fireEvent.change(screen.getByLabelText(/assunto/i), { target: { value: "Contratos de 2025" } });
    fireEvent.change(screen.getByLabelText(/o que você quer saber/i), { target: { value: "A lista dos contratos." } });
    fireEvent.change(screen.getByLabelText(/Anexar documentos ao pedido/), { target: { files: [arquivo("contrato.pdf"), arquivo("foto.png"), arquivo("virus.exe")] } });
    expect(screen.getByRole("alert").textContent).toMatch(/“virus.exe” não é de um tipo aceito/);
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: /enviar pedido/i }));
    });
    await waitFor(() => expect(screen.getByText("ESIC-2026-000007")).toBeTruthy());
    await waitFor(() => expect(screen.getByText("2 arquivos anexados.")).toBeTruthy());
    expect(c.map((x) => [x.metodo, x.url, x.arquivo])).toEqual([
      ["POST", "/api/portal/esic/pedidos", undefined],
      ["GET", "/api/portal/meus-protocolos", undefined],
      ["POST", "/api/portal/meus-protocolos/esic/p77/anexos", "contrato.pdf"],
      ["POST", "/api/portal/meus-protocolos/esic/p77/anexos", "foto.png"],
    ]);
    expect(c[0].json).toEqual({ assunto: "Contratos de 2025", descricao: "A lista dos contratos." });
  });

  it("falha parcial: diz qual arquivo falhou e por quê, e deixa tentar de novo", async () => {
    const c = roteiro({
      "POST /api/portal/esic/pedidos": { status: 201, corpo: { protocolo: "ESIC-2026-000007", "recibo-em": "2026-07-03T12:00:00Z" } },
      "GET /api/portal/meus-protocolos": { corpo: { "pedidos-esic": [itemDoProtocolo("p77", "ESIC-2026-000007")], "solicitacoes-lgpd": [], manifestacoes: [] } },
      "POST /api/portal/meus-protocolos/esic/p77/anexos": (n) => n === 2
        ? { status: 415, corpo: { erro: "Tipo de arquivo não aceito. Aceitamos PDF." } } : { status: 201, corpo: ANEXO_OK },
    });
    render(<FormEsic ente={ENTE} sessao={cidada} />);
    fireEvent.change(screen.getByLabelText(/assunto/i), { target: { value: "Contratos de 2025" } });
    fireEvent.change(screen.getByLabelText(/o que você quer saber/i), { target: { value: "A lista." } });
    fireEvent.change(screen.getByLabelText(/Anexar documentos ao pedido/), { target: { files: [arquivo("a.pdf"), arquivo("b.pdf"), arquivo("c.pdf")] } });
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: /enviar pedido/i }));
    });
    expect(await screen.findByText("2 arquivos anexados; 1 não foi anexado.")).toBeTruthy();
    expect(screen.getByText(/não foi anexado: Tipo de arquivo não aceito\. Aceitamos PDF\./).closest("li")!.textContent).toMatch(/b\.pdf/);
    // o pedido JA' foi registrado: o recibo segue na tela
    expect(screen.getByText("ESIC-2026-000007")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Tentar de novo o anexo b.pdf" }));
    expect(await screen.findByText("3 arquivos anexados.")).toBeTruthy();
    expect(c.filter((x) => x.url.endsWith("/anexos")).map((x) => x.arquivo)).toEqual(["a.pdf", "b.pdf", "c.pdf", "b.pdf"]);
  });

  it("se o protocolo não for achado em Meus protocolos, o recibo fica e a tela explica onde anexar", async () => {
    roteiro({
      "POST /api/portal/esic/pedidos": { status: 201, corpo: { protocolo: "ESIC-2026-000007", "recibo-em": "2026-07-03T12:00:00Z" } },
      "GET /api/portal/meus-protocolos": { corpo: { "pedidos-esic": [], "solicitacoes-lgpd": [], manifestacoes: [] } },
    });
    render(<FormEsic ente={ENTE} sessao={cidada} />);
    fireEvent.change(screen.getByLabelText(/assunto/i), { target: { value: "Contratos de 2025" } });
    fireEvent.change(screen.getByLabelText(/o que você quer saber/i), { target: { value: "A lista." } });
    fireEvent.change(screen.getByLabelText(/Anexar documentos ao pedido/), { target: { files: [arquivo("a.pdf")] } });
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: /enviar pedido/i }));
    });
    expect(await screen.findByText(/não conseguimos enviar os arquivos agora\. Anexe em Meus protocolos/)).toBeTruthy();
    expect(screen.getByText("ESIC-2026-000007")).toBeTruthy();
  });

  it("LGPD: os arquivos vão para a solicitação (a chave da lista é a da LGPD)", async () => {
    const c = roteiro({
      "POST /api/portal/lgpd/solicitacoes": { status: 201, corpo: { protocolo: "LGPD-2026-000003", "recibo-em": "2026-07-03T12:00:00Z" } },
      "GET /api/portal/meus-protocolos": { corpo: { "pedidos-esic": [], "solicitacoes-lgpd": [itemDoProtocolo("s3", "LGPD-2026-000003")], manifestacoes: [] } },
      "POST /api/portal/meus-protocolos/lgpd/s3/anexos": { status: 201, corpo: ANEXO_OK },
    });
    render(<FormLgpd ente={ENTE} sessao={cidada} tipoInicial="eliminar" />);
    fireEvent.change(screen.getByLabelText(/Anexar documentos ao pedido/), { target: { files: [arquivo("identidade.pdf")] } });
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: /enviar pedido/i }));
    });
    expect(await screen.findByText("1 arquivo anexado.")).toBeTruthy();
    expect(c.map((x) => x.url)).toEqual(["/api/portal/lgpd/solicitacoes", "/api/portal/meus-protocolos", "/api/portal/meus-protocolos/lgpd/s3/anexos"]);
  });

  it("ouvidoria identificada anexa; ANÔNIMA não tem anexo (o seletor some e nada é enviado)", async () => {
    const c = roteiro({
      "POST /api/portal/ouvidoria/manifestacoes": { status: 201, corpo: { protocolo: "OUV-2026-000004", "recibo-em": "2026-07-03T12:00:00Z" } },
      "GET /api/portal/meus-protocolos": { corpo: { "pedidos-esic": [], "solicitacoes-lgpd": [], manifestacoes: [itemDoProtocolo("m4", "OUV-2026-000004")] } },
      "POST /api/portal/meus-protocolos/ouvidoria/m4/anexos": { status: 201, corpo: ANEXO_OK },
    });
    render(<FormOuvidoria ente={ENTE} sessao={cidada} />);
    // identificada (o padrao): o seletor esta la'
    expect(screen.getByLabelText(/Anexar documentos à manifestação/)).toBeTruthy();
    fireEvent.change(screen.getByLabelText(/Anexar documentos à manifestação/), { target: { files: [arquivo("foto.png")] } });
    // escolhe manifestar sem se identificar: o seletor some, com a razao, e o que foi escolhido nao vai
    fireEvent.click(screen.getByRole("radio", { name: /manifestar sem me identificar/i }));
    expect(screen.queryByLabelText(/Anexar documentos à manifestação/)).toBeNull();
    expect(screen.getByText(/Manifestação anônima não leva anexo/)).toBeTruthy();
    fireEvent.change(screen.getByLabelText(/assunto/i), { target: { value: "Fila" } });
    fireEvent.change(screen.getByLabelText(/descrição/i), { target: { value: "Demora." } });
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: /enviar manifestação/i }));
    });
    await waitFor(() => expect(screen.getByText("OUV-2026-000004")).toBeTruthy());
    expect(c.map((x) => x.url)).toEqual(["/api/portal/ouvidoria/manifestacoes"]);   // so' a manifestacao: nada de busca nem upload
    expect(c[0].json).toMatchObject({ anonima: true });
  });

  it("ouvidoria identificada: depois do recibo, os arquivos sobem pela rota da ouvidoria", async () => {
    const c = roteiro({
      "POST /api/portal/ouvidoria/manifestacoes": { status: 201, corpo: { protocolo: "OUV-2026-000004", "recibo-em": "2026-07-03T12:00:00Z" } },
      "GET /api/portal/meus-protocolos": { corpo: { "pedidos-esic": [], "solicitacoes-lgpd": [], manifestacoes: [itemDoProtocolo("m4", "OUV-2026-000004")] } },
      "POST /api/portal/meus-protocolos/ouvidoria/m4/anexos": { status: 201, corpo: ANEXO_OK },
    });
    render(<FormOuvidoria ente={ENTE} sessao={cidada} />);
    fireEvent.change(screen.getByLabelText(/assunto/i), { target: { value: "Fila" } });
    fireEvent.change(screen.getByLabelText(/descrição/i), { target: { value: "Demora." } });
    fireEvent.change(screen.getByLabelText(/Anexar documentos à manifestação/), { target: { files: [arquivo("foto.png")] } });
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: /enviar manifestação/i }));
    });
    expect(await screen.findByText("1 arquivo anexado.")).toBeTruthy();
    expect(c.map((x) => x.url)).toEqual(["/api/portal/ouvidoria/manifestacoes", "/api/portal/meus-protocolos", "/api/portal/meus-protocolos/ouvidoria/m4/anexos"]);
  });

  it("sem arquivo escolhido, o fluxo é o de sempre: nenhuma chamada a mais", async () => {
    const c = roteiro({
      "POST /api/portal/esic/pedidos": { status: 201, corpo: { protocolo: "ESIC-2026-000007", "recibo-em": "2026-07-03T12:00:00Z" } },
    });
    render(<FormEsic ente={ENTE} sessao={cidada} />);
    fireEvent.change(screen.getByLabelText(/assunto/i), { target: { value: "Contratos de 2025" } });
    fireEvent.change(screen.getByLabelText(/o que você quer saber/i), { target: { value: "A lista." } });
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: /enviar pedido/i }));
    });
    await waitFor(() => expect(screen.getByText("ESIC-2026-000007")).toBeTruthy());
    expect(c).toHaveLength(1);
  });
});

// ---------------------------------------------------------------- rodada de correcoes: F1, F3, F4, F12

/** POST do pedido responde na hora; o GET de Meus protocolos e os POST de anexo so' terminam quando o teste manda. */
function roteiroPendente(opcoes: { lista: unknown; getFalha?: number }) {
  const pendentes: { chave: string; termina: (status?: number, corpo?: unknown) => void }[] = [];
  const chamadas: { metodo: string; url: string; arquivo?: string }[] = [];
  let gets = 0;
  global.fetch = vi.fn(async (url: string, init?: RequestInit) => {
    const metodo = init?.method ?? "GET";
    chamadas.push({ metodo, url: String(url), arquivo: init?.body instanceof FormData ? (init.body.get("arquivo") as File).name : undefined });
    const json = (status: number, corpo: unknown) => ({ ok: status < 300, status, json: async () => corpo }) as Response;
    if (metodo === "POST" && !String(url).endsWith("/anexos"))
      return json(201, { protocolo: "ESIC-2026-000007", "recibo-em": "2026-07-03T12:00:00Z" });
    if (metodo === "GET") {
      gets++;
      if (opcoes.getFalha && gets <= opcoes.getFalha) return json(500, {});
      return json(200, opcoes.lista);
    }
    return new Promise<Response>((resolve) => pendentes.push({ chave: String(url), termina: (st = 201, corpo = ANEXO_OK) => resolve(json(st, corpo)) }));
  }) as unknown as typeof fetch;
  return { pendentes, chamadas };
}
const listaDoEsic = { "pedidos-esic": [itemDoProtocolo("p77", "ESIC-2026-000007")], "solicitacoes-lgpd": [], manifestacoes: [] };
async function enviarEsicComArquivos(nomes: string[]) {
  render(<FormEsic ente={ENTE} sessao={cidada} />);
  fireEvent.change(screen.getByLabelText(/assunto/i), { target: { value: "Contratos de 2025" } });
  fireEvent.change(screen.getByLabelText(/o que você quer saber/i), { target: { value: "A lista." } });
  fireEvent.change(screen.getByLabelText(/Anexar documentos ao pedido/), { target: { files: nomes.map((n) => arquivo(n)) } });
  await act(async () => {
    fireEvent.click(screen.getByRole("button", { name: /enviar pedido/i }));
  });
}

describe("formulários do cidadão — o envio dos anexos depois do recibo (rodada de correções)", () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it("F3: 'Enviando seus anexos…' aparece NA HORA do recibo, antes de a busca do id responder (sem intervalo mudo)", async () => {
    // o GET de Meus protocolos fica pendente: simulamos com uma lista que so' chega depois do assert
    let soltar!: () => void;
    const segura = new Promise<void>((r) => { soltar = r; });
    const base = roteiroPendente({ lista: listaDoEsic });
    const original = global.fetch;
    global.fetch = vi.fn(async (url: string, init?: RequestInit) => {
      if ((init?.method ?? "GET") === "GET") await segura;
      return (original as unknown as (u: string, i?: RequestInit) => Promise<Response>)(url, init);
    }) as unknown as typeof fetch;
    await enviarEsicComArquivos(["a.pdf"]);
    expect(await screen.findByText("ESIC-2026-000007")).toBeTruthy();
    expect(screen.getByText("Enviando seus anexos…")).toBeTruthy();
    soltar();
    await waitFor(() => expect(base.pendentes).toHaveLength(1));
    base.pendentes[0].termina();
    expect(await screen.findByText("1 arquivo anexado.")).toBeTruthy();
    expect(screen.queryByText("Enviando seus anexos…")).toBeNull();
  });

  it("F3: se a busca do id falha (5xx), os arquivos ficam em memória, aparecem pelo nome e 'Tentar de novo' refaz a busca E o envio", async () => {
    const m = roteiroPendente({ lista: listaDoEsic, getFalha: 1 });
    await enviarEsicComArquivos(["a.pdf", "b.pdf"]);
    expect(await screen.findByText(/não conseguimos enviar os arquivos agora/)).toBeTruthy();
    const lista = screen.getByRole("list", { name: "Arquivos que não foram enviados" });
    expect(within(lista).getAllByRole("listitem").map((l) => l.textContent)).toEqual([
      expect.stringContaining("a.pdf"), expect.stringContaining("b.pdf"),
    ]);
    expect(screen.getByText("ESIC-2026-000007")).toBeTruthy();   // o recibo segue
    fireEvent.click(screen.getByRole("button", { name: "Tentar de novo o envio dos anexos" }));
    await waitFor(() => expect(m.pendentes).toHaveLength(1));
    expect(m.chamadas.filter((c) => c.metodo === "GET")).toHaveLength(2);   // refez a busca
    m.pendentes[0].termina();
    await waitFor(() => expect(m.pendentes).toHaveLength(2));
    m.pendentes[1].termina();
    expect(await screen.findByText("2 arquivos anexados.")).toBeTruthy();
    expect(screen.queryByRole("list", { name: "Arquivos que não foram enviados" })).toBeNull();
    expect(m.chamadas.filter((c) => c.arquivo).map((c) => c.arquivo)).toEqual(["a.pdf", "b.pdf"]);
  });

  it("F1: enquanto os anexos sobem, o recibo avisa para não fechar a página e os links de saída ficam inativos", async () => {
    const m = roteiroPendente({ lista: listaDoEsic });
    await enviarEsicComArquivos(["a.pdf"]);
    await waitFor(() => expect(m.pendentes).toHaveLength(1));
    expect(screen.getByText(/Enviando anexos, não feche esta página/)).toBeTruthy();
    expect(screen.queryByRole("link", { name: /Acompanhar em Meus protocolos/ })).toBeNull();
    expect(screen.queryByRole("link", { name: /Voltar ao portal/ })).toBeNull();
    expect(screen.getByText(/Acompanhar em Meus protocolos/).getAttribute("aria-disabled")).toBe("true");
    const ev = new Event("beforeunload", { cancelable: true });
    window.dispatchEvent(ev);
    expect(ev.defaultPrevented).toBe(true);
    m.pendentes[0].termina();
    await waitFor(() => expect(screen.getByRole("link", { name: /Acompanhar em Meus protocolos/ }).getAttribute("href")).toBe("/meus-protocolos"));
    expect(screen.getByRole("link", { name: /Voltar ao portal/ })).toBeTruthy();
    expect(screen.queryByText(/Enviando anexos, não feche esta página/)).toBeNull();
  });

  it("F4: o resultado do envio fica FORA do cartão role=status do recibo (cada mudança nao relê o recibo inteiro)", async () => {
    const m = roteiroPendente({ lista: listaDoEsic });
    await enviarEsicComArquivos(["a.pdf"]);
    await waitFor(() => expect(m.pendentes).toHaveLength(1));
    m.pendentes[0].termina();
    await screen.findByText("1 arquivo anexado.");
    const cartao = screen.getByRole("heading", { name: "Pedido registrado" }).closest("[role='status']")!;
    expect(cartao).toBeTruthy();
    expect(cartao.textContent).toMatch(/ESIC-2026-000007/);
    expect(cartao.textContent).not.toMatch(/arquivo anexado|Enviando/);
    expect(cartao.contains(screen.getByText("1 arquivo anexado."))).toBe(false);
  });

  it("F12: marcar 'sem me identificar' com arquivos já escolhidos AVISA que eles foram descartados", () => {
    render(<FormOuvidoria ente={ENTE} sessao={cidada} />);
    fireEvent.change(screen.getByLabelText(/Anexar documentos à manifestação/), { target: { files: [arquivo("a.pdf"), arquivo("b.png")] } });
    expect(screen.queryByText(/foram descartados/)).toBeNull();
    fireEvent.click(screen.getByRole("radio", { name: /manifestar sem me identificar/i }));
    expect(screen.getByText(/Os 2 arquivos que você escolheu foram descartados/)).toBeTruthy();
    fireEvent.click(screen.getByRole("radio", { name: /identificar-me/i }));
    expect(screen.queryByText(/foram descartados/)).toBeNull();
    // sem arquivo escolhido, nao ha o que avisar
    fireEvent.click(screen.getByRole("radio", { name: /manifestar sem me identificar/i }));
    expect(screen.queryByText(/foram descartados/)).toBeNull();
  });
});
