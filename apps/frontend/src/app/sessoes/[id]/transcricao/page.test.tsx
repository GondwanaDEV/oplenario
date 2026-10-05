import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import PaginaTranscricao from "./page";

// A Clara (ADR-0024): sem papel nos testes de sempre; o bloco da Clara liga a secretaria.
const papeisDaClara = vi.hoisted(() => ({ atual: [] as string[] }));
vi.mock("next/navigation", () => ({
  useParams: () => ({ id: "s1" }),
  useSearchParams: () => ({ get: () => null }),
  usePathname: () => "/sessoes/s1",
}));
vi.mock("@/lib/auth", () => ({
  AuthProvider: ({ children }: { children: React.ReactNode }) => children,
  useAuth: () => ({ token: "tok", papeis: papeisDaClara.atual }),
  usePapeis: () => ({ papeis: papeisDaClara.atual, estado: "pronto" }),
}));
vi.mock("@/lib/tema", () => ({ useTema: () => ({ tema: "claro", alternar: vi.fn() }) }));

const ponteiro = (over: Record<string, unknown> = {}) => ({
  id: "p1",
  "segmento-id": "g1",
  situacao: "concluida",
  "transcricao-id": "t1",
  versao: 1,
  "duracao-s": 54.96,
  "n-trechos": 3,
  "cobertura-atribuida": 0.6,
  "modelo-asr": "whisper-turbo-int8",
  "ocorrido-em": "2026-09-26T21:00:00Z",
  ...over,
});

function rede(rotas: Record<string, { status: number; body: unknown }>) {
  global.fetch = vi.fn(async (url: string) => {
    const r = rotas[url] ?? { status: 404, body: {} };
    return { ok: r.status < 400, status: r.status, json: async () => r.body } as Response;
  }) as unknown as typeof fetch;
}

describe("PaginaTranscricao", () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it("mostra quem falou, quando, e avisa para revisar com atenção", async () => {
    rede({
      "/api/sessoes/s1/transcricoes": { status: 200, body: { "sessao-id": "s1", itens: [ponteiro()] } },
      "/api/sessoes/s1/transcricoes/t1": {
        status: 200,
        body: {
          ponteiro: ponteiro(),
          trechos: [
            { inicio: 0.6, fim: 4, texto: "Declaro aberta a sessão.", "orador-nome": "Antônio Ferreira" },
            { inicio: 4.5, fim: 18, texto: "Peço a palavra.", "orador-nome": "Beatriz Lima" },
            { inicio: 22, fim: 26, texto: "(inaudível)", "orador-nome": null },
          ],
        },
      },
    });
    render(<PaginaTranscricao />);
    expect(await screen.findByText("Antônio Ferreira")).toBeTruthy();
    expect(screen.getByText("Declaro aberta a sessão.")).toBeTruthy();
    expect(screen.getByText("Orador não identificado")).toBeTruthy();
    expect(screen.getByText("0:04–0:18")).toBeTruthy();
    expect(screen.getByRole("note").textContent).toContain("Revisar com atenção: 60%");
    expect(screen.getByText(/Rascunho feito pela IA/)).toBeTruthy();
  });

  it("IA fora do ar: a gravação mostra o R-IA-1, a tela não quebra", async () => {
    rede({
      "/api/sessoes/s1/transcricoes": { status: 200, body: { "sessao-id": "s1", itens: [ponteiro()] } },
      "/api/sessoes/s1/transcricoes/t1": {
        status: 503,
        body: { erro: "A IA está indisponível agora. Siga pela tela — a gravação está guardada." },
      },
    });
    render(<PaginaTranscricao />);
    expect((await screen.findByRole("alert")).textContent).toContain("Siga pela tela");
  });

  it("falha de transcrição e estado vazio têm texto honesto", async () => {
    rede({
      "/api/sessoes/s1/transcricoes": {
        status: 200,
        body: { "sessao-id": "s1", itens: [ponteiro({ situacao: "falhou", "transcricao-id": null, "categoria-erro": "entrada" })] },
      },
    });
    render(<PaginaTranscricao />);
    expect(await screen.findByText(/o áudio não pôde ser lido/)).toBeTruthy();
    cleanup();
    rede({ "/api/sessoes/s1/transcricoes": { status: 200, body: { "sessao-id": "s1", itens: [] } } });
    render(<PaginaTranscricao />);
    expect(await screen.findByText(/Nenhuma gravação desta sessão foi transcrita ainda/)).toBeTruthy();
  });
});

describe("PaginaTranscricao — a Clara (ADR-0024, fatia 5)", () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    papeisDaClara.atual = [];
    delete document.documentElement.dataset.clara;
  });

  it("a secretaria tem a Clara, recolhida", async () => {
    papeisDaClara.atual = ["secretario"];
    rede({ "/api/sessoes/s1/transcricoes": { status: 200, body: { "sessao-id": "s1", itens: [] } } });
    render(<PaginaTranscricao />);
    expect(await screen.findByText(/Nenhuma gravação desta sessão foi transcrita/)).toBeTruthy();
    const lancador = screen.getByRole("button", { name: /Pergunte à Clara/ });
    expect(lancador.getAttribute("aria-expanded")).toBe("false");
    fireEvent.click(lancador);
    expect((document.querySelector(".ast:not([hidden])") as HTMLElement).textContent).not.toContain("Nesta tela");
  });
});
