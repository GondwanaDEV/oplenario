import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import PaginaRedigirParecer from "./page";
import { AuthProvider } from "@/lib/auth";
import { TemaProvider } from "@/lib/tema";

// ADR-0019 fatia 2 — o editor do vereador-relator: o texto salvo pela borda /meu, o copiloto da análise (rascunho que só
// entra no campo quando o relator pede) e o pedido de parecer jurídico da matéria. Hooks reais, `fetch` roteado por URL.

vi.mock("next/navigation", () => ({
  useParams: () => ({ id: "p1" }),
  useRouter: () => ({ push: vi.fn(), back: vi.fn() }),
}));

const parecer = {
  id: "p1",
  "objeto-tipo": "proposicao",
  "objeto-id": "obj1",
  "comissao-id": "c1",
  "comissao-nome": "Comissão de Constituição e Justiça",
  "relator-id": "v1",
  "voto-relator": null,
  estado: "com_relator",
  "template-id": "t1",
  "lock-version": 0,
  "criado-em": "2026-07-01T10:00:00Z",
  objeto: { id: "obj1", tipo: "projeto_lei", ano: 2026, sequencial: 12, "urn-lex": "urn:x", ementa: "Institui hortas" },
  relatorio: "Trata-se de projeto de lei.",
  analise: "",
  "texto-estado": "rascunho",
  "texto-numero-versao": 1,
};

const copiloto = {
  analise: {
    texto: "A proposição tem por objeto hortas.\n\n[confirmar: a iniciativa]",
    citacoes: [{ "fonte-id": "materia:obj1", rotulo: "Projeto de Lei nº 12/2026", trecho: "Institui hortas", status: "conferida" }],
    "paragrafos-sem-fonte": [1],
    "pontos-a-confirmar": ["a iniciativa"],
    incerteza: "revisar_com_atencao",
    modelo: "fake-1",
  },
  normas: "citadas",
  indisponivel: null,
};

type Chamada = { url: string; init?: RequestInit };

function rotear(respostas: Record<string, { status?: number; corpo: unknown }>) {
  const chamadas: Chamada[] = [];
  global.fetch = vi.fn(async (url: string, init?: RequestInit) => {
    chamadas.push({ url, init });
    const chave = `${init?.method ?? "GET"} ${url}`;
    const r = respostas[chave] ?? { status: 404, corpo: { erro: "nao roteado" } };
    const status = r.status ?? 200;
    return { ok: status < 400, status, json: async () => r.corpo } as Response;
  }) as unknown as typeof fetch;
  return chamadas;
}

const materiaSemJuridico = { pareceres: [], "pedidos-abertos": [] };

function renderizar() {
  return render(
    <AuthProvider tokenQuery="tok-de-teste">
      <TemaProvider>
        <PaginaRedigirParecer />
      </TemaProvider>
    </AuthProvider>,
  );
}

describe("PaginaRedigirParecer (vereador-relator)", () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it("o rascunho da IA só entra no campo Análise quando o relator pede, e salva pela borda /meu", async () => {
    const chamadas = rotear({
      "GET /api/meu/pareceres/p1": { corpo: parecer },
      "GET /api/legislativo/proposicoes/obj1/pareceres-juridicos": { corpo: materiaSemJuridico },
      "POST /api/meu/pareceres/p1/copiloto": { corpo: copiloto },
      "PATCH /api/meu/pareceres/p1": { corpo: { ...parecer, analise: "salvo", "texto-numero-versao": 2 } },
    });
    renderizar();
    const campo = (await screen.findByLabelText("Análise")) as HTMLTextAreaElement;
    expect((screen.getByLabelText("Relatório") as HTMLTextAreaElement).value).toBe("Trata-se de projeto de lei.");

    fireEvent.click(screen.getByRole("button", { name: "Rascunhar análise de constitucionalidade e juridicidade" }));
    expect(await screen.findByText(/Não é parecer: quem assina é o relator/)).toBeTruthy();
    expect(campo.value).toBe("");
    fireEvent.click(screen.getByRole("button", { name: "Usar no campo Análise" }));
    expect(campo.value).toBe("A proposição tem por objeto hortas.\n\n[confirmar: a iniciativa]");
    expect(chamadas.filter((c) => c.init?.method === "PATCH")).toHaveLength(0);

    fireEvent.click(screen.getByRole("button", { name: "Salvar rascunho" }));
    await waitFor(() => expect(screen.getByText(/Rascunho salvo \(versão 2\)/)).toBeTruthy());
    const patch = chamadas.find((c) => c.init?.method === "PATCH");
    expect(patch?.url).toBe("/api/meu/pareceres/p1");
    expect(JSON.parse(String(patch?.init?.body))).toEqual({
      relatorio: "Trata-se de projeto de lei.",
      analise: "A proposição tem por objeto hortas.\n\n[confirmar: a iniciativa]",
    });
  });

  it("IA fora (503): a mensagem manda redigir no campo; nada muda", async () => {
    rotear({
      "GET /api/meu/pareceres/p1": { corpo: parecer },
      "GET /api/legislativo/proposicoes/obj1/pareceres-juridicos": { corpo: materiaSemJuridico },
      "POST /api/meu/pareceres/p1/copiloto": { status: 503, corpo: { erro: "fora" } },
    });
    renderizar();
    const campo = (await screen.findByLabelText("Análise")) as HTMLTextAreaElement;
    fireEvent.click(screen.getByRole("button", { name: /Rascunhar análise/ }));
    await waitFor(() => expect(screen.getByText(/nada do parecer depende dele/)).toBeTruthy());
    expect(campo.value).toBe("");
  });

  it("pedir parecer jurídico: mostra o que já existe, confirma e dá o recibo", async () => {
    const chamadas = rotear({
      "GET /api/meu/pareceres/p1": { corpo: parecer },
      "GET /api/legislativo/proposicoes/obj1/pareceres-juridicos": {
        corpo: {
          pareceres: [],
          "pedidos-abertos": [{ id: "a", assunto: "Competência", prazo: null, "criado-em": "2026-09-29T12:00:00Z" }],
        },
      },
      "POST /api/meu/pareceres/p1/pedido-juridico": {
        status: 201,
        corpo: {
          id: "pj1",
          proposicao: { id: "obj1", ref: "PL 12/2026", ementa: "Institui hortas" },
          assunto: "Iniciativa do Prefeito",
          prazo: null,
          estado: "pendente",
          "pedido-por": "Vereadora Ana",
          "em-nome-de": null,
          origem: "relator",
          "criado-em": "2026-09-30T12:00:00Z",
          parecer: null,
        },
      },
    });
    renderizar();
    expect(await screen.findByText("Pedido em aberto desde 29/09/2026: Competência")).toBeTruthy();
    expect(screen.getByText(/O parecer jurídico é opinativo/)).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Pedir parecer jurídico" }));
    fireEvent.change(screen.getByLabelText("Assunto do pedido (opcional)"), { target: { value: "Iniciativa do Prefeito" } });
    fireEvent.click(screen.getByRole("button", { name: "Confirmar o pedido" }));
    await waitFor(() =>
      expect(screen.getByText(/Pedido registrado — o jurídico da Casa verá na fila\. Assunto: Iniciativa do Prefeito\./)).toBeTruthy(),
    );
    const post = chamadas.find((c) => c.url === "/api/meu/pareceres/p1/pedido-juridico");
    expect(JSON.parse(String(post?.init?.body))).toEqual({ assunto: "Iniciativa do Prefeito" });
    // recarrega a situação da matéria depois do pedido
    await waitFor(() =>
      expect(chamadas.filter((c) => c.url === "/api/legislativo/proposicoes/obj1/pareceres-juridicos")).toHaveLength(2),
    );
  });

  it("parecer já com desfecho: sem editor nem copiloto", async () => {
    rotear({
      "GET /api/meu/pareceres/p1": { corpo: { ...parecer, estado: "aprovado" } },
      "GET /api/legislativo/proposicoes/obj1/pareceres-juridicos": { corpo: materiaSemJuridico },
    });
    renderizar();
    expect(await screen.findByText(/o texto não muda mais/)).toBeTruthy();
    expect(screen.queryByRole("button", { name: /Rascunhar análise/ })).toBeNull();
  });
});
