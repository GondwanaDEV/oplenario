import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import { ConteudoFichaMateria } from "./conteudo-ficha-materia";
import { AuthProvider } from "@/lib/auth";
import { TemaProvider } from "@/lib/tema";

// Onda B Slice 3 — mirror do padrão de teste de página (proposicoes/page.test.tsx): loading, erro e
// happy-path. Testa ConteudoFichaMateria diretamente (id: string puro) em vez do wrapper de página
// ([id]/page.tsx, que resolve `params` via `use()`) — ver comentário de topo de conteudo-ficha-materia.tsx
// pro porquê (use()+Suspense sem o Router real do Next.js nunca resolve num render() de teste direto).

function renderComProviders(tokenQuery: string | null) {
  return render(
    <AuthProvider tokenQuery={tokenQuery}>
      <TemaProvider>
        <ConteudoFichaMateria id="1" />
      </TemaProvider>
    </AuthProvider>,
  );
}

const respostaFake = {
  proposicao: {
    id: "1",
    tipo: "projeto_lei",
    ano: 2026,
    sequencial: 42,
    "urn-lex": "urn:lex:br;ceara;fortaleza:camara.municipal:projeto.lei:2026;042",
    ementa: "Cria o Programa Municipal de Hortas Comunitárias",
    "autor-texto": "Ver.ª Helena Matos",
    estado: "em_comissoes",
    "lock-version": 3,
    "atualizado-em": "2026-05-12T10:00:00Z",
    texto: "Art. 1º Fica instituído o Programa.",
  },
  tramitacao: [
    { "de-estado": "protocolada", "para-estado": "em_comissoes", gatilho: "distribuir", "ocorrido-em": "2026-04-08T09:00:00Z" },
  ],
  apensadas: [],
  emendas: [],
  pareceres: [],
};

describe("ConteudoFichaMateria", () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it("estado de carregando -> mostra 'Carregando…'", () => {
    global.fetch = vi.fn(() => new Promise(() => {})) as unknown as typeof fetch;
    renderComProviders("tok-de-teste");
    expect(screen.getByRole("status")).toBeTruthy();
  });

  it("erro de rede -> mostra o estado de erro da página", async () => {
    global.fetch = vi.fn(async () => ({ ok: false, status: 500 }) as Response) as unknown as typeof fetch;
    renderComProviders("tok-de-teste");
    await waitFor(() => expect(screen.getByText(/não foi possível carregar/i)).toBeTruthy());
  });

  it("happy-path -> renderiza cabeçalho, trilha, abas e rail com os dados reais", async () => {
    global.fetch = vi.fn(async () => ({ ok: true, json: async () => respostaFake }) as Response) as unknown as typeof fetch;
    renderComProviders("tok-de-teste");
    // "PL 42/2026" aparece 2x de propósito (trilha + cabeçalho — mesma disciplina de ficha-materia.html).
    await waitFor(() => expect(screen.getAllByText("PL 42/2026").length).toBe(2));
    expect(screen.getByText("Cria o Programa Municipal de Hortas Comunitárias")).toBeTruthy();
    expect(screen.getByText(/Ver\.ª Helena Matos/)).toBeTruthy();
    expect(screen.getByRole("tab", { name: /texto vigente/i })).toBeTruthy();
    expect(screen.getByText("Identidade canônica (LexML)")).toBeTruthy();
    const trilha = screen.getByRole("navigation", { name: "Trilha de navegação" });
    expect(
      within(trilha).getByRole("link", { name: "Proposições" }).getAttribute("href"),
    ).toContain("token=tok-de-teste");
  });

  it("o rito da Casa que a rota devolve chega à faixa 'Onde está a matéria' (o nome do estado não decide a posição)", async () => {
    const etapa = (chave: string, rotulo: string) => ({ chave, rotulo, terminal: false });
    const comRito = {
      ...respostaFake,
      proposicao: { ...respostaFake.proposicao, estado: "instrucao" },
      rito: {
        "ordem-unica": true,
        etapas: [etapa("entrada", "Entrada"), etapa("instrucao", "Instrução"), etapa("plenario_unico", "Plenário único")],
        atual: etapa("instrucao", "Instrução"),
        anteriores: null,
        proximas: [etapa("plenario_unico", "Plenário único")],
      },
    };
    global.fetch = vi.fn(async () => ({ ok: true, json: async () => comRito }) as Response) as unknown as typeof fetch;
    renderComProviders("tok-de-teste");
    await waitFor(() => expect(screen.getByRole("img", { name: /Tramitação de/ })).toBeTruthy());
    const faixa = screen.getByRole("img", { name: /Tramitação de/ }).getAttribute("aria-label") ?? "";
    expect(faixa).toContain("concluídos Entrada; atual Instrução; pendente Plenário único");
  });

  it("ordem de heading não pula nível: h1 (ementa) -> h2 (conteúdo/rail) -> h3 (cards)", async () => {
    global.fetch = vi.fn(async () => ({ ok: true, json: async () => respostaFake }) as Response) as unknown as typeof fetch;
    renderComProviders("tok-de-teste");
    await waitFor(() => expect(screen.getByRole("heading", { level: 1 })).toBeTruthy());
    expect(screen.getAllByRole("heading", { level: 2 }).length).toBeGreaterThanOrEqual(2);
    expect(screen.getAllByRole("heading", { level: 3 }).length).toBeGreaterThanOrEqual(3);
    // nenhum h3 antes do 1º h2 na árvore de acessibilidade: rail e cards vivem sob o h2 "Dados e ações".
    const railTitulo = screen.getByText("Dados e ações da matéria");
    expect(railTitulo.tagName).toBe("H2");
  });

  // ledger docs/16, linha 30: o autógrafo e a sanção entram na linha do tempo; a leitura do pós-aprovação é à parte
  function fetchPorRota(posAprovacao: { ok: boolean; corpo?: unknown }) {
    return vi.fn(async (url: RequestInfo | URL) =>
      String(url).includes("/pos-aprovacao")
        ? ({ ok: posAprovacao.ok, status: posAprovacao.ok ? 200 : 500, json: async () => posAprovacao.corpo }) as Response
        : ({ ok: true, json: async () => respostaFake }) as Response,
    ) as unknown as typeof fetch;
  }

  it("linha 30: autógrafo e sanção aparecem na aba Tramitação, junto das transições", async () => {
    global.fetch = fetchPorRota({
      ok: true,
      corpo: {
        autografo: {
          id: "a1", "proposicao-id": "1", numero: 7, ano: 2026, "destinatario-texto": "Prefeito Municipal",
          "enviado-em": "2026-06-01T12:00:00Z",
        },
        "tramitacao-executiva": {
          id: "t1", "autografo-id": "a1", estado: "sancionado", "respondido-em": "2026-06-10T12:00:00Z", "lock-version": 1,
        },
        norma: null,
      },
    });
    renderComProviders("tok-de-teste");
    const painel = await waitFor(() => {
      const p = document.getElementById("p-tram");
      if (!p || !within(p).queryByText("Sancionada pelo Executivo")) throw new Error("ainda sem o ato");
      return p;
    });
    const eventos = within(painel).getAllByText((_, el) => el?.classList.contains("evt") ?? false).map((e) => e.textContent);
    expect(eventos).toEqual([
      "Sancionada pelo Executivo",
      "Autógrafo nº 007/2026 enviado ao Executivo",
      "Protocolado → Em comissões",
    ]);
    expect(screen.getByRole("tab", { name: /tramitação/i }).textContent).toContain("3");
  });

  it("linha 30: se a leitura do pós-aprovação falha, a ficha segue só com as transições", async () => {
    global.fetch = fetchPorRota({ ok: false });
    renderComProviders("tok-de-teste");
    await waitFor(() => expect(screen.getAllByText("PL 42/2026").length).toBe(2));
    const painel = document.getElementById("p-tram")!;
    expect(within(painel).getByText("Protocolado → Em comissões")).toBeTruthy();
    expect(within(painel).queryByText(/Autógrafo/)).toBeNull();
  });
});
