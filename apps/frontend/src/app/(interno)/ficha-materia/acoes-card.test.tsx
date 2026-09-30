import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { AcoesCard } from "./acoes-card";
import type { ProposicaoDetalheOut } from "@/lib/contrato-legislativo.gen";

const proposicaoBase: ProposicaoDetalheOut = {
  id: "p1",
  tipo: "projeto_lei",
  ano: 2026,
  sequencial: 1,
  urnLex: "urn:x",
  ementa: "Ementa",
  estado: "em_comissoes",
  aprovada: false,
  lockVersion: 0,
  atualizadoEm: "2026-01-01T00:00:00Z",
};

describe("AcoesCard", () => {
  afterEach(() => cleanup());

  it("renderiza os 2 botões que seguem inertes (aria-disabled) + EmBreve honesto — sem o antigo 'Distribuir a comissão'", () => {
    render(<AcoesCard proposicao={proposicaoBase} token="tok" papeis={["secretario"]} />);
    expect(screen.getByText("Ações")).toBeTruthy();
    for (const rotulo of ["Incluir na pauta", "Gerar ficha PDF"]) {
      const btn = screen.getByRole("button", { name: rotulo });
      expect(btn).toBeTruthy();
      expect(btn.getAttribute("aria-disabled")).toBe("true");
      expect((btn as HTMLButtonElement).disabled).toBe(true);
      // aria-describedby precisa estar no próprio botão (foco/leitura de AT) — não no <div> wrapper, que
      // não é exposto por leitores de tela (achado do review).
      expect(btn.getAttribute("aria-describedby")).toBe("ficha-acoes-embreve");
    }
    expect(screen.queryByRole("button", { name: "Distribuir a comissão" })).toBeNull();
    expect(screen.getByRole("status")).toBeTruthy();
    // o motivo do EmBreve já não fala em distribuição: ela agora existe
    expect(screen.getByRole("status").textContent).not.toMatch(/distribu/i);
  });

  it("'Encaminhar às comissões' é REAL (habilitado) e só aparece para a secretaria", () => {
    render(<AcoesCard proposicao={proposicaoBase} token="tok" papeis={["secretario"]} />);
    const btn = screen.getByRole("button", { name: "Encaminhar às comissões" }) as HTMLButtonElement;
    expect(btn.disabled).toBe(false);
    expect(btn.getAttribute("aria-disabled")).toBeNull();
    cleanup();
    render(<AcoesCard proposicao={proposicaoBase} token="tok" papeis={["vereador"]} />);
    expect(screen.queryByRole("button", { name: "Encaminhar às comissões" })).toBeNull();
  });

  it("o diálogo encaminha às comissões escolhidas (com relator) e avisa a ficha para recarregar", async () => {
    const chamadas: Array<[string, RequestInit | undefined]> = [];
    global.fetch = vi.fn(async (url: string, init?: RequestInit) => {
      chamadas.push([url, init]);
      const m = init?.method ?? "GET";
      if (m === "GET" && url === "/api/legislativo/comissoes")
        return { ok: true, status: 200, json: async () => ({ comissoes: [{ id: "c1", nome: "Comissão de Justiça" }, { id: "c2", nome: "Comissão de Finanças" }] }) } as Response;
      if (m === "GET" && url === "/api/cadastros/vereadores")
        return { ok: true, status: 200, json: async () => ({ vereadores: [{ id: "v1", nome: "Helena Past", "nome-parlamentar": "Helena", "com-acesso": true }] }) } as Response;
      if (m === "POST" && url === "/api/legislativo/proposicoes/p1/pareceres-de-comissao")
        return { ok: true, status: 201, json: async () => ({ pareceres: [
          { id: "pa1", "comissao-id": "c1", "comissao-nome": "Comissão de Justiça", "relator-id": "v1", "relator-nome": "Helena", estado: "em_elaboracao", "ja-existia": false },
          { id: "pa2", "comissao-id": "c2", "comissao-nome": "Comissão de Finanças", "relator-id": null, "relator-nome": null, estado: "em_elaboracao", "ja-existia": true },
        ] }) } as Response;
      return { ok: false, status: 404, json: async () => ({}) } as Response;
    }) as unknown as typeof fetch;
    const onMudou = vi.fn();
    render(<AcoesCard proposicao={proposicaoBase} token="tk" papeis={["secretario"]} onMudou={onMudou} />);
    fireEvent.click(screen.getByRole("button", { name: "Encaminhar às comissões" }));
    const dialogo = await screen.findByRole("dialog", { name: "Encaminhar às comissões" });
    fireEvent.click(await within(dialogo).findByLabelText("Comissão de Justiça"));
    fireEvent.click(within(dialogo).getByLabelText("Comissão de Finanças"));
    const seletor = await within(dialogo).findByLabelText(/Relator de Comissão de Justiça/);
    await waitFor(() => expect((seletor as HTMLSelectElement).disabled).toBe(false));
    fireEvent.change(seletor, { target: { value: "v1" } });
    fireEvent.click(within(dialogo).getByRole("button", { name: "Encaminhar a 2 comissões" }));
    await waitFor(() => expect(onMudou).toHaveBeenCalledTimes(1));
    const post = chamadas.find(([u, i]) => i?.method === "POST" && u.endsWith("/pareceres-de-comissao"))!;
    expect(JSON.parse(String(post[1]!.body))).toEqual({
      comissoes: [{ "comissao-id": "c1", "relator-id": "v1" }, { "comissao-id": "c2", "relator-id": null }],
    });
    // o resultado diz o que abriu e o que já existia — nada duplicado
    const status = await within(dialogo).findByRole("status");
    expect(status.textContent).toMatch(/Parecer aberto em Comissão de Justiça\./);
    expect(status.textContent).toMatch(/Comissão de Finanças já tinha parecer em andamento/);
    fireEvent.click(within(dialogo).getByRole("button", { name: "Fechar" }));
    expect(screen.queryByRole("dialog")).toBeNull();
  });

  it("o diálogo mostra o erro do servidor (403/409) e não fecha; Esc fecha", async () => {
    global.fetch = vi.fn(async (url: string, init?: RequestInit) => {
      const m = init?.method ?? "GET";
      if (m === "GET" && url === "/api/legislativo/comissoes")
        return { ok: true, status: 200, json: async () => ({ comissoes: [{ id: "c1", nome: "Comissão de Justiça" }] }) } as Response;
      if (m === "GET") return { ok: true, status: 200, json: async () => ({ vereadores: [] }) } as Response;
      return { ok: false, status: 409, json: async () => ({}) } as Response;
    }) as unknown as typeof fetch;
    render(<AcoesCard proposicao={proposicaoBase} token="tk" papeis={["secretario"]} />);
    fireEvent.click(screen.getByRole("button", { name: "Encaminhar às comissões" }));
    const dialogo = await screen.findByRole("dialog");
    fireEvent.click(await within(dialogo).findByLabelText("Comissão de Justiça"));
    fireEvent.click(within(dialogo).getByRole("button", { name: "Encaminhar" }));
    expect((await within(dialogo).findByRole("alert")).textContent).toMatch(/estado da matéria mudou/);
    expect(screen.getByRole("dialog")).toBeTruthy();
    fireEvent.keyDown(dialogo, { key: "Escape" });
    expect(screen.queryByRole("dialog")).toBeNull();
  });

  it("se as comissões não carregam, diz isso — nunca uma lista vazia fingindo que a Casa não tem comissões", async () => {
    global.fetch = vi.fn(async (url: string) => {
      if (url === "/api/legislativo/comissoes") return { ok: false, status: 500, json: async () => ({}) } as Response;
      return { ok: true, status: 200, json: async () => ({ vereadores: [] }) } as Response;
    }) as unknown as typeof fetch;
    render(<AcoesCard proposicao={proposicaoBase} token="tk" papeis={["secretario"]} />);
    fireEvent.click(screen.getByRole("button", { name: "Encaminhar às comissões" }));
    expect((await screen.findByRole("alert")).textContent).toMatch(/Não foi possível/);
    expect(screen.queryByText(/ainda não tem comissões cadastradas/)).toBeNull();
    expect((screen.getByRole("button", { name: "Encaminhar" }) as HTMLButtonElement).disabled).toBe(true);
  });

  it("aprovada === false -> sem link 'Ver pós-aprovação'", () => {
    render(<AcoesCard proposicao={proposicaoBase} token="tok" />);
    expect(screen.queryByRole("link", { name: /ver pós-aprovação/i })).toBeNull();
  });

  it("aprovada === true -> mostra o link 'Ver pós-aprovação' com o token preservado", () => {
    render(<AcoesCard proposicao={{ ...proposicaoBase, aprovada: true }} token="tok-de-teste" />);
    const link = screen.getByRole("link", { name: /ver pós-aprovação/i });
    expect(link.getAttribute("href")).toBe("/pos-aprovacao/p1?token=tok-de-teste");
  });

  it("aprovada === true mas estado ainda é texto livre não-'aprovada' -> link continua aparecendo (o gate é o booleano, não o rótulo)", () => {
    render(
      <AcoesCard
        proposicao={{ ...proposicaoBase, estado: "aguardando_promulgacao", aprovada: true }}
        token="tok"
      />,
    );
    expect(screen.getByRole("link", { name: /ver pós-aprovação/i })).toBeTruthy();
  });
});
