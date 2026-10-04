import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { PainelAssistente } from "./painel-assistente";

const SSE = [
  'event: passo\ndata: {"ferramenta":"situacao_da_materia","argumentos":{"tipo":"projeto_lei","sequencial":11,"ano":2026},"ok":true}',
  'event: resposta\ndata: {"texto":"Segundo o sistema da Casa, ementa: Merenda escolar. [[ferramenta:situacao_da_materia#1 | ementa: Merenda escolar]]\\n\\nIsso é tudo.","citacoes":[{"fonte-id":"ferramenta:situacao_da_materia#1","trecho":"ementa: Merenda escolar","status":"conferida"}],"paragrafos-sem-fonte":[1],"incerteza":"normal","modelo":"fake-1","contaminado":false}',
  'event: fim\ndata: {"execucao-id":"e1"}',
  "",
].join("\n\n");

function mockar(corpo: string, status = 200) {
  const fetchMock = vi.fn(async () => ({ ok: status === 200, status, text: async () => corpo }) as Response);
  global.fetch = fetchMock as unknown as typeof fetch;
  return fetchMock;
}

describe("PainelAssistente", () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it("pergunta, mostra o que consultou, a resposta citada e de onde veio", async () => {
    const fetchMock = mockar(SSE);
    render(<PainelAssistente token="tok" />);
    fireEvent.change(screen.getByLabelText("Sua pergunta"), { target: { value: "  situação do PL 11/2026? " } });
    fireEvent.click(screen.getByRole("button", { name: "Perguntar" }));

    expect(await screen.findByText("Consultou a situação do PL 11/2026")).toBeTruthy();
    const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe("/api/agente/perguntas");
    expect(JSON.parse(init.body as string)).toEqual({ pergunta: "situação do PL 11/2026?" });
    expect(screen.getByText("situação do PL 11/2026?")).toBeTruthy();
    expect(screen.getByText("sem fonte — confira")).toBeTruthy();
    expect(screen.getByText("De onde veio (1)")).toBeTruthy();
    expect(screen.getByText("A situação do PL 11/2026")).toBeTruthy();
    expect(screen.getByText(/Resposta escrita por IA/)).toBeTruthy();
  });

  it("sugestão pergunta direto; assistente fora manda seguir pela tela", async () => {
    mockar('event: indisponivel\ndata: {"mensagem":"O assistente está indisponível agora. Siga pela tela."}\n\nevent: fim\ndata: {}\n\n');
    render(<PainelAssistente token="tok" />);
    fireEvent.click(screen.getByRole("button", { name: "O que vai ser votado na próxima sessão?" }));
    expect(await screen.findByText(/Siga pela tela/)).toBeTruthy();
  });

  it("erro do core vira mensagem, nunca tela quebrada", async () => {
    mockar("", 403);
    render(<PainelAssistente token="tok" />);
    fireEvent.change(screen.getByLabelText("Sua pergunta"), { target: { value: "oi, tudo bem?" } });
    fireEvent.click(screen.getByRole("button", { name: "Perguntar" }));
    expect(await screen.findByText(/secretaria e dos vereadores/)).toBeTruthy();
  });

  it("B.6: a proposta preparada vira um cartão que leva à tela de confirmar — nunca confirma aqui", async () => {
    mockar(
      [
        'event: passo\ndata: {"ferramenta":"protocolar_requerimento","argumentos":{},"ok":true}',
        'event: proposta\ndata: {"id":"p-9","titulo":"Protocolar o requerimento “Obra”","ritual":"assinatura"}',
        'event: fim\ndata: {}',
        "",
      ].join("\n\n"),
    );
    render(<PainelAssistente token="tok" />);
    fireEvent.change(screen.getByLabelText("Sua pergunta"), { target: { value: "protocole um requerimento" } });
    fireEvent.click(screen.getByRole("button", { name: "Perguntar" }));
    expect(await screen.findByText("Preparou uma proposta de requerimento — nada foi protocolado")).toBeTruthy();
    expect(screen.getByText("Protocolar o requerimento “Obra”")).toBeTruthy();
    const link = screen.getByRole("link", { name: "Revisar e assinar" });
    expect(link.getAttribute("href")).toMatch(/^\/propostas\/p-9/);
    expect(screen.queryByRole("button", { name: /Confirmar/ })).toBeNull();
  });

  it("8.4: resposta com o id da execução na IA oferece 'Reportar erro', que vai ao core com esse id", async () => {
    const comId = SSE.replace('"contaminado":false}', '"contaminado":false,"execucao-ia":"ia-7"}');
    const fetchMock = mockar(comId);
    render(<PainelAssistente token="tok" />);
    fireEvent.change(screen.getByLabelText("Sua pergunta"), { target: { value: "situação do PL 11/2026?" } });
    fireEvent.click(screen.getByRole("button", { name: "Perguntar" }));
    fireEvent.click(await screen.findByRole("button", { name: "Reportar erro" }));
    fireEvent.click(screen.getByRole("radio", { name: "A fonte não diz isso" }));
    fetchMock.mockImplementation(async () => ({ ok: true, status: 200, json: async () => ({ reportado: true }) }) as Response);
    fireEvent.click(screen.getByRole("button", { name: "Enviar" }));
    expect(await screen.findByText("Obrigado — isso entra na revisão da IA.")).toBeTruthy();
    const [url, init] = fetchMock.mock.calls[1] as unknown as [string, RequestInit];
    expect(url).toBe("/api/ia/execucoes/ia-7/reportes");
    expect(JSON.parse(init.body as string)).toEqual({ categoria: "citacao_errada" });
  });

  it("8.4: sem o id da execução na IA, nada de 'Reportar erro' (não há o que reportar)", async () => {
    mockar(SSE);
    render(<PainelAssistente token="tok" />);
    fireEvent.change(screen.getByLabelText("Sua pergunta"), { target: { value: "situação do PL 11/2026?" } });
    fireEvent.click(screen.getByRole("button", { name: "Perguntar" }));
    await screen.findByText(/Resposta escrita por IA/);
    expect(screen.queryByRole("button", { name: "Reportar erro" })).toBeNull();
  });

  it("no app do vereador pede o conjunto do vereador (quem tem os dois papéis não cai no da secretaria)", async () => {
    const fetchMock = mockar(SSE);
    render(<PainelAssistente token="tok" publico="vereador" />);
    fireEvent.change(screen.getByLabelText("Sua pergunta"), { target: { value: "oi, tudo bem?" } });
    fireEvent.click(screen.getByRole("button", { name: "Perguntar" }));
    await screen.findByText("Consultou a situação do PL 11/2026");
    const [, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect(JSON.parse(init.body as string)).toEqual({ pergunta: "oi, tudo bem?", publico: "vereador" });
  });
});
