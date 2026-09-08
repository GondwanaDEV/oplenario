import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import PaginaHomeVereador from "./page";
import { AuthProvider } from "@/lib/auth";
import { TemaProvider } from "@/lib/tema";

function renderComProviders(tokenQuery: string | null) {
  return render(
    <AuthProvider tokenQuery={tokenQuery}>
      <TemaProvider>
        <PaginaHomeVereador />
      </TemaProvider>
    </AuthProvider>
  );
}

const painelFake = {
  proposicoes: [
    {
      id: "p1", tipo: "projeto_lei", ano: 2026, sequencial: 42, "urn-lex": "urn:lex:fixture",
      ementa: "Hortas Comunitárias em terrenos públicos", estado: "em_comissoes",
      "atualizado-em": "2026-07-01T00:00:00Z",
    },
  ],
  pareceres: [],
  ciencias: [
    {
      "parecer-id": "pc1", "proposicao-id": "p1", tipo: "projeto_lei", ano: 2026, sequencial: 42,
      "urn-lex": "urn:lex:fixture", ementa: "Hortas Comunitárias em terrenos públicos",
    },
  ],
};

function sessaoCrua(over: Record<string, unknown> = {}) {
  return {
    id: "s1", "sessao-legislativa-id": "sl1", "tipo-sessao": "ordinaria", "numero-sequencial": 1,
    estado: "agendada", modalidade: "presencial", delibera: true, "transmite-publica": true,
    "gera-ata-regimental": true, "permite-voto-secreto": false, "permite-modalidade-remota": false,
    "agendada-para": null, "aberta-em": null, "encerrada-em": null, "motivo-nao-realizada": null,
    ...over,
  };
}

/** Router de `fetch` por "MÉTODO caminho" — mirror do padrão de
 * app/(interno)/cadastros/vereadores/page.test.tsx (`vi.fn(async (url, init) => ...)`), necessário aqui
 * porque a home agora dispara DOIS GETs concorrentes na carga (`/api/meu/painel` + `/api/sessoes`, um por
 * hook) — um `mockResolvedValueOnce` em cadeia não dá pra dizer qual dos dois cada resposta é. Rotas ausentes
 * do mapa caem no `fallback` (default: 404), nunca em silêncio. */
function fetchRoteado(
  rotas: Record<string, () => Response | Promise<Response>>,
  fallback: () => Response | Promise<Response> = () => ({ ok: false, status: 404 }) as Response
) {
  return vi.fn(async (url: string, init?: RequestInit) => {
    const metodo = (init?.method ?? "GET").toUpperCase();
    const chave = `${metodo} ${url}`;
    return (rotas[chave] ?? fallback)();
  }) as unknown as typeof fetch;
}

const semSessoes = () => ({ ok: true, json: async () => ({ sessoes: [] }) }) as Response;

describe("PaginaHomeVereador", () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it("renderiza o herói fora-de-sessão, a ciência pendente e as proposições (sessões: pronto e vazia)", async () => {
    global.fetch = fetchRoteado({
      "GET /api/meu/painel": () => ({ ok: true, json: async () => painelFake }) as Response,
      "GET /api/sessoes": semSessoes,
    });
    renderComProviders("tok-de-teste");

    await waitFor(() => expect(screen.getByText("Sem sessão agora")).toBeTruthy());
    expect(screen.getByText("Tudo em dia.")).toBeTruthy();
    // controle positivo: SÓ com `estadoSessoes === "pronto"` E lista vazia é que "Nenhuma sessão agendada"
    // é uma afirmação verdadeira — os testes abaixo ("carregando"/"erro") provam que ela NÃO aparece fora
    // deste caso.
    expect(screen.getByText("Nenhuma sessão agendada")).toBeTruthy();
    expect(screen.getByText("Para sua ciência")).toBeTruthy();
    expect(screen.getByText("Dar ciência")).toBeTruthy();
    expect(screen.getByText("Hortas Comunitárias em terrenos públicos")).toBeTruthy();
    // "PL 42/2026" aparece 2x (o num-inline do card de ciência + o num do card de proposição) — a mesma
    // matéria referenciada nos dois lugares, não duplicação de bug.
    expect(screen.getAllByText("PL 42/2026")).toHaveLength(2);
  });

  // A REPROVA do defeito #16: uma implementação que decida o texto só por `sessao === null` (ignorando
  // `estadoSessoes`) mostraria "Nenhuma sessão agendada" aqui — porque `/api/sessoes` nunca resolve, então
  // `sessoes` fica `null` pra sempre, exatamente como ficaria se a lista fosse "de fato vazia". Este teste
  // reprova ESSA implementação: ele afirma que o texto de "carregando" aparece e que a afirmação factual
  // "Nenhuma sessão agendada" NUNCA aparece enquanto o fetch está em voo.
  it("sessões ainda carregando -> NÃO afirma 'Nenhuma sessão agendada' nem 'Sem sessão agora'", async () => {
    global.fetch = fetchRoteado({
      "GET /api/meu/painel": () => ({ ok: true, json: async () => painelFake }) as Response,
      "GET /api/sessoes": () => new Promise<Response>(() => {}), // nunca resolve — "carregando" para sempre
    });
    renderComProviders("tok-de-teste");

    await waitFor(() => expect(screen.getByText("Carregando agenda…")).toBeTruthy());
    expect(screen.getByText("Verificando sessão…")).toBeTruthy();
    expect(screen.queryByText("Nenhuma sessão agendada")).toBeNull();
    expect(screen.queryByText("Sem sessão agora")).toBeNull();
    expect(screen.queryByText("Tudo em dia.")).toBeNull();
  });

  // Mesma reprova, para o caso "falhou" (não só "ainda não voltou"): um `!r.ok` na listagem de sessões não
  // pode virar `estado: "pronto"` com lista vazia (ver use-sessoes.test.ts) — e mesmo que virasse, esta
  // tela também não pode tratar erro como "de fato nenhuma".
  it("sessões falharam (500) -> NÃO afirma 'Nenhuma sessão agendada' nem 'Sem sessão agora'", async () => {
    global.fetch = fetchRoteado({
      "GET /api/meu/painel": () => ({ ok: true, json: async () => painelFake }) as Response,
      "GET /api/sessoes": () => ({ ok: false, status: 500 }) as Response,
    });
    renderComProviders("tok-de-teste");

    await waitFor(() => expect(screen.getByText("Agenda indisponível")).toBeTruthy());
    expect(screen.getByText("Sessão: não verificada")).toBeTruthy();
    expect(screen.queryByText("Nenhuma sessão agendada")).toBeNull();
    expect(screen.queryByText("Sem sessão agora")).toBeNull();
    expect(screen.queryByText("Tudo em dia.")).toBeNull();
  });

  // O outro lado do defeito #16: uma sessão ABERTA (com orador na tribuna) e uma AGENDADA existindo ao
  // MESMO tempo — a home tem de mostrar as DUAS coisas, não "Sem sessão agora".
  it("sessão ABERTA agora + AGENDADA no futuro -> mostra as duas, nunca 'Sem sessão agora'", async () => {
    global.fetch = fetchRoteado({
      "GET /api/meu/painel": () => ({ ok: true, json: async () => painelFake }) as Response,
      "GET /api/sessoes": () => ({
        ok: true,
        json: async () => ({
          sessoes: [
            sessaoCrua({ id: "s-aberta", estado: "aberta" }),
            sessaoCrua({ id: "s-agendada", estado: "agendada", "agendada-para": "2026-09-14T19:00:00Z", "tipo-sessao": "ordinaria" }),
          ],
        }),
      }) as Response,
    });
    renderComProviders("tok-de-teste");

    await waitFor(() => expect(screen.getByText("Sessão em andamento")).toBeTruthy());
    expect(screen.queryByText("Sem sessão agora")).toBeNull();
    expect(screen.queryByText("Nenhuma sessão agendada")).toBeNull();
    expect((screen.getByRole("link", { name: /acompanhar a sessão/i }) as HTMLAnchorElement).getAttribute("href")).toBe(
      "/votar?token=tok-de-teste"
    );
    // a próxima sessão agendada continua visível no card — as duas afirmações coexistem.
    expect(screen.getByText("ordinaria")).toBeTruthy();
  });

  it("'Dar ciência' POSTa e revalida o painel (a ciência some da lista)", async () => {
    const painelSemCiencia = { ...painelFake, ciencias: [] };
    let chamadasPainel = 0;
    const fetchMock = fetchRoteado({
      "GET /api/sessoes": semSessoes,
      "GET /api/meu/painel": () => {
        chamadasPainel += 1;
        return { ok: true, json: async () => (chamadasPainel === 1 ? painelFake : painelSemCiencia) } as Response;
      },
      "POST /api/meu/ciencias": () =>
        ({ ok: true, json: async () => ({ id: "c1", "ciente-em": "2026-07-11T09:14:00Z" }) }) as Response,
    });
    global.fetch = fetchMock;
    renderComProviders("tok-de-teste");

    await waitFor(() => expect(screen.getByText("Dar ciência")).toBeTruthy());
    screen.getByText("Dar ciência").click();

    await waitFor(() => expect(screen.queryByText("Para sua ciência")).toBeNull());
    expect(fetchMock).toHaveBeenCalledWith(
      "/api/meu/ciencias",
      expect.objectContaining({ method: "POST" })
    );
  });

  it("sem proposições nem ciências -> mensagens vazias honestas, sem lançar", async () => {
    global.fetch = fetchRoteado({
      "GET /api/meu/painel": () => ({ ok: true, json: async () => ({ proposicoes: [], pareceres: [], ciencias: [] }) }) as Response,
      "GET /api/sessoes": semSessoes,
    });
    renderComProviders("tok-de-teste");

    await waitFor(() => expect(screen.getByText("Nenhuma proposição sua ainda.")).toBeTruthy());
    expect(screen.queryByText("Para sua ciência")).toBeNull();
  });

  it("resposta não-ok do painel -> estado de erro", async () => {
    global.fetch = fetchRoteado({
      "GET /api/meu/painel": () => ({ ok: false, status: 500 }) as Response,
      "GET /api/sessoes": semSessoes,
    });
    renderComProviders("tok-de-teste");
    await waitFor(() => expect(screen.getByText("Não foi possível carregar sua home")).toBeTruthy());
  });

  it("mostra a seção Meus pareceres com link para a página de assinatura", async () => {
    const painelComParecer = {
      ...painelFake,
      pareceres: [
        {
          id: "p1", "objeto-tipo": "proposicao", "objeto-id": "o1", "comissao-id": "c1",
          estado: "com_relator", "voto-relator": null, "criado-em": "2026-07-11T00:00:00Z",
        },
      ],
    };
    global.fetch = fetchRoteado({
      "GET /api/meu/painel": () => ({ ok: true, json: async () => painelComParecer }) as Response,
      "GET /api/sessoes": semSessoes,
    });
    renderComProviders("tok-de-teste");

    await waitFor(() => expect(screen.getByText(/Meus pareceres/i)).toBeTruthy());
    // jest-dom não está instalado neste repo (mesmo padrão dos outros testes deste arquivo) — assert DOM cru.
    // Rota REAL confirmada em Task 11 (apps/frontend/src/app/(vereador)/parecer/[id]/assinar/page.tsx): o
    // grupo de rota `(vereador)` não entra na URL, então é `/parecer/:id/assinar`, NUNCA
    // `/vereador/parecer/:id/assinar`. `comToken` preserva o `?token=` de dev entre navegações internas —
    // MESMO padrão de layout.tsx (tabbar) e do `router.push` de volta em assinar/page.tsx; sem isso o link
    // perderia o token dev no clique e a página de assinatura cairia no guard de auth.
    expect((screen.getByRole("link", { name: /assinar/i }) as HTMLAnchorElement).getAttribute("href")).toBe(
      "/parecer/p1/assinar?token=tok-de-teste"
    );
  });

  it("estado pronto tem um <h1> (review MAJOR react — a11y: sem isso a árvore de headings pula pro h2)", async () => {
    global.fetch = fetchRoteado({
      "GET /api/meu/painel": () => ({ ok: true, json: async () => painelFake }) as Response,
      "GET /api/sessoes": semSessoes,
    });
    const { container } = renderComProviders("tok-de-teste");
    await waitFor(() => expect(screen.getByText("Tudo em dia.")).toBeTruthy());
    expect(container.querySelector("h1")).not.toBeNull();
  });

  it("'Dar ciência' falha -> mostra o erro, não lança (unhandled rejection) e reabilita o botão", async () => {
    const fetchMock = fetchRoteado({
      "GET /api/meu/painel": () => ({ ok: true, json: async () => painelFake }) as Response,
      "GET /api/sessoes": semSessoes,
      "POST /api/meu/ciencias": () => ({ ok: false, status: 500, json: async () => ({ erro: "falha ao dar ciência" }) }) as Response,
    });
    global.fetch = fetchMock;
    renderComProviders("tok-de-teste");

    await waitFor(() => expect(screen.getByText("Dar ciência")).toBeTruthy());
    const clique = screen.getByText("Dar ciência").click();

    await waitFor(() => expect(screen.getByText(/Não foi possível registrar a ciência/)).toBeTruthy());
    // o botão continua ali (a ciência não some) e volta a ficar clicável — nada trava em "enviando" pra sempre.
    // jest-dom não está instalado neste repo (mesmo padrão de outros testes) — assert DOM cru.
    expect((screen.getByText("Dar ciência").closest("button") as HTMLButtonElement).disabled).toBe(false);
    expect(() => clique).not.toThrow();
  });
});
