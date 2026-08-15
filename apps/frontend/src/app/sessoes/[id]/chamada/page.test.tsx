import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import PaginaChamada from "./page";
import type { ChamadaOut, LinhaChamadaOut } from "@/lib/contrato-sessoes.gen";

// Este page.test.tsx mocka `useChamada` (não a rede) — pedido explícito da Etapa 3B fatia 3: o hook já tem
// 13 testes próprios (use-chamada.test.ts) cobrindo IO/SSE/otimista/rollback; aqui a prova é só que a
// PÁGINA lê `dados`/`canal` corretamente e aciona as 3 escritas do jeito certo por MODO (em curso vs
// registrada vs fechada). Mockar a rede reproduziria o polling/backoff do hook dentro de um teste de
// página, sem cobrir nada que use-chamada.test.ts já não cubra.

vi.mock("next/navigation", () => ({
  useParams: () => ({ id: "s1" }),
  useSearchParams: () => ({ get: () => null }),
}));

vi.mock("@/lib/auth", () => ({
  AuthProvider: ({ children }: { children: React.ReactNode }) => children,
  useAuth: () => ({ token: "tok" }),
}));

vi.mock("@/lib/tema", () => ({
  useTema: () => ({ tema: "claro", alternar: vi.fn() }),
}));

const marcarLinha = vi.fn().mockResolvedValue(undefined);
const registrarChamada = vi.fn().mockResolvedValue({ ok: true, ato: { id: "c-novo" } });
const decidirJustificativa = vi.fn().mockResolvedValue({ ok: true, decisao: {} });
const abrirJustificativa = vi.fn().mockResolvedValue({ ok: true });
const useChamadaMock = vi.fn();

vi.mock("@/lib/use-chamada", () => ({
  useChamada: (...args: unknown[]) => useChamadaMock(...args),
}));

function linha(over: Partial<LinhaChamadaOut>): LinhaChamadaOut {
  return {
    vereadorId: "v0",
    nome: "Fulano",
    nomeParlamentar: "Fulano",
    partido: "PDT",
    cargoMesa: null,
    estado: "presente-plenario",
    inconsistenciaCadastro: false,
    semAssento: false,
    desde: "2026-05-21T14:01:00Z",
    fonte: "manual_secretaria",
    registradoEm: "2026-05-21T14:01:00Z",
    justificativa: null,
    ...over,
  };
}

const vMesa = linha({ vereadorId: "v1", nome: "Sérgio Lopes", nomeParlamentar: "Sérgio Lopes", cargoMesa: "Presidente" });
const vCasa = linha({ vereadorId: "v2", nome: "Teó Nunes", nomeParlamentar: "Teó Nunes", partido: "PSB" });
const vLicenciado = linha({
  vereadorId: "v22",
  nome: "Paulo Verçosa",
  nomeParlamentar: "Paulo Verçosa",
  estado: "licenciado",
  desde: null,
  registradoEm: null,
});
const vFora = linha({ vereadorId: "v23", nome: "Cléber Souto", nomeParlamentar: "Cléber Souto", semAssento: true });
const vAusente = linha({
  vereadorId: "v24",
  nome: "Wilson Braga",
  nomeParlamentar: "Wilson Braga",
  estado: "ausente",
  desde: null,
  registradoEm: null,
  fonte: null,
});

function dadosBase(over: Partial<ChamadaOut> = {}): ChamadaOut {
  return {
    sessaoId: "s1",
    sessaoEstado: "aberta",
    instante: "2026-05-21T14:10:00Z",
    dataDeComposicao: "2026-05-21",
    composicaoResolvidaEm: "2026-05-21T14:10:00Z",
    semRegistroDePresenca: true,
    linhas: [vMesa, vCasa, vLicenciado, vFora],
    quorum: { presentesPlenario: 3, presentesRemoto: 0, presentesTotal: 3, membrosDaCasa: 3, presencasForaDoRoster: 0 },
    chamadasConduzidas: [],
    ...over,
  };
}

function mockRetorno(dados: ChamadaOut | null, extras: Partial<ReturnType<typeof useChamadaMock>> = {}) {
  useChamadaMock.mockReturnValue({
    dados,
    justificativas: [],
    estado: dados ? "pronto" : "carregando",
    canal: "ao-vivo",
    erro: null,
    recarregar: vi.fn(),
    marcarLinha,
    registrarChamada,
    decidirJustificativa,
    abrirJustificativa,
    ...extras,
  });
}

describe("PaginaChamada", () => {
  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
  });

  it("a folha renderiza as linhas agrupadas (Mesa / demais / licenciados / fora da composição)", () => {
    mockRetorno(dadosBase());
    render(<PaginaChamada />);
    expect(screen.getByRole("heading", { level: 2, name: /Mesa Diretora/ })).toBeDefined();
    expect(screen.getByRole("heading", { level: 2, name: /Demais vereadores/ })).toBeDefined();
    expect(screen.getByRole("heading", { level: 2, name: /Licenciados/ })).toBeDefined();
    expect(screen.getByRole("heading", { level: 2, name: /Fora da composição/ })).toBeDefined();
    expect(screen.getByText("Sérgio Lopes")).toBeDefined();
    expect(screen.getByText("Teó Nunes")).toBeDefined();
    expect(screen.getByText("Paulo Verçosa")).toBeDefined();
    expect(screen.getByText("Cléber Souto")).toBeDefined();
  });

  it("em curso: marcar um segmento fica PENDENTE (não chama marcarLinha) e Registrar chama registrarChamada com o que mudou", async () => {
    mockRetorno(dadosBase({ chamadasConduzidas: [] }));
    render(<PaginaChamada />);
    const grupo = screen.getByRole("group", { name: "Presença de Teó Nunes" });
    fireEvent.click(within(grupo).getByRole("button", { name: "Remoto" }));
    expect(marcarLinha).not.toHaveBeenCalled();
    expect(within(grupo).getByRole("button", { name: "Remoto" }).getAttribute("aria-pressed")).toBe("true");

    fireEvent.click(screen.getByRole("button", { name: "Registrar a chamada" }));
    await vi.waitFor(() => expect(registrarChamada).toHaveBeenCalledTimes(1));
    const enviado = registrarChamada.mock.calls[0][0];
    expect(enviado).toEqual({ v2: { estadoAlvo: "presente-remoto", desde: expect.any(String) } });
  });

  it("marcação pendente: o MAPA e a LEGENDA acompanham, o NÚMERO do servidor não se mexe", () => {
    // O defeito que este teste reprova (achado em browser, 15/08/2026): a linha dizia "presente", a
    // legenda dizia "Ausentes 3" e o número dizia "0 de 3" — a mesma tela em três verdades.
    mockRetorno(
      dadosBase({
        linhas: [vAusente, vCasa, vLicenciado],
        quorum: { presentesPlenario: 0, presentesRemoto: 0, presentesTotal: 0, membrosDaCasa: 2, presencasForaDoRoster: 0 },
      }),
    );
    render(<PaginaChamada />);
    const painel = screen.getByRole("region", { name: /Quórum/i });

    // a legenda é partida pelo <i> do marcador de cor -> ler o textContent do painel
    // vCasa (Teó) já vem presente-plenario do servidor; vAusente (Wilson) vem ausente.
    expect(painel.textContent).toMatch(/Plenário 1/);
    expect(painel.textContent).toMatch(/Ausentes 1/);
    expect(painel.textContent).not.toMatch(/a registrar/i);

    const grupo = screen.getByRole("group", { name: "Presença de Wilson Braga" });
    fireEvent.click(within(grupo).getByRole("button", { name: "Presente" }));

    // a FOLHA acompanha: Wilson sai de ausente para presente no mapa e na legenda
    expect(painel.textContent).toMatch(/Plenário 2/);
    expect(painel.textContent).toMatch(/Ausentes 0/);
    // o marcador que impede o mapa de ser lido como quórum confirmado
    expect(painel.textContent).toMatch(/a registrar/i);
    // e o NÚMERO do servidor segue intocado — a lei da Etapa 4
    expect(within(painel).getByText("0")).toBeDefined();
    expect(within(painel).getByText("2")).toBeDefined();
    expect(registrarChamada).not.toHaveBeenCalled();
    expect(marcarLinha).not.toHaveBeenCalled();
  });

  it("sem pendência nenhuma: o marcador 'a registrar' NÃO aparece", () => {
    mockRetorno(dadosBase());
    render(<PaginaChamada />);
    expect(screen.queryByText(/a registrar/i)).toBeNull();
  });

  it("em registrada: marcar um segmento chama marcarLinha na hora", () => {
    mockRetorno(
      dadosBase({
        semRegistroDePresenca: false,
        chamadasConduzidas: [{ id: "c1", conduzidaPor: "v1", membrosDaCasa: 3, ocorridoEm: "2026-05-21T14:03:00Z", registradoEm: "2026-05-21T14:03:00Z" }],
      }),
    );
    render(<PaginaChamada />);
    const grupo = screen.getByRole("group", { name: "Presença de Teó Nunes" });
    fireEvent.click(within(grupo).getByRole("button", { name: "Ausente" }));
    expect(marcarLinha).toHaveBeenCalledWith("v2", "ausente");
    expect(screen.queryByRole("button", { name: "Registrar a chamada" })).toBeNull();
    expect(screen.getByRole("button", { name: "Nova chamada" })).toBeDefined();
  });

  it("sessão encerrada: nenhum controle de marcação renderiza e o aviso de somente-leitura aparece", () => {
    mockRetorno(dadosBase({ sessaoEstado: "encerrada" }));
    render(<PaginaChamada />);
    expect(screen.queryByRole("group", { name: /Presença de/ })).toBeNull();
    expect(screen.queryByRole("button", { name: "Registrar a chamada" })).toBeNull();
    expect(screen.getByText(/não pode mais ser alterada/i)).toBeDefined();
  });

  it("linhas vazias: o convite à ação aparece, não um quadro em branco", () => {
    mockRetorno(dadosBase({ linhas: [], quorum: { presentesPlenario: 0, presentesRemoto: 0, presentesTotal: 0, membrosDaCasa: 0, presencasForaDoRoster: 0 } }));
    render(<PaginaChamada />);
    expect(screen.getByText(/Nenhum vereador com mandato vigente/)).toBeDefined();
    expect(screen.queryByRole("group", { name: /Presença de/ })).toBeNull();
  });

  it("presencasForaDoRoster > 0: o aviso fica visível (fail-loud)", () => {
    mockRetorno(dadosBase({ quorum: { presentesPlenario: 3, presentesRemoto: 0, presentesTotal: 4, membrosDaCasa: 3, presencasForaDoRoster: 1 } }));
    render(<PaginaChamada />);
    expect(screen.getAllByText(/presença\(s\) fora da composição/i).length).toBeGreaterThan(0);
  });

  it("canal reconectando: a tela não afirma 'Ao vivo'", () => {
    mockRetorno(dadosBase(), { canal: "reconectando" });
    render(<PaginaChamada />);
    expect(screen.queryByText("Ao vivo")).toBeNull();
    expect(screen.getByText("Reconectando")).toBeDefined();
  });

  // ---------- "Lançar justificativa" — Etapa 3 fatia 4 ----------

  it("ausente sem justificativa: o botão abre o formulário inline, que salva chamando abrirJustificativa", async () => {
    mockRetorno(dadosBase({ linhas: [vMesa, vCasa, vAusente] }));
    render(<PaginaChamada />);

    expect(screen.queryByLabelText("Motivo da ausência")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Lançar justificativa" }));

    const campo = screen.getByLabelText("Motivo da ausência");
    fireEvent.change(campo, { target: { value: "Missão oficial fora do município" } });
    fireEvent.click(screen.getByRole("button", { name: "Salvar" }));

    await vi.waitFor(() => expect(abrirJustificativa).toHaveBeenCalledTimes(1));
    expect(abrirJustificativa).toHaveBeenCalledWith("v24", "Missão oficial fora do município");
  });

  it("motivo só de espaços: não chama abrirJustificativa e mostra erro junto ao campo", async () => {
    mockRetorno(dadosBase({ linhas: [vMesa, vCasa, vAusente] }));
    render(<PaginaChamada />);

    fireEvent.click(screen.getByRole("button", { name: "Lançar justificativa" }));
    fireEvent.change(screen.getByLabelText("Motivo da ausência"), { target: { value: "   " } });
    fireEvent.click(screen.getByRole("button", { name: "Salvar" }));

    expect(abrirJustificativa).not.toHaveBeenCalled();
    expect(screen.getByRole("alert").textContent).toMatch(/motivo da ausência/i);
  });

  it("Cancelar fecha o formulário sem chamar abrirJustificativa", () => {
    mockRetorno(dadosBase({ linhas: [vMesa, vCasa, vAusente] }));
    render(<PaginaChamada />);

    fireEvent.click(screen.getByRole("button", { name: "Lançar justificativa" }));
    expect(screen.getByLabelText("Motivo da ausência")).toBeDefined();
    fireEvent.click(screen.getByRole("button", { name: "Cancelar" }));

    expect(screen.queryByLabelText("Motivo da ausência")).toBeNull();
    expect(abrirJustificativa).not.toHaveBeenCalled();
  });

  it("sessão encerrada: o botão 'Lançar justificativa' não aparece (registro, não formulário)", () => {
    mockRetorno(dadosBase({ sessaoEstado: "encerrada", linhas: [vMesa, vCasa, vAusente] }));
    render(<PaginaChamada />);
    expect(screen.queryByRole("button", { name: "Lançar justificativa" })).toBeNull();
  });

  it("quem lança não decide: uma linha já com justificativa (pendente ou decidida) nunca mostra 'Lançar justificativa'", () => {
    const vPendente = linha({
      vereadorId: "v25",
      nome: "Ana Pendente",
      nomeParlamentar: "Ana Pendente",
      estado: "ausente-justificativa-pendente",
      desde: null,
      registradoEm: null,
    });
    mockRetorno(
      dadosBase({ linhas: [vMesa, vCasa, vPendente] }),
      {
        justificativas: [
          { id: "j9", vereadorId: "v25", estado: "pendente", motivo: "Atestado médico", decididoPor: null, decididoEm: null, lockVersion: 1 },
        ],
      },
    );
    render(<PaginaChamada />);
    // a linha pendente mostra Deferir/Indeferir (a DECISÃO), nunca um segundo "Lançar justificativa"
    expect(screen.queryByRole("button", { name: "Lançar justificativa" })).toBeNull();
    expect(screen.getAllByRole("button", { name: "Deferir" }).length).toBeGreaterThan(0);
  });
});
