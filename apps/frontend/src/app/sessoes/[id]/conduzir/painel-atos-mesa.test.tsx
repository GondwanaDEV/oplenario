import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { PainelAtosMesa } from "./painel-atos-mesa";

// docs/23 Fatia 2 — o painel "Atos da Mesa" do cockpit contra uma API falsa COM ESTADO: registrar muda a lista
// que a leitura seguinte devolve.

function json(status: number, body: unknown): Response {
  return { ok: status >= 200 && status < 300, status, json: async () => body } as Response;
}

const COMPOSICAO = {
  "sessao-id": "s1", "sessao-estado": "aberta", "data-de-composicao": "2026-09-24", "composicao-resolvida-em": "x",
  membros: [
    { "vereador-id": "v-ana", "nome-parlamentar": "Ana Castro", "cargo-mesa": null },
    { "vereador-id": "v-bruno", "nome-parlamentar": "Bruno Lima", "cargo-mesa": "Presidente" },
    { "vereador-id": "v-helena", "nome-parlamentar": "Helena Past", "cargo-mesa": "Vice-presidente" },
  ],
};

let decisoes: Record<string, unknown>[];
let incidentes: Record<string, unknown>[];
let posts: { url: string; corpo: Record<string, unknown> }[];
let recusa: Response | null;
let orador: string | null;

function servidor() {
  global.fetch = vi.fn(async (input: string, init?: RequestInit) => {
    const url = String(input);
    if ((init?.method ?? "GET") === "POST") {
      const corpo = JSON.parse(String(init!.body));
      posts.push({ url, corpo });
      if (recusa) return recusa;
      if (url.endsWith("/decisoes-mesa")) decisoes.push({ id: `d${decisoes.length + 1}`, ...corpo });
      else incidentes.push({ id: `i${incidentes.length + 1}`, ...corpo });
      return json(201, { id: "novo" });
    }
    if (url.endsWith("/atos-mesa")) return json(200, { "sessao-id": "s1", decisoes, incidentes });
    if (url.endsWith("/composicao")) return json(200, COMPOSICAO);
    if (url.endsWith("/tribuna")) {
      return json(200, {
        "sessao-id": "s1", "marcos-cronometro": [], inscritos: [],
        "orador-atual": orador ? { "fala-id": "f9", "orador-id": orador } : null,
      });
    }
    return json(404, {});
  }) as unknown as typeof fetch;
}

const MATERIAS = [{ proposicaoId: "p22", rotulo: "PL 22/2026 — Energia solar em prédios públicos" }];

describe("PainelAtosMesa", () => {
  beforeEach(() => {
    decisoes = [];
    incidentes = [
      { id: "i0", tipo: "pedido_vista", resultado: "deferido", descricao: "Vista do PL 31/2026", "ocorrido-em": "2026-09-24T14:00:00Z", "requerente-id": "v-ana" },
    ];
    posts = [];
    recusa = null;
    orador = "v-ana";
    servidor();
  });
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it("lista os atos já registrados com resultado por palavra e quem requereu", async () => {
    render(<PainelAtosMesa sessaoId="s1" token="tok" podeRegistrar materias={MATERIAS} />);
    const item = (await screen.findByText("Vista do PL 31/2026")).closest("li") as HTMLElement;
    expect(within(item).getByText("Pedido de vista")).toBeTruthy();
    expect(within(item).getByText("Deferido")).toBeTruthy();
    expect(await within(item).findByText("Requereu: Ana Castro")).toBeTruthy();
  });

  it("questão de ordem: pré-seleciona o Presidente, oferece vincular à fala em curso e registra em nome de quem presidiu", async () => {
    render(<PainelAtosMesa sessaoId="s1" token="tok" podeRegistrar materias={MATERIAS} />);
    await screen.findByText("Vista do PL 31/2026");
    fireEvent.click(screen.getByRole("button", { name: "Registrar questão de ordem" }));
    const quem = screen.getByLabelText("Quem presidiu e decidiu") as HTMLSelectElement;
    await waitFor(() => expect(quem.value).toBe("v-bruno"));
    expect(await screen.findByLabelText("Vincular à fala em curso (Ana Castro)")).toBeTruthy();
    fireEvent.change(quem, { target: { value: "v-helena" } });
    fireEvent.change(screen.getByLabelText("Questão levantada"), { target: { value: "Cabe aparte na fala?" } });
    fireEvent.change(screen.getByLabelText("Decisão da Mesa"), { target: { value: "Indeferida" } });
    fireEvent.change(screen.getByLabelText("Fundamentação (opcional)"), { target: { value: "Art. 90" } });
    fireEvent.click(screen.getByRole("button", { name: "Registrar decisão" }));
    await screen.findByText("Decisão da Mesa registrada.");
    expect(posts[0].url).toBe("/api/sessoes/s1/decisoes-mesa");
    expect(posts[0].corpo).toMatchObject({
      questao: "Cabe aparte na fala?", decisao: "Indeferida", "presidente-id": "v-helena",
      fundamentacao: "Art. 90", "fala-id": "f9",
    });
    expect(await screen.findByText("Decidiu: Helena Past")).toBeTruthy();
    expect(screen.queryByLabelText("Questão levantada")).toBeNull();
  });

  it("sem fala em curso não oferece o vínculo; desmarcado não manda fala-id", async () => {
    orador = null;
    render(<PainelAtosMesa sessaoId="s1" token="tok" podeRegistrar materias={[]} />);
    await screen.findByText("Vista do PL 31/2026");
    fireEvent.click(screen.getByRole("button", { name: "Registrar questão de ordem" }));
    await waitFor(() => expect((screen.getByLabelText("Quem presidiu e decidiu") as HTMLSelectElement).value).toBe("v-bruno"));
    fireEvent.change(screen.getByLabelText("Questão levantada"), { target: { value: "Q" } });
    fireEvent.change(screen.getByLabelText("Decisão da Mesa"), { target: { value: "D" } });
    fireEvent.click(screen.getByRole("button", { name: "Registrar decisão" }));
    await screen.findByText("Decisão da Mesa registrada.");
    expect(screen.queryByText(/Vincular à fala/)).toBeNull();
    expect(posts[0].corpo).not.toHaveProperty("fala-id");
  });

  it("questão sem texto: explica o que falta e não chama a API", async () => {
    render(<PainelAtosMesa sessaoId="s1" token="tok" podeRegistrar materias={MATERIAS} />);
    await screen.findByText("Vista do PL 31/2026");
    fireEvent.click(screen.getByRole("button", { name: "Registrar questão de ordem" }));
    await waitFor(() => expect((screen.getByLabelText("Quem presidiu e decidiu") as HTMLSelectElement).value).toBe("v-bruno"));
    fireEvent.click(screen.getByRole("button", { name: "Registrar decisão" }));
    expect((await screen.findByRole("alert")).textContent).toBe("Descreva a questão de ordem levantada.");
    expect(posts).toEqual([]);
  });

  it("recusa do servidor (presidente fora da composição) aparece no formulário, que continua aberto", async () => {
    recusa = json(409, { erro: "quem presidiu nao compoe a Casa na data da sessao" });
    render(<PainelAtosMesa sessaoId="s1" token="tok" podeRegistrar materias={MATERIAS} />);
    await screen.findByText("Vista do PL 31/2026");
    fireEvent.click(screen.getByRole("button", { name: "Registrar questão de ordem" }));
    await waitFor(() => expect((screen.getByLabelText("Quem presidiu e decidiu") as HTMLSelectElement).value).toBe("v-bruno"));
    fireEvent.change(screen.getByLabelText("Questão levantada"), { target: { value: "Q" } });
    fireEvent.change(screen.getByLabelText("Decisão da Mesa"), { target: { value: "D" } });
    fireEvent.click(screen.getByRole("button", { name: "Registrar decisão" }));
    expect((await screen.findByRole("alert")).textContent).toBe("quem presidiu nao compoe a Casa na data da sessao");
    expect(screen.getByLabelText("Questão levantada")).toBeTruthy();
  });

  it("incidente: tipo, resultado, matéria da pauta e requerente vão no corpo; entra na lista", async () => {
    render(<PainelAtosMesa sessaoId="s1" token="tok" podeRegistrar materias={MATERIAS} />);
    await screen.findByText("Vista do PL 31/2026");
    fireEvent.click(screen.getByRole("button", { name: "Registrar incidente" }));
    fireEvent.change(screen.getByLabelText("Tipo"), { target: { value: "urgencia" } });
    fireEvent.click(screen.getByLabelText("Indeferido"));
    fireEvent.change(screen.getByLabelText("Descrição"), { target: { value: "Pedido de urgência para o PL 22" } });
    fireEvent.change(screen.getByLabelText("Matéria atingida (opcional)"), { target: { value: "p22" } });
    fireEvent.change(await screen.findByLabelText("Quem requereu (opcional)"), { target: { value: "v-ana" } });
    fireEvent.click(screen.getByRole("button", { name: "Registrar incidente" }));
    await screen.findByText("Incidente registrado.");
    expect(posts[0].url).toBe("/api/sessoes/s1/incidentes");
    expect(posts[0].corpo).toMatchObject({
      tipo: "urgencia", resultado: "indeferido", descricao: "Pedido de urgência para o PL 22",
      "objeto-tipo": "proposicao", "objeto-id": "p22", "requerente-id": "v-ana",
    });
    const item = (await screen.findByText("Pedido de urgência para o PL 22")).closest("li") as HTMLElement;
    expect(within(item).getByText("Indeferido")).toBeTruthy();
  });

  it("sessão fechada: só a lista, sem botões de registro", async () => {
    render(<PainelAtosMesa sessaoId="s1" token="tok" podeRegistrar={false} materias={MATERIAS} />);
    await screen.findByText("Vista do PL 31/2026");
    expect(screen.queryByRole("button", { name: "Registrar questão de ordem" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Registrar incidente" })).toBeNull();
  });
});
