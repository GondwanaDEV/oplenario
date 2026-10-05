import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, within } from "@testing-library/react";
import PaginaAtuacao from "./page";
import { AuthProvider } from "@/lib/auth";

const ENTE = "10000000-0000-0000-0000-000000000001";
const VEREADOR = "11111111-2222-3333-4444-555555555555";
const TOKEN = JSON.stringify({ sub: "u", papeis: ["vereador"], "ente-id": ENTE });

const painel = (over: Record<string, unknown> = {}) => ({
  "vereador-id": VEREADOR,
  proposicoes: [],
  "proposicoes-truncado": false,
  pareceres: [
    { id: "pc1", "objeto-tipo": "proposicao", "objeto-id": "p1", "comissao-id": "c1", estado: "rascunho",
      "voto-relator": null, "criado-em": "2026-08-01T00:00:00Z" },
    { id: "pc2", "objeto-tipo": "proposicao", "objeto-id": "p2", "comissao-id": "c1", estado: "emitido",
      "voto-relator": "favoravel", "criado-em": "2026-08-02T00:00:00Z" },
  ],
  "pareceres-truncado": false,
  ciencias: [],
  "ciencias-truncado": false,
  ...over,
});

const materia = (id: string, estado: string, sequencial: number) => ({
  "proposicao-id": id, tipo: "projeto_lei", ano: 2026, sequencial, ementa: `Matéria ${id}`, estado,
});

const perfil = (over: Record<string, unknown> = {}) => ({
  "vereador-id": VEREADOR,
  "nome-parlamentar": "Helena Past",
  "nome-civil": "Helena Pastore Matos",
  legislatura: { numero: 19, "ano-inicio": 2025, "ano-fim": 2028 },
  "cargo-mesa": null,
  comissoes: [],
  materias: [
    materia("p1", "em_comissoes", 3),
    materia("p2", "em_comissoes", 2),
    materia("p3", "aguardando_parecer", 1),
  ],
  "materias-total": 3,
  "normas-de-autoria": 1,
  votos: [],
  "votos-total": 74,
  "votos-por-opcao": { sim: 58, nao: 12, abstencao: 4 },
  presenca: {
    "sessoes-presente": 32, "sessoes-com-chamada": 34,
    "janela-de-exercicio-conhecida": true, "janela-anterior-a-projecao": false,
  },
  "acervo-com-elo-de-autoria-desde": "2025-03-04",
  "presenca-projetada-desde": "2026-07-20",
  ...over,
});

function mockApi(rotas: { eu?: unknown; painel?: unknown; perfil?: unknown }) {
  const ok = (body: unknown) => ({ ok: true, status: 200, json: async () => body }) as Response;
  const falha = { ok: false, status: 500, json: async () => ({}) } as Response;
  const f = vi.fn(async (url: string) => {
    if (url.startsWith("/api/eu")) return rotas.eu === undefined ? falha : ok(rotas.eu);
    if (url.startsWith("/api/meu/painel")) return rotas.painel === undefined ? falha : ok(rotas.painel);
    if (url.startsWith(`/api/portal/casa/${ENTE}/vereadores/${VEREADOR}`))
      return rotas.perfil === undefined ? falha : ok(rotas.perfil);
    return { ok: false, status: 404, json: async () => ({}) } as Response;
  });
  global.fetch = f as unknown as typeof fetch;
  return f;
}

function renderizar() {
  return render(
    <AuthProvider tokenQuery={TOKEN}>
      <PaginaAtuacao />
    </AuthProvider>
  );
}

describe("Minha atuação", () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it("mostra os números do perfil público do próprio vereador, resolvido pelo painel", async () => {
    const f = mockApi({ eu: { ator: { "ente-id": ENTE } }, painel: painel(), perfil: perfil() });
    renderizar();
    const nums = await screen.findByRole("list", { name: "Números do mandato" });
    const cartao = (rotulo: string) => within(nums).getByText(rotulo).closest("li")?.querySelector("b")?.textContent;
    expect(cartao("proposições de autoria")).toBe("3");
    expect(cartao("presença em sessões")).toBe("32 de 34");
    expect(cartao("viraram lei")).toBe("1");
    expect(cartao("pareceres como relator")).toBe("2");
    // o perfil é pedido com o ente DA SESSÃO e o vereador DO PAINEL — nunca de um parâmetro da URL
    expect(f.mock.calls.some(([u]) => u === `/api/portal/casa/${ENTE}/vereadores/${VEREADOR}`)).toBe(true);
  });

  describe("o acesso do próprio vereador", () => {
    afterEach(() => vi.unstubAllEnvs());
    const semToken = () =>
      render(
        <AuthProvider tokenQuery={null}>
          <PaginaAtuacao />
        </AuthProvider>
      );

    it("sessão real do login da Casa: o Perfil traz o caminho para trocar o próprio e-mail de acesso", async () => {
      vi.stubEnv("NEXT_PUBLIC_APP_ENV", "production");
      mockApi({
        eu: { ator: { "ente-id": ENTE, papeis: ["vereador"], "tipo-vinculo": "vereador" } },
        painel: painel(),
        perfil: perfil(),
      });
      semToken();
      const link = await screen.findByRole("link", { name: /Trocar meu e-mail de acesso/ });
      expect(link.getAttribute("href")).toBe("/api/auth/conta");
      expect(link.getAttribute("target")).toBe("_blank");
      expect(link.getAttribute("rel")).toBe("noopener noreferrer");
    });

    it("modo dev (token de dev): sem o link", async () => {
      mockApi({ eu: { ator: { "ente-id": ENTE } }, painel: painel(), perfil: perfil() });
      renderizar();
      await screen.findByRole("list", { name: "Números do mandato" });
      expect(screen.queryByRole("link", { name: /e-mail de acesso/ })).toBeNull();
    });
  });

  it("nunca publica percentual de presença", async () => {
    mockApi({ eu: { ator: { "ente-id": ENTE } }, painel: painel(), perfil: perfil() });
    const { container } = renderizar();
    await screen.findByRole("list", { name: "Números do mandato" });
    expect(container.textContent).not.toMatch(/%/);
  });

  it("agrupa as proposições pelo estado do rito da Casa, o mais numeroso primeiro", async () => {
    mockApi({ eu: { ator: { "ente-id": ENTE } }, painel: painel(), perfil: perfil() });
    renderizar();
    const lista = await screen.findByRole("list", { name: "Proposições de autoria por situação" });
    const linhas = within(lista).getAllByRole("listitem").map((li) => li.textContent);
    expect(linhas).toEqual(["Em comissões2", "Aguardando parecer1"]);
  });

  it("'Como você votou' traz as três contagens por opção", async () => {
    mockApi({ eu: { ator: { "ente-id": ENTE } }, painel: painel(), perfil: perfil() });
    renderizar();
    const votos = await screen.findByRole("list", { name: "Votos nominais por opção" });
    expect(within(votos).getAllByRole("listitem").map((li) => li.textContent)).toEqual([
      "58a favor",
      "12contra",
      "4abstenções",
    ]);
  });

  it("pareceres truncados no painel viram 'mais de N', nunca N", async () => {
    mockApi({ eu: { ator: { "ente-id": ENTE } }, painel: painel({ "pareceres-truncado": true }), perfil: perfil() });
    renderizar();
    const nums = await screen.findByRole("list", { name: "Números do mandato" });
    expect(within(nums).getByText("mais de 2")).toBeTruthy();
  });

  it("sem sessão com chamada, o cartão de presença não vira '0 de 0'", async () => {
    mockApi({
      eu: { ator: { "ente-id": ENTE } },
      painel: painel(),
      perfil: perfil({
        presenca: {
          "sessoes-presente": 0, "sessoes-com-chamada": 0,
          "janela-de-exercicio-conhecida": true, "janela-anterior-a-projecao": false,
        },
      }),
    });
    const { container } = renderizar();
    await screen.findByRole("list", { name: "Números do mandato" });
    expect(container.textContent).not.toMatch(/0 de 0/);
    expect(screen.getByText("Ainda não houve sessão com registro de presença neste mandato.")).toBeTruthy();
  });

  it("acesso sem cadastro de vereador na Casa é estado próprio, não erro", async () => {
    mockApi({ eu: { ator: { "ente-id": ENTE } }, painel: painel({ "vereador-id": null }) });
    renderizar();
    expect(await screen.findByText(/não está ligado a um cadastro de vereador/)).toBeTruthy();
  });

  it("falha do perfil público vira erro, nunca números zerados", async () => {
    mockApi({ eu: { ator: { "ente-id": ENTE } }, painel: painel() });
    renderizar();
    expect(await screen.findByText("Não foi possível carregar sua atuação")).toBeTruthy();
    expect(screen.queryByRole("list", { name: "Números do mandato" })).toBeNull();
  });
});
