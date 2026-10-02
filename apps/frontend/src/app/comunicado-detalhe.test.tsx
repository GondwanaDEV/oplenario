import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";

// O comunicado (ADR-0020): texto como texto, ciência com um toque, o painel de leitura para quem pode, a substituição
// e os anexos. As duas portas (interno /comunicados/:id e vereador /notificacoes/:id) são casca sobre o mesmo
// componente — provadas no fim do arquivo.

const auth = vi.hoisted(() => ({ token: "tok" as string | null }));
vi.mock("@/lib/auth", () => ({ useAuth: () => ({ token: auth.token }), usePapeis: () => ({ papeis: [], estado: "pronto" }) }));
vi.mock("next/navigation", () => ({ useParams: () => ({ id: "c1" }), useSearchParams: () => new URLSearchParams("de=enviados") }));
vi.mock("./(interno)/topo", () => ({ TopoInterno: ({ area }: { area: string }) => <div data-testid="topo">{area}</div> }));

import { ComunicadoDetalhe } from "./comunicado-detalhe";
import PaginaComunicadoInterno from "./(interno)/comunicados/[id]/page";
import PaginaComunicadoVereador from "./(vereador)/notificacoes/[id]/page";

const MIN = 60_000;
const iso = (d: number) => new Date(Date.now() + d).toISOString();

function comunicado(extra: Record<string, unknown> = {}) {
  return {
    id: "c1", protocolo: "COM-2026-000123", assunto: "Sessão extraordinária na sexta",
    corpo: "Prezados,\n\n1. Pauta anexa.\n2. Chegar às 8h.\n<b>sem HTML</b>",
    remetente: { "identidade-id": "i0", nome: "Rita Campos" }, "enviado-em": "2026-10-02T13:00:00Z",
    "exige-ciencia": true, "ciencia-ate": iso(3 * 24 * 60 * MIN), substitui: null, "substituido-por": null, objeto: null,
    destinos: [{ tipo: "setor", "alvo-id": "s1", "alvo-nome": "Jurídico" }, { tipo: "comissao", "alvo-id": "k1", "alvo-nome": "Comissão de Finanças" }],
    anexos: [], "minhas-marcas": { "recebido-em": iso(-MIN), "lido-em": iso(0), "ciente-em": null }, "pode-ver-leitura": false,
    ...extra,
  };
}

const leitura = {
  comunicado: { id: "c1", protocolo: "COM-2026-000123", assunto: "Sessão" },
  totais: { destinatarios: 15, recebidos: 14, lidos: 12, cientes: 9, "pendentes-vencidos": 1 },
  linhas: [
    { "identidade-id": "i1", nome: "Ana Lima", via: "setor Jurídico", "recebido-em": "2026-10-02T13:05:00Z", "lido-em": "2026-10-02T13:10:00Z", "ciente-em": "2026-10-02T13:11:00Z", vencido: false },
    { "identidade-id": "i2", nome: "Bruno Sá", via: "Comissão de Finanças", "recebido-em": null, "lido-em": null, "ciente-em": null, vencido: true },
  ],
};

type Resp = { status?: number; corpo?: unknown };
function mockar(rotas: Record<string, Resp>) {
  const chamadas: string[] = [];
  global.fetch = vi.fn(async (url: string, init?: RequestInit) => {
    const chave = `${init?.method ?? "GET"} ${url}`;
    chamadas.push(chave);
    const r = rotas[chave];
    if (!r) return { ok: false, status: 404, json: async () => ({}) } as Response;
    const status = r.status ?? 200;
    return { ok: status < 300, status, json: async () => r.corpo ?? {}, blob: async () => new Blob(["x"]) } as Response;
  }) as unknown as typeof fetch;
  return chamadas;
}

const montar = () => render(<ComunicadoDetalhe id="c1" voltar={{ href: "/caixa", rotulo: "Caixa" }} hrefDoComunicado={(id) => `/comunicados/${id}`} />);

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.unstubAllEnvs();
  auth.token = "tok";
});

describe("o comunicado", () => {
  it("protocolo, assunto, remetente, destinos e o texto COMO TEXTO (quebras mantidas, nada de HTML)", async () => {
    mockar({ "GET /api/comunicados/c1": { corpo: comunicado() } });
    montar();
    expect(await screen.findByRole("heading", { level: 1, name: "Sessão extraordinária na sexta" })).toBeTruthy();
    expect(screen.getByText("COM-2026-000123")).toBeTruthy();
    expect(screen.getByText("Rita Campos")).toBeTruthy();
    expect(screen.getByText("Para: setor Jurídico, Comissão de Finanças")).toBeTruthy();
    const corpo = document.querySelector(".com-corpo")!;
    expect(corpo.textContent).toContain("1. Pauta anexa.\n2. Chegar às 8h.");
    expect(corpo.querySelector("b")).toBeNull();
    expect(corpo.textContent).toContain("<b>sem HTML</b>");
    expect(screen.getByRole("link", { name: "← Caixa" }).getAttribute("href")).toBe("/caixa?token=tok");
  });

  it("abrir avisa o contador do topo (o servidor gravou 'lido')", async () => {
    mockar({ "GET /api/comunicados/c1": { corpo: comunicado() } });
    const ouvinte = vi.fn();
    window.addEventListener("oplenario:caixa-mudou", ouvinte);
    montar();
    await screen.findByRole("heading", { level: 1 });
    await waitFor(() => expect(ouvinte).toHaveBeenCalledTimes(1));
    window.removeEventListener("oplenario:caixa-mudou", ouvinte);
  });

  it("'Estou ciente' registra com um toque e a tela passa a dizer quando", async () => {
    const chamadas = mockar({
      "GET /api/comunicados/c1": { corpo: comunicado() },
      "POST /api/comunicados/c1/ciencia": { corpo: { "ciente-em": "2026-10-02T17:05:00Z" } },
    });
    montar();
    fireEvent.click(await screen.findByRole("button", { name: "Estou ciente" }));
    expect(await screen.findByText(/Você registrou ciência em 02\/10\/2026 às 14:05/)).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Estou ciente" })).toBeNull();
    expect(chamadas).toContain("POST /api/comunicados/c1/ciencia");
  });

  it("a recusa da ciência aparece como alerta e o botão continua", async () => {
    mockar({ "GET /api/comunicados/c1": { corpo: comunicado() }, "POST /api/comunicados/c1/ciencia": { status: 403 } });
    montar();
    fireEvent.click(await screen.findByRole("button", { name: "Estou ciente" }));
    expect((await screen.findByRole("alert")).textContent).toBe("Só quem recebeu o comunicado registra ciência.");
    expect(screen.getByRole("button", { name: "Estou ciente" })).toBeTruthy();
  });

  it("prazo vencido é dito em texto, e a ciência ainda pode ser registrada", async () => {
    mockar({ "GET /api/comunicados/c1": { corpo: comunicado({ "ciencia-ate": "2026-09-30T20:00:00Z" }) } });
    montar();
    expect(await screen.findByText("O prazo para a ciência venceu em 30/09/2026 às 17:00.")).toBeTruthy();
    expect(screen.getByRole("button", { name: "Estou ciente" })).toBeTruthy();
  });

  it("quem não é destinatário não vê o botão; vê que o comunicado pede ciência", async () => {
    mockar({ "GET /api/comunicados/c1": { corpo: comunicado({ "minhas-marcas": null }) } });
    montar();
    expect(await screen.findByText(/Este comunicado pede ciência aos destinatários/)).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Estou ciente" })).toBeNull();
  });

  it("o item ligado leva à tela dele", async () => {
    mockar({ "GET /api/comunicados/c1": { corpo: comunicado({ objeto: { tipo: "proposicao", id: "p7" } }) } });
    montar();
    expect((await screen.findByRole("link", { name: "Abrir a ficha da matéria →" })).getAttribute("href")).toBe("/ficha-materia/p7?token=tok");
  });

  it("não encontrado vira frase", async () => {
    mockar({});
    montar();
    expect((await screen.findByRole("alert")).textContent).toBe("Este comunicado não existe nesta Casa. Confira o endereço.");
  });
});

describe("o painel de leitura (remetente, secretaria, administração)", () => {
  it("o resumo, a tabela com quem falta primeiro e o vencido em destaque", async () => {
    mockar({
      "GET /api/comunicados/c1": { corpo: comunicado({ "pode-ver-leitura": true, "minhas-marcas": null }) },
      "GET /api/comunicados/c1/leitura": { corpo: leitura },
    });
    montar();
    expect(await screen.findByText("12 de 15 leram · 9 cientes · 3 faltam ler · 1 com prazo vencido")).toBeTruthy();
    const tabela = screen.getByRole("table");
    const linhas = within(tabela).getAllByRole("row").slice(1);
    expect(within(linhas[0]).getByRole("rowheader").textContent).toContain("Bruno Sá");
    expect(within(linhas[0]).getByText("Prazo vencido")).toBeTruthy();
    expect(within(linhas[0]).getAllByText("ainda não")).toHaveLength(3);
    expect(within(linhas[1]).getByText("02/10 às 10:11")).toBeTruthy();
    expect(within(tabela).getAllByRole("columnheader").map((c) => c.textContent)).toEqual(["Pessoa", "Caminho", "Recebido", "Lido", "Ciente"]);
  });

  it("corrigir = enviar um substituto (o comunicado não se edita)", async () => {
    mockar({
      "GET /api/comunicados/c1": { corpo: comunicado({ "pode-ver-leitura": true }) },
      "GET /api/comunicados/c1/leitura": { corpo: leitura },
    });
    montar();
    const link = await screen.findByRole("link", { name: "Corrigir (enviar substituto)" });
    expect(link.getAttribute("href")).toBe("/comunicados/novo?substitui=c1&token=tok");
  });

  it("quem não pode ver a leitura nem a pede", async () => {
    const chamadas = mockar({ "GET /api/comunicados/c1": { corpo: comunicado() } });
    montar();
    await screen.findByRole("heading", { level: 1 });
    expect(screen.queryByText("Quem recebeu e leu")).toBeNull();
    expect(chamadas).not.toContain("GET /api/comunicados/c1/leitura");
  });
});

describe("a substituição", () => {
  it("o substituído aponta o substituto e não oferece corrigir de novo", async () => {
    mockar({
      "GET /api/comunicados/c1": { corpo: comunicado({ "pode-ver-leitura": true, "substituido-por": { id: "c2", protocolo: "COM-2026-000124" } }) },
      "GET /api/comunicados/c1/leitura": { corpo: leitura },
    });
    montar();
    const link = await screen.findByRole("link", { name: "COM-2026-000124" });
    expect(link.getAttribute("href")).toBe("/comunicados/c2?token=tok");
    expect(screen.getByText("Substituído")).toBeTruthy();
    expect(screen.queryByRole("link", { name: "Corrigir (enviar substituto)" })).toBeNull();
  });

  it("o substituto aponta o que ele substitui", async () => {
    mockar({ "GET /api/comunicados/c1": { corpo: comunicado({ substitui: { id: "c0", protocolo: "COM-2026-000100" } }) } });
    montar();
    expect((await screen.findByRole("link", { name: "COM-2026-000100" })).getAttribute("href")).toBe("/comunicados/c0?token=tok");
  });
});

describe("os anexos", () => {
  const comAnexo = comunicado({ anexos: [{ id: "a1", nome: "pauta.pdf", "tipo-midia": "application/pdf", bytes: 840 * 1024 }] });

  it("modo dev: 'Baixar' busca pelos bytes com o token", async () => {
    const chamadas = mockar({ "GET /api/comunicados/c1": { corpo: comAnexo }, "GET /api/comunicados/c1/anexos/a1": { corpo: {} } });
    URL.createObjectURL = vi.fn(() => "blob:x");
    URL.revokeObjectURL = vi.fn();
    montar();
    expect(await screen.findByText("840 KB · application/pdf")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Baixar pauta.pdf" }));
    await waitFor(() => expect(chamadas).toContain("GET /api/comunicados/c1/anexos/a1"));
  });

  it("modo real: link direto (o cookie vai junto)", async () => {
    vi.stubEnv("NEXT_PUBLIC_APP_ENV", "production");
    auth.token = null;
    mockar({ "GET /api/comunicados/c1": { corpo: comAnexo } });
    montar();
    const link = await screen.findByRole("link", { name: "Baixar pauta.pdf" });
    expect(link.getAttribute("href")).toBe("/api/comunicados/c1/anexos/a1");
    expect(link.getAttribute("download")).toBe("pauta.pdf");
  });
});

describe("as duas portas", () => {
  it("interno: topo na área Caixa e 'voltar' para os enviados quando veio de lá", async () => {
    mockar({ "GET /api/comunicados/c1": { corpo: comunicado() } });
    render(<PaginaComunicadoInterno />);
    expect(screen.getByTestId("topo").textContent).toBe("Caixa");
    expect((await screen.findByRole("link", { name: "← Enviados" })).getAttribute("href")).toBe("/comunicados/enviados?token=tok");
  });

  it("vereador: fica no app, volta para Avisos", async () => {
    mockar({ "GET /api/comunicados/c1": { corpo: comunicado({ substitui: { id: "c0", protocolo: "COM-2026-000100" } }) } });
    render(<PaginaComunicadoVereador />);
    expect((await screen.findByRole("link", { name: "← Avisos" })).getAttribute("href")).toBe("/notificacoes?token=tok");
    expect(screen.getByRole("link", { name: "COM-2026-000100" }).getAttribute("href")).toBe("/notificacoes/c0?token=tok");
  });
});
