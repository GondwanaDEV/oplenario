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

  it("nominal sem nenhum voto: placar em cartões próprios (tv-placar) e o miolo ganha estado vazio, nunca um buraco", () => {
    const { container } = montar({ estado: { quorum, placar: placar({ votosNominais: {} }) } });
    // `.placar` é classe GLOBAL do dashboard da Mesa (grade 3 colunas + borda + overflow:hidden): o telão
    // usa `tv-placar` para não herdá-la — o placar saiu cortado num quadro estreito em produção.
    expect(container.querySelector(".tv-placar")).not.toBeNull();
    expect(container.querySelector(".placar")).toBeNull();
    expect(container.querySelectorAll(".tv-placar .pl")).toHaveLength(3);
    expect(screen.getByRole("status").textContent).toMatch(/Os votos aparecem aqui/);
    expect(container.querySelector(".nominal")).toBeNull();
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

describe("Modo TV — em apreciação (docs/23 Fatia 4b)", () => {
  const pautaComMateria: PautaOut = {
    "sessao-id": "s1",
    itens: [
      {
        id: "i22", fase: "ordem_do_dia", "tipo-item": "proposicao", "proposicao-id": "p22", ordem: 3,
        proposicao: { tipo: "projeto_lei", ano: 2026, sequencial: 22, ementa: "Energia solar em prédios públicos", "autor-texto": "Ver. Ana Castro" },
      },
    ],
  };
  const orador = { falaId: "f1", oradorId: "ver-c", tipoFala: "principal", fase: "ordem_do_dia", iniciouEm: "2026-09-23T17:30:00Z" };

  it("anúncio ao vivo: a matéria vira o herói, com autoria, quem fala (com partido) e o quórum", () => {
    const { container } = montar({
      pauta: pautaComMateria,
      estado: {
        quorum,
        oradorAtual: orador,
        composicao: new Map([["ver-c", { nomeParlamentar: "Carlos Tavares", cargoMesa: null, partido: "PDT" }]]),
        anuncio: { itemId: "i22", anunciadoEm: "2026-09-24T12:05:00Z", proposicaoId: "p22", votacaoNoAnuncio: null },
      },
    });
    expect(container.querySelector("main")?.getAttribute("data-fase")).toBe("em-apreciacao");
    const secao = screen.getByRole("region", { name: "Matéria em apreciação" });
    expect(secao.textContent).toContain("Em apreciação · Ordem do Dia");
    expect(screen.getByRole("heading", { level: 1, name: "Energia solar em prédios públicos" })).toBeTruthy();
    expect(secao.textContent).toContain("Autoria: Ver. Ana Castro");
    expect(secao.textContent).toContain("Fala principal · PDT");
    expect(secao.textContent).toContain("18 de 21");
  });

  it("o anúncio pede a pauta de novo (item extrapauta incluído depois que a TV abriu)", () => {
    montar({ pauta: pautaComMateria, estado: { anuncio: { itemId: "i-novo", anunciadoEm: "2026-09-24T12:05:00Z", proposicaoId: null, votacaoNoAnuncio: null } } });
    expect(usePautaMock).toHaveBeenCalledWith("s1", "tok", "aberta|i-novo");
  });

  it("com a votação aberta, a votação vence", () => {
    const { container } = montar({
      pauta: pautaComMateria,
      estado: { quorum, placar: placar(), anuncio: { itemId: "i22", anunciadoEm: "2026-09-24T12:05:00Z", proposicaoId: "p22", votacaoNoAnuncio: null } },
    });
    expect(container.querySelector("main")?.getAttribute("data-fase")).toBe("votacao");
  });
});

describe("Modo TV — tempo da fala e campainha (mig 0081)", () => {
  const falaHa = (segundos: number, tempoConcedidoSegundos: number | null) => ({
    falaId: "f1",
    oradorId: "ver-c",
    tipoFala: "principal",
    fase: "ordem_do_dia",
    iniciouEm: new Date(Date.now() - segundos * 1000).toISOString(),
    tempoConcedidoSegundos,
  });

  it("com limite: contagem regressiva e 'restantes de'", () => {
    const { container } = montar({ estado: { quorum, oradorAtual: falaHa(60, 300) } });
    const crono = container.querySelector(".crono");
    expect(crono?.className).toContain("correndo");
    expect(crono?.textContent).toMatch(/0[34]:[0-5]\d/);
    expect(crono?.textContent).toContain("restantes de 05:00");
  });

  it("esgotado: 'tempo esgotado', o excedido e o cartão marcado (a cor nunca é o único sinal)", () => {
    const { container } = montar({ estado: { quorum, oradorAtual: falaHa(400, 300) } });
    const crono = container.querySelector(".crono");
    expect(crono?.className).toContain("esgotado");
    expect(crono?.textContent).toContain("tempo esgotado");
    expect(crono?.textContent).toMatch(/\+01:[34]\d/);
    expect(container.querySelector(".cartao.tribuna.esgotado")).toBeTruthy();
  });

  it("sem limite: o relógio só conta, como antes", () => {
    const { container } = montar({ estado: { quorum, oradorAtual: falaHa(90, null) } });
    expect(container.querySelector(".crono")?.textContent).toContain("no uso da palavra");
  });

  it("o aviso de campainha sem som não disputa a tela com o convite de tela cheia", () => {
    montar({ estado: { quorum, oradorAtual: falaHa(60, 300) } });
    expect(screen.getByRole("button", { name: /Entrar em tela cheia/ })).toBeTruthy();
    expect(screen.queryByRole("button", { name: /Campainha sem som/ })).toBeNull();
  });
});
