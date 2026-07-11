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
});
