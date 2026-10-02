import { describe, expect, it, vi, afterEach } from "vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { TopoInterno, destinosVisiveis } from "./topo";
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
  // `cleanup` explicito (a suite nao liga os globals do Vitest, entao o Testing Library nao desmonta sozinho): sem
  // ele, os tres Topos ficavam montados ate' o fim do arquivo e, sob carga no CI, o React ainda tinha trabalho
  // agendado quando o jsdom era desmontado -> "ReferenceError: window is not defined" (3 erros nao tratados, CI do
  // PR #82, com os 8 testes verdes).
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

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

describe("destinosVisiveis — a nav por papel", () => {
  const rotulos = (papeis: string[]) => destinosVisiveis(papeis).map((d) => d.rotulo);

  it("a secretaria vê as telas de trabalho e NÃO vê as do administrador", () => {
    const r = rotulos(["secretario"]);
    expect(r).toContain("Proposições");
    expect(r).not.toContain("Administração");
    expect(r).not.toContain("IA da Casa");
  });

  it("quem é SÓ administrador da Casa vê só a área dele — nada que o leve a 'Acesso restrito' (ADR-0005)", () => {
    expect(rotulos(["admin_ente"])).toEqual(["IA da Casa", "Administração", "Auditoria"]);
  });

  it("o controle interno (auditor) vê só a trilha de auditoria (ADR-0017)", () => {
    expect(rotulos(["auditor"])).toEqual(["Auditoria"]);
    expect(rotulos(["secretario"])).toContain("Auditoria");
  });

  it("o jurídico (juridico) vê só a fila de pareceres — nada das telas da secretaria (ADR-0019)", () => {
    expect(rotulos(["juridico"])).toEqual(["Jurídico"]);
    expect(rotulos(["secretario"])).toContain("Jurídico");
    expect(rotulos(["admin_ente"])).not.toContain("Jurídico");
    expect(rotulos(["vereador"])).not.toContain("Jurídico");
  });

  it("quem acumula secretaria e administração vê as duas", () => {
    const r = rotulos(["secretario", "admin_ente"]);
    expect(r).toContain("Proposições");
    expect(r).toContain("Administração");
  });
});
