import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import PaginaPlenario from "./page";
import type { SessaoOut } from "@/lib/contrato";
import { TemaProvider } from "@/lib/tema";
import { aplicarEvento, estadoInicial, hidratarComposicao, hidratarVotacao, hidratarVotacaoEncerrada, type EstadoPlenario, type PlacarVotacao } from "@/lib/plenario-reducer";

// O telão da Mesa (`/sessoes/:id/plenario`). `use-plenario` tem testes próprios (a recuperação por snapshot na
// carga e na reconexão); aqui a prova é de PÁGINA: ela liga a hidratação por snapshot (`comQuorum` +
// `comVotacao`) — sem elas o telão que abre/recarrega fora da retenção do canal (~5 min) não mostra a votação
// em curso — e mostra o que o estado hidratado traz, sem nenhum evento SSE.

// A secretaria em todo teste: se esta tela ganhasse a moldura da Clara, o botão apareceria (e o teste da Clara reprova).
const papeisDaClara = vi.hoisted(() => ({ atual: ["secretario"] as string[] }));
vi.mock("next/navigation", () => ({
  useParams: () => ({ id: "s1" }),
  useSearchParams: () => ({ get: () => null }),
  usePathname: () => "/sessoes/s1",
}));
vi.mock("@/lib/auth", () => ({
  AuthProvider: ({ children }: { children: React.ReactNode }) => children,
  useAuth: () => ({ token: "tok", papeis: papeisDaClara.atual }),
  usePapeis: () => ({ papeis: papeisDaClara.atual, estado: "pronto" }),
}));
const usePlenarioMock = vi.fn();
vi.mock("@/lib/use-plenario", () => ({ usePlenario: (...a: unknown[]) => usePlenarioMock(...a) }));
const usePautaMock = vi.fn(() => ({ pauta: null as unknown, estado: "ok" }));
vi.mock("@/lib/use-pauta", () => ({ usePauta: () => usePautaMock() }));

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

describe("Telão da Mesa — a pauta numera por fase", () => {
  it("o 1º item da Ordem do Dia aparece como 1, não como o número da pauta toda", () => {
    usePautaMock.mockReturnValueOnce({
      estado: "ok",
      pauta: {
        "sessao-id": "s1",
        itens: [
          { id: "i1", fase: "expediente", "tipo-item": "leitura", "texto-descricao": "Leitura da ata", ordem: 1 },
          { id: "i2", fase: "expediente", "tipo-item": "leitura", "texto-descricao": "Leitura de ofício", ordem: 2 },
          { id: "i3", fase: "ordem_do_dia", "tipo-item": "leitura", "texto-descricao": "Leitura do parecer", ordem: 5 },
        ],
      },
    });
    montar({});
    const numeros = [...document.querySelectorAll(".pauta-ordem")].map((e) => e.textContent);
    expect(numeros).toEqual(["1", "2", "1"]);
  });
});

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

  // O defeito (04/10/2026, CLAUDE.md §3): recarregar o telão DEPOIS do encerramento perdia o placar. O estado
  // hidratado por `GET /votacao-encerrada` tem de mostrar o MESMO que o `votacao.encerrada` ao vivo.
  describe("depois de RECARREGAR com a votação já encerrada (resultado hidratado, sem evento SSE)", () => {
    const encerradaCrua = {
      votacaoId: "vt1", modalidade: "nominal", objetoTipo: "proposicao", objetoId: "p1",
      proposicao: { tipo: "projeto_lei", ano: 2026, sequencial: 22, ementa: "Energia solar em prédios públicos" },
      resultado: "aprovada", totalSim: 1, totalNao: 1, totalAbstencao: 0, baseMembros: 3,
      votos: [{ vereadorId: ANA, voto: "sim" }, { vereadorId: BRUNO, voto: "nao" }],
    };
    const hidratado = () => {
      const e = hidratarComposicao(aberta(), composicao);
      return hidratarVotacaoEncerrada(e, encerradaCrua as never, e.votacaoEventoSeq);
    };

    it("mostra a votação encerrada, o resultado, o placar e a grade nominal COM NOME", () => {
      montar(hidratado());
      expect(screen.getByRole("heading", { name: "Votação encerrada" })).toBeTruthy();
      expect(screen.queryByText("Votação em curso")).toBeNull();
      expect(document.querySelector(".placar-resultado")?.textContent).toBe("aprovada");
      const lista = screen.getByRole("list", { name: "Votos nominais" });
      expect(lista.textContent).toContain("Ana Ribeiro");
      expect(lista.textContent).toContain("Bruno Lima");
      semUuid();
    });

    it("é o MESMO bloco que o telão ao vivo mostra ao receber `votacao.encerrada` (a ementa é o único acréscimo do HTTP)", () => {
      let ao = hidratarComposicao(aberta(), composicao);
      ao = aplicarEvento(ao, { tipo: "votacao.aberta", seq: 1, dados: { "votacao-id": "vt1", "sessao-id": "s1", modalidade: "nominal", "objeto-tipo": "proposicao", "objeto-id": "p1" } } as never);
      ao = aplicarEvento(ao, { tipo: "voto.registrado", seq: 2, dados: { "votacao-id": "vt1", "sessao-id": "s1", modalidade: "nominal", "vereador-id": ANA, voto: "sim" } } as never);
      ao = aplicarEvento(ao, { tipo: "voto.registrado", seq: 3, dados: { "votacao-id": "vt1", "sessao-id": "s1", modalidade: "nominal", "vereador-id": BRUNO, voto: "nao" } } as never);
      ao = aplicarEvento(ao, { tipo: "votacao.encerrada", seq: 4, dados: { "votacao-id": "vt1", "sessao-id": "s1", resultado: "aprovada", modalidade: "nominal", "total-sim": 1, "total-nao": 1, "total-abstencao": 0, "base-membros": 3 } } as never);
      const bloco = () => document.querySelector(".placar-bloco")?.textContent;
      montar(ao);
      const aoVivo = bloco();
      cleanup();
      montar(hidratado());
      expect(aoVivo).toBeTruthy();
      expect(bloco()).toBe(aoVivo);
    });

    it("votação SECRETA hidratada: só o resultado e o agregado, nunca a grade nem nome", () => {
      const e = hidratarComposicao(aberta(), composicao);
      montar(hidratarVotacaoEncerrada(e, {
        votacaoId: "vt1", modalidade: "secreta", objetoTipo: "proposicao", objetoId: "p1", resultado: "rejeitada",
        totalSim: 0, totalNao: 2, totalAbstencao: 0, baseMembros: 3, votosRegistrados: 2,
      } as never, e.votacaoEventoSeq));
      expect(screen.getByRole("heading", { name: "Votação encerrada" })).toBeTruthy();
      expect(document.querySelector(".placar-resultado")?.textContent).toBe("rejeitada");
      expect(screen.queryByRole("list", { name: "Votos nominais" })).toBeNull();
      expect(document.body.textContent).not.toContain("Ana Ribeiro");
    });
  });

  // O defeito (05/10/2026): o bloco da votação ENCERRADA ainda dizia "faltam votar N" — numa votação que já
  // fechou ninguém mais vai votar; o que cabe é dizer quantos não votaram. Aberta continua "faltam votar".
  describe("a linha 'quantos votaram' do placar", () => {
    const meta = () => document.querySelector(".placar-meta")?.textContent;
    const crua = {
      votacaoId: "vt1", modalidade: "nominal", objetoTipo: "proposicao", objetoId: "p1",
      proposicao: { tipo: "projeto_lei", ano: 2026, sequencial: 22, ementa: "Energia solar" },
      resultado: "aprovada", totalSim: 1, totalNao: 1, totalAbstencao: 0, baseMembros: 3,
      votos: [{ vereadorId: "a", voto: "sim" }, { vereadorId: "b", voto: "nao" }],
    };
    const encerrada = (extra: object = {}) => {
      const e = hidratarComposicao(aberta(), composicao);
      return hidratarVotacaoEncerrada(e, { ...crua, ...extra } as never, e.votacaoEventoSeq);
    };

    it("votação ABERTA: 'faltam votar N de M'", () => {
      montar({ placar: { ...placar, baseMembros: 3, votosNominais: { a: "sim" } } });
      expect(meta()).toBe("faltam votar 2 de 3");
    });

    it("votação nominal ENCERRADA com quem não votou: 'não votaram N de M', nunca 'faltam votar'", () => {
      montar(encerrada());
      expect(meta()).toBe("não votaram 1 de 3");
      expect(document.body.textContent).not.toMatch(/faltam votar/i);
    });

    it("votação SECRETA ENCERRADA com quem não votou: 'não votaram N de M', nunca 'faltam votar'", () => {
      montar(encerrada({ modalidade: "secreta", votos: undefined, votosRegistrados: 2 }));
      expect(meta()).toBe("não votaram 1 de 3");
      expect(document.body.textContent).not.toMatch(/faltam votar/i);
    });

    it("votação ENCERRADA em que todos votaram: 'todos os M votaram' (não muda)", () => {
      montar(encerrada({ totalSim: 2, totalNao: 1 }));
      expect(meta()).toBe("todos os 3 votaram");
    });
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

describe("Telão — sem a Clara (ADR-0024, fatia 5)", () => {
  it("projetado ao público: nem botão nem moldura da Clara, mesmo aberto pela secretaria", () => {
    expect(papeisDaClara.atual).toEqual(["secretario"]);
    montar({});
    expect(screen.queryByRole("button", { name: /Clara/ })).toBeNull();
    expect(document.querySelector(".clara-moldura, .ast-lancador, .ast")).toBeNull();
  });
});
