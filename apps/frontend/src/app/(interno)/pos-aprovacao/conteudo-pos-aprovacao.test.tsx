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
  aprovada: true,
  "lock-version": 2,
  "atualizado-em": "2026-06-10T00:00:00Z",
};

// Fatia 3 (achado T3-A): o gate do botão é `aprovada` (booleano derivado da votação no backend), NUNCA
// `estado` — texto livre de template por câmara que nenhuma rota HTTP move. Esta proposição tem `estado`
// num rótulo qualquer de trâmite normal e `aprovada: false`, exatamente o caso que fabricou os 4
// autógrafos indevidos (T3-A): a rota /pos-aprovacao/:id navegada direto por URL antes de qualquer voto.
const proposicaoNaoAprovada = {
  ...proposicaoAprovada,
  estado: "em_comissoes",
  aprovada: false,
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

function mockFetch(posAprovacaoResposta: unknown, proposicaoResposta: unknown = proposicaoAprovada) {
  global.fetch = vi.fn(async (url: string) => {
    if (url.includes("/pos-aprovacao")) {
      return { ok: true, json: async () => posAprovacaoResposta } as Response;
    }
    return { ok: true, json: async () => proposicaoResposta } as Response;
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

  it("estado 'vetado' -> mostra 'Apreciação do veto' e o fluxo de apreciar (derrubar) leva ao Desfecho", async () => {
    const comAutografoVetado = {
      autografo: comAutografoAguardando.autografo,
      "tramitacao-executiva": {
        id: "te1",
        "autografo-id": "a1",
        estado: "vetado",
        "veto-tipo": "total",
        "respondido-em": "2026-07-01T00:00:00Z",
        "lock-version": 1,
      },
    };
    mockFetch(comAutografoVetado);
    renderComProviders();
    await waitFor(() => expect(screen.getByText("Apreciação do veto")).toBeTruthy());
    // antes do fluxo, NÃO existe o texto-placeholder estático antigo
    expect(screen.getByRole("button", { name: /apreciar o veto/i })).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: /apreciar o veto/i }));
    fireEvent.click(screen.getByRole("radio", { name: /Veto derrubado/i }));
    fireEvent.change(screen.getByLabelText(/ID da votação/i), { target: { value: "vt-1" } });

    global.fetch = vi.fn(async (_url: string, init?: RequestInit) => {
      const corpo = init?.body ? JSON.parse(init.body as string) : {};
      expect(corpo).toMatchObject({ "lock-version": 1, resultado: "veto_derrubado", "veto-votacao-id": "vt-1" });
      return {
        ok: true,
        json: async () => ({
          id: "te1",
          "autografo-id": "a1",
          estado: "veto_derrubado",
          "apreciado-em": "2026-07-05T00:00:00Z",
          "lock-version": 2,
        }),
      } as Response;
    }) as unknown as typeof fetch;

    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: /registrar apreciação/i }));
    });

    await waitFor(() => expect(screen.getByText("Desfecho")).toBeTruthy());
    expect(screen.getByText(/derrubado pela câmara — a lei segue para promulgação/i)).toBeTruthy();
    expect(screen.queryByRole("button", { name: /registrar apreciação/i })).toBeNull();
  });

  it("matéria NÃO aprovada e sem autógrafo -> botão de gerar fica inacessível (disabled+aria-disabled) e a explicação aparece", async () => {
    mockFetch(semAutografo, proposicaoNaoAprovada);
    renderComProviders();
    await waitFor(() =>
      expect(screen.getByText(/esta matéria ainda não foi aprovada em votação pela câmara/i)).toBeTruthy(),
    );
    const btn = screen.getByRole("button", { name: /gerar autógrafo e enviar ao executivo/i });
    expect((btn as HTMLButtonElement).disabled).toBe(true);
    expect(btn.getAttribute("aria-disabled")).toBe("true");
    expect(btn.getAttribute("aria-describedby")).toBe("pos-aprovacao-nao-aprovada");
    expect(
      screen.getByText(/autógrafo é o ato que leva a matéria aprovada ao executivo/i),
    ).toBeTruthy();
  });

  it("matéria aprovada e sem autógrafo -> botão de gerar fica habilitado (sem aria-disabled) e sem a nota de bloqueio", async () => {
    mockFetch(semAutografo, proposicaoAprovada);
    renderComProviders();
    await waitFor(() =>
      expect(screen.getByRole("button", { name: /gerar autógrafo e enviar ao executivo/i })).toBeTruthy(),
    );
    const btn = screen.getByRole("button", { name: /gerar autógrafo e enviar ao executivo/i });
    expect((btn as HTMLButtonElement).disabled).toBe(false);
    expect(btn.getAttribute("aria-disabled")).toBeNull();
    expect(screen.queryByText(/esta matéria ainda não foi aprovada em votação/i)).toBeNull();
  });

  it("erro ao carregar a proposição -> estado de erro da página", async () => {
    global.fetch = vi.fn(async () => ({ ok: false, status: 500 }) as Response) as unknown as typeof fetch;
    renderComProviders();
    await waitFor(() => expect(screen.getByText(/não foi possível carregar esta matéria/i)).toBeTruthy());
  });
});
