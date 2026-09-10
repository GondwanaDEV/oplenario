import { describe, expect, it, vi, afterEach } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import PaginaNotificacoes from "./page";

vi.mock("@/lib/auth", () => ({ useAuth: () => ({ token: "tok" }) }));

const umaNaoLida = {
  notificacoes: [
    {
      id: "n1",
      categoria: "norma_publicada",
      assunto: "A sua proposicao virou lei — Lei 3/2026",
      corpo: "Ementa: Dispoe sobre as hortas comunitarias.",
      "objeto-tipo": "proposicao",
      "objeto-id": "p1",
      "criado-em": new Date().toISOString(),
      "lida-em": null,
    },
  ],
  "nao-lidas": 1,
};

describe("PaginaNotificacoes", () => {
  afterEach(() => {
    // sem isto, tests 1/2 (item não-lido montado) deixavam a árvore anterior no DOM (este projeto NÃO
    // liga o auto-cleanup do RTL — todo outro *.test.tsx do shell chama `cleanup()` explicitamente, ex.
    // vereador/page.test.tsx) e o teste 5 batia em "Marcar como lida" duplicado/travava — achado real via
    // ciclo red→green, não teatro de timeout.
    cleanup();
    vi.restoreAllMocks();
  });

  it("mostra a notificação, o grupo e o badge de não lidas", async () => {
    global.fetch = vi.fn(async () => ({ ok: true, json: async () => umaNaoLida }) as Response) as unknown as typeof fetch;
    render(<PaginaNotificacoes />);
    await waitFor(() => expect(screen.getByText(/virou lei/)).toBeDefined());
    expect(screen.getByRole("heading", { level: 2, name: "Hoje" })).toBeDefined();
    expect(screen.getByLabelText("1 não lida")).toBeDefined();
    // Sem destino acessível ao vereador, não se renderiza âncora nenhuma (ver notificacoes-vista.test.ts).
    expect(screen.queryByRole("link", { name: /Abrir a ficha/ })).toBeNull();
  });

  it("não-lida é sinalizada por mais que cor (ponto com rótulo acessível)", async () => {
    global.fetch = vi.fn(async () => ({ ok: true, json: async () => umaNaoLida }) as Response) as unknown as typeof fetch;
    render(<PaginaNotificacoes />);
    await waitFor(() => expect(screen.getByRole("img", { name: "Não lida" })).toBeDefined());
  });

  it("estado vazio é honesto", async () => {
    global.fetch = vi.fn(async () => ({ ok: true, json: async () => ({ notificacoes: [], "nao-lidas": 0 }) }) as Response) as unknown as typeof fetch;
    render(<PaginaNotificacoes />);
    await waitFor(() => expect(screen.getByText(/Nenhuma notificação/)).toBeDefined());
  });

  it("erro de carga não quebra a tela", async () => {
    global.fetch = vi.fn(async () => ({ ok: false, status: 500 }) as Response) as unknown as typeof fetch;
    render(<PaginaNotificacoes />);
    await waitFor(() => expect(screen.getByText(/Não foi possível carregar/)).toBeDefined());
  });

  it("marcar como lida chama o POST e revalida", async () => {
    const chamadas: string[] = [];
    global.fetch = vi.fn(async (url: string, init?: RequestInit) => {
      chamadas.push(`${init?.method ?? "GET"} ${url}`);
      if (init?.method === "POST") {
        return { ok: true, json: async () => ({ id: "n1", "lida-em": "2026-07-19T13:00:00Z" }) } as Response;
      }
      return { ok: true, json: async () => umaNaoLida } as Response;
    }) as unknown as typeof fetch;
    render(<PaginaNotificacoes />);
    await waitFor(() => expect(screen.getByText(/virou lei/)).toBeDefined());
    screen.getByRole("button", { name: /Marcar como lida/ }).click();
    await waitFor(() =>
      expect(chamadas).toContain("POST /api/meu/notificacoes/n1/lida")
    );
    await waitFor(() => expect(chamadas.filter((c) => c.startsWith("GET")).length).toBeGreaterThan(1));
  });

  // Regressão da revisão adversarial (achado único sobrevivente): a falha do POST era visível na tela
  // mas MUDA para leitor de tela — parágrafo comum, sem região viva, e o foco fica no botão. WCAG 4.1.3
  // (Status Messages, AA) — que o "AA nos 2 temas" já medido NÃO cobre: aquilo era contraste.
  // `role="status"` é a convenção das duas telas irmãs deste shell com a mesma classe
  // (vereador/page.tsx, parecer/[id]/assinar/page.tsx).
  it("falha ao marcar como lida é anunciada a leitor de tela (região viva, não só pixel)", async () => {
    global.fetch = vi.fn(async (_url: string, init?: RequestInit) => {
      if (init?.method === "POST") return { ok: false, status: 500 } as Response;
      return { ok: true, json: async () => umaNaoLida } as Response;
    }) as unknown as typeof fetch;
    render(<PaginaNotificacoes />);
    await waitFor(() => expect(screen.getByText(/virou lei/)).toBeDefined());
    screen.getByRole("button", { name: /Marcar como lida/ }).click();
    const aviso = await waitFor(() => screen.getByRole("status"));
    expect(aviso.className).toContain("erro-inline");
    expect(aviso.textContent).toBeTruthy();
  });
});

// -------------------------------------------------------------------------------------------------
// Fatia 3 — a barra de filtro na tela. O racional de DERIVAR as abas está no topo de
// src/lib/notificacoes-vista.ts; aqui prova-se o comportamento visível.
// -------------------------------------------------------------------------------------------------

function item(id: string, categoria: string, lidaEm: string | null = null, assunto = `Assunto ${id}`) {
  return {
    id,
    categoria,
    assunto,
    corpo: "Ementa: ...",
    "objeto-tipo": "proposicao",
    "objeto-id": `p-${id}`,
    "criado-em": new Date().toISOString(),
    "lida-em": lidaEm,
  };
}

function servindo(corpo: unknown) {
  global.fetch = vi.fn(async () => ({ ok: true, json: async () => corpo }) as Response) as unknown as typeof fetch;
}

describe("PaginaNotificacoes · barra de filtro", () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it("desenha a barra com Tudo e Não lidas, com as contagens locais", async () => {
    servindo({
      notificacoes: [item("a", "norma_publicada"), item("b", "norma_publicada", "2026-07-19T10:00:00Z")],
      "nao-lidas": 1,
    });
    render(<PaginaNotificacoes />);
    const barra = await waitFor(() => screen.getByRole("group", { name: /Filtrar/i }));
    const abas = Array.from(barra.querySelectorAll("button")).map((b) => b.textContent);
    expect(abas).toEqual(["Tudo2", "Não lidas1"]);
    expect(barra.querySelector('button[aria-pressed="true"]')!.textContent).toBe("Tudo2");
  });

  it("NÃO desenha aba para categoria que nenhum produtor emite", async () => {
    servindo({ notificacoes: [item("a", "norma_publicada")], "nao-lidas": 1 });
    render(<PaginaNotificacoes />);
    const barra = await waitFor(() => screen.getByRole("group", { name: /Filtrar/i }));
    expect(barra.textContent).not.toMatch(/Falha|Prazo|Sess|Tramita/i);
  });

  it("clicar em 'Não lidas' esconde a lida, mantém a não lida e ACENDE a aba clicada", async () => {
    servindo({
      notificacoes: [
        item("a", "norma_publicada", null, "A que ainda nao li"),
        item("b", "norma_publicada", "2026-07-19T10:00:00Z", "A que ja li"),
      ],
      "nao-lidas": 1,
    });
    render(<PaginaNotificacoes />);
    await waitFor(() => expect(screen.getByText("A que ja li")).toBeDefined());

    // Como um item LIDO é renderizado — sem isto, inverter o ternário do ponto ou a classe `nao-lida`
    // passa despercebido, e o "mais que cor" (ponto + negrito + tinta) migra para o item errado.
    const artigoLido = screen.getByText("A que ja li").closest("article")!;
    const artigoNaoLido = screen.getByText("A que ainda nao li").closest("article")!;
    expect(within(artigoLido).getByText("Lida")).toBeDefined();
    expect(within(artigoLido).queryByRole("img", { name: "Não lida" })).toBeNull();
    expect(within(artigoLido).queryByRole("button", { name: /Marcar como lida/ })).toBeNull();
    expect(artigoLido.className).not.toContain("nao-lida");
    expect(within(artigoNaoLido).queryByText("Lida")).toBeNull();
    expect(within(artigoNaoLido).getByRole("img", { name: "Não lida" })).toBeDefined();
    expect(within(artigoNaoLido).getByRole("button", { name: /Marcar como lida/ })).toBeDefined();
    expect(artigoNaoLido.className).toContain("nao-lida");

    fireEvent.click(screen.getByRole("button", { name: /^Não lidas/ }));
    await waitFor(() => expect(screen.queryByText("A que ja li")).toBeNull());
    expect(screen.getByText("A que ainda nao li")).toBeDefined();
    // o realce MIGROU: `.segs button[aria-pressed="true"]` é o ÚNICO carregador do destaque da aba ativa
    // (notificacoes.css) e do anúncio "pressionado" no leitor de tela — filtrar sem acender é filtro
    // aplicado sem dizer qual.
    expect(screen.getByRole("button", { name: /^Não lidas/ }).getAttribute("aria-pressed")).toBe("true");
    expect(screen.getByRole("button", { name: /^Tudo/ }).getAttribute("aria-pressed")).toBe("false");
  });

  it("com uma SEGUNDA categoria no dado, a aba dela nasce sozinha e filtra", async () => {
    servindo({
      notificacoes: [
        item("a", "norma_publicada", null, "Virou lei"),
        item("b", "sistema", null, "Aviso do sistema"),
      ],
      "nao-lidas": 2,
    });
    render(<PaginaNotificacoes />);
    await waitFor(() => expect(screen.getByText("Aviso do sistema")).toBeDefined());
    fireEvent.click(screen.getByRole("button", { name: /^Sistema/ }));
    await waitFor(() => expect(screen.queryByText("Virou lei")).toBeNull());
    expect(screen.getByText("Aviso do sistema")).toBeDefined();
    expect(screen.getByRole("button", { name: /^Sistema/ }).getAttribute("aria-pressed")).toBe("true");
    expect(screen.getByRole("button", { name: /^Tudo/ }).getAttribute("aria-pressed")).toBe("false");
  });

  it("inbox vazia não ganha barra (nada a filtrar)", async () => {
    servindo({ notificacoes: [], "nao-lidas": 0 });
    render(<PaginaNotificacoes />);
    await waitFor(() => expect(screen.getByText(/Nenhuma notificação/)).toBeDefined());
    expect(screen.queryByRole("group", { name: /Filtrar/i })).toBeNull();
  });

  it("filtro sem resultado diz que é o FILTRO, não que a inbox está vazia", async () => {
    servindo({
      notificacoes: [item("b", "norma_publicada", "2026-07-19T10:00:00Z", "A que ja li")],
      "nao-lidas": 0,
    });
    render(<PaginaNotificacoes />);
    await waitFor(() => expect(screen.getByText("A que ja li")).toBeDefined());
    fireEvent.click(screen.getByRole("button", { name: /^Não lidas/ }));
    await waitFor(() => expect(screen.getByText(/Nenhuma notificação neste filtro/)).toBeDefined());
    expect(screen.queryByText("Nenhuma notificação por enquanto.")).toBeNull();
  });
});

// -------------------------------------------------------------------------------------------------
// O badge conta TODAS as não lidas (query sem teto do servidor); a lista e as contagens das abas vêm da
// resposta cortada em 50 linhas pelo SQL (paineis/db/notificacao_caixa.clj). São dois universos, e a
// tela exibia os dois números lado a lado sem uma linha dizendo isso.
// -------------------------------------------------------------------------------------------------

describe("PaginaNotificacoes · a lista tem teto, o badge não", () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it("diz que a lista está cortada quando há não lida que não coube nela", async () => {
    // o cenário exato do achado: 10 não lidas no servidor, e as que chegaram na lista estão todas lidas.
    servindo({
      notificacoes: [item("b", "norma_publicada", "2026-07-19T10:00:00Z", "A que ja li")],
      "nao-lidas": 10,
    });
    render(<PaginaNotificacoes />);
    await waitFor(() => expect(screen.getByText("A que ja li")).toBeDefined());
    expect(screen.getByLabelText("10 não lidas")).toBeDefined();

    const aviso = screen.getByText(/só os avisos mais recentes/);
    expect(aviso.textContent).toContain("10 avisos não lidos mais antigos fora dela");

    // e o aviso continua de pé no filtro em que a contradição aparece: aba "Não lidas 0" com badge 10.
    fireEvent.click(screen.getByRole("button", { name: /^Não lidas/ }));
    await waitFor(() => expect(screen.getByText(/Nenhuma notificação neste filtro/)).toBeDefined());
    expect(screen.getByText(/só os avisos mais recentes/)).toBeDefined();
  });

  it("uma só não lida fora da lista é dita no singular", async () => {
    servindo({
      notificacoes: [item("b", "norma_publicada", "2026-07-19T10:00:00Z", "A que ja li")],
      "nao-lidas": 1,
    });
    render(<PaginaNotificacoes />);
    await waitFor(() => expect(screen.getByText("A que ja li")).toBeDefined());
    expect(screen.getByText(/só os avisos mais recentes/).textContent).toContain(
      "Há 1 aviso não lido mais antigo fora dela"
    );
  });

  it("badge e lista concordando -> nenhuma linha de corte (não se avisa do que não houve)", async () => {
    servindo({ notificacoes: [item("a", "norma_publicada")], "nao-lidas": 1 });
    render(<PaginaNotificacoes />);
    await waitFor(() => expect(screen.getByText("Assunto a")).toBeDefined());
    expect(screen.getByLabelText("1 não lida")).toBeDefined();
    expect(screen.queryByText(/só os avisos mais recentes/)).toBeNull();
  });
});
