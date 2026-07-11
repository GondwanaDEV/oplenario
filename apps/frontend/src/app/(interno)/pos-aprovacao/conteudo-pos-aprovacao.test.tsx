import { afterEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { ConteudoPosAprovacao } from "./conteudo-pos-aprovacao";
import { AuthProvider } from "@/lib/auth";
import { TemaProvider } from "@/lib/tema";

// Onda B Slice 7 — mirror da disciplina de ficha-materia/conteudo-ficha-materia.test.tsx: testa
// ConteudoPosAprovacao diretamente (id: string puro) em vez do wrapper de página ([id]/page.tsx).

function renderComProviders() {
  return render(
    <AuthProvider tokenQuery="tok-de-teste">
      <TemaProvider>
        <ConteudoPosAprovacao id="1" />
      </TemaProvider>
    </AuthProvider>,
  );
}

const proposicaoAprovada = {
  id: "1",
  tipo: "projeto_lei",
  ano: 2026,
  sequencial: 22,
  "urn-lex": "urn:x",
  ementa: "Política municipal de incentivo à energia solar",
  estado: "aprovada",
  "lock-version": 2,
  "atualizado-em": "2026-06-10T00:00:00Z",
};

const semAutografo = { autografo: null, "tramitacao-executiva": null };

const comAutografoAguardando = {
  autografo: {
    id: "a1",
    "proposicao-id": "1",
    numero: 22,
    ano: 2026,
    "destinatario-texto": "Prefeitura Municipal",
    "enviado-em": "2026-06-18T00:00:00Z",
    "prazo-resposta-em": "2026-07-03T00:00:00Z",
  },
  "tramitacao-executiva": { id: "te1", "autografo-id": "a1", estado: "aguardando", "lock-version": 0 },
};

function mockFetch(posAprovacaoResposta: unknown) {
  global.fetch = vi.fn(async (url: string) => {
    if (url.includes("/pos-aprovacao")) {
      return { ok: true, json: async () => posAprovacaoResposta } as Response;
    }
    return { ok: true, json: async () => proposicaoAprovada } as Response;
  }) as unknown as typeof fetch;
}

describe("ConteudoPosAprovacao", () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it("estado de carregando -> mostra 'Carregando…'", () => {
    global.fetch = vi.fn(() => new Promise(() => {})) as unknown as typeof fetch;
    renderComProviders();
    expect(screen.getByRole("status")).toBeTruthy();
  });

  it("sem autógrafo -> mostra o botão 'Gerar autógrafo e enviar ao Executivo', sem pipeline", async () => {
    mockFetch(semAutografo);
    renderComProviders();
    await waitFor(() =>
      expect(screen.getByRole("button", { name: /gerar autógrafo e enviar ao executivo/i })).toBeTruthy(),
    );
    expect(screen.queryByRole("list", { name: /etapas da sanção/i })).toBeNull();
  });

  it("clicar em 'Gerar autógrafo' chama o POST e passa a mostrar o pipeline com os dados devolvidos", async () => {
    mockFetch(semAutografo);
    renderComProviders();
    await waitFor(() =>
      expect(screen.getByRole("button", { name: /gerar autógrafo e enviar ao executivo/i })).toBeTruthy(),
    );

    global.fetch = vi.fn(async (url: string, init?: RequestInit) => {
      if (init?.method === "POST") {
        return { ok: true, json: async () => comAutografoAguardando } as Response;
      }
      if (url.includes("/pos-aprovacao")) return { ok: true, json: async () => semAutografo } as Response;
      return { ok: true, json: async () => proposicaoAprovada } as Response;
    }) as unknown as typeof fetch;

    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: /gerar autógrafo e enviar ao executivo/i }));
    });

    await waitFor(() => expect(screen.getByRole("list", { name: /etapas da sanção/i })).toBeTruthy());
    expect(screen.getByText("Autógrafo gerado e enviado ao Executivo")).toBeTruthy();
  });

  it("com autógrafo 'aguardando' -> mostra pipeline, card do autógrafo e prazo do Executivo", async () => {
    mockFetch(comAutografoAguardando);
    renderComProviders();
    await waitFor(() => expect(screen.getByRole("list", { name: /etapas da sanção/i })).toBeTruthy());
    expect(screen.getByText("022/2026")).toBeTruthy();
    expect(screen.getByText("Prazo do Executivo")).toBeTruthy();
    expect(screen.getByRole("button", { name: /registrar retorno/i })).toBeTruthy();
  });

  it("registrar retorno 'sancionado' -> some o form/CTA e mostra o card de Desfecho", async () => {
    mockFetch(comAutografoAguardando);
    renderComProviders();
    await waitFor(() => expect(screen.getByRole("button", { name: /registrar retorno/i })).toBeTruthy());

    fireEvent.click(screen.getByRole("button", { name: /registrar retorno/i }));
    fireEvent.click(screen.getByRole("radio", { name: "Sancionado" }));

    global.fetch = vi.fn(async () => ({
      ok: true,
      json: async () => ({
        id: "te1",
        "autografo-id": "a1",
        estado: "sancionado",
        "respondido-em": "2026-07-01T00:00:00Z",
        "lock-version": 1,
      }),
    }) as Response) as unknown as typeof fetch;

    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: /^registrar retorno$/i }));
    });

    await waitFor(() => expect(screen.getByText("Desfecho")).toBeTruthy());
    expect(screen.getByText(/sancionada e segue para promulgação/i)).toBeTruthy();
    expect(screen.queryByRole("button", { name: /^registrar retorno$/i })).toBeNull();
  });

  it("erro ao carregar a proposição -> estado de erro da página", async () => {
    global.fetch = vi.fn(async () => ({ ok: false, status: 500 }) as Response) as unknown as typeof fetch;
    renderComProviders();
    await waitFor(() => expect(screen.getByText(/não foi possível carregar esta matéria/i)).toBeTruthy());
  });
});
