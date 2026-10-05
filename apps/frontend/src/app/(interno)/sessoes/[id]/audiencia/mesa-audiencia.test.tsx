import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MesaAudiencia } from "./mesa-audiencia";

vi.mock("next/link", () => ({
  default: ({ href, children, ...r }: { href: string; children: React.ReactNode }) => (
    <a href={href} {...r}>
      {children}
    </a>
  ),
}));

const T0 = Date.parse("2026-06-11T12:00:00.000Z");

const inscricao = (p: Record<string, unknown>) => ({
  id: "i1", protocolo: "AUD-2026-000001", ordem: 1, nome: "Ana Souza", "fala-como": "individual", tema: "Ciclovias",
  origem: "portal_govbr", estado: "inscrita", ...p,
});

function audiencia(p: Record<string, unknown> = {}) {
  return {
    "sessao-id": "s1", numero: 2, estado: "aberta", "agendada-para": "2026-06-11T12:00:00Z", modalidade: "presencial",
    comissao: { id: "c1", nome: "Comissão de Finanças" }, tema: "Metas fiscais do 1º quadrimestre", local: "Plenário",
    finalidade: "metas_fiscais", referencia: "2026-Q1", "tempo-fala-segundos": 300, "inscricoes-abertas": true,
    inscricoes: [
      inscricao({ id: "i2", ordem: 2, nome: "José Lima", "fala-como": "entidade", entidade: "Associação do Ellery", origem: "presencial_secretaria" }),
      inscricao({}),
    ],
    ...p,
  };
}

type Chamada = { url: string; metodo: string; corpo: unknown };
let chamadas: Chamada[] = [];

function servidor(get: () => { status: number; corpo: unknown }, escrita: { status: number; corpo?: unknown } = { status: 200, corpo: {} }) {
  chamadas = [];
  global.fetch = vi.fn(async (url: string, init?: RequestInit) => {
    const metodo = init?.method ?? "GET";
    chamadas.push({ url, metodo, corpo: init?.body ? JSON.parse(String(init.body)) : undefined });
    const r = metodo === "GET" ? get() : escrita;
    return { ok: r.status < 300, status: r.status, json: async () => r.corpo ?? {} } as Response;
  }) as unknown as typeof fetch;
}

const escritas = () => chamadas.filter((c) => c.metodo !== "GET");

beforeEach(() => {
  vi.useFakeTimers({ toFake: ["setInterval", "clearInterval", "Date"] });
  vi.setSystemTime(T0 + 10_000);
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe("Mesa da audiência pública", () => {
  it("mostra os dados e a fila em ordem de inscrição, com o estado em palavras", async () => {
    servidor(() => ({ status: 200, corpo: audiencia() }));
    render(<MesaAudiencia token="tok" sessaoId="s1" />);
    await screen.findByRole("heading", { name: "Metas fiscais do 1º quadrimestre" });
    expect(screen.getByText("Comissão de Finanças")).toBeTruthy();
    expect(screen.getByText(/1º quadrimestre de 2026 \(jan–abr\)/)).toBeTruthy();
    expect(screen.getByText("5 min por pessoa")).toBeTruthy();
    const itens = screen.getAllByRole("listitem");
    expect(itens[0].textContent).toContain("Ana Souza");
    expect(itens[1].textContent).toContain("José Lima · Associação do Ellery");
    expect(itens[1].textContent).toContain("inscrição presencial");
    expect(itens[0].textContent).toContain("Próxima da fila");
    expect(itens[1].textContent).toContain("Na fila para falar");
    expect(chamadas[0].url).toBe("/api/sessoes/s1/audiencia");
  });

  it("chamar: POST na chamada da inscrição e a fila recarrega", async () => {
    servidor(() => ({ status: 200, corpo: audiencia() }));
    render(<MesaAudiencia token="tok" sessaoId="s1" />);
    const botao = await screen.findByRole("button", { name: "Chamar Ana Souza" });
    await act(async () => {
      fireEvent.click(botao);
    });
    await screen.findByText("Ana Souza está com a palavra.");
    expect(escritas()).toEqual([{ url: "/api/sessoes/s1/audiencia/inscricoes/i1/chamada", metodo: "POST", corpo: {} }]);
    expect(chamadas.filter((c) => c.metodo === "GET").length).toBe(2);
  });

  it("com alguém na palavra, ninguém mais é chamado; o cronômetro regride, avisa o último minuto e o esgotado", async () => {
    servidor(() => ({
      status: 200,
      corpo: audiencia({ inscricoes: [inscricao({ estado: "falando", "chamada-em": new Date(T0).toISOString() }), inscricao({ id: "i2", ordem: 2, nome: "José Lima" })] }),
    }));
    render(<MesaAudiencia token="tok" sessaoId="s1" />);
    const relogio = await screen.findByTestId("relogio");
    expect(relogio.textContent).toBe("04:50");
    // o relógio aparece na pintura, mas o intervalo que o faz andar nasce num efeito que o React roda depois; sob
    // carga o teste chegava ao `advanceTimersByTime` antes dele e o relógio ficava em 04:50 (CI do PR #182).
    // `vi.waitFor` usa o relógio real, então espera mesmo com os timers falsos ligados.
    await vi.waitFor(() => expect(vi.getTimerCount()).toBeGreaterThan(0));
    expect((screen.getByRole("button", { name: "Chamar José Lima" }) as HTMLButtonElement).disabled).toBe(true);

    // o relógio só anda depois que o efeito registra o intervalo; o `findBy` devolve o elemento assim que ele pinta, e
    // o efeito pode vir depois. Sem esta espera, o avanço do tempo caía no vazio e o relógio ficava em "04:50".
    await vi.waitFor(() => expect(vi.getTimerCount()).toBeGreaterThan(0));
    act(() => vi.advanceTimersByTime(250_000));
    expect(screen.getByTestId("relogio").textContent).toBe("00:40");
    expect(screen.getByTestId("relogio").className).toContain("ultimo-minuto");
    expect(screen.queryByText(/Tempo esgotado/)).toBeNull();

    act(() => vi.advanceTimersByTime(45_000));
    expect(screen.getByTestId("relogio").textContent).toBe("+00:05");
    expect(screen.getByRole("alert").textContent).toMatch(/Tempo esgotado/);

    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: "Encerrar fala" }));
    });
    await screen.findByText(/Fala encerrada \(05:05\)/);
    expect(escritas()).toEqual([
      { url: "/api/sessoes/s1/audiencia/inscricoes/i1/encerramento", metodo: "POST", corpo: { "tempo-usado-segundos": 305 } },
    ]);
  });

  it("ausente: POST na ausência", async () => {
    servidor(() => ({ status: 200, corpo: audiencia() }));
    render(<MesaAudiencia token="tok" sessaoId="s1" />);
    const botao = await screen.findByRole("button", { name: "Marcar José Lima como ausente" });
    await act(async () => {
      fireEvent.click(botao);
    });
    await screen.findByText("Ausência registrada: José Lima.");
    expect(escritas()[0].url).toBe("/api/sessoes/s1/audiencia/inscricoes/i2/ausencia");
  });

  it("sessão ainda agendada: não chama, e diz por quê", async () => {
    servidor(() => ({ status: 200, corpo: audiencia({ estado: "agendada" }) }));
    render(<MesaAudiencia token="tok" sessaoId="s1" />);
    expect((await screen.findByRole("button", { name: "Chamar Ana Souza" }) as HTMLButtonElement).disabled).toBe(true);
    expect(screen.getByText(/a sessão precisa estar aberta/)).toBeTruthy();
  });

  it("inscrição presencial: confere o nome antes e manda o corpo kebab", async () => {
    servidor(() => ({ status: 200, corpo: audiencia() }), { status: 201, corpo: {} });
    render(<MesaAudiencia token="tok" sessaoId="s1" />);
    const inscrever = await screen.findByRole("button", { name: "Inscrever" });
    await act(async () => {
      fireEvent.click(inscrever);
    });
    expect(screen.getByRole("alert").textContent).toMatch(/nome/);
    expect(escritas()).toEqual([]);
    fireEvent.change(screen.getByLabelText("Nome"), { target: { value: "Maria" } });
    fireEvent.change(screen.getByLabelText("Fala como"), { target: { value: "conselho_movimento" } });
    fireEvent.change(screen.getByLabelText("Entidade, conselho ou movimento"), { target: { value: "Conselho de Saúde" } });
    fireEvent.change(screen.getByLabelText("Tema da fala"), { target: { value: "Postos de saúde" } });
    await act(async () => {
      fireEvent.click(inscrever);
    });
    await screen.findByText("Inscrição presencial registrada.");
    expect(escritas()).toEqual([
      {
        url: "/api/sessoes/s1/audiencia/inscricoes",
        metodo: "POST",
        corpo: { nome: "Maria", "fala-como": "conselho_movimento", entidade: "Conselho de Saúde", tema: "Postos de saúde" },
      },
    ]);
  });

  it("fechar as inscrições do portal e ajustar o tempo: PATCH só com o que muda", async () => {
    servidor(() => ({ status: 200, corpo: audiencia() }));
    render(<MesaAudiencia token="tok" sessaoId="s1" />);
    const botao = await screen.findByRole("button", { name: "Fechar inscrições" });
    await act(async () => {
      fireEvent.click(botao);
    });
    await screen.findByText("Inscrições pelo portal fechadas.");
    fireEvent.change(screen.getByLabelText("Tempo de fala (minutos)"), { target: { value: "40" } });
    fireEvent.click(screen.getByRole("button", { name: "Salvar tempo" }));
    expect(screen.getByRole("alert").textContent).toMatch(/1 a 30 minutos/);
    fireEvent.change(screen.getByLabelText("Tempo de fala (minutos)"), { target: { value: "3" } });
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: "Salvar tempo" }));
    });
    await screen.findByText("Tempo de fala: 3 min por pessoa.");
    expect(escritas()).toEqual([
      { url: "/api/sessoes/s1/audiencia", metodo: "PATCH", corpo: { "inscricoes-abertas": false } },
      { url: "/api/sessoes/s1/audiencia", metodo: "PATCH", corpo: { "tempo-fala-segundos": 180 } },
    ]);
  });

  it("recusa do servidor vira frase: 409 com o motivo dele", async () => {
    servidor(() => ({ status: 200, corpo: audiencia() }), { status: 409, corpo: { erro: "ja ha uma fala em curso" } });
    render(<MesaAudiencia token="tok" sessaoId="s1" />);
    const botao = await screen.findByRole("button", { name: "Chamar Ana Souza" });
    await act(async () => {
      fireEvent.click(botao);
    });
    await waitFor(() => expect(screen.getByRole("alert").textContent).toBe("Ja ha uma fala em curso."));
  });

  it("sessão que não é audiência (404): a Mesa diz, sem fila fingida", async () => {
    servidor(() => ({ status: 404, corpo: {} }));
    render(<MesaAudiencia token="tok" sessaoId="s1" />);
    expect((await screen.findByRole("alert")).textContent).toMatch(/não é uma audiência pública/);
  });
});
