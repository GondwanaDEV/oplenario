import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import PaginaAta from "./page";

vi.mock("next/navigation", () => ({
  useParams: () => ({ id: "s1" }),
  useSearchParams: () => ({ get: () => null }),
}));
vi.mock("@/lib/auth", () => ({
  AuthProvider: ({ children }: { children: React.ReactNode }) => children,
  useAuth: () => ({ token: "tok" }),
}));
vi.mock("@/lib/tema", () => ({ useTema: () => ({ tema: "claro", alternar: vi.fn() }) }));

const versao = (n: number, over: Record<string, unknown> = {}) => ({
  id: `a${n}`, versao: n, "origem-redacao": "redigida_externamente", "conteudo-sha256": `sha256:${"ab".repeat(32)}`,
  "publicada-em": "2026-09-26T21:04:00Z", "publicada-por-nome": "Maria Souza", ...over,
});

type Resp = { status: number; body: unknown };
function rede(get: () => Resp, post?: (corpo: Record<string, unknown>) => Resp) {
  const chamadas: Record<string, unknown>[] = [];
  global.fetch = vi.fn(async (_url: string, init?: RequestInit) => {
    const r = init?.method === "POST" && post
      ? post((chamadas[chamadas.push(JSON.parse(String(init.body))) - 1]))
      : get();
    return { ok: r.status < 400, status: r.status, json: async () => r.body } as Response;
  }) as unknown as typeof fetch;
  return chamadas;
}

describe("PaginaAta", () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it("sem ata: redige, revisa e publica em dois passos; depois mostra a versão congelada", async () => {
    let publicada = false;
    const chamadas = rede(
      () => ({ status: 200, body: publicada
        ? { "sessao-id": "s1", "pode-ter-ata": true, atual: { versao: versao(1), texto: "Aos vinte e seis dias..." }, versoes: [versao(1)] }
        : { "sessao-id": "s1", "pode-ter-ata": true, atual: null, versoes: [] } }),
      () => { publicada = true; return { status: 201, body: { id: "a1", versao: 1, "conteudo-sha256": "sha256:ab" } }; },
    );
    render(<PaginaAta />);
    fireEvent.click(await screen.findByRole("button", { name: "Redigir a ata" }));
    const revisar = screen.getByRole("button", { name: "Revisar para publicar" }) as HTMLButtonElement;
    expect(revisar.disabled).toBe(true);
    expect(screen.getByText("Escreva ou cole o texto da ata.")).toBeTruthy();
    fireEvent.change(screen.getByLabelText("Texto da ata"), { target: { value: "Aos vinte e seis dias..." } });
    fireEvent.click(revisar);
    expect(screen.getByText(/Publicar a versão 1\?/)).toBeTruthy();
    expect(chamadas).toHaveLength(0);
    fireEvent.click(screen.getByRole("button", { name: "Publicar a ata" }));
    expect((await screen.findByText("Ata publicada — versão 1.")).getAttribute("role")).toBe("status");
    expect(chamadas).toEqual([{ texto: "Aos vinte e seis dias...", "motivo-retificacao": null }]);
    expect(await screen.findByText("Aos vinte e seis dias...")).toBeTruthy();
    expect(screen.getByText(/^Versão 1 · publicada por Maria Souza/)).toBeTruthy();
    expect(screen.getByText("Redigida pela Casa")).toBeTruthy();
  });

  it("retificar exige motivo e mostra o histórico; erro do servidor não perde o texto", async () => {
    const chamadas = rede(
      () => ({ status: 200, body: { "sessao-id": "s1", "pode-ter-ata": true,
        atual: { versao: versao(2, { "motivo-retificacao": "nome errado" }), texto: "Texto v2" },
        versoes: [versao(2, { "motivo-retificacao": "nome errado" }), versao(1)] } }),
      () => ({ status: 409, body: { erro: "outra versao da ata foi publicada ao mesmo tempo" } }),
    );
    render(<PaginaAta />);
    expect(await screen.findByText("Versões publicadas")).toBeTruthy();
    expect(screen.getAllByText("Motivo da retificação: nome errado")).toHaveLength(2);
    fireEvent.click(screen.getByRole("button", { name: "Retificar a ata" }));
    expect((screen.getByLabelText("Texto da ata") as HTMLTextAreaElement).value).toBe("Texto v2");
    expect((screen.getByRole("button", { name: "Revisar para publicar" }) as HTMLButtonElement).disabled).toBe(true);
    fireEvent.change(screen.getByLabelText("Motivo da retificação"), { target: { value: "data errada" } });
    fireEvent.click(screen.getByRole("button", { name: "Revisar para publicar" }));
    expect(screen.getByText(/Publicar a versão 3\?/)).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Publicar a ata" }));
    expect((await screen.findByRole("alert")).textContent).toContain("publicada ao mesmo tempo");
    expect(chamadas[0]).toEqual({ texto: "Texto v2", "motivo-retificacao": "data errada" });
    expect((screen.getByLabelText("Texto da ata") as HTMLTextAreaElement).value).toBe("Texto v2");
  });

  it("sessão que não tem ata diz por quê", async () => {
    rede(() => ({ status: 200, body: { "sessao-id": "s1", "pode-ter-ata": false, atual: null, versoes: [] } }));
    render(<PaginaAta />);
    expect(await screen.findByText(/ainda não foi encerrada/)).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Redigir a ata" })).toBeNull();
  });
});
