import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { ParecerNoPortal } from "./parecer-no-portal";

// ADR-0019 fatia 2a (Eixo 4): o bloco "Parecer jurídico no portal" da administração.
const URL = "/api/legislativo/parametros-parecer-juridico";

function mockar(inicial: boolean, put: { status?: number } = {}) {
  let atual = inicial;
  const chamadas: { metodo: string; url: string; body: unknown }[] = [];
  global.fetch = vi.fn(async (url: string, init?: RequestInit) => {
    const metodo = init?.method ?? "GET";
    const body = init?.body ? JSON.parse(String(init.body)) : undefined;
    chamadas.push({ metodo, url: String(url), body });
    if (String(url) !== URL) return { ok: false, status: 404, json: async () => ({}) } as Response;
    if (metodo === "PUT") {
      const status = put.status ?? 200;
      if (status < 300) atual = body["publicar-ao-assinar"];
      return { ok: status < 300, status, json: async () => (status < 300 ? { "publicar-ao-assinar": atual } : {}) } as Response;
    }
    return { ok: true, status: 200, json: async () => ({ "publicar-ao-assinar": atual }) } as Response;
  }) as unknown as typeof fetch;
  return chamadas;
}

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe("Parecer jurídico no portal", () => {
  it("padrão: só depois da deliberação; diz que a consulta avulsa nunca vai ao portal; salvar começa desabilitado", async () => {
    mockar(false);
    render(<ParecerNoPortal token="tk" />);
    expect(await screen.findByRole("heading", { name: "Parecer jurídico no portal" })).toBeTruthy();
    expect((await screen.findByRole("radio", { name: /Só depois da deliberação/ }) as HTMLInputElement).checked).toBe(true);
    expect((screen.getByRole("radio", { name: /Assim que o jurídico assinar/ }) as HTMLInputElement).checked).toBe(false);
    expect(screen.getByText(/A consulta avulsa nunca vai ao portal/)).toBeTruthy();
    expect(screen.getByText(/só aparece no portal depois que a matéria é deliberada/)).toBeTruthy();
    expect((screen.getByRole("button", { name: "Salvar" }) as HTMLButtonElement).disabled).toBe(true);
  });

  it("antecipar: escolhe 'ao assinar', salva com a chave do fio e passa a dizer isso", async () => {
    const c = mockar(false);
    render(<ParecerNoPortal token="tk" />);
    fireEvent.click(await screen.findByRole("radio", { name: /Assim que o jurídico assinar/ }));
    fireEvent.click(screen.getByRole("button", { name: "Salvar" }));
    expect((await screen.findByRole("status")).textContent).toMatch(/Configuração salva/);
    const put = c.find((x) => x.metodo === "PUT")!;
    expect(put.body).toEqual({ "publicar-ao-assinar": true });
    expect(screen.getByText(/aparece no portal assim que o jurídico o assina/)).toBeTruthy();
    expect((screen.getByRole("radio", { name: /Assim que o jurídico assinar/ }) as HTMLInputElement).checked).toBe(true);
    expect((screen.getByRole("button", { name: "Salvar" }) as HTMLButtonElement).disabled).toBe(true);
  });

  it("voltar ao padrão também salva", async () => {
    const c = mockar(true);
    render(<ParecerNoPortal token="tk" />);
    fireEvent.click(await screen.findByRole("radio", { name: /Só depois da deliberação/ }));
    fireEvent.click(screen.getByRole("button", { name: "Salvar" }));
    await waitFor(() => expect(c.some((x) => x.metodo === "PUT")).toBe(true));
    expect(c.find((x) => x.metodo === "PUT")!.body).toEqual({ "publicar-ao-assinar": false });
  });

  it("erro do servidor ao salvar: alerta e o estado salvo não muda", async () => {
    mockar(false, { status: 403 });
    render(<ParecerNoPortal token="tk" />);
    fireEvent.click(await screen.findByRole("radio", { name: /Assim que o jurídico assinar/ }));
    fireEvent.click(screen.getByRole("button", { name: "Salvar" }));
    expect((await screen.findByRole("alert")).textContent).toMatch(/administrador da Casa/);
    expect(screen.getByText(/só aparece no portal depois que a matéria é deliberada/)).toBeTruthy();
  });

  it("falha ao ler: mostra o erro, nunca finge que está desligado", async () => {
    global.fetch = vi.fn(async () => ({ ok: false, status: 500, json: async () => ({}) }) as Response) as unknown as typeof fetch;
    render(<ParecerNoPortal token="tk" />);
    expect((await screen.findByRole("alert")).textContent).toMatch(/Não foi possível/);
    expect(screen.queryByRole("radio")).toBeNull();
  });
});
