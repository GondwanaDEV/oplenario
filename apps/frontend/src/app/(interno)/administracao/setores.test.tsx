import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { Setores } from "./setores";

// O bloco "Setores" da administração (ADR-0020, Eixo 1): criar, renomear, desativar e dizer quem é de cada setor. As
// pessoas vêm das opções de destino dos comunicados; quem já é do setor e não está nelas continua marcado.

type Chamada = { metodo: string; url: string; corpo: unknown };

function mockar(estado: { setores: unknown[] }, extra: Record<string, { status?: number; corpo?: unknown }> = {}) {
  const chamadas: Chamada[] = [];
  global.fetch = vi.fn(async (url: string, init?: RequestInit) => {
    const c = { metodo: init?.method ?? "GET", url: String(url), corpo: init?.body ? JSON.parse(String(init.body)) : undefined };
    chamadas.push(c);
    const r = extra[`${c.metodo} ${c.url}`];
    if (r) {
      const status = r.status ?? 200;
      return { ok: status < 300, status, json: async () => r.corpo ?? {} } as Response;
    }
    if (c.metodo === "GET" && c.url === "/api/administracao/setores") return { ok: true, status: 200, json: async () => ({ setores: estado.setores }) } as Response;
    if (c.metodo === "GET" && c.url === "/api/meu/comunicados/destinos") {
      return {
        ok: true, status: 200,
        json: async () => ({ "pode-enviar-a-grupos": true, setores: [], comissoes: [], vereadores: [],
          pessoas: [{ "identidade-id": "i1", nome: "Rita Campos" }, { "identidade-id": "i2", nome: "Ana Lima" }] }),
      } as Response;
    }
    if (c.metodo !== "GET") return { ok: true, status: c.metodo === "POST" ? 201 : 200, json: async () => ({}) } as Response;
    return { ok: false, status: 404, json: async () => ({}) } as Response;
  }) as unknown as typeof fetch;
  return chamadas;
}

const juridico = { id: "s1", nome: "Jurídico", ativo: true, membros: [{ "identidade-id": "i9", nome: "Lúcia Prado" }] };

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe("Setores", () => {
  it("lista os setores (ativos primeiro), com quem é de cada um, e diz que setor não é permissão", async () => {
    mockar({ setores: [{ id: "s2", nome: "Arquivo", ativo: false, membros: [] }, juridico] });
    render(<Setores token="tok" />);
    const lista = await screen.findByRole("list", { name: "Setores da Casa" });
    const itens = within(lista).getAllByRole("listitem");
    expect(within(itens[0]).getByText("Jurídico")).toBeTruthy();
    expect(within(itens[0]).getByText("1 pessoa: Lúcia Prado")).toBeTruthy();
    expect(within(itens[1]).getByText("Desativado")).toBeTruthy();
    expect(within(itens[1]).getByText("Ninguém ainda")).toBeTruthy();
    expect(screen.getByText(/O setor\s+não dá permissão no sistema/)).toBeTruthy();
  });

  it("cria um setor e recarrega a lista", async () => {
    const estado = { setores: [] as unknown[] };
    const chamadas = mockar(estado);
    render(<Setores token="tok" />);
    expect(await screen.findByText("Nenhum setor ainda. Crie o primeiro acima.")).toBeTruthy();
    fireEvent.change(screen.getByLabelText("Nome do novo setor"), { target: { value: "  Protocolo " } });
    estado.setores = [{ id: "s3", nome: "Protocolo", ativo: true, membros: [] }];
    fireEvent.click(screen.getByRole("button", { name: "Criar setor" }));
    expect((await screen.findByRole("status")).textContent).toMatch(/Setor Protocolo criado/);
    expect(await screen.findByText("Protocolo")).toBeTruthy();
    expect(chamadas.find((c) => c.metodo === "POST")).toEqual({ metodo: "POST", url: "/api/administracao/setores", corpo: { nome: "Protocolo" } });
  });

  it("nome vazio e nome repetido são ditos sem sair da tela", async () => {
    mockar({ setores: [] }, { "POST /api/administracao/setores": { status: 409 } });
    render(<Setores token="tok" />);
    await screen.findByText(/Nenhum setor ainda/);
    fireEvent.click(screen.getByRole("button", { name: "Criar setor" }));
    expect((await screen.findByRole("alert")).textContent).toMatch(/Escreva o nome do setor/);
    fireEvent.change(screen.getByLabelText("Nome do novo setor"), { target: { value: "Jurídico" } });
    fireEvent.click(screen.getByRole("button", { name: "Criar setor" }));
    await waitFor(() => expect(screen.getByRole("alert").textContent).toBe("Já existe um setor com esse nome nesta Casa."));
  });

  it("renomear e desativar vão no PUT do setor", async () => {
    const chamadas = mockar({ setores: [juridico] });
    render(<Setores token="tok" />);
    fireEvent.click(await screen.findByRole("button", { name: "Renomear o setor Jurídico" }));
    fireEvent.change(screen.getByLabelText("Novo nome do setor Jurídico"), { target: { value: "Procuradoria" } });
    fireEvent.click(screen.getByRole("button", { name: "Salvar nome" }));
    expect((await screen.findByRole("status")).textContent).toMatch(/renomeado para Procuradoria/);
    fireEvent.click(screen.getByRole("button", { name: "Desativar o setor Jurídico" }));
    await waitFor(() => expect(screen.getByRole("status").textContent).toMatch(/desativado/));
    expect(chamadas.filter((c) => c.metodo === "PUT")).toEqual([
      { metodo: "PUT", url: "/api/administracao/setores/s1", corpo: { nome: "Procuradoria", ativo: true } },
      { metodo: "PUT", url: "/api/administracao/setores/s1", corpo: { nome: "Jurídico", ativo: false } },
    ]);
  });

  it("pessoas: a Casa toda + quem já é do setor (mesmo fora da lista), filtro por nome, e a lotação inteira no PUT", async () => {
    const chamadas = mockar({ setores: [juridico] });
    render(<Setores token="tok" />);
    fireEvent.click(await screen.findByRole("button", { name: "Pessoas do setor Jurídico" }));
    const grupo = await screen.findByRole("group", { name: "Quem é do setor Jurídico" });
    await waitFor(() => expect(within(grupo).getAllByRole("checkbox")).toHaveLength(3));
    expect((within(grupo).getByLabelText("Lúcia Prado") as HTMLInputElement).checked).toBe(true);
    fireEvent.change(within(grupo).getByLabelText("Filtrar por nome"), { target: { value: "rita" } });
    expect(within(grupo).getAllByRole("checkbox")).toHaveLength(1);
    fireEvent.click(within(grupo).getByLabelText("Rita Campos"));
    fireEvent.click(within(grupo).getByRole("button", { name: "Salvar (2 pessoas)" }));
    expect((await screen.findByRole("status")).textContent).toBe("Pessoas do setor Jurídico salvas: 2 pessoas.");
    expect(chamadas.find((c) => c.url.endsWith("/membros"))).toEqual({
      metodo: "PUT", url: "/api/administracao/setores/s1/membros", corpo: { identidades: ["i9", "i1"] },
    });
  });
});
