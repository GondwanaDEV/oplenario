import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import PaginaPlenario from "./page";
import type { SessaoOut } from "@/lib/contrato";
import { TemaProvider } from "@/lib/tema";
import { estadoInicial, type EstadoPlenario, type PlacarVotacao } from "@/lib/plenario-reducer";

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
