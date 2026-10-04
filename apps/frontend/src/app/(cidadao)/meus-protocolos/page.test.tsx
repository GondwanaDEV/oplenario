import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";

vi.mock("@/lib/auth", () => ({ useAuth: () => ({ token: "tok" }) }));
vi.mock("./inscricoes-audiencia", () => ({ InscricoesAudiencia: () => null }));

import PaginaMeusProtocolos from "./page";

const item = (extra: Record<string, unknown> = {}) => ({
  id: "p6", protocolo: "ESIC-2026-000006", assunto: "Folha", estado: "protocolado", "recibo-em": "2026-07-03T12:00:00Z",
  "vence-em": "2026-07-23", "dias-restantes": 20, resposta: null, anexos: [], "pode-anexar": true, ...extra,
});
const lista = (i: unknown) => ({ "pedidos-esic": [i], "solicitacoes-lgpd": [], manifestacoes: [] });

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe("/meus-protocolos — falha ao reler depois do envio", () => {
  it("F13: se o recarregar falha, a lista e o resultado do envio ficam (último estado bom) e a tela mostra o erro", async () => {
    let gets = 0;
    global.fetch = vi.fn(async (_url: string, init?: RequestInit) => {
      const json = (status: number, corpo: unknown) => ({ ok: status < 300, status, json: async () => corpo }) as Response;
      if ((init?.method ?? "GET") === "GET") {
        gets++;
        return gets === 1 ? json(200, lista(item())) : json(500, {});   // a releitura depois do envio falha
      }
      return json(201, { id: "n1", nome: "contrato.pdf", "tipo-midia": "application/pdf", bytes: 1, origem: "requerente", "enviado-em": "2026-07-03T12:01:00Z" });
    }) as unknown as typeof fetch;
    render(<PaginaMeusProtocolos />);
    const p6 = (await screen.findByText("ESIC-2026-000006")).closest("li")!;
    fireEvent.change(within(p6).getByLabelText(/Anexar arquivos ao pedido/), { target: { files: [new File(["x"], "contrato.pdf")] } });
    fireEvent.click(within(p6).getByRole("button", { name: "Enviar os arquivos" }));
    await waitFor(() => expect(gets).toBe(2));
    // o erro aparece, mas a lista NAO some e o resultado do envio continua a vista
    expect((await screen.findByRole("alert")).textContent).toMatch(/Não foi possível atualizar/);
    expect(screen.getByText("ESIC-2026-000006")).toBeTruthy();
    expect(screen.getByText("1 arquivo anexado.")).toBeTruthy();
  });

  it("sem nenhum dado ainda, a falha do primeiro carregamento mostra o erro (como antes)", async () => {
    global.fetch = vi.fn(async () => ({ ok: false, status: 500, json: async () => ({}) }) as Response) as unknown as typeof fetch;
    render(<PaginaMeusProtocolos />);
    expect((await screen.findByRole("alert")).textContent).toMatch(/Não foi possível carregar os seus protocolos/);
  });
});
