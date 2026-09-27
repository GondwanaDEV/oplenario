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
});
