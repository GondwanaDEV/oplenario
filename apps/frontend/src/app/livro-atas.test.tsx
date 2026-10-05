import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, within } from "@testing-library/react";
import { LivroAtas } from "./livro-atas";
import { ProvedorDaDica, useDicaAtual } from "./(interno)/clara/dica";

const t = "2026-09-10T17:00:00Z";
const sessao = (id: string, n: number) => ({ id, "tipo-sessao": "ordinaria", "numero-sequencial": n, "aberta-em": t });

const livro = {
  atas: [
    { sessao: sessao("s13", 13), versao: 2, "origem-redacao": "redigida_externamente", "conteudo-sha256": "sha256:ab",
      "publicada-em": t, leitura: null },
    { sessao: sessao("s12", 12), versao: 1, "origem-redacao": "redigida_externamente", "conteudo-sha256": "sha256:cd",
      "publicada-em": t, leitura: null },
  ],
};

const ata = (id: string, n: number, texto: string) => ({
  sessao: sessao(id, n),
  versao: { versao: 1, "origem-redacao": "redigida_externamente", "conteudo-sha256": "sha256:ab", "publicada-em": t },
  texto,
  vigente: true,
  versoes: [{ versao: 1, "origem-redacao": "redigida_externamente", "conteudo-sha256": "sha256:ab", "publicada-em": t }],
  leitura: null,
});

function mockApi(rotas: Record<string, unknown>) {
  const f = vi.fn(async (url: string) => {
    const corpo = rotas[url];
    return corpo === undefined
      ? ({ ok: false, status: 404, json: async () => ({}) } as Response)
      : ({ ok: true, status: 200, json: async () => corpo } as Response);
  });
  global.fetch = f as unknown as typeof fetch;
  return f;
}

describe("LivroAtas", () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it("portal: abre a ata mais recente e lista todas, com link para ler cada uma no portal", async () => {
    mockApi({
      "/api/portal/casa/casa-1/atas": livro,
      "/api/portal/casa/casa-1/atas/s13": ata("s13", 13, "Aos dez dias do mês de setembro."),
    });
    render(<LivroAtas fonte={{ tipo: "publico", ente: "casa-1" }} sessao={null} versao={null} nomeCasa="Câmara de Baturité" />);
    const folha = await screen.findByRole("article", { name: "Ata da 13ª Sessão Ordinária" });
    expect(within(folha).getByText("Aos dez dias do mês de setembro.")).toBeTruthy();
    expect(within(folha).getByText("Câmara de Baturité")).toBeTruthy();
    expect(screen.getByRole("heading", { name: "Última ata publicada" })).toBeTruthy();
    const lista = screen.getByRole("list", { name: "Atas publicadas" });
    expect(within(lista).getByRole("link", { name: "Ler a ata da 12ª Sessão Ordinária" }).getAttribute("href")).toBe(
      "/portal/casa/casa-1/atas?sessao=s12",
    );
    expect(within(lista).getByText("retificada")).toBeTruthy();
  });

  it("interno: a ata pedida na URL é a aberta, e os links levam o dev-token", async () => {
    const f = mockApi({ "/api/atas": livro, "/api/atas/s12": ata("s12", 12, "Texto da 12ª.") });
    render(<LivroAtas fonte={{ tipo: "interno", token: "tk" }} sessao="s12" versao={null} />);
    expect(await screen.findByText("Texto da 12ª.")).toBeTruthy();
    expect(screen.getByRole("heading", { name: "Ata aberta" })).toBeTruthy();
    expect(screen.getByRole("link", { name: "Ler a ata da 13ª Sessão Ordinária" }).getAttribute("href")).toBe(
      "/atas?sessao=s13&token=tk",
    );
    const [, init] = f.mock.calls.find(([u]) => u === "/api/atas") as unknown as [string, RequestInit];
    expect(new Headers(init.headers).get("Authorization")).toBe("Bearer tk");
  });

  it("ata indisponível (404: inexistente, secreta ou versão que não existe) não vira folha vazia", async () => {
    mockApi({ "/api/portal/casa/casa-1/atas": livro });
    render(<LivroAtas fonte={{ tipo: "publico", ente: "casa-1" }} sessao="secreta" versao={null} />);
    expect(await screen.findByText(/Esta ata não está disponível/)).toBeTruthy();
    expect(screen.queryByRole("article")).toBeNull();
  });

  it("livro vazio: explica, sem folha", async () => {
    mockApi({ "/api/portal/casa/casa-1/atas": { atas: [] } });
    render(<LivroAtas fonte={{ tipo: "publico", ente: "casa-1" }} sessao={null} versao={null} />);
    expect(await screen.findByText(/Nenhuma ata publicada ainda/)).toBeTruthy();
    expect(screen.queryByRole("article")).toBeNull();
  });

  describe("a dica da Clara (ADR-0024)", () => {
    function Sonda() {
      const dica = useDicaAtual();
      return <output data-testid="dica">{dica ? `${dica.rotulo} | ${dica.inicio} | ${dica.acao}` : "sem dica"}</output>;
    }

    it("interno: depois de lida, a ata aberta vira a dica — e só ela", async () => {
      mockApi({ "/api/atas": livro, "/api/atas/s13": ata("s13", 13, "Texto da 13ª.") });
      render(
        <ProvedorDaDica>
          <LivroAtas fonte={{ tipo: "interno", token: "tk" }} sessao={null} versao={null} />
          <Sonda />
        </ProvedorDaDica>,
      );
      expect(screen.getByTestId("dica").textContent).toBe("sem dica");
      await screen.findByText("Texto da 13ª.");
      expect(screen.getByTestId("dica").textContent).toBe(
        "Ata da 13ª Sessão Ordinária | Sobre a ata da 13ª Sessão Ordinária,  | Perguntar sobre esta ata",
      );
    });

    it("interno: ata indisponível não vira dica", async () => {
      mockApi({ "/api/atas": livro });
      render(
        <ProvedorDaDica>
          <LivroAtas fonte={{ tipo: "interno", token: "tk" }} sessao="secreta" versao={null} />
          <Sonda />
        </ProvedorDaDica>,
      );
      await screen.findByText(/Esta ata não está disponível/);
      expect(screen.getByTestId("dica").textContent).toBe("sem dica");
    });

    it("portal: nunca publica dica (o portal não tem Clara), nem com o provedor por perto", async () => {
      mockApi({
        "/api/portal/casa/casa-1/atas": livro,
        "/api/portal/casa/casa-1/atas/s13": ata("s13", 13, "Aos dez dias do mês de setembro."),
      });
      render(
        <ProvedorDaDica>
          <LivroAtas fonte={{ tipo: "publico", ente: "casa-1" }} sessao={null} versao={null} />
          <Sonda />
        </ProvedorDaDica>,
      );
      await screen.findByText("Aos dez dias do mês de setembro.");
      expect(screen.getByTestId("dica").textContent).toBe("sem dica");
    });
  });
});
