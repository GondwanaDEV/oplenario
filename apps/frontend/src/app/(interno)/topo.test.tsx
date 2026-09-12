import { describe, expect, it, vi, afterEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import { TopoInterno } from "./topo";
import { TemaProvider } from "@/lib/tema";
import { AuthProvider } from "@/lib/auth";

// TopoInterno chama useTema() (src/lib/tema.tsx), useAuth() (src/lib/auth.tsx) e, desde a fatia
// "demo-tres-consertos" #1, useMeuIdentidade() (busca GET /api/meu/identidade) — como useTema(), o
// contexto lança fora do seu Provider (mesmo contrato de composição documentado em src/lib/auth.tsx). O
// teste real precisa do MESMO wrapper que layout.tsx aplica em produção (mirror do padrão em
// auth.test.tsx / proposicoes/page.test.tsx), e agora também mocka `fetch` — o ator não é mais uma prop
// literal, é resolvido do servidor (achado ao vivo: o cabeçalho mostrava o MESMO ator fixo pra qualquer
// persona logada).
describe("TopoInterno", () => {
  afterEach(() => vi.restoreAllMocks());

  it("mostra o rótulo da área e o nome+papel resolvidos do ator autenticado", async () => {
    global.fetch = vi.fn(
      async () => ({ ok: true, json: async () => ({ nome: "Marina Alencar Freire", papeis: ["secretario"] }) }) as Response
    ) as unknown as typeof fetch;

    render(
      <AuthProvider tokenQuery="abc123">
        <TemaProvider>
          <TopoInterno area="Painéis da Mesa" />
        </TemaProvider>
      </AuthProvider>
    );
    // selector: ".area-tag" desambigua da Task 12 — o novo <nav> (abaixo) também renderiza o texto
    // "Painéis da Mesa" no link ativo; sem o selector, getByText acha 2 elementos e lança.
    expect(screen.getByText("Painéis da Mesa", { selector: ".area-tag" })).toBeTruthy();
    await waitFor(() => expect(screen.getByText("Marina Alencar Freire")).toBeTruthy());
    expect(screen.getByText("Secretário(a)")).toBeTruthy();
    expect(screen.getByRole("link", { name: "Painéis da Mesa" })).toBeTruthy();
    expect(screen.getByRole("link", { name: "Proposições" })).toBeTruthy();
    expect(screen.getByRole("link", { name: "Painéis da Mesa" }).getAttribute("aria-current")).toBe("page");
    // Task 14 — comToken deve preservar o ?token= dev na navegação interna via <Link>.
    expect(screen.getByRole("link", { name: "Proposições" }).getAttribute("href")).toBe("/proposicoes?token=abc123");
  });

  it("enquanto a identidade carrega, mostra um rótulo HONESTO — nunca um nome fixo/inventado", () => {
    global.fetch = vi.fn(() => new Promise(() => {})) as unknown as typeof fetch; // nunca resolve
    render(
      <AuthProvider tokenQuery="abc123">
        <TemaProvider>
          <TopoInterno area="Painéis da Mesa" />
        </TemaProvider>
      </AuthProvider>
    );
    expect(screen.getByText("Carregando…")).toBeTruthy();
    expect(screen.queryByText("Sérgio Lopes")).toBeNull();
  });

  it("se a busca falhar, mostra 'Sessão'/'indisponível' — nunca finge um ator (mesma disciplina da fatia 2)", async () => {
    global.fetch = vi.fn(async () => ({ ok: false, status: 401 }) as Response) as unknown as typeof fetch;
    render(
      <AuthProvider tokenQuery="abc123">
        <TemaProvider>
          <TopoInterno area="Painéis da Mesa" />
        </TemaProvider>
      </AuthProvider>
    );
    await waitFor(() => expect(screen.getByText("Sessão")).toBeTruthy());
    expect(screen.getByText("indisponível")).toBeTruthy();
  });
});
