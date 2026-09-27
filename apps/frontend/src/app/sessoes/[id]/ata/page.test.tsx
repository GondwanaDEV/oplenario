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
function rede(get: (url: string) => Resp, post?: (corpo: Record<string, unknown>, url: string) => Resp) {
  const chamadas: Record<string, unknown>[] = [];
  global.fetch = vi.fn(async (url: string, init?: RequestInit) => {
    const r = init?.method === "POST" && post
      ? post(chamadas[chamadas.push(init.body ? JSON.parse(String(init.body)) : { url }) - 1], url)
      : get(url);
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

  it("pede o rascunho, acompanha, revisa com as citações e publica como gerada pela IA", async () => {
    let fase: "nada" | "redigindo" | "pronto" | "publicada" = "nada";
    const ped = { "solicitacao-id": "q1", "solicitado-em": "2026-09-26T21:00:00Z", "ocorrido-em": "2026-09-26T21:02:00Z" };
    const chamadas = rede(
      (url) => {
        if (url.endsWith("/rascunhos/r1"))
          return { status: 200, body: {
            "rascunho-id": "r1",
            texto: "Ana falou: “Senhor presidente” [[transcricao:t#1 | Senhor presidente]]\n\nEncerrada. [confirmar: hora]",
            "texto-limpo": "Ana falou: “Senhor presidente”\n\nEncerrada. [confirmar: hora]",
            incerteza: { nivel: "revisar_com_atencao", motivos: ["conteudo_de_terceiro", "sem_fonte"] },
            citacoes: [{ "fonte-id": "transcricao:t#1", trecho: "Senhor presidente", inicio: 0, fim: 1, status: "conferida", rotulo: "Ana, 0:10–0:40" }],
            "paragrafos-sem-fonte": [1], "pontos-a-confirmar": ["hora"], "modelo-llm-id": "fake:fake-1", "prompt-versao": "ata-v1" } };
        const rascunho = fase === "redigindo" ? { ...ped, situacao: "solicitado" }
          : fase === "pronto" ? { ...ped, situacao: "pronto", "rascunho-id": "r1", "n-pontos-a-confirmar": 1, "n-paragrafos-sem-fonte": 1 }
          : null;
        return { status: 200, body: fase === "publicada"
          ? { "sessao-id": "s1", "pode-ter-ata": true, atual: { versao: versao(1, { "origem-redacao": "gerada_automaticamente", "rascunho-id": "r1" }), texto: "Ana falou." }, versoes: [versao(1)] }
          : { "sessao-id": "s1", "pode-ter-ata": true, atual: null, versoes: [], rascunho } };
      },
      (_corpo, url) => {
        if (url.endsWith("/rascunhos")) { fase = "redigindo"; return { status: 202, body: { "solicitacao-id": "q1" } }; }
        fase = "publicada";
        return { status: 201, body: { id: "a1", versao: 1, "conteudo-sha256": "sha256:ab" } };
      },
    );
    render(<PaginaAta />);
    fireEvent.click(await screen.findByRole("button", { name: "Pedir rascunho à IA" }));
    expect(await screen.findByText("A IA está redigindo o rascunho…")).toBeTruthy();
    fase = "pronto";
    expect(await screen.findByText("Rascunho pronto para revisar", {}, { timeout: 8000 })).toBeTruthy();
    expect(screen.getByText("Antes de usar: 1 ponto a confirmar e 1 parágrafo sem fonte.")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Revisar o rascunho" }));
    expect((await screen.findByRole("note")).textContent).toContain("Revise com atenção");
    expect(screen.getByTitle("Ana, 0:10–0:40").textContent).toBe("1");
    expect(screen.getByText("sem fonte — confira")).toBeTruthy();
    expect(screen.getByText("[confirmar: hora]")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Usar este rascunho" }));
    const area = screen.getByLabelText("Texto da ata") as HTMLTextAreaElement;
    expect(area.value).toBe("Ana falou: “Senhor presidente”\n\nEncerrada. [confirmar: hora]");
    expect(screen.getByText("Resolva o ponto a confirmar ([confirmar: …]) antes de publicar.")).toBeTruthy();
    fireEvent.change(area, { target: { value: "Ana falou: “Senhor presidente”\n\nEncerrada às 20h." } });
    fireEvent.click(screen.getByRole("button", { name: "Revisar para publicar" }));
    fireEvent.click(screen.getByRole("button", { name: "Publicar a ata" }));
    expect(await screen.findByText("Ata publicada — versão 1.")).toBeTruthy();
    expect(chamadas.at(-1)).toEqual({
      texto: "Ana falou: “Senhor presidente”\n\nEncerrada às 20h.",
      "motivo-retificacao": null,
      "origem-redacao": "gerada_automaticamente",
      "rascunho-id": "r1",
    });
  }, 15000);

  it("IA fora ao abrir o rascunho: mensagem R-IA-1 e volta ao caminho manual", async () => {
    rede((url) => url.endsWith("/rascunhos/r1")
      ? { status: 503, body: { erro: "A IA está indisponível agora. Siga pela tela — redija a ata sem o rascunho, nada depende dela." } }
      : { status: 200, body: { "sessao-id": "s1", "pode-ter-ata": true, atual: null, versoes: [],
          rascunho: { "solicitacao-id": "q", situacao: "pronto", "rascunho-id": "r1", "solicitado-em": "2026-09-26T21:00:00Z", "ocorrido-em": "2026-09-26T21:01:00Z" } } });
    render(<PaginaAta />);
    fireEvent.click(await screen.findByRole("button", { name: "Revisar o rascunho" }));
    expect((await screen.findByRole("alert")).textContent).toContain("Siga pela tela");
    fireEvent.click(screen.getByRole("button", { name: "Voltar" }));
    expect(screen.getByRole("button", { name: "Redigir a ata" })).toBeTruthy();
  });

  it("pedido recusado mostra a razão do servidor", async () => {
    rede(
      () => ({ status: 200, body: { "sessao-id": "s1", "pode-ter-ata": true, atual: null, versoes: [] } }),
      () => ({ status: 409, body: { erro: "ainda nao ha' transcricao concluida desta sessao para a IA redigir a ata" } }),
    );
    render(<PaginaAta />);
    fireEvent.click(await screen.findByRole("button", { name: "Pedir rascunho à IA" }));
    expect((await screen.findByRole("alert")).textContent).toContain("transcricao concluida");
  });
});
