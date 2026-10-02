import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";

// ADR-0018 (fatia 2) — o encerramento na ficha do console: as etapas em ordem, o operador vendo SÓ metadado da
// exportação (nunca um botão de baixar), o ofício, a guarda com a data, o pedido de apagamento (two-person) e o
// retomar, e a câmara encerrada com o resumo do apagamento.

vi.mock("next/navigation", () => ({
  useParams: () => ({ ente: "e1" }),
  useSearchParams: () => ({ get: () => null }),
  useRouter: () => ({ push: vi.fn() }),
  usePathname: () => "/operacao/casas/e1",
}));
vi.mock("@/lib/auth", () => ({ useAuth: () => ({ token: null }) }));

import CamaraNoConsole from "./page";

function json(corpo: unknown, status = 200) {
  return new Response(JSON.stringify(corpo), { status, headers: { "content-type": "application/json" } });
}

const SHA = "a1b2c3d4".repeat(8);

const CASA = {
  "ente-id": "e1", nome: "Câmara Municipal de Baturité", "nome-curto": null, uf: "CE",
  municipio: { ibge: "2302008", nome: "Baturité" }, estado: "suspenso", "criada-em": "2026-01-10T10:00:00Z",
  "convite-enviado-em": "2026-01-10T10:00:01Z", "ativada-em": "2026-01-11T10:00:00Z",
  restricao: { motivo: "encerramento_em_curso", desde: "2026-10-01T12:00:00Z" }, "suspensao-agendada": false,
  "encerrada-em": null, "destino-acervo-url": null,
};

const EXPORTACAO = {
  id: "x1", estado: "pronta", "solicitada-em": "2026-10-01T13:00:00Z", "solicitada-por": "operador",
  "concluida-em": "2026-10-01T13:20:00Z", sha256: SHA, bytes: 13_000_000,
  manifesto: { versao: "1", tabelas: { itens: 42 } }, erro: null, "confirmada-em": null, "confirmada-por": null, oficio: null,
};

const ENCERRAMENTO = {
  "em-curso": true, desde: "2026-10-01T12:00:00Z", exportacoes: [EXPORTACAO], confirmacao: null,
  "apagamento-possivel-em": null, "pode-pedir-apagamento": false, "exportacao-disponivel": true,
  "apagamento-disponivel": true, "apagamento-pendente": null, "destino-acervo-url": null, "encerrada-em": null,
  apagamento: null,
};

const ficha = (enc: unknown, casa: unknown = CASA, pedido: unknown = null) =>
  ({ casa, "primeiro-admin": null, "pedido-aberto": pedido, encerramento: enc, atuacao: [] });

function porUrl(rotas: Record<string, unknown>) {
  return vi.fn(async (url: string, init?: RequestInit) => {
    const chave = `${init?.method ?? "GET"} ${url}`;
    const corpo = rotas[chave] ?? rotas[url];
    if (corpo === undefined) return json({}, 404);
    if (corpo instanceof Response) return corpo;
    return json(corpo);
  });
}

const etapa = (titulo: string) => screen.getByRole("heading", { name: titulo, level: 3 }).closest("li")!;

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe("Encerramento no console (ADR-0018 fatia 2)", () => {
  it("as etapas em ordem, com a exportação como metadado — sem botão de baixar", async () => {
    vi.stubGlobal("fetch", porUrl({ "/api/operacao/casas/e1": ficha(ENCERRAMENTO), "/api/operacao/eu": { operador: { id: "op-ana" } } }));
    render(<CamaraNoConsole />);
    const titulos = (await screen.findAllByRole("heading", { level: 3 })).map((h) => h.textContent);
    expect(titulos).toEqual([
      "Exportação completa", "Confirmação de recebimento", "Guarda de 90 dias", "Destino do acervo público",
      "Apagamento dos dados", "Câmara encerrada",
    ]);
    expect(etapa("Exportação completa").textContent).toMatch(/Concluída/);
    expect(etapa("Confirmação de recebimento").getAttribute("aria-current")).toBe("step");
    expect(etapa("Exportação completa").textContent).toMatch(/12,4 MB/);
    expect(etapa("Exportação completa").textContent).toMatch(/a1b2c3d4 a1b2c3d4/);
    expect(etapa("Exportação completa").textContent).toMatch(/tabelas: 42 itens/);
    expect(screen.getByText(/nunca o conteúdo/)).toBeTruthy();
    expect(screen.queryByRole("link", { name: /baixar/i })).toBeNull();
    expect(screen.queryByRole("button", { name: /baixar/i })).toBeNull();
    expect(etapa("Apagamento dos dados").textContent).toMatch(/Só depois que a câmara confirmar/);
  });

  it("registra a confirmação por ofício: confere o texto e manda ao console", async () => {
    const f = porUrl({
      "/api/operacao/casas/e1": ficha(ENCERRAMENTO),
      "POST /api/operacao/exportacoes/x1/oficio": { ...EXPORTACAO, "confirmada-em": "2026-10-02T10:00:00Z", "confirmada-por": "oficio" },
    });
    vi.stubGlobal("fetch", f);
    render(<CamaraNoConsole />);
    const campo = await screen.findByLabelText(/Ofício de recebimento/);
    fireEvent.click(screen.getByRole("button", { name: "Registrar ofício" }));
    expect(await screen.findByText(/Escreva o número, a data/)).toBeTruthy();
    expect(f.mock.calls.some(([u]) => String(u).includes("/oficio"))).toBe(false);
    fireEvent.change(campo, { target: { value: "Ofício 12/2026 da Mesa Diretora, de 02/10/2026." } });
    fireEvent.click(screen.getByRole("button", { name: "Registrar ofício" }));
    expect((await screen.findByRole("status")).textContent).toMatch(/guarda de 90 dias começou/);
    const chamada = f.mock.calls.find(([u]) => String(u).includes("/oficio"))!;
    expect(JSON.parse(String((chamada[1] as RequestInit).body))).toEqual({ texto: "Ofício 12/2026 da Mesa Diretora, de 02/10/2026." });
  });

  it("guarda em curso: mostra a partir de quando o apagamento é possível, sem o botão de pedir", async () => {
    const enc = {
      ...ENCERRAMENTO,
      exportacoes: [{ ...EXPORTACAO, "confirmada-em": "2026-10-02T10:00:00Z", "confirmada-por": "admin_ente" }],
      confirmacao: { ...EXPORTACAO, "confirmada-em": "2026-10-02T10:00:00Z", "confirmada-por": "admin_ente" },
      "apagamento-possivel-em": "2099-12-31T10:00:00Z",
    };
    vi.stubGlobal("fetch", porUrl({ "/api/operacao/casas/e1": ficha(enc) }));
    render(<CamaraNoConsole />);
    expect((await screen.findByText(/Confirmada pelo administrador da câmara/)).textContent).toMatch(/não se desfaz/);
    expect(etapa("Guarda de 90 dias").textContent).toMatch(/Apagamento possível a partir de 31\/12\/2099/);
    expect(etapa("Guarda de 90 dias").getAttribute("aria-current")).toBe("step");
    expect(screen.queryByRole("button", { name: "Pedir apagamento" })).toBeNull();
  });

  it("depois da guarda: pede o apagamento com justificativa; o pedido aberto vai para a etapa", async () => {
    const enc = {
      ...ENCERRAMENTO,
      confirmacao: { ...EXPORTACAO, "confirmada-em": "2026-01-02T10:00:00Z", "confirmada-por": "admin_ente" },
      "apagamento-possivel-em": "2026-04-02T10:00:00Z", "pode-pedir-apagamento": true,
    };
    const f = porUrl({
      "/api/operacao/casas/e1": ficha(enc),
      "POST /api/operacao/casas/e1/apagamento": { casa: CASA, pedido: null, efeito: null },
    });
    vi.stubGlobal("fetch", f);
    render(<CamaraNoConsole />);
    const botao = await screen.findByRole("button", { name: "Pedir apagamento" });
    fireEvent.click(botao);
    expect(await screen.findByText(/Escreva a justificativa/)).toBeTruthy();
    fireEvent.change(screen.getByLabelText("Justificativa do apagamento"), { target: { value: "Guarda de 90 dias cumprida." } });
    fireEvent.click(botao);
    expect((await screen.findByRole("status")).textContent).toMatch(/Outro operador precisa aprovar/);
  });

  it("o pedido de apagamento de outro operador: aprovar avisa que não tem volta e diz se encerrou", async () => {
    const pedido = {
      id: "p9", "ente-id": "e1", "casa-nome": "Câmara Municipal de Baturité", acao: "apagar", motivo: "fim_da_guarda",
      justificativa: "Guarda de 90 dias cumprida.", estado: "aguardando", "pedido-por-id": "op-ana", "pedido-por": "Ana Operação",
      "pedido-em": "2026-10-02T10:00:00Z", "confirmar-ate": null, "efetivado-em": null,
    };
    const f = porUrl({
      "/api/operacao/casas/e1": ficha({ ...ENCERRAMENTO, "pode-pedir-apagamento": true }, CASA, pedido),
      "/api/operacao/eu": { operador: { id: "op-beto", nome: "Beto", email: "b@x", papeis: ["operador"] } },
      "POST /api/operacao/pedidos/p9/aprovacao": { casa: { ...CASA, estado: "encerrado" }, pedido, efeito: "encerrada" },
    });
    vi.stubGlobal("fetch", f);
    render(<CamaraNoConsole />);
    await screen.findByRole("heading", { name: "Apagamento dos dados", level: 3 });
    const apagar = etapa("Apagamento dos dados");
    expect(await within(apagar).findByText(/Isto não tem volta/)).toBeTruthy();
    expect(within(apagar).getByText(/Apagamento · Fim da guarda de 90 dias · pedido por Ana Operação/)).toBeTruthy();
    // o pedido de apagamento aparece só na etapa, não em "Acesso da câmara"
    expect(screen.getAllByText(/Aguardando o 2º operador/)).toHaveLength(1);
    fireEvent.click(await within(apagar).findByRole("button", { name: "Aprovar e apagar os dados" }));
    expect((await screen.findByRole("status")).textContent).toMatch(/Dados apagados. A câmara está encerrada/);
  });

  it("o apagamento que parou no meio: retomar; se parar de novo, diz por quê", async () => {
    const pendente = {
      id: "p9", "ente-id": "e1", "casa-nome": null, acao: "apagar", motivo: "fim_da_guarda", justificativa: "Guarda cumprida.",
      estado: "aprovado", "pedido-por-id": "op-ana", "pedido-por": "Ana", "pedido-em": "2026-10-02T10:00:00Z",
      "confirmar-ate": null, "efetivado-em": null,
    };
    const f = porUrl({
      "/api/operacao/casas/e1": ficha({ ...ENCERRAMENTO, "apagamento-pendente": pendente }),
      "POST /api/operacao/casas/e1/apagamento/retomada": { casa: CASA, pedido: pendente, efeito: "apagamento-interrompido", erro: "o Keycloak nao respondeu" },
    });
    vi.stubGlobal("fetch", f);
    render(<CamaraNoConsole />);
    expect(await screen.findByText(/foi aprovado e parou no meio/)).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Retomar o apagamento" }));
    expect((await screen.findByRole("alert")).textContent).toMatch(/parou de novo \(o Keycloak nao respondeu\)/);
  });

  it("a câmara encerrada: todas as etapas concluídas e o resumo do apagamento como prova", async () => {
    const encerrada = { ...CASA, estado: "encerrado", restricao: null, "encerrada-em": "2027-01-05T15:00:00Z",
      "destino-acervo-url": "https://camarabaturite.ce.gov.br/acervo" };
    const enc = {
      ...ENCERRAMENTO, "em-curso": false, confirmacao: { ...EXPORTACAO, "confirmada-em": "2026-10-02T10:00:00Z", "confirmada-por": "oficio", oficio: "Ofício 12/2026." },
      "encerrada-em": "2027-01-05T15:00:00Z", "destino-acervo-url": "https://camarabaturite.ce.gov.br/acervo",
      apagamento: {
        tabelas: { "legislativo.proposicao": 1200, "sessoes.sessao": 80 }, "linhas-total": 1280, objetos: 340, "realm-apagado?": true,
        exportacao: { id: "x1", sha256: SHA, bytes: 13000000, "confirmada-em": "2026-10-02T10:00:00Z", "confirmada-por": "oficio" },
      },
    };
    vi.stubGlobal("fetch", porUrl({ "/api/operacao/casas/e1": ficha(enc, encerrada) }));
    render(<CamaraNoConsole />);
    await screen.findByRole("heading", { name: "Encerramento" });
    for (const li of screen.getAllByRole("listitem").filter((l) => l.classList.contains("op-etapa"))) {
      expect(li.textContent).toMatch(/Concluída/);
    }
    const fim = etapa("Câmara encerrada");
    expect(fim.textContent).toMatch(/Encerrada em 05\/01\/2027/);
    expect(fim.textContent).toMatch(/1\.280 em 2 tabelas/);
    expect(fim.textContent).toMatch(/340/);
    expect(fim.textContent).toMatch(/confirmada em 02\/10\/2026 por ofício/);
    expect(within(fim).getByRole("link", { name: "https://camarabaturite.ce.gov.br/acervo" })).toBeTruthy();
    expect(screen.queryByRole("heading", { name: "Acesso da câmara" })).toBeNull();
    await waitFor(() => expect(within(fim).getByText("Tabelas apagadas (2)")).toBeTruthy());
  });

  it("câmara sem encerramento que exportou por conta própria: só o histórico, sem etapas", async () => {
    const ativa = { ...CASA, estado: "ativo", restricao: null };
    vi.stubGlobal("fetch", porUrl({ "/api/operacao/casas/e1": ficha({ ...ENCERRAMENTO, "em-curso": false }, ativa) }));
    render(<CamaraNoConsole />);
    expect(await screen.findByRole("heading", { name: "Exportações da câmara" })).toBeTruthy();
    expect(screen.queryByRole("heading", { name: "Exportação completa", level: 3 })).toBeNull();
    expect(screen.getByText(/pela Operação/)).toBeTruthy();
  });
});
