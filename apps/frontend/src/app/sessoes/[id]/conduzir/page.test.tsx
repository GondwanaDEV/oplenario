import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import PaginaConduzir from "./page";
import type { SessaoOut } from "@/lib/contrato-sessoes.gen";

// Mocka os hooks (não a rede): `use-conducao-sessao.test.ts` já cobre IO/CAS/409, e `conducao-sessao-vista`
// cobre o grafo. Aqui a prova é só que a PÁGINA lê o estado, oferece os atos certos e os dispara do jeito
// certo por tom (direto / confirmação / motivo).

vi.mock("next/navigation", () => ({
  useParams: () => ({ id: "s1" }),
  useSearchParams: () => ({ get: () => null }),
}));
vi.mock("@/lib/auth", () => ({
  AuthProvider: ({ children }: { children: React.ReactNode }) => children,
  useAuth: () => ({ token: "tok" }),
}));
vi.mock("@/lib/tema", () => ({ useTema: () => ({ tema: "claro", alternar: vi.fn() }) }));
vi.mock("@/lib/use-pauta", () => ({ usePauta: () => ({ pauta: null, estado: "erro" }) }));
// O painel de votação tem testes próprios (painel-votacao.test.tsx); aqui só evitamos o IO real dele
// quando a sessão está aberta (a página o monta nesse estado).
vi.mock("@/lib/use-votacao-mesa", () => ({
  useVotacaoMesa: () => ({ votacaoAberta: null, itens: [], estado: "pronto", erro: null, recarregar: vi.fn(), abrir: vi.fn(), encerrar: vi.fn() }),
}));
vi.mock("@/lib/use-tribuna-mesa", () => ({
  useTribunaMesa: () => ({ tribuna: null, composicao: null, estado: "pronto", erro: null, recarregar: vi.fn(), inscrever: vi.fn(), desistir: vi.fn() }),
}));

const transicionar = vi.fn().mockResolvedValue({ ok: true, sessao: {} });
const recarregar = vi.fn().mockResolvedValue(undefined);
const useConducaoMock = vi.fn();
vi.mock("@/lib/use-conducao-sessao", () => ({
  useConducaoSessao: (...a: unknown[]) => useConducaoMock(...a),
}));

function sessao(estado: SessaoOut["estado"], over: Partial<SessaoOut> = {}): SessaoOut {
  return {
    id: "s1",
    sessaoLegislativaId: "sl1",
    tipoSessao: "ordinaria",
    numeroSequencial: 14,
    estado,
    modalidade: "presencial",
    delibera: true,
    transmitePublica: true,
    geraAtaRegimental: true,
    permiteVotoSecreto: false,
    permiteModalidadeRemota: false,
    lockVersion: 2,
    ...over,
  };
}

function montar(estado: SessaoOut["estado"], over: Partial<SessaoOut> = {}) {
  useConducaoMock.mockReturnValue({
    sessao: sessao(estado, over),
    estado: "pronto",
    erro: null,
    transicionar,
    recarregar,
  });
  return render(<PaginaConduzir />);
}

afterEach(() => {
  cleanup();
  transicionar.mockClear();
  recarregar.mockClear();
});

describe("Comando da Mesa — rende o estado e os atos permitidos", () => {
  it("mostra o estado atual e o número da sessão", () => {
    montar("aberta");
    expect(screen.getAllByText(/Sessão ordinária nº 14/i).length).toBeGreaterThan(0);
    expect(screen.getByText("Aberta")).toBeTruthy();
  });

  it("agendada oferece Abrir e Marcar como não realizada", () => {
    montar("agendada", { agendadaPara: "2026-05-21T14:00:00Z" });
    expect(screen.getByRole("button", { name: /^Abrir a sessão$/ })).toBeTruthy();
    expect(screen.getByRole("button", { name: /Marcar como não realizada/ })).toBeTruthy();
  });

  it("estado terminal (arquivada) não oferece atos", () => {
    montar("arquivada");
    expect(screen.queryByRole("button", { name: /Abrir|Encerrar|Suspender/ })).toBeNull();
    expect(screen.getByText(/não há mais atos de condução/i)).toBeTruthy();
  });
});

describe("Comando da Mesa — disparo dos atos", () => {
  it("ato direto (abrir) dispara transicionar na hora, sem confirmação", async () => {
    montar("agendada");
    fireEvent.click(screen.getByRole("button", { name: /^Abrir a sessão$/ }));
    await waitFor(() => expect(transicionar).toHaveBeenCalledWith("aberta", undefined));
  });

  it("encerrar pede confirmação antes de disparar", async () => {
    montar("aberta");
    fireEvent.click(screen.getByRole("button", { name: /^Encerrar a sessão$/ }));
    // ainda não disparou — abriu o painel de confirmação
    expect(transicionar).not.toHaveBeenCalled();
    expect(screen.getByText(/Confirmar o encerramento/i)).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: /Confirmar: Encerrar a sessão/ }));
    await waitFor(() => expect(transicionar).toHaveBeenCalledWith("encerrada", undefined));
  });

  it("marcar não realizada exige motivo — vazio bloqueia, preenchido dispara com o motivo", async () => {
    montar("agendada");
    fireEvent.click(screen.getByRole("button", { name: /Marcar como não realizada/ }));
    // confirmar sem motivo: bloqueia
    fireEvent.click(screen.getByRole("button", { name: /Confirmar: Marcar como não realizada/ }));
    await screen.findByText(/não pode ficar em branco/i);
    expect(transicionar).not.toHaveBeenCalled();
    // preenche e dispara
    fireEvent.change(screen.getByLabelText("Motivo"), { target: { value: "falta de quórum" } });
    fireEvent.click(screen.getByRole("button", { name: /Confirmar: Marcar como não realizada/ }));
    await waitFor(() => expect(transicionar).toHaveBeenCalledWith("nao_realizada", "falta de quórum"));
  });

  it("botão Atualizar estado chama recarregar", () => {
    montar("aberta");
    fireEvent.click(screen.getByRole("button", { name: /Atualizar estado/ }));
    expect(recarregar).toHaveBeenCalled();
  });
});
