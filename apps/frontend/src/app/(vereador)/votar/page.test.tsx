import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";

// O cockpit inteiro com o `usePlenario` REAL (fetch falso): é a costura página -> hook -> reducer -> vista que
// o defeito atravessava (docs/16, "A Trilha 3 vira gate"). Os hooks de identidade/sessão atual/ação são
// mockados — não são o que se testa aqui, e cada um tem teste próprio.
vi.mock("@/lib/auth", () => ({ useAuth: () => ({ token: "tok" }) }));
vi.mock("@/lib/use-minha-sessao-atual", () => ({
  useMinhaSessaoAtual: () => ({ sessaoId: "s1", situacao: "em_curso", estado: "pronto" }),
}));
vi.mock("@/lib/use-meu-painel", () => ({
  useMeuPainel: () => ({ dados: { vereadorId: "v-eu" }, estado: "pronto", recarregar: () => {} }),
}));
vi.mock("@/lib/use-detalhe-votacao", () => ({ useDetalheVotacao: () => ({ dados: null, estado: "carregando" }) }));
vi.mock("@/lib/use-confirmar-presenca", () => ({
  useConfirmarPresenca: () => ({ confirmar: vi.fn(), estado: "ocioso", erro: null }),
}));
vi.mock("@/lib/use-meu-voto", () => ({ useMeuVoto: () => ({ votar: vi.fn(), estado: "ocioso", erro: null }) }));

import VotarPage from "./page";

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

const sessaoCrua = {
  id: "s1", "sessao-legislativa-id": "sl1", "tipo-sessao": "ordinaria", "numero-sequencial": 14, estado: "aberta",
  modalidade: "presencial", delibera: true, "transmite-publica": true, "gera-ata-regimental": true,
  "permite-voto-secreto": false, "permite-modalidade-remota": false, "agendada-para": "2026-05-21T22:00:00Z",
  "aberta-em": "2026-05-21T22:00:00Z", "encerrada-em": null, "motivo-nao-realizada": null,
};
const votacaoAbertaCrua = { "votacao-id": "vt1", modalidade: "nominal", "objeto-tipo": "proposicao", "objeto-id": "p1", votos: [] };

function fetchFake(minhaPresenca: () => Response | Promise<Response>) {
  return vi.fn(async (url: string) => {
    const u = String(url);
    if (u.includes("/presenca/minha")) return minhaPresenca();
    if (u.includes("/votacao-aberta")) return { ok: true, status: 200, json: async () => votacaoAbertaCrua } as Response;
    if (u.includes("/plenario")) return new Promise<Response>(() => {}); // SSE pendurado: NENHUM evento no replay
    if (u.includes("/composicao")) return { ok: false, status: 403, json: async () => ({}) } as Response;
    return { ok: true, status: 200, json: async () => sessaoCrua } as Response;
  }) as unknown as typeof fetch;
}

describe("cockpit /votar — revisita fora da janela de replay do SSE", () => {
  it("vereador JÁ presente que reabre o cockpit vê o grupo de voto, não 'Confirme sua presença'", async () => {
    global.fetch = fetchFake(
      () =>
        ({
          ok: true,
          status: 200,
          json: async () => ({ "vereador-id": "v-eu", presente: true, "ocorrido-em": "2026-05-21T22:01:00Z", modalidade: "plenario" }),
        }) as Response,
    );
    render(<VotarPage />);
    expect(await screen.findByRole("group", { name: "Seu voto na votação corrente" })).toBeTruthy();
    expect(screen.queryByText("Confirme sua presença para poder votar.")).toBeNull();
  });

  it("quem ainda não confirmou continua vendo 'Confirme sua presença' (o snapshot não inventa presença)", async () => {
    global.fetch = fetchFake(
      () => ({ ok: true, status: 200, json: async () => ({ "vereador-id": "v-eu", presente: false }) }) as Response,
    );
    render(<VotarPage />);
    expect(await screen.findByText("Confirme sua presença para poder votar.")).toBeTruthy();
    expect(screen.queryByRole("group", { name: "Seu voto na votação corrente" })).toBeNull();
  });
});
