import { afterEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { PainelLeituraAta } from "./painel-leitura-ata";
import type { Leitor } from "@/lib/voz";

const anterior = { id: "s11", "tipo-sessao": "ordinaria", "numero-sequencial": 11, "aberta-em": "2026-09-12T18:00:00Z" };
const ata = {
  versao: 2, texto: "Aos doze dias. Reuniu-se a Câmara.\n\nNada mais havendo.", "conteudo-sha256": "sha256:ab",
  "origem-redacao": "gerada_automaticamente", "publicada-em": "2026-09-13T12:00:00Z",
};
type Resp = { status: number; body: unknown };

function rede(get: () => Resp, post?: (corpo: Record<string, unknown>) => Resp) {
  const enviados: Record<string, unknown>[] = [];
  global.fetch = vi.fn(async (_url: string, init?: RequestInit) => {
    const r = init?.method === "POST" && post ? post(enviados[enviados.push(JSON.parse(String(init.body))) - 1]) : get();
    return { ok: r.status < 400, status: r.status, json: async () => r.body } as Response;
  }) as unknown as typeof fetch;
  return enviados;
}

function leitorFalso(voz: string | null = "Luciana") {
  let ao: Parameters<Leitor["ler"]>[1] | null = null;
  const leitor: Leitor = {
    voz: () => voz,
    ler: (_t, a) => { ao = a; return { pausar: vi.fn(), retomar: vi.fn(), parar: vi.fn() }; },
  };
  return { leitor, avancar: (i: number) => act(() => ao!.trecho(i)), acabar: () => act(() => ao!.fim()) };
}

describe("PainelLeituraAta", () => {
  afterEach(() => { cleanup(); vi.restoreAllMocks(); });

  it("lê em voz, destaca o parágrafo em leitura e registra ao final", async () => {
    let lida = false;
    const enviados = rede(
      () => ({ status: 200, body: lida
        ? { "sessao-id": "s12", "pode-registrar": false, anterior, ata,
            leitura: { modo: "voz_sintetizada", "ata-sessao-id": "s11", "ata-versao": 2, "registrada-em": "2026-09-15T18:10:00Z", "registrada-por-nome": "João Mesa" } }
        : { "sessao-id": "s12", "pode-registrar": true, anterior, ata } }),
      () => { lida = true; return { status: 201, body: { modo: "voz_sintetizada" } }; },
    );
    const { leitor, avancar, acabar } = leitorFalso();
    render(<PainelLeituraAta sessaoId="s12" token="tok" leitor={leitor} />);
    expect(await screen.findByText("Ata da 11ª Sessão Ordinária (12/09/2026)")).toBeTruthy();
    expect(screen.getByText(/partiu de um rascunho da IA/)).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Ler em voz sintetizada" }));
    avancar(2);
    expect(screen.getByText("Nada mais havendo.").getAttribute("aria-current")).toBe("true");
    acabar();
    fireEvent.click(screen.getByRole("button", { name: "Registrar: lida em voz sintetizada" }));
    expect(await screen.findByText("Lida em voz sintetizada")).toBeTruthy();
    expect(enviados).toEqual([{ modo: "voz_sintetizada", "ata-sessao-id": "s11", "ata-versao": 2 }]);
    expect(screen.getByText(/registrada por João Mesa/)).toBeTruthy();
  });

  it("dispensa com confirmação; recusa do servidor aparece com a razão", async () => {
    const enviados = rede(
      () => ({ status: 200, body: { "sessao-id": "s12", "pode-registrar": true, anterior, ata } }),
      () => ({ status: 409, body: { erro: "a ata a ler mudou (outra versao foi publicada, ou a sessao anterior mudou): recarregue" } }),
    );
    render(<PainelLeituraAta sessaoId="s12" token="tok" leitor={leitorFalso().leitor} />);
    fireEvent.click(await screen.findByRole("button", { name: "Dispensar a leitura" }));
    expect(screen.getByText(/dispensou a leitura/)).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Registrar: leitura dispensada" }));
    expect((await screen.findByRole("alert")).textContent).toContain("a ata a ler mudou");
    expect(enviados[0]).toMatchObject({ modo: "dispensada" });
  });

  it("sem voz em português: a leitura em voz fica desabilitada e a tela explica", async () => {
    rede(() => ({ status: 200, body: { "sessao-id": "s12", "pode-registrar": true, anterior, ata } }));
    render(<PainelLeituraAta sessaoId="s12" token="tok" leitor={leitorFalso(null).leitor} />);
    expect(((await screen.findByRole("button", { name: "Ler em voz sintetizada" })) as HTMLButtonElement).disabled).toBe(true);
    expect(screen.getByText(/não tem voz em português instalada/)).toBeTruthy();
  });

  it("ata anterior ainda não publicada aponta para a tela Ata; sem anterior diz isso", async () => {
    rede(() => ({ status: 200, body: { "sessao-id": "s12", "pode-registrar": true, anterior, ata: null } }));
    render(<PainelLeituraAta sessaoId="s12" token="tok" leitor={leitorFalso().leitor} />);
    expect((await screen.findByRole("link", { name: "Publique-a na tela Ata" })).getAttribute("href")).toBe("/sessoes/s11/ata");
    cleanup();
    rede(() => ({ status: 200, body: { "sessao-id": "s12", "pode-registrar": true } }));
    render(<PainelLeituraAta sessaoId="s12" token="tok" leitor={leitorFalso().leitor} />);
    expect(await screen.findByText("Não há sessão anterior com ata para ler.")).toBeTruthy();
  });
});
