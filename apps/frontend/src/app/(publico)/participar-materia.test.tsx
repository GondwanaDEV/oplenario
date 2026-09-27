import { afterEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { AcompanharMateria, ComporComentario } from "./participar-materia";

const ENTE = "10000000-0000-0000-0000-000000000001";
const PID = "p1";

function mockFetch(respostas: Record<string, { status: number; corpo: unknown }>) {
  global.fetch = vi.fn(async (url: string | URL | Request, init?: RequestInit) => {
    const chave = `${init?.method ?? "GET"} ${String(url)}`;
    const r = respostas[chave] ?? { status: 404, corpo: {} };
    return { ok: r.status < 300, status: r.status, json: async () => r.corpo } as Response;
  }) as unknown as typeof fetch;
}

describe("ComporComentario (design ficha-materia-publica)", () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it("anônima: convite para entrar com gov.br e voltar a esta matéria", () => {
    render(<ComporComentario ente={ENTE} proposicaoId={PID} sessao={{ estado: "anonima", token: null }} />);
    expect(screen.getByText(/Para comentar, identifique-se com a conta gov\.br/)).toBeTruthy();
    const link = screen.getByRole("link", { name: /entrar com gov\.br e comentar/i });
    expect(link.getAttribute("href")).toContain("via=govbr");
    expect(decodeURIComponent(link.getAttribute("href")!)).toContain(`/portal/casa/${ENTE}/materias/${PID}`);
    expect(screen.queryByRole("button", { name: /enviar comentário/i })).toBeNull();
  });

  it("cidadã: envia e avisa que o comentário passa por moderação", async () => {
    mockFetch({ [`POST /api/portal/materias/${PID}/comentarios`]: { status: 201, corpo: { id: "c1", estado: "pendente" } } });
    render(<ComporComentario ente={ENTE} proposicaoId={PID} sessao={{ estado: "cidada", token: "tok" }} />);
    fireEvent.change(screen.getByLabelText(/deixe seu comentário/i), { target: { value: "Apoio o projeto." } });
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: /enviar comentário/i }));
    });
    await waitFor(() => expect(screen.getByRole("status").textContent).toMatch(/depois da moderação/));
    const [, init] = vi.mocked(global.fetch).mock.calls[0];
    expect(JSON.parse(String(init?.body))).toEqual({ corpo: "Apoio o projeto." });
  });

  it("não envia em branco", () => {
    mockFetch({});
    render(<ComporComentario ente={ENTE} proposicaoId={PID} sessao={{ estado: "cidada", token: "tok" }} />);
    expect((screen.getByRole("button", { name: /enviar comentário/i }) as HTMLButtonElement).disabled).toBe(true);
  });

  it("outra Casa: não oferece o formulário", () => {
    render(<ComporComentario ente={ENTE} proposicaoId={PID} sessao={{ estado: "outra-casa", token: null }} />);
    expect(screen.getByText(/entrou por outra Câmara/)).toBeTruthy();
    expect(screen.queryByLabelText(/deixe seu comentário/i)).toBeNull();
  });
});

describe("AcompanharMateria (cartão 'Quer acompanhar?')", () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it("cidadã que ainda não segue: acompanha com um toque", async () => {
    mockFetch({
      "GET /api/portal/acompanhamentos": { status: 200, corpo: { acompanhamentos: [], "acompanhamentos-total": 0 } },
      [`POST /api/portal/materias/${PID}/acompanhar`]: { status: 201, corpo: { estado: "ativo" } },
    });
    render(<AcompanharMateria ente={ENTE} proposicaoId={PID} sessao={{ estado: "cidada", token: "tok" }} />);
    const botao = await screen.findByRole("button", { name: /acompanhar esta matéria/i });
    await act(async () => {
      fireEvent.click(botao);
    });
    await screen.findByRole("button", { name: /deixar de acompanhar/i });
  });

  it("quem já segue vê 'Deixar de acompanhar'", async () => {
    mockFetch({
      "GET /api/portal/acompanhamentos": {
        status: 200,
        corpo: { acompanhamentos: [{ "proposicao-id": PID }], "acompanhamentos-total": 1 },
      },
    });
    render(<AcompanharMateria ente={ENTE} proposicaoId={PID} sessao={{ estado: "cidada", token: "tok" }} />);
    await screen.findByRole("button", { name: /deixar de acompanhar/i });
  });

  it("anônima: o cartão leva ao gov.br", () => {
    render(<AcompanharMateria ente={ENTE} proposicaoId={PID} sessao={{ estado: "anonima", token: null }} />);
    expect(screen.getByRole("link", { name: /entrar com gov\.br e acompanhar/i }).getAttribute("href")).toContain("via=govbr");
  });
});
