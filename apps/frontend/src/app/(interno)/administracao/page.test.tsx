import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import PaginaAdministracao from "./page";
import { AuthProvider } from "@/lib/auth";
import { TemaProvider } from "@/lib/tema";

// A área do administrador da Casa (ADR-0005). Mesma disciplina dos demais page.test.tsx: não mocka os hooks —
// mocka `global.fetch` e deixa useVereadores / useConcederAcesso reais rodarem. Tokens de dev (modo test:
// usePapeis lê os papéis DO TOKEN). O 1º administrador de uma Casa nasce SÓ com admin_ente (ADR-0016).
const TOKEN_ADMIN = '{"sub":"u","papeis":["admin_ente"]}';
const TOKEN_SECRETARIA = '{"sub":"u","papeis":["secretario"]}';

const listaFake = {
  vereadores: [
    { id: "v1", nome: "Helena Past", "nome-parlamentar": null, partido: "PT", "estado-mandato": "vigente", "cargo-mesa": null, "com-acesso": false },
    { id: "v2", nome: "Rafael Melo", "nome-parlamentar": "Rafa", partido: "PSDB", "estado-mandato": "licenciado", "cargo-mesa": null, "com-acesso": false },
    { id: "v3", nome: "Sônia Lima", "nome-parlamentar": null, partido: "PDT", "estado-mandato": "vigente", "cargo-mesa": null, "com-acesso": true },
  ],
};

function montar(token: string) {
  return render(
    <AuthProvider tokenQuery={token}>
      <TemaProvider>
        <PaginaAdministracao />
      </TemaProvider>
    </AuthProvider>,
  );
}

const agente = (ligado: boolean) => ({
  itens: [{ agente: "conferencia-normativa", titulo: "Conferência das proposições contra a LOM e o Regimento",
    descricao: "A cada proposição protocolada, a IA lê o texto…", classes: ["leitura", "rascunho"], ligado,
    "ligado-em": ligado ? "2026-09-27T12:00:00Z" : null }],
});

const destinosFake = { "pode-enviar-a-grupos": true, setores: [], comissoes: [], vereadores: [], pessoas: [] };

function fetchMock(opts: { lista500?: boolean; identidadeVinculada409?: boolean } = {}) {
  return vi.fn(async (url: string, init?: RequestInit) => {
    const method = init?.method ?? "GET";
    if (method === "GET" && url === "/api/identidade/agentes-institucionais") {
      return { ok: true, status: 200, json: async () => agente(false) } as Response;
    }
    if (method === "PUT" && url === "/api/identidade/agentes-institucionais/conferencia-normativa/concessao") {
      return { ok: true, status: 200, json: async () => agente(true) } as Response;
    }
    // ADR-0020: o bloco "Setores" carrega os setores e as pessoas da Casa (as opções de destino dos comunicados)
    if (method === "GET" && url === "/api/administracao/setores") {
      return { ok: true, status: 200, json: async () => ({ setores: [] }) } as Response;
    }
    if (method === "GET" && url === "/api/meu/comunicados/destinos") {
      return { ok: true, status: 200, json: async () => destinosFake } as Response;
    }
    if (method === "GET" && url === "/api/meu/identidade") {
      return { ok: true, status: 200, json: async () => ({ nome: "Ana Moreira", papeis: ["admin_ente"] }) } as Response;
    }
    if (method === "GET" && url === "/api/cadastros/vereadores") {
      if (opts.lista500) return { ok: false, status: 500 } as Response;
      return { ok: true, status: 200, json: async () => listaFake } as Response;
    }
    if (method === "POST" && url === "/api/identidade/identidades") {
      return { ok: true, status: 201, json: async () => ({ "identidade-id": "id-9" }) } as Response;
    }
    if (method === "PATCH" && /\/identidade$/.test(url)) {
      if (opts.identidadeVinculada409) {
        return {
          ok: false, status: 409,
          json: async () => ({ erro: "identidade ja vinculada a outro vereador nesta Casa" }),
        } as Response;
      }
      return { ok: true, status: 200, json: async () => ({ id: "v1", "identidade-id": "id-9" }) } as Response;
    }
    if (method === "POST" && url === "/api/identidade/acessos") {
      return { ok: true, status: 201, json: async () => ({ "vinculo-id": "vin-1", convite: "enviado" }) } as Response;
    }
    return { ok: false, status: 404 } as Response;
  });
}

async function abrirFormDe(nome: string) {
  fireEvent.click(await screen.findByRole("button", { name: `Conceder acesso a ${nome}` }));
  return screen.findByRole("form", { name: /^conceder acesso$/i });
}

function preencherESubmeter(form: HTMLElement) {
  fireEvent.change(within(form).getByLabelText(/^cpf/i), { target: { value: "529.982.247-25" } });
  fireEvent.change(within(form).getByLabelText(/e-mail institucional/i), { target: { value: "helena@camara.local" } });
  fireEvent.submit(form);
}

describe("Área do administrador da Casa (/administracao)", () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it("sem o papel admin_ente (ex.: secretaria) a área mostra 'Acesso restrito' e não carrega nada", async () => {
    const f = fetchMock();
    global.fetch = f as unknown as typeof fetch;
    montar(TOKEN_SECRETARIA);
    expect(await screen.findByText("Esta área é do administrador da Casa.")).toBeTruthy();
    expect(f.mock.calls.some(([url]) => url === "/api/cadastros/vereadores")).toBe(false);
  });

  it("lista os vereadores com o nome de exibição, o partido e o estado do mandato", async () => {
    global.fetch = fetchMock() as unknown as typeof fetch;
    montar(TOKEN_ADMIN);
    expect(await screen.findByRole("heading", { name: "Acessos dos vereadores" })).toBeTruthy();
    const lista = await screen.findByRole("list", { name: "Vereadores" });
    expect(within(lista).getByText("Helena Past")).toBeTruthy();
    expect(within(lista).getByText("Rafa")).toBeTruthy(); // nome parlamentar vence o civil
    expect(within(lista).getByText("PT · Mandato ativo")).toBeTruthy();
    expect(within(lista).getByText("PSDB · Licença")).toBeTruthy();
    // o atalho vive na seção da administração (a nav também tem "IA da Casa" para o admin_ente)
    const outras = screen.getByRole("region", { name: "Outras áreas da administração" });
    expect(within(outras).getByRole("link", { name: "IA da Casa" })).toBeTruthy();
  });

  it("Conceder acesso: os 3 passos NA ORDEM (acesso por último), o form fecha e a confirmação aparece", async () => {
    const f = fetchMock();
    global.fetch = f as unknown as typeof fetch;
    montar(TOKEN_ADMIN);

    const form = await abrirFormDe("Helena Past");
    // o nome de quem recebe é exibido, não pedido
    expect(within(form).getByText("Helena Past")).toBeTruthy();
    preencherESubmeter(form);

    await waitFor(() => expect(screen.getByRole("status").textContent).toMatch(/Acesso concedido a Helena Past/));
    const mutacoes = f.mock.calls
      .map(([url, init]) => [url, (init as RequestInit | undefined)?.method])
      .filter(([, metodo]) => metodo && metodo !== "GET");
    expect(mutacoes).toEqual([
      ["/api/identidade/identidades", "POST"],
      ["/api/cadastros/vereadores/v1/identidade", "PATCH"],
      ["/api/identidade/acessos", "POST"],
    ]);
    expect(screen.queryByRole("form", { name: /^conceder acesso$/i })).toBeNull();
  });

  it("Conceder acesso: 409 no passo 2 aparece como alerta, o passo 3 nunca dispara e o form segue aberto", async () => {
    const f = fetchMock({ identidadeVinculada409: true });
    global.fetch = f as unknown as typeof fetch;
    montar(TOKEN_ADMIN);

    const form = await abrirFormDe("Helena Past");
    preencherESubmeter(form);

    await waitFor(() => expect(screen.getByText(/identidade ja vinculada a outro vereador/i)).toBeTruthy());
    expect(f.mock.calls.some(([url]) => url === "/api/identidade/acessos")).toBe(false);
    expect(screen.getByRole("form", { name: /^conceder acesso$/i })).toBeTruthy();
    expect(screen.queryByRole("status")).toBeNull();
  });

  it("se a lista não carregar, diz isso — nunca uma lista vazia fingindo que não há vereadores", async () => {
    global.fetch = fetchMock({ lista500: true }) as unknown as typeof fetch;
    montar(TOKEN_ADMIN);
    expect(await screen.findByText(/Não foi possível carregar os vereadores/)).toBeTruthy();
    expect(screen.queryByText(/Nenhum vereador cadastrado/)).toBeNull();
  });

  it("o administrador liga a conferência automática daqui — o painel não fica preso na tela da secretaria", async () => {
    const f = fetchMock();
    global.fetch = f as unknown as typeof fetch;
    montar(TOKEN_ADMIN);
    expect(await screen.findByRole("heading", { name: "Conferência automática" })).toBeTruthy();
    fireEvent.click(await screen.findByRole("button", { name: "Ligar a conferência" }));
    expect(await screen.findByRole("button", { name: "Desligar" })).toBeTruthy();
    expect(f.mock.calls.some(([u, i]) => String(u).endsWith("/concessao") && (i as RequestInit)?.method === "PUT")).toBe(true);
  });

  it("quem já tem acesso aparece marcado e sem o botão — conceder de novo só daria conflito", async () => {
    global.fetch = fetchMock() as unknown as typeof fetch;
    montar(TOKEN_ADMIN);
    const lista = await screen.findByRole("list", { name: "Vereadores" });
    const sonia = within(lista).getByText("Sônia Lima").closest("li")!;
    expect(within(sonia).getByText("Acesso concedido")).toBeTruthy();
    expect(within(sonia).queryByRole("button", { name: /conceder acesso/i })).toBeNull();
    expect(screen.getByRole("button", { name: "Conceder acesso a Helena Past" })).toBeTruthy();
  });
  it("dá ao controle interno o acesso à trilha: identidade e então vínculo de SERVIDOR com o papel auditor", async () => {
    const f = fetchMock();
    global.fetch = f as unknown as typeof fetch;
    montar(TOKEN_ADMIN);
    fireEvent.click(await screen.findByRole("button", { name: "Dar acesso ao controle interno" }));
    const form = await screen.findByRole("form", { name: "Dar acesso ao controle interno" });
    fireEvent.change(within(form).getByLabelText(/nome completo/i), { target: { value: "Renata Costa" } });
    fireEvent.change(within(form).getByLabelText(/^cpf/i), { target: { value: "529.982.247-25" } });
    fireEvent.change(within(form).getByLabelText(/e-mail institucional/i), { target: { value: "renata@camara.local" } });
    fireEvent.submit(form);
    await waitFor(() => expect(screen.getByRole("status").textContent).toMatch(/Acesso à trilha concedido a Renata Costa/));
    const mutacoes = f.mock.calls.filter(([, init]) => (init as RequestInit | undefined)?.method === "POST");
    expect(mutacoes.map(([url]) => url)).toEqual(["/api/identidade/identidades", "/api/identidade/acessos"]);
    expect(JSON.parse(String((mutacoes[1][1] as RequestInit).body))).toEqual({
      "identidade-id": "id-9", tipo: "servidor", papeis: ["auditor"], email: "renata@camara.local",
    });
    const outras = screen.getByRole("region", { name: "Outras áreas da administração" });
    expect(within(outras).getByRole("link", { name: "Trilha de auditoria" })).toBeTruthy();
  });
  it("dá acesso ao jurídico: identidade e então vínculo de SERVIDOR com papel juridico, qualificação e OAB (ADR-0019)", async () => {
    const f = fetchMock();
    global.fetch = f as unknown as typeof fetch;
    montar(TOKEN_ADMIN);
    expect(await screen.findByRole("heading", { name: "Jurídico da Casa" })).toBeTruthy();
    fireEvent.click(await screen.findByRole("button", { name: "Dar acesso ao jurídico" }));
    const form = await screen.findByRole("form", { name: "Dar acesso ao jurídico" });
    fireEvent.change(within(form).getByLabelText(/nome completo/i), { target: { value: "Lúcia Prado" } });
    fireEvent.change(within(form).getByLabelText(/^cpf/i), { target: { value: "529.982.247-25" } });
    fireEvent.change(within(form).getByLabelText(/e-mail institucional/i), { target: { value: "lucia@camara.local" } });
    fireEvent.change(within(form).getByLabelText(/qualificação/i), { target: { value: "contratado" } });
    fireEvent.change(within(form).getByLabelText(/^oab/i), { target: { value: "CE 12345" } });
    fireEvent.submit(form);
    await waitFor(() => expect(screen.getByRole("status").textContent).toMatch(/Acesso ao jurídico concedido a Lúcia Prado/));
    const mutacoes = f.mock.calls.filter(([, init]) => (init as RequestInit | undefined)?.method === "POST");
    expect(mutacoes.map(([url]) => url)).toEqual(["/api/identidade/identidades", "/api/identidade/acessos"]);
    expect(JSON.parse(String((mutacoes[1][1] as RequestInit).body))).toEqual({
      "identidade-id": "id-9", tipo: "servidor", papeis: ["juridico"], email: "lucia@camara.local",
      qualificacao: "contratado", oab: "CE 12345",
    });
  });

  it("jurídico: sem qualificação e com OAB inválida, nada é enviado e cada campo diz o que falta", async () => {
    const f = fetchMock();
    global.fetch = f as unknown as typeof fetch;
    montar(TOKEN_ADMIN);
    fireEvent.click(await screen.findByRole("button", { name: "Dar acesso ao jurídico" }));
    const form = await screen.findByRole("form", { name: "Dar acesso ao jurídico" });
    fireEvent.change(within(form).getByLabelText(/nome completo/i), { target: { value: "Lúcia Prado" } });
    fireEvent.change(within(form).getByLabelText(/^cpf/i), { target: { value: "529.982.247-25" } });
    fireEvent.change(within(form).getByLabelText(/e-mail institucional/i), { target: { value: "lucia@camara.local" } });
    fireEvent.change(within(form).getByLabelText(/^oab/i), { target: { value: "12345" } });
    fireEvent.submit(form);
    expect(await within(form).findByText("Escolha a qualificação.")).toBeTruthy();
    expect(within(form).getByText(/OAB no formato UF e número/)).toBeTruthy();
    expect(f.mock.calls.some(([url]) => url === "/api/identidade/identidades" || url === "/api/identidade/acessos")).toBe(false);
  });

  it("jurídico: 400 do backend no acesso aparece como alerta e o form segue aberto", async () => {
    const base = fetchMock();
    global.fetch = vi.fn(async (url: string, init?: RequestInit) => {
      if (url === "/api/identidade/acessos") {
        return { ok: false, status: 400, json: async () => ({ erro: "oab invalida para o vinculo juridico" }) } as Response;
      }
      return base(url, init);
    }) as unknown as typeof fetch;
    montar(TOKEN_ADMIN);
    fireEvent.click(await screen.findByRole("button", { name: "Dar acesso ao jurídico" }));
    const form = await screen.findByRole("form", { name: "Dar acesso ao jurídico" });
    fireEvent.change(within(form).getByLabelText(/nome completo/i), { target: { value: "Lúcia Prado" } });
    fireEvent.change(within(form).getByLabelText(/^cpf/i), { target: { value: "529.982.247-25" } });
    fireEvent.change(within(form).getByLabelText(/e-mail institucional/i), { target: { value: "lucia@camara.local" } });
    fireEvent.change(within(form).getByLabelText(/qualificação/i), { target: { value: "efetivo" } });
    fireEvent.change(within(form).getByLabelText(/^oab/i), { target: { value: "CE 12345" } });
    fireEvent.submit(form);
    await waitFor(() => expect(screen.getByText(/oab invalida para o vinculo juridico/)).toBeTruthy());
    expect(screen.getByRole("form", { name: "Dar acesso ao jurídico" })).toBeTruthy();
    expect(screen.queryByRole("status")).toBeNull();
  });
});
