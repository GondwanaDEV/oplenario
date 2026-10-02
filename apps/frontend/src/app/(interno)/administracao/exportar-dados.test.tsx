import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { ExportarDados } from "./exportar-dados";

// ADR-0018 fatia 2 (9.6): o bloco "Exportar os dados da Câmara" do admin_ente — gerar, acompanhar, baixar e confirmar
// o recebimento vendo o código. No encerramento, a tela diz que a confirmação abre a contagem dos 90 dias.

const SHA = "0123456789abcdef".repeat(4);
const PRONTA = {
  id: "x1", estado: "pronta", "solicitada-em": "2026-10-01T13:00:00Z", "solicitada-por": "admin_ente",
  "concluida-em": "2026-10-01T13:20:00Z", sha256: SHA, bytes: 2_500_000, manifesto: null, erro: null,
  "confirmada-em": null, "confirmada-por": null, oficio: null,
};

type Chamada = { metodo: string; url: string; body: unknown };

function mockar(get: unknown, posts: Record<string, { status: number; corpo: unknown }> = {}) {
  const chamadas: Chamada[] = [];
  global.fetch = vi.fn(async (url: string, init?: RequestInit) => {
    const c = { metodo: init?.method ?? "GET", url: String(url), body: init?.body ? JSON.parse(String(init.body)) : undefined };
    chamadas.push(c);
    if (c.metodo === "POST" && posts[c.url]) {
      const p = posts[c.url];
      return { ok: p.status < 300, status: p.status, json: async () => p.corpo } as Response;
    }
    if (c.url === "/api/administracao/exportacoes") return { ok: true, status: 200, json: async () => get } as Response;
    return { ok: false, status: 404, json: async () => ({}) } as Response;
  }) as unknown as typeof fetch;
  return chamadas;
}

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.unstubAllEnvs();
});

describe("ExportarDados", () => {
  it("explica o arquivo, gera, e mostra o código e o tamanho da pronta", async () => {
    const chamadas = mockar(
      { disponivel: true, "em-encerramento": false, exportacoes: [] },
      { "/api/administracao/exportacoes": { status: 202, corpo: { ...PRONTA, estado: "gerando", sha256: null, bytes: null } } },
    );
    render(<ExportarDados token="tk" />);
    expect(await screen.findByText(/formato aberto/)).toBeTruthy();
    expect(screen.getByText("Nenhuma exportação gerada ainda.")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Gerar exportação completa" }));
    expect(await screen.findByText(/está sendo gerado/)).toBeTruthy();
    expect(chamadas.filter((c) => c.metodo === "POST")).toHaveLength(1);
  });

  it("a pronta: baixa e confirma o recebimento com o código que a tela mostra", async () => {
    const chamadas = mockar(
      { disponivel: true, "em-encerramento": true, exportacoes: [PRONTA] },
      { "/api/administracao/exportacoes/x1/confirmacao": { status: 200, corpo: { ...PRONTA, "confirmada-em": "2026-10-02T10:00:00Z" } } },
    );
    render(<ExportarDados token="tk" />);
    expect(await screen.findByText(/está encerrando o uso/)).toBeTruthy();
    expect(screen.getByText(/abre a contagem de 90 dias, e só depois deles/)).toBeTruthy();
    expect(screen.getByText(/2,4 MB/)).toBeTruthy();
    expect(screen.getByText("01234567 89abcdef 01234567 89abcdef 01234567 89abcdef 01234567 89abcdef")).toBeTruthy();
    expect(screen.getByRole("button", { name: "Baixar arquivo" })).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Confirmar recebimento" }));
    expect((await screen.findByRole("alert")).textContent).toMatch(/Marque que a Câmara recebeu/);
    expect(chamadas.some((c) => c.url.endsWith("/confirmacao"))).toBe(false);
    fireEvent.click(screen.getByRole("checkbox", { name: /Recebemos o arquivo com o código 01234567…89abcdef/ }));
    fireEvent.click(screen.getByRole("button", { name: "Confirmar recebimento" }));
    expect(await screen.findByText("Recebimento confirmado. A contagem de 90 dias começou hoje.")).toBeTruthy();
    expect(chamadas.find((c) => c.url.endsWith("/confirmacao"))?.body).toEqual({ sha256: SHA });
  });

  it("no modo real (sem token), baixar é um link direto — o arquivo vai para o disco com o cookie da sessão", async () => {
    vi.stubEnv("NEXT_PUBLIC_APP_ENV", "production");
    mockar({ disponivel: true, "em-encerramento": false, exportacoes: [{ ...PRONTA, "confirmada-em": "2026-10-02T10:00:00Z", "confirmada-por": "admin_ente" }] });
    render(<ExportarDados token={null} />);
    const link = await screen.findByRole("link", { name: "Baixar arquivo" });
    expect(link.getAttribute("href")).toBe("/api/administracao/exportacoes/x1/arquivo");
    expect(screen.getByText("Recebimento confirmado")).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Confirmar recebimento" })).toBeNull();
  });

  it("geração em andamento trava o botão; a que falhou diz por quê; indisponível diz isso", async () => {
    mockar({ disponivel: true, "em-encerramento": false, exportacoes: [
      { ...PRONTA, id: "x2", estado: "gerando", sha256: null, bytes: null },
      { ...PRONTA, id: "x3", estado: "falhou", sha256: null, bytes: null, erro: "disco cheio" },
    ] });
    render(<ExportarDados token="tk" />);
    expect(((await screen.findByRole("button", { name: "Gerando o arquivo…" })) as HTMLButtonElement).disabled).toBe(true);
    expect(screen.getByText(/A geração falhou: disco cheio/)).toBeTruthy();
    cleanup();
    mockar({ disponivel: false, "em-encerramento": false, exportacoes: [] });
    render(<ExportarDados token="tk" />);
    expect(await screen.findByText(/ainda não está disponível nesta instalação/)).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Gerar exportação completa" })).toBeNull();
  });

  it("recusa do servidor vira frase (uma geração por vez)", async () => {
    mockar(
      { disponivel: true, "em-encerramento": false, exportacoes: [] },
      { "/api/administracao/exportacoes": { status: 409, corpo: { erro: "x", causa: "exportacao-em-andamento" } } },
    );
    render(<ExportarDados token="tk" />);
    fireEvent.click(await screen.findByRole("button", { name: "Gerar exportação completa" }));
    expect((await screen.findByRole("alert")).textContent).toBe("Já há uma exportação sendo gerada. Espere ela terminar.");
  });
});
