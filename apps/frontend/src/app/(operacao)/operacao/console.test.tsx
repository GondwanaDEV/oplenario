import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";

const push = vi.fn();
let params: Record<string, string | null> = {};
vi.mock("next/navigation", () => ({
  useParams: () => ({ ente: "e1" }),
  useSearchParams: () => ({ get: (k: string) => params[k] ?? null }),
  useRouter: () => ({ push }),
  usePathname: () => "/operacao",
}));
vi.mock("@/lib/auth", () => ({ useAuth: () => ({ token: null }) }));

import CamarasNaPlataforma from "./page";
import CamaraNoConsole from "./casas/[ente]/page";
import ProvisionarCamara from "./casas/nova/page";
import { atividade, haQuanto } from "./quando";

function json(corpo: unknown, status = 200) {
  return new Response(JSON.stringify(corpo), { status, headers: { "content-type": "application/json" } });
}

const CASA = {
  "ente-id": "e1", nome: "Câmara Municipal de Baturité", "nome-curto": null, uf: "CE",
  municipio: { ibge: "2302008", nome: "Baturité" }, estado: "provisionar",
  "criada-em": "2026-09-27T10:00:00Z", "convite-enviado-em": "2026-09-27T10:00:01Z", "ativada-em": null,
};

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  push.mockReset();
  params = {};
});

describe("Câmaras na plataforma", () => {
  it("lista as Casas com estado e atividade, e as métricas do registro", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(json({
      casas: [CASA, { ...CASA, "ente-id": "e2", nome: "Câmara Municipal de Sobral", municipio: { ibge: "2312908", nome: "Sobral" },
        estado: "ativo", "ativada-em": "2026-09-26T10:00:00Z" }],
      resumo: { total: 2, ativas: 1, "aguardando-admin": 1 },
    })));
    render(<CamarasNaPlataforma />);
    const linha = (await screen.findByText("Câmara Municipal de Baturité")).closest("tr")!;
    expect(within(linha).getByText("Aguardando 1º admin")).toBeTruthy();
    expect(within(linha).getByText(/convite enviado/)).toBeTruthy();
    expect(screen.getByText("Câmara Municipal de Sobral").closest("tr")!.textContent).toMatch(/Ativa/);
    expect(screen.getByText("Câmaras ativas").parentElement!.textContent).toMatch(/1/);
    expect(screen.getByRole("link", { name: "Provisionar câmara" }).getAttribute("href")).toBe("/operacao/casas/nova");
  });

  it("filtra por estado e pela busca", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(json({
      casas: [CASA, { ...CASA, "ente-id": "e2", nome: "Câmara Municipal de Sobral", municipio: { ibge: "2312908", nome: "Sobral" },
        estado: "ativo" }],
      resumo: { total: 2, ativas: 1, "aguardando-admin": 1 },
    })));
    render(<CamarasNaPlataforma />);
    await screen.findByText("Câmara Municipal de Sobral");
    fireEvent.change(screen.getByLabelText("Status"), { target: { value: "ativo" } });
    expect(screen.queryByText("Câmara Municipal de Baturité")).toBeNull();
    fireEvent.change(screen.getByLabelText("Status"), { target: { value: "todos" } });
    fireEvent.change(screen.getByPlaceholderText(/buscar câmara/i), { target: { value: "batur" } });
    expect(screen.queryByText("Câmara Municipal de Sobral")).toBeNull();
  });

  it("registro vazio convida a provisionar a primeira; sessão vencida manda entrar", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(json({ casas: [], resumo: { total: 0, ativas: 0, "aguardando-admin": 0 } })));
    render(<CamarasNaPlataforma />);
    expect(await screen.findByText(/provisione a primeira/i)).toBeTruthy();
    cleanup();
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(json({}, 401)));
    render(<CamarasNaPlataforma />);
    expect((await screen.findByRole("alert")).textContent).toMatch(/sessão do console expirou/i);
  });
});

describe("A Câmara no console", () => {
  const FICHA = {
    casa: CASA,
    "primeiro-admin": { nome: "Renata Costa", email: "renata@camara.ce.gov.br" },
    atuacao: [
      { id: "a2", em: "2026-09-27T10:00:01Z", acao: "convite-enviado", operador: "Rafaela Operação", detalhe: {}, selo: "e4c78a09aaaa0a12" },
      { id: "a1", em: "2026-09-27T10:00:00Z", acao: "casa-provisionada", operador: "Rafaela Operação", detalhe: {}, selo: "77ef00000000ffff" },
    ],
  };

  it("mostra o handoff, o 1º administrador e a atuação selada — e nada de dentro da Casa", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(json(FICHA)));
    render(<CamaraNoConsole />);
    expect(await screen.findByRole("heading", { name: "Câmara Municipal de Baturité" })).toBeTruthy();
    expect(screen.getByText("Aguardando o 1º administrador")).toBeTruthy();
    expect(screen.getByText("Renata Costa")).toBeTruthy();
    expect(screen.getByText("Convite enviado ao 1º administrador")).toBeTruthy();
    expect(screen.getByText("selo e4c7·0a12")).toBeTruthy();
    expect(screen.getByText(/Sem acesso aos dados/)).toBeTruthy();
    expect(screen.queryByRole("button", { name: /solicitar acesso/i })).toBeNull();
  });

  it("reenviar o convite chama o console e recarrega", async () => {
    const f = vi.fn().mockResolvedValueOnce(json(FICHA)).mockResolvedValueOnce(json(CASA)).mockResolvedValue(json(FICHA));
    vi.stubGlobal("fetch", f);
    render(<CamaraNoConsole />);
    fireEvent.click(await screen.findByRole("button", { name: "Reenviar convite" }));
    expect((await screen.findByRole("status")).textContent).toMatch(/Convite reenviado para renata@camara.ce.gov.br/);
    expect(f.mock.calls[1][0]).toBe("/api/operacao/casas/e1/convite");
    await waitFor(() => expect(f).toHaveBeenCalledTimes(3));
  });

  it("Casa que assumiu não oferece convite; convite que falhou no provisionamento avisa", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(json({ ...FICHA, casa: { ...CASA, estado: "ativo", "ativada-em": "2026-09-27T11:00:00Z" } })));
    render(<CamaraNoConsole />);
    expect(await screen.findByText("A Casa assumiu")).toBeTruthy();
    expect(screen.queryByRole("button", { name: /convite/i })).toBeNull();
    cleanup();
    params = { provisionada: "falhou" };
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(json({ ...FICHA, casa: { ...CASA, "convite-enviado-em": null } })));
    render(<CamaraNoConsole />);
    expect((await screen.findByRole("alert")).textContent).toMatch(/o convite não saiu/i);
    expect(screen.getByRole("button", { name: "Enviar convite" })).toBeTruthy();
  });
});

describe("Provisionar câmara", () => {
  function preencher() {
    fireEvent.change(screen.getByLabelText("Nome oficial"), { target: { value: "Câmara Municipal de Baturité" } });
    fireEvent.change(screen.getByLabelText("UF"), { target: { value: "CE" } });
    fireEvent.change(screen.getByLabelText("Município"), { target: { value: "Baturité" } });
    fireEvent.change(screen.getByLabelText("Código IBGE do município"), { target: { value: "2302008" } });
    fireEvent.change(screen.getByLabelText("Nome completo"), { target: { value: "Renata Costa" } });
    fireEvent.change(screen.getByLabelText("CPF"), { target: { value: "529.982.247-25" } });
    fireEvent.change(screen.getByLabelText("E-mail institucional"), { target: { value: "renata@camara.ce.gov.br" } });
  }

  it("confere antes de enviar: nada vai ao servidor com CPF errado", async () => {
    const f = vi.fn();
    vi.stubGlobal("fetch", f);
    render(<ProvisionarCamara />);
    preencher();
    fireEvent.change(screen.getByLabelText("CPF"), { target: { value: "529.982.247-24" } });
    fireEvent.click(screen.getByRole("button", { name: "Provisionar e convidar" }));
    expect(await screen.findByText(/Este CPF não confere/)).toBeTruthy();
    expect(screen.getByLabelText("CPF").getAttribute("aria-invalid")).toBe("true");
    expect(f).not.toHaveBeenCalled();
  });

  it("provisiona e leva à ficha da Casa dizendo se o convite saiu", async () => {
    const f = vi.fn().mockResolvedValue(json({ casa: CASA, convite: "enviado" }, 201));
    vi.stubGlobal("fetch", f);
    render(<ProvisionarCamara />);
    preencher();
    fireEvent.click(screen.getByRole("button", { name: "Provisionar e convidar" }));
    await waitFor(() => expect(push).toHaveBeenCalledWith("/operacao/casas/e1?provisionada=enviado"));
    const corpo = JSON.parse(f.mock.calls[0][1].body);
    expect(corpo.admin.cpf).toBe("52998224725");
  });

  it("recusa do servidor aparece no formulário", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(json({ erro: "requisicao invalida" }, 400)));
    render(<ProvisionarCamara />);
    preencher();
    fireEvent.click(screen.getByRole("button", { name: "Provisionar e convidar" }));
    expect((await screen.findByRole("alert")).textContent).toMatch(/não passou na conferência do servidor/);
    expect(push).not.toHaveBeenCalled();
  });
});

describe("datas do console", () => {
  it("relativas em português", () => {
    const agora = new Date("2026-09-27T12:00:00Z");
    expect(haQuanto("2026-09-27T11:58:00Z", agora)).toBe("há 2 min");
    expect(haQuanto("2026-09-25T12:00:00Z", agora)).toBe("há 2 dias");
    expect(atividade({ estado: "provisionar", conviteEnviadoEm: null } as never, agora)).toBe("convite não saiu");
  });
});
