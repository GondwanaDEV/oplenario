import { describe, expect, it, vi, afterEach, beforeEach } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import PaginaPautaConvocacao from "./page";
import { MSG_CONFLITO_PAUTA } from "@/lib/use-editar-pauta";

// docs/23 Fatia 1 — "Montar a pauta". Um servidor FALSO com estado: as escritas mudam a pauta que o GET
// seguinte devolve, então cada teste prova o ciclo inteiro (escrita → recarga → tela), não só o request.

const { papeisAtual } = vi.hoisted(() => ({
  papeisAtual: { papeis: ["secretario"] as string[], estado: "pronto" as "carregando" | "pronto" | "erro" },
}));
vi.mock("@/lib/auth", () => ({
  useAuth: () => ({ token: "tok" }),
  usePapeis: () => papeisAtual,
}));
vi.mock("@/lib/tema", () => ({ useTema: () => ({ tema: "claro", alternar: () => {} }) }));

const SESSAO = {
  id: "s1", "sessao-legislativa-id": "sl1", "tipo-sessao": "ordinaria", "numero-sequencial": 15, estado: "agendada",
  modalidade: "presencial", delibera: true, "transmite-publica": true, "gera-ata-regimental": true,
  "permite-voto-secreto": false, "permite-modalidade-remota": false, "agendada-para": "2026-09-30T17:00:00Z",
  "aberta-em": null, "encerrada-em": null, "motivo-nao-realizada": null,
};

type Item = Record<string, unknown> & { id: string; ordem: number; "lock-version": number; fase: string };

const PROPS = [
  { id: "p-pronta", tipo: "projeto_lei", ano: 2026, sequencial: 48, "urn-lex": "u1", ementa: "Calendário de eventos culturais", estado: "aguardando_pauta", "atualizado-em": "2026-09-01T00:00:00Z" },
  { id: "p-busca", tipo: "requerimento", ano: 2026, sequencial: 241, "urn-lex": "u2", ementa: "Audiência pública sobre mobilidade", estado: "em_comissoes", "atualizado-em": "2026-09-01T00:00:00Z" },
];

let itens: Item[];
let chamadas: { metodo: string; url: string; corpo: unknown }[];
let proximoStatus: number | null;

function pautaInicial(): Item[] {
  return [
    { id: "i1", fase: "expediente", "tipo-item": "leitura", "texto-descricao": "Leitura da ata da 14ª sessão", ordem: 1, "lock-version": 0 },
    { id: "i2", fase: "expediente", "tipo-item": "proposicao", "proposicao-id": "p-x", proposicao: { tipo: "requerimento", ano: 2026, sequencial: 230, ementa: "Requerimento de informações" }, ordem: 2, "lock-version": 0 },
    { id: "i3", fase: "ordem_do_dia", "tipo-item": "proposicao", "proposicao-id": "p-y", proposicao: { tipo: "projeto_lei", ano: 2026, sequencial: 29, ementa: "Programa de arborização" }, ordem: 3, "lock-version": 1 },
    { id: "i4", fase: "ordem_do_dia", "tipo-item": "proposicao", "proposicao-id": "p-z", proposicao: { tipo: "projeto_lei", ano: 2026, sequencial: 31, ementa: "Denomina logradouro" }, ordem: 4, "lock-version": 0 },
  ];
}

function json(status: number, body: unknown): Response {
  return { ok: status >= 200 && status < 300, status, json: async () => body } as Response;
}

function servidorFalso() {
  global.fetch = vi.fn(async (input: string, init?: RequestInit) => {
    const url = String(input);
    const metodo = init?.method ?? "GET";
    if (metodo !== "GET") {
      const corpo = init?.body ? JSON.parse(String(init.body)) : null;
      chamadas.push({ metodo, url, corpo });
      if (proximoStatus) {
        const s = proximoStatus;
        proximoStatus = null;
        return json(s, { erro: "item removido ou lock-version desatualizado" });
      }
      const itemId = url.split("/pauta/itens/")[1];
      if (metodo === "POST") {
        const novo: Item = { id: `n${itens.length + 1}`, fase: corpo.fase, "tipo-item": corpo["tipo-item"], ordem: 99, "lock-version": 0 };
        if (corpo["proposicao-id"]) novo["proposicao-id"] = corpo["proposicao-id"];
        if (corpo["texto-descricao"]) novo["texto-descricao"] = corpo["texto-descricao"];
        itens.push(novo);
        return json(201, { id: novo.id, ordem: 99 });
      }
      const alvo = itens.find((i) => i.id === itemId)!;
      if (metodo === "PATCH") {
        alvo.ordem = corpo["nova-ordem"];
        alvo["lock-version"] += 1;
        return json(200, { id: itemId });
      }
      itens = itens.filter((i) => i.id !== itemId);
      return json(200, { id: itemId });
    }
    if (url === "/api/paineis/sli/sessoes") {
      return json(200, { sessoes: [{ "sessao-id": "s1", "estado-atual": "agendada", situacao: "agendada", "agendada-para": "2026-09-30T17:00:00Z" }], "sessoes-total": 1 });
    }
    if (url === "/api/sessoes/s1") return json(200, SESSAO);
    if (url === "/api/sessoes/s1/pauta") return json(200, { "sessao-id": "s1", itens: itens.map((i) => ({ ...i })) });
    if (url.startsWith("/api/legislativo/proposicoes?")) {
      const busca = new URL(url, "http://x").searchParams.get("busca");
      const lista = busca ? PROPS.filter((p) => p.ementa.toLowerCase().includes(busca.toLowerCase())) : PROPS;
      return json(200, { itens: lista, total: lista.length, pagina: 1, tamanho: 100 });
    }
    if (url === "/api/meu/identidade") return json(200, { nome: "Elvis", papeis: ["secretario"] });
    return json(404, {});
  }) as unknown as typeof fetch;
}

async function pautaCarregada() {
  await screen.findByText("Leitura da ata da 14ª sessão");
}

function grupo(titulo: string) {
  return screen.getByRole("heading", { level: 3, name: titulo }).closest(".grupo") as HTMLElement;
}

describe("PaginaPautaConvocacao — montar a pauta", () => {
  beforeEach(() => {
    papeisAtual.papeis = ["secretario"];
    itens = pautaInicial();
    chamadas = [];
    proximoStatus = null;
    servidorFalso();
  });
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it("mostra a pauta por fase, com numeração dentro da fase e o resumo da matéria vindo da própria pauta", async () => {
    render(<PaginaPautaConvocacao />);
    await pautaCarregada();
    const ordemDoDia = within(grupo("Ordem do Dia"));
    expect(ordemDoDia.getByText("PL 29/2026")).toBeTruthy();
    expect(ordemDoDia.getByText("Programa de arborização")).toBeTruthy();
    expect(within(grupo("Expediente")).getByText("REQ 230/2026")).toBeTruthy();
    // setas: o primeiro de cada fase não sobe, o último não desce
    expect((screen.getByRole("button", { name: "Mover PL 29/2026 para cima" }) as HTMLButtonElement).disabled).toBe(true);
    expect((screen.getByRole("button", { name: "Mover PL 31/2026 para baixo" }) as HTMLButtonElement).disabled).toBe(true);
    expect((screen.getByRole("button", { name: "Mover PL 29/2026 para baixo" }) as HTMLButtonElement).disabled).toBe(false);
  });

  it("descer um item troca a ordem com o vizinho (dois PATCHes com CAS) e a pauta recarrega na nova ordem", async () => {
    render(<PaginaPautaConvocacao />);
    await pautaCarregada();
    fireEvent.click(screen.getByRole("button", { name: "Mover PL 29/2026 para baixo" }));
    await screen.findByText("Ordem atualizada.");
    expect(chamadas).toEqual([
      { metodo: "PATCH", url: "/api/sessoes/s1/pauta/itens/i3", corpo: { "nova-ordem": 4, "lock-version": 1 } },
      { metodo: "PATCH", url: "/api/sessoes/s1/pauta/itens/i4", corpo: { "nova-ordem": 3, "lock-version": 0 } },
    ]);
    await waitFor(() => {
      const nums = within(grupo("Ordem do Dia")).getAllByText(/^PL \d+\/2026$/).map((n) => n.textContent);
      expect(nums).toEqual(["PL 31/2026", "PL 29/2026"]);
    });
  });

  it("retirar pede a classificação, manda a justificativa e o item sai da pauta", async () => {
    render(<PaginaPautaConvocacao />);
    await pautaCarregada();
    fireEvent.click(screen.getByRole("button", { name: "Retirar PL 31/2026 da pauta" }));
    fireEvent.click(screen.getByLabelText("Retirada a pedido do autor"));
    fireEvent.change(screen.getByLabelText("Justificativa (opcional)"), { target: { value: "Autor pediu em ofício" } });
    fireEvent.click(screen.getByRole("button", { name: "Retirar da pauta" }));
    await screen.findByText("Item retirado da pauta.");
    expect(chamadas[0]).toEqual({
      metodo: "DELETE",
      url: "/api/sessoes/s1/pauta/itens/i4",
      corpo: { tipo: "retirada_pedido_autor", "lock-version": 0, justificativa: "Autor pediu em ofício" },
    });
    await waitFor(() => expect(screen.queryByText("Denomina logradouro")).toBeNull());
  });

  it("409 na retirada: avisa o conflito, recarrega a pauta e não finge sucesso", async () => {
    render(<PaginaPautaConvocacao />);
    await pautaCarregada();
    proximoStatus = 409;
    fireEvent.click(screen.getByRole("button", { name: "Retirar PL 31/2026 da pauta" }));
    fireEvent.click(screen.getByRole("button", { name: "Retirar da pauta" }));
    expect((await screen.findByRole("alert")).textContent).toBe(MSG_CONFLITO_PAUTA);
    expect(screen.getByText("Denomina logradouro")).toBeTruthy();
  });

  it("incluir do rail: escolhe a fase e a matéria entra na pauta (e sai do rail)", async () => {
    render(<PaginaPautaConvocacao />);
    await pautaCarregada();
    fireEvent.click(await screen.findByRole("button", { name: "Incluir PL 48/2026 na pauta" }));
    fireEvent.change(screen.getByLabelText("Em qual fase"), { target: { value: "expediente" } });
    fireEvent.click(screen.getByRole("button", { name: "Incluir" }));
    await screen.findByText("PL 48/2026 incluída na pauta.");
    expect(chamadas[0]).toEqual({
      metodo: "POST",
      url: "/api/sessoes/s1/pauta/itens",
      corpo: { fase: "expediente", "tipo-item": "proposicao", "proposicao-id": "p-pronta" },
    });
    await waitFor(() => expect(screen.getByText("Nenhuma matéria pronta fora da pauta.")).toBeTruthy());
  });

  it("incluir item de texto pelo formulário: leitura no expediente", async () => {
    render(<PaginaPautaConvocacao />);
    await pautaCarregada();
    fireEvent.click(screen.getByRole("button", { name: /Incluir item na pauta/ }));
    fireEvent.click(screen.getByLabelText("Comunicado"));
    fireEvent.change(screen.getByLabelText("Fase da sessão"), { target: { value: "expediente" } });
    fireEvent.change(screen.getByLabelText("Descrição do item"), { target: { value: "Comunicado da Presidência" } });
    fireEvent.click(screen.getByRole("button", { name: "Incluir na pauta" }));
    await screen.findByText("Item incluído na pauta.");
    expect(chamadas[0].corpo).toEqual({ fase: "expediente", "tipo-item": "comunicado", "texto-descricao": "Comunicado da Presidência" });
    expect(await within(grupo("Expediente")).findByText("Comunicado da Presidência")).toBeTruthy();
  });

  it("incluir matéria pela busca: busca, escolhe e inclui na fase escolhida", async () => {
    render(<PaginaPautaConvocacao />);
    await pautaCarregada();
    fireEvent.click(screen.getByRole("button", { name: /Incluir item na pauta/ }));
    fireEvent.change(screen.getByLabelText("Buscar matéria"), { target: { value: "mobilidade" } });
    fireEvent.click(screen.getByRole("button", { name: "Buscar" }));
    fireEvent.click(await screen.findByRole("button", { name: "Escolher REQ 241/2026" }));
    fireEvent.click(screen.getByRole("button", { name: "Incluir na pauta" }));
    await screen.findByText("Item incluído na pauta.");
    expect(chamadas[0].corpo).toEqual({ fase: "ordem_do_dia", "tipo-item": "proposicao", "proposicao-id": "p-busca" });
  });

  it("formulário sem matéria escolhida: explica o que falta e não chama a API", async () => {
    render(<PaginaPautaConvocacao />);
    await pautaCarregada();
    fireEvent.click(screen.getByRole("button", { name: /Incluir item na pauta/ }));
    fireEvent.click(screen.getByRole("button", { name: "Incluir na pauta" }));
    expect((await screen.findByRole("alert")).textContent).toBe("Busque e escolha a matéria que entra na pauta.");
    expect(chamadas).toEqual([]);
  });

  it("quem não é secretaria vê acesso restrito", () => {
    papeisAtual.papeis = ["vereador"];
    render(<PaginaPautaConvocacao />);
    expect(screen.getByRole("heading", { name: "Acesso restrito" })).toBeTruthy();
  });
});
