import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { SecaoPerfilVereador } from "./secao-perfil-vereador";

// Onda E fatia 2 (Task 6) — wrapper CLIENTE do perfil público do vereador: compõe usePerfilVereador
// (fetch, Task 5) + derivarPerfil (view-model, Task 5). Mesma razão de secao-ficha.tsx: o fetch real mora
// aqui, não em page.tsx (SSR não resolve fetch relativo).
//
// O que estes testes travam é a DEGRADAÇÃO, não o layout. Duas assimetrias que a nota de metodologia e o
// contrato impõem, e que só se verificam no DOM: (a) no caminho de ERRO nada de esqueleto — um perfil com
// campos vazios insinua "parlamentar real sem nenhuma atuação", que o próprio handler do backend chama de
// difamatório; (b) listas vazias com HTTP 200 são parlamentar REAL (suplente que ainda não tomou posse tem
// perfil) e a página renderiza inteira — vazio nunca vira 404.
//
// Fixture em kebab-case: é o wire cru; quem cameliza é `buscarPublico`.

const perfilWire = {
  "vereador-id": "v1",
  "nome-parlamentar": "Helena Past",
  "nome-civil": "Helena Pastore Matos",
  legislatura: { numero: 19, "ano-inicio": 2025, "ano-fim": 2028 },
  "cargo-mesa": "2ª Secretária da Mesa",
  comissoes: ["Comissão de Meio Ambiente"],
  materias: [
    {
      "proposicao-id": "p1",
      tipo: "projeto_lei",
      ano: 2026,
      sequencial: 42,
      ementa: "Hortas comunitárias em terrenos públicos",
      estado: "em_comissoes",
    },
  ],
  "materias-total": 1,
  "normas-de-autoria": 3,
  votos: [
    {
      "votacao-id": "vt1",
      voto: "sim",
      "ocorrido-em": "2026-05-18T14:00:00Z",
      "materia-rotulo": "PL 022/2026",
      "materia-ementa": "Incentivo à energia solar no município",
    },
  ],
  "votos-total": 1,
  presenca: {
    "sessoes-presente": 8,
    "sessoes-com-chamada": 12,
    "janela-de-exercicio-conhecida": true,
    "janela-anterior-a-projecao": false,
  },
  "acervo-com-elo-de-autoria-desde": "2026-07-20",
  "presenca-projetada-desde": "2026-07-20",
};

const perfilVazioWire = {
  ...perfilWire,
  "cargo-mesa": null,
  comissoes: [],
  materias: [],
  "materias-total": 0,
  "normas-de-autoria": 0,
  votos: [],
  "votos-total": 0,
};

function mockFetch(resposta: { ok: boolean; json?: () => Promise<unknown> }) {
  global.fetch = vi.fn(async () => ({
    ok: resposta.ok,
    json: resposta.json ?? (async () => ({})),
  })) as unknown as typeof fetch;
}

describe("SecaoPerfilVereador", () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it("carregando -> nada visível, só aria-busy", () => {
    global.fetch = vi.fn(() => new Promise(() => {})) as unknown as typeof fetch; // nunca resolve
    const { container } = render(<SecaoPerfilVereador ente="fortaleza" vereadorId="v1" />);
    expect(container.querySelector('[aria-busy="true"]')).toBeTruthy();
    expect(container.textContent).toBe("");
  });

  it("erro -> 'Vereador não encontrado' sem afirmar se foi 404 ou instabilidade", async () => {
    mockFetch({ ok: false });
    render(<SecaoPerfilVereador ente="fortaleza" vereadorId="v1" />);
    await waitFor(() => expect(screen.getByRole("status")).toBeTruthy());
    expect(screen.getByText(/vereador não encontrado/i)).toBeTruthy();
    // a copy tem de deixar as DUAS possibilidades abertas — não pode afirmar qual ocorreu.
    expect(screen.getByText(/instabilidade passageira/i)).toBeTruthy();
  });

  it("erro -> NÃO renderiza o esqueleto do perfil com campos vazios (perfil vazio insinuaria parlamentar real sem atuação)", async () => {
    mockFetch({ ok: false });
    const { container } = render(<SecaoPerfilVereador ente="fortaleza" vereadorId="v1" />);
    await waitFor(() => expect(screen.getByRole("status")).toBeTruthy());
    expect(screen.queryByRole("heading", { level: 1 })).toBeNull();
    expect(container.querySelector(".perfil-presenca")).toBeNull();
    expect(container.querySelector(".perfil-cab")).toBeNull();
  });

  it("perfil com listas todas vazias -> a página renderiza inteira: 200 é parlamentar real, nunca vira 404", async () => {
    mockFetch({ ok: true, json: async () => perfilVazioWire });
    const { container } = render(<SecaoPerfilVereador ente="fortaleza" vereadorId="v1" />);
    await waitFor(() => expect(screen.getByRole("heading", { level: 1 }).textContent).toBe("Helena Past"));
    expect(container.querySelector(".perfil-presenca")).toBeTruthy();
    expect(screen.getByText("Nenhuma matéria de autoria consta desta lista.")).toBeTruthy();
    expect(screen.getByText("Ainda não há votos nominais registrados para este vereador.")).toBeTruthy();
    expect(screen.getByText("Não há comissões registradas para este vereador.")).toBeTruthy();
    // a declaração de recorte do acervo é o que impede "0 matérias" de virar "não é autor de nada".
    expect(screen.getByText(/Matérias de autoria estão publicadas a partir de 20\/07\/2026/)).toBeTruthy();
    expect(screen.queryByText(/não encontrado/i)).toBeNull();
  });

  it("a seção de presença renderiza os DOIS números, nunca um percentual", async () => {
    mockFetch({ ok: true, json: async () => perfilWire });
    const { container } = render(<SecaoPerfilVereador ente="fortaleza" vereadorId="v1" />);
    await waitFor(() => expect(container.querySelector(".perfil-presenca")).toBeTruthy());
    const secao = container.querySelector(".perfil-presenca") as HTMLElement;
    expect(secao.querySelector(".presenca-fracao")?.textContent).toBe("8 de 12");
    expect(secao.textContent).toContain(
      "Compareceu a 8 das 12 sessões com registro de presença que a Câmara realizou enquanto este " +
        "vereador estava em exercício do mandato, descontados os períodos de licença registrados.",
    );
    expect(secao.textContent).not.toContain("%");
    expect(secao.textContent).toContain("O documento de fé é a ata de cada sessão.");
  });

  it("trilha aponta para a página inicial do portal, não para a lista de matérias", async () => {
    mockFetch({ ok: true, json: async () => perfilWire });
    const { container } = render(<SecaoPerfilVereador ente="fortaleza" vereadorId="v1" />);
    await waitFor(() => expect(container.querySelector(".migalha")).toBeTruthy());
    const migalha = container.querySelector(".migalha") as HTMLElement;
    const link = migalha.querySelector("a") as HTMLAnchorElement;
    expect(link.getAttribute("href")).toBe("/portal/casa/fortaleza");
    expect(link.textContent).toBe("Início");
    expect(migalha.textContent).toContain("Helena Past");
    expect(migalha.textContent).not.toMatch(/matérias/i);
  });

  it("cada matéria linka para a ficha pública e leva aria-label da faixa de tramitação", async () => {
    mockFetch({ ok: true, json: async () => perfilWire });
    render(<SecaoPerfilVereador ente="fortaleza" vereadorId="v1" />);
    await waitFor(() => expect(screen.getByText("Hortas comunitárias em terrenos públicos")).toBeTruthy());
    const link = screen.getByRole("link", { name: /PL 042\/2026/ });
    expect(link.getAttribute("href")).toBe("/portal/casa/fortaleza/materias/p1");
    expect(
      screen.getByRole("img", {
        name: "Tramitação de PL 042/2026: concluídos Protocolo; atual Comissões; pendente 1º turno, 2º turno, Sanção.",
      }),
    ).toBeTruthy();
  });

  it("voto nominal sai com rótulo textual além da cor (cor nunca é o único sinal)", async () => {
    mockFetch({ ok: true, json: async () => perfilWire });
    const { container } = render(<SecaoPerfilVereador ente="fortaleza" vereadorId="v1" />);
    await waitFor(() => expect(container.querySelector(".votos")).toBeTruthy());
    const voto = container.querySelector(".votos .voto") as HTMLElement;
    expect(voto.textContent).toContain("Incentivo à energia solar no município");
    expect(voto.textContent).toContain("PL 022/2026");
    expect(voto.querySelector(".chip.chip-ok")?.textContent).toBe("A favor");
  });
});
