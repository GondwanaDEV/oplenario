import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import PaginaPlenario from "./page";
import type { SessaoOut } from "@/lib/contrato";
import { TemaProvider } from "@/lib/tema";
import { aplicarEvento, estadoInicial, hidratarComposicao, hidratarVotacao, type EstadoPlenario, type PlacarVotacao } from "@/lib/plenario-reducer";

// O telão da Mesa (`/sessoes/:id/plenario`). `use-plenario` tem testes próprios (a recuperação por snapshot na
// carga e na reconexão); aqui a prova é de PÁGINA: ela liga a hidratação por snapshot (`comQuorum` +
// `comVotacao`) — sem elas o telão que abre/recarrega fora da retenção do canal (~5 min) não mostra a votação
// em curso — e mostra o que o estado hidratado traz, sem nenhum evento SSE.

vi.mock("next/navigation", () => ({
  useParams: () => ({ id: "s1" }),
  useSearchParams: () => ({ get: () => null }),
}));
vi.mock("@/lib/auth", () => ({
  AuthProvider: ({ children }: { children: React.ReactNode }) => children,
  useAuth: () => ({ token: "tok" }),
}));
const usePlenarioMock = vi.fn();
vi.mock("@/lib/use-plenario", () => ({ usePlenario: (...a: unknown[]) => usePlenarioMock(...a) }));
vi.mock("@/lib/use-pauta", () => ({ usePauta: () => ({ pauta: null, estado: "ok" }) }));

beforeAll(() => {
  // jsdom não tem matchMedia; `useAgora` o consulta (prefers-reduced-motion)
  window.matchMedia ??= ((q: string) => ({ matches: false, media: q, addEventListener() {}, removeEventListener() {} })) as unknown as typeof window.matchMedia;
});
afterEach(() => {
  cleanup();
  usePlenarioMock.mockReset();
});

const sessao: SessaoOut = {
  id: "s1",
  "sessao-legislativa-id": "sl1",
  "tipo-sessao": "ordinaria",
  "numero-sequencial": 15,
  estado: "aberta",
  modalidade: "presencial",
  delibera: true,
  "transmite-publica": true,
  "gera-ata-regimental": true,
  "permite-voto-secreto": true,
  "permite-modalidade-remota": false,
  "agendada-para": null,
  "aberta-em": "2026-09-23T16:25:00Z",
  "encerrada-em": null,
  "motivo-nao-realizada": null,
};

const placar: PlacarVotacao = {
  votacaoId: "v1",
  modalidade: "nominal",
  objetoTipo: "proposicao",
  objetoId: "p22",
  proposicao: { tipo: "projeto_lei", ano: 2026, sequencial: 22, ementa: "Energia solar em prédios públicos" },
  encerrada: false,
  votosNominais: { v1: "sim" },
  votosSecretos: 0,
  resultado: null,
  totais: null,
  baseMembros: null,
};

function montar(estado: Partial<EstadoPlenario>) {
  usePlenarioMock.mockReturnValue({ sessao, estado: { ...estadoInicial(sessao), ...estado }, conexao: "ao-vivo", erro: null });
  return render(<TemaProvider><PaginaPlenario /></TemaProvider>);
}

describe("Telão da Mesa — recuperação de estado", () => {
  it("liga a hidratação por snapshot de quórum/tribuna E de votação no usePlenario", () => {
    montar({});
    expect(usePlenarioMock).toHaveBeenCalledWith("s1", "tok", { comQuorum: true, comVotacao: true });
  });

  it("estado hidratado com votação aberta (nenhum evento SSE visto) mostra a matéria e o placar em curso", () => {
    montar({ placar });
    expect(screen.getByText("Votação em curso")).toBeTruthy();
    expect(screen.getByText(/Energia solar em prédios públicos/)).toBeTruthy();
  });
});

// O defeito (04/10/2026): a grade nominal do telão imprimia `vereadorId.slice(0, 8)` — o prefixo do UUID — no
// lugar do nome do parlamentar, embora `usePlenario` já hidrate a composição (a tribuna e a TV a usam).
// Ids de verdade (uuid), não "v1": o defeito só aparece com o formato real.
describe("Telão da Mesa — placar nominal com o NOME do vereador", () => {
  const ANA = "64d38c04-1111-4222-8333-aaaaaaaaaaaa";
  const BRUNO = "e9a7b2c1-2222-4333-8444-bbbbbbbbbbbb";
  const SEM_CADASTRO = "0f1e2d3c-3333-4444-8555-cccccccccccc";
  const composicao = {
    sessaoId: "s1", sessaoEstado: "aberta", dataDeComposicao: "2026-09-01", composicaoResolvidaEm: "2026-09-01T23:00:00Z",
    membros: [
      { vereadorId: ANA, nomeParlamentar: "Ana Ribeiro", cargoMesa: null, partido: "PDT" },
      { vereadorId: BRUNO, nomeParlamentar: "Bruno Lima", cargoMesa: null, partido: null },
    ],
  } as never;
  const semUuid = () => {
    const texto = document.body.textContent ?? "";
    for (const id of [ANA, BRUNO, SEM_CADASTRO]) expect(texto).not.toContain(id.slice(0, 8));
  };
  const aberta = () => estadoInicial(sessao);

  it("voto chegado por EVENTO SSE mostra o nome, nunca o prefixo do uuid", () => {
    let e = hidratarComposicao(aberta(), composicao);
    e = aplicarEvento(e, { tipo: "votacao.aberta", seq: 1, dados: { "votacao-id": "vt1", "sessao-id": "s1", modalidade: "nominal", "objeto-tipo": "proposicao", "objeto-id": "p1" } } as never);
    e = aplicarEvento(e, { tipo: "voto.registrado", seq: 2, dados: { "votacao-id": "vt1", "sessao-id": "s1", modalidade: "nominal", "vereador-id": ANA, voto: "sim" } } as never);
    e = aplicarEvento(e, { tipo: "voto.registrado", seq: 3, dados: { "votacao-id": "vt1", "sessao-id": "s1", modalidade: "nominal", "vereador-id": BRUNO, voto: "nao" } } as never);
    montar(e);
    const lista = screen.getByRole("list", { name: "Votos nominais" });
    expect(lista.textContent).toContain("Ana Ribeiro");
    expect(lista.textContent).toContain("Bruno Lima");
    semUuid();
  });

  it("voto chegado por SNAPSHOT de recuperação (sem evento SSE) mostra o nome", () => {
    let e = hidratarComposicao(aberta(), composicao);
    e = hidratarVotacao(e, { votacaoId: "vt1", modalidade: "nominal", objetoTipo: "proposicao", objetoId: "p1", votosRegistrados: 2, votos: [{ vereadorId: ANA, voto: "sim" }, { vereadorId: BRUNO, voto: "abstencao" }] } as never, e.votacaoEventoSeq);
    montar(e);
    const lista = screen.getByRole("list", { name: "Votos nominais" });
    expect(lista.textContent).toContain("Ana Ribeiro");
    expect(lista.textContent).toContain("Bruno Lima");
    semUuid();
  });

  it("a composição ainda não chegou (corrida de carga): rótulo neutro, nunca o uuid", () => {
    montar({ placar: { ...placar, votosNominais: { [ANA]: "sim", [SEM_CADASTRO]: "nao" } } });
    const lista = screen.getByRole("list", { name: "Votos nominais" });
    expect(lista.textContent).toContain("Vereador(a)");
    semUuid();
  });

  it("composição chegou mas o id não é de membro: rótulo neutro; os com nome seguem em ordem alfabética", () => {
    montar({
      ...hidratarComposicao(aberta(), composicao),
      placar: { ...placar, votosNominais: { [BRUNO]: "sim", [SEM_CADASTRO]: "nao", [ANA]: "sim" } },
    });
    const itens = screen.getAllByRole("listitem").filter((li) => li.closest("ul")?.getAttribute("aria-label") === "Votos nominais");
    expect(itens.map((li) => li.querySelector("b")?.textContent)).toEqual(["Ana Ribeiro", "Bruno Lima", "Vereador(a)"]);
    semUuid();
  });

  it("votação SECRETA continua sem grade nominal (sigilo não regride)", () => {
    montar({
      ...hidratarComposicao(aberta(), composicao),
      placar: { ...placar, modalidade: "secreta", votosNominais: {}, votosSecretos: 2 },
    });
    expect(screen.queryByRole("list", { name: "Votos nominais" })).toBeNull();
    expect(document.body.textContent).not.toContain("Ana Ribeiro");
  });
});
