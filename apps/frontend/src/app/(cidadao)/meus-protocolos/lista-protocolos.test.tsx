import { afterEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { ListaProtocolos } from "./lista-protocolos";
import type { MeusProtocolos } from "@/lib/use-meus-protocolos";

// o mesmo `ListaProtocolos`, com atalho para os testes de download (modo real = sem token = link direto)
function ListaDeProtocolosComToken({ dados, token }: { dados: MeusProtocolos; token: string | null }) {
  return <ListaProtocolos dados={dados} token={token} aoMudar={() => {}} />;
}

const base = { reciboEm: "2026-07-03T12:00:00Z", venceEm: "2026-07-23", resposta: null };
const DADOS: MeusProtocolos = {
  pedidosEsic: [
    { ...base, id: "p1", protocolo: "ESIC-2026-000001", assunto: "Contratos", estado: "protocolado", diasRestantes: 12 },
    {
      ...base,
      id: "p2",
      protocolo: "ESIC-2026-000002",
      assunto: "Diárias",
      estado: "respondido",
      diasRestantes: 5,
      resposta: { corpo: "Segue a planilha.", respondidaEm: "2026-07-10T15:00:00Z" },
    },
  ],
  solicitacoesLgpd: [{ ...base, id: "s1", protocolo: "LGPD-2026-000001", tipo: "acessar", estado: "em_analise", diasRestantes: -2 }],
  manifestacoes: [],
};

describe("ListaProtocolos — o que a cidadã protocolou", () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it("mostra cada protocolo com estado e prazo; aberto em atraso diz que venceu", () => {
    render(<ListaProtocolos dados={DADOS} token="tok" aoMudar={() => {}} />);
    const p1 = screen.getByText("ESIC-2026-000001").closest("li")!;
    expect(within(p1).getByText("Protocolado")).toBeTruthy();
    expect(within(p1).getByText(/12 dias para a resposta/)).toBeTruthy();
    const s1 = screen.getByText("LGPD-2026-000001").closest("li")!;
    expect(within(s1).getByText(/Acessar meus dados/)).toBeTruthy();
    expect(within(s1).getByText(/prazo venceu há 2 dias/i)).toBeTruthy();
    expect(screen.getByText(/nenhuma manifestação identificada/i)).toBeTruthy();
  });

  it("a resposta da Câmara aparece, e só o e-SIC respondido oferece recurso", () => {
    render(<ListaProtocolos dados={DADOS} token="tok" aoMudar={() => {}} />);
    const p2 = screen.getByText("ESIC-2026-000002").closest("li")!;
    expect(within(p2).getByText("Segue a planilha.")).toBeTruthy();
    expect(within(p2).getByRole("button", { name: /recorrer/i })).toBeTruthy();
    const p1 = screen.getByText("ESIC-2026-000001").closest("li")!;
    expect(within(p1).queryByRole("button", { name: /recorrer/i })).toBeNull();
  });

  it("o protocolo indeferido: selo 'Indeferido' (e não o de aprovado), a fundamentação com o título certo e o recurso à mão", () => {
    const indeferido: MeusProtocolos = {
      ...DADOS,
      pedidosEsic: [{
        ...base, id: "p3", protocolo: "ESIC-2026-000003", assunto: "Folha de pagamento", estado: "indeferido", diasRestantes: 12,
        resposta: { corpo: "O pedido pede dado pessoal de terceiros (LAI art. 31).", respondidaEm: "2026-07-10T15:00:00Z" },
      }],
      solicitacoesLgpd: [{
        ...base, id: "s2", protocolo: "LGPD-2026-000002", tipo: "eliminar", estado: "indeferida", diasRestantes: 3,
        resposta: { corpo: "A eliminação não cabe: dados mantidos por obrigação legal.", respondidaEm: "2026-07-10T15:00:00Z" },
      }],
    };
    render(<ListaProtocolos dados={indeferido} token="tok" aoMudar={() => {}} />);
    const p3 = screen.getByText("ESIC-2026-000003").closest("li")!;
    const selo = within(p3).getByText("Indeferido");
    expect(selo.className).not.toMatch(/chip-aprovada/);
    expect(within(p3).queryByText("indeferido")).toBeNull();
    expect(within(p3).getByText(/Fundamentação do indeferimento · /)).toBeTruthy();
    expect(within(p3).queryByText(/Resposta da Câmara/)).toBeNull();
    expect(within(p3).getByText(/dado pessoal de terceiros/)).toBeTruthy();
    expect(within(p3).queryByText(/dias para a resposta/)).toBeNull();
    // o grafo ja' admite recurso de pedido indeferido: o botao fala do indeferimento
    expect(within(p3).getByRole("button", { name: "Recorrer do indeferimento" })).toBeTruthy();
    // LGPD indeferida: mesma fundamentacao, sem recurso (a LGPD nao tem)
    const s2 = screen.getByText("LGPD-2026-000002").closest("li")!;
    expect(within(s2).getByText("Indeferida")).toBeTruthy();
    expect(within(s2).getByText(/Fundamentação do indeferimento · /)).toBeTruthy();
    expect(within(s2).getByText(/dados mantidos por obrigação legal/)).toBeTruthy();
    expect(within(s2).queryByRole("button", { name: /recorrer/i })).toBeNull();
  });

  it("recorrer do indeferimento: o formulário pergunta pelo indeferimento e envia o motivo ao mesmo endpoint de recurso", async () => {
    global.fetch = vi.fn(async () =>
      ({ ok: true, status: 201, json: async () => ({ protocolo: "REC-2026-000009", "recibo-em": "2026-07-11T12:00:00Z" }) }) as Response,
    ) as unknown as typeof fetch;
    const indeferido: MeusProtocolos = {
      ...DADOS, solicitacoesLgpd: [], manifestacoes: [],
      pedidosEsic: [{ ...base, id: "p3", protocolo: "ESIC-2026-000003", assunto: "Folha", estado: "indeferido", diasRestantes: null,
        resposta: { corpo: "Dado pessoal.", respondidaEm: "2026-07-10T15:00:00Z" } }],
    };
    render(<ListaProtocolos dados={indeferido} token="tok" aoMudar={() => {}} />);
    const p3 = screen.getByText("ESIC-2026-000003").closest("li")!;
    fireEvent.click(within(p3).getByRole("button", { name: "Recorrer do indeferimento" }));
    fireEvent.change(within(p3).getByLabelText("Por que você não concordou com o indeferimento?"), { target: { value: "Peço só os valores agregados." } });
    fireEvent.click(within(p3).getByRole("button", { name: /enviar recurso/i }));
    await waitFor(() => expect(within(p3).getByText("REC-2026-000009")).toBeTruthy());
    const [url, init] = vi.mocked(global.fetch).mock.calls[0];
    expect(url).toBe("/api/portal/esic/pedidos/p3/recursos");
    expect(JSON.parse(String(init?.body))).toEqual({ motivo: "Peço só os valores agregados." });
  });

  it("o protocolo prorrogado mostra as duas datas, quando foi e a justificativa da Câmara (LAI art. 11 §2º); o não prorrogado, nada", () => {
    const prorrogacao = {
      deData: "2026-07-23",
      paraData: "2026-08-02",
      justificativa: "Busca no arquivo morto: o acervo de 2019 ainda não foi digitalizado.",
      prorrogadoEm: "2026-07-08T15:30:00Z",
    };
    const comProrrogacao: MeusProtocolos = {
      ...DADOS,
      pedidosEsic: [
        { ...base, id: "p4", protocolo: "ESIC-2026-000004", assunto: "Contratos", estado: "protocolado", diasRestantes: 20, prorrogacao },
        { ...base, id: "p5", protocolo: "ESIC-2026-000005", assunto: "Diárias", estado: "protocolado", diasRestantes: 12, prorrogacao: null },
        { ...base, id: "p6", protocolo: "ESIC-2026-000006", assunto: "Folha", estado: "protocolado", diasRestantes: 12 },
      ],
      manifestacoes: [
        { ...base, id: "m1", protocolo: "OUV-2026-000001", tipo: "reclamacao", assunto: "Fila", estado: "protocolada", diasRestantes: 25,
          prorrogacao: { ...prorrogacao, deData: "2026-08-02", paraData: "2026-09-01", justificativa: "Consulta à secretaria de obras." } },
      ],
    };
    render(<ListaProtocolos dados={comProrrogacao} token="tok" aoMudar={() => {}} />);
    const p4 = screen.getByText("ESIC-2026-000004").closest("li")!;
    expect(within(p4).getByText(/Prazo prorrogado de 23\/07\/2026 para 02\/08\/2026, em 08\/07\/2026\./)).toBeTruthy();
    const texto = within(p4).getByText(/Busca no arquivo morto/);
    expect(texto.closest("p")!.textContent).toMatch(/^Justificativa da Câmara: Busca no arquivo morto/);
    // nada de ISO cru nem de chave de contrato na tela
    expect(within(p4).queryByText(/2026-07-23|2026-08-02|T15:30/)).toBeNull();
    expect(p4.textContent).not.toMatch(/deData|paraData|prorrogacao|prorrogadoEm/);
    // a ouvidoria também
    const m1 = screen.getByText("OUV-2026-000001").closest("li")!;
    expect(within(m1).getByText(/Prazo prorrogado de 02\/08\/2026 para 01\/09\/2026, em 08\/07\/2026\./)).toBeTruthy();
    expect(within(m1).getByText(/Consulta à secretaria de obras\./)).toBeTruthy();
    // sem prorrogação (nil ou ausente): bloco nenhum
    for (const protocolo of ["ESIC-2026-000005", "ESIC-2026-000006"]) {
      const li = screen.getByText(protocolo).closest("li")!;
      expect(within(li).queryByText(/prorrogado/i)).toBeNull();
      expect(within(li).queryByText(/Justificativa da Câmara/)).toBeNull();
    }
    // e a LGPD (que não prorroga) segue sem bloco
    const s1 = screen.getByText("LGPD-2026-000001").closest("li")!;
    expect(within(s1).queryByText(/prorrogado/i)).toBeNull();
  });

  it("a justificativa é texto, nunca HTML (o servidor escreve livremente)", () => {
    const dados: MeusProtocolos = {
      ...DADOS, solicitacoesLgpd: [], manifestacoes: [],
      pedidosEsic: [{ ...base, id: "p7", protocolo: "ESIC-2026-000007", assunto: "x", estado: "protocolado", diasRestantes: 20,
        prorrogacao: { deData: "2026-07-23", paraData: "2026-08-02", prorrogadoEm: "2026-07-08T15:30:00Z",
          justificativa: "<b>negrito</b> <img src=x onerror=alert(1)>" } }],
    };
    const { container } = render(<ListaProtocolos dados={dados} token="tok" aoMudar={() => {}} />);
    expect(screen.getByText(/<b>negrito<\/b> <img src=x/)).toBeTruthy();
    expect(container.querySelector("img")).toBeNull();
    expect(container.querySelector("li b")).toBeNull();
  });

  const anexo = (id: string, nome: string, bytes: number, tipoMidia = "application/pdf", origem = "casa") =>
    ({ id, nome, tipoMidia, bytes, origem, enviadoEm: "2026-07-10T15:00:00Z" });

  it("os anexos da resposta: nome, tamanho legível e formato (nunca o tipo cru), com link para baixar como arquivo", () => {
    const dados: MeusProtocolos = {
      ...DADOS,
      pedidosEsic: [{ ...base, id: "p9", protocolo: "ESIC-2026-000009", assunto: "Folha", estado: "respondido", diasRestantes: 5,
        resposta: { corpo: "Segue a planilha.", respondidaEm: "2026-07-10T15:00:00Z" },
        anexos: [anexo("a1", "folha de 2025.pdf", 1536), anexo("a2", "dados.csv", 2 * 1024 * 1024, "text/csv")] }],
      solicitacoesLgpd: [{ ...base, id: "s9", protocolo: "LGPD-2026-000009", tipo: "acessar", estado: "respondida", diasRestantes: 1,
        resposta: { corpo: "Seus dados.", respondidaEm: "2026-07-10T15:00:00Z" }, anexos: [anexo("a3", "meus-dados.pdf", 100)] }],
      manifestacoes: [{ ...base, id: "m9", protocolo: "OUV-2026-000009", tipo: "reclamacao", assunto: "Fila", estado: "respondida", diasRestantes: 1,
        resposta: { corpo: "Ampliamos.", respondidaEm: "2026-07-10T15:00:00Z" }, anexos: [anexo("a4", "relatorio.odt", 100, "application/vnd.oasis.opendocument.text")] }],
    };
    render(<ListaProtocolos dados={dados} token={null} aoMudar={() => {}} />);
    const p9 = screen.getByText("ESIC-2026-000009").closest("li")!;
    const lista = within(p9).getByRole("list", { name: "Anexos da resposta" });
    const itens = within(lista).getAllByRole("listitem");
    expect(itens).toHaveLength(2);
    expect(itens[0].textContent).toMatch(/folha de 2025\.pdf.*2 KB · PDF/);
    expect(itens[1].textContent).toMatch(/dados\.csv.*2,0 MB · CSV/);
    expect(p9.textContent).not.toMatch(/application\/pdf|text\/csv/);
    // o link vai pela rota do requerente, da especie certa, e baixa como arquivo
    const link = within(itens[0]).getByRole("link", { name: "Baixar folha de 2025.pdf" });
    expect(link.getAttribute("href")).toBe("/api/portal/meus-protocolos/esic/p9/anexos/a1");
    expect(link.hasAttribute("download")).toBe(true);
    // lgpd e ouvidoria: a rota da propria especie
    const s9 = screen.getByText("LGPD-2026-000009").closest("li")!;
    expect(within(s9).getByRole("link", { name: "Baixar meus-dados.pdf" }).getAttribute("href")).toBe("/api/portal/meus-protocolos/lgpd/s9/anexos/a3");
    const m9 = screen.getByText("OUV-2026-000009").closest("li")!;
    expect(within(m9).getByRole("link", { name: "Baixar relatorio.odt" }).getAttribute("href")).toBe("/api/portal/meus-protocolos/ouvidoria/m9/anexos/a4");
    expect(within(m9).getByText(/Texto \(ODT\)/)).toBeTruthy();
  });

  it("os anexos do próprio pedido ficam em 'Seus anexos', separados dos da resposta, ambos com download", () => {
    const dados: MeusProtocolos = {
      ...DADOS, solicitacoesLgpd: [], manifestacoes: [],
      pedidosEsic: [{ ...base, id: "p5", protocolo: "ESIC-2026-000005", assunto: "Folha", estado: "respondido", diasRestantes: 5,
        resposta: { corpo: "Segue.", respondidaEm: "2026-07-10T15:00:00Z" },
        anexos: [anexo("a1", "meu contrato.pdf", 2048, "application/pdf", "requerente"), anexo("a2", "resposta.pdf", 1024, "application/pdf", "casa")] }],
    };
    render(<ListaDeProtocolosComToken dados={dados} token={null} />);
    const p5 = screen.getByText("ESIC-2026-000005").closest("li")!;
    const seus = within(p5).getByRole("list", { name: "Seus anexos" });
    expect(within(seus).getAllByRole("listitem")).toHaveLength(1);
    expect(within(seus).getByRole("link", { name: "Baixar meu contrato.pdf" }).getAttribute("href"))
      .toBe("/api/portal/meus-protocolos/esic/p5/anexos/a1");
    const daResposta = within(p5).getByRole("list", { name: "Anexos da resposta" });
    expect(within(daResposta).getAllByRole("listitem")).toHaveLength(1);
    expect(within(daResposta).getByText("resposta.pdf")).toBeTruthy();
    expect(within(seus).queryByText("resposta.pdf")).toBeNull();
  });

  it("enquanto `pode-anexar`: o controle de anexar ao PRÓPRIO pedido, com o limite de 10 minutos dito em palavras; envia um a um à rota do dono", async () => {
    const chamadas: { url: string; metodo: string; arquivo?: string }[] = [];
    global.fetch = vi.fn(async (url: string, init?: RequestInit) => {
      chamadas.push({ url: String(url), metodo: init?.method ?? "GET",
        arquivo: init?.body instanceof FormData ? (init.body.get("arquivo") as File).name : undefined });
      return { ok: true, status: 201, json: async () => ({ id: "n", nome: "x", "tipo-midia": "application/pdf", bytes: 1, origem: "requerente", "enviado-em": "2026-07-10T15:00:00Z" }) } as Response;
    }) as unknown as typeof fetch;
    const aoMudar = vi.fn();
    const dados: MeusProtocolos = {
      ...DADOS, solicitacoesLgpd: [], manifestacoes: [],
      pedidosEsic: [{ ...base, id: "p6", protocolo: "ESIC-2026-000006", assunto: "Folha", estado: "protocolado", diasRestantes: 20,
        anexos: [], podeAnexar: true }],
    };
    render(<ListaProtocolos dados={dados} token="tok" aoMudar={aoMudar} />);
    const p6 = screen.getByText("ESIC-2026-000006").closest("li")!;
    const controle = within(p6).getByRole("group", { name: "Anexar ao pedido" });
    expect(within(controle).getByText(/até 10 minutos depois de enviar o pedido/)).toBeTruthy();
    expect(within(controle).getByText(/Até 5 arquivos de até 10 MB: PDF, PNG, JPEG, TXT, CSV, DOCX, XLSX, ODT e ODS/)).toBeTruthy();
    const enviar = within(controle).getByRole("button", { name: "Enviar os arquivos" }) as HTMLButtonElement;
    expect(enviar.disabled).toBe(true);
    fireEvent.change(within(controle).getByLabelText(/Anexar arquivos ao pedido/),
      { target: { files: [new File(["x"], "contrato.pdf"), new File(["y"], "foto.png"), new File(["z"], "programa.exe")] } });
    expect(within(controle).getByRole("alert").textContent).toMatch(/“programa.exe” não é de um tipo aceito/);
    fireEvent.click(enviar);
    await waitFor(() => expect(within(p6).getByText("2 arquivos anexados.")).toBeTruthy());
    expect(chamadas.map((c) => [c.metodo, c.url, c.arquivo])).toEqual([
      ["POST", "/api/portal/meus-protocolos/esic/p6/anexos", "contrato.pdf"],
      ["POST", "/api/portal/meus-protocolos/esic/p6/anexos", "foto.png"],
    ]);
    expect(aoMudar).toHaveBeenCalled();   // a lista se relê: os anexos novos aparecem
  });

  it("o controle de anexar: a falha diz qual arquivo e por quê (409 da janela), e deixa tentar de novo", async () => {
    let n = 0;
    global.fetch = vi.fn(async () => (++n === 1
      ? ({ ok: false, status: 409, json: async () => ({ erro: "Os anexos vão junto com o pedido: os 10 minutos depois do protocolo já passaram." }) })
      : ({ ok: true, status: 201, json: async () => ({}) })) as Response) as unknown as typeof fetch;
    const dados: MeusProtocolos = {
      ...DADOS, solicitacoesLgpd: [], manifestacoes: [],
      pedidosEsic: [{ ...base, id: "p6", protocolo: "ESIC-2026-000006", assunto: "Folha", estado: "protocolado", diasRestantes: 20, anexos: [], podeAnexar: true }],
    };
    render(<ListaProtocolos dados={dados} token="tok" aoMudar={() => {}} />);
    const p6 = screen.getByText("ESIC-2026-000006").closest("li")!;
    fireEvent.change(within(p6).getByLabelText(/Anexar arquivos ao pedido/), { target: { files: [new File(["x"], "contrato.pdf")] } });
    fireEvent.click(within(p6).getByRole("button", { name: "Enviar os arquivos" }));
    const falha = await within(p6).findByText(/não foi anexado: Os anexos vão junto com o pedido: os 10 minutos depois do protocolo já passaram\./);
    expect(falha.closest("li")!.textContent).toMatch(/contrato\.pdf/);   // diz QUAL arquivo e por quê
    fireEvent.click(within(p6).getByRole("button", { name: "Tentar de novo o anexo contrato.pdf" }));
    await waitFor(() => expect(within(p6).getByText("1 arquivo anexado.")).toBeTruthy());
    expect(vi.mocked(global.fetch)).toHaveBeenCalledTimes(2);
  });

  it("sem `pode-anexar` (a janela passou ou já são 5): nenhum controle de anexar", () => {
    const dados: MeusProtocolos = {
      ...DADOS, solicitacoesLgpd: [], manifestacoes: [],
      pedidosEsic: [
        { ...base, id: "p1", protocolo: "ESIC-2026-000001", assunto: "a", estado: "protocolado", diasRestantes: 20, anexos: [], podeAnexar: false },
        { ...base, id: "p2", protocolo: "ESIC-2026-000002", assunto: "b", estado: "protocolado", diasRestantes: 20 },
      ],
    };
    render(<ListaProtocolos dados={dados} token="tok" aoMudar={() => {}} />);
    expect(screen.queryByRole("group", { name: "Anexar ao pedido" })).toBeNull();
    expect(screen.queryByLabelText(/Anexar arquivos ao pedido/)).toBeNull();
  });

  it("LGPD e ouvidoria identificada também anexam, cada uma pela rota da sua espécie", async () => {
    const urls: string[] = [];
    global.fetch = vi.fn(async (url: string) => { urls.push(String(url)); return { ok: true, status: 201, json: async () => ({}) } as Response; }) as unknown as typeof fetch;
    const dados: MeusProtocolos = {
      ...DADOS,
      pedidosEsic: [],
      solicitacoesLgpd: [{ ...base, id: "s7", protocolo: "LGPD-2026-000007", tipo: "acessar", estado: "protocolada", diasRestantes: 14, anexos: [], podeAnexar: true }],
      manifestacoes: [{ ...base, id: "m7", protocolo: "OUV-2026-000007", tipo: "reclamacao", assunto: "Fila", estado: "protocolada", diasRestantes: 29, anexos: [], podeAnexar: true }],
    };
    render(<ListaProtocolos dados={dados} token="tok" aoMudar={() => {}} />);
    for (const protocolo of ["LGPD-2026-000007", "OUV-2026-000007"]) {
      const li = screen.getByText(protocolo).closest("li")!;
      fireEvent.change(within(li).getByLabelText(/Anexar arquivos ao pedido/), { target: { files: [new File(["x"], "doc.pdf")] } });
      fireEvent.click(within(li).getByRole("button", { name: "Enviar os arquivos" }));
      await waitFor(() => expect(within(li).getByText("1 arquivo anexado.")).toBeTruthy());
    }
    expect(urls).toEqual(["/api/portal/meus-protocolos/lgpd/s7/anexos", "/api/portal/meus-protocolos/ouvidoria/m7/anexos"]);
  });

  it("o nome do arquivo é texto, nunca HTML", () => {
    const dados: MeusProtocolos = {
      ...DADOS, solicitacoesLgpd: [], manifestacoes: [],
      pedidosEsic: [{ ...base, id: "p8", protocolo: "ESIC-2026-000008", assunto: "x", estado: "respondido", diasRestantes: 1,
        resposta: { corpo: "ok", respondidaEm: "2026-07-10T15:00:00Z" }, anexos: [anexo("a1", "<img src=x onerror=alert(1)>.pdf", 10)] }],
    };
    const { container } = render(<ListaProtocolos dados={dados} token={null} aoMudar={() => {}} />);
    expect(screen.getByText(/<img src=x onerror=alert\(1\)>\.pdf/)).toBeTruthy();
    expect(container.querySelector("img")).toBeNull();
  });

  it("sem anexo (lista vazia ou ausente), nenhum bloco de anexos", () => {
    const dados: MeusProtocolos = {
      ...DADOS, solicitacoesLgpd: [], manifestacoes: [],
      pedidosEsic: [
        { ...base, id: "p1", protocolo: "ESIC-2026-000001", assunto: "a", estado: "respondido", diasRestantes: 1, anexos: [] },
        { ...base, id: "p2", protocolo: "ESIC-2026-000002", assunto: "b", estado: "respondido", diasRestantes: 1 },
      ],
    };
    render(<ListaProtocolos dados={dados} token={null} aoMudar={() => {}} />);
    expect(screen.queryByRole("list", { name: "Anexos da resposta" })).toBeNull();
  });

  it("no modo dev (token), o download sai pelos bytes: um botão, não um link cru", () => {
    const dados: MeusProtocolos = {
      ...DADOS, solicitacoesLgpd: [], manifestacoes: [],
      pedidosEsic: [{ ...base, id: "p1", protocolo: "ESIC-2026-000001", assunto: "a", estado: "respondido", diasRestantes: 1,
        anexos: [anexo("a1", "folha.pdf", 10)] }],
    };
    render(<ListaProtocolos dados={dados} token="tok" aoMudar={() => {}} />);
    expect(screen.getByRole("button", { name: "Baixar folha.pdf" })).toBeTruthy();
    expect(screen.queryByRole("link", { name: "Baixar folha.pdf" })).toBeNull();
  });

  it("pedido que já tem recurso mostra o recurso (e a decisão), sem oferecer recorrer de novo", () => {
    const comRecurso: MeusProtocolos = {
      ...DADOS,
      pedidosEsic: [
        {
          ...DADOS.pedidosEsic[1],
          recurso: {
            protocolo: "REC-2026-000001",
            estado: "decidido",
            reciboEm: "2026-07-11T12:00:00Z",
            resposta: { corpo: "Recurso provido: segue a lista com valores.", respondidaEm: "2026-07-15T12:00:00Z" },
          },
        },
      ],
    };
    render(<ListaProtocolos dados={comRecurso} token="tok" aoMudar={() => {}} />);
    const p2 = screen.getByText("ESIC-2026-000002").closest("li")!;
    expect(within(p2).getByText("REC-2026-000001")).toBeTruthy();
    expect(within(p2).getByText(/Recurso provido/)).toBeTruthy();
    expect(within(p2).queryByRole("button", { name: /recorrer/i })).toBeNull();
  });

  it("recorrer: envia o motivo e mostra o protocolo do recurso", async () => {
    global.fetch = vi.fn(async () =>
      ({ ok: true, status: 201, json: async () => ({ protocolo: "REC-2026-000001", "recibo-em": "2026-07-11T12:00:00Z" }) }) as Response,
    ) as unknown as typeof fetch;
    const aoMudar = vi.fn();
    render(<ListaProtocolos dados={DADOS} token="tok" aoMudar={aoMudar} />);
    const p2 = screen.getByText("ESIC-2026-000002").closest("li")!;
    fireEvent.click(within(p2).getByRole("button", { name: /recorrer/i }));
    fireEvent.change(within(p2).getByLabelText(/por que você não concordou/i), { target: { value: "Faltaram os valores." } });
    await act(async () => {
      fireEvent.click(within(p2).getByRole("button", { name: /enviar recurso/i }));
    });
    await waitFor(() => expect(within(p2).getByText(/REC-2026-000001/)).toBeTruthy());
    const [url, init] = vi.mocked(global.fetch).mock.calls[0];
    expect(url).toBe("/api/portal/esic/pedidos/p2/recursos");
    expect(JSON.parse(String(init?.body))).toEqual({ motivo: "Faltaram os valores." });
  });
});
