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

  it("o orçamento de IA definido pela linha de comando não passa por ato da própria câmara", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(json({
      ...FICHA,
      atuacao: [
        { id: "a3", em: "2026-10-05T10:00:01Z", acao: "ia-orcamento-definido", operador: null,
          detalhe: { origem: "linha-de-comando" }, selo: "aa11bb22cc33dd44" },
        ...FICHA.atuacao,
      ],
    })));
    render(<CamaraNoConsole />);
    expect(await screen.findByText("Orçamento de IA definido")).toBeTruthy();
    expect(screen.getByText("linha de comando da Operação")).toBeTruthy();
    expect(screen.queryByText("pela própria câmara")).toBeNull();
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

describe("Acesso da câmara (ADR-0018)", () => {
  const ATIVA = { ...CASA, estado: "ativo", "ativada-em": "2026-09-27T11:00:00Z", restricao: null, "suspensao-agendada": false };
  const FICHA_ATIVA = { casa: ATIVA, "primeiro-admin": null, "pedido-aberto": null, atuacao: [] };
  const PEDIDO = {
    id: "p1", "ente-id": "e1", "casa-nome": "Câmara Municipal de Baturité", acao: "suspender", motivo: "inadimplencia",
    justificativa: "Três faturas em aberto (processo 12/2026).", estado: "aguardando", "pedido-por-id": "op-ana",
    "pedido-por": "Ana Operação", "pedido-em": "2026-09-30T10:00:00Z", "confirmar-ate": null, "efetivado-em": null,
  };

  function porUrl(rotas: Record<string, unknown>) {
    return vi.fn(async (url: string, init?: RequestInit) => {
      const chave = `${init?.method ?? "GET"} ${url}`;
      const corpo = rotas[chave] ?? rotas[url];
      if (corpo === undefined) return json({}, 404);
      return json(corpo);
    });
  }

  it("a lista mostra a fila do 2º operador e as câmaras com acesso restrito", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(json({
      casas: [{ ...ATIVA, estado: "suspenso", restricao: { motivo: "inadimplencia", desde: "2026-09-29T10:00:00Z" } }],
      resumo: { total: 1, ativas: 0, "aguardando-admin": 0, suspensas: 1 },
      pendentes: [PEDIDO],
    })));
    render(<CamarasNaPlataforma />);
    const fila = await screen.findByRole("region", { name: "Aguardando o 2º operador" });
    expect(fila.textContent).toMatch(/Suspensão · Inadimplência · pedido por Ana Operação/);
    expect(within(fila).getByRole("link", { name: /Decidir o pedido/ }).getAttribute("href")).toBe("/operacao/casas/e1");
    expect(screen.getByText("Com acesso restrito").parentElement!.textContent).toMatch(/1/);
    expect(screen.getByRole("table").textContent).toMatch(/Suspensa.*acesso restrito/);
  });

  it("suspender: confere motivo e justificativa antes; depois pede ao console", async () => {
    const f = porUrl({ "/api/operacao/casas/e1": FICHA_ATIVA,
      "POST /api/operacao/casas/e1/suspensao": { casa: ATIVA, pedido: PEDIDO, efeito: null } });
    vi.stubGlobal("fetch", f);
    render(<CamaraNoConsole />);
    fireEvent.click(await screen.findByRole("button", { name: "Pedir suspensão" }));
    expect(await screen.findByText("Escolha o motivo.")).toBeTruthy();
    expect(f.mock.calls.some(([u]) => String(u).endsWith("/suspensao"))).toBe(false);
    fireEvent.change(screen.getByLabelText("Motivo"), { target: { value: "inadimplencia" } });
    fireEvent.change(screen.getByLabelText("Justificativa"), { target: { value: "Três faturas em aberto (processo 12/2026)." } });
    fireEvent.click(screen.getByRole("button", { name: "Pedir suspensão" }));
    expect((await screen.findByRole("status")).textContent).toMatch(/Outro operador precisa aprovar/);
    const chamada = f.mock.calls.find(([u]) => String(u).endsWith("/suspensao"))!;
    expect(JSON.parse(String(chamada[1]!.body))).toEqual({ motivo: "inadimplencia", justificativa: "Três faturas em aberto (processo 12/2026)." });
  });

  it("incidente de segurança avisa que restringe agora", async () => {
    vi.stubGlobal("fetch", porUrl({ "/api/operacao/casas/e1": FICHA_ATIVA }));
    render(<CamaraNoConsole />);
    fireEvent.change(await screen.findByLabelText("Motivo"), { target: { value: "incidente_de_seguranca" } });
    expect(screen.getByText(/outro operador confirma em até 24 h/)).toBeTruthy();
    expect(screen.getByRole("button", { name: "Restringir agora" })).toBeTruthy();
  });

  it("o 2º operador aprova ou recusa o pedido aberto", async () => {
    const f = porUrl({
      "/api/operacao/casas/e1": { ...FICHA_ATIVA, "pedido-aberto": PEDIDO },
      "/api/operacao/eu": { operador: { id: "op-beto", nome: "Beto", email: "b@x", papeis: ["operador"] } },
      "POST /api/operacao/pedidos/p1/aprovacao": { casa: { ...ATIVA, "suspensao-agendada": true }, pedido: PEDIDO, efeito: "agendado" },
    });
    vi.stubGlobal("fetch", f);
    render(<CamaraNoConsole />);
    const pedido = await screen.findByRole("group", { name: "Aguardando o 2º operador" });
    expect(pedido.textContent).toMatch(/Três faturas em aberto/);
    expect(screen.queryByRole("button", { name: "Pedir suspensão" })).toBeNull();
    fireEvent.click(await within(pedido).findByRole("button", { name: "Aprovar suspensão" }));
    expect((await screen.findByRole("status")).textContent).toMatch(/sessão plenária em curso/);
    expect(within(pedido).getByRole("button", { name: "Recusar" })).toBeTruthy();
  });

  it("quem pediu não aprova: só retira", async () => {
    vi.stubGlobal("fetch", porUrl({
      "/api/operacao/casas/e1": { ...FICHA_ATIVA, "pedido-aberto": PEDIDO },
      "/api/operacao/eu": { operador: { id: "op-ana", nome: "Ana", email: "a@x", papeis: ["operador"] } },
    }));
    render(<CamaraNoConsole />);
    expect(await screen.findByRole("button", { name: "Retirar pedido" })).toBeTruthy();
    expect(screen.queryByRole("button", { name: /Aprovar/ })).toBeNull();
  });

  it("câmara suspensa: mostra desde quando e o motivo, e reativa com justificativa", async () => {
    const suspensa = { ...ATIVA, estado: "suspenso", restricao: { motivo: "ordem_judicial", desde: "2026-09-29T10:00:00Z" } };
    const f = porUrl({
      "/api/operacao/casas/e1": { ...FICHA_ATIVA, casa: suspensa },
      "POST /api/operacao/casas/e1/reativacao": { casa: ATIVA, pedido: null, efeito: null },
    });
    vi.stubGlobal("fetch", f);
    render(<CamaraNoConsole />);
    expect(await screen.findByText(/Acesso restrito desde/)).toBeTruthy();
    expect(screen.getByText("Motivo: Ordem judicial")).toBeTruthy();
    fireEvent.change(screen.getByLabelText("Por que reativar"), { target: { value: "Decisão revogada em 30/09." } });
    fireEvent.click(screen.getByRole("button", { name: "Reativar câmara" }));
    expect((await screen.findByRole("status")).textContent).toMatch(/Câmara reativada/);
    const chamada = f.mock.calls.find(([u]) => String(u).endsWith("/reativacao"))!;
    expect(JSON.parse(String(chamada[1]!.body))).toEqual({ justificativa: "Decisão revogada em 30/09." });
  });

  it("recusa do servidor (409) aparece com o porquê", async () => {
    vi.stubGlobal("fetch", vi.fn(async (url: string, init?: RequestInit) =>
      init?.method === "POST" ? json({ erro: "ja' ha' um pedido esperando o 2o operador nesta Casa" }, 409) : json(FICHA_ATIVA)));
    render(<CamaraNoConsole />);
    fireEvent.change(await screen.findByLabelText("Motivo"), { target: { value: "pedido_da_casa" } });
    fireEvent.change(screen.getByLabelText("Justificativa"), { target: { value: "Ofício 3/2026 da Mesa." } });
    fireEvent.click(screen.getByRole("button", { name: "Pedir suspensão" }));
    expect((await screen.findByRole("alert")).textContent).toMatch(/pedido esperando o 2o operador/);
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
    expect(atividade({ estado: "suspenso", restricao: { motivo: "inadimplencia", desde: "2026-09-25T12:00:00Z" } } as never, agora))
      .toBe("acesso restrito há 2 dias");
    expect(atividade({ estado: "ativo", suspensaoAgendada: true } as never, agora)).toBe("suspensão agendada para o fim da sessão");
  });
});
