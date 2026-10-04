import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";

// ADR-0019 fatia 2a — /juridico/notas/:id: o jurídico lê a nota técnica da IA e a "usa como rascunho".
const estadoAuth = vi.hoisted(() => ({ papeis: ["juridico"] as string[], estado: "pronto" as "pronto" | "carregando" }));
const push = vi.hoisted(() => vi.fn());
vi.mock("@/lib/auth", () => ({
  useAuth: () => ({ token: "tk" }),
  usePapeis: () => ({ papeis: estadoAuth.papeis, estado: estadoAuth.estado }),
}));
vi.mock("../../../topo", () => ({ TopoInterno: ({ area }: { area: string }) => <div data-testid="topo">{area}</div> }));
vi.mock("next/navigation", () => ({ useParams: () => ({ id: "n1" }), useRouter: () => ({ push, replace: vi.fn() }) }));

import PaginaNotaDoJuridico from "./page";

const nota = (estado = "pendente") => ({
  id: "n1", "proposicao-id": "p1", tipo: "projeto_lei", sequencial: 7, ano: 2026, ementa: "Institui o Programa de Hortas",
  estado, agente: "conferencia-normativa",
  texto: "O projeto institui hortas. [[materia:p1 | institui hortas]]\n\nAplica-se a LOM.",
  "texto-limpo": "O projeto institui hortas.\n\nAplica-se a LOM.",
  citacoes: [{ "fonte-id": "materia:p1", trecho: "institui hortas", status: "conferida", rotulo: "PL 7/2026" }],
  "paragrafos-sem-fonte": [1], "motivos-incerteza": ["sem_fonte"], incerteza: "revisar_com_atencao", "modelo-llm-id": "fake:x",
  "texto-final": null, "criada-em": "2026-09-30T09:00:00Z", "decidida-em": estado === "pendente" ? null : "2026-09-30T10:00:00Z",
});
const pedidoNovo = { id: "ped9", proposicao: null, assunto: "Análise jurídica da matéria", prazo: null, estado: "pendente", "pedido-por": "Lúcia",
  "em-nome-de": null, origem: "nota_tecnica", "criado-em": "2026-09-30T11:00:00Z",
  parecer: { id: "pj1", numero: null, ano: null, estado: "rascunho", conclusao: null, assinatura: null, substituido: false, "origem-rascunho": "nota_tecnica" } };

type Rota = { status?: number; corpo: unknown };
function mockar(rotas: Record<string, Rota>) {
  const chamadas: { metodo: string; url: string; body: unknown }[] = [];
  global.fetch = vi.fn(async (url: string, init?: RequestInit) => {
    const metodo = init?.method ?? "GET";
    chamadas.push({ metodo, url: String(url), body: init?.body ? JSON.parse(String(init.body)) : undefined });
    const r = rotas[`${metodo} ${String(url).split("?")[0]}`];
    if (!r) return { ok: false, status: 404, json: async () => ({}) } as Response;
    const status = r.status ?? 200;
    return { ok: status < 300, status, json: async () => r.corpo } as Response;
  }) as unknown as typeof fetch;
  return chamadas;
}

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  push.mockClear();
  estadoAuth.papeis = ["juridico"];
  estadoAuth.estado = "pronto";
});

const U = "POST /api/legislativo/notas-tecnicas/n1/rascunho-juridico";

describe("/juridico/notas/:id", () => {
  it("guard: quem não é jurídico nem secretaria não vê a nota", () => {
    estadoAuth.papeis = ["vereador"];
    const c = mockar({});
    render(<PaginaNotaDoJuridico />);
    expect(screen.getByRole("heading", { name: "Acesso restrito" })).toBeTruthy();
    expect(c).toHaveLength(0);
  });

  it("a leitura: selo de IA ('não é parecer'), aviso, citação numerada, parágrafo sem fonte e os dispositivos citados", async () => {
    mockar({ "GET /api/legislativo/notas-tecnicas/n1": { corpo: nota() } });
    render(<PaginaNotaDoJuridico />);
    expect(await screen.findByText(/Rascunho produzido por IA/)).toBeTruthy();
    expect(screen.getByText(/Texto de IA não é parecer/)).toBeTruthy();
    expect(screen.getByText(/Leia com atenção: há parágrafos que nenhum dispositivo lido sustenta/)).toBeTruthy();
    expect(screen.getByText("sem fonte — confira")).toBeTruthy();
    expect(screen.getByText("1").getAttribute("title")).toBe("PL 7/2026");
    expect(screen.getByRole("region", { name: "Dispositivos citados" })).toBeTruthy();
    expect(screen.getByRole("link", { name: "Abrir a ficha da matéria" }).getAttribute("href")).toBe("/ficha-materia/p1?token=tk");
    expect(screen.getByRole("link", { name: /Parecer jurídico/ }).getAttribute("href")).toBe("/juridico?token=tk");
  });

  it("com o id da execução na IA a nota oferece 'Reportar erro' (8.4); sem id (nota anterior), não oferece", async () => {
    for (const execucaoIa of ["e-ia-nota-1", undefined]) {
      mockar({ "GET /api/legislativo/notas-tecnicas/n1": { corpo: { ...nota(), ...(execucaoIa ? { "execucao-ia": execucaoIa } : {}) } } });
      render(<PaginaNotaDoJuridico />);
      await screen.findByText(/Rascunho produzido por IA/);
      expect(screen.queryByRole("button", { name: "Reportar erro" }) !== null).toBe(execucaoIa !== undefined);
      cleanup();
    }
  });

  it("'Usar como rascunho': explica o que acontece, faz o POST e leva ao pedido já com o rascunho", async () => {
    const c = mockar({ "GET /api/legislativo/notas-tecnicas/n1": { corpo: nota() }, [U]: { status: 201, corpo: pedidoNovo } });
    render(<PaginaNotaDoJuridico />);
    const bloco = await screen.findByRole("region", { name: "Usar como rascunho" });
    expect(bloco.textContent).toMatch(/nada é assinado por você antes disso/);
    expect(bloco.textContent).toMatch(/sem conclusão/);
    expect(c.some((x) => x.metodo === "POST")).toBe(false);
    fireEvent.click(screen.getByRole("button", { name: "Usar como rascunho" }));
    await waitFor(() => expect(push).toHaveBeenCalledWith("/juridico/ped9?token=tk"));
    const post = c.find((x) => x.metodo === "POST")!;
    expect(post.url).toBe("/api/legislativo/notas-tecnicas/n1/rascunho-juridico");
    expect(post.body).toEqual({});
  });

  it("409 (nota já usada ou pedido com rascunho em curso): alerta e continua na nota, sem navegar", async () => {
    mockar({ "GET /api/legislativo/notas-tecnicas/n1": { corpo: nota() }, [U]: { status: 409, corpo: { erro: "x" } } });
    render(<PaginaNotaDoJuridico />);
    fireEvent.click(await screen.findByRole("button", { name: "Usar como rascunho" }));
    expect((await screen.findByRole("alert")).textContent).toMatch(/Abra o pedido na fila/);
    expect(push).not.toHaveBeenCalled();
    expect((screen.getByRole("button", { name: "Usar como rascunho" }) as HTMLButtonElement).disabled).toBe(false);
  });

  it("403 e falha de rede viram frase; nada navega", async () => {
    mockar({ "GET /api/legislativo/notas-tecnicas/n1": { corpo: nota() }, [U]: { status: 403, corpo: {} } });
    render(<PaginaNotaDoJuridico />);
    fireEvent.click(await screen.findByRole("button", { name: "Usar como rascunho" }));
    expect((await screen.findByRole("alert")).textContent).toMatch(/Só o jurídico da Casa/);
    expect(push).not.toHaveBeenCalled();
  });

  it("nota já decidida: sem botão e sem texto de rascunho", async () => {
    mockar({ "GET /api/legislativo/notas-tecnicas/n1": { corpo: nota("aproveitada") } });
    render(<PaginaNotaDoJuridico />);
    expect(await screen.findByText(/Esta nota já foi aproveitada/)).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Usar como rascunho" })).toBeNull();
    expect(screen.queryByText(/Rascunho produzido por IA/)).toBeNull();
  });

  it("a secretaria pode ler, mas não usa: só o jurídico", async () => {
    estadoAuth.papeis = ["secretario"];
    mockar({ "GET /api/legislativo/notas-tecnicas/n1": { corpo: nota() } });
    render(<PaginaNotaDoJuridico />);
    expect(await screen.findByText(/Rascunho produzido por IA/)).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Usar como rascunho" })).toBeNull();
    expect(screen.getByText(/Só o jurídico da Casa usa a nota/)).toBeTruthy();
  });

  it("nota que não existe (404 do servidor): alerta, sem quebrar", async () => {
    mockar({});
    render(<PaginaNotaDoJuridico />);
    expect((await screen.findByRole("alert")).textContent).toMatch(/não existe nesta Casa/);
  });
});
