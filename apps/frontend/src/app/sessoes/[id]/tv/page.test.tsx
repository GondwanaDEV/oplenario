import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import PaginaTv from "./page";
import type { PautaOut, SessaoOut } from "@/lib/contrato";
import { estadoInicial, type EstadoPlenario, type PlacarVotacao } from "@/lib/plenario-reducer";

// Mocka os hooks (não a rede): `use-plenario`/`use-pauta` têm testes próprios e toda regra de exibição mora
// em tv-vista/tv-letreiro (testados). Aqui a prova é que a PÁGINA escolhe a fase certa, mostra o que o
// público precisa ler e respeita o sigilo e a honestidade ("—" quando não sabe).

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
const usePautaMock = vi.fn();
vi.mock("@/lib/use-pauta", () => ({ usePauta: (...a: unknown[]) => usePautaMock(...a) }));

const sessao = (over: Partial<SessaoOut> = {}): SessaoOut => ({
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
  ...over,
});

const placar = (over: Partial<PlacarVotacao> = {}): PlacarVotacao => ({
  votacaoId: "v1",
  modalidade: "nominal",
  objetoTipo: "proposicao",
  objetoId: "p22",
  proposicao: { tipo: "projeto_lei", ano: 2026, sequencial: 22, ementa: "Energia solar em prédios públicos" },
  encerrada: false,
  votosNominais: {},
  votosSecretos: 0,
  resultado: null,
  totais: null,
  baseMembros: null,
  ...over,
});

const quorum = { presentesTotal: 18, presentesPlenario: 18, presentesRemoto: 0, membrosDaCasa: 21, presencasForaDoRoster: 0, semRegistroDePresenca: false };

function montar(opts: { estado?: Partial<EstadoPlenario>; sessao?: Partial<SessaoOut>; conexao?: string; erro?: string | null; pauta?: PautaOut | null } = {}) {
  const s = sessao(opts.sessao);
  const conexao = opts.conexao ?? "ao-vivo";
  usePlenarioMock.mockReturnValue(
    conexao === "erro"
      ? { sessao: null, estado: null, conexao, erro: opts.erro ?? null }
      : { sessao: s, estado: { ...estadoInicial(s), ...opts.estado }, conexao, erro: null },
  );
  usePautaMock.mockReturnValue({ pauta: opts.pauta ?? null, estado: "ok" });
  return render(<PaginaTv />);
}

afterEach(() => {
  cleanup();
  usePlenarioMock.mockReset();
  usePautaMock.mockReset();
});

describe("Modo TV — moldura", () => {
  it("topo: número da sessão e selo ao vivo; liga quórum e votação no usePlenario", () => {
    montar();
    expect(screen.getAllByText("15ª Sessão Ordinária").length).toBeGreaterThan(0);
    expect(screen.getByText("Ao vivo")).toBeTruthy();
    expect(usePlenarioMock).toHaveBeenCalledWith("s1", "tok", { comQuorum: true, comVotacao: true });
  });

  it("oferece o botão de tela cheia (gesto exigido pelo navegador)", () => {
    montar();
    expect(screen.getByRole("button", { name: /Entrar em tela cheia/ })).toBeTruthy();
  });

  it("o botão some quando o navegador recusa a tela cheia (a TV segue em janela)", async () => {
    Object.defineProperty(document.documentElement, "requestFullscreen", {
      configurable: true,
      value: vi.fn().mockRejectedValue(new Error("negado")),
    });
    montar();
    fireEvent.click(screen.getByRole("button", { name: /Entrar em tela cheia/ }));
    await screen.findByText("Ao vivo");
    await vi.waitFor(() => expect(screen.queryByRole("button", { name: /Entrar em tela cheia/ })).toBeNull());
  });
});

describe("Modo TV — fases", () => {
  it("agendada → abertura com a pauta do dia (sigla + ementa do resumo)", () => {
    montar({
      sessao: { estado: "agendada", "aberta-em": null },
      estado: { estado: "agendada" },
      pauta: {
        "sessao-id": "s1",
        itens: [
          {
            id: "i1", fase: "ordem_do_dia", "tipo-item": "proposicao", "proposicao-id": "p22", ordem: 1,
            proposicao: { tipo: "projeto_lei", ano: 2026, sequencial: 22, ementa: "Energia solar em prédios públicos" },
          },
        ],
      },
    });
    expect(screen.getByText("Aguardando abertura")).toBeTruthy();
    expect(screen.getByText(/Pauta do dia · 1 item/)).toBeTruthy();
    expect(screen.getByText("Energia solar em prédios públicos")).toBeTruthy();
  });

  it("votação nominal: podem votar, já votaram, faltam, placar e nomes", () => {
    montar({
      estado: {
        quorum,
        composicao: new Map([["ver-a", { nomeParlamentar: "Helena Past", cargoMesa: null }]]),
        placar: placar({ votosNominais: { "ver-a": "sim", "ver-b": "nao" } }),
      },
    });
    const kpi = (rot: string) => screen.getByText(rot).closest(".kpi")!.querySelector("b")!.textContent;
    expect(kpi("Podem votar")).toBe("18");
    expect(kpi("Já votaram")).toBe("2");
    expect(kpi("Faltam")).toBe("16");
    expect(screen.getByText("Helena Past")).toBeTruthy();
    expect(screen.getByText("Energia solar em prédios públicos")).toBeTruthy();
  });

  it("votação sem quórum conhecido: '—', nunca um número inventado", () => {
    montar({ estado: { placar: placar({ votosNominais: { a: "sim" } }) } });
    const kpi = (rot: string) => screen.getByText(rot).closest(".kpi")!.querySelector("b")!.textContent;
    expect(kpi("Podem votar")).toBe("—");
    expect(kpi("Faltam")).toBe("—");
  });

  it("votação secreta: só o contador; nenhum nome nem contagem por voto (§22.6)", () => {
    montar({
      estado: {
        quorum,
        composicao: new Map([["ver-a", { nomeParlamentar: "Helena Past", cargoMesa: null }]]),
        placar: placar({ modalidade: "secreta", votosSecretos: 5 }),
      },
    });
    expect(screen.getByText(/Votação secreta\./)).toBeTruthy();
    expect(screen.queryByText("Helena Past")).toBeNull();
    expect(screen.queryByLabelText("Votos nominais")).toBeNull();
  });

  it("suspensa → aviso de pausa", () => {
    montar({ sessao: { estado: "suspensa" }, estado: { estado: "suspensa" } });
    expect(screen.getAllByText(/Sessão suspensa/).length).toBeGreaterThan(0);
  });

  it("acesso negado (sessão secreta) → 'Sessão reservada', não erro técnico", () => {
    montar({ conexao: "erro", erro: "Acesso ao painel negado." });
    expect(screen.getByText("Sessão reservada")).toBeTruthy();
  });

  it("outra falha → 'Sem sinal da sessão' com a causa", () => {
    montar({ conexao: "erro", erro: "Sessão indisponível." });
    expect(screen.getByText("Sem sinal da sessão")).toBeTruthy();
    expect(screen.getByText(/Sessão indisponível\./)).toBeTruthy();
  });
});

describe("Modo TV — letreiro", () => {
  it("rodapé traz as frases do estado atual", () => {
    montar({ estado: { quorum, placar: placar({ votosNominais: { a: "sim" } }) } });
    const letreiro = screen.getByLabelText("Acontecimentos da sessão");
    expect(letreiro.textContent).toMatch(/Em votação:.*22\/2026.*faltam 17 votos/);
    expect(letreiro.textContent).toMatch(/Quórum: 18 de 21 vereadores presentes/);
  });
});
