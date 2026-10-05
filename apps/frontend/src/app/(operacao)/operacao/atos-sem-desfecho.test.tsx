import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor, within } from "@testing-library/react";

vi.mock("next/navigation", () => ({
  useParams: () => ({}),
  useSearchParams: () => ({ get: () => null }),
  useRouter: () => ({ push: vi.fn() }),
  usePathname: () => "/operacao",
}));
vi.mock("@/lib/auth", () => ({ useAuth: () => ({ token: null }) }));

import CamarasNaPlataforma from "./page";

function json(corpo: unknown, status = 200) {
  return new Response(JSON.stringify(corpo), { status, headers: { "content-type": "application/json" } });
}

const CAMARAS = { casas: [], resumo: { total: 0, ativas: 0, "aguardando-admin": 0 } };

const ENTE = "7d1c6a3e-3f0e-4b6e-9a53-0e6a3d6f2b11";

const DO_COMANDO = {
  id: "a1", em: "2026-10-05T12:31:00Z", acao: "ia-orcamento-iniciado", operador: null, origem: "linha-de-comando",
  "ente-id": ENTE, "casa-nome": "Câmara Municipal de Baturité",
};
const DA_ENTRADA = {
  id: "a2", em: "2026-10-05T02:30:00Z", acao: "entrada-no-console-iniciada", operador: "Rafaela Operação", origem: null,
  "ente-id": null, "casa-nome": null,
};

function pagina(atos: unknown, status = 200) {
  vi.stubGlobal("fetch", vi.fn(async (url: string) =>
    String(url).endsWith("/atos-sem-desfecho") ? json(atos, status) : json(CAMARAS)));
}

const TOLERANCIA = { "tolerancia-segundos": 120, limite: 50 };

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe("Atos da Operação iniciados sem desfecho registrado (console)", () => {
  it("diz quantos são e mostra cada ato em palavras, com quem, a Câmara pelo nome e o horário de Fortaleza", async () => {
    pagina({ ...TOLERANCIA, total: 2, truncado: false, atos: [DO_COMANDO, DA_ENTRADA] });
    render(<CamarasNaPlataforma />);
    const bloco = await screen.findByRole("region", { name: "2 atos iniciados sem desfecho registrado" });
    const itens = within(bloco).getAllByRole("listitem");
    expect(itens).toHaveLength(2);

    // o ato do comando: sem pessoa, e a Câmara pelo nome; o horário é o de Fortaleza (12:31 UTC = 09:31)
    expect(itens[0].textContent).toMatch(/Orçamento de IA: definição iniciada/);
    expect(itens[0].textContent).toMatch(/Câmara Municipal de Baturité/);
    expect(itens[0].textContent).toMatch(/linha de comando da Operação/);
    expect(itens[0].textContent).toMatch(/05\/10\/2026 09:31/);
    expect(within(itens[0]).getByRole("link", { name: "Abrir Câmara Municipal de Baturité" }).getAttribute("href"))
      .toBe(`/operacao/casas/${ENTE}`);

    // a entrada no console: quem tentou, sem Câmara e sem link; 02:30 UTC ainda é o dia 4 em Fortaleza
    expect(itens[1].textContent).toMatch(/Entrada no console: iniciada/);
    expect(itens[1].textContent).toMatch(/Rafaela Operação/);
    expect(itens[1].textContent).toMatch(/04\/10\/2026 23:30/);
    expect(within(itens[1]).queryByRole("link")).toBeNull();

    // a frase da trilha da Casa, em cada ato
    for (const item of itens) expect(item.textContent).toMatch(/Confira se o ato aconteceu\./);
  });

  it("nunca mostra UUID nem chave de ação na tela", async () => {
    pagina({ ...TOLERANCIA, total: 2, truncado: false, atos: [DO_COMANDO, DA_ENTRADA] });
    render(<CamarasNaPlataforma />);
    const bloco = await screen.findByRole("region", { name: /atos iniciados sem desfecho/ });
    expect(bloco.textContent).not.toMatch(/[0-9a-f]{8}-[0-9a-f]{4}-/i);
    expect(bloco.textContent).not.toMatch(/-iniciad[oa]\b|linha-de-comando/);
  });

  it("é só leitura: nenhum botão, e a Câmara sem nome no registro não vira UUID", async () => {
    pagina({ ...TOLERANCIA, total: 1, truncado: false, atos: [{ ...DO_COMANDO, "casa-nome": null }] });
    render(<CamarasNaPlataforma />);
    const bloco = await screen.findByRole("region", { name: "1 ato iniciado sem desfecho registrado" });
    expect(within(bloco).queryAllByRole("button")).toHaveLength(0);
    expect(bloco.textContent).toMatch(/Câmara fora do registro/);
    expect(bloco.textContent).not.toContain(ENTE);
  });

  it("a lista cortada diz que foi cortada e quanto há no total", async () => {
    pagina({ ...TOLERANCIA, total: 83, truncado: true, atos: [DO_COMANDO, DA_ENTRADA] });
    render(<CamarasNaPlataforma />);
    const bloco = await screen.findByRole("region", { name: "83 atos iniciados sem desfecho registrado" });
    expect(bloco.textContent).toMatch(/Mostrando os 2 mais recentes de 83\./);
  });

  it("diz a janela de tolerância que o servidor usou", async () => {
    pagina({ ...TOLERANCIA, total: 1, truncado: false, atos: [DO_COMANDO] });
    render(<CamarasNaPlataforma />);
    const bloco = await screen.findByRole("region", { name: /ato iniciado/ });
    expect(bloco.textContent).toMatch(/iniciados há mais de 2 minutos/);
  });

  it("sem nenhum ato, o bloco não existe: nada de cartão 'tudo certo'", async () => {
    pagina({ ...TOLERANCIA, total: 0, truncado: false, atos: [] });
    render(<CamarasNaPlataforma />);
    await screen.findByText(/Nenhuma câmara no registro ainda/);
    // a leitura dos atos já voltou (a página inteira esperou o registro de Câmaras, que veio junto)
    await waitFor(() => expect((fetch as unknown as ReturnType<typeof vi.fn>).mock.calls.length).toBe(2));
    expect(screen.queryByRole("region", { name: /sem desfecho/ })).toBeNull();
    expect(screen.queryByText(/sem desfecho|tudo certo|em dia/i)).toBeNull();
    expect(screen.queryByRole("status")).toBeNull();
  });

  it("a conferência que não rodou não passa por conferência sem achados", async () => {
    pagina({ erro: "boom" }, 500);
    render(<CamarasNaPlataforma />);
    expect((await screen.findByRole("status")).textContent).toMatch(/Não deu para conferir agora se há atos da Operação sem desfecho/);
    expect(screen.queryByRole("region", { name: /sem desfecho/ })).toBeNull();
  });

  it("resposta fora da forma também é erro, nunca 'nenhum ato'", async () => {
    pagina({ casas: [] });
    render(<CamarasNaPlataforma />);
    expect((await screen.findByRole("status")).textContent).toMatch(/Não deu para conferir agora/);
  });

  it("sessão do console vencida: só o aviso da página, sem bloco nem segundo alerta", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => json({}, 401)));
    render(<CamarasNaPlataforma />);
    expect((await screen.findByRole("alert")).textContent).toMatch(/sessão do console expirou/i);
    await waitFor(() => expect((fetch as unknown as ReturnType<typeof vi.fn>).mock.calls.length).toBe(2));
    expect(screen.getAllByRole("alert")).toHaveLength(1);
    expect(screen.queryByRole("status")).toBeNull();
    expect(screen.queryByRole("region", { name: /sem desfecho/ })).toBeNull();
  });
});
