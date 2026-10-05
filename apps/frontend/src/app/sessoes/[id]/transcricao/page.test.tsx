import { afterEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import PaginaTranscricao, { ConteudoTranscricao } from "./page";
import { ProvedorDaDica, useDicaAtual } from "@/app/(interno)/clara/dica";

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

// A sessão (ADR-0024, fatia 6): a tela lê `GET /api/sessoes/:id` para o nome no cabeçalho e a dica da Clara. Corpo
// cru do servidor (kebab-case); quem não fala dela recebe esta, quem fala põe a própria rota em `rotas`.
const SESSAO = {
  id: "s1", "sessao-legislativa-id": "sl1", "tipo-sessao": "ordinaria", "numero-sequencial": 15, estado: "encerrada",
  modalidade: "presencial", delibera: true, "transmite-publica": true, "gera-ata-regimental": true,
  "permite-voto-secreto": false, "permite-modalidade-remota": false, "lock-version": 3,
};

function rede(rotas: Record<string, { status: number; body: unknown }>) {
  const todas: Record<string, { status: number; body: unknown }> = { "/api/sessoes/s1": { status: 200, body: SESSAO }, ...rotas };
  global.fetch = vi.fn(async (url: string) => {
    const r = todas[url] ?? { status: 404, body: {} };
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
    // fatia 6: a tela lê a sessão, e a Clara sabe de qual se trata
    await waitFor(() =>
      expect((document.querySelector(".ast:not([hidden])") as HTMLElement).textContent).toContain(
        "Nesta tela: 15ª Sessão Ordinária",
      ),
    );
  });
});

describe("PaginaTranscricao — qual sessão é (ADR-0024, fatia 6)", () => {
  const vazia = { "/api/sessoes/s1/transcricoes": { status: 200, body: { "sessao-id": "s1", itens: [] } } };
  function Sonda() {
    const dica = useDicaAtual();
    return <output data-testid="dica">{dica ? `${dica.rotulo} | ${dica.inicio} | ${dica.acao}` : "sem dica"}</output>;
  }
  const cabecalho = () => document.querySelector("header.topo .sessao-meta") as HTMLElement;
  function montar() {
    return render(
      <ProvedorDaDica>
        <ConteudoTranscricao id="s1" />
        <Sonda />
      </ProvedorDaDica>,
    );
  }

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it("com a sessão lida, o cabeçalho diz qual é e a tela publica a dica da sessão", async () => {
    rede(vazia);
    montar();
    expect(await screen.findByText(/Nenhuma gravação desta sessão foi transcrita/)).toBeTruthy();
    await waitFor(() => expect(cabecalho().querySelector(".quando")?.textContent).toBe("Sessão ordinária nº 15"));
    expect(cabecalho().querySelector(".tipo")?.textContent).toBe("Transcrição");
    await waitFor(() =>
      expect(screen.getByTestId("dica").textContent).toBe(
        "15ª Sessão Ordinária | Sobre a 15ª Sessão Ordinária,  | Perguntar sobre esta sessão",
      ),
    );
  });

  it("sessão que não abre (404): cabeçalho como antes, sem dica, e a transcrição segue", async () => {
    rede({ ...vazia, "/api/sessoes/s1": { status: 404, body: { erro: "nao-encontrada" } } });
    montar();
    expect(await screen.findByText(/Nenhuma gravação desta sessão foi transcrita/)).toBeTruthy();
    await waitFor(() =>
      expect(vi.mocked(global.fetch).mock.calls.filter(([u]) => u === "/api/sessoes/s1")).toHaveLength(1),
    );
    await act(async () => {
      await new Promise((r) => setTimeout(r, 20));
    });
    expect(cabecalho().textContent).toBe("Transcrição");
    expect(screen.getByTestId("dica").textContent).toBe("sem dica");
  });
});
